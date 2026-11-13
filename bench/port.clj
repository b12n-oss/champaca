;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The jolt side of jolt bench:port: times each hand translation in
;; examples/portcheck.clj on the inputs champaca.lib.PortBench gives the Java
;; methods, and prints "name ns-per-call", one line a method, as PortBench
;; does. Run from the repository root: jolt -M bench/port.clj
(load-file "examples/portcheck.clj")
(in-ns 'examples.portcheck)

(defn- time-it [name n f]
  (let [best (reduce min (for [_ (range 5)]
                           (let [t0 (System/nanoTime)]
                             (dotimes [_ n] (f))
                             (- (System/nanoTime) t0))))]
    (println name (quot best n))))

(let [xs (int-array (map #(- (* % 7919) 3000000) (range 1000)))
      half (apply str (map #(char (+ 97 (mod % 7))) (range 100)))
      pal (str half (apply str (reverse half)))
      digits (apply str (map #(char (+ 48 (mod % 10))) (range 200)))
      parts (java.util.ArrayList. (mapv #(str "  part" % " ") (range 20)))]
  (time-it "sumEven" 20000 #(sum-even xs))
  (time-it "gcd" 1000000 #(gcd 1836311903 1134903170))
  (time-it "fib" 1000000 #(fib 40))
  (time-it "clamp" 5000000 #(clamp 5 1 3))
  (time-it "reverse" 20000 #(reverse! xs))
  (time-it "countChar" 50000 #(count-char pal \a))
  (time-it "isPalindrome" 50000 #(palindrome? pal))
  (time-it "digitSum" 50000 #(digit-sum digits))
  (time-it "divAll" 20000 #(div-all xs 7))
  (time-it "hash31" 50000 #(hash31 pal))
  (time-it "joinTrim" 50000 #(join-trim parts ", ")))
