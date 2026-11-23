;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The startup cache's index of later programs' class entries: written sorted,
;; found by binary search over name bytes, read one at a time.
;;   jolt example extras
(ns examples.extras
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]
            [champaca.extras :as x]
            [champaca.state :as st]))

;; the runner saves the startup cache after the tests, which needs the runtime
(ldk/init!)

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory "extras" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- utf8 [^String s] (.getBytes s "UTF-8"))

(defn- inflated-len [e]
  (if (vector? e)
    (+ 4 (reduce + (map #(+ 4 (if % (alength ^bytes %) 0)) e)))
    (+ 8 (alength ^bytes e))))

(defn- round-trip [e]
  (x/bytes->entry (x/entry->bytes e) (inflated-len e)))

;; a string of one char per byte, so sort-by compares lexicographically (a
;; vector of bytes would compare by length first)
(defn- utf8-unsigned [^String s] (apply str (map #(char (bit-and % 0xff)) (utf8 s))))

(defn- write-names! [names]
  (let [dir (temp-dir)
        recs (for [n names]
               (let [b (utf8 n)] [n (x/entry->bytes b) (inflated-len b)]))]
    (x/write-extras! dir recs)
    dir))

(deftest entries-round-trip
  (let [one (utf8 "{:name \"a/B\"}")
        three [(utf8 "core") nil (utf8 "methods")]]
    (is (= (seq one) (seq (x/bytes->entry (x/entry->bytes one) (inflated-len one)))))
    (is (= (map seq three) (map #(some-> % seq) (round-trip three))))))

(deftest lookups-find-every-name-and-nothing-else
  (let [names ["java/util/Map" "a/z" "a/é" "a/A" "z/Last"]
        dir (write-names! names)
        h (x/open-extras dir)]
    (doseq [n names] (is (= n (String. ^bytes (x/extras-entry h n) "UTF-8"))))
    (is (nil? (x/extras-entry h "a/Missing")))
    (is (nil? (x/extras-entry h "")))
    (is (= (sort-by utf8-unsigned names) (x/extras-names h)))
    (x/close-extras! h)))

(deftest no-index-is-nil-and-a-damaged-one-throws
  (is (nil? (x/open-extras (temp-dir))))
  (let [dir (write-names! ["a/B"])]
    (spit (io/file dir "extras.idx") "garbage")
    (is (= :damaged (:extras (ex-data (try (x/open-extras dir) (catch Exception e e))))))))

(defn- damaged? [f]
  (= :damaged (:extras (ex-data (try (f) (catch Exception e e))))))

(defn- spit-bytes! [file ^bytes b] (with-open [o (io/output-stream file)] (.write o b)))

(deftest damaged-files-throw-damaged-not-raw-errors
  (let [names ["a/B" "c/D" "e/F"]
        trunc (fn [file k]
                (let [b (java.nio.file.Files/readAllBytes (.toPath file))]
                  (spit-bytes! file (java.util.Arrays/copyOfRange b 0 (- (alength b) k)))))]
    (let [dir (write-names! names) f (io/file dir "extras.idx")
          b (java.nio.file.Files/readAllBytes (.toPath f))]
      (aset b 0 (byte 88))
      (spit-bytes! f b)
      (is (damaged? #(x/open-extras dir))))
    (let [dir (write-names! names)]
      (trunc (io/file dir "extras.idx") 2)
      (is (damaged? #(x/open-extras dir))))
    (let [dir (write-names! names) h (x/open-extras dir) dat (io/file dir (:dat h))]
      (x/close-extras! h)
      (trunc dat 3)
      (is (damaged? #(x/open-extras dir))))
    (let [dir (write-names! names) h (x/open-extras dir) dat (io/file dir (:dat h))
          b (java.nio.file.Files/readAllBytes (.toPath dat))]
      (x/close-extras! h)
      (dotimes [i (alength b)] (aset b i (unchecked-byte (bit-xor (aget b i) 0x5a))))
      (spit-bytes! dat b)
      (let [h (x/open-extras dir)]
        (is (damaged? #(x/extras-entry h "a/B")))
        (x/close-extras! h)))))

(deftest a-deleted-data-file-still-reads
  (let [dir (write-names! ["a/B" "c/D"]) h (x/open-extras dir)]
    (.delete (io/file dir (:dat h)))
    (is (= "c/D" (String. ^bytes (x/extras-entry h "c/D") "UTF-8")))
    (x/close-extras! h)))

(defn- with-extras*
  "Runs f with the state's extras over a temp dir holding names, the class
  table and :lazy empty, and the old state put back after."
  [names f]
  (let [h (x/open-extras (write-names! names))
        old @st/state]
    (swap! st/state assoc :extras h :classes {} :lazy #{} :extras-misses #{})
    (try (f)
         (finally (reset! st/state old) (x/close-extras! h)))))

(defmacro ^:private with-extras [names & body] `(with-extras* ~names (fn [] ~@body)))

(deftest a-miss-in-memory-faults-in-from-the-extras
  (with-extras ["x/Y"]
    (is (some? (x/class-entry "x/Y")))
    (is (contains? (:lazy @st/state) "x/Y"))
    (is (some? (get-in @st/state [:classes "x/Y"])))))

(deftest a-name-in-neither-is-searched-once
  (with-extras ["x/Y"]
    (is (nil? (x/class-entry "q/Absent")))
    (is (contains? (:extras-misses @st/state) "q/Absent"))))

(deftest an-entry-in-memory-wins
  (with-extras ["x/Y"]
    (swap! st/state assoc-in [:classes "x/Y"] {:name "x/Y" :origin :jdk})
    (is (= {:name "x/Y" :origin :jdk} (x/class-entry "x/Y")))
    (is (not (contains? (:lazy @st/state) "x/Y")))))

(deftest a-damaged-entry-runs-the-hook-and-throws
  (let [dir (write-names! ["x/Y"])
        h (x/open-extras dir)
        old @st/state
        seen (atom nil)
        saved @x/damaged-entry-fn]
    ;; corrupt the data file in place: the handle holds it open
    (let [dat (io/file dir (:dat h))]
      (spit dat (apply str (repeat (.length dat) "z"))))
    (swap! st/state assoc :extras h :classes {} :lazy #{} :extras-misses #{})
    (reset! x/damaged-entry-fn (fn [n ex] (reset! seen [n (:extras (ex-data ex))])))
    (try (is (thrown? Exception (x/class-entry "x/Y")))
         (is (= ["x/Y" :damaged] @seen))
         (finally (reset! x/damaged-entry-fn saved) (reset! st/state old) (x/close-extras! h)))))
