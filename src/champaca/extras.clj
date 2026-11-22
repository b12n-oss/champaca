;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.extras
  "The startup cache's index of later programs' class entries: a data file of
  deflated entries and a small index, sorted by class name, that a run
  searches in place and reads one entry from at a time.
  extras.idx, big-endian: magic CXT1, n, the data file's name length and
  name, then n records of five ints (name-off name-len data-off data-len
  inflated-len, name-off relative to the names block), then the names' UTF-8
  bytes in sorted order. A data frame before deflating is a part count (1 or
  3) and, per part, a length (-1 for nil) and the bytes."
  (:require [clojure.java.io :as io])
  (:import (java.nio ByteBuffer)
           (java.io ByteArrayOutputStream RandomAccessFile)
           (java.util.zip Deflater Inflater)))

(def ^:private magic (.getBytes "CXT1" "UTF-8"))
(def ^:private rec-size 20)

(defn- damaged! [why]
  (throw (ex-info (str "damaged extras index: " why) {:extras :damaged})))

(defn- frame [e]
  (let [parts (if (vector? e) e [e])
        bb (ByteBuffer/allocate (+ 4 (reduce + (map #(+ 4 (if % (alength ^bytes %) 0)) parts))))]
    (.putInt bb (count parts))
    (doseq [^bytes p parts]
      (if p
        (do (.putInt bb (alength p)) (.put bb p))
        (.putInt bb -1)))
    (.array bb)))

(defn entry->bytes
  "A packed entry, a byte array or [core vt ms] with any part nil, framed and
  deflated at level 1."
  ^bytes [e]
  (let [^bytes raw (frame e)
        d (Deflater. 1)
        out (ByteArrayOutputStream. (alength raw))
        buf (byte-array 8192)]
    (.setInput d raw)
    (.finish d)
    (loop []
      (when-not (.finished d)
        (.write out buf 0 (.deflate d buf))
        (recur)))
    (.end d)
    (.toByteArray out)))

(defn bytes->entry
  "The inverse of entry->bytes, given the inflated length."
  [^bytes deflated inflated-len]
  (let [inf (Inflater.)
        raw (byte-array inflated-len)]
    (.setInput inf deflated)
    (loop [off 0]
      (when (< off inflated-len)
        (let [n (.inflate inf raw off (- inflated-len off))]
          (when (and (zero? n) (or (.finished inf) (.needsInput inf)))
            (damaged! "short entry"))
          (recur (+ off n)))))
    (.end inf)
    (let [bb (ByteBuffer/wrap raw)
          cnt (.getInt bb)
          part (fn []
                 (let [n (.getInt bb)]
                   (when-not (neg? n)
                     (let [p (byte-array n)] (.get bb p) p))))
          parts (vec (repeatedly cnt part))]
      (if (= cnt 1) (first parts) parts))))

(defn- compare-bytes
  "Unsigned lexicographic compare of a[ao, ao+al) with b[bo, bo+bl)."
  [^bytes a ao al ^bytes b bo bl]
  (let [m (min al bl)]
    (loop [i 0]
      (if (< i m)
        (let [x (bit-and (aget a (+ ao i)) 0xff)
              y (bit-and (aget b (+ bo i)) 0xff)]
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
      (.putInt bb (count recs))
      (.putInt bb (alength dn))
      (.put bb dn)
      (loop [rs recs ns names noff 0 doff 0]
        (when (seq rs)
          (let [[_ ^bytes d ilen] (first rs)
                nl (alength ^bytes (first ns))]
            (.write o d)
            (doseq [v [noff nl doff (alength d) ilen]] (.putInt bb (int v)))
            (recur (rest rs) (rest ns) (+ noff nl) (+ doff (alength d))))))
      (doseq [^bytes n names] (.put bb n)))
    (let [tmp (io/file dir (str "extras.idx.tmp-" stamp))]
      (with-open [o (io/output-stream tmp)] (.write o (.array bb)))
      (when-not (.renameTo tmp (io/file dir "extras.idx"))
        (throw (ex-info "extras index rename failed" {:dir dir}))))
    dat))

(defn open-extras
  "A handle for dir's extras.idx, or nil when there is none. The data file is
  opened once, so a later writer's replacement doesn't disturb a reader."
  [dir]
  (let [f (io/file dir "extras.idx")]
    (when (.exists f)
      (let [idx (java.nio.file.Files/readAllBytes (.toPath f))
            len (alength idx)
            _ (when (< len 12) (damaged! "truncated"))
            bb (ByteBuffer/wrap idx)
            _ (when-not (= (seq magic) (seq (java.util.Arrays/copyOfRange idx 0 4)))
                (damaged! "bad magic"))
            n (.getInt bb 4)
            dl (.getInt bb 8)]
        (when (or (neg? n) (neg? dl) (< (- len 12 dl) (* rec-size n)))
          (damaged! "truncated"))
        (let [dat (String. idx 12 (int dl) "UTF-8")]
          (try
            {:idx idx :n n :dat dat :raf (RandomAccessFile. (io/file dir dat) "r")}
            (catch java.io.IOException _ (damaged! "no data file"))))))))

(defn- rec-base [h i] (+ 12 (alength (.getBytes ^String (:dat h) "UTF-8")) (* rec-size i)))

(defn extras-find
  "The record index of name, or nil, by binary search over name bytes."
  [h ^String name]
  (let [^bytes idx (:idx h)
        ^bytes nm (.getBytes name "UTF-8")
        bb (ByteBuffer/wrap idx)
        names-at (+ (rec-base h 0) (* rec-size (:n h)))]
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
  (let [bb (ByteBuffer/wrap (:idx h))
        r (+ (rec-base h i) 8)
        d (byte-array (.getInt bb (+ r 4)))
        ^RandomAccessFile raf (:raf h)]
    (locking raf
      (.seek raf (long (.getInt bb r)))
      (.readFully raf d))
    [d (.getInt bb (+ r 8))]))

(defn extras-entry
  "The packed entry for name, or nil."
  [h name]
  (when-let [i (extras-find h name)]
    (let [[d n] (extras-raw h i)] (bytes->entry d n))))

(defn extras-names
  "Every name, in index order."
  [h]
  (let [^bytes idx (:idx h)
        bb (ByteBuffer/wrap idx)
        names-at (+ (rec-base h 0) (* rec-size (:n h)))]
    (for [i (range (:n h))]
      (let [r (rec-base h i)]
        (String. idx (int (+ names-at (.getInt bb r))) (int (.getInt bb (+ r 4))) "UTF-8")))))

(defn close-extras! [h] (.close ^RandomAccessFile (:raf h)))
