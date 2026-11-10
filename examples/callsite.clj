;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The call-site cache: a call remembers which overload it chose for the kinds
;; of arguments it saw. Each test reuses a site with arguments that need a
;; different choice, so a cache keyed too coarsely answers something else.
;;   jolt example callsite
;;   CHAMPACA_CALL_CACHE=off jolt example callsite   (the same, with no cache)
(ns examples.callsite
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]
            [jolt.scheme :as s]))

(ldk/init!)

(defn- max-of [a b] (ldk/invoke-static "java.lang.Math" "max" a b))
(defn- value-of [x] (ldk/invoke-static "java.lang.String" "valueOf" x))
(defn- text [o] (ldk/invoke o "toString"))
(defn- fmt [f & args] (apply ldk/invoke-static "java.lang.String" "format" f args))
(defn- as-list [v] (ldk/invoke (ldk/invoke-static "java.util.Arrays" "asList" v) "toString"))

(deftest one-site-many-overloads
  ;; max(int,int), max(long,long), max(double,double)
  (is (= [7 5000000000 7.5 7 2.5] (mapv (fn [[a b]] (max-of a b)) [[3 7] [1 5000000000] [2 7.5] [3 7] [2.5 1]])))
  ;; valueOf(int), valueOf(char), valueOf(boolean), valueOf(double), valueOf(Object)
  (is (= ["42" "x" "true" "2.5" "42" "sb"]
         (mapv value-of [42 \x true 2.5 42 (ldk/construct "java.lang.StringBuilder" "sb")]))))

(deftest one-name-many-receivers
  (is (= ["a" "[]" "b"] (mapv text [(ldk/construct "java.lang.StringBuilder" "a")
                                    (ldk/construct "java.util.ArrayList")
                                    (ldk/construct "java.lang.StringBuilder" "b")]))))

(deftest varargs-of-other-lengths
  (is (= ["a" "a-b-c" "1+2" "z"]
         (mapv (fn [args] (apply fmt args)) [["%s" "a"] ["%s-%s-%s" "a" "b" "c"] ["%d+%d" 1 2] ["%s" "z"]]))))

(deftest vectors-of-other-lengths-and-kinds
  (is (= ["[a, b]" "[c]" "[1, 2, 3]" "[1, mixed]" "[]" "[d, e, f]"]
         (mapv as-list [["a" "b"] ["c"] [1 2 3] [1 "mixed"] [] ["d" "e" "f"]]))))

(deftest constructors
  ;; StringBuilder(String), StringBuilder(int), StringBuilder()
  (is (= [3 0 0 2] (mapv (fn [args] (ldk/invoke (apply ldk/construct "java.lang.StringBuilder" args) "length"))
                         [["abc"] [16] [] ["xy"]]))))

(deftest failed-lookups-and-nil
  ;; a failed lookup isn't cached: it fails the same way twice
  (is (= [:lookup :lookup]
         (vec (for [_ (range 2)]
                (try (ldk/invoke-static "java.lang.Math" "max" "a" "b") :no-error
                     (catch :default e (:champaca/error (ex-data e))))))))
  ;; nil picks the most specific overload, every time
  (is (= ["null" "null"] (mapv (fn [_] (ldk/invoke-static "java.util.Objects" "toString" nil)) (range 2)))))

(deftest integer-vectors-key-by-their-widest
  ;; [1 300 70000] mixes byte, short and int ranges and keys as int, as
  ;; [5 100000] does: one entry for both, and [1 2 3] (all bytes) a second
  (s/call "call-cache-clear!")
  (is (= ["[1, 300, 70000]" "[5, 100000]" "[1, 2, 3]"]
         (mapv (fn [v] (ldk/invoke-static "java.util.Arrays" "toString" v)) [[1 300 70000] [5 100000] [1 2 3]])))
  (is (= (if (= "off" (System/getenv "CHAMPACA_CALL_CACHE")) 0 2) (s/call "call-cache-size"))))

(deftest the-cache-is-used
  (max-of 1 2)
  (is (= (not= "off" (System/getenv "CHAMPACA_CALL_CACHE"))
         (pos? (s/call "call-cache-size")))))
