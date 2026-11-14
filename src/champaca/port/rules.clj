;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.port.rules
  "The JDK members champaca.port can write as jolt forms: each rule names the
  jolt form that stands for a member, and the condition, if any, under which
  the two agree.")

;; :form takes the arguments, already translated, receiver first. :holds names
;; the condition the rule needs, if any. A boolean result is 1 or 0 in the
;; structured form, so a rule for a method returning boolean gives that.

(def direct-rules
  {"java/lang/String.length()I"
   {:form (fn [[r]] (list '.length r)) :holds :strings-within-the-bmp}
   "java/lang/String.charAt(I)C"
   {:form (fn [[r i]] (list 'int (list '.charAt r i))) :holds :strings-within-the-bmp}
   "java/lang/String.trim()Ljava/lang/String;"
   {:form (fn [[r]] (list '.trim r))}
   "java/lang/String.isEmpty()Z"
   {:form (fn [[r]] (list 'if (list '.isEmpty r) 1 0))}
   "java/lang/StringBuilder.append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/StringBuilder.append(C)Ljava/lang/StringBuilder;"
   {:form (fn [[r x]] (list '.append r (list 'char x)))}
   "java/lang/StringBuilder.append(I)Ljava/lang/StringBuilder;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/StringBuilder.toString()Ljava/lang/String;"
   {:form (fn [[r]] (list '.toString r))}
   "java/lang/StringBuilder.length()I"
   {:form (fn [[r]] (list '.length r)) :holds :strings-within-the-bmp}
   "java/lang/String.substring(I)Ljava/lang/String;"
   {:form (fn [[r i]] (list '.substring r i)) :holds :strings-within-the-bmp}
   "java/lang/String.substring(II)Ljava/lang/String;"
   {:form (fn [[r i j]] (list '.substring r i j)) :holds :strings-within-the-bmp}
   "java/lang/String.indexOf(Ljava/lang/String;)I"
   {:form (fn [[r x]] (list '.indexOf r x)) :holds :strings-within-the-bmp}
   "java/lang/String.indexOf(Ljava/lang/String;I)I"
   {:form (fn [[r x i]] (list '.indexOf r x i)) :holds :strings-within-the-bmp}
   "java/lang/String.lastIndexOf(Ljava/lang/String;)I"
   {:form (fn [[r x]] (list '.lastIndexOf r x)) :holds :strings-within-the-bmp}
   "java/lang/String.indexOf(I)I"
   {:form (fn [[r c]] (list '.indexOf r c)) :holds :strings-within-the-bmp}
   "java/lang/String.indexOf(II)I"
   {:form (fn [[r c i]] (list '.indexOf r c i)) :holds :strings-within-the-bmp}
   "java/lang/String.lastIndexOf(I)I"
   {:form (fn [[r c]] (list '.lastIndexOf r c)) :holds :strings-within-the-bmp}
   "java/lang/String.lastIndexOf(II)I"
   {:form (fn [[r c i]] (list '.lastIndexOf r c i)) :holds :strings-within-the-bmp}
   "java/lang/String.lastIndexOf(Ljava/lang/String;I)I"
   {:form (fn [[r x i]] (list '.lastIndexOf r x i)) :holds :strings-within-the-bmp}
   ;; a char[] of a string within the BMP has one element a char, as Java's;
   ;; on a nil typed as a String jolt answers an empty array
   "java/lang/String.toCharArray()[C"
   {:form (fn [[r]] (list '.toCharArray (list 'non-null r))) :holds :strings-within-the-bmp}
   ;; jolt makes "" of a nil array, where Java throws
   "java/lang/String.valueOf([C)Ljava/lang/String;"
   {:form (fn [[a]] (list 'String. (list 'non-null a)))}
   "java/lang/StringBuffer.reverse()Ljava/lang/StringBuffer;"
   {:form (fn [[r]] (list '.reverse r)) :holds :strings-within-the-bmp}
   ;; the same as the JDK's on every char outside the surrogates
   ;; (examples/port.clj checks each)
   "java/lang/Character.isTitleCase(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isTitleCase (list 'char c)) 1 0))}
   "java/lang/String.valueOf(C)Ljava/lang/String;"
   {:form (fn [[c]] (list 'String/valueOf (list 'char c)))}
   "java/lang/String.equalsIgnoreCase(Ljava/lang/String;)Z"
   {:form (fn [[r x]] (list 'if (list '.equalsIgnoreCase r x) 1 0))}
   "java/lang/String.regionMatches(ZILjava/lang/String;II)Z"
   {:form (fn [[r ic i x j n]] (list 'if (list '.regionMatches r (list 'not= 0 ic) i x j n) 1 0))
    :holds :strings-within-the-bmp}
   "java/lang/String.replace(CC)Ljava/lang/String;"
   {:form (fn [[r a b]] (list '.replace r (list 'char a) (list 'char b)))}
   "java/lang/String.equals(Ljava/lang/Object;)Z"
   {:form (fn [[r x]] (list 'if (list '.equals r x) 1 0))}
   "java/lang/String.startsWith(Ljava/lang/String;)Z"
   {:form (fn [[r x]] (list 'if (list '.startsWith r x) 1 0))}
   "java/lang/String.endsWith(Ljava/lang/String;)Z"
   {:form (fn [[r x]] (list 'if (list '.endsWith r x) 1 0))}
   "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;"
   {:form (fn [[r x]] (list '.concat r x))}
   ;; jolt 0.8.27's Character/isWhitespace differs from the JDK's on five
   ;; chars: it leaves out U+001C to U+001F and takes U+0085. With those
   ;; settled here the form agrees with the JDK on every char
   ;; outside the surrogates (examples/port.clj checks each).
   "java/lang/Character.isWhitespace(C)Z"
   {:form (fn [[c]] (list 'let ['ch c]
                          (list 'if (list 'and (list 'not= 'ch 0x85)
                                          (list 'or (list '<= 0x1c 'ch 0x1f) (list 'Character/isWhitespace (list 'char 'ch))))
                                1 0)))}
   "java/lang/Character.isDigit(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isDigit (list 'char c)) 1 0))}
   ;; jolt's Unicode tables and JDK 25's differ on eight chars it calls
   ;; letters and the JDK doesn't (U+088F, U+0C5C, U+0CDC and five in U+A7CE
   ;; to U+A7F1)
   "java/lang/Character.isLetter(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isLetter (list 'char c)) 1 0)) :holds :letters-jolt-knows}
   "java/lang/Character.isLetterOrDigit(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isLetterOrDigit (list 'char c)) 1 0)) :holds :letters-jolt-knows}
   ;; case: jolt and JDK 25 agree on every char but three each way
   ;; (U+A7CE to U+A7D5, from a newer Unicode), so these hold for the
   ;; letters both know
   "java/lang/Character.toLowerCase(C)C"
   {:form (fn [[c]] (list 'int (list 'Character/toLowerCase (list 'char c)))) :holds :letters-jolt-knows}
   "java/lang/Character.toUpperCase(C)C"
   {:form (fn [[c]] (list 'int (list 'Character/toUpperCase (list 'char c)))) :holds :letters-jolt-knows}
   "java/lang/Character.toTitleCase(C)C"
   {:form (fn [[c]] (list 'int (list 'Character/toTitleCase (list 'char c)))) :holds :letters-jolt-knows}
   "java/lang/Character.isUpperCase(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isUpperCase (list 'char c)) 1 0)) :holds :letters-jolt-knows}
   "java/lang/Character.isLowerCase(C)Z"
   {:form (fn [[c]] (list 'if (list 'Character/isLowerCase (list 'char c)) 1 0)) :holds :letters-jolt-knows}
   "java/lang/String.toUpperCase()Ljava/lang/String;"
   {:form (fn [[r]] (list '.toUpperCase r)) :holds :letters-jolt-knows}
   "java/lang/String.toLowerCase()Ljava/lang/String;"
   {:form (fn [[r]] (list '.toLowerCase r)) :holds :letters-jolt-knows}
   "java/lang/StringBuffer.append(Ljava/lang/Object;)Ljava/lang/StringBuffer;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/StringBuilder.append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/Math.min(II)I" {:form (fn [[a b]] (list 'min a b))}
   "java/lang/Math.max(II)I" {:form (fn [[a b]] (list 'max a b))}
   "java/lang/StringBuffer.append(Ljava/lang/String;)Ljava/lang/StringBuffer;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/StringBuffer.append(C)Ljava/lang/StringBuffer;"
   {:form (fn [[r x]] (list '.append r (list 'char x)))}
   "java/lang/StringBuffer.append(I)Ljava/lang/StringBuffer;"
   {:form (fn [[r x]] (list '.append r x))}
   "java/lang/StringBuffer.toString()Ljava/lang/String;"
   {:form (fn [[r]] (list '.toString r))}
   "java/lang/StringBuffer.length()I"
   {:form (fn [[r]] (list '.length r)) :holds :strings-within-the-bmp}})

(def virtual-rules
  "By method name and descriptor: the receiver's class isn't in the call.
  These hold for a java.util.List jolt implements."
  {"size()I" {:form (fn [[r]] (list '.size r))}
   "get(I)Ljava/lang/Object;" {:form (fn [[r i]] (list '.get r i))}
   "isEmpty()Z" {:form (fn [[r]] (list 'if (list '.isEmpty r) 1 0))}
   "add(Ljava/lang/Object;)Z" {:form (fn [[r x]] (list 'if (list '.add r x) 1 0))}
   "iterator()Ljava/util/Iterator;" {:form (fn [[r]] (list '.iterator r))}
   "hasNext()Z" {:form (fn [[r]] (list 'if (list '.hasNext r) 1 0))}
   "next()Ljava/lang/Object;" {:form (fn [[r]] (list '.next r))}
   "toArray([Ljava/lang/Object;)[Ljava/lang/Object;" {:form (fn [[r a]] (list '.toArray r a))}
   "toString()Ljava/lang/String;" {:form (fn [[r]] (list '.toString r))}})

(def constructor-rules
  "By class, then constructor descriptor."
  (into {"java/lang/StringBuilder" {"()V" (fn [_] (list 'StringBuilder.))
                                    "(I)V" (fn [[n]] (list 'StringBuilder. n))
                                    "(Ljava/lang/String;)V" (fn [[x]] (list 'StringBuilder. x))}
         "java/lang/StringBuffer" {"()V" (fn [_] (list 'StringBuffer.))
                                   "(I)V" (fn [[n]] (list 'StringBuffer. n))
                                   "(Ljava/lang/String;)V" (fn [[x]] (list 'StringBuffer. x))}
         "java/lang/String" {"([C)V" (fn [[a]] (list 'String. (list 'non-null a)))
                             "([CII)V" (fn [[a off n]] (list 'string-of a off n))}
         "java/util/ArrayList" {"()V" (fn [_] (list 'java.util.ArrayList.))}}
        (for [c ["IllegalArgumentException" "NumberFormatException" "IllegalStateException"
                 "ArithmeticException" "IndexOutOfBoundsException" "UnsupportedOperationException"
                 "RuntimeException" "NullPointerException"]]
          [(str "java/lang/" c) {"()V" (fn [_] (list (symbol (str c "."))))
                                 "(Ljava/lang/String;)V" (fn [[msg]] (list (symbol (str c ".")) msg))}])))

;; No boolean[]: an element read from one is true or false in jolt, where
;; the code here works on 1 and 0, and a boolean[] is read by the same
;; instruction as a byte[]. A char[] has instructions of its own (char-ref
;; and char-set! in the plain form), so its elements cross as chars.
(def array-makers
  {"C" 'char-array "I" 'int-array "J" 'long-array "B" 'byte-array "S" 'short-array "D" 'double-array "F" 'float-array})

(def int-ops {"ix+" '+ "ix-" '- "ix*" '* "ix-and" 'bit-and "ix-or" 'bit-or "ix-xor" 'bit-xor})
(def wrapped-ops {"ix+" 'unchecked-add-int "ix-" 'unchecked-subtract-int "ix*" 'unchecked-multiply-int})
(def compare-ops
  {"ix=" '= "ix<>" 'not= "ix<" '< "ix<=" '<= "ix>" '> "ix>=" '>= "=" '= "<" '< "<=" '<= ">" '> ">=" '>=})
