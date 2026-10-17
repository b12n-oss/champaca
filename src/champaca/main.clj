(ns champaca.main
  "Class loading, linking and the JIT hook, plus the command-line entry.

  Classes come from the user classpath first, then from the JDK's own
  jmods. Loading a class registers it with the Scheme runtime and
  defines every method as a stub; the first call to a stub calls back into
  jit!, which translates that one method and redefines the global in place.
  Native methods are bound by name from resources/champaca/natives.ss.
  That mirrors OpenLDK, which also compiles each method on first call."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classfile :as cf]
            [champaca.codegen :as cg]
            [jolt.image :as img])
  (:import [java.util.zip ZipFile]))

(defonce ^:private state (atom {:classpath [] :classes {} :jitted 0}))

(defn- env [k] (System/getenv k))
(defn- trace? [] (some? (env "CHAMPACA_TRACE")))

;; CHAMPACA_PROF: exclusive (self) time per phase. A nested phase's time is
;; charged to it and taken out of its parent.
(def ^:private prof-stack (volatile! []))

(defn- timed [k f]
  (if-not (env "CHAMPACA_PROF")
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
      (println (str "champaca prof (" label ", cache " (name (or (:cache @state) :off)) "): wall " (ms wall-ns) " ms"))
      (doseq [[k v] (sort-by (comp - val) p)] (println (str "  " (name k) " " (ms v) " ms")))
      (println (str "  java+runtime (rest) " (ms (- wall-ns accounted)) " ms")))))

(defn- scheme-trace!
  "Append text to target/jit/trace.ss (CHAMPACA_TRACE)."
  [text]
  (let [dir (io/file "target" "jit")]
    (.mkdirs dir)
    (spit (io/file dir "trace.ss") (str text "\n\n") :append true)))

(defn- scheme!
  "Evaluate Scheme text: compiled by default, or :interpret for one-shot
  plumbing like class registration."
  ([text] (scheme! text :compile))
  ([text mode]
   (when (trace?) (scheme-trace! text))
   (if (= mode :interpret)
     (s/call "interpret-string" text)
     (s/eval-string text))))

(declare ensure-loaded! ancestry-of-unlinked lambda-class link registration-text)

(defn- class-info [cname]
  (or (get-in @state [:classes cname])
      (do (ensure-loaded! cname) (get-in @state [:classes cname]))))

;; --- class bytes -----------------------------------------------------------------
;; JDK classes come from $JAVA_HOME/jmods. A .jmod is a zip behind a 4-byte
;; "JM" header, and jolt's ZipFile shim wants a plain zip, so each module's
;; zip part is copied once, the first time a class is looked up in it, under
;; the cache root. java.base is tried first, then the other modules in name
;; order. User classes come from the classpath: directories and jars.

(defn- java-home []
  (or (env "JAVA_HOME") (throw (ex-info "JAVA_HOME is not set" {}))))

(defn- cache-root []
  (io/file (or (env "CHAMPACA_CACHE_DIR") (str (env "HOME") "/.cache/champaca"))))

(defn- jmods-dir []
  (let [release (io/file (java-home) "release")]
    (io/file (cache-root) "jmods"
             (Long/toHexString (bit-and (hash [(java-home) (when (.exists release) (slurp release))])
                                        0xffffffff)))))

(declare jmod-zip-base)

(defn- jmod-zip [module]
  (or (get-in @state [:jmods module])
      (let [out (io/file (jmods-dir) (str module ".zip"))]
        (when-not (.exists out)
          ;; copied beside, then renamed, so a concurrent run never opens half
          (let [tmp (io/file (str out ".tmp-" (System/nanoTime)))]
            (io/make-parents tmp)
            (let [jm (io/file (java-home) "jmods" (str module ".jmod"))]
              (with-open [in (io/input-stream jm)]
                ;; from where its offsets count, so they hold in the copy
                (.skip in (jmod-zip-base jm))
                (io/copy in tmp)))
            (when-not (.renameTo tmp out) (.delete tmp))))
        (let [zf (ZipFile. (str out))]
          (swap! state assoc-in [:jmods module] zf)
          zf))))

;; Which module holds a package comes from each jmod's zip central directory,
;; read in place so no module is copied just to be searched. jlink writes no
;; zip comment, so the end-of-directory record is the file's last 22 bytes.
;; The index is saved beside the copies.

(defn- le16 [b i] (bit-or (bit-and (aget b i) 0xff) (bit-shift-left (bit-and (aget b (inc i)) 0xff) 8)))
(defn- le32 [b i] (bit-or (le16 b i) (bit-shift-left (le16 b (+ i 2)) 16)))

