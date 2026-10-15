(ns openldk-jolt.main
  "Class loading, linking and the JIT hook, plus the command-line entry.

  Classes come from the user classpath first, then from the JDK's own
  java.base.jmod. Loading a class registers it with the Scheme runtime and
  defines every method as a stub; the first call to a stub calls back into
  jit!, which translates that one method and redefines the global in place.
  Native methods are bound by name from resources/openldk_jolt/natives.ss.
  That mirrors OpenLDK, which also compiles each method on first call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [openldk-jolt.classfile :as cf]
            [openldk-jolt.codegen :as cg]
            [jolt.image :as img])
  (:import [java.util.zip ZipFile]))

(defonce ^:private state (atom {:classpath [] :classes {} :jitted 0}))

(defn- env [k] (System/getenv k))
(defn- trace? [] (some? (env "OPENLDK_TRACE")))

;; OPENLDK_PROF: exclusive (self) time per phase. A nested phase's time is
;; charged to it and taken out of its parent.
(def ^:private prof-stack (volatile! []))

(defn- timed [k f]
  (if-not (env "OPENLDK_PROF")
    (f)
    (let [t0 (System/nanoTime)]
      (vswap! prof-stack conj 0)
      (try (f)
           (finally
             (let [total (- (System/nanoTime) t0)
                   child (peek @prof-stack)]
               (vswap! prof-stack pop)
               (when (seq @prof-stack)
                 (vswap! prof-stack #(conj (pop %) (+ (peek %) total))))
               (swap! state update-in [:prof k] (fnil + 0) (- total child))))))))

(defn- prof-report [label wall-ns]
  (let [p (:prof @state) ms #(/ (Math/round (/ % 1e4)) 100.0)
        accounted (reduce + (vals p))]
    (binding [*out* *err*]
      (println (str "openldk prof (" label ", cache " (name (or (:cache @state) :off)) "): wall " (ms wall-ns) " ms"))
      (doseq [[k v] (sort-by (comp - val) p)] (println (str "  " (name k) " " (ms v) " ms")))
      (println (str "  java+runtime (rest) " (ms (- wall-ns accounted)) " ms")))))

(defn- scheme!
  "Evaluate Scheme text: compiled by default, or :interpret for one-shot
  plumbing like class registration."
  ([text] (scheme! text :compile))
  ([text mode]
   (when (trace?)
     (let [dir (io/file "target" "jit")]
       (.mkdirs dir)
       (spit (io/file dir "trace.ss") (str text "\n\n") :append true)))
   (if (= mode :interpret)
     (s/call "interpret-string" text)
     (s/eval-string text))))

(declare ensure-loaded! ancestry-of-unlinked lambda-class link registration-text)

(defn- class-info [cname]
  (or (get-in @state [:classes cname])
      (do (ensure-loaded! cname) (get-in @state [:classes cname]))))

;; --- the JDK image ---------------------------------------------------------------
;; A .jmod is a zip behind a 4-byte "JM" header. jolt's ZipFile shim wants a
;; plain zip, so the zip part is copied once into target/jdk/.

(defn- java-home []
  (or (env "JAVA_HOME") (throw (ex-info "JAVA_HOME is not set" {}))))

(defn- jdk-zip []
  (or (:jdk-zip @state)
      (let [jmod (io/file (java-home) "jmods" "java.base.jmod")
            out (io/file "target" "jdk" "java.base.zip")]
        (when-not (.exists out)
          (io/make-parents out)
          (with-open [in (io/input-stream jmod)]
            (.skip in 4)
            (io/copy in out)))
        (let [zf (ZipFile. (str out))]
          (swap! state assoc :jdk-zip zf)
          zf))))

(defn- user-class-bytes [cname]
  (some (fn [dir]
          (let [f (io/file dir (str cname ".class"))]
            (when (.exists f)
              (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String []))))))
        (:classpath @state)))

