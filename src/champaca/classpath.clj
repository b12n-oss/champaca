;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.classpath
  "Where class and resource bytes come from: the JDK's runtime image, the
  class path (directories and jars, their manifests), the boot layer's
  module lookups, and the resources and URLs built on them. Nothing here
  loads or links a class."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.jimage :as ji]
            [champaca.state :refer [state env packed?]])
  (:import [java.util.zip ZipFile]))

(def layer-modules
  "Slashed package -> the name of the boot layer module holding it, as
  natives.ss layer-module-name answered once the layer was built. A package
  no module holds isn't kept: a layer defined later may add one."
  (atom {}))

(defn layer-module
  "The name of the boot layer module holding cname's package, nil when no
  module of it does, :open before initPhase2 has built the layer."
  [cname]
  (let [i (str/last-index-of cname "/")
        p (if i (subs cname 0 i) "")
        lm @layer-modules]
    (if (contains? lm p)
      (get lm p)
      (let [r (s/call "layer-module-name" p)]
        (if (true? r)
          :open
          (when (string? r)
            (swap! layer-modules assoc p r)
            r))))))

(defn outside-boot-layer?
  "Whether JDK class cname is one the JVM's loaders can't find: no module of
  the boot layer holds its package, because --limit-modules left the module
  out or the default layer doesn't resolve it (jdk.incubator.vector without
  --add-modules). Bytecode naming it fails as a class that isn't there, as
  HotSpot's NoClassDefFoundError. champaca's own classes are always there,
  and so is a call site's lambda class (caller$$Lambda/0x<n>), which runs
  only where its caller does, and anything is before the layer exists."
  [cname]
  (and (not (str/starts-with? cname "["))
       (not (str/starts-with? cname "champaca/"))
       (not (str/includes? cname "$$Lambda/0x"))
       (nil? (layer-module cname))))

(defn refuse-outside-layer!
  "Throw normalize's \"class not found\" for a JDK class in the class table
  that the boot layer leaves out (outside-boot-layer?): a startup cache made
  under another layer, or champaca's boot, may hold it."
  [cname]
  (let [e (get-in @state [:classes cname])]
    (when (and e (or (packed? e) (= :jdk (:origin e))) (outside-boot-layer? cname))
      (throw (ex-info (str "class not found: " cname) {:class cname})))))

;; --- class bytes -----------------------------------------------------------------
;; JDK classes come from $JAVA_HOME/lib/modules, the runtime image every JDK
;; has and the JVM itself loads them from (champaca.jimage reads it). A
;; class's package names its module through the image's /packages entries.
;; User classes come from the classpath: directories and jars.

(defn java-home
  "JAVA_HOME, which must hold a runtime image: every JDK since 9 does, and so
  does a jlink-built runtime."
  []
  (let [h (or (env "JAVA_HOME") (throw (ex-info "JAVA_HOME is not set" {})))]
    (when-not (.isFile (io/file h "lib" "modules"))
      (throw (ex-info (str "champaca reads the JDK's classes from $JAVA_HOME/lib/modules, and " h
                           " has none. JAVA_HOME must be a JDK 25 (or a runtime jlink built from one).")
                      {:java-home h})))
    h))


