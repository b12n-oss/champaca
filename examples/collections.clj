;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt maps, vectors, sets and seqs as java.util collections, and back.
;; test/java/CollectionsCheck.java makes the same Java-side calls on the JVM.
;;   bb example collections
(ns examples.collections
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

;; jolt -> Java, chosen by the parameter type
(deftest jolt-to-java
  (is (= "{a=1, b=2, c=[1, 2]}" (ldk/to-string (ldk/construct "java.util.TreeMap" {:b 2 :a 1 "c" [1 2]}))))
  (is (= 9 (ldk/invoke-static "java.util.Collections" "max" [3 9 4])))
  (is (= 3 (ldk/invoke-static "java.util.Collections" "frequency" '(1 2 1 1) 1)))
  (is (= "a-b-c" (ldk/invoke-static "java.lang.String" "join" "-" (list "a" "b" "c"))))
  (is (true? (ldk/invoke (ldk/construct "java.util.HashSet" #{1 2 3}) "contains" 2)))
  (is (= "[1, [2, 3], {k=v}]" (ldk/to-string (ldk/invoke-static "java.util.List" "copyOf" [1 [2 3] {:k "v"}]))))
  (is (= [1 true 2]
         (let [m (ldk/construct "java.util.HashMap")]
           (ldk/invoke m "putAll" {:x 1 :y nil})
           [(ldk/invoke m "get" "x") (ldk/invoke m "containsKey" "y") (ldk/invoke m "size")])))
  ;; a vector where Java wants an array is still an array
  (is (= "[1, 2, 3]" (ldk/invoke-static "java.util.Arrays" "toString" [1 2 3]))))

;; Java -> jolt
(deftest java-to-jolt
  (is (= {"a" 1 "b" [1 2]}
         (ldk/->clj (ldk/invoke-static "java.util.Map" "of" "a" 1 "b" (ldk/invoke-static "java.util.List" "of" 1 2)))))
  (is (= #{"x"} (ldk/->clj (ldk/invoke-static "java.util.Set" "of" "x"))))
  (is (= {"a" [1 {"b" #{2}}] "c" [3 4] "d" "s" "e" 2.5 "f" true "g" nil}
         (ldk/->clj (ldk/->java {:a [1 {:b #{2}}] :c '(3 4) :d "s" :e 2.5 :f true :g nil}))))
  (is (= {"a" [1 2] "b" #{"c"}} (ldk/->clj (ldk/->java {:a [1 2] :b #{:c}}))))
  (is (= "java.util.LinkedHashMap" (ldk/invoke (ldk/invoke (ldk/->java {:a 1}) "getClass") "getName"))))
