;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Call-site forms: what the code generator writes where a call names one of
;; the JDK methods HotSpot intrinsifies (src/champaca/intrinsics.clj). Each
;; does the common case in line and calls the method itself, its last
;; argument, for the rest. String.charAt and its relatives are in strings.ss,
;; next to the layout they read.

;; Objects.checkIndex and its relatives, and requireNonNull, at the call site
;; (intrinsics.clj static-forms). The test in line; a failing one calls the
;; method itself, passed as slow, with the same arguments, so the exception,
;; its message (a formatter's too) and its stack trace are the JDK's. The
;; index arguments are ints, so fixnums, and arrive as variables or
;; constants: each is evaluated once and their order doesn't matter.
(define-syntax jcheck-index   ; index, length[, formatter]
  (syntax-rules ()
    [(_ i n slow) (let ([k i] [m n]) (if (and (#3%fx>= k 0) (#3%fx< k m)) k (slow k m)))]
    [(_ i n f slow) (let ([k i] [m n]) (if (and (#3%fx>= k 0) (#3%fx< k m)) k (slow k m f)))]))
(define-syntax jcheck-from-to   ; from, to, length[, formatter]
  (syntax-rules ()
    [(_ a b n slow)
     (let ([from a] [to b] [m n])
       (if (and (#3%fx>= from 0) (#3%fx<= from to) (#3%fx<= to m)) from (slow from to m)))]
    [(_ a b n f slow)
     (let ([from a] [to b] [m n])
       (if (and (#3%fx>= from 0) (#3%fx<= from to) (#3%fx<= to m)) from (slow from to m f)))]))
(define-syntax jcheck-from-size   ; from, size, length[, formatter]
  (syntax-rules ()
    [(_ a z n slow)
     (let ([from a] [size z] [m n])
       (if (and (#3%fx>= (#3%fxlogor m from size) 0) (#3%fx<= size (#3%fx- m from))) from (slow from size m)))]
    [(_ a z n f slow)
     (let ([from a] [size z] [m n])
       (if (and (#3%fx>= (#3%fxlogor m from size) 0) (#3%fx<= size (#3%fx- m from))) from (slow from size m f)))]))
(define-syntax jrequire-non-null   ; object[, message]
  (syntax-rules ()
    [(_ x slow) (let ([o x]) (if o o (slow o)))]
    [(_ x msg slow) (let ([o x]) (if o o (slow o msg)))]))
;; Integer.valueOf and the other boxes' valueOf, at the call site (intrinsics.clj
;; box-forms). A value in the box's cache goes to the method, so the
;; cached object comes back and two boxes of a small value are still the
;; same object. Any other is allocated here and its value stored: the
;; constructors (Integer, Number, Object) do nothing else. jnew-ic runs the
;; class's initializer the first time, as new does.
(define integer-value-slot #f)
(define long-value-slot #f)
(define short-value-slot #f)
(define character-value-slot #f)
(define double-value-slot #f)
(define float-value-slot #f)
;; Integer's cache reaches IntegerCache.high, which a property can raise. It
;; is read once the cache class has run its initializer, which the first
;; call of the method sees to; until then every value goes to the method.
(define integer-cache-high #f)
(define (integer-cache-high!)
  (let ([c (find-class "java/lang/Integer$IntegerCache")])
    (when (eq? (jclass-state c) 'initialized)
      (set! integer-cache-high (top-level-value '|java/lang/Integer$IntegerCache:high|)))))
(define-syntax jbox-fresh
  (syntax-rules ()
    [(_ cell sym slot cname v tag pc)
     (let ([o (jnew-ic cell sym tag pc)])
       (unless slot (set! slot (jfield-index cname "value")))
       (#3%vector-set! (jobj-slots o) slot v)
       o)]))
(define-syntax jbox-integer
  (syntax-rules ()
    [(_ cell sym v tag pc slow)
     (let ([k v] [high integer-cache-high])
       (if (and high (or (#3%fx> k high) (#3%fx< k -128)))
           (jbox-fresh cell sym integer-value-slot "java/lang/Integer" k tag pc)
           (let ([r (slow k)]) (unless high (integer-cache-high!)) r)))]))
(define-syntax jbox-long   ; a long may be a bignum, which is never in the cache
  (syntax-rules ()
    [(_ cell sym v tag pc slow)
     (let ([k v])
       (if (and (fixnum? k) (#3%fx>= k -128) (#3%fx<= k 127))
           (slow k)
           (jbox-fresh cell sym long-value-slot "java/lang/Long" k tag pc)))]))
(define-syntax jbox-short
  (syntax-rules ()
    [(_ cell sym v tag pc slow)
     (let ([k v])
       (if (and (#3%fx>= k -128) (#3%fx<= k 127))
           (slow k)
           (jbox-fresh cell sym short-value-slot "java/lang/Short" k tag pc)))]))
(define-syntax jbox-character
  (syntax-rules ()
    [(_ cell sym v tag pc slow)
     (let ([k v])
       (if (#3%fx<= k 127)
           (slow k)
           (jbox-fresh cell sym character-value-slot "java/lang/Character" k tag pc)))]))
(define-syntax jbox-double
  (syntax-rules ()
    [(_ cell sym v tag pc slow) (jbox-fresh cell sym double-value-slot "java/lang/Double" v tag pc)]))
(define-syntax jbox-float
  (syntax-rules ()
    [(_ cell sym v tag pc slow) (jbox-fresh cell sym float-value-slot "java/lang/Float" v tag pc)]))

;; Arrays.fill at the call site: a whole array is one vector-fill!, a range
;; one loop of unchecked stores, where the JDK's loop paid an indexed store
;; per element (and for a reference array an aastore check per element; the
;; value is checked once here). A null array, a bad range or a value the
;; array can't hold goes to the method, so its exception and its frames are
;; the JDK's (Arrays.rangeCheck's messages).
(define (jarray-fill-span! v from to x)
  (let loop ([i from]) (when (#3%fx< i to) (#3%vector-set! v i x) (loop (#3%fx+ i 1)))))
(define-syntax jarray-range-ok?
  (syntax-rules ()
    [(_ a from to) (and (#3%fx<= 0 from) (#3%fx<= from to) (#3%fx<= to (#3%vector-length (jarr-vec a))))]))
(define-syntax jarray-fill            ; a value, method
  (syntax-rules ()
    [(_ a v slow) (let ([x a] [y v]) (if (jarr? x) (#3%vector-fill! (jarr-vec x) y) (slow x y)))]))
(define-syntax jarray-fill-range      ; a from to value, method
  (syntax-rules ()
    [(_ a f t v slow)
     (let ([x a] [from f] [to t] [y v])
       (if (and (jarr? x) (jarray-range-ok? x from to)) (jarray-fill-span! (jarr-vec x) from to y) (slow x from to y)))]))
(define-syntax jarray-fill-ref        ; Object[]: the value checked once
  (syntax-rules ()
    [(_ a v slow)
     (let ([x a] [y v])
       (if (and (jarr? x) (or (not y) (array-store-ok? x y))) (#3%vector-fill! (jarr-vec x) y) (slow x y)))]))
(define-syntax jarray-fill-ref-range
  (syntax-rules ()
    [(_ a f t v slow)
     (let ([x a] [from f] [to t] [y v])
       (if (and (jarr? x) (jarray-range-ok? x from to) (or (not y) (array-store-ok? x y)))
           (jarray-fill-span! (jarr-vec x) from to y)
           (slow x from to y)))]))

