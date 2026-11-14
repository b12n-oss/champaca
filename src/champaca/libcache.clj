;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.libcache
  "The caches that outlive a process: the library cache, which keeps
  classpath and loader-defined classes by jar with the fingerprints of what
  they depend on and restores them while those still hold, and the startup
  cache, the boot's compiled registrations in boot.so with the class table in
  state.img, plus a generation for each later save of what a run compiled."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.cachedir :refer [cache-origin cache-read-only? cache-unusable! delete-tree! load-unit!
                                       prune-cache-root! record! runtime-files stale-ms write-units!]]
            [champaca.classpath :refer [class-source layer-module note-class-entry! outside-boot-layer?]]
            [champaca.link :refer [ancestry ancestry-of-unlinked registration-text sealed-refused?]]
            [champaca.state :refer [state trace? timed scheme! ensure-loaded!* packed? packed= thaw-entry
                                    packed-entry strip-for-cache]]
            [jolt.image :as img]))

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

(defn- class-origin
  "The :origin of n's class table entry (:jdk, :user, :defined, whatever it
  holds), :jdk for an entry still packed, nil when the table has none."
  [n]
  (let [e (get-in @state [:classes n])] (if (packed? e) :jdk (:origin e))))

(defn- dep-value
  "What user-deps records for the class n: its fingerprint, :present (a
  synthetic class), :layer (a JDK class outside java.base), nil for a JDK
  class that needs nothing, or ::unchecked for one that can't be checked
  later."
  [n defined?]
  (case (if (:preview (get-in @state [:classes n])) :preview (class-origin n))
    ;; a class outside java.base is :layer, there only when the boot layer
    ;; holds its module (--limit-modules may leave it out of a later run,
    ;; outside-boot-layer?)
    ;; a call site's lambda class (caller$$Lambda/0x<n>) is synthetic too:
    ;; it carries :origin :jdk but sits in its caller's package, so without
    ;; this it was :layer, which outside-boot-layer? always passes, and a
    ;; cached method was restored in a run whose table lacked its lambda
    ;; (NoClassDefFoundError: Scala's LazyList$.<clinit>)
    :jdk (cond (or (str/starts-with? n "champaca/") (str/includes? n "$$Lambda/0x")) :present
               (not= "java.base" (layer-module n)) :layer
               :else nil)
    :user (or (:fp (class-source n)) ::unchecked)
    :defined (or (when defined? (get-in @state [:classes n :src-fp])) ::unchecked)
    ::unchecked))

