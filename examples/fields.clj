;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt reading and writing Java fields, and ->clj's :key-fn.
;; target/lib.jar is built from test/jar by jolt test and jolt example.
;;   jolt example fields
(ns examples.fields
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [champaca.api :as ldk]))

(ldk/init! {:classpath ["target/lib.jar"]})

(defn- err [f] (try (f) :no-error (catch Exception e (ex-message e))))

(def ^:private F "champaca.lib.Fields")

(deftest statics
  ;; Java code sees what jolt stored
  (ldk/set-static! F "count" 41)
  (ldk/set-static! F "label" "from jolt")
  (is (= "41 from jolt" (ldk/invoke-static F "statics")))
  (is (= [42 42] [(ldk/invoke-static F "bump") (ldk/get-static F "count")])))

(deftest class-initialization
  ;; a static found in a superclass or interface initializes only its
  ;; declarer: Fields.log records each class initializer that ran. A class
  ;; initializes once per process, so these hold the first time this test runs
  ;; in a session, and nothing else here touches Base, Sub or FieldConsts.
  (is (= [7 "Base;"] [(ldk/get-static "champaca.lib.Fields$Sub" "shared") (ldk/to-string (ldk/get-static F "log"))]))
  (ldk/set-static! "champaca.lib.Fields$Sub" "shared" 8)
  (is (= [8 "Base;"] [(ldk/get-static "champaca.lib.Fields$Base" "shared") (ldk/to-string (ldk/get-static F "log"))]))
  (ldk/set-static! "champaca.lib.Fields$Sub" "shared" 7)
  (is (= [["x" "Base;FieldConsts;"] 10] [(ldk/->clj (ldk/get-static F "NAMES")) (ldk/get-static F "LIMIT")]))
  ;; a field hidden by a subclass: the most-derived one, as get-field reads.
  ;; Last, since making a Sub runs its initializer.
  (let [sub (ldk/construct "champaca.lib.Fields$Sub")]
    (ldk/set-field! sub "hidden" 9)
    (is (= [9 1] [(ldk/get-field sub "hidden") (ldk/invoke sub "baseHidden")]))))

(deftest instance-fields
  (let [o (ldk/construct F)]
    ;; every primitive type, strings, collections and arrays
    (doseq [[f v] [["i" -7] ["l" 12345678901] ["b" -128] ["s" 300] ["c" \x] ["d" 2.5] ["f" 1.25] ["z" true]
                   ["str" "hi"] ["obj" 3] ["list" [1 "two" :three]] ["ints" [4 5]]]]
      (ldk/set-field! o f v))
    (is (= "-7 12345678901 -128 300 x 2.5 1.25 true hi 3 [1, two, three] 2:4" (ldk/invoke o "describe")))
    (is (= [-7 12345678901 -128 300 \x 2.5 1.25 true "hi" 3]
           (mapv #(ldk/get-field o %) ["i" "l" "b" "s" "c" "d" "f" "z" "str" "obj"])))
    (ldk/set-field! o "c" 65)
    (ldk/set-field! o "str" nil)
    (is (= [\A nil] [(ldk/get-field o "c") (ldk/get-field o "str")]))

    ;; refused, and nothing changes
    (is (= "field ro in champaca/lib/Fields is final" (err #(ldk/set-field! o "ro" 6))))
    (is (= "field b in champaca/lib/Fields has type byte, which the value doesn't fit" (err #(ldk/set-field! o "b" 128))))
    (is (= "field i in champaca/lib/Fields has type int, which the value doesn't fit" (err #(ldk/set-field! o "i" 3000000000))))
    (is (= "field i in champaca/lib/Fields has type int, which the value doesn't fit" (err #(ldk/set-field! o "i" "seven"))))
    (is (= "field z in champaca/lib/Fields has type boolean, which the value doesn't fit" (err #(ldk/set-field! o "z" nil))))
    (is (= "no field nope in champaca/lib/Fields" (err #(ldk/set-field! o "nope" 1))))
    (is (= "field ints in champaca/lib/Fields has type int[], which the value doesn't fit" (err #(ldk/set-field! o "ints" ["x"]))))
    (is (= "field str in champaca/lib/Fields has type java.lang.String, which the value doesn't fit" (err #(ldk/set-field! o "str" 5))))
    (is (= "-7 12345678901 -128 300 A 2.5 1.25 true null 3 [1, two, three] 2:4" (ldk/invoke o "describe")))))

(deftest refused-statics
  (is (= "field FIXED in champaca/lib/Fields is final" (err #(ldk/set-static! F "FIXED" 2))))
  (is (= "no static field nope in champaca/lib/Fields" (err #(ldk/set-static! F "nope" 1)))))

(deftest key-fn
  ;; ->clj with :key-fn, applied to string keys at every depth
  (let [m (ldk/->java {"a" 1 "b" {"c" [{"d" 2}]}})]
    (is (= {:a 1 :b {:c [{:d 2}]}} (ldk/->clj m {:key-fn keyword})))
    (is (= {"a" 1 "b" {"c" [{"d" 2}]}} (ldk/->clj m))))
  (is (= {1 "one" :k 2} (ldk/->clj (ldk/->java {1 "one" "k" 2}) {:key-fn keyword})))
  (is (= "->clj: :key-fn maps two keys to :a"
         (err #(ldk/->clj (ldk/->java {"a" 1 "A" 2}) {:key-fn (comp keyword str/lower-case)}))))
  (is (= {"a" 1 "A" 2} (ldk/->clj (ldk/->java {"a" 1 "A" 2})))))
