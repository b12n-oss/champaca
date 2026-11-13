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
  (let [results (port/check-ports (port/port-class sample) 200)]
    (is (= 15 (count results)))
    (doseq [r results]
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

(deftest what-has-no-rule-is-left-with-the-reason
  (let [r (port/port-method "java.lang.Integer" "toHexString" "(I)Ljava/lang/String;")]
    (is (string? (:left r)))
    (is (nil? (:source r)))))
