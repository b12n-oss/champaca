;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.jit
  "The JIT and the runtime's hooks into the translator. Loading a class
  defines every method as a stub; the first call to a stub calls back into
  jit!, which translates that one method (or loads its cached code) and
  redefines the global in place. That mirrors OpenLDK, which also compiles
  each method on first call. The hooks answer helpful NPE messages, a
  frame's source file and line, class path lookups for misnamed classes and
  constant pool entries."
  (:require [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.cachedir :refer [cache-origin compile-to-cache? load-unit! record!]]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [class-bytes class-source jar-entry? package-module user-resource-bytes]]
            [champaca.codegen :as cg]
            [champaca.libcache :refer [deps-still-hold! uncheckable-dep user-deps]]
            [champaca.link :refer [check-kind check-virtual class-info direct-target field-index methods-of
                                   overridden? package-private-decl parsed resolve-method
                                   resolve-static-field thaw!]]
            [champaca.loader :refer [ensure-loaded! lambda-class]]
            [champaca.state :refer [state env trace? timed scheme! scheme-trace! *reads*]]))

;; --- resolution ----------------------------------------------------------------------

(defn- method-named
  "The method mname(desc) in methods, or nil."
  [methods mname desc]
  (first (filter #(and (= mname (:name %)) (= desc (:desc %))) methods)))

(defn- inline-source?
  "Whether decl's methods may be inlined into caller-cname's: the caller's
  own class, or a JDK class already parsed in this run (reading one again
  from the image costs a warm start more than the inlining saves; decided
  first, so a restored class's methods aren't thawed for an inlining that
  can't happen)."
  [caller-cname decl info]
  (or (= decl caller-cname) (and (= :jdk (:origin info)) (:parsed info))))

(defn- leaf-sized?
  "Whether im, from decl's class map, has code within codegen's leaf limit
  and isn't one the runtime overrides; the class map answers it without
  reading the class file again."
  [decl im]
  (and im (:code im) (<= (or (:code-len im) 0) cg/leaf-limit)
       (not (overridden? decl im))))

(defn- inline-candidate
  "The parsed class and method decl.mname(desc), for codegen to inline into a
  method of caller-cname, or nil. Only a method in the caller's own class or
  in the JDK qualifies: a compiled method is cached with its own class, and a
  copy of another user class's code would outlive a change to that class.
  The JDK can't change under a cache, which is keyed by it. A method the
  runtime overrides never qualifies."
  [caller-cname decl mname desc]
  (let [info (class-info decl)]
    (when (and (inline-source? caller-cname decl info)
               (leaf-sized? decl (method-named (methods-of info) mname desc)))
      (when-let [p (if (= decl caller-cname) (parsed decl) (:parsed info))]
        {:cls p :method (method-named (:methods p) mname desc)}))))

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
   :lambda-class (fn [ifaces keys & [caller site]] (lambda-class ifaces keys caller site))})

;; --- the JIT -----------------------------------------------------------------------

(defn- lines?
  "Whether cname's code compiles with Chez's inspector information, which is
  what gives its frames line numbers (how-it-works.md). By default a class
  from a classpath directory, a developer's own build, does; a jar's or a
  loader-defined class names its file without a line, as a JDK frame does,
  because the information costs about as much memory as the code itself (a
  warm Spring Boot: 100 of 460 MB live). A source-file run's defined
  classes do too: javac's source launcher defines the program's own classes
  (their cache file is apart, defined-jar). So does a class generated at
  run time, defined with no code source location (a Clojure REPL's eval
  classes, loader.clj note-generated!). CHAMPACA_LINES=all gives every
  class but the JDK's lines, CHAMPACA_LINES=off none. Every compile path
  asks, cached or not, so a frame's line doesn't depend on the cache."
  [cname info]
  (case (some-> (env "CHAMPACA_LINES") str/lower-case)
    "all" (contains? #{:user :defined} (:origin info))
    "off" false
    (case (:origin info)
      :user (boolean (when-let [src (class-source cname)] (not (jar-entry? (:entry src)))))
      :defined (boolean (or (:source-mode @state) (contains? (:generated-classes @state) cname)))
      false)))

;; the throws codegen leaves for what it couldn't resolve, scanned for in
;; each method's text; a method without the marker can't match, and almost
;; none has one, so a substring test skips the regex (the scans were 2.8% of a
;; first-use run's samples, each pattern compiled again per method)
(def ^:private throw-patterns
  {:missing ["(jthrow-missing \"" (re-pattern "\\(jthrow-missing \"([^\"]+)\"")]
   :class-format ["(jthrow-class-format \"" (re-pattern "\\(jthrow-class-format \"([^\"]+)\"")]
   :no-method ["(jthrow-no-method \"" (re-pattern "\\(jthrow-no-method \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"")]
   :linkage ["(jthrow-linkage \"" (re-pattern "\\(jthrow-linkage \"([^\"]+)\"")]
   :icce ["(jthrow-icce \"" (re-pattern "\\(jthrow-icce \"[^\"]+\" \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"")]})

(defn- thrown-in
  "re-seq of the throw pattern named k over a method's text."
  [k text]
  (let [[marker pat] (throw-patterns k)]
    (when (str/includes? text marker) (re-seq pat text))))

;; how deep compile-method! is on this thread: only the outermost call's
;; time counts toward the CompilationMXBean's total, which a compile nested
;; in another (a class its code resolves) would otherwise count twice
(def ^:private compile-depth (ThreadLocal/withInitial (fn [] 0)))

(declare compile-method!*)
(defn- compile-method! [cname mname desc]
  (let [d (.get compile-depth)
        t0 (when (zero? d) (System/nanoTime))]
    (.set compile-depth (inc d))
    (try (compile-method!* cname mname desc)
         (finally
           (.set compile-depth d)
           (when t0 (s/call "note-compile-time!" (- (System/nanoTime) t0)))))))

(defn- method-cache-origin
  "Where a compiled method's code is cached: the library cache (:user) when
  every class its translation read can be checked, recording those reads as
  the class's dependencies (a fingerprint already recorded stays: were a
  dependency to differ from it, the next run's check must fail); nil, not
  cached, when one can't; for a class with no source fingerprint, its
  class's cache-origin."
  [cname mname desc info reads]
  (if reads
    (if-let [deps (user-deps (conj @reads cname) (= :defined (:origin info)))]
      (do (swap! state update-in [:classes cname :deps] #(merge-with (fn [old _] old) % (dissoc deps cname)))
          :user)
      (do (when (trace?)
            (binding [*out* *err*]
              (println "jit" (str cname "." mname desc) "not cached: it reads" (uncheckable-dep (conj @reads cname) (= :defined (:origin info))))))
          nil))
    (cache-origin info)))

(defn- report-throws
  "Whether the method's code throws for what an instruction names (codegen):
  a class that isn't there (NoClassDefFoundError), a method its class doesn't
  have (NoSuchMethodError), a file that isn't a class file
  (ClassFormatError), a call of the wrong kind (IncompatibleClassChangeError),
  a class the JVM refuses to define (check-definition!). Such a method is
  never cached, so a cache can't carry a throw a later run doesn't; under
  CHAMPACA_BT each is named on stderr."
  [cname mname desc text]
  (let [member (fn [[_ c n d]] (str c "." n d))
        found [[(seq (map second (thrown-in :missing text))) "not found" "NoClassDefFoundError"]
               [(seq (map member (thrown-in :no-method text))) "not found" "NoSuchMethodError"]
               [(seq (map second (thrown-in :class-format text))) "not a class file" "ClassFormatError"]
               [(seq (map member (thrown-in :icce text))) "of the other kind" "IncompatibleClassChangeError"]
               [(seq (map second (thrown-in :linkage text))) "refused by its definition's checks" "the JVM's linkage error"]]]
    (when (System/getenv "CHAMPACA_BT")
      (doseq [[names why what] found :when names]
        (binding [*out* *err*]
          (println (str "champaca: " cname "." mname desc " names " (str/join ", " (distinct names))
                        ", " why "; that code throws " what)))))
    (boolean (some first found))))

(defn- compile-text!
  "Compile a method's text. One that throws for something it names is
  compiled and never recorded. The boot that archives the module graph
  (archive.ss) runs the image's generated SystemModules classes once,
  interpreted: a warm boot takes the graph from the archive and never calls
  them, and compiling moduleDescriptors alone was about a second of a cold
  one. Code for the cache is compiled once, and the same object code runs now
  and goes in the cache; otherwise the text is kept (cheaply) so save-cache!
  can compile it into a generation, without which a jolt program re-jits
  these every run."
  [cname info text origin unit throws?]
  (cond
    throws? (timed :chez-compile #(scheme! text :compile (lines? cname info)))
    (and (str/starts-with? cname "jdk/internal/module/SystemModules$")
         (true? (s/eval-string "module-graph-dumping")))
    (timed :chez-compile #(scheme! text :interpret))
    (compile-to-cache? origin)
    (let [bv (timed :chez-compile #(do (when (trace?) (scheme-trace! text))
                                       ;; a classpath class's frames keep their line numbers
                                       (s/call "compile-unit" text (lines? cname info))))]
      (record! origin unit {:fasl bv}))
    :else
    (do (timed :chez-compile #(scheme! text :compile (lines? cname info)))
        (record! origin unit {:text text :inspect (lines? cname info)}))))

(defn- compile-method!* [cname mname desc]
  (let [p (parsed cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))
        info (assoc (class-info cname) :parsed p)]
    (swap! state update :jitted inc)
    (when (= :user (:origin info)) (swap! state update :jitted-user (fnil inc 0)))
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (let [reads (when (:src-fp info) (volatile! #{}))
          text (binding [*reads* reads] (timed :codegen #(cg/compile-method (:parsed info) m resolver)))
          origin (method-cache-origin cname mname desc info reads)
          throws? (report-throws cname mname desc text)]
      (compile-text! cname info text origin [cname (str mname desc)] throws?))))

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

(defn npe-message [site]
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

(defn- parse-frame-class
  "What a stack frame of class cname shows, from its class file's bytes: the
  source file, the module of a JDK class, each method's line table and the
  @Hidden methods."
  [cname bytes]
  (let [p (binding [cf/*lines* true] (cf/parse bytes))
        slash (str/last-index-of cname "/")]
    {:source (:source p)
     :module (when (= :jdk (:origin (class-info cname)))
               (package-module (if slash (subs cname 0 slash) "")))
     :lines (into {} (for [m (:methods p) :when (:code m)]
                       [(str (:name m) (:desc m)) (:lines (:code m))]))
     :hidden (set (for [m (:methods p) :when (hidden-method? (:cp p) m)]
                    (str (:name m) (:desc m))))}))

(defn- read-frame-class
  "parse-frame-class for cname, or nil when its bytes are gone or don't
  parse (one stderr line under CHAMPACA_BT)."
  [cname]
  (try (when-let [bytes (class-bytes cname)]
         (parse-frame-class cname bytes))
       (catch Exception e
         (when (env "CHAMPACA_BT")
           (binding [*out* *err*] (println "champaca: no source lines for" cname (ex-message e))))
         nil)))

(defn- frame-class [cname]
  (let [cache (:frame-classes @state)]
    (if (contains? cache cname)
      (get cache cname)
      (let [info (read-frame-class cname)]
        (swap! state assoc-in [:frame-classes cname] info)
        info))))

(defn frame-info
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

(defn classpath-class-answer
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

(defn constant-value
  "ConstantPool natives (annotations): constant i of cname, its value or a
  class's name. A lone surrogate's code units (classfile/decode-mutf8) go
  over as a Scheme vector, which jstring takes."
  [cname i]
  (let [e (get (:cp (parsed cname)) i)
        v (or (:value e) (:name e)
              (throw (ex-info "no such constant" {:class cname :index i})))]
    (if (vector? v) (apply s/call "vector" v) v)))
