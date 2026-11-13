;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.linkage
  "Resolving what an instruction names while its method is translated:
  missing classes and members become code that throws where the JVM would."
  (:require [clojure.string :as str]
            [champaca.cgtext :refer [parse-desc]]))

(defn missing-class
  "The class a resolution failure says isn't there, or nil for any other
  failure (link.clj's normalize: \"class not found\" with the name)."
  [e]
  (let [d (ex-data e)]
    (when (and (= (set (keys d)) #{:class}) (str/starts-with? (str (ex-message e)) "class not found"))
      (:class d))))

(defn- array-class-element
  "The class an array descriptor's element names (\"[[Lp/X;\" -> \"p/X\"), or
  nil for an array of primitives."
  [d]
  (let [e (str/replace d #"^\[+" "")]
    (when (str/starts-with? e "L") (subs e 1 (dec (count e))))))

(defn resolve-type!
  "Loads the class a checkcast or instanceof names, or a call site's
  descriptor (resolve-site!): an array's element class
  (JVMS 5.4.3.1), whose absence is the array's (NoClassDefFoundError names the
  array, as HotSpot's). A class the element needs, such as its superclass,
  stays the one named."
  [resolve c]
  (if (str/starts-with? c "[")
    (when-let [el (array-class-element c)]
      (try ((:ensure-loaded resolve) el)
           (catch Exception e
             (if (= el (missing-class e)) (throw (ex-info (str "class not found: " c) {:class c})) (throw e)))))
    ((:ensure-loaded resolve) c)))

(defn resolve-site!
  "Resolves the classes a signature-polymorphic call's or an invokedynamic's
  descriptor names, parameters in order then the return type, as HotSpot
  resolves the call site's MethodType when it links the instruction: a
  missing one makes the instruction throw NoClassDefFoundError (an array's
  names the array), whatever the receiver. A plain invoke's descriptor isn't
  resolved, there or here. A bootstrap's static arguments are
  resolve-bsm-args!'s."
  [resolve desc]
  (let [{:keys [args ret]} (parse-desc desc)]
    (doseq [t (conj args ret)]
      (case (first t)
        \L (resolve-type! resolve (subs t 1 (dec (count t))))
        \[ (resolve-type! resolve t)
        nil))))

(defn resolve-bsm-args!
  "Resolves the classes the static arguments of a bootstrap codegen compiles
  directly name (a lambda's method types and implementation handle, a record's
  getters, a pattern switch's class labels), in order, as HotSpot resolves
  them when it links the invokedynamic, after the call site's type
  (resolve-site!). A Class constant is the class, a MethodType its
  descriptor's classes, a MethodHandle its member's class, the method itself,
  then its descriptor (a field's type; a missing field isn't checked). Dynamic
  constants resolve when they run (handles.ss)."
  [cls resolve arg-idxs]
  (let [cp (:cp cls)]
    (doseq [i arg-idxs :let [e (cp i)]]
      (case (:tag e)
        :class (resolve-type! resolve (:name e))
        :methodtype (resolve-site! resolve (:desc e))
        :methodhandle (let [{:keys [class name desc]} (cp (:ref-idx e))
                            kind (int (:kind e))]
                        (resolve-type! resolve class)
                        ;; the member before its descriptor (JVMS 5.4.3.5): a
                        ;; method that isn't there is NoSuchMethodError, whatever
                        ;; its descriptor names
                        (case kind
                          (5 9) ((:check-virtual resolve) class name desc)
                          (6 7 8) ((:resolve-method resolve) class name desc)
                          nil)
                        ;; kinds 1-4 are field accessors: the descriptor is the
                        ;; field's type, read as a method returning it
                        (resolve-site! resolve (if (<= 1 kind 4) (str "()" desc) desc)))
        nil))))

(defn bad-class
  "[class message] of a class whose file isn't a class file (link.clj's
  normalize), or nil for any other failure."
  [e]
  (let [d (ex-data e)]
    (when (= (set (keys d)) #{:class :class-format})
      [(:class d) (:class-format d)])))

(defn missing-method
  "[class name desc] of a method a call names that its class doesn't have
  (link.clj's resolve-method, check-virtual), or nil for any other failure."
  [e]
  (let [d (ex-data e)]
    (when (and (= (set (keys d)) #{:class :method :desc}) (= "no such method" (ex-message e)))
      [(:class d) (:method d) (:desc d)])))

(defn refused-class
  "[class error-class text] of a class the JVM refuses to define (link.clj
  check-definition!, loader.clj load-supertypes!), or nil."
  [e]
  (let [d (ex-data e)]
    (when (:linkage d) [(:class d) (:linkage d) (:message d)])))

(defn wrong-kind
  "[what class name desc] of a call whose instruction doesn't match the
  method it resolves to (link.clj check-kind), or nil."
  [e]
  (let [d (ex-data e)]
    (when (:icce d) [(:icce d) (:class d) (:method d) (:desc d)])))

(defn local-names
  "The variables a local is passed in: a long kept as halves (split) takes two."
  [split i]
  (if (split i) [(str "l" i "h") (str "l" i "l")] [(str "l" i)]))
