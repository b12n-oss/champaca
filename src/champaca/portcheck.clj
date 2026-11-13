;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.portcheck
  "Checks a jolt function written to stand for a Java static method against
  the method itself, run by champaca. Both get the same generated inputs and
  must agree on what a caller can observe: the result, the class of any
  exception, and what the call did to its array and list arguments.

  Agreement is on the inputs tried. It shows nothing about the ones that
  weren't, so a report names how many ran and from which seed."
  (:require [champaca.api :as ldk]
            [clojure.string :as str]))

;; --- a generator that gives the same inputs everywhere --------------------------
;; java.util.Random's recurrence on 48 bits, kept in a long.

(defn- step [seed]
  (bit-and (unchecked-add (unchecked-multiply seed 0x5DEECE66D) 0xB) 0xFFFFFFFFFFFF))

(defn- rng [seed] (volatile! (step (bit-xor (long seed) 0x5DEECE66D))))

(defn- below
  "The next value in [0, n)."
  [r n]
  (let [s (vswap! r step)]
    (mod (unsigned-bit-shift-right s 17) n)))

(defn- pick [r xs] (nth xs (below r (count xs))))

(def ^:private int-edges
  [0 1 -1 2 -2 3 7 31 255 256 65535 65536 2147483647 2147483646 -2147483648 -2147483647 1073741824 -1073741824])

(def ^:private long-edges
  (into int-edges [9223372036854775807 -9223372036854775808 4294967296 -4294967296]))

(def ^:private char-pools
  ;; each pool holds the ones before it; :any adds a character above U+FFFF,
  ;; which is two chars to Java and one to jolt
  (let [ascii (vec "abAZ09 _-.,\t\n\u0001\u001f")
        latin1 (into ascii "éÿ ")
        bmp (into latin1 " 中Ж﻿")]
    {:ascii ascii :latin1 latin1 :bmp bmp
     :any (conj (mapv str bmp) "😀")}))

;; The values int code goes wrong on most, drawn a third of the time, so two
;; of them meet in one input often enough: Integer.MIN_VALUE / -1 needs both.
(def ^:private sharp-ints [0 1 -1 2147483647 -2147483648])

(defn- gen-int [r {:keys [range]}]
  (if range
    (+ (first range) (below r (inc (- (second range) (first range)))))
    (case (below r 3)
      0 (- (below r 4294967296) 2147483648)
      1 (pick r int-edges)
      (pick r sharp-ints))))

(defn- gen-string [r {:keys [chars nil?] :or {chars :bmp}}]
  (if (and nil? (zero? (below r 12)))
    nil
    (apply str (repeatedly (below r 9) #(pick r (char-pools chars))))))

(defn- gen
  "A value for one argument. spec is a type keyword or a map with :type and
  that type's options: :range [lo hi] for an :int, :chars (:ascii, :latin1,
  :bmp, :any) and :nil? for a :string, the same under :of for :strings."
  [r spec]
  (let [{:keys [type] :as spec} (if (keyword? spec) {:type spec} spec)]
    (case type
      :int (gen-int r spec)
      :long (if (or (:range spec) (zero? (below r 3))) (gen-int r spec) (pick r long-edges))
      :boolean (zero? (below r 2))
      :char (first (str (pick r (char-pools (:chars spec :bmp)))))
      :string (gen-string r spec)
      :ints (vec (repeatedly (below r 7) #(gen-int r spec)))
      :strings (vec (repeatedly (below r 5) #(gen-string r (:of spec {})))))))

(defn- spec-type [spec] (if (keyword? spec) spec (:type spec)))

;; --- the two sides ----------------------------------------------------------------

(defn- java-arg
  "v as the Java method takes it. An array is made here, so the same object
  can be read back after the call."
  [spec v]
  (case (spec-type spec)
    ;; copyOf of an empty vector could be any array type; a one-int vector
    ;; cut to length 0 is an int[]
    :ints (ldk/invoke-static "java.util.Arrays" "copyOf" (if (seq v) v [0]) (count v))
    :strings (ldk/->java v)
    v))

(defn- jolt-arg [spec v]
  (case (spec-type spec)
    :ints (int-array v)
    :strings (java.util.ArrayList. v)
    v))

(defn- plain
  "A result or an argument after the call, as data both sides can be compared
  by: arrays and lists as vectors."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (char? x)) x
    :else (vec x)))

(defn- mutable? [spec] (contains? #{:ints :strings} (spec-type spec)))

(defn- observe
  "What a caller sees of (apply f args): {:result} or {:throws class-name},
  and :args, the mutable arguments afterwards. after turns a result or an
  argument into plain data."
  [f specs args after throw-class]
  (let [outcome (try {:result (after (apply f args))}
                     (catch Throwable e {:throws (throw-class e)}))]
    (assoc outcome :args (vec (keep (fn [[spec a]] (when (mutable? spec) (after a))) (map vector specs args))))))

(defn- java-throw-class [e]
  (if-let [x (:java/exception (ex-data e))]
    (ldk/invoke (ldk/invoke x "getClass") "getName")
    (str "not a Java exception: " (ex-message e))))

(defn- jolt-throw-class [e] (.getName (class e)))

(defn- same? [a b]
  (and (= (:throws a) (:throws b))
       (= (:args a) (:args b))
       (or (:throws a) (= (:result a) (:result b)))))

(defn check
  "Run the Java method and the jolt fn on :runs generated inputs (300 unless
  given) from :seed. Returns {:name :runs :seed :mismatches}, each mismatch
  {:at :input :java :jolt}, :at counting inputs from 1; at most three are
  kept, and the check stops there.

    (check {:name \"sumEven\" :class \"champaca.lib.PortSample\" :method \"sumEven\"
            :args [:ints] :f sum-even})

  :desc, the method's descriptor (\"([I)I\"), picks one overload exactly and
  reaches a private method; without it the method is found by name and
  arguments, which fails when two overloads both take a null."
  [{:keys [name class method desc args f runs seed] :or {runs 300 seed 84}}]
  (let [r (rng seed)
        ;; by descriptor when one is given: the one overload, private or not,
        ;; where a call by name can't choose between two that take null
        call (if desc
               (let [cname (str/replace class "." "/")] #(apply ldk/call-static cname method desc %&))
               #(apply ldk/invoke-static class method %&))]
    (loop [i 0 bad []]
      (if (or (= i runs) (= 3 (count bad)))
        {:name name :runs i :seed seed :mismatches bad}
        (let [input (mapv #(gen r %) args)
              java (observe call args
                            (map java-arg args input) #(plain (ldk/->clj %)) java-throw-class)
              jolt (observe f args (map jolt-arg args input) plain jolt-throw-class)]
          (recur (inc i) (if (same? java jolt) bad (conj bad {:at (inc i) :input input :java java :jolt jolt}))))))))

(defn agrees? [report] (empty? (:mismatches report)))

(defn report-line
  "One line a person can read: the name, how many inputs ran, and the first
  disagreement if there was one."
  [{:keys [name runs seed mismatches]}]
  (if (empty? mismatches)
    (str name ": agrees on " runs " inputs (seed " seed ")")
    (let [{:keys [at input java jolt]} (first mismatches)]
      (str name ": DIFFERS at input " at " (seed " seed "): " (pr-str input)
           " java " (pr-str java) " jolt " (pr-str jolt)))))