(defn user-deps
  "The dependencies of a library cache entry whose link or compile read the
  classes in names: each classpath class among them and among everything
  they inherit from (a class's layout and vtable come from its ancestors),
  with its fingerprint (class-source). JDK classes need none, since the
  cache key covers the JDK, but a synthetic one (champaca/, a lambda's)
  has no bytes to make it again from, so it is :present, there when the
  entry is used. nil when one can't be checked later (one not in the class
  table, or one a loader defined, unless defined? says the entry is a
  loader-defined class's own, whose dependencies on others are checked by
  their fingerprints when its methods are first called: deps-still-hold!,
  or a preview class file, never cached itself, so that a run without
  --enable-preview reaches it and refuses it), which keeps the entry out of
  the cache."
  [names & [defined?]]
  (when (every? class-origin names)
    (reduce (fn [acc n]
              (let [v (dep-value n defined?)]
                (cond (= v ::unchecked) (reduced nil)
                      (nil? v) acc
                      :else (assoc acc n v))))
            {} (distinct (mapcat ancestry names)))))

(defn uncheckable-dep
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

(defn library-cache-keys
  "What a classpath class's entry carries for the library cache once
  linked, its link having read the classes in reads: :src-fp, its own
  fingerprint, :src-jar, the jar whose library file holds it, and :deps
  (user-deps). nil when it can't be cached."
  [cname reads]
  (let [src (class-source cname)]
    (when-let [fp (:fp src)]
      (when-let [deps (user-deps (conj reads cname))]
        {:src-fp fp :src-jar (:jar src) :deps (dissoc deps cname)}))))

(defn- thaw-library-entry
  "A library cache entry read back, or nil. An entry that doesn't read back
  goes alone, unlike state.img's (parse-entry): the class compiles, and the
  next save rewrites it."
  [cname packed]
  (try (thaw-entry packed)
       (catch Exception e
         (binding [*out* *err*]
           (println (str "champaca: dropping the library cache's unreadable entry for " cname ":") (ex-message e)))
         nil)))

(defn- entry-deps-hold?
  "Whether a cached class's sources are as they were: its own fingerprint,
  and each dependency present, in the boot layer, or the classpath's copy
  unchanged and not some other class of that name this run already has (a
  loader's, the JDK's)."
  [src info]
  (and (= (:fp src) (:src-fp info))
       (every? (fn [[d fp]]
                 (let [e (get-in @state [:classes d])]
                   (case fp
                     :present (some? e)
                     :layer (not (outside-boot-layer? d))
                     (and (= fp (:fp (class-source d)))
                          (or (nil? e) (= :user (:origin e)))))))
               (:deps info))))

(defn- register-restored!
  "Register a class restored from the library cache. A directory's class
  keeps the bytes it was restored as (held by class-source) for the run: a
  method it lacks (parsed) and its stack frames (frame-class) read them,
  since the file may have changed or gone by then."
  [cname src info]
  (note-class-entry! cname (:entry src))
  (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
    (ensure-loaded!* dep))
  (swap! state update :restored (fnil inc 0))
  (when-not (get-in @state [:classes cname])
    (swap! state assoc-in [:classes cname] info)
    (or (load-unit! (:cached-class info))
        (let [text (registration-text info)]
          (record! :user [cname] {:text text})
          (scheme! text :interpret)))))

(defn- drop-stale-entry!
  "A stale library cache entry dropped, noted for the save's merge."
  [cname src packed]
  (swap! state #(-> %
                    (update-in [:user-cache (:jar src)] dissoc cname)
                    (assoc-in [:library-dropped (:jar src) cname] packed)
                    (update :library-dirty (fnil conj #{}) (:jar src)))))

(defn restore-user-class!
  "Register classpath class cname from the library cache, when an earlier
  run cached it and neither it nor any class it depends on has changed
  since: true then. Else nil, the stale entry is dropped, and the class is
  parsed and compiled as usual, which caches it again at the next save."
  [cname]
  (when-let [src (class-source cname)]
    (when-let [packed (when (:fp src) (get (library-cache (:jar src)) cname))]
      (let [info (thaw-library-entry cname packed)]
        (if (and info (entry-deps-hold? src info))
          (do (register-restored! cname src info)
              true)
          (do (drop-stale-entry! cname src packed)
              nil))))))

(declare dep-matches?)

(defn- thaw-defined-entry
  "The class map in a loader-defined class's packed entry, or nil, with one
  stderr line, when the entry can't be read."
  [n packed]
  (try (timed :thaw #(thaw-entry packed))
       (catch Exception e
         (binding [*out* *err*]
           (println (str "champaca: dropping the library cache's unreadable entry for " n ":") (ex-message e)))
         nil)))

(defn- defined-changed!
  "Count a loader-defined class whose entry no longer holds, print why under
  CHAMPACA_TRACE, and answer false."
  [& why]
  (swap! state update :defined-changed (fnil inc 0))
  (when (trace?) (binding [*out* *err*] (apply println why)))
  false)

(defn- defined-entry-holds?
  "Whether the entry info for class n, defined now from bytes with
  fingerprint fp, can be restored: the same bytes, its supertypes loaded,
  no sealed supertype refusing it (refused is set then), and every ancestry
  dependency what it was."
  [n fp info refused]
  (and (or (= fp (:src-fp info))
           (defined-changed! "define" n "changed since its entry"))
       (do (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))] (ensure-loaded!* dep)) true)
       (not (and (sealed-refused? info) (vreset! refused true)))
       (every? (fn [d] (or (dep-matches? d (get (:deps info) d))
                           (defined-changed! "define" n "not restored:" d "changed")))
               (filter (:deps info) (rest (ancestry-of-unlinked info))))))

(defn- register-defined!
  "Put the restored class n (bytes bs, map info) in the table and run its
  registration, from its cached unit when it has one."
  [n bs info]
  (swap! state #(-> %
                    (assoc-in [:classes n] info)
                    (assoc-in [:defined-bytes n] bs)
                    (update :dir-bytes dissoc n)
                    (update :restored (fnil inc 0))))
  (or (load-unit! (:cached-class info))
      (let [text (registration-text info)]
        (record! :user [n] {:text text})
        (scheme! text :interpret)))
  (str "ok " n))

(defn- drop-defined-entry!
  "Drop class n's stale entry from the library cache's file dj, so the next
  save rewrites the file without it."
  [n dj packed]
  (swap! state #(-> %
                    (update-in [:user-cache dj] dissoc n)
                    (assoc-in [:library-dropped dj n] packed)
                    (update :library-dirty (fnil conj #{}) dj))))

(defn restore-defined-class!
  "Register the class a loader is defining, n from bytes bs with fingerprint
  fp, from the library cache's file dj: when an earlier run cached a class of
  that name from the same bytes and the classes it inherits from are what
  they were, \"ok <name>\"; else nil, the stale entry dropped so the save
  rewrites it, and the class parses as usual. What its methods read is
  checked when one is first called (deps-still-hold!). A class a sealed
  supertype refuses where it's defined now isn't restored, and its entry
  stays: the definition then parses and fails as the JVM's."
  [n bs fp dj]
  (if-let [packed (get (library-cache dj) n)]
    (let [refused (volatile! false)
          info (thaw-defined-entry n packed)]
      (if (and info (defined-entry-holds? n fp info refused))
        (register-defined! n bs info)
        (when-not @refused
          (drop-defined-entry! n dj packed)
          nil)))
    (do (swap! state update :defined-new (fnil inc 0)) nil)))

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
  (let [e (when-not (= fp :layer)
            (or (get-in @state [:classes d])
                (do (try (ensure-loaded!* d) (catch Exception _ nil))
                    (get-in @state [:classes d]))))]
    (cond (= fp :layer) (not (outside-boot-layer? d)) ; a JDK class outside java.base
          (nil? e) false
          (= fp :present) true   ; a lambda class in state.img, thawed or not
          (packed? e) false      ; a JDK class has that name now
          :else (= fp (or (:src-fp e) (:fp (class-source d)))))))

(defn deps-still-hold!
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

(defn write-cache!
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
    (s/call "module-graph-write!" (str (io/file tmp "module-graph.img")))
    ;; a directory missing a file (a crash mid-write, a load that failed) would
    ;; block the rename forever, so it goes first
    (when (and (.exists dir) (not-every? #(.exists (io/file dir %)) ["boot.so" "state.img" "runtime.so"]))
      (delete-tree! dir))
    (if (.renameTo tmp dir)
      (prune-cache-root! dir)
      ;; another run won the race; ours is redundant
      (delete-tree! tmp))))

(defn load-cache!
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
          (swap! state assoc :classes {} :lazy #{} :boot-classes #{})
          (if (cache-read-only? dir)
            (cache-unusable! dir (str "its copy doesn't load (" (ex-message e) ")"))
            (do (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
                (delete-tree! dir)))
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

(defn- writable-cache-dir
  "The cache directory a save may write, or nil: a cache that can't be
  written (an installed bundle's) is read only, so this run's code isn't
  kept, and the run doesn't fail for it."
  []
  (let [dir (:cache-dir @state)]
    (when (and dir (not (:cache-unusable @state)) (not (cache-read-only? dir))
               (.isDirectory dir))
      dir)))

(defn- save-units!
  "Write the recorded units as one more extra-N.so in dir, note where each
  sits, and rewrite state.img (written beside, then renamed)."
  [dir units]
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

(defn- save-library-file!
  "Rewrite jar's library file (written beside, then renamed). A failure
  leaves the jar dirty, with one stderr line."
  [jar]
  (let [f (library-file jar)
        tmp (io/file (.getParentFile f) (str (.getName f) ".tmp-" (System/nanoTime)))]
    (try
      (.mkdirs (.getParentFile f))
      (img/dump! (str tmp) {:jar jar :entries (library-entries jar (:classes @state))})
      (if (.renameTo tmp f)
        (swap! state #(-> % (update :library-dirty disj jar) (update :library-dropped dissoc jar)))
        (.delete tmp))
      (catch Exception e
        (.delete tmp)
        (binding [*out* *err*]
          (println "champaca: couldn't save the library cache for" jar (str "(" (ex-message e) ")")))))))

(defn save-generation!
  "Append what this run compiled for JDK classes after boot as one more
  extra-N.so, compiled unit by unit, and rewrite state.img (written beside,
  then renamed) with where each unit sits. A save that fails drops its
  units rather than keeping them: retrying would fail the same way at every
  later save, and the code already ran, so only cache coverage is lost."
  []
  (when-let [dir (writable-cache-dir)]
    (let [units (some-> (:recording @state) deref)]
      (when (seq units)
        (save-units! dir units)))
    ;; after the units above have their places; one jar's failure leaves it
    ;; dirty and the others still go
    (when (seq (:library-dirty @state))
      (doseq [jar (remove nil? (:library-dirty @state))]
        (save-library-file! jar))
      (swap! state update :library-dirty (fn [s] (set (remove nil? s))))
      (prune-library! dir))))
