;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The port tool's Scheme half (src/champaca/port.clj loads it on first use).
;; A method's generated text is read, put through the structuring pass
;; (structure.ss), and written out as text the Clojure reader takes: a list
;; as a vector, a symbol as a string, a string as [:str "..."], a flonum as
;; [:num "..."], #t and #f as true and false, a dotted pair as [:dot a b].
;; The translation to Clojure is done there, on that data.

(define (port-put-string s p)   ; a Clojure string literal
  (put-char p #\")
  (string-for-each
    (lambda (c)
      (let ([n (char->integer c)])
        (cond [(char=? c #\") (put-string p "\\\"")]
              [(char=? c #\\) (put-string p "\\\\")]
              [(and (fx>= n 32) (fx< n 127)) (put-char p c)]
              [(fx< n #x10000) (port-put-unit n p)]
              [else (let ([v (fx- n #x10000)])
                      (port-put-unit (fx+ #xd800 (fxsrl v 10)) p)
                      (port-put-unit (fx+ #xdc00 (fxlogand v #x3ff)) p))])))
    s)
  (put-char p #\"))
(define (port-put-unit n p)   ; \uXXXX
  (let ([hex (number->string n 16)])
    (put-string p "\\u")
    (put-string p (make-string (fx- 4 (string-length hex)) #\0))
    (put-string p hex)))

(define (port-put-all xs p)
  (for-each (lambda (x) (put-char p #\space) (port-put x p)) xs))
(define (port-put-pair x p)
  (if (list? x)
      (begin (put-char p #\[) (port-put-all x p) (put-char p #\]))
      (begin (put-string p "[:dot") (port-put (car x) p) (put-char p #\space) (port-put (cdr x) p) (put-char p #\]))))
(define (port-put x p)
  (cond
    [(pair? x) (port-put-pair x p)]
    [(null? x) (put-string p "[]")]
    [(symbol? x) (port-put-string (symbol->string x) p)]
    [(string? x) (put-string p "[:str ") (port-put-string x p) (put-char p #\])]
    [(boolean? x) (put-string p (if x "true" "false"))]
    [(and (integer? x) (exact? x)) (put-string p (number->string x))]
    [(number? x) (put-string p "[:num ") (port-put-string (number->string x) p) (put-char p #\])]
    [(vector? x) (put-string p "[:vec") (port-put-all (vector->list x) p) (put-char p #\])]
    [else (put-string p "[:other]")]))

;; text: one generated method form. Answers the reader text of [:ok form],
;; the structured method expression, or [:left "reason"] when the pass
;; can't follow the method.
(define (port-structured text)
  (let* ([form (read (open-string-input-port text))]
         [names (make-eq-hashtable)]
         [why (call/1cc (lambda (k) (st-scan (caddr form) names k) #f))])
    (call-with-string-output-port
      (lambda (p)
        (if why
            (begin (put-string p "[:left ") (port-put-string (symbol->string why) p) (put-char p #\]))
            (begin (put-string p "[:ok ") (port-put (st-rewrite (caddr form) names) p) (put-char p #\])))))))
