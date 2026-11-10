;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.cachedir
  "Where the caches live and what keys them: the cache root, the translator
  and runtime fingerprint, the cache key directory, read-only caches and the
  user copy of a bundle's cache, pruning, and the compiled units a cache
  keeps."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classpath :refer [bundle-root java-home jdk-image]]
            [champaca.state :refer [state env timed]]
            [jolt.image :as img]))

(defn cache-root []
  (io/file (or (env "CHAMPACA_CACHE_DIR") (str (env "HOME") "/.cache/champaca"))))

;; --- boot cache ---------------------------------------------------------------------
;; Modeled on Clojure's RT.load: one compiled unit per input, used only when
;; it is newer than its source, and a miss quietly takes the slow path. The
;; unit here is the whole JDK boot. Clojure's key is a timestamp; this key
;; also covers the translator itself, because a cache built by a different
;; translator is stale even when the JDK is not (Clojure's AOT
;; version-mismatch trap).

(def runtime-files
  "The Scheme runtime, in load order."
  ["champaca/runtime.ss" "champaca/natives.ss" "champaca/reflect.ss" "champaca/handles.ss" "champaca/files.ss"
   "champaca/net.ss" "champaca/spawn.ss" "champaca/foreign.ss"])

(def ^:private translator-sources
  ["champaca/codegen.clj" "champaca/main.clj" "champaca/classfile.clj" "champaca/jimage.clj"
   "champaca/state.clj" "champaca/launcher.clj" "champaca/classpath.clj" "champaca/cachedir.clj"
   "champaca/link.clj" "champaca/libcache.clj" "champaca/loader.clj" "champaca/jit.clj"
   "champaca/boot.clj"])

(defmacro ^:private built-sources-hash
  "The translator sources' hash where this namespace is compiled: what a
  `jolt build` binary, which carries no source of a namespace compiled into
  it, keys its cache with."
  []
  ;; a source missing where the binary is built would leave the key blind to
  ;; it: fail the build instead
  (hash (apply str (map #(slurp (or (io/resource %) (throw (ex-info (str "built-sources-hash: no " %) {}))))
                        translator-sources))))

