;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Varargs and vector-to-array calls from jolt. Trailing arguments are packed
;; into the varargs array; a jolt vector passed for an array parameter
;; becomes a Java array of the parameter's element type. The expected values
;; are what test/java/VarargsCheck.java prints on the JVM.
;;   bb example varargs
(ns examples.varargs
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(defn- s [v] (ldk/to-string v))

(deftest packed-tails
  (is (= "[a, b, c]" (s (ldk/invoke-static "java.util.List" "of" "a" "b" "c"))))
  (is (= "[x]" (s (ldk/invoke-static "java.util.List" "of" "x"))))
  (is (= "[]" (s (ldk/invoke-static "java.util.List" "of"))))
  (is (= "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]" (s (apply ldk/invoke-static "java.util.List" "of" (range 1 13)))))
  (is (= "[1, 2, 3]" (s (ldk/invoke-static "java.util.Arrays" "asList" 1 2 3))))
  (is (= "x-y-z" (ldk/invoke-static "java.lang.String" "join" "-" "x" "y" "z")))
  (is (= 30817 (ldk/invoke-static "java.util.Objects" "hash" 1 2 3))))

(deftest vectors-as-arrays
  (is (= "[3, 1, 2]" (ldk/invoke-static "java.util.Arrays" "toString" [3 1 2])))
  (is (= "[p, q]" (ldk/invoke-static "java.util.Arrays" "toString" ["p" "q"])))
  (is (= "[[1, 2], [3]]" (ldk/invoke-static "java.util.Arrays" "deepToString" [[1 2] [3]]))))

(deftest string-format
  (is (= "cart has 3 items at 2.50 each, true"
         (ldk/invoke-static "java.lang.String" "format" "%s has %d items at %.2f each, %b" "cart" 3 2.5 true)))
  (is (= "[   42|ab  |ff]" (ldk/invoke-static "java.lang.String" "format" "[%5d|%-4s|%x]" 42 "ab" 255))))

(deftest generic-results
  (is (= 9 (ldk/invoke-static "java.util.Collections" "max" (ldk/invoke-static "java.util.Arrays" "asList" 4 9 2))))
  (is (= "[NEW, BLOCKED]"
         (s (ldk/invoke-static "java.util.EnumSet" "of"
                               (ldk/get-static "java.lang.Thread$State" "NEW")
                               (ldk/get-static "java.lang.Thread$State" "BLOCKED"))))))
