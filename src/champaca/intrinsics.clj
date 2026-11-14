;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.intrinsics
  "Methods a call site does in line. HotSpot intrinsifies these: a call to
  one is a handful of machine instructions there and several Java calls
  here. Each maps to a form in the runtime (strings.ss, inline.ss) that does
  the common case where the call stands and calls the method itself, passed
  as its last argument, for anything else. So an exception, its message and
  its stack trace are still the JDK's, and nothing is overridden: a call the
  code generator can't name statically runs the Java code as before."
  (:require [champaca.cgtext :refer [sx sym]]))

(def ^:private direct-forms
  "java.lang.String methods on a receiver whose class is known."
  {"java/lang/String.charAt(I)C" "jstring-char-at"
   "java/lang/String.length()I" "jstring-length"
   "java/lang/String.isEmpty()Z" "jstring-empty?"})

(def ^:private static-forms
  "The range checks behind every collection and string index, and
  requireNonNull."
  (let [f "Ljava/util/function/BiFunction;"]
    {"java/util/Objects.checkIndex(II)I" "jcheck-index"
     (str "jdk/internal/util/Preconditions.checkIndex(II" f ")I") "jcheck-index"
     "java/util/Objects.checkFromToIndex(III)I" "jcheck-from-to"
     (str "jdk/internal/util/Preconditions.checkFromToIndex(III" f ")I") "jcheck-from-to"
     "java/util/Objects.checkFromIndexSize(III)I" "jcheck-from-size"
     (str "jdk/internal/util/Preconditions.checkFromIndexSize(III" f ")I") "jcheck-from-size"
     "java/util/Objects.requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;" "jrequire-non-null"
     "java/util/Objects.requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;" "jrequire-non-null"}))

(def ^:private fill-forms
  "java.util.Arrays.fill, whole and over a range, for each element type."
  (into {"java/util/Arrays.fill([Ljava/lang/Object;Ljava/lang/Object;)V" "jarray-fill-ref"
         "java/util/Arrays.fill([Ljava/lang/Object;IILjava/lang/Object;)V" "jarray-fill-ref-range"}
        (mapcat (fn [t] [[(str "java/util/Arrays.fill([" t t ")V") "jarray-fill"]
                         [(str "java/util/Arrays.fill([" t "II" t ")V") "jarray-fill-range"]])
                ["Z" "B" "C" "S" "I" "J" "F" "D"])))

(def ^:private box-forms
  "valueOf for the boxes: [form class]. A value the box's cache holds goes
  to the method; any other is allocated at the call site with its value
  stored, which is all the constructor does."
  {"java/lang/Integer.valueOf(I)Ljava/lang/Integer;" ["jbox-integer" "java/lang/Integer"]
   "java/lang/Long.valueOf(J)Ljava/lang/Long;" ["jbox-long" "java/lang/Long"]
   "java/lang/Short.valueOf(S)Ljava/lang/Short;" ["jbox-short" "java/lang/Short"]
   "java/lang/Character.valueOf(C)Ljava/lang/Character;" ["jbox-character" "java/lang/Character"]
   "java/lang/Double.valueOf(D)Ljava/lang/Double;" ["jbox-double" "java/lang/Double"]
   "java/lang/Float.valueOf(F)Ljava/lang/Float;" ["jbox-float" "java/lang/Float"]})

(defn direct-form
  "The in-line form for a direct call of the method named k
  (Class.name(desc)), or nil: recv is the null-checked receiver's text, argv
  the arguments', method the method itself."
  [k recv argv method]
  (when-let [form (direct-forms k)]
    (apply sx form recv (concat argv [method]))))

(defn static-form
  "The in-line form for an invokestatic of the method named k, or nil. cell!
  names a fresh inline-cache cell, taken only by a form that allocates;
  site is the call's tag and pc."
  [k argv method cell! site]
  (if-let [form (or (static-forms k) (fill-forms k))]
    (apply sx form (concat argv [method]))
    (when-let [[form cname] (box-forms k)]
      (sx form (cell!) (str "'" (sym cname)) (first argv) site method))))
