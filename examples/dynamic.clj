;; Calling Java from jolt without JVM descriptors. Overloads are chosen from
;; the argument values, the way javac chooses at a call site.
;;   jolt -M examples/dynamic.clj
(require '[champaca.api :as ldk])

(ldk/init!)

(defn p [label v] (println label v))

(p "max-int" (ldk/invoke-static "java.lang.Math" "max" 3 7))
(p "max-mixed" (ldk/invoke-static "java.lang.Math" "max" 3 7.5))
(p "max-long" (ldk/invoke-static "java.lang.Math" "max" 1 5000000000))
(p "abs" (str (ldk/invoke-static "java.lang.Math" "abs" -4) " " (ldk/invoke-static "java.lang.Math" "abs" -2.5)))
(p "valueOf" (str (ldk/invoke-static "java.lang.String" "valueOf" 42) "|"
                  (ldk/invoke-static "java.lang.String" "valueOf" true) "|"
                  (ldk/invoke-static "java.lang.String" "valueOf" \x)))
(p "parseInt" (inc (ldk/invoke-static "java.lang.Integer" "parseInt" "123")))
(p "builder" (-> (ldk/construct "java.lang.StringBuilder" "ab")
                 (ldk/invoke "append" 1) (ldk/invoke "append" \c) (ldk/invoke "append" 2.5)
                 (ldk/invoke "append" true) (ldk/invoke "toString")))
(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [x ["a" "b" "c"]] (ldk/invoke xs "add" x))
  (p "remove-index" (ldk/invoke xs "remove" 0))
  (p "remove-object" (ldk/invoke xs "remove" "b"))
  (p "list" (ldk/to-string xs)))
(let [m (ldk/construct "java.util.HashMap")]
  (ldk/invoke m "put" "k" 1)
  (p "map-get" (inc (ldk/invoke m "get" "k"))))
(let [ws (ldk/construct "java.util.ArrayList")]
  (doseq [w ["pear" "fig" "banana" "kiwi"]] (ldk/invoke ws "add" w))
  ;; plain jolt fns where Java wants a Comparator, Predicate, Function
  (ldk/invoke ws "sort" (fn [a b] (compare (count a) (count b))))
  (p "sorted" (ldk/to-string ws))
  (p "removeIf" (ldk/invoke ws "removeIf" (fn [w] (= \k (first w)))))
  (p "after" (ldk/to-string ws))
  (p "stream" (-> (ldk/invoke ws "stream")
                  (ldk/invoke "map" (fn [w] (.toUpperCase w)))
                  (ldk/invoke "collect" (ldk/invoke-static "java.util.stream.Collectors" "toList"))
                  ldk/to-string)))
(p "upper" (ldk/invoke "hello" "toUpperCase"))
(p "charAt" (ldk/invoke "abc" "charAt" 1))
(p "split" (count (ldk/->clj (ldk/invoke "a,b,c" "split" ","))))
(p "MAX_VALUE" (ldk/get-static "java.lang.Integer" "MAX_VALUE"))
;; a surrogate pair comes back as one character; a lone surrogate, which a
;; jolt string can't hold, as U+FFFD
(p "surrogates" (map int (str (ldk/invoke-static "java.lang.String" "valueOf" (ldk/invoke-static "java.lang.Character" "toChars" 0x1F600))
                              (ldk/invoke-static "java.lang.String" "valueOf" (ldk/invoke-static "java.lang.Character" "toChars" 0xD800)))))
(try (ldk/invoke-static "java.lang.Integer" "parseInt" "x")
     (catch Exception e (p "caught" (ex-message e))))
;; a native champaca lacks is an UnsatisfiedLinkError, caught like any Java exception
(p "missing native" (try (ldk/invoke-static "java.net.InetAddress" "getByName" "localhost")
                         (catch Exception e [(subs (ex-message e) 0 30) (contains? (ex-data e) :java/exception)])))
