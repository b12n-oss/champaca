(ns champaca.main
  "Class loading, linking and the JIT hook, plus the command-line entry.

  Classes come from the user classpath first, then from the JDK's own
  runtime image. Loading a class registers it with the Scheme runtime and
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
            [champaca.jimage :as ji]
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

(declare ensure-loaded! ancestry-of-unlinked lambda-class link registration-text delete-tree!)

(defn- parse-entry
  "edn/read-string for one packed class entry. Damage inside an entry of an
  otherwise readable state.img surfaces here, mid-run, where booting cold is
  no longer possible (generations are already half loaded). The cache goes,
  so the next run boots cold, and this run fails loudly."
  [cname e]
  (try (edn/read-string e)
       (catch Exception ex
         (binding [*out* *err*]
           (println "champaca: discarding a damaged startup cache (bad entry for"
                    (str cname "):") (ex-message ex)))
         (when-let [dir (:cache-dir @state)] (delete-tree! dir))
         (throw ex))))

(defn- thaw!
  "The class table entry for cname as a map, or nil. A cache hit leaves
  every entry packed as edn text; the first read parses it and puts the
  map back, so a run only pays for the classes it touches. The swap keeps
  a map that is already there, so a racing thaw or a concurrent assoc-in
  on the same entry is never overwritten."
  [cname]
  (let [e (get-in @state [:classes cname])]
    (if (string? e)
      (let [u (parse-entry cname e)]
        (-> (swap! state update-in [:classes cname] #(if (string? %) u %))
            (get-in [:classes cname])))
      e)))

(defn- class-info [cname]
  (or (thaw! cname)
      (do (ensure-loaded! cname) (thaw! cname))))

;; --- class bytes -----------------------------------------------------------------
;; JDK classes come from $JAVA_HOME/lib/modules, the runtime image every JDK
;; has and the JVM itself loads them from (champaca.jimage reads it). A
;; class's package names its module through the image's /packages entries.
;; User classes come from the classpath: directories and jars.

(defn- java-home
  "JAVA_HOME, which must hold a runtime image: every JDK since 9 does, and so
  does a jlink-built runtime."
  []
  (let [h (or (env "JAVA_HOME") (throw (ex-info "JAVA_HOME is not set" {})))]
    (when-not (.isFile (io/file h "lib" "modules"))
      (throw (ex-info (str "champaca reads the JDK's classes from $JAVA_HOME/lib/modules, and " h
                           " has none. JAVA_HOME must be a JDK 25 (or a runtime jlink built from one).")
                      {:java-home h})))
    h))

(defn- cache-root []
  (io/file (or (env "CHAMPACA_CACHE_DIR") (str (env "HOME") "/.cache/champaca"))))

(defn- jdk-image []
  (or (:image @state)
      ;; two threads may both open it: the first stored wins, and the other
      ;; closes its file
      (let [i (ji/open (io/file (java-home) "lib" "modules"))
            kept (:image (swap! state update :image #(or % i)))]
        (when-not (identical? kept i) (.close ^java.io.RandomAccessFile (:raf i)))
        kept)))

(defn- package-module
  "The JDK module holding package dir (\"java/util/zip\"), or nil, looked up
  once per package."
  [dir]
  (let [m (:package-modules @state)]
    (if (contains? m dir)
      (get m dir)
      (let [mod (ji/package-module (jdk-image) dir)]
        (swap! state assoc-in [:package-modules dir] mod)
        mod))))

(defn- read-entry [zf entry-name]
  (when-let [e (.getEntry zf entry-name)]
    (with-open [in (.getInputStream zf e)] (.readAllBytes in))))

(defn- jar [path]
  (or (get-in @state [:jars path])
      (let [zf (ZipFile. (str path))]
        (swap! state assoc-in [:jars path] zf)
        zf)))

(defn- package-dir [cname] (subs cname 0 (or (str/last-index-of cname "/") 0)))

(defn- user-class-bytes
  "cname's bytes from the first classpath entry that has it. The first class
  read from a package also decides which entry defines that package, as the
  JVM's class loaders define a package with the first class they define in
  it (package-info reads that entry's manifest)."
  [cname]
  (let [rel (str cname ".class")]
    (some (fn [entry]
            (when-let [bs (if (str/ends-with? (str entry) ".jar")
                            (read-entry (jar entry) rel)
                            (let [f (io/file entry rel)]
                              (when (.exists f)
                                (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String []))))))]
              (swap! state update :package-entries
                     (fn [m] (let [d (package-dir cname)] (if (contains? m d) m (assoc m d entry)))))
              bs))
          (:classpath @state))))

(defn- jdk-class-bytes [cname]
  (when-let [slash (str/last-index-of cname "/")]
    (when-let [module (package-module (subs cname 0 slash))]
      (ji/entry (jdk-image) (str "/" module "/" cname ".class")))))

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
  "path from the module holding its directory. The image counts a directory
  of resources alone as a package, so ICU's data has a module too."
  [path]
  (when-let [slash (str/last-index-of path "/")]
    (when-let [module (package-module (subs path 0 slash))]
      (ji/entry (jdk-image) (str "/" module "/" path)))))

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

(defn- ascii-lower
  "Lower case for attribute names, which are ASCII; String.toLowerCase would
  follow the default locale (a Turkish one maps I to a dotless i)."
  [s]
  (apply str (map #(let [c (int %)] (if (<= 65 c 90) (char (+ c 32)) %)) s)))

(defn- manifest-sections
  "A manifest's attributes by section, as java.util.jar.Manifest reads
  them: the main one under nil, a named one under its Name, keys in lower
  case (names compare without case). A line starting with a space continues
  the one before, joined as bytes so a character split across the wrap
  survives, and an unterminated last line is dropped. A header without
  \": \" or a section not opened by Name is the format error the JDK refuses,
  thrown here."
  [bs]
  (let [text (String. bs "ISO-8859-1")
        ;; the piece after the last line break is empty, or unterminated
        lines (butlast (str/split text #"\r\n|\r|\n" -1))
        lines (reduce (fn [acc l]
                        (if (and (str/starts-with? l " ") (seq acc))
                          (conj (pop acc) (str (peek acc) (subs l 1)))
                          (conj acc l)))
                      [] lines)
        utf8 (fn [s] (String. (.getBytes s "ISO-8859-1") "UTF-8"))]
    (loop [ls lines sec nil opening? false out {}]
      (if-let [l (first ls)]
        (if (str/blank? l)
          (recur (rest ls) sec true out)
          (let [i (or (str/index-of l ": ")
                      (throw (ex-info (str "invalid header: " (utf8 l)) {})))
                k (ascii-lower (subs l 0 i)) v (utf8 (subs l (+ i 2)))]
            (cond (not opening?) (recur (rest ls) sec false (assoc-in out [sec k] v))
                  (= k "name") (recur (rest ls) v false out)
                  :else (throw (ex-info (str "a section starts with " (utf8 (subs l 0 i)) ", not Name") {})))))
        out))))

(defn- jar-manifest
  "entry's parsed manifest, read once per jar; nil for a directory, a jar
  without one, or one the JDK would refuse (said on stderr, then ignored)."
  [entry]
  (let [cached (get-in @state [:manifests entry] ::unread)]
    (if (not= cached ::unread)
      cached
      (let [m (when (str/ends-with? (str entry) ".jar")
                (when-let [bs (read-entry (jar entry) "META-INF/MANIFEST.MF")]
                  (try (manifest-sections bs)
                       (catch Exception e
                         (binding [*out* *err*]
                           (println (str "champaca: ignoring the invalid manifest of " entry ": " (ex-message e))))
                         nil))))]
        (swap! state assoc-in [:manifests entry] m)
        m))))

(defn- package-info
  "What Class.getPackage needs about cname's package, one line each: user or
  jdk, then Specification-Title, -Version, -Vendor and Implementation-Title,
  -Version, -Vendor (+value, or - when absent), then the seal base (+url or
  -). The attributes come from the manifest of the classpath entry that
  defined the package (its first class read), the package's own section
  winning over the main one, as the JDK's class loaders read them. A
  directory has no manifest, and a package no classpath class defined (the
  JDK's, a generated class's) has no version information."
  [cname]
  (let [d (package-dir cname)
        entry (get-in @state [:package-entries d])]
    (if-not entry
      "jdk"
      (let [m (jar-manifest entry)
            section (str d "/")
            attr (fn [k] (or (get-in m [section k]) (get-in m [nil k])))
            line (fn [k] (if-let [v (attr k)] (str "+" v) "-"))]
        (str/join "\n"
                  (concat ["user"]
                          (map line ["specification-title" "specification-version" "specification-vendor"
                                     "implementation-title" "implementation-version" "implementation-vendor"])
                          [(if (= "true" (some-> (attr "sealed") ascii-lower))
                             (str "+" (.toURI (io/file (.getAbsolutePath (io/file entry)))))
                             "-")]))))))

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
  "Run one unit of a cached generation: [file offset length]. False when the
  generation file is gone (pruned under a live process, cleared by hand), so
  callers fall back to compiling and the next save re-caches the unit."
  [[file off len]]
  (let [f (io/file (:cache-dir @state) file)]
    (if (.exists f)
      (do (swap! state update :units-loaded (fnil inc 0))
          (timed :cache-load-unit #(s/call "load-unit" (str f) off len))
          true)
      false)))

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

(defn- specific-defaults
  "Of cands, the [interface method] pairs declaring one method, the
  maximally specific declarations with a body (JVMS 5.4.3.3): those no other
  declaring interface extends. One is the default interface dispatch picks,
  two or more conflict (the call throws IncompatibleClassChangeError), and
  none leaves the method abstract."
  [cands]
  (let [maximal (if (next cands)
                  (let [extends? (fn [j i] (some #{i} (rest (ancestry j))))]
                    (remove (fn [[i _]] (some (fn [[j _]] (and (not= i j) (extends? j i))) cands)) cands))
                  cands)]
    (filter (fn [[_ m]] (:code m)) maximal)))

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
        ;; a conflict resolves arbitrarily (JVMS 5.4.3.3); dispatch still throws
        (ffirst (specific-defaults (get (iface-methods (filter #(cf/interface? (class-info %)) (ancestry cname)))
                                       (str mname desc))))
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
      ;; the image costs a warm start more than the inlining saves
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
                                  (select-keys f [:signature :annotations :type-annotations])))
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
        vtable (into (into {} (remove (fn [[_ g]] (or (str/starts-with? g "icce:")
                                                      (cf/interface? (class-info (subs g 0 (str/index-of g "."))))))
                                      (:vtable sup)))
                     (for [m (:methods info)
                           :when (and (:virtual m) (or (:code m) (:native m) (overridden? (:name info) m)))]
                       [(str (:name m) (:desc m)) (str (:name info) "." (:name m) (:desc m))]))
        picked (for [[k cands] (iface-methods (filter #(cf/interface? (class-info %))
                                                      (rest (ancestry-of-unlinked info))))
                     :when (not (contains? vtable k))
                     :let [ds (specific-defaults cands)]
                     :when (seq ds)]
                 [k ds])
        ;; two or more maximally specific defaults conflict: the vtable entry
        ;; is a stub that throws, defined by this class's registration
        defaults (for [[k ds] picked]
                   [k (if (next ds) (str "icce:" (:name info) "." k) (str (ffirst ds) "." k))])
        conflicts (into {} (for [[k ds] picked :when (next ds)] [k (mapv first ds)]))
        vtable (into vtable defaults)
        ;; an interface's private methods aren't inherited and pick no
        ;; default, but javac calls them with invokeinterface on the
        ;; receiver, which dispatches through this vtable
        privates (for [i (filter #(cf/interface? (class-info %)) (rest (ancestry-of-unlinked info)))
                       m (:methods (class-info i))
                       :when (and (:virtual m) (:code m) (pos? (bit-and (:flags m) 0x0002)))]
                   [(str (:name m) (:desc m)) (str i "." (:name m) (:desc m))])
        vtable (reduce (fn [vt [k g]] (if (contains? vt k) vt (assoc vt k g))) vtable privates)]
    (assoc info :layout layout :template template :vtable vtable :conflicts conflicts)))

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

(defn- registration-text [{:keys [name super interfaces template vtable layout fields methods flags reflection conflicts]}]
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
            ;; annotation-default type-annotations exceptions parameters),
            ;; fields (name desc flags signature annotations type-annotations),
            ;; then the class's own (signature annotations inner
            ;; enclosing-method record-components type-annotations)
            (str "'" (cg/sx (apply cg/sx (for [m methods]
                                           (cg/sx (cg/str-lit (:name m)) (cg/str-lit (:desc m)) (str (:flags m))
                                                  (refl-lit (:signature m)) (refl-lit (:annotations m))
                                                  (refl-lit (:param-annotations m)) (refl-lit (:annotation-default m))
                                                  (refl-lit (:type-annotations m))
                                                  ;; Exceptions: class names
                                                  (if-let [exs (:exceptions m)] (apply cg/sx (map cg/str-lit exs)) "#f")
                                                  ;; MethodParameters: (name flags), name #f when absent
                                                  (if-let [ps (:parameters m)]
                                                    (apply cg/sx (for [[n fl] ps] (cg/sx (refl-lit n) (str fl))))
                                                    "#f"))))
                            (apply cg/sx (for [f fields]
                                           (cg/sx (cg/str-lit (:name f)) (cg/str-lit (:desc f)) (str (or (:flags f) 0))
                                                  (refl-lit (:signature f)) (refl-lit (:annotations f))
                                                  (refl-lit (:type-annotations f)))))
                            (let [{:keys [inner enclosing-method record]} reflection]
                              (cg/sx (refl-lit (:signature reflection)) (refl-lit (:annotations reflection))
                                     ;; (outer simple-name flags): InnerClasses' entry for this class
                                     (if inner
                                       (cg/sx (refl-lit (:outer inner)) (refl-lit (:simple-name inner)) (str (:flags inner)))
                                       "#f")
                                     ;; (class name desc): EnclosingMethod
                                     (if enclosing-method
                                       (cg/sx (refl-lit (:class enclosing-method)) (refl-lit (:name enclosing-method))
                                              (refl-lit (:desc enclosing-method)))
                                       "#f")
                                     ;; Record: (name desc signature annotations type-annotations)
                                     (if record
                                       (apply cg/sx (for [rc record]
                                                      (cg/sx (cg/str-lit (:name rc)) (cg/str-lit (:desc rc))
                                                             (refl-lit (:signature rc)) (refl-lit (:annotations rc))
                                                             (refl-lit (:type-annotations rc)))))
                                       "#f")
                                     (refl-lit (:type-annotations reflection)))))))]
    ;; a conflicting default's stub, with the JVM's message: the interfaces
    ;; in declaration order, dotted, without descriptors
    (for [[k is] (sort conflicts)
          :let [mname (subs k 0 (str/index-of k "("))]]
      (cg/sx "define" (cg/sym (str "icce:" name "." k))
             (cg/sx "icce-stub" (cg/str-lit (str "Conflicting default methods: "
                                                 (str/join " " (map #(str (str/replace % "/" ".") "." mname) is)))))))
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
    (let [info (thaw! cname)]
      (swap! state update :lazy disj cname)
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (or (load-unit! (:cached-class info))
          (let [text (registration-text info)]
            (record! (:origin info) [cname] {:text text})
            (scheme! text :interpret))))

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
  (or (:parsed (thaw! cname))
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
        ;; the text is kept (cheaply) so save-cache! can compile it into a
        ;; generation; without that a jolt program re-jits these every run
        (do (timed :chez-compile #(scheme! text))
            (record! (:origin info) unit {:text text}))))))

(defn saves-generation!
  "Tell the JIT this process will save a generation, so JDK methods compile
  once to object code (api save-cache! calls it on its first save)."
  []
  (swap! state assoc :saves-generation true))

(defn jit! [cname mname desc]
  (let [unit (get (:cached-methods (thaw! cname)) (str mname desc))]
    (when-not (and unit (load-unit! unit)) ; else compiled by an earlier run
      (compile-method! cname mname desc))))

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

;; The text of a helpful NPE message, from the failing method's bytecode
;; (champaca.npe, loaded on the first message so other runs don't pay for
;; it). The class is parsed again with its local variable names, which a
;; normal parse skips, so the work happens only when a message is asked
;; for; a site always gives the same text, so it is kept.
(defn- npe-message* [site]
  (let [at (str/last-index-of site "@")
        tag (subs site 0 at)
        bci (parse-long (subs site (inc at)))
        paren (str/index-of tag "(")
        dot (str/last-index-of tag "." paren)
        cname (subs tag 0 dot)
        mname (subs tag (inc dot) paren)
        desc (subs tag paren)
        p (binding [cf/*locals* true] (cf/parse (class-bytes cname)))
        m (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (:methods p))]
    (when (:code m)
      ;; HotSpot gives up silently on bytecode its analysis can't follow;
      ;; CHAMPACA_BT shows why this one did
      (try ((requiring-resolve 'champaca.npe/message) (:cp p) m bci)
           (catch Exception e
             (when (env "CHAMPACA_BT")
               (binding [*out* *err*] (println "champaca: no NPE message for" site (ex-message e))))
             nil)))))

(defn- npe-message [site]
  (let [cache (:npe-messages @state)]
    (if (contains? cache site)
      (get cache site)
      (let [msg (npe-message* site)]
        (swap! state assoc-in [:npe-messages site] msg)
        msg))))

(defn- translator-fingerprint []
  (hash (apply str (map #(slurp (io/resource %))
                        (concat runtime-files
                                ["champaca/codegen.clj" "champaca/main.clj" "champaca/classfile.clj" "champaca/jimage.clj"])))))

(defn- cache-dir []
  (when-not (= "off" (env "CHAMPACA_CACHE"))
    (let [modules (io/file (java-home) "lib" "modules")
          release (io/file (java-home) "release")
          ;; CHAMPACA_BT changes the generated code (shadow-stack wrappers that
          ;; hide frame names from getCallerClass), so it gets its own cache
          key (hash [(when (.exists release) (slurp release)) (.length modules) (.lastModified modules)
                     (translator-fingerprint) (img/runtime-version) (some? (env "CHAMPACA_BT"))])]
      (io/file (cache-root) (Long/toHexString (bit-and key 0xffffffff))))))

(defn- strip-for-cache
  "The linker's class map as edn text per class, JDK classes only, without
  bytecode: methods keep :code as a flag. An entry this run never thawed is
  already text and is kept as it is."
  [classes]
  (into {}
        (for [[k info] classes
              :let [s (if (string? info)
                        info
                        (when (not= :user (:origin info))
                          (pr-str (-> info
                                      (dissoc :parsed)
                                      (update :methods (fn [ms] (mapv #(cond-> % (:code %) (assoc :code true)) ms)))))))]
              :when s]
          [k s])))

(defn- dump-classes!
  "state.img: each class entry as edn text, plus the set of classes whose
  registration sits in a generation, so a load knows what is lazy without
  parsing anything."
  [img]
  (let [{:keys [classes lazy]} @state]
    (img/dump! (str img) {:lazy (into (set lazy)
                                      (for [[k v] classes :when (and (map? v) (:cached-class v))] k))
                          :classes (strip-for-cache classes)})))

(defn- delete-tree! [f] (doseq [x (reverse (file-seq f))] (.delete x)))

;; A key directory is left behind whenever its key changes (a translator edit,
;; a JDK update), so writing a new one removes siblings no run has loaded for a
;; week: a hit touches its directory. Other live keys (CHAMPACA_BT, a second
;; JDK) stay as long as something uses them. jolt/ never matches, nor does
;; jmods/, where older versions kept copies of the JDK's jmods.
;; The age rule alone is not enough on a machine where the translator itself
;; is edited: every edit is a new key, none a week old, and a day of work
;; leaves gigabytes. So the newest few keys survive and the rest go, however
;; young. Half-written .tmp- directories are collected by age only.
(def ^:private stale-ms (* 7 24 60 60 1000))
(def ^:private keep-keys 16)

(defn- prune-cache-root! [keep]
  (let [cutoff (- (System/currentTimeMillis) stale-ms)
        sibs (filter #(and (.isDirectory %)
                           (re-matches #"[0-9a-f]{1,8}(\.tmp-\d+)?" (.getName %))
                           (not= (.getName %) (.getName keep)))
                     (.listFiles (.getParentFile keep)))
        keys-newest-first (sort-by (juxt #(- (.lastModified %)) #(.getName %))
                                   (remove #(str/includes? (.getName %) ".tmp-") sibs))
        over (set (drop (dec keep-keys) keys-newest-first))]
    (doseq [d sibs
            :when (or (< (.lastModified d) cutoff) (over d))]
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
        (let [{:keys [classes lazy]} (timed :cache-read-state #(img/read-image (str img-file)))]
          (when-not (and (map? classes) (set? lazy))
            (throw (ex-info "not a champaca state image" {})))
          (swap! state assoc :classes classes :lazy lazy)
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
  then renamed) with where each unit sits. A save that fails drops its
  units rather than keeping them: retrying would fail the same way at every
  later save, and the code already ran, so only cache coverage is lost."
  []
  (let [dir (:cache-dir @state)
        units (some-> (:recording @state) deref)]
    (when (and dir (.isDirectory dir) (seq units))
      (vreset! (:recording @state) [])
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
        (.renameTo img-tmp (io/file dir "state.img"))))))

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
  (s/call "set-jpackage-hook!" package-info)
  (s/call "set-cp-hook!" (fn [cname i]
                           (let [e (get (:cp (parsed cname)) i)]
                             (let [v (or (:value e) (:name e)
                                         (throw (ex-info "no such constant" {:class cname :index i})))]
                               ;; a lone surrogate's code units (classfile/decode-mutf8)
                               ;; go over as a Scheme vector, which jstring takes
                               (if (vector? v) (apply s/call "vector" v) v)))))
  ;; helpful NPE messages: site "pkg/Cls.name(desc)@bci" -> text, or false
  (s/call "set-npe-hook!" (fn [site] (or (npe-message site) false)))
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
  (saves-generation!) ; save-generation! runs at exit below
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
