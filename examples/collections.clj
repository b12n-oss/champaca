;; jolt maps, vectors, sets and seqs as java.util collections, and back.
;; test/java/CollectionsCheck.java makes the same Java-side calls on the JVM.
;;   jolt -M examples/collections.clj
(require '[champaca.api :as ldk])

(ldk/init!)

(defn p [label v] (println label (pr-str v)))

;; jolt -> Java, chosen by the parameter type
(p "treemap" (ldk/to-string (ldk/construct "java.util.TreeMap" {:b 2 :a 1 "c" [1 2]})))
(p "max" (ldk/invoke-static "java.util.Collections" "max" [3 9 4]))
(p "frequency" (ldk/invoke-static "java.util.Collections" "frequency" '(1 2 1 1) 1))
(p "join" (ldk/invoke-static "java.lang.String" "join" "-" (list "a" "b" "c")))
(p "contains" (ldk/invoke (ldk/construct "java.util.HashSet" #{1 2 3}) "contains" 2))
(p "copyOf" (ldk/to-string (ldk/invoke-static "java.util.List" "copyOf" [1 [2 3] {:k "v"}])))
(p "putAll" (let [m (ldk/construct "java.util.HashMap")]
              (ldk/invoke m "putAll" {:x 1 :y nil})
              [(ldk/invoke m "get" "x") (ldk/invoke m "containsKey" "y") (ldk/invoke m "size")]))
(p "array-still" (ldk/invoke-static "java.util.Arrays" "toString" [1 2 3]))

;; Java -> jolt
;; Map.of iterates in a per-run order, so compare rather than print
(p "map" (= {"a" 1 "b" [1 2]} (ldk/->clj (ldk/invoke-static "java.util.Map" "of" "a" 1 "b" (ldk/invoke-static "java.util.List" "of" 1 2)))))
(p "set" (ldk/->clj (ldk/invoke-static "java.util.Set" "of" "x")))
(p "nested" (ldk/->clj (ldk/->java {:a [1 {:b #{2}}] :c '(3 4) :d "s" :e 2.5 :f true :g nil})))
(p "round-trip" (= {"a" [1 2] "b" #{"c"}} (ldk/->clj (ldk/->java {:a [1 2] :b #{:c}}))))
(p "class" (ldk/invoke (ldk/invoke (ldk/->java {:a 1}) "getClass") "getName"))
