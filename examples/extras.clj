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
            [champaca.extras :as x]))

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

(deftest a-deleted-data-file-still-reads
  (let [dir (write-names! ["a/B" "c/D"]) h (x/open-extras dir)]
    (.delete (io/file dir (:dat h)))
    (is (= "c/D" (String. ^bytes (x/extras-entry h "c/D") "UTF-8")))
    (x/close-extras! h)))
