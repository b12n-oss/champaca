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
     (s/eval-string text)))
  ;; a method's text, with inspector information only when lines says
  ;; (runtime.ss eval-method-text; the runtime's global setting is #t)
  ([text _mode lines]
   (when (trace?) (scheme-trace! text))
   (s/call "eval-method-text" text (boolean lines))))

(declare ensure-loaded! ancestry-of-unlinked lambda-class link registration-text delete-tree!)

(defn- pack
  "A class entry's edn text as UTF-8 bytes, the form the caches keep it in
  and the class table holds it in until thawed: a Chez string takes four
  bytes a character, and the JDK's entries alone run to 19 million
  characters, 78 MB as strings against 19 as bytes."
  [^String text]
  (.getBytes text "UTF-8"))
(defn- packed? [x] (or (bytes? x) (string? x) (and (vector? x) (bytes? (first x)))))   ; a string: an entry made this run
;; An entry's text; a multi-part entry (packed-entry) answers its first part
(defn- unpack ^String [x] (cond (vector? x) (unpack (first x)) (bytes? x) (String. ^bytes x "UTF-8") (nil? x) nil :else x))
(defn- packed=
  "Whether two packed entries hold the same text (= on byte arrays is
  identity), every part of a multi-part entry."
  [a b]
  (and (some? a) (some? b)
       (= (if (vector? a) (mapv unpack a) [(unpack a)]) (if (vector? b) (mapv unpack b) [(unpack b)]))))

(def ^:private interned-strings (atom {}))
(defn- intern-string
  "One copy of each string the thawed entries hold: a method's key recurs in
  the vtable of every class that inherits it, and a descriptor in every
  method with that signature; read apart, each is its own string of four
  bytes a character."
  [^String s]
  (or (get @interned-strings s)
      (do (swap! interned-strings assoc s s) s)))
(defn- intern-keys
  "m with its string keys interned (and its string values when values? says)."
  [m values?]
  (if (map? m)
    (persistent! (reduce-kv (fn [acc k v] (assoc! acc (if (string? k) (intern-string k) k) (if (and values? (string? v)) (intern-string v) v)))
                            (transient {}) m))
    m))