(defn translator-fingerprint []
  (let [rt (apply str (map #(slurp (io/resource %)) runtime-files))]
    ;; a built binary carries the runtime files and not the translator's
    ;; sources, which io/resource could still find in a checkout beside it
    ;; (a deps.edn in the current directory): the hash taken at build time.
    ;; Elsewhere the sources themselves, since jolt caches a compiled
    ;; namespace by its own text, so this namespace's macro alone could miss a
    ;; codegen change
    (if (jolt.host/embedded-resource? "champaca/runtime.ss")
      (hash [rt (built-sources-hash)])
      (hash (apply str rt (map #(slurp (io/resource %)) translator-sources))))))

(defn- image-index-crc
  "CRC-32 of the runtime image's index: it changes with any resource added,
  removed, moved or resized, which a JDK build of another version or a
  different jlink selection does."
  []
  (let [idx ^bytes (:index (jdk-image)) c (java.util.zip.CRC32.)]
    (.update c idx 0 (count idx))
    (.getValue c)))

(defn- writable-root?
  "Whether the cache root can be written: it does, or the nearest directory
  above it that exists does (mkdirs makes the rest)."
  [^java.io.File f]
  (loop [f (.getAbsoluteFile f)]
    (cond (nil? f) false
          (.exists f) (.canWrite f)
          :else (recur (.getParentFile f)))))

(defn cache-read-only?
  "Whether the cache key directory d can't be written: its root can't, or d
  is there and can't."
  [^java.io.File d]
  (not (and (writable-root? (.getParentFile (.getAbsoluteFile d))) (or (not (.exists d)) (.canWrite d)))))

(defn cache-unusable!
  "This run goes without a cache: d can't be written and has none, or none
  that loads. Said once, on stderr. Answers nil."
  [d why]
  (when-not (:cache-unusable @state)
    (swap! state assoc :cache-unusable true)
    (when-not (:cache-unusable-said @state)
     (binding [*out* *err*]
      (println (str "champaca: the startup cache at " (.getParent (io/file d)) " can't be written and " why
                    ", so this run uses none")))))
  nil)

(defn delete-tree! [f] (doseq [x (reverse (file-seq f))] (.delete x)))

;; A key directory is left behind whenever its key changes (a translator edit,
;; a JDK update), so writing a new one removes siblings no run has loaded for a
;; week: a hit touches its directory. Other live keys (CHAMPACA_BT, a second
;; JDK) stay as long as something uses them. jolt/ never matches, nor does
;; jmods/, where older versions kept copies of the JDK's jmods.
;; The age rule alone is not enough on a machine where the translator itself
;; is edited: every edit is a new key, none a week old, and a day of work
;; leaves gigabytes. So the newest few keys survive and the rest go, however
;; young. Half-written .tmp- directories are collected by age only.
(def stale-ms (* 7 24 60 60 1000))
(def ^:private keep-keys 16)

(defn prune-cache-root! [keep]
  (let [cutoff (- (System/currentTimeMillis) stale-ms)
        sibs (filter #(and (.isDirectory %)
                           ;; a key, a user copy's <key>-<seed hash>, their temp dirs
                           (re-matches #"[0-9a-f]{1,8}(-[0-9a-f]{1,8})?(\.tmp-\d+)?" (.getName %))
                           (not= (.getName %) (.getName keep)))
                     (.listFiles (.getParentFile keep)))
        keys-newest-first (sort-by (juxt #(- (.lastModified %)) #(.getName %))
                                   (remove #(str/includes? (.getName %) ".tmp-") sibs))
        over (set (drop (dec keep-keys) keys-newest-first))]
    (doseq [d sibs
            :when (or (< (.lastModified d) cutoff) (over d))]
      (delete-tree! d))))

(defn- user-cache-root
  "Where a bundle whose own cache can't be written keeps a writable one:
  CHAMPACA_USER_CACHE_DIR (off for none), else ~/.cache/champaca/bundles, one
  directory per bundle path below it."
  []
  (let [v (env "CHAMPACA_USER_CACHE_DIR")]
    (cond (= v "off") nil
          (seq v) (io/file v)
          (seq (env "HOME")) (io/file (env "HOME") ".cache" "champaca" "bundles")
          :else nil)))

(defn- seed-id
  "What a bundle's key directory d holds, as far as a user copy of it needs to
  know: state.img's CRC-32 and size, boot.so's size and the generations' names
  (all written only by a build or a save), nil when d has no cache."
  [^java.io.File d]
  (let [img (io/file d "state.img") so (io/file d "boot.so")]
    (when (and (.isFile img) (.isFile so))
      (let [c (java.util.zip.CRC32.)
            bs (with-open [in (io/input-stream img)] (.readAllBytes in))]
        (.update c bs 0 (count bs))
        (str (.getValue c) ":" (count bs) ":" (.length so) ":"
             (str/join "," (sort (filter #(str/starts-with? % "extra-") (map #(.getName ^java.io.File %) (.listFiles d))))))))))

(defn- copy-tree! [^java.io.File from ^java.io.File to]
  (doseq [^java.io.File f (file-seq from)
          :let [rel (subs (.getPath f) (count (.getPath from)))
                t (io/file (str (.getPath to) rel))]]
    (if (.isDirectory f) (.mkdirs t) (io/copy f t))))

(defn- copy-to-user-cache!
  "u, the user cache's copy of the bundle's key directory d: copied into a
  temp dir and renamed into place when missing (with a seed id), the
  winner's when another process's rename won. nil, said on stderr, when the
  copy fails."
  [d sid root u]
  (let [tmp (io/file root (str (.getName u) ".tmp-" (System/nanoTime)))]
    (try
      (when (and sid (not (.isDirectory u)))
        (.mkdirs root)
        (copy-tree! d tmp)
        (if (.renameTo tmp u)
          ;; a new version of the bundle's cache: older copies
          ;; go by the usual rules (a week unused, 16 at most)
          (prune-cache-root! u)
          (when-not (.isDirectory u)
            (throw (ex-info "rename failed" {})))))
      u
      (catch Exception e
        (binding [*out* *err*]
          (println "champaca: couldn't copy the bundle's cache to" (str root) (str "(" (ex-message e) ")")))
        nil)
      (finally (when (.exists tmp) (delete-tree! tmp))))))

(defn user-copy
  "For a bundle's key directory d that can't be written, a writable copy under
  the user's cache for this bundle (user-cache-root, then a hash of the
  bundle's path), which runs then read and write as any cache: <key>-<hash of
  d's seed-id>, so each version of the bundle's cache gets a directory of its
  own and none is ever deleted under a process using it. A missing one is
  copied into a temp dir and renamed into place; when another process's
  rename won, that one is used and the temp dropped. Asked once per process.
  nil when there's no user cache, or it can't be written (said once), and the
  bundle's own cache is then read alone."
  [^java.io.File d]
  (if (contains? @state :user-copy)
    (:user-copy @state)
    (let [r (when-let [base (user-cache-root)]
              (let [h #(format "%08x" (bit-and (hash %) 0xffffffff))
                    root (io/file (.getAbsoluteFile base) (str (h @bundle-root) (h (str/reverse @bundle-root))))
                    ;; a seed it can't read (an install's permissions, an I/O
                    ;; error) has no id: no copy, and the run goes on as the
                    ;; bundle's own cache allows
                    sid (try (seed-id d) (catch Exception _ nil))
                    u (io/file root (str (.getName d) "-" (if sid (h sid) "0")))]
                (if-not (writable-root? root)
                  (do (binding [*out* *err*]
                        (println (str "champaca: the bundle's cache can't be written, nor its user cache at " root
                                      "; this run keeps nothing it compiles")))
                      ;; said: cache-unusable! needn't say it again
                      (swap! state assoc :cache-unusable-said true)
                      nil)
                  (copy-to-user-cache! d sid root u))))]
      (swap! state assoc :user-copy r)
      r)))

(defn cache-dir []
  (when-not (= "off" (env "CHAMPACA_CACHE"))
    (let [modules (io/file (java-home) "lib" "modules")
          release (io/file (java-home) "release")
          ;; CHAMPACA_BT changes the generated code (shadow-stack wrappers that
          ;; hide frame names from getCallerClass), so it gets its own cache;
          ;; CHAMPACA_LINES decides which code carries inspector information
          ;; outside a bundle the image's mtime, as before: any rewrite of the
          ;; file, a same-size change to one class included, gives a fresh
          ;; cache. In a bundle (CHAMPACA_BUNDLE) its index by CRC-32 (every
          ;; resource's name, offset and sizes, not their bytes), since a copy
          ;; of the bundle has new mtimes and must keep its cache; the image
          ;; there is the one the bundle shipped with
          key (hash [(when (.exists release) (slurp release)) (.length modules)
                     (if @bundle-root (image-index-crc) (.lastModified modules))
                     (translator-fingerprint) (img/runtime-version) (some? (env "CHAMPACA_BT")) (some-> (env "CHAMPACA_LINES") str/lower-case)])
          d (io/file (cache-root) (Long/toHexString (bit-and key 0xffffffff)))
          ;; a bundle whose own cache can't be written runs on a copy in the
          ;; user's (user-copy)
          d (or (when (and @bundle-root (cache-read-only? d)) (user-copy d)) d)]
      ;; a cache that can't be written (an installed bundle's) is read when
      ;; it's there and loads; with none for this key, or one that failed to
      ;; load (cache-unusable!), the run has no cache rather than failing the
      ;; boot that would write one
      (cond (:cache-unusable @state) nil
            (or (.isDirectory d) (not (cache-read-only? d))) d
            :else (cache-unusable! d "has none for this run")))))

(defn cache-origin
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

(defn record!
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

(defn compile-to-cache?
  "Whether a JDK method should compile straight to object code kept for the
  cache (compile-unit): during boot, whose cache write always follows, or
  when this run saves a generation at exit (run does). Otherwise it is
  recorded as text, cheaper to compile, and compiled again only if
  save-cache! is called after all."
  [origin]
  (and origin (:recording @state)
       (or (nil? (:boot-status @state)) (:saves-generation @state))))

(defn write-units!
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

(defn load-unit!
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