;; skip is an lseek on the jolt pinned in deps.edn (jolt#1375); a released
;; 0.8.22 reads the skipped bytes, slower but the same answer.
(defn- read-at [f off n]
  (with-open [in (java.io.FileInputStream. f)]
    (.skip in off)
    (let [b (byte-array n)]
      (loop [got 0]
        (if (< got n)
          (let [r (.read in b got (- n got))]
            (if (neg? r) (throw (ex-info "short read" {:file (str f) :offset off :wanted n})) (recur (+ got r))))
          b)))))

(defn- jmod-eocd [f]
  (let [eocd (read-at f (- (.length f) 22) 22)]
    (when-not (= 0x06054b50 (le32 eocd 0))
      (throw (ex-info "jmod has no plain end-of-directory record" {:file (str f)})))
    eocd))

;; A jmod is a 4-byte header and then a zip. The JDK's jmod tool counts the
;; zip's offsets from the end of the header, but not every build does: 30 of
;; GraalVM CE 25.0.1's 84 jmods count them from the start of the file. The
;; base is whichever puts the central directory's signature at its offset.
(defn- jmod-zip-base [f]
  (let [off (le32 (jmod-eocd f) 16)
        cd-at? (fn [base] (= 0x02014b50 (le32 (read-at f (+ base off) 4) 0)))]
    (cond (cd-at? 4) 4
          (cd-at? 0) 0
          :else (throw (ex-info "jmod's central directory is not where its end record says"
                                {:file (str f) :offset off})))))

(defn- jmod-packages
  "The packages with classes in one jmod, as \"java/util/zip\"."
  [f]
  (let [eocd (jmod-eocd f)
        size (le32 eocd 12)
        cd (read-at f (+ (jmod-zip-base f) (le32 eocd 16)) size)]
    (loop [i 0 acc (transient #{})]
      (if (>= i size)
        (persistent! acc)
        (let [nl (le16 cd (+ i 28))
              entry (String. cd (+ i 46) nl "UTF-8")
              slash (str/last-index-of entry "/")]
          (recur (+ i 46 nl (le16 cd (+ i 30)) (le16 cd (+ i 32)))
                 (if (and (str/starts-with? entry "classes/") (str/ends-with? entry ".class") (> slash 7))
                   (conj! acc (subs entry 8 slash))
                   acc)))))))

(defn- package-modules []
  (or (:package-modules @state)
      (let [f (io/file (jmods-dir) "packages.edn")
            idx (if (.exists f)
                  (edn/read-string (slurp f))
                  (let [idx (into {}
                                  (for [jm (.listFiles (io/file (java-home) "jmods"))
                                        :let [n (.getName jm)]
                                        :when (str/ends-with? n ".jmod")
                                        pkg (jmod-packages jm)]
                                    [pkg (subs n 0 (- (count n) 5))]))
                        tmp (io/file (str f ".tmp-" (System/nanoTime)))]
                    (io/make-parents tmp)
                    (spit tmp (pr-str idx))
                    (when-not (.renameTo tmp f) (.delete tmp))
                    idx))]
        (swap! state assoc :package-modules idx)
        idx)))

(defn- read-entry [zf entry-name]
  (when-let [e (.getEntry zf entry-name)]
    (with-open [in (.getInputStream zf e)] (.readAllBytes in))))

(defn- jar [path]
  (or (get-in @state [:jars path])
      (let [zf (ZipFile. (str path))]
        (swap! state assoc-in [:jars path] zf)
        zf)))

(defn- user-class-bytes [cname]
  (let [rel (str cname ".class")]
    (some (fn [entry]
            (if (str/ends-with? (str entry) ".jar")
              (read-entry (jar entry) rel)
              (let [f (io/file entry rel)]
                (when (.exists f)
                  (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String [])))))))
          (:classpath @state))))

(defn- jdk-class-bytes [cname]
  (when-let [slash (str/last-index-of cname "/")]
    (when-let [module (get (package-modules) (subs cname 0 slash))]
      (read-entry (jmod-zip module) (str "classes/" cname ".class")))))

(defn- class-bytes [cname]
  (or (user-class-bytes cname) (jdk-class-bytes cname)))

(defn- user-resource-bytes [path]
  (some (fn [entry]
          (if (str/ends-with? (str entry) ".jar")
            (read-entry (jar entry) path)
            (let [f (io/file entry path)]
              (when (.isFile f)
                (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String [])))))))
        (:classpath @state)))