(defn- intern-members [ms]
  (mapv #(cond-> % (string? (:name %)) (update :name intern-string) (string? (:desc %)) (update :desc intern-string)) ms))
(defn- thaw-entry
  "A packed entry as its class map, the strings in the slots that hold them
  interned: the vtable's keys and values, the methods' and fields' names and
  descriptors, the interfaces, the layout's, dependencies' and cached
  methods' keys. Those slots, not a walk of the whole map: a postwalk with a
  lookup per string took 2.7 s over 7,400 entries where this takes 0.4."
  [e]
  (let [m (edn/read-string (unpack e))]
    (cond-> m
      ;; a multi-part entry keeps its vtable and its methods packed until
      ;; something reads them (class-vtable, class-methods): three quarters
      ;; of an entry's text, and a restored class's vtable is read only when
      ;; a subclass links or its registration is rebuilt, its methods when
      ;; a call to it is compiled, a subclass links or it is reflected on
      (and (vector? e) (second e)) (assoc :vtable-packed (second e))
      (and (vector? e) (nth e 2 nil)) (assoc :methods-packed (nth e 2))
      (:vtable m) (update :vtable intern-keys true)
      (:methods m) (update :methods intern-members)
      (:fields m) (update :fields intern-members)
      (:interfaces m) (update :interfaces #(mapv intern-string %))
      (:layout m) (update :layout intern-keys false)
      (:deps m) (update :deps intern-keys false)
      (:cached-methods m) (update :cached-methods intern-keys false))))

(defn- parse-entry
  "thaw-entry for one packed class entry. Damage inside an entry of an
  otherwise readable state.img surfaces here, mid-run, where booting cold is
  no longer possible (generations are already half loaded). The cache goes,
  so the next run boots cold, and this run fails loudly."
  [cname e]
  (try (thaw-entry e)
       (catch Exception ex
         (binding [*out* *err*]
           (println "champaca: discarding a damaged startup cache (bad entry for"
                    (str cname "):") (ex-message ex)))
         (when-let [dir (:cache-dir @state)] (delete-tree! dir))
         (throw ex))))

;; The classes whose table entries a classpath class's link or method compile
;; reads, a volatile set while one runs: what its library cache entry depends
;; on (user-deps).
(def ^:private ^:dynamic *reads* nil)

(defn- thaw!
  "The class table entry for cname as a map, or nil. A cache hit leaves
  every entry packed as UTF-8 edn bytes; the first read parses it and puts
  the map back, so a run only pays for the classes it touches. The swap
  keeps a map that is already there, so a racing thaw or a concurrent
  assoc-in on the same entry is never overwritten."
  [cname]
  (when-let [r *reads*] (vswap! r conj cname))
  (let [e (get-in @state [:classes cname])]
    (if (packed? e)
      (let [u (parse-entry cname e)]
        (-> (swap! state update-in [:classes cname] #(if (packed? %) u %))
            (get-in [:classes cname])))
      e)))

(defn- class-info [cname]
  (or (thaw! cname)
      (do (ensure-loaded! cname) (thaw! cname))))

(defn- class-vtable
  "cname's vtable, thawed on this first read when its entry kept it packed
  (thaw-entry) and put back in the table; a map already holding one answers
  it. The swap keeps a vtable a racing read put there first."
  [cname]
  (let [info (class-info cname)]
    (or (:vtable info)
        (when-let [p (:vtable-packed info)]
          (let [vt (intern-keys (edn/read-string (unpack p)) true)]
            (-> (swap! state update-in [:classes cname]
                       ;; the entry is there (class-info thawed it); a vtable a racing read stored stays
                       #(cond (nil? %) % (:vtable %) % :else (-> % (assoc :vtable vt) (dissoc :vtable-packed))))
                (get-in [:classes cname :vtable])))))))

(defn- vtable-of
  "The vtable of a class map: its own, or thawed through class-vtable."
  [info]
  (when info (or (:vtable info) (class-vtable (:name info)))))

(defn- class-methods
  "cname's methods, thawed on this first read when its entry kept them
  packed (thaw-entry), as class-vtable thaws a vtable."
  [cname]
  (let [info (class-info cname)]
    (or (:methods info)
        (when-let [p (:methods-packed info)]
          (let [ms (intern-members (edn/read-string (unpack p)))]
            (-> (swap! state update-in [:classes cname]
                       #(cond (nil? %) % (:methods %) % :else (-> % (assoc :methods ms) (dissoc :methods-packed))))
                (get-in [:classes cname :methods])))))))

(defn- methods-of
  "The methods of a class map: its own, or thawed through class-methods."
  [info]
  (when info (or (:methods info) (class-methods (:name info)))))

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

(defn- package-dir
  "cname's package directory (\"java/util\"). A hidden class's /0x<n> suffix
  (define-class!) is part of its name, as in runtime.ss."
  [cname]
  (let [n (str/replace cname #"/0x[0-9a-f]+$" "")]
    (subs n 0 (or (str/last-index-of n "/") 0))))

(defn- jar-entry?
  "Whether a classpath entry is a jar: as the JDK's class path has it, any
  entry that isn't a directory, whatever its name (a .jar that isn't there
  counts too, as before). Asked once per entry."
  [entry]
  (let [k (str entry)]
    (if-some [v (get-in @state [:jar-entries k])]
      v
      (let [v (boolean (or (str/ends-with? k ".jar") (.isFile (io/file k))))]
        (swap! state assoc-in [:jar-entries k] v)
        v))))

(defn- note-class-entry!
  "cname was read from classpath entry. The first class read from a package
  also decides which entry defines that package, as the JVM's class loaders
  define a package with the first class they define in it (package-info
  reads that entry's manifest)."
  [cname entry]
  (swap! state (fn [st]
                 (-> st
                     (update :package-entries
                             (fn [m] (let [d (package-dir cname)] (if (contains? m d) m (assoc m d entry)))))
                     ;; the entry a class came from is its code source
                     (assoc-in [:class-entries cname] entry)))))

(defn- read-class-file [entry rel]
  (let [f (io/file entry rel)]
    (when (.exists f)
      (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String []))))))

(declare bytes-fp jar-path)

(defn- find-user-class
  "[entry bytes held?] from the first classpath entry that has cname, the
  JVM's order, or nil. The bytes class-source held for the class are used
  for the entry they came from (held? true)."
  [cname]
  (let [rel (str cname ".class")
        [from held] (get-in @state [:dir-bytes cname])]
    (some (fn [entry]
            (let [held? (and held (= from entry))]
              (when-let [bs (cond (jar-entry? entry) (read-entry (jar entry) rel)
                                  held? held
                                  :else (read-class-file entry rel))]
                [entry bs held?])))
          (:classpath @state))))

(defn- user-class-bytes
  "cname's bytes from the classpath, to load the class (normalize). The
  fingerprint the library cache records for it (class-source) is the one
  of the bytes parsed: a directory's class is parsed from the very bytes
  class-source took it from, and a load from any other bytes (a retried
  load, an earlier classpath entry that gained the class since) replaces
  class-source's answer, so one version's fingerprint can't go with
  another's code. A directory's class keeps the bytes it loaded from for
  the run (class-bytes), as a restored one does."
  [cname]
  (when-let [[entry bs held?] (find-user-class cname)]
    (note-class-entry! cname entry)
    (swap! state #(cond-> %
                    true (update :dir-bytes dissoc cname)
                    (not (jar-entry? entry)) (assoc-in [:dir-bytes cname] [entry bs])
                    (not held?) (assoc-in [:class-sources cname]
                                          {:entry entry :fp (bytes-fp bs) :jar (jar-path entry)})))
    bs))

(defn- bytes-fp
  "A class's fingerprint from its bytes: [CRC-32 size], what a jar's
  directory records for an entry, so a jar's class and a directory's compare
  the same way."
  [^bytes bs]
  (let [c (java.util.zip.CRC32.)]
    (.update c bs 0 (count bs))
    [(.getValue c) (count bs)]))

(defn- jar-path
  "A classpath entry's canonical path, a jar's or a directory's, which names
  its file in the library cache. Asked once per entry."
  [entry]
  (or (get-in @state [:jar-paths entry])
      (let [p (.getCanonicalPath (io/file (str entry)))]
        (swap! state assoc-in [:jar-paths entry] p)
        p)))

(defn- class-source
  "Where cname is on the classpath, found as user-class-bytes finds it:
  {:entry e :fp fp :jar j}, fp being [CRC size] of the class's bytes and j
  the entry's canonical path (a directory's too, though the key says jar).
  A jar's fp comes from the zip's directory, nothing inflated; a
  directory's class is read for it, and the bytes are held for
  user-class-bytes, so the file is read once. nil when no entry has it.
  Looked up once per class per run."
  [cname]
  (let [m (:class-sources @state)]
    (if (contains? m cname)
      (get m cname)
      (let [rel (str cname ".class")
            src (some (fn [entry]
                        (if (jar-entry? entry)
                          (when-let [e (.getEntry (jar entry) rel)]
                            {:entry entry :fp [(.getCrc e) (.getSize e)] :jar (jar-path entry)})
                          (when-let [bs (read-class-file entry rel)]
                            (swap! state assoc-in [:dir-bytes cname] [entry bs])
                            {:entry entry :fp (bytes-fp bs) :jar (jar-path entry)})))
                      (:classpath @state))]
        (swap! state assoc-in [:class-sources cname] src)
        src))))

(defn- jdk-class-bytes [cname]
  (when-let [slash (str/last-index-of cname "/")]
    (when-let [module (package-module (subs cname 0 slash))]
      (ji/entry (jdk-image) (str "/" module "/" cname ".class")))))

(defn- class-bytes
  "cname's bytes for a class already loaded (its methods, stack traces, NPE
  messages): the bytes a loader defined it from, or the bytes a directory's
  class was loaded or restored from, both held for the run, else the
  classpath's or the JDK's.
  Nothing is recorded, so reading can't change what the cache keeps."
  [cname]
  (or (get-in @state [:defined-bytes cname])
      (second (get-in @state [:dir-bytes cname]))
      (second (find-user-class cname))
      (jdk-class-bytes cname)))

(defn- user-resource-bytes [path]
  (some (fn [entry]
          (if (jar-entry? entry)
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
      (let [m (when (jar-entry? entry)
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
    (cond
      ;; jdk means a package the runtime image owns, whose module the boot
      ;; layer defines (a hidden class the JDK defines in one is in it too);
      ;; anything else without a classpath entry (a class a loader defined, a
      ;; generated one) has no version information either but gets a plain
      ;; package, cached, like a classpath class's
      (and (not entry) (package-module d)) "jdk"
      (not entry) (str/join "\n" (concat ["user"] (repeat 6 "-") ["-"]))
      :else
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

(defn- entry-url
  "A classpath entry's URL as the JDK's class loaders make it: from the
  canonical file (a symlink resolved), a directory's ending in /."
  [entry]
  (str (.toURI (.getCanonicalFile (io/file entry)))))

(defn- encode-path
  "A resource name in a URL as the JDK's ParseUtil.encodePath writes it for
  its class path's loaders: ASCII kept except controls, space and
  \" # % ; < = > ? [ \\ ] ^ ` { | } DEL, which are %xx in lower-case hex,
  and every UTF-16 unit above 0x7F as its two or three UTF-8-style bytes
  (a character beyond the BMP as its two surrogates, three bytes each)."
  [path]
  (let [enc (set (concat (map char (range 0 32)) " \"#%;<=>?[\\]^`{|}" [(char 127)]))
        esc (fn [b] (str "%" (format "%02x" b)))
        units (fn [cp] (if (> cp 0xFFFF)
                         (let [v (- cp 0x10000)] [(+ 0xD800 (bit-shift-right v 10)) (+ 0xDC00 (bit-and v 0x3FF))])
                         [cp]))]
    (apply str (for [ch path
                     u (units (int ch))]
                 (cond (and (< u 128) (enc (char u))) (esc u)
                       (< u 128) (str (char u))
                       (> u 0x7FF) (str (esc (bit-or 0xE0 (bit-and (bit-shift-right u 12) 0x0F)))
                                        (esc (bit-or 0x80 (bit-and (bit-shift-right u 6) 0x3F)))
                                        (esc (bit-or 0x80 (bit-and u 0x3F))))
                       :else (str (esc (bit-or 0xC0 (bit-and (bit-shift-right u 6) 0x1F)))
                                  (esc (bit-or 0x80 (bit-and u 0x3F)))))))))

(defn- resource-urls
  "Every classpath entry's URL for path, file: for a directory and jar: for a
  jar, in classpath order. In a directory anything that exists counts, a
  directory too, and \"\" names the directory itself, as the JDK's file
  loader answers. JDK resources have none: their jrt: URLs need the runtime
  image."
  [path]
  (for [entry (when (or (= path "") (plain-resource-path? path)) (:classpath @state))
        :let [jar? (jar-entry? entry)]
        :when (if jar?
                (and (not= path "") (some? (.getEntry (jar entry) path)))
                (.exists (io/file entry path)))]
    ;; the name as given, a directory's trailing / kept, as the JDK's
    ;; loaders join it to the entry's URL
    (if jar?
      (str "jar:" (entry-url entry) "!/" (encode-path path))
      (str (entry-url entry) (encode-path path)))))

(defn- library-file
  "The library cache's file for the classpath entry (a jar or a directory)
  at canonical path jar: lib/<hash>.img in the cache directory, holding the
  entries of the classes that entry supplied. An entry per file means a
  program reads only what its own entries cached, and two programs on
  different versions of a library (different paths) keep their own
  entries."
  [jar]
  ;; 64 bits from two string hashes, so two jars sharing a name (each would
  ;; overwrite the other's file every run) take thousands of times more jars
  ;; than 32 would; a clash still reads as {} (read-library-image)
  (let [h #(format "%08x" (bit-and (hash %) 0xffffffff))]
    (io/file (:cache-dir @state) "lib" (str (h jar) (h (str/reverse jar)) ".img"))))

(defn- read-library-image
  "The entries in jar's library file, {} when there is none, or when it
  belongs to another jar whose path hashes the same. Reading touches it, so
  pruning (prune-library!) keeps the files programs use. A damaged file is
  dropped (its classes compile again)."
  [jar]
  (let [f (library-file jar)]
    (or (when (.exists f)
          (try (let [m (timed :cache-read-library #(img/read-image (str f)))]
                 (when-not (and (map? m) (map? (:entries m)))
                   (throw (ex-info "not a champaca library image" {})))
                 (when (= jar (:jar m))
                   (.setLastModified f (System/currentTimeMillis))
                   (:entries m)))
               (catch Exception e
                 (binding [*out* *err*]
                   (println "champaca: discarding a damaged library cache file:" (str f) (ex-message e)))
                 (.delete f)
                 nil)))
        {})))

(defn- library-cache
  "The library cache's entries for the jar at canonical path jar, classpath
  classes by name as edn text, read the first time a run loads one of its
  classes."
  [jar]
  (or (get-in @state [:user-cache jar])
      (let [m (read-library-image jar)]
        (get-in (swap! state update-in [:user-cache jar] #(or % m)) [:user-cache jar]))))

(defn- cache-origin
  "Whether a class's code may be cached: :jdk, :user for a classpath class
  the library cache keeps (a jar's class whose dependencies can be checked,
  :src-fp and :src-jar set by library-cache-keys), else nil."
  [info]
  (case (:origin info)
    :jdk :jdk
    ;; a class a loader of the program's own defined is kept like a jar's
    ;; (define-class!), under the class path's file
    (:user :defined) (when (:src-fp info) :user)
    nil))

(defn- record!
  "Keep code for the startup cache when origin, a cache-origin, says it may
  be: {:text t} to compile when the cache is written, or {:fasl bv}, object
  code already compiled (compile-unit). unit says what it defines: [cname]
  a class's registration, [cname key] one method body."
  [origin unit code]
  (when-let [rec (:recording @state)]
    (when origin
      ;; the jar whose library file the save rewrites
      (when (= origin :user)
        (swap! state (fn [st] (update st :library-dirty (fnil conj #{}) (get-in st [:classes (first unit) :src-jar])))))
      (vswap! rec conj (assoc code :unit unit)))))

(defn- compile-to-cache?
  "Whether a JDK method should compile straight to object code kept for the
  cache (compile-unit): during boot, whose cache write always follows, or
  when this run saves a generation at exit (run does). Otherwise it is
  recorded as text, cheaper to compile, and compiled again only if
  save-cache! is called after all."
  [origin]
  (and origin (:recording @state)
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
                             (let [start (s/call "add-unit!" (str/join "\n" (map :text run)) (boolean (some :inspect run)))]
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
                  m (class-methods i)
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
                                 (class-methods c)))
        supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (or (some #(when (has? % false) %) supers)
        ;; a conflict resolves arbitrarily (JVMS 5.4.3.3); dispatch still throws
        (ffirst (specific-defaults (get (iface-methods (filter #(cf/interface? (class-info %)) (ancestry cname)))
                                       (str mname desc))))
        (some #(when (has? % true) %) (ancestry cname))
        (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(defn- check-virtual
  "Throws resolve-method's \"no such method\" when nothing in cname's
  ancestry declares mname(desc), as a JVM's resolution of an invokevirtual
  or invokeinterface does when the call runs (JVMS 5.4.3.3, 5.4.3.4), before
  it looks at the receiver. An interface's ancestry ends in Object, so its
  calls resolve to Object's methods too. A signature-polymorphic method of
  MethodHandle or VarHandle (native, varargs) takes any descriptor."
  [cname mname desc]
  (let [has? (fn [c] (some #(and (= mname (:name %)) (= desc (:desc %))) (class-methods c)))
        polymorphic? (and (#{"java/lang/invoke/MethodHandle" "java/lang/invoke/VarHandle"} cname)
                          (some #(and (= mname (:name %)) (:native %) (pos? (bit-and (:flags %) 0x0080)))
                                (class-methods cname)))]
    (when-not (or (str/starts-with? cname "[")
                  ;; the superclass chain first, lazily: nearly every call hits there
                  (some has? (take-while some? (iterate #(:super (class-info %)) cname)))
                  (some has? (ancestry cname))
                  polymorphic?)
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
        im (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (methods-of info)))]
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
     (let [m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (class-methods decl)))]
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
   :check-virtual check-virtual
   :lambda-class #(lambda-class %1 %2)})

;; --- loading -----------------------------------------------------------------------

(declare class-map)

(defn- normalize
  "Parse cname's classfile into the linker's class map."
  [cname]
  (let [user (timed :class-bytes #(user-class-bytes cname))
        bytes (or user (timed :class-bytes #(jdk-class-bytes cname))
                  ;; a loader of the program's own may define it, when the
                  ;; class naming it is that loader's (natives.ss); nil then.
                  ;; The time is the loader's own code running (reading its
                  ;; jars) and the define, restored from the cache or not
                  (when (boolean (timed :loader #(s/call "ask-resolving-loader" cname))) ::defined)
                  (throw (ex-info (str "class not found: " cname) {:class cname})))]
    (when-not (= bytes ::defined)
      ;; a classpath class is cached only through the library cache
      ;; (restore-user-class!), which checks it hasn't changed
      ;; bytes that aren't a class file are the JVM's ClassFormatError for
      ;; the code that needs the class (codegen, runtime.ss find-class): the
      ;; message ends with HotSpot's text, which find-class reads off
      (let [p (try (timed :cf-parse #(cf/parse bytes))
                   (catch Exception e
                     (let [{:keys [class-format named]} (ex-data e)]
                       (if class-format
                         (let [msg (if named (str class-format " " cname) class-format)]
                           (throw (ex-info (str "class format error in " cname ": " msg)
                                           {:class cname :class-format msg})))
                         (throw e)))))]
        (class-map cname p (if user :user :jdk))))))

(defn- class-map
  "The linker's class map for cname from its parsed classfile c, of origin
  :jdk, :user (the classpath) or :defined (bytes a class loader handed over
  at run time, define-class!)."
  [cname c origin]
  {:name cname :super (:super c) :interfaces (:interfaces c) :parsed c :flags (:flags c)
   :origin origin
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
                  (:methods c))})

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
                                      (vtable-of sup)))
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
                       m (class-methods i)
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

(defn- registration-text [{:keys [name super interfaces template layout fields flags reflection conflicts origin] :as info}]
  (let [vtable (vtable-of info) methods (methods-of info)]
  (str/join
   "\n"
   (concat
    ;; a classpath class stays in the unnamed module whatever its package
    ;; (runtime.ss user-class!); a registration the library cache keeps
    ;; carries this too
    (when (= origin :user) [(cg/sx "user-class!" (cg/str-lit name))])
    [(cg/sx "register-class!" (cg/str-lit name) (if super (cg/str-lit super) "#f")
            (str "'" (apply cg/sx (map cg/str-lit interfaces)))
            (apply cg/sx "vector" template)
            ;; the vtable as changes to the superclass's: printing every
            ;; inherited entry made it a third of the startup cache's source
            (str "'" (apply cg/sx (let [sup-vt (when super (vtable-of (class-info super)))]
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
            ;; enclosing-method record-components type-annotations nest-host
            ;; nest-members)
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
                            (let [{:keys [inner enclosing-method record nest-members]} reflection]
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
                                     (refl-lit (:type-annotations reflection))
                                     ;; NestHost: the host's name; NestMembers: their names
                                     (refl-lit (:nest-host reflection))
                                     (if nest-members (apply cg/sx (map cg/str-lit nest-members)) "#f"))))))]
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
                                           (if (:native m) "#t" "#f"))))))]))))

(defn- user-deps
  "The dependencies of a library cache entry whose link or compile read the
  classes in names: each classpath class among them and among everything
  they inherit from (a class's layout and vtable come from its ancestors),
  with its fingerprint (class-source). JDK classes need none, since the
  cache key covers the JDK, but a synthetic one (champaca/, a lambda's)
  has no bytes to make it again from, so it is :present, there when the
  entry is used. nil when one can't be checked later (one not in the class
  table, or one a loader defined, unless defined? says the entry is a
  loader-defined class's own, whose dependencies on others are checked by
  their fingerprints when its methods are first called: deps-still-hold!),
  which keeps the entry out of the cache."
  [names & [defined?]]
  (let [origin (fn [n] (let [e (get-in @state [:classes n])] (if (packed? e) :jdk (:origin e))))]
    (when (every? origin names)
      (reduce (fn [acc n]
                (case (origin n)
                  :jdk (if (str/starts-with? n "champaca/") (assoc acc n :present) acc)
                  :user (if-let [fp (:fp (class-source n))] (assoc acc n fp) (reduced nil))
                  :defined (if-let [fp (when defined? (get-in @state [:classes n :src-fp]))] (assoc acc n fp) (reduced nil))
                  (reduced nil)))
              {} (distinct (mapcat ancestry names))))))

(defn- uncheckable-dep
  "The first of names, or of their ancestry, that user-deps can't record a
  fingerprint for (why an entry isn't cached), or nil."
  [names defined?]
  (let [e (fn [n] (get-in @state [:classes n]))]
    (or (first (remove e names))
        (first (filter (fn [n] (let [x (e n)]
                                 (and (not (packed? x))
                                      (case (:origin x)
                                        :jdk false
                                        :user (not (:fp (class-source n)))
                                        :defined (not (and defined? (:src-fp x)))
                                        true))))
                       (distinct (mapcat ancestry names)))))))

(defn- library-cache-keys
  "What a classpath class's entry carries for the library cache once
  linked, its link having read the classes in reads: :src-fp, its own
  fingerprint, :src-jar, the jar whose library file holds it, and :deps
  (user-deps). nil when it can't be cached."
  [cname reads]
  (let [src (class-source cname)]
    (when-let [fp (:fp src)]
      (when-let [deps (user-deps (conj reads cname))]
        {:src-fp fp :src-jar (:jar src) :deps (dissoc deps cname)}))))

(defn- restore-user-class!
  "Register classpath class cname from the library cache, when an earlier
  run cached it and neither it nor any class it depends on has changed
  since: true then. Else nil, the stale entry is dropped, and the class is
  parsed and compiled as usual, which caches it again at the next save."
  [cname]
  (when-let [src (class-source cname)]
    (when-let [packed (when (:fp src) (get (library-cache (:jar src)) cname))]
      ;; an entry that doesn't read back goes alone, unlike state.img's
      ;; (parse-entry): the class compiles, and the next save rewrites it
      (let [info (try (thaw-entry packed)
                      (catch Exception e
                        (binding [*out* *err*]
                          (println (str "champaca: dropping the library cache's unreadable entry for " cname ":") (ex-message e)))
                        nil))]
        (if (and info (= (:fp src) (:src-fp info))
                 (every? (fn [[d fp]]
                           (let [e (get-in @state [:classes d])]
                             (if (= fp :present)
                               (some? e)
                               ;; the classpath's copy, unchanged, and not some
                               ;; other class of that name this run already has
                               ;; (a loader's, the JDK's)
                               (and (= fp (:fp (class-source d)))
                                    (or (nil? e) (= :user (:origin e)))))))
                         (:deps info)))
          (do (note-class-entry! cname (:entry src))
              ;; a directory's class keeps the bytes it was restored as
              ;; (held by class-source) for the run: a method it lacks
              ;; (parsed) and its stack frames (frame-class) read them,
              ;; since the file may have changed or gone by then
              (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
                (ensure-loaded! dep))
              (swap! state update :restored (fnil inc 0))
              (when-not (get-in @state [:classes cname])
                (swap! state assoc-in [:classes cname] info)
                (or (load-unit! (:cached-class info))
                    (let [text (registration-text info)]
                      (record! :user [cname] {:text text})
                      (scheme! text :interpret))))
              true)
          (do (swap! state #(-> %
                                (update-in [:user-cache (:jar src)] dissoc cname)
                                ;; what was dropped, for the save's merge
                                (assoc-in [:library-dropped (:jar src) cname] packed)
                                (update :library-dirty (fnil conj #{}) (:jar src))))
              nil))))))

(defn ensure-loaded! [cname]
  (cond
    ;; registered by an earlier run: its class table entry came from the
    ;; cache, and its registration is one unit of a cached generation
    (contains? (:lazy @state) cname)
    (let [info (thaw! cname)]
      (swap! state update :lazy disj cname)
      ;; a dependency that isn't there leaves cname lazy, not half-registered
      (try
        (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
          (ensure-loaded! dep))
        (catch Exception e
          (swap! state update :lazy conj cname)
          (throw e)))
      (or (load-unit! (:cached-class info))
          (let [text (registration-text info)]
            (record! (cache-origin info) [cname] {:text text})
            (scheme! text :interpret))))

    (not (get-in @state [:classes cname]))
    (when-not (restore-user-class! cname)
      (when-let [info (timed :parse #(normalize cname))]
        (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
          (ensure-loaded! dep))
        (when-not (get-in @state [:classes cname])
          ;; a classpath class's link and registration record what they read
          (let [reads (when (= :user (:origin info)) (volatile! #{}))
                linked (binding [*reads* reads] (timed :link #(link info)))]
            (swap! state assoc-in [:classes cname] linked)
            (let [text (binding [*reads* reads] (timed :register-text #(registration-text linked)))
                  linked (if-let [ks (when reads (library-cache-keys cname @reads))]
                           (get-in (swap! state update-in [:classes cname] merge ks) [:classes cname])
                           linked)]
              (record! (cache-origin linked) [cname] {:text text})
              (timed :register-eval #(scheme! text :interpret))))))))
  cname)

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

(declare defined-jar restore-defined-class! dep-matches?)

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
        (let [c (try (cf/parse bs)
                     (catch Exception e
                       ;; a wrong magic number's text ends with the name the caller
                       ;; gave, as HotSpot's, or <Unknown>
                       {:bad (let [m (or (ex-message e) (str (class e)))]
                               (if (:named (ex-data e)) (str m " " (or given "<Unknown>")) m))}))]
          (cond
            (:bad c) (str "bad " (:bad c))
            (and given (not= given (:name c))) (str "wrong " (:name c))
            (and (not hidden?) (get-in @state [:classes (:name c)])) (str "dup " (:name c))
            :else
            (let [c (if hidden? (rename-class c (str (:name c) "/0x" (Long/toHexString (swap! hidden-counter inc)))) c)
                  n (:name c)
                  info (class-map n c :defined)]
              ;; bytes class-source held for a classpath class of this name won't be parsed
              (swap! state update :dir-bytes dissoc n)
              (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))] (ensure-loaded! dep))
              ;; as ensure-loaded! does for a classpath class: the link and the
              ;; registration record what they read, which the entry depends on
              (let [reads (when fp (volatile! #{}))
                    ;; its own fingerprint goes in first: user-deps reads it
                    ;; for the class itself, and for a class depending on it
                    linked (cond-> (binding [*reads* reads] (link info)) fp (assoc :src-fp fp :src-jar dj))]
                ;; the defined bytes are kept for NPE messages and frames, once the class is in
                (swap! state #(-> % (assoc-in [:classes n] linked) (assoc-in [:defined-bytes n] bs)))
                (let [text (binding [*reads* reads] (registration-text linked))
                      deps (when fp (user-deps (conj @reads n) true))
                      _ (when fp
                          (when-not deps (swap! state update :defined-uncacheable (fnil inc 0)))
                          (when (and (not deps) (trace?))
                            (binding [*out* *err*] (println "define" n "not cached: it depends on" (uncheckable-dep (conj @reads n) true)))))
                      ;; without checkable dependencies it isn't cached, and
                      ;; without a fingerprint nothing depending on it is
                      linked (get-in (swap! state update-in [:classes n]
                                            #(if deps (assoc % :deps (dissoc deps n)) (dissoc % :src-fp :src-jar)))
                                     [:classes n])]
                  (record! (cache-origin linked) [n] {:text text})
                  (scheme! text :interpret)))
              (str "ok " n)))))))

(defn- defined-jar
  "The library cache's key for the classes a program's own loaders define:
  one file per class path, the bytes having no path of their own."
  []
  (or (:defined-jar @state)
      (let [j (str "defined:" (str/join ":" (map jar-path (:classpath @state))))]
        (swap! state assoc :defined-jar j)
        j)))

(defn- restore-defined-class!
  "Register the class a loader is defining, n from bytes bs with fingerprint
  fp, from the library cache's file dj: when an earlier run cached a class of
  that name from the same bytes and the classes it inherits from are what
  they were, \"ok <name>\"; else nil, the stale entry dropped so the save
  rewrites it, and the class parses as usual. What its methods read is
  checked when one is first called (deps-still-hold!)."
  [n bs fp dj]
  (if-let [packed (get (library-cache dj) n)]
    (let [info (try (timed :thaw #(thaw-entry packed))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "champaca: dropping the library cache's unreadable entry for " n ":") (ex-message e)))
                      nil))]
      (if (and info
               (or (= fp (:src-fp info))
                   (do (swap! state update :defined-changed (fnil inc 0))
                       (when (trace?) (binding [*out* *err*] (println "define" n "changed since its entry")))
                       false))
               (do (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))] (ensure-loaded! dep)) true)
               (every? (fn [d] (or (dep-matches? d (get (:deps info) d))
                                   (do (swap! state update :defined-changed (fnil inc 0))
                                       (when (trace?) (binding [*out* *err*] (println "define" n "not restored:" d "changed")))
                                       false)))
                       (filter (:deps info) (rest (ancestry-of-unlinked info)))))
        (do (swap! state #(-> %
                              (assoc-in [:classes n] info)
                              (assoc-in [:defined-bytes n] bs)
                              (update :dir-bytes dissoc n)
                              (update :restored (fnil inc 0))))
            (or (load-unit! (:cached-class info))
                (let [text (registration-text info)]
                  (record! :user [n] {:text text})
                  (scheme! text :interpret)))
            (str "ok " n))
        (do (swap! state #(-> %
                              (update-in [:user-cache dj] dissoc n)
                              (assoc-in [:library-dropped dj n] packed)
                              (update :library-dirty (fnil conj #{}) dj)))
            nil)))
    (do (swap! state update :defined-new (fnil inc 0)) nil)))

;; --- the JIT -----------------------------------------------------------------------

(defn- parsed
  "The parsed classfile for cname. A class restored from the boot cache
  carries no bytecode, so it is re-read on the first method compile; a
  classpath class restored from the library cache must read back as the
  bytes its entry was made from, or its new methods wouldn't match the
  ones it has. A directory's class does, from the bytes it kept; a jar
  changed in place under the run is an error."
  [cname]
  (or (:parsed (thaw! cname))
      (let [bs (class-bytes cname)
            fp (get-in @state [:classes cname :src-fp])
            _ (when (and fp (not bs))
                (throw (ex-info (str cname " is no longer on the classpath") {:class cname})))
            _ (when (and fp (not= fp (bytes-fp bs)))
                (throw (ex-info (str cname " changed on the classpath while this program ran") {:class cname})))
            p (timed :parse #(cf/parse bs))]
        (swap! state assoc-in [:classes cname :parsed] p)
        p)))

(defn- lines?
  "Whether cname's code compiles with Chez's inspector information, which is
  what gives its frames line numbers (how-it-works.md). By default a class
  from a classpath directory, a developer's own build, does; a jar's or a
  loader-defined class names its file without a line, as a JDK frame does,
  because the information costs about as much memory as the code itself (a
  warm Spring Boot: 100 of 460 MB live). CHAMPACA_LINES=all gives every
  class but the JDK's lines, CHAMPACA_LINES=off none. Every compile path
  asks, cached or not, so a frame's line doesn't depend on the cache."
  [cname info]
  (case (some-> (env "CHAMPACA_LINES") str/lower-case)
    "all" (contains? #{:user :defined} (:origin info))
    "off" false
    (and (= :user (:origin info))
         (boolean (when-let [src (class-source cname)] (not (jar-entry? (:entry src))))))))

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
          _ (report missing "not found" "NoClassDefFoundError")
          _ (report no-method "not found" "NoSuchMethodError")
          _ (report bad-format "not a class file" "ClassFormatError")
          missing (or missing no-method bad-format)]
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

(defn saves-generation!
  "Tell the JIT this process will save a generation, so JDK methods compile
  once to object code (api save-cache! calls it on its first save)."
  []
  (swap! state assoc :saves-generation true))

(defn- dep-matches?
  "Whether a loader-defined class's dependency d is what its entry recorded:
  fp its fingerprint, or :present for a synthetic class. One not loaded yet
  is asked of the resolving loader, as translating the method would have
  (runtime.ss jit! and define-class! bind it); one it doesn't have is a
  mismatch."
  [d fp]
  (let [e (or (get-in @state [:classes d])
              (do (try (ensure-loaded! d) (catch Exception _ nil))
                  (get-in @state [:classes d])))]
    (cond (nil? e) false
          (= fp :present) true   ; a lambda class in state.img, thawed or not
          (packed? e) false      ; a JDK class has that name now
          :else (= fp (or (:src-fp e) (:fp (class-source d)))))))

(defn- deps-still-hold!
  "For a loader-defined class restored with cached methods: whether every
  class those methods read is what the entry recorded, checked once, at the
  first call of one of them, which is when translating it would have loaded
  them. If not, the cached methods are dropped (they compile again, and the
  save rewrites the entry) and the entry's dependencies start over from the
  class's ancestry; an ancestry that can't be checked any more makes the
  class uncacheable and drops its entry."
  [cname info]
  (or (not= :defined (:origin info))
      (contains? (:deps-held @state) cname)
      (if (every? (fn [[d fp]] (or (dep-matches? d fp)
                                   (do (when (trace?) (binding [*out* *err*] (println "cached methods of" cname "dropped:" d "changed")))
                                       false)))
                  (:deps info))
        (do (swap! state update :deps-held (fnil conj #{}) cname) true)
        (let [deps (user-deps [cname] true)
              dj (:src-jar info)]
          (swap! state update :cached-methods-dropped (fnil inc 0))
          (swap! state (fn [st]
                         (-> st
                             (update-in [:classes cname] #(if deps
                                                           (-> % (dissoc :cached-methods) (assoc :deps (dissoc deps cname)))
                                                           (dissoc % :cached-methods :src-fp :src-jar :deps)))
                             (update :library-dirty (fnil conj #{}) dj)
                             (cond-> (not deps) (assoc-in [:library-dropped dj cname] (get-in st [:user-cache dj cname]))))))
          false))))

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

;; --- boot cache ---------------------------------------------------------------------
;; Modeled on Clojure's RT.load: one compiled unit per input, used only when
;; it is newer than its source, and a miss quietly takes the slow path. The
;; unit here is the whole JDK boot. Clojure's key is a timestamp; this key
;; also covers the translator itself, because a cache built by a different
;; translator is stale even when the JDK is not (Clojure's AOT
;; version-mismatch trap).

(def ^:private runtime-files
  "The Scheme runtime, in load order."
  ["champaca/runtime.ss" "champaca/natives.ss" "champaca/reflect.ss" "champaca/handles.ss" "champaca/files.ss"
   "champaca/net.ss" "champaca/spawn.ss"])

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
;; why on stderr, and CHAMPACA_NPE_STRICT (set by bb test) rethrows, so a
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
  1 for a method the JVM leaves out of traces."
  [cname mname desc pc]
  (let [{:keys [source module lines hidden]} (frame-class cname)
        table (get lines (str mname desc))
        line (if (and (seq table) (>= pc 0))
               (or (some (fn [[start l]] (when (<= start pc) l)) (rseq table)) -1)
               -1)]
    [(or source "") line (or module "") (if (contains? hidden (str mname desc)) 1 0)]))

(defn- translator-fingerprint []
  (hash (apply str (map #(slurp (io/resource %))
                        (concat runtime-files
                                ["champaca/codegen.clj" "champaca/main.clj" "champaca/classfile.clj" "champaca/jimage.clj"])))))

(defn- cache-dir []
  (when-not (= "off" (env "CHAMPACA_CACHE"))
    (let [modules (io/file (java-home) "lib" "modules")
          release (io/file (java-home) "release")
          ;; CHAMPACA_BT changes the generated code (shadow-stack wrappers that
          ;; hide frame names from getCallerClass), so it gets its own cache;
          ;; CHAMPACA_LINES decides which code carries inspector information
          key (hash [(when (.exists release) (slurp release)) (.length modules) (.lastModified modules)
                     (translator-fingerprint) (img/runtime-version) (some? (env "CHAMPACA_BT")) (some-> (env "CHAMPACA_LINES") str/lower-case)])]
      (io/file (cache-root) (Long/toHexString (bit-and key 0xffffffff))))))

(defn- packed-entry
  "A class map as edn text in UTF-8 bytes (pack), without bytecode: methods
  keep :code as a flag. Three parts, [map vtable methods], when the map has
  a vtable or methods (every linked class's): a thaw reads the map and
  keeps the other parts' bytes for class-vtable and class-methods, so a
  warm run never parses the vtables and methods of the classes it only
  restores, three quarters of the entries' text (spikes 73, 74). A part
  never thawed is written back as it was."
  [info]
  (let [vt (if (:vtable info) (pack (pr-str (:vtable info))) (:vtable-packed info))   ; live over kept bytes
        ms (if (contains? info :methods)
             (pack (pr-str (mapv #(cond-> % (:code %) (assoc :code true)) (:methods info))))
             (:methods-packed info))
        core (pack (pr-str (dissoc info :parsed :vtable :vtable-packed :methods :methods-packed)))]
    (if (or vt ms) [core vt ms] core)))

(defn- strip-for-cache
  "The linker's class map as packed edn per class, JDK classes only, without
  bytecode. An entry this run never thawed is packed already and is kept as
  it is."
  [classes]
  (into {}
        (for [[k info] classes
              :let [s (if (packed? info)
                        info
                        ;; not a classpath class, not one a loader defined, not a
                        ;; lambda class over interfaces no later run can check
                        (when (and (= :jdk (:origin info)) (not (:no-cache info)))
                          (packed-entry info)))]
              :when s]
          [k s])))

(defn- library-entries
  "jar's library file's entries: classpath classes by name as edn text,
  kept apart from the class table because the cache serves every program.
  It starts from the file as it is now, since another process may have
  saved since this one read it, less the entries this run found stale
  (unless they were replaced meanwhile); every class of the jar this run
  loaded is written again, with what it compiled."
  [jar classes]
  (into (reduce-kv (fn [m k seen] (if (packed= seen (get m k)) (dissoc m k) m))
                   (read-library-image jar) (get-in @state [:library-dropped jar]))
        (for [[k info] classes
              :when (and (map? info) (= :user (cache-origin info)) (= jar (:src-jar info)))]
          [k (packed-entry info)])))

(defn- dump-classes!
  "state.img: each class entry as edn text, plus the set of classes whose
  registration sits in a generation, so a load knows what is lazy without
  parsing anything. A JDK class goes in only when its registration is cached
  somewhere: boot.so (:boot-classes, the names of the class table as this
  process read it, or of a cold boot's registrations) or a generation
  (:cached-class). A
  later run takes any other entry for registered and fails with \"class
  not registered\": one whose save failed, so that a later save in the
  process found its map and not its code, or one linked and not yet
  recorded. Left out, it loads again in the run that needs it."
  [img]
  (let [{:keys [classes lazy boot-classes]} @state
        classes (into {} (filter (fn [[k v]] (or (packed? v) (contains? boot-classes k) (:cached-class v)))) classes)]
    (img/dump! (str img) {:lazy (into (set lazy)
                                      (for [[k v] classes :when (and (map? v) (= :jdk (:origin v)) (:cached-class v))] k))
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
    ;; the classes whose registration boot.so now holds (dump-classes!)
    (swap! state assoc :boot-classes (set (keep (fn [{[c k] :unit}] (when-not k c)) forms)))
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
          ;; the names in the class table as read: its entries are what
          ;; boot.so registers, and the lazy ones (dump-classes!). The names
          ;; alone: the map itself would pin every packed entry for the run
          (swap! state assoc :classes classes :lazy lazy :boot-classes (set (keys classes)))
          (timed :cache-load-code #(s/call "load" (str so)))
          true)
        ;; a damaged cache would fail every run the same way: drop it, boot
        ;; cold, and the cold boot writes a new one
        (catch Exception e
          (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
          (delete-tree! dir)
          (swap! state assoc :classes {} :lazy #{} :boot-classes #{})
          nil)))))

(defn- prune-library!
  "Drop library files no run has read for a week (a jar gone, a version no
  longer used), and temp files a crash left."
  [dir]
  ;; Racy by design: another process may rename a fresh file over one
  ;; between the age check and the delete, and that costs its jar one
  ;; recompile, never a stale entry.
  (let [cutoff (- (System/currentTimeMillis) stale-ms)]
    (doseq [f (.listFiles (io/file dir "lib"))
            :when (< (.lastModified f) cutoff)]
      (.delete f))))

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
        (.renameTo img-tmp (io/file dir "state.img"))))
    ;; after the units above have their places
    (when (and dir (.isDirectory dir) (seq (:library-dirty @state)))
      (doseq [jar (remove nil? (:library-dirty @state))
              :let [f (library-file jar)
                    tmp (io/file (.getParentFile f) (str (.getName f) ".tmp-" (System/nanoTime)))]]
        ;; one jar's failure leaves it dirty and the others still go
        (try
          (.mkdirs (.getParentFile f))
          (img/dump! (str tmp) {:jar jar :entries (library-entries jar (:classes @state))})
          (if (.renameTo tmp f)
            (swap! state #(-> % (update :library-dirty disj jar) (update :library-dropped dissoc jar)))
            (.delete tmp))
          (catch Exception e
            (.delete tmp)
            (binding [*out* *err*]
              (println "champaca: couldn't save the library cache for" jar (str "(" (ex-message e) ")"))))))
      (swap! state update :library-dirty (fn [s] (set (remove nil? s))))
      (prune-library! dir))))

(defn- boot!
  "Load the runtime, then run System.initPhase1 the way the JVM does before
  main. Uses the boot cache when it is valid and writes it when it is not.
  Returns 0 on success."
  []
  ;; CHAMPACA_LINES takes all or off (lines?); anything else is a typo
  (when-let [v (env "CHAMPACA_LINES")]
    (when-not (#{"all" "off"} (str/lower-case v))
      (binding [*out* *err*]
        (println (str "champaca: CHAMPACA_LINES=" v " isn't all or off; using the default, lines for classes from class directories")))))
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
  (s/call "set-classpath!" (str/join ":" (:classpath-given @state)))
  (when-let [c (:java-command @state)] (s/call "set-java-command!" c))
  (when-let [ps (seq (:command-line-properties @state))]
    (s/call "set-command-line-properties!" (str/join "\u0000" (mapcat identity ps))))
  (when-let [ds (seq (:assertion-directives @state))]
    (s/call "set-assertion-directives!" (str/join "\u0000" ds)))
  (s/call "set-define-class-hook!" (fn [& args] (timed :define #(apply define-class! args))))
  ;; annotation instances: a synthetic class implementing the annotation
  ;; interface, its element keys "name()desc" joined by |
  (s/call "set-annotation-class-hook!" (fn [iface keys]
                                         (lambda-class [iface] (if (= "" keys) [] (str/split keys #"\|")))))
  ;; ConstantPool natives (annotations): a constant's value, or a class's name
  (s/call "set-resource-hook!" (fn [path] (or (resource-bytes path) false))
          ;; URLs as one string, one per line
          (fn [path] (str/join "\n" (resource-urls path))))
  (s/call "set-jpackage-hook!" package-info)
  ;; the JDK's resources alone, for the platform loader
  (s/call "set-jdk-resource-hook!" (fn [path] (or (when (plain-resource-path? path) (jdk-resource-bytes path)) false)))
  ;; a classpath class's code source: its entry's URL, or false
  (s/call "set-code-source-hook!" (fn [cname] (if-let [e (get-in @state [:class-entries cname])] (entry-url e) false)))
  (s/call "set-cp-hook!" (fn [cname i]
                           (let [e (get (:cp (parsed cname)) i)]
                             (let [v (or (:value e) (:name e)
                                         (throw (ex-info "no such constant" {:class cname :index i})))]
                               ;; a lone surrogate's code units (classfile/decode-mutf8)
                               ;; go over as a Scheme vector, which jstring takes
                               (if (vector? v) (apply s/call "vector" v) v)))))
  ;; helpful NPE messages: site "pkg/Cls.name(desc)@bci" -> text, or false
  (s/call "set-npe-hook!" (fn [site] (or (npe-message site) false)))
  ;; stack traces: a frame's file, line and module as a Scheme vector
  (s/call "set-frame-hook!" (fn [cname mname desc pc]
                              (let [[f l m h] (frame-info cname mname desc pc)] (s/call "vector" f l m h))))
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
                     ;; initPhase2 builds the boot layer from the image's
                     ;; pre-generated module graph (SystemModules$default, no
                     ;; resolution) and initPhase3 makes the app class loader the
                     ;; system and context loader, as the JVM does before main; a
                     ;; failing phase 2 has printed its cause to stderr already
                     (unless (eqv? 0 ((method-proc \"java/lang/System\" \"initPhase2(ZZ)I\") 1 1))
                       (error 'champaca \"the boot layer failed to initialize\"))
                     ((method-proc \"java/lang/System\" \"initPhase3()V\"))))")]
      (when-let [rec (:recording @state)]
        ;; a cold boot's code goes to boot.so, and from here on only what
        ;; later code compiles is recorded. A warm boot keeps what it
        ;; recorded for this run's generation: it compiles only classes the
        ;; cached boot never loaded (a -Dstdout.encoding brings the boot a
        ;; new encoder), and dropping their registrations left their linked
        ;; maps in state.img with no code behind them, so a later run took
        ;; them for registered and failed with "class not registered"
        (when-not hit?
          (when (zero? status)
            (timed :cache-write #(write-cache! dir @rec)))
          (vreset! rec [])))
      (swap! state assoc :cache (cond hit? :hit dir :miss :else :off))
      status)))

(defn- percent-decode
  "%xx escapes decoded as the bytes of UTF-8 text, as a URL's path is."
  [u]
  (let [bs (.getBytes ^String u "UTF-8")
        n (count bs)
        out (java.io.ByteArrayOutputStream.)]
    (loop [i 0]
      (if (>= i n)
        (String. (.toByteArray out) "UTF-8")
        (let [b (bit-and (nth bs i) 0xff)
              h (when (and (= b 37) (< (+ i 2) n)) (subs u (+ i 1) (+ i 3)))
              v (when (and h (re-matches (re-pattern "[0-9A-Fa-f]{2}") h)) (Integer/parseInt h 16))]
          (if v
            (do (.write out (int v)) (recur (+ i 3)))
            (do (.write out (int b)) (recur (inc i)))))))))

(defn- manifest-class-path
  "The entries a jar's manifest Class-Path names, as files, the way the
  JDK's URLClassPath resolves them: each a URL relative to the jar's real
  location (a symlink followed), so a leading / is absolute and file: one
  is the same with the scheme; a name ending in / is a directory and any
  other a jar, so a directory named without / and a scheme other than file:
  are left out."
  [entry]
  (when-let [v (get-in (jar-manifest entry) [nil "class-path"])]
    (let [dir (.getParentFile (.getCanonicalFile (io/file entry)))]
      (for [u (str/split (str/trim v) (re-pattern "\\s+"))
            :when (not (str/blank? u))
            :let [scheme (second (re-find (re-pattern "^([A-Za-z][A-Za-z0-9+.-]*):") u))
                  p (percent-decode (if (= "file" (some-> scheme str/lower-case)) (subs u 5) u))
                  f (if (str/starts-with? p "/") (io/file p) (io/file dir p))]
            :when (and (or (nil? scheme) (= "file" (str/lower-case scheme)))
                       (if (str/ends-with? p "/") (.isDirectory f) (.isFile f)))]
        (str f)))))

(defn- with-manifest-class-paths
  "classpath with each jar's manifest Class-Path entries right after it,
  where the JDK's URLClassPath searches them, theirs after them in turn; an
  entry is searched once, and one that isn't there is left out."
  [classpath]
  ;; todo is a list of [entry named-by-a-manifest?], a jar's entries pushed
  ;; on its front; an entry seen before is skipped, the given ones included.
  ;; A jar a manifest names that can't be read is left out, as the JDK
  ;; skips one it can't open; a manifest that can't be read names nothing.
  (loop [todo (apply list (map (fn [e] [e false]) classpath)) seen #{} out []]
    (if-let [[e manifest?] (first todo)]
      (let [k (str (.getCanonicalFile (io/file e)))
            more (when (jar-entry? e)
                   (try (vec (manifest-class-path e)) (catch Exception _ (if manifest? ::unreadable []))))]
        (if (or (seen k) (= more ::unreadable))
          (recur (rest todo) seen out)
          (recur (into (rest todo) (reverse (map (fn [m] [m true]) more)))
                 (conj seen k) (conj out e))))
      out)))

(defn init!
  "Boot the JDK once (System.initPhase1 and what precedes it) with the given
  user classpath dirs. Returns 0 on success; a second call is a no-op."
  [classpath]
  (or (:boot-status @state)
      (do (swap! state assoc :classpath (with-manifest-class-paths (vec classpath))
                 ;; java.class.path is what was given, as the JVM's launcher has it
                 :classpath-given (vec classpath))
          (let [status (boot!)] (swap! state assoc :boot-status status) status))))

(defn run
  "Run cname's main method with classes found under the classpath dirs,
  after booting the JDK. Returns the exit status."
  [classpath cname args]
  (saves-generation!) ; save-generation! runs at exit below
  (let [t0 (System/nanoTime)
        status (init! classpath)
        _ (when (env "CHAMPACA_PROF") (prof-report "boot" (- (System/nanoTime) t0)))]
    (when (env "CHAMPACA_STATS") (binding [*out* *err*] (print "after boot: ")) (stats))
    (if (zero? status)
      (let [status (apply s/call "run-main" cname args)]
        (when (env "CHAMPACA_PROF") (prof-report "run" (- (System/nanoTime) t0)))
        (when (env "CHAMPACA_STATS") (stats))
        (save-generation!)
        status)
      status)))

(defn- launcher-error
  "The java launcher's refusal of an option: its text on stderr, status 1."
  [& lines]
  (binding [*out* *err*] (run! println lines))
  (System/exit 1))

(defn- assertion-directive
  "-ea, -da and their long forms as natives.ss set-assertion-directives!
  reads them: kind (u for the default, c a class, p a package, \"\" the
  unnamed one), + or -, the name slashed. nil for any other option."
  [a]
  (let [[_ flag arg] (or (re-matches #"-(ea|da|enableassertions|disableassertions)(?::(.*))?" a) [])
        on (if (#{"ea" "enableassertions"} flag) "+" "-")]
    (when flag
      (cond (nil? arg) (str "u" on)
            (str/ends-with? arg "...") (str "p" on (str/replace (subs arg 0 (- (count arg) 3)) "." "/"))
            :else (str "c" on (str/replace arg "." "/"))))))

(defn- jvm-options
  "The options before the main class or -jar, as the java launcher reads
  them, as {:props [[k v]...] :classpath s-or-nil :assertions [...] :argv rest}:
  -Dkey=value pairs (-Dkey is an empty value, a bare -D nothing); -cp,
  -classpath and --class-path (the last one wins); -ea/-da with a class, a
  package... or nothing, and -esa/-dsa, in order (assertion-directive);
  -server and -client, which the launcher accepts and ignores. A heap or
  stack size (-Xmx, -Xms, -Xmn, -Xss), -Xshare: and any -XX: flag are
  ignored with a warning, since start scripts pass them and they tune only
  the VM; any other option is the launcher's Unrecognized option."
  [argv]
  (loop [argv argv opts {:props [] :classpath nil :assertions []}]
    (let [a (first argv)]
      (cond (or (nil? a) (= a "-jar") (not (str/starts-with? a "-"))) (assoc opts :argv argv)
            (str/starts-with? a "-D")
            (let [kv (subs a 2) i (str/index-of kv "=")
                  k (if i (subs kv 0 i) kv)]
              (recur (rest argv) (cond-> opts (not= "" k) (update :props conj [k (if i (subs kv (inc i)) "")]))))
            (#{"-cp" "-classpath" "--class-path"} a)
            (if-let [cp (second argv)]
              (recur (nnext argv) (assoc opts :classpath cp))
              (launcher-error (str "Error: " a " requires class path specification")))
            (str/starts-with? a "--class-path=") (recur (rest argv) (assoc opts :classpath (subs a 13)))
            (assertion-directive a) (recur (rest argv) (update opts :assertions conj (assertion-directive a)))
            (#{"-esa" "-enablesystemassertions"} a) (recur (rest argv) (update opts :assertions conj "s+"))
            (#{"-dsa" "-disablesystemassertions"} a) (recur (rest argv) (update opts :assertions conj "s-"))
            (#{"-server" "-client"} a) (recur (rest argv) opts)
            (or (re-matches #"-X(mx|ms|mn|ss)[0-9]+[kKmMgGtT]?" a) (re-matches #"-XX:.+" a) (re-matches #"-Xshare:.+" a))
            (do (binding [*out* *err*] (println (str "champaca: ignoring option " a "; it has no counterpart here")))
                (recur (rest argv) opts))
            :else
            (launcher-error (str "Unrecognized option: " a)
                            "Error: Could not create the Java Virtual Machine."
                            "Error: A fatal exception has occurred. Program will exit.")))))

(defn- expand-class-path
  "A class path string as a list of entries, a dir/* (or a bare *) standing
  for the names ending .jar or .JAR in that directory, as the java launcher
  expands it. One that finds none, or names no directory, stays as written,
  as java leaves it (so a directory really named * still works). Sorted by
  name: the launcher takes the directory's own order, which its
  documentation leaves unspecified."
  [cp]
  (mapcat (fn [e]
            (if (or (= e "*") (str/ends-with? e "/*"))
              (let [dir (case e "*" "." "/*" "/" (subs e 0 (- (count e) 2)))
                    jars (for [n (some-> (io/file dir) .list seq sort)
                               :when (or (str/ends-with? n ".jar") (str/ends-with? n ".JAR"))]
                           (case e "*" n "/*" (str "/" n) (str dir "/" n)))]
                (or (seq jars) [e]))
              [e]))
          (str/split cp #":")))

(defn -main [& argv]
  (let [{:keys [props classpath assertions argv]} (jvm-options argv)
        _ (swap! state assoc :command-line-properties props :assertion-directives assertions)
        ;; with -cp the main class comes first; without, champaca's own
        ;; form, the class path first (-jar ignores -cp, as java does)
        [cp cname & args] (if (and classpath (not= "-jar" (first argv))) (cons classpath argv) argv)]
    (when (and (= cp "-jar") (not cname))
      (binding [*out* *err*] (println "Error: -jar requires jar file specification"))
      (System/exit 1))
    (when-not cname
      (binding [*out* *err*]
        (println "usage: jolt -M -m champaca.main [options] <classpath[:entry...] (dirs, jars, dir/*)> <MainClass> [args...]")
        (println "   or: jolt -M -m champaca.main [options] -cp <classpath> <MainClass> [args...]")
        (println "   or: jolt -M -m champaca.main [options] -jar <jarfile> [args...]")
        (println "options: -Dkey=value, -ea[:<class>|:<package>...], -da[...], -esa, -dsa; -server, -client accepted;")
        (println "         -Xmx, -Xms, -Xmn, -Xss, -Xshare:, -XX: ignored with a warning"))
      (System/exit 2))
    (if (= cp "-jar")
      ;; as java -jar: the manifest's Main-Class, the jar alone as the class
      ;; path given (its Class-Path entries follow it, as for any jar)
      (let [file? (.isFile (io/file cname))
            ;; any file, whatever its name, as java -jar takes it; one that
            ;; isn't a zip is the launcher's "invalid or corrupt"
            m (when file? (try (or (jar-manifest cname) {}) (catch Exception _ ::corrupt)))
            main (when (map? m) (get-in m [nil "main-class"]))]
        (when-not main
          (binding [*out* *err*]
            (println (cond (not file?) (str "Error: Unable to access jarfile " cname)
                           (= m ::corrupt) (str "Error: Invalid or corrupt jarfile " cname)
                           :else (str "no main manifest attribute, in " cname))))
          (System/exit 1))
        (swap! state assoc :java-command (str/join " " (cons cname args)))
        (System/exit (run [cname] (str/replace (str/trim main) "." "/") (vec args))))
      (do
        ;; sun.java.command, as the launcher has it: the main class as given, then the arguments
        (swap! state assoc :java-command (str/join " " (cons cname args)))
        (System/exit (run (vec (expand-class-path cp)) (str/replace cname "." "/") (vec args)))))))
