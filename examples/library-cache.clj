;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; What the library cache records about a cached class's dependencies, so a
;; later run restores the class only when they still hold.
;;   jolt example library-cache
(ns examples.library-cache
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]
            [champaca.libcache :as libcache]
            [champaca.loader :as loader]))

(ldk/init!)

(deftest a-call-site-lambda-must-be-present
  ;; A method that makes a lambda depends on the lambda's class, which has no
  ;; bytes to make it again from. It's :present, so a later run restores the
  ;; method only when its table holds that class (a cached method naming a
  ;; missing one threw NoClassDefFoundError). Before, a lambda whose caller
  ;; sits outside java.base's packages was :layer, which always held.
  (let [n (loader/lambda-class ["java/lang/Runnable"] ["run()V"] "lcx/Caller" 0)]
    (is (re-find #"^lcx/Caller\$\$Lambda/0x" n))
    (is (= {n :present} (libcache/user-deps [n])))))

(deftest a-java-base-class-needs-nothing
  ;; the startup cache's key covers the JDK, so java.base records nothing,
  ;; and champaca's own synthetic classes are :present
  (is (= {} (libcache/user-deps ["java/lang/String"])))
  (let [n (loader/lambda-class ["java/lang/Runnable"] ["run()V"])]
    (is (= {n :present} (libcache/user-deps [n])))))