(defn- class-bytes [cname]
  (or (some (fn [dir]
              (let [f (io/file dir (str cname ".class"))]
                (when (.exists f)
                  (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String []))))))
            (:classpath @state))
      (let [zf (jdk-zip)]
        (when-let [e (.getEntry zf (str "classes/" cname ".class"))]
          (with-open [in (.getInputStream zf e)] (.readAllBytes in))))))

(defn- record!
  "Keep text for the boot cache when it belongs to a JDK class."
  [origin text]
  (when-let [rec (:recording @state)]
    (when (= origin :jdk) (vswap! rec conj text))))

;; --- resolution ----------------------------------------------------------------------

(defn- ancestry
  "cname, its superclasses, then every superinterface reachable from any of
  them, each once, in that order."
  [cname]
  (let [supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (loop [todo (mapcat #(:interfaces (class-info %)) supers) seen (set supers) out (vec supers)]
      (if-let [i (first todo)]
        (if (seen i)
          (recur (rest todo) seen out)
          (recur (concat (rest todo) (:interfaces (class-info i))) (conj seen i) (conj out i)))
        out))))

(defn- resolve-static-field [cname fname]
  (or (some (fn [c] (when (some #(and (:static %) (= fname (:name %))) (:fields (class-info c))) c))
            (ancestry cname))
      (throw (ex-info "no such static field" {:class cname :field fname}))))

(defn- field-index [cname fname]
  (loop [c cname]
    (if-let [info (and c (class-info c))]
      (if (some #(and (not (:static %)) (= fname (:name %))) (:fields info))
        (get (:layout info) (str c "." fname))
        (recur (:super info)))
      (throw (ex-info "no such field" {:class cname :field fname})))))

(defn- resolve-method
  "The class or interface that implements cname.mname(desc) for a direct
  (static or special) call: the superclass chain first, then interfaces,
  where only a method with a body counts."
  [cname mname desc]
  (let [has? (fn [c body?] (some #(and (= mname (:name %)) (= desc (:desc %))
                                       (or (not body?) (:code %) (:native %)))
                                 (:methods (class-info c))))
        supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (or (some #(when (has? % false) %) supers)
        (some #(when (has? % true) %) (ancestry cname))
        (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(def ^:private resolver
  {:ensure-loaded #(ensure-loaded! %)
   :resolve-static-field resolve-static-field
   :field-index field-index
   :resolve-method resolve-method
   :lambda-class #(lambda-class %1 %2)})

;; --- loading -----------------------------------------------------------------------

(defn- normalize
  "Parse cname's classfile into the linker's class map."
  [cname]
  (let [bytes (or (class-bytes cname)
                  (throw (ex-info (str "class not found: " cname) {:class cname})))
        c (cf/parse bytes)]
    {:name cname :super (:super c) :interfaces (:interfaces c) :parsed c :flags (:flags c)
     ;; only JDK classes are cached; user classes change between runs
     :origin (if (user-class-bytes cname) :user :jdk)
     :fields (mapv (fn [f] {:name (:name f) :desc (:desc f) :static (cf/static? f) :constant (:constant f)})
                   (:fields c))
     :methods (mapv (fn [m] (assoc m
                                   :native (cf/native? m)
                                   :virtual (not (or (cf/static? m) (str/starts-with? (:name m) "<")))))
                    (:methods c))}))

(defn- link
  "Compute field layout, slot template and vtable from the (already linked)
  superclass, then fill vtable gaps with interface default methods."
  [info]
  (let [sup (when (:super info) (class-info (:super info)))
        own (remove :static (:fields info))
        base (count (:template sup []))
        layout (into (or (:layout sup) {})
                     (map-indexed (fn [i f] [(str (:name info) "." (:name f)) (+ base i)]) own))
        template (into (or (:template sup) []) (map #(cg/default-value (:desc %)) own))
        vtable (into (or (:vtable sup) {})
                     (for [m (:methods info)
                           :when (and (:virtual m) (or (:code m) (:native m)))]
                       [(str (:name m) (:desc m)) (str (:name info) "." (:name m) (:desc m))]))
        defaults (for [i (rest (ancestry-of-unlinked info))
                       :let [ii (class-info i)]
                       :when (cf/interface? ii)
                       m (:methods ii)
                       :when (and (:virtual m) (:code m))]
                   [(str (:name m) (:desc m)) (str i "." (:name m) (:desc m))])
        vtable (reduce (fn [vt [k g]] (if (contains? vt k) vt (assoc vt k g))) vtable defaults)]
    (assoc info :layout layout :template template :vtable vtable)))

(defn- ancestry-of-unlinked
  "ancestry for a class that is not in the registry yet."
  [info]
  (cons (:name info)
        (distinct (mapcat ancestry (remove nil? (cons (:super info) (:interfaces info)))))))

(defn- constant-text [{:keys [tag value]}]
  (case tag
    (:int :long :float :double) (cg/num-lit value)
    :string (cg/sx "jstring-lit" (cg/str-lit value))))

(defn- registration-text [{:keys [name super interfaces template vtable layout fields methods flags]}]
  (str/join
   "\n"
   (concat
    [(cg/sx "register-class!" (cg/str-lit name) (if super (cg/str-lit super) "#f")
            (str "'" (apply cg/sx (map cg/str-lit interfaces)))
            (apply cg/sx "vector" template)
            (str "'" (apply cg/sx (for [[k g] (sort vtable)] (str "(" (cg/str-lit k) " . " (cg/sym g) ")"))))
            (str "'" (apply cg/sx (for [[k i] (sort layout)] (str "(" (cg/str-lit k) " . " i ")"))))
            (str flags))]
    (for [f fields :when (:static f)]
      (cg/sx "define" (cg/static-global name (:name f))
             (if-let [k (:constant f)] (constant-text k) (cg/default-value (:desc f)))))
    (for [m methods
          :let [g (cg/method-global name (:name m) (:desc m))]
          :when (or (:native m) (:code m))]
      (if (:native m)
        (cg/sx "define" g (cg/sx "native-ref" (cg/str-lit (str name "." (:name m) (:desc m)))))
        ;; method-stub returns a runtime override when natives.ss has one,
        ;; else a closure that JIT-compiles the method on first call
        (cg/sx "define" g
               (cg/sx "method-stub" (cg/str-lit name) (cg/str-lit (:name m)) (cg/str-lit (:desc m))
                      (str "'" g))))))))

(defn ensure-loaded! [cname]
  (when-not (get-in @state [:classes cname])
    (let [info (timed :parse #(normalize cname))]
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (when-not (get-in @state [:classes cname])
        (let [linked (timed :link #(link info))]
          (swap! state assoc-in [:classes cname] linked)
          (let [text (timed :register-text #(registration-text linked))]
            (record! (:origin linked) text)
            (timed :register-eval #(scheme! text :interpret)))))))
  cname)

(defn lambda-class
  "The synthetic class implementing ifaces for lambdas whose abstract
  method keys (\"name(desc)\", the SAM plus any bridges) are sam-keys.
  Slot 0 holds the closure; every SAM key maps to openldk/Lambda.invoke,
  which calls it."
  [ifaces sam-keys]
  (let [n (str "openldk/Lambda$" (str/replace (first ifaces) "/" "$") "$" (Math/abs (hash [ifaces sam-keys])))]
    (when-not (get-in @state [:classes n])
      (run! ensure-loaded! ifaces)
      (let [info {:name n :super "java/lang/Object" :interfaces (vec ifaces) :flags 0x1011
                  :fields [{:name "fn" :desc "Ljava/lang/Object;" :static false}] :methods []}
            linked (reduce #(assoc-in %1 [:vtable %2] "openldk/Lambda.invoke") (link info) sam-keys)]
        (swap! state assoc-in [:classes n] linked)
        (let [text (registration-text linked)]
          (record! :jdk text) ; pure data, safe to cache whoever asked
          (scheme! text :interpret))))
    n))

;; --- the JIT -----------------------------------------------------------------------

(defn- parsed
  "The parsed classfile for cname. A class restored from the boot cache
  carries no bytecode, so it is re-read on the first method compile."
  [cname]
  (or (get-in @state [:classes cname :parsed])
      (let [p (timed :parse #(cf/parse (class-bytes cname)))]
        (swap! state assoc-in [:classes cname :parsed] p)
        p)))

(defn jit! [cname mname desc]
  (let [p (parsed cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))
        info (assoc (class-info cname) :parsed p)]
    (swap! state update :jitted inc)
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (let [text (timed :codegen #(cg/compile-method (:parsed info) m resolver))]
      (record! (:origin info) text)
      (timed :chez-compile #(scheme! text)))))

(defn- stats []
  (let [{:keys [classes jitted]} @state]
    (binding [*out* *err*]
      (println (str "openldk: " (count classes) " classes loaded, " jitted " methods compiled")))))

;; --- boot cache ---------------------------------------------------------------------
;; Modeled on Clojure's RT.load: one compiled unit per input, used only when
;; it is newer than its source, and a miss quietly takes the slow path. The
;; unit here is the whole JDK boot. Clojure's key is a timestamp; this key
;; also covers the translator itself, because a cache built by a different
;; translator is stale even when the JDK is not (Clojure's AOT
;; version-mismatch trap).

(defn- translator-fingerprint []
  (hash (apply str (map #(slurp (io/resource %))
                        ["openldk_jolt/runtime.ss" "openldk_jolt/natives.ss"
                         "openldk_jolt/codegen.clj" "openldk_jolt/main.clj"
                         "openldk_jolt/classfile.clj"]))))

(defn- cache-dir []
  (when-not (= "off" (env "OPENLDK_CACHE"))
    (let [jmod (io/file (java-home) "jmods" "java.base.jmod")
          release (io/file (java-home) "release")
          key (hash [(when (.exists release) (slurp release)) (.length jmod) (.lastModified jmod)
                     (translator-fingerprint) (img/runtime-version)])]
      (io/file (or (env "OPENLDK_CACHE_DIR") (str (env "HOME") "/.cache/openldk-jolt"))
               (Long/toHexString (bit-and key 0xffffffff))))))

(defn- strip-for-cache
  "The linker's class map for JDK classes only, without bytecode: methods
  keep :code as a flag."
  [classes]
  (into {}
        (for [[k info] classes :when (not= :user (:origin info))]
          [k (-> info
                 (dissoc :parsed)
                 (update :methods (fn [ms] (mapv #(cond-> % (:code %) (assoc :code true)) ms))))])))

(defn- write-cache!
  "Compile the recorded boot forms to boot.so and save the class table to
  state.img, in a temp dir renamed into place so readers never see half."
  [dir forms]
  (let [tmp (io/file (str dir ".tmp-" (System/nanoTime)))]
    (.mkdirs tmp)
    (spit (io/file tmp "boot.ss") (apply str (interpose "\n" forms)))
    (s/call "quiet-compile-file" (str (io/file tmp "boot.ss")) (str (io/file tmp "boot.so")))
    (.delete (io/file tmp "boot.ss"))
    (img/dump! (str (io/file tmp "state.img")) (strip-for-cache (:classes @state)))
    (when-not (.renameTo tmp dir)
      ;; another run won the race; ours is redundant
      (doseq [f (reverse (file-seq tmp))] (.delete f)))))

(defn- extras [dir]
  (sort-by #(.getName %) (filter #(re-matches #"extra-\d+\.so" (.getName %)) (.listFiles dir))))

(defn- load-cache!
  "Restore a cached boot plus every later generation. Returns true on a hit."
  [dir]
  (let [so (io/file dir "boot.so") img-file (io/file dir "state.img")]
    (when (and dir (.exists so) (.exists img-file))
      (let [classes (timed :cache-read-state #(img/read-image (str img-file)))]
        (swap! state assoc :classes classes)
        (timed :cache-load-code #(do (s/call "load" (str so))
                                     (doseq [f (extras dir)] (s/call "load" (str f)))))
        true))))

(defn save-generation!
  "Append what this run compiled for JDK classes after boot: one more
  extra-N.so, and a rewritten state.img (written beside, then renamed)."
  []
  (let [dir (:cache-dir @state)
        forms (some-> (:recording @state) deref)]
    (when (and dir (.isDirectory dir) (seq forms))
      (let [stamp (System/currentTimeMillis)
            src (io/file dir (str "extra-" stamp ".ss.tmp"))
            so (io/file dir (str "extra-" stamp ".so"))
            so-tmp (io/file dir (str "extra-" stamp ".so.tmp"))
            img-tmp (io/file dir (str "state.img.tmp-" stamp))]
        (spit src (apply str (interpose "\n" forms)))
        (s/call "quiet-compile-file" (str src) (str so-tmp))
        (.delete src)
        (img/dump! (str img-tmp) (strip-for-cache (:classes @state)))
        (.renameTo so-tmp so)
        (.renameTo img-tmp (io/file dir "state.img"))
        (vreset! (:recording @state) [])))))

(defn- boot!
  "Load the runtime, then run System.initPhase1 the way the JVM does before
  main. Uses the boot cache when it is valid and writes it when it is not.
  Returns 0 on success."
  []
  (scheme! (slurp (io/resource "openldk_jolt/runtime.ss")))
  (scheme! (slurp (io/resource "openldk_jolt/natives.ss")))
  (s/call "set-hooks!" jit! (fn [n] (ensure-loaded! n) nil))
  (let [dir (cache-dir)
        hit? (load-cache! dir)]
    (when dir (swap! state assoc :recording (volatile! []) :cache-dir dir))
    (run! ensure-loaded! ["java/lang/Object" "java/lang/String" "java/lang/Class"])
    (let [status (s/eval-string
                  "(run-guarded (lambda ()
                     (jinit \"java/lang/System\")
                     (boot-threads!)
                     ;; the JVM initializes Reference before initPhase1; its <clinit>
                     ;; installs SharedSecrets.javaLangRefAccess, which initPhase1 uses
                     (jinit \"java/lang/ref/Reference\")
                     ((method-proc \"java/lang/System\" \"initPhase1()V\"))))")]
      (when-let [rec (:recording @state)]
        (when (and (not hit?) (zero? status))
          (timed :cache-write #(write-cache! dir @rec)))
        ;; from here on, record only what later code compiles
        (vreset! rec []))
      (swap! state assoc :cache (cond hit? :hit dir :miss :else :off))
      status)))

(defn init!
  "Boot the JDK once (System.initPhase1 and what precedes it) with the given
  user classpath dirs. Returns 0 on success; a second call is a no-op."
  [classpath]
  (or (:boot-status @state)
      (do (swap! state assoc :classpath (vec classpath))
          (let [status (boot!)] (swap! state assoc :boot-status status) status))))

(defn run
  "Run cname's main method with classes found under the classpath dirs,
  after booting the JDK. Returns the exit status."
  [classpath cname args]
  (let [t0 (System/nanoTime)
        status (init! classpath)
        _ (when (env "OPENLDK_PROF") (prof-report "boot" (- (System/nanoTime) t0)))]
    (when (env "OPENLDK_STATS") (binding [*out* *err*] (print "after initPhase1: ")) (stats))
    (if (zero? status)
      (let [status (apply s/call "run-main" cname args)]
        (when (env "OPENLDK_STATS") (stats))
        (save-generation!)
        status)
      status)))

(defn -main [& argv]
  (let [[cp cname & args] argv]
    (when-not cname
      (binding [*out* *err*] (println "usage: jolt -M -m openldk-jolt.main <classpath-dir[:dir...]> <MainClass> [args...]"))
      (System/exit 2))
    (System/exit (run (str/split cp #":") (str/replace cname "." "/") (vec args)))))
