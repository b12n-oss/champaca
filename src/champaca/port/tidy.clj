;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.port.tidy
  "The Clojure a port writes, made readable: a binding of a name to itself
  goes, nested lets become one, (let [x E] x) is E, and a loop keeps only
  the locals some recur changes.")

(defn do-form
  "forms as one form: the form itself when there is one, else a do."
  [forms]
  (if (= 1 (count forms)) (first forms) (apply list 'do forms)))

(defn mentions?
  "Whether the Clojure form uses the symbol."
  [sym form]
  (cond (symbol? form) (= sym form)
        (coll? form) (boolean (some #(mentions? sym %) form))
        :else false))

(defn- recurs
  "The argument lists of the recur forms that belong to the loop whose body
  form is: not those of a loop or fn inside it."
  [form]
  (cond (not (seq? form)) (if (vector? form) (mapcat recurs form) [])
        (= 'recur (first form)) [(vec (rest form))]
        (contains? #{'loop 'fn 'letfn} (first form)) []
        :else (mapcat recurs form)))

(defn- drop-recur-args
  "form with the recur arguments at the positions in gone removed, in the
  same loop only."
  [gone form]
  (let [keep (fn [xs] (keep-indexed (fn [i x] (when-not (gone i) x)) xs))]
    (cond (vector? form) (mapv #(drop-recur-args gone %) form)
          (not (seq? form)) form
          (= 'recur (first form)) (apply list 'recur (keep (rest form)))
          (contains? #{'loop 'fn 'letfn} (first form)) form
          :else (apply list (map #(drop-recur-args gone %) form)))))

(defn- binds?
  "Whether form binds the name p anywhere: in a let or loop vector, or as a
  fn's parameter."
  [p form]
  (cond (vector? form) (boolean (some #(binds? p %) form))
        (not (seq? form)) false
        (contains? #{'let 'loop} (first form))
        (or (boolean (some #(= p %) (take-nth 2 (second form)))) (boolean (some #(binds? p %) (rest form))))
        :else (boolean (some #(binds? p %) form))))

(defn- tidy-loop
  "A loop without the locals that never change: one whose initial value is
  the outer name itself, that every recur passes on under that name, and
  that nothing in the body binds again (a recur naming an inner binding of
  the same name passes a new value)."
  [[_ bindings & forms]]
  (let [pairs (vec (partition 2 bindings))
        args (mapcat recurs forms)
        gone (set (keep-indexed (fn [i [p init]]
                                  (when (and (= p init)
                                             (every? #(= p (nth % i)) args)
                                             (not (some #(binds? p %) forms)))
                                    i))
                                pairs))
        kept (vec (mapcat identity (keep-indexed (fn [i pair] (when-not (gone i) pair)) pairs)))]
    (apply list 'loop kept (map #(drop-recur-args gone %) forms))))

(defn- unused-pairs-dropped
  "pairs without those whose name nothing after them uses and whose value is
  a name or a constant, so costs nothing to leave out: a local the method
  sets and this path never reads."
  [pairs forms]
  (loop [kept [] pairs (vec pairs)]
    (if-let [[n init :as pair] (first pairs)]
      (let [later (concat (map second (rest pairs)) forms)
            plain? (or (symbol? init) (number? init) (string? init) (nil? init))]
        (recur (if (and plain? (not-any? #(mentions? n %) later)) kept (conj kept pair)) (subvec pairs 1)))
      kept)))

(defn- tidy-let [[_ bindings & forms]]
  (let [pairs (remove (fn [[n init]] (= n init)) (partition 2 bindings))
        inner (first forms)
        merged? (and (= 1 (count forms)) (seq? inner) (= 'let (first inner)))
        pairs (if merged? (concat pairs (partition 2 (second inner))) pairs)
        forms (if merged? (drop 2 inner) forms)
        pairs (unused-pairs-dropped pairs forms)]
    (cond (empty? pairs) (do-form forms)
          (and (= 1 (count forms)) (= (first forms) (first (last pairs))) (= 1 (count pairs))) (second (first pairs))
          :else (apply list 'let (vec (mapcat identity pairs)) forms))))

(defn- as-statement
  "A form whose value nothing uses. (let [... x E] nil), a call with an
  unused result after its arguments' lets, is (let [...] E)."
  [form]
  (if (and (seq? form) (= 'let (first form)) (= 3 (count form)) (nil? (nth form 2)))
    (let [pairs (partition 2 (second form))]
      (tidy-let (list 'let (vec (mapcat identity (butlast pairs))) (second (last pairs)))))
    form))

(defn- tidy-do
  "A do with the dos inside it spliced in and its statements simplified."
  [[_ & forms]]
  (let [flat (mapcat #(if (and (seq? %) (= 'do (first %))) (rest %) [%]) forms)]
    (do-form (concat (map as-statement (butlast flat)) [(last flat)]))))

(defn tidy
  "form with its lets, loops and dos tidied, innermost first."
  [form]
  (cond (vector? form) (mapv tidy form)
        (not (seq? form)) form
        :else (let [form (apply list (map tidy form))]
                (case (first form)
                  let (tidy-let form)
                  loop (tidy-loop form)
                  do (tidy-do form)
                  form))))
