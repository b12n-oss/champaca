;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.loader
  "Class loading: a class from the class path or the JDK's runtime image
  loaded with its superclass and interfaces, linked and registered with the
  Scheme runtime (or restored from the library cache, or registered from a
  cached generation), the synthetic classes behind lambdas, and the classes
  a loader defines from bytes at run time. ensure-loaded! is installed in
  champaca.state's forward hook when this namespace loads, so the linker
  and the library cache can load a class missing from the table."
  (:require [clojure.string :as str]
            [champaca.cachedir :refer [cache-origin load-unit! record!]]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [bytes-fp jar-path refuse-outside-layer!]]
            [champaca.libcache :refer [library-cache-keys restore-defined-class! restore-user-class!
                                       uncheckable-dep user-deps]]
            [champaca.link :refer [check-definition! class-map dotted final-sealed? link linkage-error
                                   normalize note-parsed! registration-text thaw!]]
            [champaca.state :refer [state trace? timed scheme! *reads* ensure-loaded-fn]]))

(declare ensure-loaded!)

;; --- loading -----------------------------------------------------------------------

;; The classes whose superclass and interfaces are being loaded, on this
;; thread: one among its own supertypes is the JVM's ClassCircularityError,
;; where loading it again recursed until the heap ran out
(def ^:private ^:dynamic *loading-supertypes* #{})

(defn- load-supertypes!
  "ensure-loaded! of info's superclass and interfaces, then check-definition!;
  one already being loaded on this thread is ClassCircularityError."
  [info]
  (let [n (:name info)]
    (when (contains? *loading-supertypes* n)
      (throw (linkage-error n "java/lang/ClassCircularityError" (dotted n))))
    (binding [*loading-supertypes* (conj *loading-supertypes* n)]
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep)))
    (check-definition! info)))

(defn- register-lazy-class!
  "A class registered by an earlier run: its class table entry came from the
  cache, and its registration is one unit of a cached generation. A
  dependency that isn't there leaves cname lazy, not half-registered."
  [cname]
  (let [info (thaw! cname)]
    (swap! state update :lazy disj cname)
    (try
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (catch Exception e
        (swap! state update :lazy conj cname)
        (throw e)))
    (or (load-unit! (:cached-class info))
        (let [text (registration-text info)]
          (record! (cache-origin info) [cname] {:text text})
          (scheme! text :interpret)))))

