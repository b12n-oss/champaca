;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.main
  "Class loading and the JIT hook, plus the command-line entry.

  Classes come from the user classpath first, then from the JDK's own
  runtime image. Loading a class registers it with the Scheme runtime and
  defines every method as a stub; the first call to a stub calls back into
  jit!, which translates that one method and redefines the global in place.
  Native methods are bound by name from resources/champaca/natives.ss.
  That mirrors OpenLDK, which also compiles each method on first call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [bytes-fp class-bytes class-source entry-url
                                        jar-entry? jar-manifest jar-path jdk-resource-bytes
                                        module-resource-bytes module-resource-url
                                        package-info package-module plain-resource-path?
                                        refuse-outside-layer! resource-bytes resource-urls
                                        user-resource-bytes with-manifest-class-paths]]
            [champaca.cachedir :refer [cache-dir cache-origin cache-read-only? cache-unusable!
                                       compile-to-cache? delete-tree! load-unit! record! runtime-files]]
            [champaca.codegen :as cg]
            [champaca.launcher :refer [module-properties launcher-error jvm-options expand-class-path
                                       source-mode-of apply-launcher-options! program-args print-usage!]]
            [champaca.link :refer [check-definition! check-kind check-virtual class-info class-map
                                   direct-target dotted field-index final-sealed? link linkage-error
                                   methods-of normalize overridden? package-private-decl parsed
                                   registration-text resolve-method resolve-static-field thaw!]]
            [champaca.libcache :refer [deps-still-hold! library-cache-keys load-cache! restore-defined-class!
                                       restore-user-class! save-generation! saves-generation! uncheckable-dep
                                       user-deps write-cache!]]
            [champaca.state :refer [state env trace? timed prof-report scheme! scheme-trace! *reads*
                                    ensure-loaded-fn]]))

(declare ensure-loaded! lambda-class)

;; --- resolution ----------------------------------------------------------------------

(defn- inline-candidate
  "The parsed class and method decl.mname(desc), for codegen to inline into a
  method of caller-cname, or nil. Only a method in the caller's own class or
  in the JDK qualifies: a compiled method is cached with its own class, and a
  copy of another user class's code would outlive a change to that class.
  The JDK can't change under a cache, which is keyed by it. A method the
  runtime overrides never qualifies."
  [caller-cname decl mname desc]
  (let [info (class-info decl)]
    ;; only a class already parsed in this run: reading one again from the
    ;; image costs a warm start more than the inlining saves; decided first,
    ;; so a restored class's methods aren't thawed for an inlining that
    ;; can't happen
    (when (or (= decl caller-cname) (and (= :jdk (:origin info)) (:parsed info)))
      (let [im (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (methods-of info)))]
        ;; the class map answers most of it without reading the class file again
        (when (and im (:code im) (<= (or (:code-len im) 0) cg/leaf-limit)
                   (not (overridden? decl im)))
          (when-let [p (if (= decl caller-cname) (parsed decl) (:parsed info))]
            {:cls p :method (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))}))))))

