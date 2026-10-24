;; Calling Java from jolt through champaca, with JVM descriptors. Run from the
;; repo root:
;;   bb example interop
(ns examples.interop
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(deftest static-calls
  (is (= 1.4142135623730951 (ldk/call-static "java/lang/Math" "sqrt" "(D)D" 2)))
  (is (= "101010" (ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 42))))

(deftest jolt-fn-as-comparator
  (let [xs (ldk/new-object "java/util/ArrayList" "()V")]
    (doseq [w ["pear" "fig" "apple" "banana"]]
      (ldk/call xs "add" "(Ljava/lang/Object;)Z" w))
    ;; a jolt fn as a java.util.Comparator: Java calls back into jolt
    (ldk/call-static "java/util/Collections" "sort" "(Ljava/util/List;Ljava/util/Comparator;)V"
                     xs (ldk/implement "java/util/Comparator" "compare" "(Ljava/lang/Object;Ljava/lang/Object;)I"
                                       (fn [a b] (compare (count a) (count b)))))
    (is (= "[fig, pear, apple, banana]" (ldk/to-string xs)))
    (is (= "fig" (ldk/call xs "get" "(I)Ljava/lang/Object;" 0)))
    (is (true? (ldk/call xs "contains" "(Ljava/lang/Object;)Z" "fig")))))

(deftest tree-map
  (let [m (ldk/new-object "java/util/TreeMap" "()V")]
    (doseq [[k v] {"b" 2 "a" 1 "c" 3}]
      (ldk/call m "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                k (ldk/call-static "java/lang/Integer" "valueOf" "(I)Ljava/lang/Integer;" v)))
    (is (= "{a=1, b=2, c=3}" (ldk/to-string m)))))

(deftest java-exception
  (is (= "java.lang.NumberFormatException: For input string: \"nope\""
         (try (ldk/call-static "java/lang/Integer" "parseInt" "(Ljava/lang/String;)I" "nope")
              (catch Exception e (ex-message e))))))
