;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Java object serialization driven from jolt: a class of the program's own
;; (champaca.lib.Note, in target/lib.jar) and JDK classes, written and read
;; back through ObjectOutputStream and ObjectInputStream. ObjectInputStream
;; resolves class names through the application class loader when jolt is
;; the caller, as the JVM would for a Clojure caller.
;; target/lib.jar is built from test/jar by jolt test and jolt example.
;;   jolt example serialization
(ns examples.serialization
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init! {:classpath ["target/lib.jar"]})

(defn- freeze [o]
  (let [bytes (ldk/construct "java.io.ByteArrayOutputStream")
        out (ldk/construct "java.io.ObjectOutputStream" bytes)]
    (ldk/invoke out "writeObject" o)
    (ldk/invoke out "close")
    (ldk/invoke bytes "toByteArray")))

(defn- thaw [bytes]
  (ldk/invoke (ldk/construct "java.io.ObjectInputStream" (ldk/construct "java.io.ByteArrayInputStream" bytes)) "readObject"))

(deftest a-class-of-the-programs-own
  ;; found through the application loader
  (let [note (ldk/construct "champaca.lib.Note" "chez" 5)
        back (thaw (freeze note))]
    (is (= "Note(chez, 5, null)" (ldk/to-string back)))
    (is (= (ldk/invoke back "getClass") (ldk/invoke note "getClass")))
    (is (not (identical? back note)))
    (is (= ["chez" 5] [(ldk/get-field back "title") (ldk/get-field back "stars")]))
    (is (= "[Note(chez, 5, null), Note(chez, 5, null)]" (ldk/to-string (thaw (freeze (ldk/->java [note note]))))))))

(deftest jdk-classes
  ;; a jolt collection converted on the way in
  (is (= [1 "two" 3.5] (ldk/->clj (thaw (freeze (ldk/->java [1 "two" 3.5]))))))
  (is (= {"a" [1 2] "b" nil} (ldk/->clj (thaw (freeze (ldk/->java {"a" [1 2] "b" nil})))))))

(deftest the-streams-view-of-the-class
  ;; the JVM's computed serialVersionUID and stream length for the same class
  (let [note (ldk/construct "champaca.lib.Note" "chez" 5)]
    (is (= -1021398932933719370
           (ldk/invoke (ldk/invoke-static "java.io.ObjectStreamClass" "lookup" (ldk/invoke note "getClass")) "getSerialVersionUID")))
    (is (= 86 (count (ldk/->clj (freeze note)))))))