(defn- load-new-class!
  "A class not in the table: restored from the library cache, else parsed,
  its supertypes loaded, linked and registered. A classpath class's link and
  registration record what they read."
  [cname]
  (when-not (restore-user-class! cname)
    (when-let [info (timed :parse #(normalize cname))]
      (load-supertypes! info)
      (when-not (get-in @state [:classes cname])
        (let [reads (when (= :user (:origin info)) (volatile! #{}))
              linked (binding [*reads* reads] (timed :link #(link info)))]
          (swap! state assoc-in [:classes cname] linked)
          (note-parsed! cname)
          (let [text (binding [*reads* reads] (timed :register-text #(registration-text linked)))
                linked (if-let [ks (when reads (library-cache-keys cname @reads))]
                         (get-in (swap! state update-in [:classes cname] merge ks) [:classes cname])
                         linked)]
            (record! (cache-origin linked) [cname] {:text text})
            (timed :register-eval #(scheme! text :interpret))))))))

(defn ensure-loaded! [cname]
  (refuse-outside-layer! cname)
  (cond
    (contains? (:lazy @state) cname) (register-lazy-class! cname)
    (not (get-in @state [:classes cname])) (load-new-class! cname))
  cname)

;; the var, so a REPL redefinition of ensure-loaded! reaches class-info too
(reset! ensure-loaded-fn #'ensure-loaded!)

(defn lambda-class
  "The synthetic class implementing ifaces for lambdas whose abstract
  method keys (\"name(desc)\", the SAM plus any bridges) are sam-keys.
  Slot 0 holds the closure; every SAM key maps to champaca/Lambda.invoke,
  which calls it, and is a public method for reflection (getDeclaredMethods
  lists it, Method.invoke runs it through the vtable). Its vtable takes the interfaces' default methods, so its
  name carries the fingerprints of every classpath interface among them
  and their superinterfaces (user-deps), a loader-defined interface's too: an
  interface that changes between runs gets a class of its own instead of an
  earlier run's cached one. One whose interfaces can't be checked isn't
  cached."
  ([ifaces sam-keys] (lambda-class ifaces sam-keys nil nil))
  ([ifaces sam-keys caller site]
  (run! ensure-loaded! ifaces)
  (let [deps (user-deps ifaces true)
        h (Math/abs (hash (cond-> [ifaces sam-keys] (seq deps) (conj deps) caller (conj site))))
        ;; "$$Lambda" in the name, as the JVM's lambda classes have it:
        ;; frameworks tell a lambda by it (Spring's ClassUtils.isLambdaClass).
        ;; A call site's is caller$$Lambda/0x<n>, a hidden class in the
        ;; caller's package (runtime.ss lambda-site-caller), one per site as
        ;; on the JVM, n from the site and the interfaces so a later run's
        ;; cache finds it; jolt's own (no caller) share one per interface
        n (if caller
            (str caller "$$Lambda/0x" (format "%016x" h))
            (str "champaca/$$Lambda$" (str/replace (first ifaces) "/" "$") "$" h))]
    (if (get-in @state [:classes n])
      (ensure-loaded! n) ; an earlier run's, maybe not registered in this one yet
      (let [info (cond-> {:name n :super "java/lang/Object" :interfaces (vec ifaces) :flags 0x1011 :origin :jdk
                          :fields [{:name "fn" :desc "Ljava/lang/Object;" :flags 0x12 :static false}]
                          ;; for reflection only, public as the JVM's: no code,
                          ;; so the vtable entries below are what runs
                          :methods (mapv (fn [k] (let [i (str/index-of k "(")]
                                                   {:name (subs k 0 i) :desc (subs k i) :flags 0x1 :virtual true}))
                                         sam-keys)}
                   (nil? deps) (assoc :no-cache true))
            linked (reduce #(assoc-in %1 [:vtable %2] "champaca/Lambda.invoke") (link info) sam-keys)]
        (swap! state assoc-in [:classes n] linked)
        (let [text (registration-text linked)]
          ;; a later run trusts what state.img holds, so only a name that
          ;; pins the interfaces goes there (strip-for-cache skips :no-cache)
          (record! (when deps :jdk) [n] {:text text})
          (scheme! text :interpret))))
    ;; the code being compiled names n (user-deps)
    (when-let [r *reads*] (vswap! r conj n))
    n)))

(defn- rename-class
  "The parsed class c under a new name: its own, every constant-pool class
  entry and member reference naming it, and the exception-table types
  parse-code resolved to names. A hidden class keeps the name its bytes carry
  inside, and two hidden classes from the same bytes would otherwise share the
  methods and statics compiled under it. Descriptors keep the old name, and so
  does an array type naming the class ([Lold;), which the JVM resolves to the
  original class too (DefineCheck's hidden self-references row)."
  [c new]
  (let [old (:name c)
        fix-name (fn [s] (if (= s old) new s))
        fix (fn [e] (cond (= :class (:tag e)) (update e :name fix-name)
                          (contains? e :class) (update e :class fix-name)
                          :else e))]
    (-> c
        (assoc :name new)
        (update :cp #(into {} (map (fn [[i e]] [i (fix e)])) %))
        (update :methods (fn [ms] (mapv (fn [m] (cond-> m (:code m) (update-in [:code :handlers] (fn [hs] (mapv #(update % :type fix-name) hs)))))
                                        ms))))))

(def ^:private hidden-counter (atom 0x1000))

(declare defined-jar)

(defn- parse-defined
  "The class file bs parsed, or {:bad text} when it isn't one, or {:version
  text} for an unsupported version. A wrong magic number's text ends with
  the name the caller gave, as HotSpot's, or <Unknown>."
  [bs given]
  (try (cf/parse bs)
       (catch Exception e
         (if-let [f (:class-version (ex-data e))]
           {:version (f (or given "<Unknown>"))}
           {:bad (let [m (or (ex-message e) (str (class e)))]
                   (if (:named (ex-data e)) (str m " " (or given "<Unknown>")) m))}))))

(defn- definition-refusal
  "define-class!'s answer when the parsed class c can't be defined, else nil.
  HotSpot checks the name as it reads it, before the interface and sealed
  checks."
  [c given hidden?]
  (cond
    (:bad c) (str "bad " (:bad c))
    (:version c) (str "link java/lang/UnsupportedClassVersionError|" (:version c))
    (and given (not= given (:name c))) (str "wrong " (:name c))
    (and (pos? (bit-and (:flags c) 0x0200)) (not= "java/lang/Object" (:super c)))
    (str "bad Interfaces must have java.lang.Object as superclass in class file " (or given (:name c)))
    (final-sealed? c) (str "bad PermittedSubclasses attribute in final class file " (or given (:name c)))
    (and (not hidden?) (get-in @state [:classes (:name c)])) (str "dup " (:name c))))

(defn- defined-deps!
  "A defined class n's library cache dependencies from what its link and
  registration read: without checkable ones it isn't cached, and without a
  fingerprint nothing depending on it is. Answers its class map."
  [n reads fp]
  (let [deps (when fp (user-deps (conj @reads n) true))]
    (when fp
      (when-not deps (swap! state update :defined-uncacheable (fnil inc 0)))
      (when (and (not deps) (trace?))
        (binding [*out* *err*] (println "define" n "not cached: it depends on" (uncheckable-dep (conj @reads n) true)))))
    (get-in (swap! state update-in [:classes n]
                   #(if deps (assoc % :deps (dissoc deps n)) (dissoc % :src-fp :src-jar)))
            [:classes n])))

(defn- define-parsed-class!
  "Link and register the parsed class c (renamed first when hidden), as
  ensure-loaded! does for a classpath class: \"ok <name>\", or HotSpot's
  definition checks' error as \"link <class>|<text>\"."
  [c hidden? bs fp dj]
  (try
    (let [c (if hidden? (rename-class c (str (:name c) "/0x" (Long/toHexString (swap! hidden-counter inc)))) c)
          n (:name c)
          info (class-map n c :defined)]
      ;; bytes class-source held for a classpath class of this name won't be parsed
      (swap! state update :dir-bytes dissoc n)
      ;; HotSpot's checks of the definition (load-supertypes!)
      (load-supertypes! info)
      ;; the link and the registration record what they read, which the
      ;; entry depends on; its own fingerprint goes in first: user-deps reads
      ;; it for the class itself, and for a class depending on it
      (let [reads (when fp (volatile! #{}))
            linked (cond-> (binding [*reads* reads] (link info)) fp (assoc :src-fp fp :src-jar dj))]
        ;; the defined bytes are kept for NPE messages and frames, once the class is in
        (swap! state #(-> % (assoc-in [:classes n] linked) (assoc-in [:defined-bytes n] bs)))
        (let [text (binding [*reads* reads] (registration-text linked))
              linked (defined-deps! n reads fp)]
          (record! (cache-origin linked) [n] {:text text})
          (scheme! text :interpret)))
      (str "ok " n))
    (catch Exception e
      (if-let [err (:linkage (ex-data e))]
        (str "link " err "|" (:message (ex-data e)))
        (throw e)))))

(defn- note-generated!
  "Mark class n as generated at run time, so its code compiles with line
  numbers (jit.clj lines?)."
  [n]
  (swap! state update :generated-classes (fnil conj #{}) n))

(defn define-class!
  "ClassLoader.defineClass, Lookup.defineClass and defineHiddenClass, Proxy
  and Unsafe.defineClass0 (natives.ss define-class!): parse, link and
  register a class from the bytes a loader handed over, which cross from
  Scheme as a Latin-1 string since a byte array doesn't. given is the name
  the caller expects, slashed, or nil. Answers \"ok <name>\", \"bad <message>\"
  (not a class file), \"wrong <name in the bytes>\" or \"dup <name>\" (one
  class table here, so a second definition of a name is a duplicate whatever
  its loader), and the Scheme side throws the JVM's error for each. A hidden
  class is named <name>/0x<n>, as HotSpot names one, and renamed inside.
  With cacheable? (a named, non-hidden class from a loader of the program's
  own), the class is kept in the library cache like a jar's, keyed by its
  bytes' fingerprint under the class path's file (defined-jar), and an
  earlier run's entry for the same bytes is restored instead of parsing
  (restore-defined-class!). With generated? (a domain with no code source
  location: bytes made at run time, as a Clojure REPL's eval classes) the
  class gets line numbers (jit.clj lines?) and its own cache file."
  [given text hidden? cacheable? generated?]
  (let [bs (.getBytes ^String text "ISO-8859-1")
        fp (when (and cacheable? given) (timed :fingerprint #(bytes-fp bs)))
        dj (when fp (defined-jar generated?))
        answer (or (when (and fp (not (get-in @state [:classes given])))
                     (restore-defined-class! given bs fp dj))
                   (let [c (parse-defined bs given)]
                     (or (definition-refusal c given hidden?)
                         (do (when generated? (note-generated! (:name c)))
                             (define-parsed-class! c hidden? bs fp dj)))))]
    (when (and generated? (str/starts-with? answer "ok "))
      (note-generated! (subs answer 3)))
    answer))

(defn- defined-jar
  "The library cache's key for the classes a program's own loaders define:
  one file per class path, the bytes having no path of their own. A
  source-file run has its own (\"source:\"), and so do generated classes
  (\"generated:\"), since those compile with line numbers (lines?) and
  other defined classes don't."
  [generated?]
  (let [k (if generated? :generated-jar :defined-jar)]
    (or (get @state k)
        (let [j (str (cond (:source-mode @state) "source:" generated? "generated:" :else "defined:")
                     (str/join ":" (map jar-path (:classpath @state))))]
          (swap! state assoc k j)
          j))))