(defn- jdk-resource-bytes
  "path from the module owning the nearest enclosing package. The index
  lists packages with classes, and a resource directory (ICU's data, say)
  may have none of its own."
  [path]
  (loop [dir (some->> (str/last-index-of path "/") (subs path 0))]
    (when dir
      (or (when-let [module (get (package-modules) dir)]
            (read-entry (jmod-zip module) (str "classes/" path)))
          (recur (some->> (str/last-index-of dir "/") (subs dir 0)))))))

(defn- plain-resource-path?
  "A resource name that stays inside its classpath entry: no empty, . or ..
  segment, no leading /."
  [path]
  (and (not (str/blank? path))
       (not-any? #{"" "." ".."} (str/split path #"/"))))

(defn- resource-bytes
  "The JDK's modules first, as a parent-first class loader would, then the
  classpath."
  [path]
  (when (plain-resource-path? path)
    (or (jdk-resource-bytes path) (user-resource-bytes path))))

(defn- resource-urls
  "Every classpath entry's URL for path, file: for a directory and jar: for a
  jar, in classpath order. JDK resources have none: their jrt: URLs need the
  runtime image."
  [path]
  (for [entry (when (plain-resource-path? path) (:classpath @state))
        :let [f (io/file entry)
              abs (.getAbsolutePath f)]
        :when (if (str/ends-with? (str entry) ".jar")
                (some? (.getEntry (jar entry) path))
                (.isFile (io/file entry path)))]
    ;; File.toURI percent-encodes as the JDK's class loaders do; an entry name
    ;; is encoded the same way, through a File standing for "/<path>"
    (if (str/ends-with? (str entry) ".jar")
      (str "jar:" (.toURI f) "!" (subs (str (.toURI (io/file (str "/" path)))) 5))
      (str (.toURI (io/file abs path))))))

(defn- record!
  "Keep code for the startup cache when it belongs to a JDK class: {:text t}
  to compile when the cache is written, or {:fasl bv}, object code already
  compiled (compile-unit). unit says what it defines: [cname] a class's
  registration, [cname key] one method body."
  [origin unit code]
  (when-let [rec (:recording @state)]
    (when (= origin :jdk) (vswap! rec conj (assoc code :unit unit)))))

(defn- compile-to-cache?
  "Whether a JDK method should compile straight to object code kept for the
  cache (compile-unit): during boot, whose cache write always follows, or
  when this run saves a generation at exit (run does). Otherwise it is
  recorded as text, cheaper to compile, and compiled again only if
  save-cache! is called after all."
  [origin]
  (and (= origin :jdk) (:recording @state)
       (or (nil? (:boot-status @state)) (:saves-generation @state))))

(defn- write-units!
  "Write units to path as one object file: text compiled here, fasl appended
  as it is. Returns each unit's start offset, then the file's length. With
  batch?, consecutive texts compile in one call (Chez pays per call), and
  their offsets are the run's start, so nothing can load them one by one."
  [path units batch?]
  (s/call "begin-units!" path)
  (let [runs (if batch? (partition-by #(boolean (:fasl %)) units) (map vector units))
        starts (try
                 (reduce (fn [acc run]
                           (if (:fasl (first run))
                             (into acc (map #(s/call "add-fasl!" (:fasl %)) run))
                             (let [start (s/call "add-unit!" (str/join "\n" (map :text run)))]
                               (into acc (repeat (count run) start)))))
                         [] runs)
                 (catch Exception e
                   ;; close the file; the caller's temp name keeps it out of the cache
                   (s/call "end-units!")
                   (throw e)))]
    (conj starts (s/call "end-units!"))))

(defn- load-unit!
  "Run one unit of a cached generation: [file offset length]."
  [[file off len]]
  (swap! state update :units-loaded (fnil inc 0))
  (timed :cache-load-unit #(s/call "load-unit" (str (io/file (:cache-dir @state) file)) off len)))

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

(defn- iface-methods
  "[interface method] for every instance method the interfaces ifaces
  declare, private ones aside, grouped by name and descriptor."
  [ifaces]
  (group-by (fn [[_ m]] (str (:name m) (:desc m)))
            (for [i (distinct ifaces)
                  m (:methods (class-info i))
                  :when (and (:virtual m) (zero? (bit-and (:flags m) 0x0002)))]
              [i m])))

(defn- max-specific
  "Of cands, the [interface method] pairs declaring one method, the
  interface JVM selection picks (JVMS 5.4.3.3): of the declarations no other
  declaring interface extends, the one with a body, when exactly one has.
  nil otherwise."
  [cands]
  (let [maximal (if (next cands)
                  (let [extends? (fn [j i] (some #{i} (rest (ancestry j))))]
                    (remove (fn [[i _]] (some (fn [[j _]] (and (not= i j) (extends? j i))) cands)) cands))
                  cands)
        concrete (filter (fn [[_ m]] (:code m)) maximal)]
    (when (= 1 (count concrete)) (ffirst concrete))))

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
        (max-specific (get (iface-methods (filter #(cf/interface? (class-info %)) (ancestry cname)))
                           (str mname desc)))
        (some #(when (has? % true) %) (ancestry cname))
        (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(declare parsed overridden?)

(defn- inline-candidate
  "The parsed class and method decl.mname(desc), for codegen to inline into a
  method of caller-cname, or nil. Only a method in the caller's own class or
  in the JDK qualifies: a compiled method is cached with its own class, and a
  copy of another user class's code would outlive a change to that class.
  The JDK can't change under a cache, which is keyed by it. A method the
  runtime overrides never qualifies."
  [caller-cname decl mname desc]
  (let [info (class-info decl)
        im (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods info)))]
    ;; the class map answers most of it without reading the class file again
    (when (and im (:code im) (<= (or (:code-len im) 0) cg/leaf-limit)
               (or (= decl caller-cname) (= :jdk (:origin info)))
               (not (overridden? decl im)))
      ;; only a class already parsed in this run: reading one again from
      ;; its jmod costs a warm start more than the inlining saves
      (when-let [p (if (= decl caller-cname) (parsed decl) (:parsed info))]
        {:cls p :method (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))}))))

(defn- direct-target
  "The class whose cname.mname(desc) an invokevirtual or invokeinterface
  always reaches, or nil: the method it resolves to is private or final, or
  its class is final, so no override can be picked at run time."
  [cname mname desc]
  (when-not (str/starts-with? cname "[")
    ;; a method only an interface declares, without a body, doesn't resolve
    ;; here; dispatch handles it
    (when-let [decl (try (resolve-method cname mname desc) (catch Exception _ nil))]
     (let [m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods (class-info decl))))]
      (when (and m (not (cf/static? m))
                 (or (pos? (bit-and (:flags m) 0x0012))
                     (pos? (bit-and (:flags (class-info cname)) 0x0010))))
        decl)))))

(def ^:private resolver
  {:ensure-loaded #(ensure-loaded! %)
   :inline-candidate inline-candidate
   :direct-target direct-target
   :resolve-static-field resolve-static-field
   :field-index field-index
   :resolve-method resolve-method
   :lambda-class #(lambda-class %1 %2)})

;; --- loading -----------------------------------------------------------------------

(defn- normalize
  "Parse cname's classfile into the linker's class map."
  [cname]
  (let [user (timed :class-bytes #(user-class-bytes cname))
        bytes (or user (timed :class-bytes #(jdk-class-bytes cname))
                  (throw (ex-info (str "class not found: " cname) {:class cname})))
        c (timed :cf-parse #(cf/parse bytes))]
    {:name cname :super (:super c) :interfaces (:interfaces c) :parsed c :flags (:flags c)
     ;; only JDK classes are cached; user classes change between runs
     :origin (if user :user :jdk)
     :reflection (:reflection c)
     :fields (mapv (fn [f] (merge {:name (:name f) :desc (:desc f) :flags (:flags f)
                                   :static (cf/static? f) :constant (:constant f)}
                                  (select-keys f [:signature :annotations])))
                   (:fields c))
     :methods (mapv (fn [m] (assoc m
                                   :native (cf/native? m)
                                   ;; kept in the startup cache, which drops
                                   ;; the bytecode: inlining checks it first
                                   :code-len (some-> (:code m) :code count)
                                   :virtual (not (or (cf/static? m) (str/starts-with? (:name m) "<")))))
                    (:methods c))}))

(defn- overridden?
  "Whether the runtime overrides cname's method m. An abstract method with an
  override (VarHandle.withInvokeExactBehavior, natives.ss) gets a vtable entry
  and a stub like a method with code."
  [cname m]
  (boolean (s/call "native-override" (str cname "." (:name m) (:desc m)))))

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
        ;; the superclass's entries for methods some class declares; the
        ;; defaults it picked are picked again below, since this class may
        ;; implement a more specific interface
        vtable (into (into {} (remove (fn [[_ g]] (cf/interface? (class-info (subs g 0 (str/index-of g ".")))))
                                      (:vtable sup)))
                     (for [m (:methods info)
                           :when (and (:virtual m) (or (:code m) (:native m) (overridden? (:name info) m)))]
                       [(str (:name m) (:desc m)) (str (:name info) "." (:name m) (:desc m))]))
        defaults (for [[k cands] (iface-methods (filter #(cf/interface? (class-info %))
                                                        (rest (ancestry-of-unlinked info))))
                       :when (not (contains? vtable k))
                       :let [i (max-specific cands)]
                       :when i]
                   [k (str i "." k)])
        vtable (into vtable defaults)
        ;; an interface's private methods aren't inherited and pick no
        ;; default, but javac calls them with invokeinterface on the
        ;; receiver, which dispatches through this vtable
        privates (for [i (filter #(cf/interface? (class-info %)) (rest (ancestry-of-unlinked info)))
                       m (:methods (class-info i))
                       :when (and (:virtual m) (:code m) (pos? (bit-and (:flags m) 0x0002)))]
                   [(str (:name m) (:desc m)) (str i "." (:name m) (:desc m))])
        vtable (reduce (fn [vt [k g]] (if (contains? vt k) vt (assoc vt k g))) vtable privates)]
    (assoc info :layout layout :template template :vtable vtable)))

(defn- ancestry-of-unlinked
  "ancestry for a class that is not in the registry yet."
  [info]
  (cons (:name info)
        (distinct (mapcat ancestry (remove nil? (cons (:super info) (:interfaces info)))))))

(defn- constant-text [{:keys [tag value]}]
  (case tag
    (:int :long :float :double) (cg/num-lit value)
    :string (cg/jstring-lit-form value)))

;; A reflection attribute as a Scheme literal: a Signature string, raw
;; annotation bytes, or #f when the classfile has none.
(defn- refl-lit [v]
  (cond (nil? v) "#f"
        (string? v) (cg/str-lit v)
        :else (str "#vu8" (apply cg/sx (map str v)))))

(defn- registration-text [{:keys [name super interfaces template vtable layout fields methods flags reflection]}]
  (str/join
   "\n"
   (concat
    [(cg/sx "register-class!" (cg/str-lit name) (if super (cg/str-lit super) "#f")
            (str "'" (apply cg/sx (map cg/str-lit interfaces)))
            (apply cg/sx "vector" template)
            ;; the vtable as changes to the superclass's: printing every
            ;; inherited entry made it a third of the startup cache's source
            (str "'" (apply cg/sx (let [sup-vt (when super (:vtable (class-info super)))]
                                    (concat
                                     (for [[k g] (sort vtable) :when (not= g (get sup-vt k))]
                                       (str "(" (cg/str-lit k) " . " (cg/sym g) ")"))
                                     (for [k (sort (keys sup-vt)) :when (not (contains? vtable k))]
                                       (str "(" (cg/str-lit k) " . #f)"))))))
            (str "'" (apply cg/sx (for [[k i] (sort layout)] (str "(" (cg/str-lit k) " . " i ")"))))
            (str flags)
            ;; declared members, in classfile order, for reflection:
            ;; methods (name desc flags signature annotations param-annotations
            ;; annotation-default), fields (name desc flags signature
            ;; annotations), then the class's own (signature annotations inner
            ;; enclosing-method)
            (str "'" (cg/sx (apply cg/sx (for [m methods]
                                           (cg/sx (cg/str-lit (:name m)) (cg/str-lit (:desc m)) (str (:flags m))
                                                  (refl-lit (:signature m)) (refl-lit (:annotations m))
                                                  (refl-lit (:param-annotations m)) (refl-lit (:annotation-default m)))))
                            (apply cg/sx (for [f fields]
                                           (cg/sx (cg/str-lit (:name f)) (cg/str-lit (:desc f)) (str (or (:flags f) 0))
                                                  (refl-lit (:signature f)) (refl-lit (:annotations f)))))
                            (let [{:keys [inner enclosing-method]} reflection]
                              (cg/sx (refl-lit (:signature reflection)) (refl-lit (:annotations reflection))
                                     ;; (outer simple-name flags): InnerClasses' entry for this class
                                     (if inner
                                       (cg/sx (refl-lit (:outer inner)) (refl-lit (:simple-name inner)) (str (:flags inner)))
                                       "#f")
                                     ;; (class name desc): EnclosingMethod
                                     (if enclosing-method
                                       (cg/sx (refl-lit (:class enclosing-method)) (refl-lit (:name enclosing-method))
                                              (refl-lit (:desc enclosing-method)))
                                       "#f"))))))]
    (for [f fields :when (:static f)]
      (cg/sx "define" (cg/static-global name (:name f))
             (if-let [k (:constant f)] (constant-text k) (cg/default-value (:desc f)))))
    ;; every method's definition from one form: a native's from natives.ss,
    ;; any other's from method-stub, which returns a runtime override when
    ;; natives.ss has one, else a closure that JIT-compiles the method on
    ;; first call
    [(cg/sx "define-methods!" (cg/str-lit name)
            (str "'" (apply cg/sx (for [m methods
                                        :when (or (:native m) (:code m) (overridden? name m))]
                                    (cg/sx (cg/str-lit (:name m)) (cg/str-lit (:desc m))
                                           (cg/method-global name (:name m) (:desc m))
                                           (if (:native m) "#t" "#f"))))))])))

(defn ensure-loaded! [cname]
  (cond
    ;; registered by an earlier run: its class table entry came from the
    ;; cache, and its registration is one unit of a cached generation
    (contains? (:lazy @state) cname)
    (let [info (get-in @state [:classes cname])]
      (swap! state update :lazy disj cname)
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (load-unit! (:cached-class info)))

    (not (get-in @state [:classes cname]))
    (let [info (timed :parse #(normalize cname))]
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (when-not (get-in @state [:classes cname])
        (let [linked (timed :link #(link info))]
          (swap! state assoc-in [:classes cname] linked)
          (let [text (timed :register-text #(registration-text linked))]
            (record! (:origin linked) [cname] {:text text})
            (timed :register-eval #(scheme! text :interpret)))))))
  cname)

(defn lambda-class
  "The synthetic class implementing ifaces for lambdas whose abstract
  method keys (\"name(desc)\", the SAM plus any bridges) are sam-keys.
  Slot 0 holds the closure; every SAM key maps to champaca/Lambda.invoke,
  which calls it."
  [ifaces sam-keys]
  (let [n (str "champaca/Lambda$" (str/replace (first ifaces) "/" "$") "$" (Math/abs (hash [ifaces sam-keys])))]
    (if (get-in @state [:classes n])
      (ensure-loaded! n) ; an earlier run's, maybe not registered in this one yet
      (do (run! ensure-loaded! ifaces)
          (let [info {:name n :super "java/lang/Object" :interfaces (vec ifaces) :flags 0x1011
                      :fields [{:name "fn" :desc "Ljava/lang/Object;" :flags 0x12 :static false}] :methods []}
                linked (reduce #(assoc-in %1 [:vtable %2] "champaca/Lambda.invoke") (link info) sam-keys)]
            (swap! state assoc-in [:classes n] linked)
            (let [text (registration-text linked)]
              (record! :jdk [n] {:text text}) ; pure data, safe to cache whoever asked
              (scheme! text :interpret)))))
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

(defn- compile-method! [cname mname desc]
  (let [p (parsed cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))
        info (assoc (class-info cname) :parsed p)]
    (swap! state update :jitted inc)
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (let [text (timed :codegen #(cg/compile-method (:parsed info) m resolver))
          unit [cname (str mname desc)]]
      (if (compile-to-cache? (:origin info))
        ;; compiled once: the same object code runs now and goes in the cache
        (let [bv (timed :chez-compile #(do (when (trace?) (scheme-trace! text))
                                           (s/call "compile-unit" text)))]
          (record! :jdk unit {:fasl bv}))
        (timed :chez-compile #(scheme! text))))))

(defn jit! [cname mname desc]
  (if-let [unit (get-in @state [:classes cname :cached-methods (str mname desc)])]
    (load-unit! unit) ; compiled by an earlier run
    (compile-method! cname mname desc)))

(defn- stats []
  (let [{:keys [classes lazy jitted units-loaded]} @state]
    (binding [*out* *err*]
      (println (str "champaca: " (- (count classes) (count lazy)) " classes loaded, " jitted " methods compiled, "
                    (or units-loaded 0) " cached units loaded")))))

;; --- boot cache ---------------------------------------------------------------------
;; Modeled on Clojure's RT.load: one compiled unit per input, used only when
;; it is newer than its source, and a miss quietly takes the slow path. The
;; unit here is the whole JDK boot. Clojure's key is a timestamp; this key
;; also covers the translator itself, because a cache built by a different
;; translator is stale even when the JDK is not (Clojure's AOT
;; version-mismatch trap).

(def ^:private runtime-files
  "The Scheme runtime, in load order."
  ["champaca/runtime.ss" "champaca/natives.ss" "champaca/reflect.ss" "champaca/handles.ss" "champaca/files.ss"])

(defn- translator-fingerprint []
  (hash (apply str (map #(slurp (io/resource %))
                        (concat runtime-files
                                ["champaca/codegen.clj" "champaca/main.clj" "champaca/classfile.clj"])))))

(defn- cache-dir []
  (when-not (= "off" (env "CHAMPACA_CACHE"))
    (let [jmod (io/file (java-home) "jmods" "java.base.jmod")
          release (io/file (java-home) "release")
          ;; CHAMPACA_BT changes the generated code (shadow-stack wrappers that
          ;; hide frame names from getCallerClass), so it gets its own cache
          key (hash [(when (.exists release) (slurp release)) (.length jmod) (.lastModified jmod)
                     (translator-fingerprint) (img/runtime-version) (some? (env "CHAMPACA_BT"))])]
      (io/file (cache-root) (Long/toHexString (bit-and key 0xffffffff))))))

(defn- strip-for-cache
  "The linker's class map for JDK classes only, without bytecode: methods
  keep :code as a flag."
  [classes]
  (into {}
        (for [[k info] classes :when (not= :user (:origin info))]
          [k (-> info
                 (dissoc :parsed)
                 (update :methods (fn [ms] (mapv #(cond-> % (:code %) (assoc :code true)) ms))))])))

(defn- dump-classes! [img]
  (img/dump! (str img) (strip-for-cache (:classes @state))))

(defn- delete-tree! [f] (doseq [x (reverse (file-seq f))] (.delete x)))

;; A key directory is left behind whenever its key changes (a translator edit,
;; a JDK update), so writing a new one removes siblings no run has loaded for a
;; week: a hit touches its directory. Other live keys (CHAMPACA_BT, a second
;; JDK) stay as long as something uses them. jmods/ and jolt/ never match.
(def ^:private stale-ms (* 7 24 60 60 1000))

(defn- prune-cache-root! [keep]
  (let [cutoff (- (System/currentTimeMillis) stale-ms)]
    (doseq [d (.listFiles (.getParentFile keep))
            :when (and (.isDirectory d)
                       (re-matches #"[0-9a-f]{1,8}(\.tmp-\d+)?" (.getName d))
                       (not= (.getName d) (.getName keep))
                       (< (.lastModified d) cutoff))]
      (delete-tree! d))))

(defn- write-cache!
  "Compile the recorded boot forms to boot.so and save the class table to
  state.img, in a temp dir renamed into place so readers never see half."
  [dir forms]
  ;; boot! may have started the temp dir, with runtime.so already in it
  (let [pre (:cache-tmp @state)
        tmp (if (and pre (.exists (io/file pre "runtime.so"))) pre (io/file (str dir ".tmp-" (System/nanoTime))))]
    (swap! state dissoc :cache-tmp)
    (.mkdirs tmp)
    ;; registrations compile here; method bodies were compiled when they ran
    (timed :cache-write-boot #(write-units! (str (io/file tmp "boot.so")) forms true))
    ;; the runtime itself, so a warm start loads it compiled rather than
    ;; compiling its source again (about 130 ms)
    (when-not (.exists (io/file tmp "runtime.so"))
        (let [src (io/file tmp "runtime.ss")]
          (spit src (str/join "\n" (map #(slurp (io/resource %)) runtime-files)))
          (timed :cache-write-runtime #(s/call "quiet-compile-file" (str src) (str (io/file tmp "runtime.so"))))
          (.delete src)))
    (timed :cache-write-state #(dump-classes! (io/file tmp "state.img")))
    ;; a directory missing a file (a crash mid-write, a load that failed) would
    ;; block the rename forever, so it goes first
    (when (and (.exists dir) (not-every? #(.exists (io/file dir %)) ["boot.so" "state.img" "runtime.so"]))
      (delete-tree! dir))
    (if (.renameTo tmp dir)
      (prune-cache-root! dir)
      ;; another run won the race; ours is redundant
      (delete-tree! tmp))))

(defn- load-cache!
  "Restore a cached boot. Later generations stay on disk until something
  reaches them: their classes are listed in :lazy, and the class table says
  where each class's registration and each method body sits. Returns true on
  a hit."
  [dir]
  (let [so (io/file dir "boot.so") img-file (io/file dir "state.img")]
    (when (and dir (.exists so) (.exists img-file))
      (.setLastModified dir (System/currentTimeMillis))   ; keeps it from being pruned
      (try
        (let [classes (timed :cache-read-state #(img/read-image (str img-file)))]
          (swap! state assoc :classes classes
                 :lazy (set (for [[k v] classes :when (:cached-class v)] k)))
          (timed :cache-load-code #(s/call "load" (str so)))
          true)
        ;; a damaged cache would fail every run the same way: drop it, boot
        ;; cold, and the cold boot writes a new one
        (catch Exception e
          (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
          (delete-tree! dir)
          (swap! state assoc :classes {} :lazy #{})
          nil)))))

(defn save-generation!
  "Append what this run compiled for JDK classes after boot as one more
  extra-N.so, compiled unit by unit, and rewrite state.img (written beside,
  then renamed) with where each unit sits."
  []
  (let [dir (:cache-dir @state)
        units (some-> (:recording @state) deref)]
    (when (and dir (.isDirectory dir) (seq units))
      (let [stamp (System/currentTimeMillis)
            file (str "extra-" stamp ".so")
            so-tmp (io/file dir (str file ".tmp"))
            img-tmp (io/file dir (str "state.img.tmp-" stamp))
            offsets (write-units! (str so-tmp) units false)
            starts (pop offsets)
            ends (subvec offsets 1)]
        (.renameTo so-tmp (io/file dir file))
        (doseq [[{[cname k] :unit} start end] (map vector units starts ends)]
          (swap! state assoc-in (if k [:classes cname :cached-methods k] [:classes cname :cached-class])
                 [file start (- end start)]))
        (dump-classes! img-tmp)
        (.renameTo img-tmp (io/file dir "state.img"))
        (vreset! (:recording @state) [])))))

(defn- boot!
  "Load the runtime, then run System.initPhase1 the way the JVM does before
  main. Uses the boot cache when it is valid and writes it when it is not.
  Returns 0 on success."
  []
  (let [dir (cache-dir)
        so (when dir (io/file dir "runtime.so"))]
    ;; the cache key covers the runtime's source, so a cached runtime.so is
    ;; always this source compiled
    (when-not (and so (.exists so)
                   (try (timed :cache-load-runtime #(s/call "load" (str so))) true
                        (catch Exception e
                          (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
                          (delete-tree! dir)
                          false)))
      (if dir
        ;; a cold start writes runtime.so into the cache anyway, so compile it
        ;; first and load it, rather than evaluating the source and compiling
        ;; it again for the cache (write-cache! moves this file into place)
        ;; inside the temp dir write-cache! will fill and rename, so a run
        ;; that dies first leaves it where prune-cache-root! looks
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
          (swap! state assoc :cache-tmp tmp))
        (doseq [f runtime-files] (scheme! (slurp (io/resource f)))))))
  (s/call "set-hooks!" jit! (fn [n] (ensure-loaded! n) nil))
  ;; annotation instances: a synthetic class implementing the annotation
  ;; interface, its element keys "name()desc" joined by |
  (s/call "set-annotation-class-hook!" (fn [iface keys]
                                         (lambda-class [iface] (if (= "" keys) [] (str/split keys #"\|")))))
  ;; ConstantPool natives (annotations): a constant's value, or a class's name
  (s/call "set-resource-hook!" (fn [path] (or (resource-bytes path) false))
          ;; URLs as one string, one per line
          (fn [path] (str/join "\n" (resource-urls path))))
  (s/call "set-cp-hook!" (fn [cname i]
                           (let [e (get (:cp (parsed cname)) i)]
                             (let [v (or (:value e) (:name e)
                                         (throw (ex-info "no such constant" {:class cname :index i})))]
                               ;; a lone surrogate's code units (classfile/decode-mutf8)
                               ;; go over as a Scheme vector, which jstring takes
                               (if (vector? v) (apply s/call "vector" v) v)))))
  (let [dir (cache-dir)
        hit? (load-cache! dir)]
    (when dir (swap! state assoc :recording (volatile! []) :cache-dir dir))
    (run! ensure-loaded! ["java/lang/Object" "java/lang/String" "java/lang/Class"])
    (let [status (s/eval-string
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
                     (install-unnamed-module!)))")]
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
  (swap! state assoc :saves-generation true) ; save-generation! below
  (let [t0 (System/nanoTime)
        status (init! classpath)
        _ (when (env "CHAMPACA_PROF") (prof-report "boot" (- (System/nanoTime) t0)))]
    (when (env "CHAMPACA_STATS") (binding [*out* *err*] (print "after initPhase1: ")) (stats))
    (if (zero? status)
      (let [status (apply s/call "run-main" cname args)]
        (when (env "CHAMPACA_STATS") (stats))
        (save-generation!)
        status)
      status)))

(defn -main [& argv]
  (let [[cp cname & args] argv]
    (when-not cname
      (binding [*out* *err*] (println "usage: jolt -M -m champaca.main <classpath[:entry...] (dirs and jars)> <MainClass> [args...]"))
      (System/exit 2))
    (System/exit (run (str/split cp #":") (str/replace cname "." "/") (vec args)))))
