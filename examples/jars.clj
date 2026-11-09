;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt calling into a jar, and into JDK modules other than java.base.
;; target/lib.jar is built from test/jar by bb test and bb example.
;;   bb example jars
(ns examples.jars
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init! {:classpath ["target/lib.jar"]})

(deftest a-class-from-the-jar
  (is (= 10 (ldk/invoke-static "champaca.lib.Shelf" "sum" [1 2 3 4])))
  (is (= ["x" "x"] (ldk/->clj (ldk/invoke-static "champaca.lib.Shelf" "fill"
                                                 (ldk/invoke-static "java.lang.Class" "forName" "java.lang.String")
                                                 2 "x"))))
  (is (= [3 3] (mapv count (ldk/->clj (ldk/invoke-static "champaca.lib.Shelf" "grid" 2 3))))))

(deftest narrowing-only-when-nothing-else-fits
  ;; champaca.lib.Overloads: what javac picks for an int 5 (a Java caller's
  ;; output, as an int variable can't narrow), and only(byte), which only
  ;; narrowing reaches
  (is (= ["varargs 1" "long 5" "byte 5"]
         [(ldk/invoke-static "champaca.lib.Overloads" "pick" 5)
          (ldk/invoke-static "champaca.lib.Overloads" "wide" 5)
          (ldk/invoke-static "champaca.lib.Overloads" "only" 5)])))

(deftest unsafe-from-the-jar
  ;; Shelf.bumpThroughUnsafe adds one to the static count through
  ;; sun.misc.Unsafe and returns it
  (is (= 3 (ldk/get-static "champaca.lib.Shelf" "count")))
  (is (= 4 (ldk/invoke-static "champaca.lib.Shelf" "bumpThroughUnsafe")))
  ;; back to its initial value, so the test passes again in the same session
  (ldk/set-static! "champaca.lib.Shelf" "count" 3))

(deftest other-jdk-modules
  (is (= "WARNING" (ldk/invoke (ldk/get-static "java.util.logging.Level" "WARNING") "getName")))
  (is (= 0 (ldk/invoke (ldk/construct "java.sql.Date" 0) "getTime")))
  (is (= 32400000 (ldk/invoke (ldk/invoke-static "java.util.TimeZone" "getTimeZone" "Asia/Tokyo") "getRawOffset"))))