(defn jdk-image []
  (or (:image @state)
      ;; two threads may both open it: the first stored wins, and the other
      ;; closes its file
      (let [i (ji/open (io/file (java-home) "lib" "modules"))
            kept (:image (swap! state update :image #(or % i)))]
        (when-not (identical? kept i) (.close ^java.io.RandomAccessFile (:raf i)))
        kept)))

(defn package-module
  "The JDK module holding package dir (\"java/util/zip\"), or nil, looked up
  once per package."
  [dir]
  (let [m (:package-modules @state)]
    (if (contains? m dir)
      (get m dir)
      (let [mod (ji/package-module (jdk-image) dir)]
        (swap! state assoc-in [:package-modules dir] mod)
        mod))))

(defn read-entry [zf entry-name]
  (when-let [e (.getEntry zf entry-name)]
    (with-open [in (.getInputStream zf e)] (.readAllBytes in))))

(defn jar [path]
  (or (get-in @state [:jars path])
      (let [zf (ZipFile. (str path))]
        (swap! state assoc-in [:jars path] zf)
        zf)))

(defn package-dir
  "cname's package directory (\"java/util\"). A hidden class's /0x<n> suffix
  (define-class!) is part of its name, as in runtime.ss."
  [cname]
  (let [n (str/replace cname #"/0x[0-9a-f]+$" "")]
    (subs n 0 (or (str/last-index-of n "/") 0))))

(defn jar-entry?
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

(defn note-class-entry!
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

(defn read-class-file [entry rel]
  (let [f (io/file entry rel)]
    (when (.exists f)
      (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String []))))))

(declare bytes-fp jar-path)

(defn app-roots
  "The application loader's resolved modules from files (java -p), as
  {name jar-or-directory}, read from the boot layer once it exists
  (natives.ss app-module-roots); {} before."
  []
  (cond
    (:app-roots @state) (:app-roots @state)
    ;; no module path: none, and no Java asked (a class path program pays
    ;; nothing)
    (not (some (fn [[sfx _]] (= sfx "path")) (:module-options @state))) {}
    ;; reading the layer loads JDK classes, whose lookups land back here:
    ;; they need no module roots
    (:app-roots-busy @state) {}
    :else
    (let [r (try (swap! state assoc :app-roots-busy true)
                 (s/call "app-module-roots")
                 (finally (swap! state dissoc :app-roots-busy)))]
      (if (true? r)
        {}
        (let [pairs (vec (for [l (str/split-lines (str r)) :when (seq l)
                               :let [[n path] (str/split l #"\t" 2)]]
                           [n (str/replace path #"/$" "")]))
              m (into {} pairs)]
          ;; the loader's own order, for resources in no package
          (swap! state assoc :app-roots m :app-root-order (mapv second pairs))
          m)))))

(defn lookup-entries
  "Where a class is looked for, as the JVM's application loader does: a
  class in a package of one of its named modules (java -p) only in that
  module's jar or directory, so the class path can't add to the module's
  packages; a class in a package of a JDK module in the boot layer nowhere,
  since the JDK's loaders own that package and the class path can't shadow
  or add to it (an old jar carrying javax.xml classes loads the JDK's, and a
  class the JDK doesn't have there isn't found); any other class on the
  class path. A module the boot layer didn't resolve is never looked in, so
  its packages are the class path's, as on the JVM."
  [cname]
  (let [m (layer-module cname)
        root (when (string? m) (get (app-roots) m))]
    (cond root [root]
          (string? m) []
          :else (:classpath @state))))

(declare root-directory?)

(defn resource-entries
  "Where a resource is looked for, as BuiltinClassLoader.findResource: in a
  named application module's package, that module when it opens the package
  or the resource is a class file, then the class path (a closed package's
  resource is the module's own: the loader goes straight to the class path);
  in a JDK module's package, the class path after the image; any other (a
  root resource, META-INF/...) in the resolved modules, then the class path."
  [path]
  (let [m (layer-module path)
        roots (app-roots)
        cp (:classpath @state)]
    (cond (and (string? m) (get roots m))
          ;; a class file, a directory, or an opened package's resource is the
          ;; module's for anyone; a closed package's isn't (findResource's
          ;; name.endsWith(\".class\") || url ends with / || isOpen)
          (let [root (get roots m)]
            (if (or (str/ends-with? path ".class")
                    (str/ends-with? path "/")
                    (root-directory? root path)
                    (true? (s/call "app-package-open?" (subs path 0 (str/last-index-of path "/")))))
              (cons root cp)
              cp))
          (string? m) cp
          :else (concat (:app-root-order @state) cp))))

(defn root-directory?
  "Whether path names a directory in a module's jar or exploded directory."
  [root path]
  (if (jar-entry? root)
      (some? (.getEntry (jar root) (str path "/")))
      (.isDirectory (io/file root path))))

(defn entry-bytes
  "path's bytes in one class path entry or module root, or nil."
  [entry path]
  (if (jar-entry? entry)
    (read-entry (jar entry) path)
    (let [f (io/file entry path)]
      (when (.isFile f)
        (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str f) (into-array String [])))))))

(defn find-user-class
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
          (lookup-entries cname))))

(defn user-class-bytes
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

(defn bytes-fp
  "A class's fingerprint from its bytes: [CRC-32 size], what a jar's
  directory records for an entry, so a jar's class and a directory's compare
  the same way."
  [^bytes bs]
  (let [c (java.util.zip.CRC32.)]
    (.update c bs 0 (count bs))
    [(.getValue c) (count bs)]))

(def bundle-root
  "CHAMPACA_BUNDLE's canonical path: the directory an app bundle ships in,
  whose classpath entries the library cache names relative to it, so the
  bundle's cache still answers once the directory is copied elsewhere."
  (delay (some-> (env "CHAMPACA_BUNDLE") not-empty io/file .getCanonicalPath)))

(defn jar-path
  "A classpath entry's canonical path, a jar's or a directory's, which names
  its file in the library cache, or for an entry inside the bundle
  (bundle-root) bundle:<path relative to it>. Asked once per entry."
  [entry]
  (or (get-in @state [:jar-paths entry])
      (let [c (.getCanonicalPath (io/file (str entry)))
            root @bundle-root
            p (cond (nil? root) c
                    (= c root) "bundle:"
                    (str/starts-with? c (str root "/")) (str "bundle:" (subs c (inc (count root))))
                    :else c)]
        (swap! state assoc-in [:jar-paths entry] p)
        p)))

(defn class-source
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
                      (lookup-entries cname))]
        (swap! state assoc-in [:class-sources cname] src)
        src))))

(defn jdk-class-bytes [cname]
  (when-let [slash (str/last-index-of cname "/")]
    (when-let [module (package-module (subs cname 0 slash))]
      (ji/entry (jdk-image) (str "/" module "/" cname ".class")))))

(defn class-bytes
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

(defn user-resource-bytes [path]
  (some #(entry-bytes % path) (resource-entries path)))

(declare plain-resource-path? entry-url encode-path)

(defn module-resource-bytes
  "A named application module's resource from its own jar or directory, any
  path (Module.getResourceAsStream, Class.getResource* of its classes), or
  nil for any other module."
  [mn path]
  (when-let [root (get (app-roots) mn)]
    (when (plain-resource-path? path) (entry-bytes root path))))

(defn module-resource-url
  "The URL of a named application module's resource in its own jar or
  directory, as module-resource-bytes finds it, or nil."
  [mn path]
  (when (module-resource-bytes mn path)
    (let [root (get (app-roots) mn)]
      (if (jar-entry? root)
        (str "jar:" (entry-url root) "!/" (encode-path path))
        (str (entry-url root) (encode-path path))))))

(defn jdk-resource-bytes
  "path from the module holding its directory. The image counts a directory
  of resources alone as a package, so ICU's data has a module too."
  [path]
  (when-let [slash (str/last-index-of path "/")]
    (when-let [module (package-module (subs path 0 slash))]
      (ji/entry (jdk-image) (str "/" module "/" path)))))

(defn plain-resource-path?
  "A resource name that stays inside its classpath entry: no empty, . or ..
  segment, no leading /."
  [path]
  (and (not (str/blank? path))
       (not-any? #{"" "." ".."} (str/split path #"/"))))

(defn resource-bytes
  "The JDK's modules first, as a parent-first class loader would, then the
  classpath."
  [path]
  (when (plain-resource-path? path)
    (or (jdk-resource-bytes path) (user-resource-bytes path))))

(defn ascii-lower
  "Lower case for attribute names, which are ASCII; String.toLowerCase would
  follow the default locale (a Turkish one maps I to a dotless i)."
  [s]
  (apply str (map #(let [c (int %)] (if (<= 65 c 90) (char (+ c 32)) %)) s)))

(defn manifest-sections
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

(defn jar-manifest
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

(defn package-info
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

(defn entry-url
  "A classpath entry's URL as the JDK's class loaders make it: from the
  canonical file (a symlink resolved), a directory's ending in /."
  [entry]
  (str (.toURI (.getCanonicalFile (io/file entry)))))

;; A resource name as it appears in a class loader's URL: letters, digits and
;; the characters in encode-path-plain stay, / stays as the separator, every
;; other character becomes %xx in lower-case hex over its UTF-8 bytes. A code
;; point beyond the BMP is first split into its two UTF-16 units, each written
;; as its own three bytes. Written from HotSpot 25's observed URLs.
(def encode-path-plain
  (set "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!$&'()*+,-.@_~/"))

(defn encode-unit
  "The %xx text of one UTF-16 unit u (0 to 0xFFFF) as UTF-8 bytes."
  [u]
  (let [hex (fn [b] (str "%" (when (< b 16) "0") (Integer/toHexString b)))]
    (cond
      (< u 0x80) (hex u)
      (< u 0x800) (str (hex (bit-or 0xC0 (bit-shift-right u 6)))
                       (hex (bit-or 0x80 (bit-and u 0x3F))))
      :else (str (hex (bit-or 0xE0 (bit-shift-right u 12)))
                 (hex (bit-or 0x80 (bit-and (bit-shift-right u 6) 0x3F)))
                 (hex (bit-or 0x80 (bit-and u 0x3F)))))))

(defn encode-path
  [path]
  (apply str
         (for [ch path
               :let [cp (int ch)]]
           (cond
             (contains? encode-path-plain ch) (str ch)
             (> cp 0xFFFF) (let [v (- cp 0x10000)]
                             (str (encode-unit (+ 0xD800 (bit-shift-right v 10)))
                                  (encode-unit (+ 0xDC00 (bit-and v 0x3FF)))))
             :else (encode-unit cp)))))

(defn resource-urls
  "Every classpath entry's URL for path, file: for a directory and jar: for a
  jar, in classpath order. In a directory anything that exists counts, a
  directory too, and \"\" names the directory itself, as the JDK's file
  loader answers. JDK resources have none: their jrt: URLs need the runtime
  image."
  [path]
  (for [entry (when (or (= path "") (plain-resource-path? path)) (resource-entries path))
        :let [jar? (jar-entry? entry)]
        :when (if jar?
                (and (not= path "") (some? (.getEntry (jar entry) path)))
                (.exists (io/file entry path)))]
    ;; the name as given, a directory's trailing / kept, as the JDK's
    ;; loaders join it to the entry's URL
    (if jar?
      (str "jar:" (entry-url entry) "!/" (encode-path path))
      (str (entry-url entry) (encode-path path)))))

(defn percent-decode
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

(defn manifest-class-path
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

(defn with-manifest-class-paths
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
