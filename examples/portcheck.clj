;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt functions written by hand to stand for the static methods of
;; champaca.lib.PortSample (test/jar), each checked against the method as
;; champaca runs it (champaca.portcheck). Then the same check on versions
;; with a bug put in, which it has to report: a check that passes everything
;; shows nothing.
;;
;;   jolt example portcheck
(ns examples.portcheck
  (:require [champaca.api :as ldk]
            [champaca.portcheck :as pc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(ldk/init! {:classpath ["target/lib.jar"]})

(def sample "champaca.lib.PortSample")

;; --- the translations -------------------------------------------------------------
;; Java's int arithmetic wraps, so sums and products use the unchecked int
;; operations. Strings are read by index, which is by UTF-16 code unit in
;; Java and by character in jolt: isPalindrome, digitSum and hash31 hold for
;; strings with no character above U+FFFF, and their checks generate only
;; those. countChar needs no such condition, which the check found: the
;; char it looks for is never half of a pair, so both counts come out the
;; same, and it runs on any string.

(defn sum-even [^ints xs]
  (areduce xs i s (int 0)
           (let [x (aget xs i)] (if (zero? (rem x 2)) (unchecked-add-int s x) s))))

(defn gcd [a b]
  (loop [a a b b] (if (zero? b) a (recur b (rem a b)))))

(defn fib [n]
  (loop [i 0 a 0 b 1] (if (< i n) (recur (inc i) b (unchecked-add-int a b)) a)))

(defn clamp [v lo hi]
  (when (> lo hi) (throw (IllegalArgumentException. "lo > hi")))
  (cond (< v lo) lo (> v hi) hi :else v))

(defn reverse! [^ints xs]
  (loop [i 0 j (dec (alength xs))]
    (when (< i j)
      (let [t (aget xs i)]
        (aset xs i (aget xs j))
        (aset xs j t)
        (recur (inc i) (dec j))))))

(defn count-char [^String s c]
  (loop [i 0 n 0]
    (if (< i (.length s)) (recur (inc i) (if (= (.charAt s i) c) (inc n) n)) n)))

(defn palindrome? [^String s]
  (loop [i 0 j (dec (.length s))]
    (cond (>= i j) true
          (not= (.charAt s i) (.charAt s j)) false
          :else (recur (inc i) (dec j)))))

(defn digit-sum [^String s]
  (loop [i 0 n 0]
    (if (< i (.length s))
      (let [c (int (.charAt s i))]
        (when (or (< c 48) (> c 57)) (throw (NumberFormatException. (str "not a digit: " (char c)))))
        (recur (inc i) (+ n (- c 48))))
      n)))

(defn div-all [^ints xs d]
  (let [out (int-array (alength xs))]
    (dotimes [i (alength xs)]
      ;; Integer.MIN_VALUE / -1 is Integer.MIN_VALUE in Java
      (aset out i (unchecked-int (quot (aget xs i) d))))
    out))

(defn hash31 [^String s]
  (loop [i 0 h 7]
    (if (< i (.length s))
      (recur (inc i) (unchecked-add-int (unchecked-multiply-int 31 h) (int (.charAt s i))))
      h)))

(defn join-trim [^java.util.List parts ^String sep]
  (let [sb (StringBuilder.)]
    (dotimes [i (.size parts)]
      (when (pos? i) (.append sb sep))
      (.append sb (.trim ^String (.get parts i))))
    (.toString sb)))

(def bmp-string {:type :string :chars :bmp :nil? true})

(def translations
  [{:method "sumEven" :args [:ints] :f sum-even}
   {:method "gcd" :args [:int :int] :f gcd}
   {:method "fib" :args [{:type :int :range [0 60]}] :f fib}
   {:method "clamp" :args [:long :long :long] :f clamp}
   {:method "reverse" :args [:ints] :f reverse!}
   {:method "countChar" :args [{:type :string :chars :any :nil? true} :char] :f count-char}
   {:method "isPalindrome" :args [bmp-string] :f palindrome?}
   {:method "digitSum" :args [{:type :string :chars :ascii :nil? true}] :f digit-sum}
   {:method "divAll" :args [:ints :int] :f div-all}
   {:method "hash31" :args [bmp-string] :f hash31}
   {:method "joinTrim" :args [{:type :strings :of {:nil? true}} {:type :string :nil? true}] :f join-trim}])

(defn- run [t] (pc/check (assoc t :class sample :name (or (:name t) (:method t)))))

(deftest translations-agree
  (doseq [t translations]
    (let [r (run t)]
      (is (pc/agrees? r) (pc/report-line r))
      (is (= 300 (:runs r))))))

;; --- the same check on wrong versions ---------------------------------------------
;; One mistake each, the kind a translator or a person would make.

(def seeded-bugs
  [{:name "sumEven, + for unchecked-add-int" :method "sumEven" :args [:ints]
    :f (fn [^ints xs] (areduce xs i s 0 (let [x (aget xs i)] (if (zero? (rem x 2)) (+ s x) s))))}
   {:name "gcd, mod for rem" :method "gcd" :args [:int :int]
    :f (fn [a b] (loop [a a b b] (if (zero? b) a (recur b (mod a b)))))}
   {:name "fib, + for unchecked-add-int" :method "fib" :args [{:type :int :range [0 60]}]
    :f (fn [n] (loop [i 0 a 0 b 1] (if (< i n) (recur (inc i) b (+ a b)) a)))}
   {:name "clamp, the lo > hi check left out" :method "clamp" :args [:long :long :long]
    :f (fn [v lo hi] (cond (< v lo) lo (> v hi) hi :else v))}
   {:name "reverse, stops one swap early" :method "reverse" :args [:ints]
    :f (fn [^ints xs]
         (loop [i 0 j (dec (alength xs))]
           (when (< (inc i) j)
             (let [t (aget xs i)] (aset xs i (aget xs j)) (aset xs j t) (recur (inc i) (dec j))))))}
   {:name "hash31, given characters above U+FFFF" :method "hash31"
    :args [{:type :string :chars :any}] :f hash31}
   {:name "isPalindrome, compares to the middle only on even lengths" :method "isPalindrome" :args [bmp-string]
    :f (fn [^String s]
         (if (odd? (.length s)) true (palindrome? s)))}
   {:name "digitSum, another exception class" :method "digitSum" :args [{:type :string :chars :ascii :nil? true}]
    :f (fn [^String s]
         (try (digit-sum s) (catch NumberFormatException e (throw (IllegalArgumentException. (ex-message e))))))}
   {:name "divAll, no unchecked-int" :method "divAll" :args [:ints :int]
    :f (fn [^ints xs d] (mapv #(quot % d) xs))}
   {:name "hash31, plain arithmetic" :method "hash31" :args [bmp-string]
    :f (fn [^String s] (reduce (fn [h c] (+ (* 31 h) (int c))) 7 s))}
   {:name "joinTrim, str/join and str/trim" :method "joinTrim" :args [:strings {:type :string :nil? true}]
    :f (fn [parts sep] (str/join sep (map str/trim parts)))}])

(deftest seeded-bugs-are-reported
  (doseq [b seeded-bugs]
    (let [r (run b)]
      (is (not (pc/agrees? r)) (str (:name b) " went unnoticed over " (:runs r) " inputs")))))

(deftest reports-read-well
  (testing "an agreeing check names its inputs and seed"
    (is (= "sumEven: agrees on 300 inputs (seed 84)" (pc/report-line (run (first translations))))))
  (testing "a disagreement names the input and both sides"
    (let [line (pc/report-line (run (first seeded-bugs)))]
      (is (str/includes? line "DIFFERS at input "))
      (is (str/includes? line " java {:result "))
      (is (str/includes? line " jolt {:result ")))))
