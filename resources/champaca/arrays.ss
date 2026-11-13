;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Making arrays, and running out of room: the allocators generated code and
;; the natives call, the limits on an array's size, and jolt's out-of-memory
;; error as Java's OutOfMemoryError. Access to an array's elements is in
;; runtime.ss, next to the macros generated code reads them through.

;; An array is a vector of eight-byte words whatever its element type. A
;; length past HotSpot's limit (Integer.MAX_VALUE - 2), or a vector that
;; wouldn't fit in what the heap may still take, is OutOfMemoryError with
;; HotSpot's two messages. Without the check Chez asks the system for the
;; memory and the process ends when it isn't there. Only a request of
;; large-array-length or more pays for the question. The heap's limit is
;; jolt's ceiling: a quarter of physical memory or of the container's
;; limit, HotSpot's default, or JOLT_MAX_HEAP.
(define max-array-length 2147483645)
(define large-array-length 131072)
(define (heap-room? words)
  (let ([limit (jolt-heap-max-bytes)] [bytes (* 8 words)])
    (or (not limit)
        (<= (+ bytes (bytes-allocated)) limit)
        ;; what is allocated counts garbage not yet collected: collect
        ;; (natives.ss full-collect), as HotSpot does before it gives up,
        ;; and ask again
        (and (<= bytes limit)
             (begin (full-collect) (<= (+ bytes (bytes-allocated)) limit))))))
(define (array-fits? n)
  (and (fx<= n max-array-length) (heap-room? n)))
(define (array-too-large n)
  (jthrow "java/lang/OutOfMemoryError"
          (if (> n max-array-length) "Requested array size exceeds VM limit" "Java heap space")))

(define (jnewarray elem n default)
  (cond
    [(fx< n 0) (jthrow "java/lang/NegativeArraySizeException" (number->string n))]
    [(and (fx>= n large-array-length) (not (array-fits? n))) (array-too-large n)]
    [else (make-jarr elem (#3%make-vector n default))]))
;; generated code's newarray and anewarray, with elem already interned
(define (jnewarray-interned elem n default)
  (cond
    [(fx< n 0) (jthrow "java/lang/NegativeArraySizeException" (number->string n))]
    [(and (fx>= n large-array-length) (not (array-fits? n))) (array-too-large n)]
    [else (make-jarr* elem (#3%make-vector n default))]))

(define (elem-default d)
  (case (string-ref d 0) [(#\L #\[) #f] [(#\F #\D) 0.0] [else 0]))

(define (jmultiarray desc dims)
  ;; desc is the full array type, e.g. "[[I". HotSpot makes the outermost
  ;; array, then the first array of each dimension inside it, and so on, and
  ;; fails at the first step that can't be done; the checks here come in
  ;; that order, before anything is made. A dimension of zero makes nothing
  ;; below it, but those below are still checked for a negative length, so
  ;; new int[0][-1] throws too.
  (define (negative n) (jthrow "java/lang/NegativeArraySizeException" (number->string n)))
  (let loop ([ds dims] [words 0])   ; words: the first array of each dimension so far
    (unless (null? ds)
      (let ([n (car ds)])
        (cond
          [(fx< n 0) (negative n)]
          [(fx> n max-array-length) (array-too-large n)]
          [(and (fx>= n large-array-length) (not (heap-room? (+ words n)))) (array-too-large 0)]
          [(fx= n 0) (for-each (lambda (m) (when (fx< m 0) (negative m))) (cdr ds))]
          [else (loop (cdr ds) (+ words n))]))))
  ;; then every element of every array together, since each inner array
  ;; alone may be small
  (let ([words (apply * dims)])
    (when (and (>= words large-array-length) (not (heap-room? words)))
      (array-too-large 0)))
  (jmultiarray* desc dims))
(define (jmultiarray* desc dims)
  (let ([elem (substring desc 1 (string-length desc))])
    (if (null? (cdr dims))
        (jnewarray elem (car dims) (elem-default elem))
        (let ([a (jnewarray elem (car dims) #f)])
          (do ([i 0 (fx+ i 1)]) ((fx= i (car dims)) a)
            (vector-set! (jarr-vec a) i (jmultiarray* elem (cdr dims))))))))

;; --- a full heap ----------------------------------------------------------------

;; jolt holds the heap under a ceiling (a quarter of physical memory or of
;; a container's limit, or JOLT_MAX_HEAP) and raises an error whose message
;; opens "out of memory" when a full collection leaves more than that live,
;; or when collecting takes nearly all the time. To Java that is
;; OutOfMemoryError with HotSpot's message for each. The object is made
;; when the heap has no room, so while one is being made a second such
;; error answers the last one made rather than start again.
(define ooms-seen (make-weak-eq-hashtable))
(define oom-last #f)
(define oom-making #f)
(define (jolt-out-of-memory e)   ; HotSpot's message for jolt's error, or #f
  (and (condition? e) (message-condition? e)
       (let ([m (condition-message e)])
         (and (string? m) (fx>= (string-length m) 14)
              (string=? (substring m 0 14) "out of memory:")
              (if (text-after m "GC overhead limit exceeded") "GC overhead limit exceeded" "Java heap space")))))
(define (java-out-of-memory e message)
  (or (eq-hashtable-ref ooms-seen e #f)
      (and oom-making oom-last)
      (let ([o (dynamic-wind
                (lambda () (set! oom-making #t))
                (lambda ()
                  (jinit "java/lang/OutOfMemoryError")
                  (let ([o (jnew "java/lang/OutOfMemoryError")])
                    ((method-proc "java/lang/OutOfMemoryError" "<init>(Ljava/lang/String;)V") o (jstring message))
                    o))
                (lambda () (set! oom-making #f)))])
        (set! oom-last o)
        (eq-hashtable-set! ooms-seen e o)
        o)))
