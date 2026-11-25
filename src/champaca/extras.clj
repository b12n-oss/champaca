;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.extras
  "The startup cache's index of later programs' class entries: a data file of
  deflated entries and an index sorted by class name, searched in place.
  extras.idx, big-endian: magic CXT1, n, data-name length and name, n records
  of five ints (name-off name-len data-off data-len inflated-len, name-off
  relative to the names block), then the sorted names' UTF-8 bytes. A data
  frame before deflating: a part count (1 or 3), per part a length (-1 for
  nil) and the bytes."
  (:require [clojure.java.io :as io]
            [champaca.state :refer [state packed? packed-entry]])
  (:import (java.nio ByteBuffer)
           (java.io ByteArrayOutputStream RandomAccessFile)
           (java.nio.channels FileChannel OverlappingFileLockException)
           (java.nio.file OpenOption StandardOpenOption)
           (java.util.zip Deflater Inflater)))

(def ^:private magic (.getBytes "CXT1" "UTF-8"))
(def ^:private rec-size 20)
(defn- damaged! [why]
  (throw (ex-info (str "damaged extras index: " why) {:extras :damaged})))

(defn- frame-len
  "A framed entry's size: the inflated length its record keeps."
  [e]
  (+ 4 (reduce + (map #(+ 4 (if % (alength ^bytes %) 0)) (if (vector? e) e [e])))))

(defn- frame [e]
  (let [parts (if (vector? e) e [e])
        bb (ByteBuffer/allocate (frame-len e))]
    (.putInt bb (count parts))
    (doseq [^bytes p parts]
      (if p
        (do (.putInt bb (alength p)) (.put bb p))
        (.putInt bb -1)))
    (.array bb)))

(defn entry->bytes
  "A packed entry (a byte array or [core vt ms]) framed and deflated, level 1."
  ^bytes [e]
  (let [^bytes raw (frame e)
        d (Deflater. 1)
        out (ByteArrayOutputStream. (alength raw))
        buf (byte-array 8192)]
    (.setInput d raw) (.finish d)
    (loop []
      (when-not (.finished d)
        (.write out buf 0 (.deflate d buf))
        (recur)))
    (.end d) (.toByteArray out)))

(defn bytes->entry
  "The inverse of entry->bytes. A corrupt entry throws :damaged."
  [^bytes deflated inflated-len]
  (let [inf (Inflater.)]
    (try
      (let [raw (byte-array inflated-len)]
        (.setInput inf deflated)
        (loop [off 0]
          (when (< off inflated-len)
            (let [n (.inflate inf raw off (- inflated-len off))]
              (when (and (zero? n) (or (.finished inf) (.needsInput inf) (.needsDictionary inf)))
                (damaged! "short entry"))
              (recur (+ off n)))))
        (let [bb (ByteBuffer/wrap raw)
              cnt (.getInt bb)
              part (fn []
                     (let [n (.getInt bb)]
                       (when-not (neg? n)
                         (when (> n (.remaining bb)) (damaged! "part longer than the entry"))
                         (let [p (byte-array n)] (.get bb p) p))))]
          (when-not (contains? #{1 3} cnt) (damaged! "bad part count"))
          (let [parts (vec (repeatedly cnt part))] (if (= cnt 1) (first parts) parts))))
      (catch Exception e
        (if (:extras (ex-data e)) (throw e) (damaged! (str "bad entry: " (.getMessage e)))))
      (finally (.end inf)))))

(defn- compare-bytes
  "Unsigned lexicographic compare of a[ao, ao+al) with b[bo, bo+bl)."
  [^bytes a ao al ^bytes b bo bl]
  (let [m (min al bl)]
    (loop [i 0]
      (if (< i m)
        (let [x (bit-and (aget a (+ ao i)) 0xff) y (bit-and (aget b (+ bo i)) 0xff)]
          (cond (< x y) -1 (> x y) 1 :else (recur (inc i))))
        (compare al bl)))))

(defn write-extras!
  "Writes records, [name ^bytes deflated inflated-len] in any order, as
  extras-<stamp>.dat and then an index renamed into place. Returns the data
  file's name."
  [dir records]
  (let [stamp (System/nanoTime)
        dat (str "extras-" stamp ".dat")
        recs (vec (sort (fn [[a] [b]]
                          (let [^bytes x (.getBytes ^String a "UTF-8") ^bytes y (.getBytes ^String b "UTF-8")]
                            (compare-bytes x 0 (alength x) y 0 (alength y))))
                        records))
        names (mapv (fn [[n]] (.getBytes ^String n "UTF-8")) recs)
        ^bytes dn (.getBytes dat "UTF-8")
        nb (reduce + (map alength names))
        bb (ByteBuffer/allocate (+ 12 (alength dn) (* rec-size (count recs)) nb))]
    (with-open [o (io/output-stream (io/file dir dat))]
      (.put bb ^bytes magic)
      (doseq [v [(count recs) (alength dn)]] (.putInt bb (int v)))
      (.put bb dn)
      (loop [rs recs ns names noff 0 doff 0]
        (when (seq rs)
          (let [[_ ^bytes d ilen] (first rs) nl (alength ^bytes (first ns))]
            (.write o d)
            (doseq [v [noff nl doff (alength d) ilen]] (.putInt bb (int v)))
            (recur (rest rs) (rest ns) (+ noff nl) (+ doff (alength d))))))
      (doseq [^bytes n names] (.put bb n)))
    (let [tmp (io/file dir (str "extras.idx.tmp-" stamp))]
      (with-open [o (io/output-stream tmp)] (.write o (.array bb)))
      (when-not (.renameTo tmp (io/file dir "extras.idx"))
        (throw (ex-info "extras index rename failed" {:dir dir}))))
    dat))

(defn- check-records!
  "Every record field non-negative and in bounds, the file ending with the
  last name."
  [^bytes idx n names-at ^RandomAccessFile raf]
  (let [bb (ByteBuffer/wrap idx)
        names-len (- (alength idx) names-at)
        dat-len (.length raf)
        base (- names-at (* rec-size n))]
    (loop [i 0 end 0]
      (if (< i n)
        (let [r (+ base (* rec-size i))
              no (.getInt bb r) nl (.getInt bb (+ r 4)) dof (.getInt bb (+ r 8))
              dl (.getInt bb (+ r 12)) il (.getInt bb (+ r 16))]
          (when-not (and (<= 0 no) (<= 0 nl) (<= (+ no nl) names-len)
                         (<= 0 dof) (<= 0 dl) (<= (+ dof dl) dat-len) (<= 0 il))
            (damaged! "record out of bounds"))
          (recur (inc i) (max end (+ no nl))))
        (when-not (= end names-len) (damaged! "names block size"))))))

(defn open-extras
  "A handle for dir's extras.idx, or nil when there is none. The data file is
  opened once. Throws :damaged when either file is short or inconsistent. A
  data file gone because a save replaced the index meanwhile reads the new
  index, up to tries times."
  ([dir] (open-extras dir 3))
  ([dir tries]
  (let [f (io/file dir "extras.idx")]
    (when (.exists f)
      (let [idx (java.nio.file.Files/readAllBytes (.toPath f))
            len (alength idx)
            _ (when (< len 12) (damaged! "truncated"))
            bb (ByteBuffer/wrap idx)
            _ (when-not (= (seq magic) (seq (java.util.Arrays/copyOfRange idx 0 4))) (damaged! "bad magic"))
            n (.getInt bb 4)
            dl (.getInt bb 8)]
        (when (or (neg? n) (neg? dl) (< (- len 12 dl) (* rec-size n))) (damaged! "truncated"))
        (let [dat (String. idx 12 (int dl) "UTF-8") base (+ 12 dl)
              names-at (+ base (* rec-size n))
              raf (try (RandomAccessFile. (io/file dir dat) "r")
                       (catch java.io.IOException _ nil))]
          (cond
            raf (do (try (check-records! idx n names-at raf)
                         (catch Throwable e (.close raf) (throw e)))
                    {:idx idx :n n :dat dat :raf raf :base base :names-at names-at})
            (and (> tries 1) (not= (seq idx) (some-> (when (.exists f) (java.nio.file.Files/readAllBytes (.toPath f))) seq)))
            (open-extras dir (dec tries))
            :else (damaged! "no data file"))))))))

(defn- rec-base [h i] (+ (:base h) (* rec-size i)))

(defn extras-find
  "The record index of name, or nil, by binary search over name bytes."
  [h ^String name]
  (let [^bytes idx (:idx h)
        ^bytes nm (.getBytes name "UTF-8")
        bb (ByteBuffer/wrap idx)
        names-at (:names-at h)]
    (loop [lo 0 hi (dec (:n h))]
      (when (<= lo hi)
        (let [mid (quot (+ lo hi) 2)
              r (rec-base h mid)
              c (compare-bytes idx (+ names-at (.getInt bb r)) (.getInt bb (+ r 4)) nm 0 (alength nm))]
          (cond (zero? c) mid
                (neg? c) (recur (inc mid) hi)
                :else (recur lo (dec mid))))))))

(defn extras-raw
  "[deflated inflated-len] of record i, one seek and read."
  [h i]
  (let [bb (ByteBuffer/wrap (:idx h)) r (+ (rec-base h i) 8)
        d (byte-array (.getInt bb (+ r 4))) ^RandomAccessFile raf (:raf h)]
    (locking raf (.seek raf (long (.getInt bb r))) (.readFully raf d))
    [d (.getInt bb (+ r 8))]))

(defn extras-entry
  "The packed entry for name, or nil."
  [h name]
  (when-let [i (extras-find h name)]
    (apply bytes->entry (extras-raw h i))))

(defn extras-names
  "Every name, in index order."
  [h]
  (let [^bytes idx (:idx h)
        bb (ByteBuffer/wrap idx)
        names-at (:names-at h)]
    (for [i (range (:n h))]
      (let [r (rec-base h i)]
        (String. idx (int (+ names-at (.getInt bb r))) (int (.getInt bb (+ r 4))) "UTF-8")))))

(defn close-extras!
  "Close h's data file, under the lock extras-raw reads it with, so no read is
  cut off partway; a later read through h throws, which fault-in! takes for
  a handle a save replaced."
  [h]
  (let [^RandomAccessFile raf (:raf h)] (locking raf (.close raf))))

;; champaca.link installs its stderr line and cache drop for a damaged entry,
;; since cachedir, where those live, sits above this namespace
(defonce damaged-entry-fn (atom nil))

;; one stderr line per process for a read error that is not damage
(defonce ^:private failure-noted (atom false))

(defn- note-failure! [ex]
  (when (compare-and-set! failure-noted false true)
    (binding [*out* *err*]
      (println "champaca: reading the startup cache's extras failed:" (ex-message ex)))))

(defn- fault-in!
  "name's entry from the extras into the class table, or nil, noting a name
  the extras lack so it is searched once. A damaged entry runs the hook, then
  throws; any other read error is a miss not remembered, or, when a save
  swapped the handle meanwhile, a search of the new one."
  [name]
  (let [st @state h (:extras st)]
    (when (and h (not (contains? (:extras-misses st) name)))
      (let [found (try (extras-entry h name)
                       (catch Exception ex
                         (cond (= :damaged (:extras (ex-data ex)))
                               (do (when-let [f @damaged-entry-fn] (f name ex)) (throw ex))
                               ;; a save swapped in new files and closed h: search those
                               (not (identical? h (:extras @state))) ::retry
                               ;; an I/O error: a miss this time, not remembered
                               :else (do (note-failure! ex) ::failed))))]
        (cond
          (= ::retry found) (fault-in! name)
          (= ::failed found) nil
          (nil? found) (do (swap! state update :extras-misses (fnil conj #{}) name) nil)
          ;; into :lazy only when this swap put it in the table, not a racing thread
          :else
          (-> (swap! state #(if (get-in % [:classes name])
                              %
                              (-> % (assoc-in [:classes name] found) (update :lazy (fnil conj #{}) name))))
              (get-in [:classes name])))))))

(defn class-entry
  "name's class table entry, or nil. An entry in memory answers; otherwise the
  startup cache's extras are searched, and a hit goes into the table and :lazy
  as a packed entry, for ensure-loaded! to register."
  [name]
  (or (get-in @state [:classes name]) (fault-in! name)))

(defn extras-entries
  "The non-boot class entries of st's table the extras keep, as st holds them
  (save-extras! packs a map): a JDK class only when its registration sits in
  a generation (:cached-class). A later run takes any other entry for
  registered and fails with \"class not registered\": one whose save
  failed, so that a later save in the process found its map and not its
  code, or one linked and not yet recorded. Left out, it loads again in the
  run that needs it. A packed entry is kept as it is (strip-for-cache)."
  [{:keys [classes boot-classes]}]
  (into {} (filter (fn [[k v]] (and (not (contains? boot-classes k))
                                    (or (packed? v) (and (:cached-class v) (= :jdk (:origin v)) (not (:no-cache v)))))))
        classes))

(defn- file-locks?
  "Whether this jolt's FileChannel locks hold between processes. They do
  from 0.8.29, the release that also added jolt.host/build-id."
  []
  (some? (resolve 'jolt.host/build-id)))

(defn- take-lock!
  "Lock dir's extras.lock file, trying every 50 ms for up to 5 s: the open
  channel holding the lock, or nil when another holder kept it throughout.
  The OS drops a lock when its process dies, so a save that died holding it
  blocks nobody. :unlocked on a jolt older than 0.8.29, whose FileChannel
  can't lock; saves there go ahead unlocked, and two at once can lose one's
  new entries for a later run to compile again."
  [^java.io.File lock]
  (if-not (file-locks?)
    :unlocked
    (let [ch (FileChannel/open (.toPath lock)
                               (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
          got? #(try (some? (.tryLock ch))
                     ;; held by this process, outside save-lock
                     (catch OverlappingFileLockException _ false))]
      (try
        (loop [waited 0]
          (cond (got?) ch
                (>= waited 5000) (do (.close ch) nil)
                :else (do (Thread/sleep 50) (recur (+ waited 50)))))
        (catch Throwable e (.close ch) (throw e))))))

(defn- release-lock!
  "Close the lock's channel, which releases it."
  [l]
  (when (instance? FileChannel l) (.close ^FileChannel l)))

;; one save at a time in this process: a FileChannel lock is per process, and
;; a second tryLock from it throws
(defonce ^:private save-lock (Object.))

(defn index-dat
  "The data file dir's extras.idx names, read from its header alone, or nil
  when there is no index or the name can't be read."
  [dir]
  (try (let [idx (java.nio.file.Files/readAllBytes (.toPath (io/file dir "extras.idx")))
             dl (when (>= (alength idx) 12) (.getInt (ByteBuffer/wrap idx) 8))]
         (when (and dl (< 0 dl 256) (<= (+ 12 dl) (alength idx)))
           (let [dat (String. idx 12 (int dl) "UTF-8")]
             (when (re-matches #"extras-\d+\.dat" dat) dat))))
       (catch Exception _ nil)))

(defn- damaged-index!
  "One stderr line for a damaged extras.idx a save replaces, and the data
  file it names deleted when the name can be read. nil, for no index."
  [dir ex]
  (binding [*out* *err*]
    (println "champaca: replacing a damaged startup cache extras index:" (ex-message ex)))
  (when-let [dat (index-dat dir)] (.delete (io/file dir dat)))
  nil)

(defn save-extras!
  "Rewrite dir's extras from the files as they are now and entries, this
  process's non-boot JDK class entries by name (a packed entry or a thawed
  map), under a FileChannel lock on extras.lock (take-lock!). An entry this
  process holds as a map, or one the files lack, is written from memory; any
  other is copied as the files have it. Then this process reads the new
  files, and searches again the names it missed. The wait for the lock, up
  to 5 s, runs under the GIL; a lock not had by then skips the save
  (:extras-skipped, one stderr line). A damaged index is replaced, not
  merged from."
  [dir entries]
  (locking save-lock
    (let [lock (io/file dir "extras.lock")
          held (take-lock! lock)]
      (if-not held
        (do (swap! state update :extras-skipped (fnil inc 0))
            (binding [*out* *err*]
              (println "champaca: the startup cache's extras lock was held for 5 s, so this save keeps no new class entries (" (str lock) ")")))
        (do
          (try
            (let [cur (try (open-extras dir)
                           (catch Exception e
                             (when-not (:extras (ex-data e)) (throw e))
                             (damaged-index! dir e)))]
              (try
                (let [own (for [[n e] entries
                                :when (or (map? e) (nil? cur) (nil? (extras-find cur n)))
                                :let [p (cond (map? e) (packed-entry e) :else e)]]
                            [n (entry->bytes p) (frame-len p)])
                      copied (when cur
                               (for [[i n] (map-indexed vector (extras-names cur)) :when (not (map? (get entries n)))]
                                 (into [n] (extras-raw cur i))))
                      dat (write-extras! dir (concat own copied))]
                  (when (and cur (not= dat (:dat cur)))
                    (.delete (io/file dir (:dat cur)))))
                (finally (when cur (close-extras! cur)))))
            (finally (release-lock! held)))
          (let [h (open-extras dir)
                [old] (swap-vals! state #(-> % (assoc :extras h) (dissoc :extras-misses)))]
            (when-let [o (:extras old)] (close-extras! o))))))
    nil))
