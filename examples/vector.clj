;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The Vector API from jolt: init! with :add-modules puts jdk.incubator.vector
;; in the boot layer, as java --add-modules jdk.incubator.vector does (the
;; JDK warns that an incubator module is in use). champaca runs every vector
;; operation through the Java fallback the JDK carries for each intrinsic, so
;; results are exact but nothing is vectorized.
;;   bb example vector
(ns examples.vector
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init! {:add-modules ["jdk.incubator.vector"]})

(deftest the-module-is-in-the-boot-layer
  (is (true? (ldk/invoke (ldk/invoke (ldk/invoke-static "java.lang.ModuleLayer" "boot")
                                     "findModule" "jdk.incubator.vector")
                         "isPresent"))))

(deftest lanes-add
  (let [species (ldk/get-static "jdk.incubator.vector.IntVector" "SPECIES_128")
        ;; a vector of integers is an int[] here
        load (fn [xs] (ldk/invoke-static "jdk.incubator.vector.IntVector" "fromArray" species xs 0))
        sum (ldk/invoke (load [1 2 3 4]) "add" (load [10 20 30 40]))]
    ;; 128 bits are four ints, as HotSpot has it on arm64 and x86_64 alike
    (is (= 4 (ldk/invoke species "length")))
    (is (= [11 22 33 44] (vec (ldk/->clj (ldk/invoke sum "toArray")))))
    (is (= 110 (ldk/invoke (ldk/invoke (load [1 2 3 4]) "add" (load [10 20 30 40]))
                           "reduceLanes" (ldk/get-static "jdk.incubator.vector.VectorOperators" "ADD"))))))