(def ^:private resolver
  {:ensure-loaded #(ensure-loaded! %)
   :package-private-decl package-private-decl
   :inline-candidate inline-candidate
   :direct-target direct-target
   :resolve-static-field resolve-static-field
   :field-index field-index
   :resolve-method resolve-method
   :check-virtual check-virtual
   :check-kind check-kind
   :lambda-class #(lambda-class %1 %2)})

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
  which calls it. Its vtable takes the interfaces' default methods, so its
  name carries the fingerprints of every classpath interface among them
  and their superinterfaces (user-deps), a loader-defined interface's too: an
  interface that changes between runs gets a class of its own instead of an
  earlier run's cached one. One whose interfaces can't be checked isn't
  cached."
  [ifaces sam-keys]
  (run! ensure-loaded! ifaces)
  (let [deps (user-deps ifaces true)
        n (str "champaca/Lambda$" (str/replace (first ifaces) "/" "$") "$"
               (Math/abs (hash (if (empty? deps) [ifaces sam-keys] [ifaces sam-keys deps]))))]
    (if (get-in @state [:classes n])
      (ensure-loaded! n) ; an earlier run's, maybe not registered in this one yet
      (let [info (cond-> {:name n :super "java/lang/Object" :interfaces (vec ifaces) :flags 0x1011 :origin :jdk
                          :fields [{:name "fn" :desc "Ljava/lang/Object;" :flags 0x12 :static false}] :methods []}
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
    n))

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

(defn- define-class!
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
  (restore-defined-class!)."
  [given text hidden? cacheable?]
  (let [bs (.getBytes ^String text "ISO-8859-1")
        fp (when (and cacheable? given) (timed :fingerprint #(bytes-fp bs)))
        dj (when fp (defined-jar))]
    (or (when (and fp (not (get-in @state [:classes given])))
          (restore-defined-class! given bs fp dj))
        (let [c (parse-defined bs given)]
          (or (definition-refusal c given hidden?)
              (define-parsed-class! c hidden? bs fp dj))))))

(defn- defined-jar
  "The library cache's key for the classes a program's own loaders define:
  one file per class path, the bytes having no path of their own. A
  source-file run has its own (\"source:\"), since its defined classes
  compile with line numbers (lines?) and other runs' don't."
  []
  (or (:defined-jar @state)
      (let [j (str (if (:source-mode @state) "source:" "defined:") (str/join ":" (map jar-path (:classpath @state))))]
        (swap! state assoc :defined-jar j)
        j)))

;; --- the JIT -----------------------------------------------------------------------

(defn- lines?
  "Whether cname's code compiles with Chez's inspector information, which is
  what gives its frames line numbers (how-it-works.md). By default a class
  from a classpath directory, a developer's own build, does; a jar's or a
  loader-defined class names its file without a line, as a JDK frame does,
  because the information costs about as much memory as the code itself (a
  warm Spring Boot: 100 of 460 MB live). A source-file run's defined
  classes do too: javac's source launcher defines the program's own classes
  (their cache file is apart, defined-jar). CHAMPACA_LINES=all gives every
  class but the JDK's lines, CHAMPACA_LINES=off none. Every compile path
  asks, cached or not, so a frame's line doesn't depend on the cache."
  [cname info]
  (case (some-> (env "CHAMPACA_LINES") str/lower-case)
    "all" (contains? #{:user :defined} (:origin info))
    "off" false
    (case (:origin info)
      :user (boolean (when-let [src (class-source cname)] (not (jar-entry? (:entry src)))))
      :defined (boolean (:source-mode @state))
      false)))

(defn- compile-method! [cname mname desc]
  (let [p (parsed cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))
        info (assoc (class-info cname) :parsed p)]
    (swap! state update :jitted inc)
    (when (= :user (:origin info)) (swap! state update :jitted-user (fnil inc 0)))
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (let [reads (when (:src-fp info) (volatile! #{}))
          text (binding [*reads* reads] (timed :codegen #(cg/compile-method (:parsed info) m resolver)))
          ;; a library cache entry's dependencies grow with each method
          ;; compiled; a method reading one that can't be checked isn't kept
          origin (if reads
                   (if-let [deps (user-deps (conj @reads cname) (= :defined (:origin info)))]
                     ;; a fingerprint already recorded stays: were a dependency
                     ;; to differ from it, the next run's check must fail
                     (do (swap! state update-in [:classes cname :deps] #(merge-with (fn [old _] old) % (dissoc deps cname)))
                         :user)
                     (do (when (trace?)
                           (binding [*out* *err*]
                             (println "jit" (str cname "." mname desc) "not cached: it reads" (uncheckable-dep (conj @reads cname) (= :defined (:origin info))))))
                         nil))
                   (cache-origin info))
          unit [cname (str mname desc)]
          ;; an instruction naming a class that isn't there throws when it
          ;; runs (codegen): such a method is never cached, so a cache can't
          ;; carry a throw for a class a later run has, and CHAMPACA_BT says so
          missing (seq (map second (re-seq (re-pattern "\\(jthrow-missing \"([^\"]+)\"") text)))
          ;; and for a class whose file isn't a class file (ClassFormatError)
          bad-format (seq (map second (re-seq (re-pattern "\\(jthrow-class-format \"([^\"]+)\"") text)))
          ;; the same for a method its class doesn't have (NoSuchMethodError)
          no-method (seq (map (fn [[_ c n d]] (str c "." n d))
                              (re-seq (re-pattern "\\(jthrow-no-method \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"") text)))
          report (fn [names why what]
                   (when (and names (System/getenv "CHAMPACA_BT"))
                     (binding [*out* *err*]
                       (println (str "champaca: " cname "." mname desc " names " (str/join ", " (distinct names))
                                     ", " why "; that code throws " what)))))
          ;; and for a class the JVM refuses to define (check-definition!)
          refused (seq (map second (re-seq (re-pattern "\\(jthrow-linkage \"([^\"]+)\"") text)))
          ;; and for a call of the wrong kind (IncompatibleClassChangeError)
          icce (seq (map (fn [[_ c n d]] (str c "." n d))
                         (re-seq (re-pattern "\\(jthrow-icce \"[^\"]+\" \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"") text)))
          _ (report missing "not found" "NoClassDefFoundError")
          _ (report no-method "not found" "NoSuchMethodError")
          _ (report bad-format "not a class file" "ClassFormatError")
          _ (report icce "of the other kind" "IncompatibleClassChangeError")
          _ (report refused "refused by its definition's checks" "the JVM's linkage error")
          missing (or missing no-method bad-format icce refused)]
      (cond
        missing (timed :chez-compile #(scheme! text :compile (lines? cname info)))
        ;; compiled once: the same object code runs now and goes in the cache
        (compile-to-cache? origin)
        (let [bv (timed :chez-compile #(do (when (trace?) (scheme-trace! text))
                                           ;; a classpath class's frames keep their line numbers
                                           (s/call "compile-unit" text (lines? cname info))))]
          (record! origin unit {:fasl bv}))
        ;; the text is kept (cheaply) so save-cache! can compile it into a
        ;; generation; without that a jolt program re-jits these every run
        :else
        (do (timed :chez-compile #(scheme! text :compile (lines? cname info)))
            (record! origin unit {:text text :inspect (lines? cname info)}))))))

(defn jit! [cname mname desc]
  (let [info (thaw! cname)
        unit (get (:cached-methods info) (str mname desc))
        ;; a restored class's dependencies are checked before any method of
        ;; it runs, cached or not: compiling an uncached one first would
        ;; record a changed dependency's new fingerprint, and the cached
        ;; ones, compiled against the old, would then pass the check
        held? (or (empty? (:cached-methods info)) (deps-still-hold! cname info))]
    (when-not (and unit held? (load-unit! unit)) ; else compiled by an earlier run
      (compile-method! cname mname desc))))

(defn- stats []
  (let [{:keys [classes lazy jitted jitted-user units-loaded restored]} @state]
    (binding [*out* *err*]
      (println (str "champaca: " (- (count classes) (count lazy)) " classes loaded ("
                    (or restored 0) " from the library cache), " jitted " methods compiled ("
                    (or jitted-user 0) " of classpath classes), " (or units-loaded 0) " cached units loaded"))
      ;; classes loaders of the program's own defined, by what the cache did with them
      (let [{:keys [defined-new defined-changed defined-uncacheable cached-methods-dropped]} @state
            n (fn [k] (or k 0))]
        (when (some pos? (map n [defined-new defined-changed defined-uncacheable cached-methods-dropped]))
          (println (str "champaca: loader-defined classes not from the cache: " (n defined-new) " new, "
                        (n defined-changed) " changed, " (n defined-uncacheable) " of them uncacheable; "
                        (n cached-methods-dropped) " classes' cached methods dropped")))))))

;; The text of a helpful NPE message, from the failing method's bytecode
;; (champaca.npe, loaded on the first message so other runs don't pay for
;; it). The class is parsed again with its local variable names, which a
;; normal parse skips, so the work happens only when a message is asked
;; for; a site always gives the same text, so it is kept.
(defn- npe-message** [site]
  (let [at (str/last-index-of site "@")
        tag (subs site 0 at)
        bci (parse-long (subs site (inc at)))
        paren (str/index-of tag "(")
        dot (str/last-index-of tag "." paren)
        cname (subs tag 0 dot)
        mname (subs tag (inc dot) paren)
        desc (subs tag paren)
        bytes (or (class-bytes cname) (throw (ex-info (str cname " is no longer on the classpath") {:class cname})))
        p (binding [cf/*locals* true] (cf/parse bytes))
        m (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (:methods p))]
    (when (:code m)
      ((requiring-resolve 'champaca.npe/message) (:cp p) m bci))))

;; Whatever goes wrong gives no message, as HotSpot gives none for bytecode
;; its analysis can't follow: getMessage must not throw. CHAMPACA_BT says
;; why on stderr, and CHAMPACA_NPE_STRICT (set by jolt test) rethrows, so a
;; bug in the port fails the suite instead of passing as a missing message.
(defn- npe-message* [site]
  (try (npe-message** site)
       (catch Exception e
         (when (env "CHAMPACA_NPE_STRICT") (throw e))
         (when (env "CHAMPACA_BT")
           (binding [*out* *err*] (println "champaca: no NPE message for" site (ex-message e))))
         nil)))

(defn- npe-message [site]
  (let [cache (:npe-messages @state)]
    (if (contains? cache site)
      (get cache site)
      (let [msg (npe-message* site)]
        (swap! state assoc-in [:npe-messages site] msg)
        msg))))

;; --- stack traces ---------------------------------------------------------------
;; A frame's source file, line and module for StackTraceElement. The file and
;; the LineNumberTable come from the class file, re-read with
;; classfile/*lines* the first time a class is asked (a normal parse skips
;; the table), the module from the image's package index for a JDK class.

;; jdk.internal.vm.annotation.Hidden marks methods the JVM leaves out of
;; stack traces (Thread.runWith, lambda forms). It has no elements, but the
;; method's other annotations may, so their values are skipped by the
;; element_value grammar (JVMS 4.7.16.1).
(declare ann-skip)
(defn- ann-u2 [b i] (+ (* 256 (bit-and (nth b i) 0xff)) (bit-and (nth b (inc i)) 0xff)))
(defn- ann-skip-annotation [b i]
  (let [n (ann-u2 b (+ i 2))]
    (loop [j (+ i 4) k 0] (if (= k n) j (recur (ann-skip b (+ j 2)) (inc k))))))
(defn- ann-skip [b i]
  (case (char (bit-and (nth b i) 0xff))
    (\B \C \D \F \I \J \S \Z \s \c) (+ i 3)
    \e (+ i 5)
    \@ (ann-skip-annotation b (inc i))
    \[ (let [n (ann-u2 b (inc i))] (loop [j (+ i 3) k 0] (if (= k n) j (recur (ann-skip b j) (inc k)))))))
(defn- hidden-method? [cp m]
  (when-let [b (:annotations m)]
    (try (let [n (ann-u2 b 0)]
           (loop [i 2 k 0]
             (cond (= k n) false
                   (= "Ljdk/internal/vm/annotation/Hidden;" (:value (cp (ann-u2 b i)))) true
                   :else (recur (ann-skip-annotation b i) (inc k)))))
         (catch Exception _ false))))

(defn- frame-class [cname]
  (let [cache (:frame-classes @state)]
    (if (contains? cache cname)
      (get cache cname)
      (let [info (try (when-let [bytes (class-bytes cname)]
                        (let [p (binding [cf/*lines* true] (cf/parse bytes))
                              slash (str/last-index-of cname "/")]
                          {:source (:source p)
                           :module (when (= :jdk (:origin (class-info cname)))
                                     (package-module (if slash (subs cname 0 slash) "")))
                           :lines (into {} (for [m (:methods p) :when (:code m)]
                                             [(str (:name m) (:desc m)) (:lines (:code m))]))
                           :hidden (set (for [m (:methods p) :when (hidden-method? (:cp p) m)]
                                          (str (:name m) (:desc m))))}))
                      (catch Exception e
                        (when (env "CHAMPACA_BT")
                          (binding [*out* *err*] (println "champaca: no source lines for" cname (ex-message e))))
                        nil))]
        (swap! state assoc-in [:frame-classes cname] info)
        info))))

(defn- frame-info
  "[file line module hidden] for cname.mname desc at bytecode pc, with \"\"
  and -1 where unknown (pc -1 is a frame whose pc wasn't recorded), hidden
  1 for a method the JVM leaves out of traces: one marked @Hidden, or any
  method of a hidden class (ClassFileParser marks them all), whose name is
  Name/0x<n> here (no identifier starts with a digit)."
  [cname mname desc pc]
  (let [{:keys [source module lines hidden]} (frame-class cname)
        table (get lines (str mname desc))
        line (if (and (seq table) (>= pc 0))
               (or (some (fn [[start l]] (when (<= start pc) l)) (rseq table)) -1)
               -1)]
    [(or source "") line (or module "")
     (if (or (contains? hidden (str mname desc)) (str/includes? cname "/0x")) 1 0)]))

(defn- check-lines-env!
  "CHAMPACA_LINES takes all or off (lines?); anything else is a typo, said
  on stderr."
  []
  (when-let [v (env "CHAMPACA_LINES")]
    (when-not (#{"all" "off"} (str/lower-case v))
      (binding [*out* *err*]
        (println (str "champaca: CHAMPACA_LINES=" v " isn't all or off; using the default, lines for classes from class directories"))))))

(defn- load-cached-runtime!
  "Load the cache's runtime.so, true when it loaded. The cache key covers the
  runtime's source, so a cached runtime.so is always this source compiled. A
  damaged one is discarded with the cache, or over a read-only cache makes
  it unusable."
  [dir so ro]
  (and so (.exists so)
       (try (timed :cache-load-runtime #(s/call "load" (str so))) true
            (catch Exception e
              (if ro
                (cache-unusable! dir (str "its runtime doesn't load (" (ex-message e) ")"))
                (do (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
                    (delete-tree! dir)))
              false))))

(defn- compile-runtime!
  "A cold start writes runtime.so into the cache anyway, so compile it first
  and load it, rather than evaluating the source and compiling it again for
  the cache (write-cache! moves this file into place). It goes inside the
  temp dir write-cache! will fill and rename, so a run that dies first
  leaves it where prune-cache-root! looks."
  [dir]
  (let [tmp (io/file (str dir ".tmp-" (System/nanoTime) (rand-int 1000000)))
        f (io/file tmp "runtime.so")
        src (io/file tmp "runtime.ss")]
    (.mkdirs tmp)
    (spit src (str/join "\n" (map #(slurp (io/resource %)) runtime-files)))
    (timed :runtime-compile
           #(s/eval-string (str "(parameterize ([current-output-port (open-output-string)]"
                                " [generate-inspector-information #f])"
                                " (compile-file " (pr-str (str src)) " " (pr-str (str f)) "))")))
    (.delete src)
    (timed :runtime-load #(s/call "load" (str f)))
    (swap! state assoc :cache-tmp tmp)))

(defn- load-runtime!
  "The runtime: the cache's runtime.so, else compiled into a cache that can
  be written, else its source evaluated."
  []
  (let [dir (cache-dir)
        so (when dir (io/file dir "runtime.so"))
        ro (and dir (cache-read-only? dir))]
    (when-not (load-cached-runtime! dir so ro)
      (when (and ro (not (.exists so)))
        (cache-unusable! dir "its runtime is missing"))
      (if (and dir (not ro))
        (compile-runtime! dir)
        (doseq [f runtime-files] (scheme! (slurp (io/resource f))))))))

(defn- pass-launcher-state!
  "What the launcher read reaches the runtime: the class path, the command,
  the properties, assertion directives, runtime arguments and preview."
  []
  (s/call "set-classpath!" (str/join ":" (:classpath-given @state)))
  (when-let [c (:java-command @state)] (s/call "set-java-command!" c))
  ;; the module options reach ModuleBootstrap as the launcher passes them
  ;; (module-properties); --add-modules or --limit-modules makes it resolve
  ;; the boot layer instead of reading the image's pre-generated graph
  (when-let [ps (seq (concat (:command-line-properties @state)
                             (module-properties (:module-options @state))))]
    (s/call "set-command-line-properties!" (str/join "\u0000" (mapcat identity ps))))
  (when-let [ds (seq (:assertion-directives @state))]
    (s/call "set-assertion-directives!" (str/join "\u0000" ds)))
  (when-let [as (seq (:runtime-arguments @state))]
    (s/call "set-runtime-arguments!" (str/join "\u0000" as)))
  (when @cf/preview-enabled (s/call "set-preview!" true)))

(defn- classpath-class-answer
  "The class file <cname>.class on the class path, found as the JDK's
  URLClassPath finds it (a directory through the file system, so a//b is
  a/b; a jar by its exact entry), for the application loader's names it
  can't load (natives.ss misnamed-classpath-class): false when there's no
  such file, else \"found\" when parse? is false, else what parsing it as
  cname gives, as normalize would: \"ok <its class name>\", \"format <text>\"
  or \"version <text>\" (HotSpot's ClassFormatError and
  UnsupportedClassVersionError texts), \"error <text>\" for anything else,
  so no Clojure exception crosses the native."
  [cname parse?]
  (if-let [bs (user-resource-bytes (str cname ".class"))]
    (if-not parse?
      "found"
      (try (str "ok " (:name (cf/parse bs)))
           (catch Exception e
             (let [{:keys [class-format named class-version]} (ex-data e)]
               (cond class-format (str "format " (if named (str class-format " " cname) class-format))
                     class-version (str "version " (class-version cname))
                     :else (str "error " (ex-message e)))))))
    false))

(defn- constant-value
  "ConstantPool natives (annotations): constant i of cname, its value or a
  class's name. A lone surrogate's code units (classfile/decode-mutf8) go
  over as a Scheme vector, which jstring takes."
  [cname i]
  (let [e (get (:cp (parsed cname)) i)
        v (or (:value e) (:name e)
              (throw (ex-info "no such constant" {:class cname :index i})))]
    (if (vector? v) (apply s/call "vector" v) v)))

(defn- install-hooks!
  "The runtime's hooks into this namespace, after the launcher's state."
  []
  (s/call "set-define-class-hook!" (fn [& args] (timed :define #(apply define-class! args))))
  ;; annotation instances: a synthetic class implementing the annotation
  ;; interface, its element keys "name()desc" joined by |
  (s/call "set-annotation-class-hook!" (fn [iface keys]
                                         (lambda-class [iface] (if (= "" keys) [] (str/split keys #"\|")))))
  (s/call "set-resource-hook!" (fn [path] (or (resource-bytes path) false))
          ;; URLs as one string, one per line
          (fn [path] (str/join "\n" (resource-urls path))))
  ;; java -p's named modules, each from its own jar or directory
  (s/call "set-module-resource-hook!" (fn [mn path] (or (module-resource-bytes mn path) false)))
  (s/call "set-module-resource-url-hook!" (fn [mn path] (or (module-resource-url mn path) false)))
  (s/call "set-jpackage-hook!" package-info)
  ;; the JDK's resources alone, for the platform loader
  (s/call "set-jdk-resource-hook!" (fn [path] (or (when (plain-resource-path? path) (jdk-resource-bytes path)) false)))
  (s/call "set-classpath-class-hook!" classpath-class-answer)
  ;; a classpath class's code source: its entry's URL, or false
  (s/call "set-code-source-hook!" (fn [cname] (if-let [e (get-in @state [:class-entries cname])] (entry-url e) false)))
  (s/call "set-cp-hook!" constant-value)
  ;; helpful NPE messages: site "pkg/Cls.name(desc)@bci" -> text, or false
  (s/call "set-npe-hook!" (fn [site] (or (npe-message site) false)))
  ;; stack traces: a frame's file, line and module as a Scheme vector
  (s/call "set-frame-hook!" (fn [cname mname desc pc]
                              (let [[f l m h] (frame-info cname mname desc pc)] (s/call "vector" f l m h)))))

(def ^:private boot-phases
  "The JVM's boot before main, as Scheme text run guarded: its status."
  "(run-guarded (lambda ()
     ;; String.<clinit> sets COMPACT_STRINGS; before it runs,
     ;; hashCode takes the UTF-16 path on Latin-1 strings, and
     ;; initPhase1 would key the property map by those hashes
     (jinit \"java/lang/String\")
     (jinit \"java/lang/System\")
     (boot-threads!)
     ;; the JVM initializes Reference before initPhase1; its <clinit>
     ;; installs SharedSecrets.javaLangRefAccess, which initPhase1 uses
     (jinit \"java/lang/ref/Reference\")
     ;; AccessibleObject.<clinit> installs langReflectAccess, which
     ;; ReflectionFactory needs (OpenLDK %initialize-system-phase1)
     (jinit \"java/lang/reflect/AccessibleObject\")
     ((method-proc \"java/lang/System\" \"initPhase1()V\"))
     ;; initPhase2 builds the boot layer from the image's
     ;; pre-generated module graph (SystemModules$default, no
     ;; resolution, unless --add-modules asks for one) and
     ;; initPhase3 makes the app class loader the system and
     ;; context loader, as the JVM does before main; a failing
     ;; phase 2 has printed its cause already, as HotSpot's: to
     ;; stdout, the exception and its cause without a trace,
     ;; and the VM ends with status 1, as a halt does
     ;; (under CHAMPACA_BT too: the exception line names a
     ;; champaca fault, a missing native say, and BT must not
     ;; change what the program prints)
     (unless (eqv? 0 ((method-proc \"java/lang/System\" \"initPhase2(ZZ)I\") 0 0))
       (raise (make-java-halt 1)))
     ;; from here the boot layer decides which JDK classes
     ;; the built-in loaders see (builtin-loader-sees?)
     (boot-layer-complete!)
     ((method-proc \"java/lang/System\" \"initPhase3()V\"))))")

(defn- finish-recording!
  "After the boot: a cold boot's code goes to boot.so, and from here on only
  what later code compiles is recorded. A warm boot keeps what it recorded
  for this run's generation: it compiles only classes the cached boot never
  loaded (a -Dstdout.encoding brings the boot a new encoder), and dropping
  their registrations left their linked maps in state.img with no code
  behind them, so a later run took them for registered and failed with
  \"class not registered\". Nothing is written over a cache that can't be
  written and didn't load."
  [dir hit? status]
  (when-let [rec (:recording @state)]
    (when-not hit?
      (when (and (zero? status) (not (:cache-unusable @state)))
        (timed :cache-write #(write-cache! dir @rec)))
      (vreset! rec []))))

(defn- run-boot!
  "The boot from the startup cache, or recorded for it on a miss: its
  status."
  []
  (let [dir (cache-dir)
        hit? (load-cache! dir)]
    ;; a miss over a cache that can't be written (a file of it gone): no
    ;; cold write there
    (when (and dir (not hit?) (cache-read-only? dir))
      (cache-unusable! dir "holds no boot for this run"))
    ;; nothing is recorded for a cache this run doesn't use (it can't be
    ;; written and didn't load): its save would write into it anyway when
    ;; only the root is read-only
    (when (and dir (not (:cache-unusable @state)))
      (swap! state assoc :recording (volatile! []) :cache-dir dir))
    (run! ensure-loaded! ["java/lang/Object" "java/lang/String" "java/lang/Class"])
    (let [status (s/eval-string boot-phases)]
      (finish-recording! dir hit? status)
      (swap! state assoc :cache (cond hit? :hit (and dir (not (:cache-unusable @state))) :miss :else :off))
      status)))

(defn- boot!
  "Load the runtime, then run System.initPhase1 the way the JVM does before
  main. Uses the boot cache when it is valid and writes it when it is not.
  Returns 0 on success."
  []
  (when-not @ensure-loaded-fn
    (throw (ex-info "champaca.state/ensure-loaded-fn is unset: champaca.main installs ensure-loaded! when it loads, and class-info can't load a class without it" {})))
  (check-lines-env!)
  (load-runtime!)
  (s/call "set-hooks!" jit! (fn [n] (ensure-loaded! n) nil))
  (pass-launcher-state!)
  (install-hooks!)
  (run-boot!))

(defn init!
  "Boot the JDK once (System.initPhase1 and what precedes it) with the given
  user classpath dirs. Returns 0 on success; a second call is a no-op.
  opts: {:preview true} turns on this release's preview features, as
  --enable-preview does, before the boot (never after: the JDK reads it once);
  :add-modules [\"jdk.incubator.vector\" ...] (or one comma-separated string)
  adds root modules to the boot layer, as --add-modules does."
  ([classpath] (init! classpath {}))
  ([classpath {:keys [preview add-modules]}]
   (or (:boot-status @state)
       (do (when preview (reset! cf/preview-enabled true))
           ;; a string is one module list, as the option's value
           (when (seq add-modules)
             (swap! state update :module-options (fnil conj [])
                    ["addmods" (if (string? add-modules) add-modules (str/join "," add-modules))]))
           (swap! state assoc :classpath (with-manifest-class-paths (vec classpath))
                  ;; java.class.path is what was given, as the JVM's launcher has it
                  :classpath-given (vec classpath))
           (let [status (boot!)] (swap! state assoc :boot-status status) status)))))

(defn run
  "Run cname's main method with classes found under the classpath dirs,
  after booting the JDK. Returns the exit status. With :version in the
  state (-showversion and friends) the JDK's VersionProps prints the
  version first; a nil cname runs nothing after it (-version)."
  [classpath cname args]
  (saves-generation!) ; save-generation! runs at exit below
  (let [t0 (System/nanoTime)
        status (init! classpath)
        _ (when (env "CHAMPACA_PROF") (prof-report "boot" (- (System/nanoTime) t0)))]
    (when (env "CHAMPACA_STATS") (binding [*out* *err*] (print "after boot: ")) (stats))
    (if (zero? status)
      (let [v (:version @state)
            vs (if v (s/call "java-print-version" (boolean (:err? v))) 0)
            status (cond (not (and cname (zero? vs))) vs
                         (:launch-module @state) (apply s/call "run-module-main" cname args)
                         :else (apply s/call "run-main" cname args))]
        (when (env "CHAMPACA_PROF") (prof-report "run" (- (System/nanoTime) t0)))
        (when (env "CHAMPACA_STATS") (stats))
        (save-generation!)
        status)
      status)))

(defn- run-jar!
  "As java -jar: the manifest's Main-Class, the jar alone as the class path
  given (its Class-Path entries follow it, as for any jar). Any file,
  whatever its name, as java -jar takes it; one that isn't a zip is the
  launcher's \"invalid or corrupt\"."
  [jar args]
  (let [file? (.isFile (io/file jar))
        m (when file? (try (or (jar-manifest jar) {}) (catch Exception _ ::corrupt)))
        main (when (map? m) (get-in m [nil "main-class"]))]
    (when-not main
      (binding [*out* *err*]
        (println (cond (not file?) (str "Error: Unable to access jarfile " jar)
                       (= m ::corrupt) (str "Error: Invalid or corrupt jarfile " jar)
                       :else (str "no main manifest attribute, in " jar))))
      (System/exit 1))
    (swap! state assoc :java-command (str/join " " (cons jar args)))
    (System/exit (run [jar] (str/replace (str/trim main) "." "/") (vec args)))))

(defn- run-main-class!
  "The main class on the class path cp. sun.java.command, as the launcher
  has it: the main class as given (the source launcher with its module),
  then the arguments."
  [cp cname args source-mode?]
  (swap! state assoc :java-command (str/join " " (cons (if source-mode? (str "jdk.compiler/" (str/replace cname "/" ".")) cname) args)))
  (System/exit (run (if (= "" cp) [] (vec (expand-class-path cp))) (str/replace cname "." "/") (vec args))))

(defn -main [& all-argv]
  (let [{:keys [classpath argv source version module] :as opts} (jvm-options all-argv)
        consumed (take (- (count all-argv) (count argv)) all-argv)
        source-mode? (source-mode-of argv source)
        _ (apply-launcher-options! opts consumed source-mode?)
        [cp cname & args] (program-args argv classpath source source-mode?)]
    ;; -version and --version print and exit, whatever follows, as the
    ;; launcher, which needs no main class for them
    (when (:exit? version)
      (System/exit (run (if classpath (vec (expand-class-path classpath)) []) nil [])))
    (when module
      ;; java -m: the main class is the module's, and the class path -cp's
      ;; alone ("" without one, as java.class.path then is)
      (swap! state assoc :java-command (str/join " " (cons module argv)) :launch-module module)
      (System/exit (run (if classpath (vec (expand-class-path classpath)) []) module (vec argv))))
    (when (and source (= "-jar" (first argv)))
      (launcher-error "Error: Option -jar is not allowed with --source"))
    (when (and (= cp "-jar") (not cname))
      (binding [*out* *err*] (println "Error: -jar requires jar file specification"))
      (System/exit 1))
    (when-not cname
      ;; -showversion without a program prints the version, then the usage
      (when version (run [] nil []))
      (print-usage!)
      (System/exit 2))
    (if (= cp "-jar")
      (run-jar! cname args)
      (run-main-class! cp cname args source-mode?))))
