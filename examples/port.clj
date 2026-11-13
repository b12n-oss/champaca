;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; champaca.port writes a class's static methods out as jolt functions, and
;; champaca.portcheck runs each one beside the method it came from. Here on
;; test/jar's champaca.lib.PortSample: every method ports and agrees, one
;; port's text is as expected, a method with something no rule covers is
;; left with the reason, and what a port calls decides whether it stands.
;;
;;   jolt example port
(ns examples.port
  (:require [champaca.api :as ldk]
            [champaca.port :as port]
            [champaca.portcheck :as pc]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(ldk/init! {:classpath ["target/lib.jar"]})

(def sample "champaca.lib.PortSample")

(deftest every-method-ports-and-agrees
  ;; all but square and calls, which read static fields with no constant
  ;; value, countTrue, which takes a boolean[], and tabled, which calls a
  ;; method of another class that is left; after them, the methods of that
  ;; class they call
  (let [results (port/check-ports (port/port-class sample) 200)]
    (is (= 26 (count (remove :from results))))
    (is (= ["champaca/lib/PortSample.square(I)I" "champaca/lib/PortSample.countTrue([Z)I"
            "champaca/lib/PortSample.calls()I" "champaca/lib/PortSample.tabled(I)I"]
           (map :method (filter :left (remove :from results)))))
    (doseq [r (remove :left results)]
      (is (nil? (:left r)) (str (:method r) " was left: " (:left r)))
      (is (some? (:report r)) (str (:method r) " wasn't checked"))
      (when (:report r)
        (is (pc/agrees? (:report r)) (pc/report-line (:report r)))
        (is (= 200 (:runs (:report r))))))))

(deftest a-loop-is-loop-and-recur
  (is (= (str "(defn gcd\n"
              "  [l0 l1]\n"
              "  (loop [l0 l0 l1 l1 l2 0] (if (= l1 0) l0 (let [l2 (rem l0 l1)] (recur l1 l2 l2)))))")
         (:source (port/port-method sample "gcd" "(II)I")))))

(deftest a-swap-keeps-both-values
  ;; start and end change places at once; a let binds one after the other,
  ;; so the port takes both values before it rebinds either name
  (let [r (port/port-method sample "span" "(II)I")]
    (is (str/includes? (:source r) "_in"))
    (port/check-ports [r] 1)
    (let [span @(ns-resolve 'champaca.ported 'span)]
      (is (= [5 5 0] [(span 2 7) (span 7 2) (span 3 3)])))))

(deftest a-name-clojure-has-gets-a-star
  (is (= 'reverse* (:name (port/port-method sample "reverse" "([I)V")))))

(deftest a-port-names-what-it-relies-on
  (testing "a string read by index holds for characters up to U+FFFF"
    (is (= #{:strings-within-the-bmp} (set (:holds (port/port-method sample "hash31" "(Ljava/lang/String;)I"))))))
  (testing "one that reads no string by index holds for any"
    (is (empty? (:holds (port/port-method sample "gcd" "(II)I"))))))

(deftest a-port-tests-its-condition-when-it-runs
  ;; hash31 reads its string by index, so its port holds for characters up to
  ;; U+FFFF and throws on any other string, where it would otherwise answer
  ;; differently from Java without saying so
  (let [r (port/port-method sample "hash31" "(Ljava/lang/String;)I")]
    (is (str/includes? (:source r) "(within-bmp! l0)"))
    (port/check-ports [r] 1)
    (let [hash31 @(ns-resolve 'champaca.ported 'hash31)]
      (is (= (ldk/invoke-static sample "hash31" "héllo wörld") (hash31 "héllo wörld")))
      (is (= :strings-within-the-bmp
             (try (hash31 "a😀") (catch clojure.lang.ExceptionInfo e (:champaca.port/outside (ex-data e))))))
      (is (thrown? NullPointerException (hash31 nil)) "null goes on to the method's own behavior")))
  (testing "a port that reads no string by index has no test"
    (is (not (str/includes? (:source (port/port-method sample "joinTrim" "(Ljava/util/List;Ljava/lang/String;)Ljava/lang/String;"))
                            "within-bmp!"))))
  (testing "the check, run with the condition broken, reports it"
    (let [r (port/port-method sample "hash31" "(Ljava/lang/String;)I")
          f @(ns-resolve 'champaca.ported 'hash31)
          report (pc/check {:name "hash31, any string" :class sample :method "hash31"
                            :args [{:type :string :chars :any}] :f f})]
      (is (not (pc/agrees? report))))))

(deftest ints-are-small-only-where-a-loop-or-a-size-could-take-them
  (testing "a loop's count may be the parameter"
    (is (:small-ints (port/port-method sample "fib" "(I)I")))
    (is (= [{:type :int :range [-64 64]}] (:args (port/port-method sample "fib" "(I)I")))))
  (testing "a method that calls a looping one"
    (is (:small-ints (port/port-method sample "gcdOfSpans" "(III)I"))))
  (testing "straight-line arithmetic is checked on the full range"
    (is (not (:small-ints (port/port-method sample "span" "(II)I"))))
    (is (= [:int :int] (:args (port/port-method sample "span" "(II)I"))))))

(deftest calls-within-the-class
  (let [r (port/port-method sample "gcdOfSpans" "(III)I")]
    (is (= #{"champaca/lib/PortSample.gcd(II)I" "champaca/lib/PortSample.span(II)I"} (set (:calls r))))
    (is (str/includes? (:source r) "(gcd "))
    (is (str/includes? (:source r) "(span "))))

(deftest overloads-are-named-by-their-parameters
  (is (= 'within-int-int (:name (port/port-method sample "within" "(II)Z"))))
  (is (= 'within-int-int-int (:name (port/port-method sample "within" "(III)Z"))))
  (is (str/includes? (:source (port/port-method sample "within" "(II)Z")) "(within-int-int-int ")))

(deftest the-whitespace-rule-is-the-jdks-on-every-char
  ;; jolt's own Character/isWhitespace differs from the JDK's on five chars,
  ;; so the rule's form settles those itself; this is that form
  (let [rule (fn [ch] (and (not= ch 0x85) (or (<= 0x1c ch 0x1f) (Character/isWhitespace (char ch)))))
        differ (for [c (range 65536)
                     :when (not (<= 0xD800 c 0xDFFF))
                     :when (not= (boolean (rule c))
                                 (boolean (ldk/call-static "java/lang/Character" "isWhitespace" "(C)Z" (char c))))]
                 c)]
    (is (empty? differ))))

(deftest a-static-field-with-no-constant-value-is-left
  ;; as a reason, for that method alone: the field's name must not reach the
  ;; function as a free symbol, which would stop every port of the class
  ;; from loading. SQUARES is an array with elements, which code can change
  (let [r (port/port-method sample "square" "(I)I")]
    (is (str/includes? (:left r) "static field"))
    (is (str/includes? (:left r) "SQUARES"))
    (is (str/includes? (:left r) "empty array")))
  (is (str/includes? (:left (port/port-method sample "calls" "()I")) "isn't final")))

(deftest a-static-final-constant-is-a-def
  ;; an empty array, a string, a number or null can't change, so the field
  ;; is a def before the functions, named in capitals after its class
  (let [results (port/check-ports (port/port-class sample) 100)
        words (first (filter #(= "champaca/lib/PortSample.words(Ljava/lang/String;)[Ljava/lang/String;" (:method %)) results))
        f @(ns-resolve 'champaca.ported 'words)]
    (is (str/includes? (:source words) "port-sample-NO-WORDS"))
    (is (some #{"(def port-sample-NO-WORDS (make-array String 0))"} (port/definitions results)))
    (is (pc/agrees? (:report words)))
    (is (identical? (f "") (f "")) "the same array each time, as the field is")
    (is (= ["x"] (vec (f "x"))))))

(deftest an-initializer-that-calls-is-a-condition
  ;; PortSample's initializer only fills arrays; PortOther's calls
  ;; String.valueOf, so its methods, and what calls them, hold only if that
  ;; does nothing a caller can see
  (let [by-method (into {} (map (juxt :method identity) (port/port-class sample)))]
    (is (empty? (:holds (by-method "champaca/lib/PortSample.gcd(II)I"))))
    (is (= #{:initializers-without-effects}
           (set (:holds (by-method "champaca/lib/PortOther.clamp(III)I")))))
    (is (contains? (set (:holds (by-method "champaca/lib/PortSample.vowels(Ljava/lang/String;)I")))
                   :initializers-without-effects))))

(deftest a-null-array-throws-as-in-java
  ;; jolt's aget answers nil for a nil array; the port keeps Java's check
  (let [r (port/port-method sample "sumEven" "([I)I")]
    (is (str/includes? (:source r) "(non-null "))
    (is (= [{:type :ints :nil? true}] (:args r)) "the check passes null for the array now and then")
    (port/check-ports [r] 1)
    (is (thrown? NullPointerException (@(ns-resolve 'champaca.ported 'sum-even) nil)))))

(deftest the-case-rules-differ-from-the-jdk-only-on-newer-letters
  ;; String.toUpperCase and toLowerCase on each char as a one-char string,
  ;; ß to SS and the like included: jolt and the JDK differ only on the few
  ;; chars from a Unicode newer than the JDK's tables, which is what the
  ;; rules' condition, letters-jolt-knows, says
  (let [differ (fn [jolt-fn java-name]
                 (for [c (range 65536)
                       :when (not (<= 0xD800 c 0xDFFF))
                       :let [s (str (char c))]
                       :when (not= (jolt-fn s) (ldk/invoke s java-name))]
                   c))
        newer? (fn [c] (or (<= 0xA7CE c 0xA7D5) (contains? #{0x88F 0xC5C 0xCDC 0xA7F1 0x295} c)))]
    (is (every? newer? (differ #(.toUpperCase ^String %) "toUpperCase")))
    (is (every? newer? (differ #(.toLowerCase ^String %) "toLowerCase")))
    (is (= (ldk/invoke "ΟΔΟΣ" "toLowerCase") (.toLowerCase "ΟΔΟΣ")) "a final sigma")))

(deftest a-char-array-is-read-and-written-as-chars
  ;; an element of a char[] is a char in jolt and its code in the ported
  ;; code, so a read converts one way and a write the other
  (let [count-in (port/port-method sample "countIn" "([CC)I")
        mirror (port/port-method sample "mirror" "(Ljava/lang/String;)Ljava/lang/String;")
        fill (port/port-method sample "fillFrom" "([CIC)[C")]
    (is (str/includes? (:source count-in) "(int (aget (non-null "))
    (is (str/includes? (:source mirror) "(char-array "))
    (is (re-find #"\(aset \(non-null [^)]+\) [^\n]*\(char " (:source mirror)))
    (is (= [:strings-within-the-bmp] (vec (:holds mirror))) "toCharArray: one element a char only within the BMP")
    (doseq [r (port/check-ports [count-in mirror fill] 300)]
      (is (pc/agrees? (:report r)) (str (:method r))))
    (is (= "abcdcb" (@(ns-resolve 'champaca.ported 'mirror) "abc")))
    (is (thrown? NullPointerException (@(ns-resolve 'champaca.ported 'mirror) nil)))
    (is (thrown? NullPointerException (@(ns-resolve 'champaca.ported 'count-in) nil \a)))))

(deftest a-string-of-part-of-a-char-array-checks-its-range
  ;; jolt's (String. a off n) takes a range outside the array and a nil
  ;; array; the port's string-of throws as Java does
  (let [r (first (port/check-ports [(port/port-method sample "slice" "([CII)Ljava/lang/String;")] 300))
        slice @(ns-resolve 'champaca.ported 'slice)]
    (is (str/includes? (:source r) "(string-of "))
    (is (pc/agrees? (:report r)))
    (is (= "bc" (slice (char-array "abcd") 1 2)))
    (is (thrown? StringIndexOutOfBoundsException (slice (char-array "abcd") -1 0)))
    (is (thrown? StringIndexOutOfBoundsException (slice (char-array "abcd") 3 2)))
    (is (thrown? NullPointerException (slice nil 0 0)))))

(deftest a-string-array-is-made-and-stored-into
  (let [r (first (port/check-ports [(port/port-method sample "ends" "(Ljava/lang/String;)[Ljava/lang/String;")] 300))]
    (is (str/includes? (:source r) "(make-array String 2)"))
    (is (pc/agrees? (:report r)))
    (is (= ["a" "c"] (vec (@(ns-resolve 'champaca.ported 'ends) "abc"))))))

(deftest a-boolean-array-in-the-signature-is-left
  ;; a boolean[] is read by the instruction that reads a byte[], and its
  ;; elements are true and false in jolt: nothing tells the two apart yet
  (let [r (port/port-method sample "countTrue" "([Z)I")]
    (is (str/includes? (:left r) "boolean[]"))))

(deftest is-title-case-is-the-jdks-on-every-char
  (is (empty? (for [c (range 65536)
                    :when (not (<= 0xD800 c 0xDFFF))
                    :when (not= (boolean (Character/isTitleCase (char c)))
                                (boolean (ldk/call-static "java/lang/Character" "isTitleCase" "(C)Z" (char c))))]
                c))))

(deftest a-call-into-another-class-brings-its-method-along
  ;; the methods of PortOther that PortSample calls are ported after its
  ;; own, named with their class, and what PortOther's own methods call too
  (let [results (port/check-ports (port/port-class sample) 100)
        by-method (into {} (map (juxt :method identity) results))
        other (filter :from results)
        vowels (by-method "champaca/lib/PortSample.vowels(Ljava/lang/String;)I")]
    (is (= #{"champaca/lib/PortOther"} (set (map :from other))))
    (is (= #{"champaca/lib/PortOther.vowel(C)Z" "champaca/lib/PortOther.clamp(II)I"
             "champaca/lib/PortOther.clamp(III)I" "champaca/lib/PortOther.tableAt(I)I"}
           (set (map :method other))))
    (is (= 'port-other-vowel (:name (by-method "champaca/lib/PortOther.vowel(C)Z"))))
    (is (= 'port-other-clamp-int-int (:name (by-method "champaca/lib/PortOther.clamp(II)I"))))
    (is (str/includes? (:source vowels) "(port-other-vowel "))
    (is (contains? (set (:holds vowels)) :initializers-without-effects)
        "PortOther's static initializer calls a method, and the function doesn't run it")
    (doseq [r (remove :left other)]
      (is (pc/agrees? (:report r)) (str (:method r))))
    (is (= 3 (@(ns-resolve 'champaca.ported 'vowels) "a quiet one")) "six vowels, clamped to 3")
    (is (= 1 (@(ns-resolve 'champaca.ported 'vowels) "xyz a")))
    (is (str/includes? (:left (by-method "champaca/lib/PortOther.tableAt(I)I")) "static field"))
    (is (str/includes? (:left (by-method "champaca/lib/PortSample.tabled(I)I")) "PortOther.tableAt"))))

(deftest what-has-no-rule-is-left-with-the-reason
  (let [r (port/port-method "java.lang.Integer" "toHexString" "(I)Ljava/lang/String;")]
    (is (string? (:left r)))
    (is (nil? (:source r)))))
