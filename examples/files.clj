;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt reading and writing files through java.nio.file, and hashing with
;; MessageDigest, whose providers load through the same file system code.
;;   jolt example files
(ns examples.files
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(defn- files [method & args] (apply ldk/invoke-static "java.nio.file.Files" method args))

(deftest write-read-list-delete
  (let [dir (files "createTempDirectory" "champaca-example")
        notes (ldk/invoke dir "resolve" "notes.txt")]
    (files "writeString" notes "first\nsecond\n")
    (is (= "first\nsecond\n" (files "readString" notes)))
    (is (= ["first" "second"] (ldk/->clj (files "readAllLines" notes))))
    (is (= 13 (files "size" notes)))
    (files "createDirectories" (ldk/invoke dir "resolve" "a/b"))
    (is (= ["a" "notes.txt"]
           (sort (map #(ldk/to-string (ldk/invoke % "getFileName"))
                      (ldk/->clj (ldk/invoke (files "list" dir) "toList"))))))
    (doseq [f ["a/b" "a" "notes.txt"]] (files "delete" (ldk/invoke dir "resolve" f)))
    (files "delete" dir)
    (is (false? (files "exists" dir)))))

(deftest message-digest
  (let [md (ldk/invoke-static "java.security.MessageDigest" "getInstance" "SHA-256")]
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (apply str (map #(format "%02x" (bit-and % 0xff))
                           (ldk/->clj (ldk/invoke md "digest" (ldk/invoke "abc" "getBytes")))))))))
