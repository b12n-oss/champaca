;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; hn.main against a hand-made front page, so nothing here needs the network.
;; jolt hn:test runs it with the example's jars on CHAMPACA_CLASSPATH.
(ns hn.main-test
  (:require [champaca.api :as ldk]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hn.main :as hn]))

(ldk/init!)

(def page (slurp "test/hn/frontpage.html"))

(deftest every-shape-of-row
  (is (= [{:id "1001" :rank 1 :title "Running Java without a JVM"
           :url "https://example.org/blog/running-java-without-a-jvm" :site "example.org"
           :points 412 :by "alice" :age "3 hours ago" :comments 128}
          {:id "1002" :rank 2 :title "Ask HN: What do you use to read the front page?"
           :url "https://news.ycombinator.com/item?id=1002" :site nil
           :points 57 :by "bob" :age "1 hour ago" :comments 1}
          {:id "1003" :rank 3 :title "Example Corp (YC W26) is hiring engineers"
           :url "https://example.com/careers" :site "example.com"
           :points nil :by nil :age "4 hours ago" :comments nil}
          {:id "1004" :rank 4 :title "A Kotlin library, called from Clojure"
           :url "https://example.net/a-kotlin-library-from-clojure" :site "example.net"
           :points 3 :by "carol" :age "10 minutes ago" :comments 0}]
         (hn/stories page))))

(deftest printed-text
  (let [out (with-out-str (hn/print-stories (take 3 (hn/stories page)) false))]
    (testing "a story with its points, author, age and comments"
      (is (str/includes? out "  1. Running Java without a JVM (example.org)\n     412 points by alice, 3 hours ago, 128 comments\n     https://example.org/blog/running-java-without-a-jvm")))
    (testing "a job post: its age alone"
      (is (str/includes? out "  3. Example Corp (YC W26) is hiring engineers (example.com)\n     4 hours ago\n")))
    (is (not (str/includes? out "Kotlin library")) "--limit's take")))

(deftest edn-output-reads-back
  (let [out (with-out-str (hn/print-stories (hn/stories page) true))]
    (is (= (hn/stories page) (mapv read-string (str/split-lines out))))))

(deftest options
  (is (= 0 (hn/run {:help true})))
  (is (str/starts-with? (with-out-str (hn/run {:file "test/hn/frontpage.html" :limit 1 :color "never"}))
                        "  1. Running Java without a JVM"))
  (is (true? (hn/color? "always")))
  (is (false? (hn/color? "never"))))

(defn- strip-ansi [s] (str/replace s (re-pattern "\u001b\\[[0-9;]*m") ""))

(deftest colour-through-fansi
  ;; fansi (Scala) puts the escapes in; without them the text is the plain one
  (let [ss (hn/stories page)
        plain (with-out-str (hn/print-stories ss false false))
        coloured (with-out-str (hn/print-stories ss false true))]
    (is (str/includes? coloured "\u001b[1mRunning Java without a JVM"))
    (is (str/includes? coloured "\u001b[36mexample.org"))
    (is (not= plain coloured))
    (is (= plain (strip-ansi coloured)))))

(deftest a-fetch-through-okhttp-that-fails
  ;; OkHttp (Kotlin) refuses a URL with no scheme with its own message,
  ;; through the same calls a real fetch makes, and no network is touched
  (let [e (try (hn/fetch "not a url") nil (catch Exception e e))]
    (is (some? e))
    ;; the Java exception reaches jolt as an ex-info carrying it
    (is (str/starts-with? (ex-message e) "java.lang.IllegalArgumentException: Expected URL scheme 'http' or 'https'"))))
