;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; (java-heap-report), for tools/heap.clj java: the Java object graph from
;; every top-level symbol bound to a Java object, array or bignum (the static
;; fields), plus the intern table: objects, arrays by element, bignums in slots
(define (java-heap-report)
  (let ([seen (make-eq-hashtable)] [objs 0] [slots 0] [arrs (make-hashtable string-hash string=?)]
        [bigs 0] [bigbits 0] [samples '()] [strs 0] [chars 0] [roots 0])
    (define (note-arr a)
      (let* ([e (jarr-elem a)] [k (if (string? e) e (format "~s" e))] [cur (hashtable-ref arrs k (cons 0 0))])
        (hashtable-set! arrs k (cons (+ (car cur) 1) (+ (cdr cur) (vector-length (jarr-vec a)))))))
    (define (walk x where)
      (cond [(jobj? x)
             (unless (eq-hashtable-ref seen x #f)
               (eq-hashtable-set! seen x #t)
               (set! objs (+ objs 1))
               (let ([v (jobj-slots x)])
                 (set! slots (+ slots (vector-length v)))
                 (do ([i 0 (+ i 1)]) ((= i (vector-length v)))
                   (walk (vector-ref v i) (cons (jclass-cname (jobj-cls x)) i)))))]
            [(jarr? x)
             (unless (eq-hashtable-ref seen x #f)
               (eq-hashtable-set! seen x #t)
               (note-arr x)
               (unless (member (jarr-elem x) '("B" "C" "I" "J" "S" "Z" "F" "D"))
                 (vector-for-each (lambda (y) (walk y where)) (jarr-vec x))))]
            [(bignum? x)
             (set! bigs (+ bigs 1))
             (set! bigbits (+ bigbits (integer-length x)))
             (when (< (length samples) 6) (set! samples (cons (list where (integer-length x)) samples)))]
            [(string? x)
             (unless (eq-hashtable-ref seen x #f)
               (eq-hashtable-set! seen x #t)
               (set! strs (+ strs 1))
               (set! chars (+ chars (string-length x))))]
            [else (void)]))
    (for-each (lambda (s)
                (when (top-level-bound? s)
                  (let ([v (top-level-value s)])
                    (when (or (jobj? v) (jarr? v) (bignum? v))
                      (set! roots (+ roots 1))
                      (walk v (cons (symbol->string s) 'static))))))
              (oblist))
    (walk interned 'interned)
    (with-output-to-string
      (lambda ()
        (printf "roots ~a; objects ~a with ~a slots (~a MB as vectors); strings in slots ~a (~a chars); bignums ~a (avg ~a bits), samples ~s~%"
                roots objs slots (quotient (* 8 slots) 1048576) strs chars bigs (if (> bigs 0) (quotient bigbits bigs) 0) samples)
        (printf "arrays by element: ")
        (let-values ([(ks vs) (hashtable-entries arrs)])
          (let ([rows (list-sort (lambda (a b) (> (cddr a) (cddr b))) (map cons (vector->list ks) (vector->list vs)))])
            (for-each (lambda (r) (printf "~a: ~a arrays, ~a elements (~a MB as vectors); " (car r) (cadr r) (cddr r) (quotient (* 8 (cddr r)) 1048576)))
                      (list-head rows (min 8 (length rows))))))))))
