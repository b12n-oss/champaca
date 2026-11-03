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

;; an integral parameter or a jolt fn's integral result takes an integer in
;; its type's range (or a char, as its code) and refuses anything else, as
;; Method.invoke and a lambda's cast would
(defn- message [f] (try (f) (catch Exception e (ex-message e))))

(deftest integral-arguments
  (is (= "1100001" (ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" \a)))
  (is (= "java.lang.IllegalArgumentException: 1.5 is not a Java int"
         (message #(ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 1.5))))
  (is (= "java.lang.IllegalArgumentException: nil is not a Java int"
         (message #(ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" nil))))
  (is (= "java.lang.IllegalArgumentException: 2147483648 is not a Java int"
         (message #(ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 2147483648))))
  (is (= "java.lang.IllegalArgumentException: 128 is not a Java byte"
         (message #(ldk/call-static "java/lang/Byte" "toString" "(B)Ljava/lang/String;" 128))))
  (is (= "java.lang.IllegalArgumentException: 0.5 is not a Java long"
         (message #(ldk/call-static "java/lang/Long" "toString" "(J)Ljava/lang/String;" 0.5))))
  (is (= "9223372036854775807" (ldk/call-static "java/lang/Long" "toString" "(J)Ljava/lang/String;" 9223372036854775807))))

(deftest integral-results
  (let [xs (ldk/new-object "java/util/ArrayList" "()V")]
    (doseq [w ["b" "a"]] (ldk/call xs "add" "(Ljava/lang/Object;)Z" w))
    (is (= "java.lang.ClassCastException: 1.5 is not a Java int"
           (message #(ldk/call-static "java/util/Collections" "sort" "(Ljava/util/List;Ljava/util/Comparator;)V"
                                      xs (ldk/implement "java/util/Comparator" "compare" "(Ljava/lang/Object;Ljava/lang/Object;)I"
                                                        (fn [a b] 1.5))))))
    (is (= "java.lang.ClassCastException: nil is not a Java int"
           (message #(ldk/call-static "java/util/Collections" "sort" "(Ljava/util/List;Ljava/util/Comparator;)V"
                                      xs (ldk/implement "java/util/Comparator" "compare" "(Ljava/lang/Object;Ljava/lang/Object;)I"
                                                        (fn [a b] nil))))))))
