;; Calling Java from jolt without JVM descriptors. Overloads are chosen from
;; the argument values, the way javac chooses at a call site. The expected
;; values are what test/java/DynamicCheck.java, which makes the same calls,
;; prints on the JVM.
;;   bb example dynamic
(ns examples.dynamic
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(deftest overloads-from-values
  (is (= 7 (ldk/invoke-static "java.lang.Math" "max" 3 7)))
  (is (= 7.5 (ldk/invoke-static "java.lang.Math" "max" 3 7.5)))
  (is (= 5000000000 (ldk/invoke-static "java.lang.Math" "max" 1 5000000000)))
  (is (= 4 (ldk/invoke-static "java.lang.Math" "abs" -4)))
  (is (= 2.5 (ldk/invoke-static "java.lang.Math" "abs" -2.5)))
  (is (= ["42" "true" "x"]
         [(ldk/invoke-static "java.lang.String" "valueOf" 42)
          (ldk/invoke-static "java.lang.String" "valueOf" true)
          (ldk/invoke-static "java.lang.String" "valueOf" \x)]))
  (is (= 124 (inc (ldk/invoke-static "java.lang.Integer" "parseInt" "123")))))

(deftest string-builder
  (is (= "ab1c2.5true"
         (-> (ldk/construct "java.lang.StringBuilder" "ab")
             (ldk/invoke "append" 1) (ldk/invoke "append" \c) (ldk/invoke "append" 2.5)
             (ldk/invoke "append" true) (ldk/invoke "toString")))))

(deftest remove-by-index-or-object
  (let [xs (ldk/construct "java.util.ArrayList")]
    (doseq [x ["a" "b" "c"]] (ldk/invoke xs "add" x))
    (is (= "a" (ldk/invoke xs "remove" 0)))
    (is (true? (ldk/invoke xs "remove" "b")))
    (is (= "[c]" (ldk/to-string xs)))))

(deftest map-values
  (let [m (ldk/construct "java.util.HashMap")]
    (ldk/invoke m "put" "k" 1)
    (is (= 2 (inc (ldk/invoke m "get" "k"))))))

(deftest jolt-fns-as-functional-interfaces
  (let [ws (ldk/construct "java.util.ArrayList")]
    (doseq [w ["pear" "fig" "banana" "kiwi"]] (ldk/invoke ws "add" w))
    ;; plain jolt fns where Java wants a Comparator, Predicate, Function
    (ldk/invoke ws "sort" (fn [a b] (compare (count a) (count b))))
    (is (= "[fig, pear, kiwi, banana]" (ldk/to-string ws)))
    (is (true? (ldk/invoke ws "removeIf" (fn [w] (= \k (first w))))))
    (is (= "[fig, pear, banana]" (ldk/to-string ws)))
    (is (= "[FIG, PEAR, BANANA]"
           (-> (ldk/invoke ws "stream")
               (ldk/invoke "map" (fn [w] (.toUpperCase w)))
               (ldk/invoke "collect" (ldk/invoke-static "java.util.stream.Collectors" "toList"))
               ldk/to-string)))))

(deftest strings-and-statics
  (is (= "HELLO" (ldk/invoke "hello" "toUpperCase")))
  (is (= \b (ldk/invoke "abc" "charAt" 1)))
  (is (= 3 (count (ldk/->clj (ldk/invoke "a,b,c" "split" ",")))))
  (is (= 2147483647 (ldk/get-static "java.lang.Integer" "MAX_VALUE"))))

(deftest surrogates
  ;; a surrogate pair comes back as one character; a lone surrogate, which a
  ;; jolt string can't hold, as U+FFFD
  (is (= [128512 65533]
         (map int (str (ldk/invoke-static "java.lang.String" "valueOf" (ldk/invoke-static "java.lang.Character" "toChars" 0x1F600))
                       (ldk/invoke-static "java.lang.String" "valueOf" (ldk/invoke-static "java.lang.Character" "toChars" 0xD800)))))))

(deftest exceptions
  (is (= "java.lang.NumberFormatException: For input string: \"x\""
         (try (ldk/invoke-static "java.lang.Integer" "parseInt" "x")
              (catch Exception e (ex-message e)))))
  ;; a native champaca lacks is an UnsatisfiedLinkError, caught like any Java
  ;; exception, and again on a second call: listing every process stops at
  ;; ProcessHandleImpl.getProcessPids0
  (dotimes [_ 2]
    (is (= ["java.lang.UnsatisfiedLinkError" true]
           (try (ldk/invoke-static "java.lang.ProcessHandle" "allProcesses")
                (catch Exception e [(subs (ex-message e) 0 30) (contains? (ex-data e) :java/exception)]))))))
