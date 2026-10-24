;; jolt keeps working after champaca boots. The runtime's Scheme files share
;; jolt's top-level environment, and a definition there with one of jolt's
;; own names (jfloat->string, jclass?, reflect-args, ...) used to replace
;; jolt's, which broke its float printing and Java interop.
;;   bb example jolt-after-init
(ns examples.jolt-after-init
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(defn- jolt-side []
  [(str (float 1.5)) (pr-str (float 0.1)) (str 2.5)
   (str (class (java.util.ArrayList.)))
   (.getName (class "x"))
   (Math/max 1 2)])

;; taken before init! when the file is loaded into a fresh process
(def before (jolt-side))

(ldk/init!)

(deftest jolt-unchanged-by-init
  (is (= ["1.5" "0.1" "2.5" "class java.util.ArrayList" "java.lang.String" 2] (jolt-side)))
  (is (= before (jolt-side)))
  (is (= "1.5" (ldk/invoke-static "java.lang.Float" "toString" 1.5))))
