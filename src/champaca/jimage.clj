(ns champaca.jimage
  "The JDK's runtime image, $JAVA_HOME/lib/modules, read the way
  jdk.internal.jimage.BasicImageReader reads it (JDK 25's java.base, which
  the comments below cite by class). Every JDK since 9 has one, jlink writes
  it, and the JVM loads its own classes from it.

  The file is a 28-byte header, an index and then the entries' bytes. The
  index is a perfect hash over every entry's full name (\"/java.base/java/
  lang/Object.class\"), a table of location records and a string table. Only
  the index is read into memory, about 1.5 MB for JDK 25; an entry is read
  from where it lies when asked for.")

(def ^:private magic 0xCAFEDADA)
(def ^:private hash-multiplier 0x01000193)

;; ImageHeader: seven u4 in the image's byte order, which is the order of the
;; machine that wrote it (jlink writes native order). Every system champaca
;; runs on is little-endian, so a big-endian image is refused.
(defn- u4 [^bytes b i]
  (bit-or (bit-and (aget b i) 0xff)
          (bit-shift-left (bit-and (aget b (+ i 1)) 0xff) 8)
          (bit-shift-left (bit-and (aget b (+ i 2)) 0xff) 16)
          (bit-shift-left (bit-and (aget b (+ i 3)) 0xff) 24)))

(defn- s4 [b i] (let [v (u4 b i)] (if (>= v 0x80000000) (- v 0x100000000) v)))

(defn- read-bytes [^java.io.RandomAccessFile raf off n]
  (let [b (byte-array n)]
    (.seek raf off)
    (.readFully raf b)
    b))

(defn open
  "The image at path: its index in memory and the file kept open for reading
  entries, shared under a lock, for the life of the process."
  [path]
  (let [raf (java.io.RandomAccessFile. (str path) "r")]
    (try
      (when (< (.length raf) 28)
        (throw (ex-info (str path " is not a JDK runtime image") {:path (str path)})))
      (let [h (read-bytes raf 0 28)
            m (u4 h 0)]
        (when-not (= magic m)
          (throw (ex-info (str path (if (= m 0xDADAFECA)
                                      " is a big-endian runtime image, which champaca can't read"
                                      " is not a JDK runtime image"))
                          {:path (str path) :magic m})))
        (when (zero? (u4 h 16))
          (throw (ex-info (str path " is not a JDK runtime image: its index is empty") {:path (str path)})))
        (when-not (= 0x10000 (u4 h 4))
          (throw (ex-info (str path " is runtime image version " (format "%x" (u4 h 4))
                               "; champaca reads version 1.0")
                          {:path (str path)})))
        (let [table (u4 h 16)
              locs-size (u4 h 20)
              strs-size (u4 h 24)
              index-size (+ 28 (* 8 table) locs-size strs-size)]
          (when (> index-size (.length raf))
            (throw (ex-info (str path " is cut short: its index ends past the end of the file")
                            {:path (str path) :index-size index-size})))
          {:path (str path)
           :raf raf
           :index (read-bytes raf 0 index-size)
           :table table
           :redirect 28
           :offsets (+ 28 (* 4 table))
           :locations (+ 28 (* 8 table))
           :strings (+ 28 (* 8 table) locs-size)
           :index-size index-size}))
      (catch Throwable e (.close raf) (throw e)))))

;; ImageStringsReader.unmaskedHashCode, over the name's UTF-8. Java's is over
;; modified UTF-8, which differs only for U+0000 and characters outside the
;; BMP; a name holding one would hash differently here and not be found.
(defn- name-hash [^bytes nb seed]
  (let [n (alength nb)]
    (loop [i 0 h seed]
      (if (< i n)
        (recur (inc i) (bit-and (bit-xor (* h hash-multiplier) (bit-and (aget nb i) 0xff)) 0xffffffff))
        (bit-and h 0x7fffffff)))))

(defn- string-at
  "The NUL-terminated string at offset off of the string table."
  [{:keys [index strings]} off]
  (let [s (+ strings off)]
    (loop [e s]
      (if (zero? (aget index e))
        ;; copied out first: jolt's String.(bytes, off, len, charset) takes
        ;; time in the whole array's length, and this one is the index
        (String. (java.util.Arrays/copyOfRange index (int s) (int e)) "UTF-8")
        (recur (inc e))))))

;; ImageLocation: a run of attributes, each a byte holding kind << 3 and
;; length - 1, then that many bytes of value, big-endian. A byte below 8 ends
;; the run.
(def ^:private attr-keys [nil :module :parent :base :extension :offset :compressed :uncompressed])

(defn- location [{:keys [index locations]} off]
  (loop [i (+ locations off) m {}]
    (let [b (bit-and (aget index i) 0xff)]
      (if (< b 8)
        m
        (let [n (inc (bit-and b 7))
              k (get attr-keys (bit-shift-right b 3))
              v (loop [j 0 v 0]
                  (if (< j n) (recur (inc j) (bit-or (bit-shift-left v 8) (bit-and (aget index (+ i 1 j)) 0xff))) v))]
          (when-not k
            (throw (ex-info "runtime image has an unknown location attribute" {:kind (bit-shift-right b 3)})))
          (recur (+ i 1 n) (assoc m k v)))))))

;; ImageLocation.getFullName.
(defn- full-name [img loc]
  (let [part #(let [o (get loc %)] (if (and o (pos? o)) (string-at img o) ""))
        module (part :module) parent (part :parent) ext (part :extension)]
    (str (when (seq module) (str "/" module "/"))
         (when (seq parent) (str parent "/"))
         (part :base)
         (when (seq ext) (str "." ext)))))

(defn- find-location
  "BasicImageReader.getLocationIndex then findLocation: the location named
  name, or nil. The hash only says where it would be, so the name stored
  there is compared."
  [{:keys [index table redirect offsets] :as img} name]
  (let [nb (.getBytes ^String name "UTF-8")
        r (s4 index (+ redirect (* 4 (mod (name-hash nb hash-multiplier) table))))
        slot (cond (neg? r) (- (- r) 1)
                   (pos? r) (mod (name-hash nb r) table))]
    (when slot
      (let [loc (location img (u4 index (+ offsets (* 4 slot))))]
        (when (= name (full-name img loc)) loc)))))

;; ZipDecompressor: zlib, inflated to the size the header gives.
(defn- inflate [^bytes in off size]
  (let [inf (java.util.zip.Inflater.)
        out (byte-array size)]
    (try
      (.setInput inf in off (- (alength in) off))
      (loop [got 0]
        (if (< got size)
          (let [n (.inflate inf out got (- size got))]
            (when (and (zero? n) (or (.finished inf) (.needsInput inf) (.needsDictionary inf)))
              (throw (ex-info "runtime image entry inflates short" {:wanted size :got got})))
            (recur (+ got n)))
          out))
      (finally (.end inf)))))

;; Decompressor.decompressResource: a compressed entry starts with a 29-byte
;; CompressedResourceHeader (magic 0xCAFEFAFA, sizes, the decompressor's name
;; in the string table), and jlink may stack them, so headers are peeled until
;; none is left. jlink's zip compression (--compress zip-0 to zip-9, or the
;; older 2) is inflated; its string sharing (--compress 1) is refused.
(defn- decompress [img ^bytes b]
  (if (and (>= (alength b) 29) (= 0xCAFEFAFA (u4 b 0)))
    (let [size (+ (u4 b 12) (bit-shift-left (u4 b 16) 32))
          plugin (string-at img (u4 b 20))]
      ;; the JDK's reader refuses the same sizes (getResourceBuffer)
      (when (>= size 0x7fffffff)
        (throw (ex-info (str (:path img) " has an entry claiming " size " bytes uncompressed") {:path (:path img)})))
      (if (= "zip" plugin)
        (recur img (inflate b 29 size))
        (throw (ex-info (str (:path img) " is compressed with jlink's " plugin
                             " plugin, which champaca can't read; jlink --compress zip-6 works")
                        {:path (:path img) :plugin plugin}))))
    b))

(defn entry
  "The bytes of the entry named name (\"/java.base/java/lang/Object.class\"),
  or nil."
  [img name]
  (when-let [loc (find-location img name)]
    (let [csize (get loc :compressed 0)
          raf (:raf img)
          b (locking raf
              (read-bytes raf (+ (:index-size img) (get loc :offset 0))
                          (if (pos? csize) csize (get loc :uncompressed 0))))]
      (if (pos? csize) (decompress img b) b))))

(defn package-module
  "The module holding package pkg (\"java/util/zip\"), or nil, from the
  image's /packages/java.util.zip entry: pairs of u4, a flag and then a
  module's name in the string table (ImageReader.visitPackageLocation). The
  flag is set for a module that has the directory only on the way to deeper
  ones; jlink allows one module without it per package
  (ImageResourcesTree.PackageNode.validate). Directories of resources alone
  count as packages, META-INF does not."
  [img pkg]
  (when-let [b (entry img (str "/packages/" (.replace ^String pkg "/" ".")))]
    (some (fn [i] (when (zero? (u4 b i)) (string-at img (u4 b (+ i 4)))))
          (range 0 (alength b) 8))))
