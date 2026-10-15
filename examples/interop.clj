;; Calling Java from jolt through openldk-jolt. Run from the repo root:
;;   jolt -M examples/interop.clj
(require '[openldk-jolt.api :as ldk])

(ldk/init!)

(println (ldk/call-static "java/lang/Math" "sqrt" "(D)D" 2))
(println (ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 42))

(let [xs (ldk/new-object "java/util/ArrayList" "()V")]
  (doseq [w ["pear" "fig" "apple" "banana"]]
    (ldk/call xs "add" "(Ljava/lang/Object;)Z" w))
  ;; a jolt fn as a java.util.Comparator: Java calls back into jolt
  (ldk/call-static "java/util/Collections" "sort" "(Ljava/util/List;Ljava/util/Comparator;)V"
                   xs (ldk/implement "java/util/Comparator" "compare" "(Ljava/lang/Object;Ljava/lang/Object;)I"
                                     (fn [a b] (compare (count a) (count b)))))
  (println (ldk/to-string xs))
  (println (ldk/call xs "get" "(I)Ljava/lang/Object;" 0) (ldk/call xs "contains" "(Ljava/lang/Object;)Z" "fig")))

(let [m (ldk/new-object "java/util/TreeMap" "()V")]
  (doseq [[k v] {"b" 2 "a" 1 "c" 3}]
    (ldk/call m "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
              k (ldk/call-static "java/lang/Integer" "valueOf" "(I)Ljava/lang/Integer;" v)))
  (println (ldk/to-string m)))

(try
  (ldk/call-static "java/lang/Integer" "parseInt" "(Ljava/lang/String;)I" "nope")
  (catch Exception e (println "caught:" (ex-message e))))
