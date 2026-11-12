;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.cgtext
  "Text of generated Scheme: forms and literals, descriptors, and the
  labels and pc markers each method's code carries."
  (:require [clojure.string :as str]))

;; --- text helpers ------------------------------------------------------------

(defn sx [& parts] (str "(" (str/join " " (remove nil? parts)) ")"))

(defn- hex-escaped-sym
  "s as a bare Scheme symbol with every character but an ASCII letter written
  as \\xN; (Chez's own way to print such a symbol, read back as written)."
  [s]
  (apply str (map (fn [c] (if (or (<= (int \a) (int c) (int \z)) (<= (int \A) (int c) (int \Z)))
                            c
                            (str "\\x" (Integer/toHexString (int c)) ";")))
                  s)))

(defn sym
  "A Scheme symbol literal for a JVM-ish name, bar-quoted so / ( ) ; < > [ $
  all survive. Chez reads no escape between bars, so a name holding | or \\
  (legal in the JVM; Scala and Kotlin emit them) is written bare with hex
  escapes instead."
  [s]
  (if (or (str/includes? s "|") (str/includes? s "\\"))
    (hex-escaped-sym s)
    (str "|" s "|")))

(defn str-lit
  "A Scheme string literal, or for a constant holding a lone surrogate (a
  vector of UTF-16 code units, see classfile/decode-mutf8) a vector literal.
  Quote it where it's evaluated; jstring-lit-form does."
  [s]
  (if (vector? s)
    (str "#" (apply sx (map str s)))
    (str "\""
         (apply str (map (fn [c]
                           (let [i (int c)]
                             (cond (= c \\) "\\\\"
                                   (= c \") "\\\""
                                   ;; Chez reads any line ending inside a literal as \n, NEL
                                   ;; and LS included (R6RS 4.2.7); escaped, they stay themselves
                                   (or (< i 32) (= i 0x85) (= i 0x2028)) (str "\\x" (Integer/toHexString i) ";")
                                   :else (str c))))
                         s))
         "\"")))

(defn jstring-lit-form
  "The expression for a String constant: the interned java.lang.String."
  [s]
  (sx "jstring-lit" (if (vector? s) (str "'" (str-lit s)) (str-lit s))))

(defn num-lit [x]
  (cond
    (integer? x) (str x)
    (Double/isNaN x) "+nan.0"
    (Double/isInfinite x) (if (pos? x) "+inf.0" "-inf.0")
    :else (str x)))

(defn method-global [cls mname desc] (sym (str cls "." mname desc)))
(defn static-global [cls fname] (sym (str cls ":" fname)))

;; --- descriptors -------------------------------------------------------------

(defn- read-type [s i]
  (case (nth s i)
    \L (let [j (str/index-of s ";" i)] [(subs s i (inc j)) (inc j)])
    \[ (let [[t j] (read-type s (inc i))] [(str "[" t) j])
    [(str (nth s i)) (inc i)]))

(defn parse-desc
  "\"(IJLjava/lang/String;)V\" -> {:args [\"I\" \"J\" \"Ljava/lang/String;\"] :ret \"V\"}"
  [desc]
  (loop [i 1 args []]
    (if (= \) (nth desc i))
      {:args args :ret (subs desc (inc i))}
      (let [[t j] (read-type desc i)] (recur j (conj args t))))))

(defn wide? [t] (contains? #{"J" "D"} t))

(defn default-value [t]
  (case t ("F" "D") "0.0" ("I" "J" "S" "B" "C" "Z") "0" "#f"))


(def ^:dynamic *method-tag*
  "Class.name(desc) of the method being translated. Block labels carry it
  so a Chez stack frame names its Java method (see getCallerClass)."
  "")

(defn label [pc] (sym (str *method-tag* "#B" pc)))

;; what a null check passes to npe-at so the NPE's message can be built from
;; its bytecode: npe-tag, the method's Class.name(desc), which each compiled
;; method (an inlined callee too) binds once in its lambda, and the bci
;; Every call that can end up pending in a frame, and every helper that can
;; throw, carries its bytecode pc as a constant (begin '(jpc . pc) x): Chez
;; drops it, so the code costs the same, but the frame's recorded source
;; keeps it, and a stack trace reads the pc, hence the line, off the frame
;; (reflect.ss java-frames-of). at wraps a call's operator; site is the
;; "tag pc" argument pair of the null-check, array, class-init and cast
;; helpers, its tag reference wrapped the same way.
(def ^:dynamic *inline-site*
  "[pc tag] of the call site a leaf callee is being compiled for. Markers
  in its body then name the site and the callee, (jpc site-pc callee . pc),
  so the walk shows the callee as a frame of its own above the caller's
  line, as HotSpot does for an inlined method."
  nil)
(defn- jpc [pc]
  (if-let [[spc] *inline-site*]
    (str "'(jpc " spc " " (sym *method-tag*) " . " pc ")")
    (str "'(jpc . " pc ")")))
(defn at [pc op] (sx "begin" (jpc pc) op))
(defn site [pc] (str (sx "begin" (jpc pc) "npe-tag") " " pc))
