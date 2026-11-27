;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; champaca runtime: the Chez Scheme half. Generated method bodies call
;; into these. Java values map to Scheme as:
;;   int/short/byte/char/boolean  fixnum (booleans are 0/1, as in bytecode)
;;   long                         exact integer, wrapped to 64 bits
;;   float/double                 flonum (float rounded through ieee-single)
;;   null                         #f
;;   object                       jobj record: class + slot vector
;;                                (java.lang.String is a real jobj too: value byte[] + coder)
;;   array                        jarr record: element descriptor + vector

;; --- hooks into jolt ---------------------------------------------------------

(define jit-hook #f)
(define load-hook #f)
(define (set-hooks! jit load) (set! jit-hook jit) (set! load-hook load))
;; the loader a class is being defined or compiled for, per thread: a class
;; that code names and the classpath and JDK lack is asked of it (natives.ss
;; ask-resolving-loader), as the JVM resolves a reference through the
;; referring class's loader (JVMS 5.3)
(define resolving-loader (make-thread-parameter #f))
;; set for every compile, #f for a class no loader of the program's own
;; defined, since compiling one method can compile another (a loader's
;; findClass runs Java code)
(define (jit! c m d)
  (parameterize ([resolving-loader (hashtable-ref class-loaders c #f)]) (call-unbounded (lambda () (jit-hook c m d)))))

;; --- numbers ---------------------------------------------------------------

(define (i32 x)
  (let ([y (logand x #xffffffff)])
    (if (fx>= y #x80000000) (fx- y #x100000000) y)))

(define (i64 x)
  (if (or (fixnum? x) (and (<= #x-8000000000000000 x) (<= x #x7fffffffffffffff)))
      x
      (let ([y (logand x #xffffffffffffffff)])
        (if (>= y #x8000000000000000) (- y #x10000000000000000) y))))

;; Generated code wraps int and long results with these macros rather than
;; i32 and i64: a call to a top-level procedure can't be inlined, and i64's
;; logand with a bignum mask made every long add a bignum operation. The
;; fixnum case is inline; anything else (a bignum, which a long past 60 bits
;; or an int product can be) takes the procedure. A fixnum is always inside
;; long's range, since Chez's fixnums are 61 bits.
(define-syntax wrap-int
  (syntax-rules ()
    [(_ e) (let ([y e])
             (if (fixnum? y) (#3%fx- (#3%fxlogxor (#3%fxlogand y #xffffffff) #x80000000) #x80000000) (i32 y)))]))
(define-syntax wrap-long
  (syntax-rules ()
    [(_ e) (let ([y e]) (if (fixnum? y) y (i64 y)))]))

;; Generated code's int operations. An int is a fixnum in int's range by
;; construction, so these skip Chez's checks (#3%): the checked fx operations
;; were a third of an int[] sum's time. Under CHAMPACA_BT (in the cache key)
;; they keep them, so the battery's bt mode still catches a producer that
;; hands on something else as an int. ix* is fx*/wraparound, whose product
;; keeps its low 32 bits (2^32 divides the fixnum range), which is all
;; wrap-int reads; ix-quotient and ix-remainder get a nonzero divisor.
(define-syntax define-int-ops
  (syntax-rules ()
    [(_ (name unchecked checked) ...)
     (begin (define-syntax name
              (if (getenv "CHAMPACA_BT")
                  (syntax-rules () [(_ . args) (checked . args)])
                  (syntax-rules () [(_ . args) (unchecked . args)])))
            ...)]))
(define-int-ops
  (ix+ #3%fx+ fx+) (ix- #3%fx- fx-) (ix* #3%fx*/wraparound fx*/wraparound)
  (ix= #3%fx= fx=) (ix< #3%fx< fx<) (ix<= #3%fx<= fx<=) (ix> #3%fx> fx>) (ix>= #3%fx>= fx>=)
  (ix<> (lambda (a b) (not (#3%fx= a b))) (lambda (a b) (not (fx= a b))))
  (ix-and #3%fxlogand fxlogand) (ix-or #3%fxlogor fxlogor) (ix-xor #3%fxlogxor fxlogxor)
  (ix-sll #3%fxsll fxsll) (ix-sra #3%fxsra fxsra) (ix-srl #3%fxsrl fxsrl)
  (ix-quotient #3%fxquotient fxquotient) (ix-remainder #3%fxremainder fxremainder))

;; Java long arithmetic. A long is an exact integer, a fixnum up to 60 bits
;; and a bignum past them, so generic + and * on longs made a bignum of up to
;; 128 bits and i64 cut it back with more bignum operations, three or four
;; allocations an operation. Generated code calls these macros instead: two
;; fixnums inline (a sum of two fixnums is always a long), anything else a
;; procedure that does the 64-bit operation on 32-bit halves in fixnum
;; arithmetic and makes at most one bignum, for the result. The halves go
;; through long-buf: #3%bytevector-s64-native-set! reads a long's 64 bits
;; inline (the bignum case an assembly routine), and
;; #3%bytevector-s64-native-ref gives back the fixnum or a new bignum. Its
;; unchecked set reads the top two bigits of a longer bignum without
;; complaint, so only longs go in (checked under CHAMPACA_BT). One buffer serves every thread: only the
;; thread holding the GIL runs Java code, and nothing between a store and
;; its loads can give the GIL up (no call, no safepoint). The unchecked fx
;; operations act on halves, fixnums by construction. Little-endian only, as
;; are the machines champaca runs on.
(unless (eq? (native-endianness) 'little) (error 'champaca "long arithmetic assumes a little-endian machine"))
(define long-buf (make-bytevector 16 0))
;; Under CHAMPACA_BT (in the cache key) a value outside long's range is an
;; error here instead of garbage, so the battery's bt mode checks that no
;; producer of a long hands on an unwrapped one.
(define-syntax long-put!
  (if (getenv "CHAMPACA_BT")
      (syntax-rules ()
        [(_ off v) (let ([x v])
                     (unless (and (exact? x) (integer? x) (<= #x-8000000000000000 x #x7fffffffffffffff))
                       (errorf 'long-put! "~s is not a long" x))
                     (#3%bytevector-s64-native-set! long-buf off x))])
      (syntax-rules () [(_ off v) (#3%bytevector-s64-native-set! long-buf off v)])))
(define-syntax long-lo (syntax-rules () [(_ off) (#3%bytevector-u32-native-ref long-buf off)]))
(define-syntax long-hi (syntax-rules () [(_ off) (#3%bytevector-s32-native-ref long-buf (#3%fx+ off 4))]))
(define-syntax long-uhi (syntax-rules () [(_ off) (#3%bytevector-u32-native-ref long-buf (#3%fx+ off 4))]))
;; the long whose halves are hi and lo, each cut to its low 32 bits
(define-syntax make-long
  (syntax-rules ()
    [(_ hi lo) (begin (#3%bytevector-u32-native-set! long-buf 0 lo)
                      (#3%bytevector-s32-native-set! long-buf 4 hi)
                      (#3%bytevector-s64-native-ref long-buf 0))]))
(define (ladd/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (let ([lo (#3%fx+ (long-lo 0) (long-lo 8))])
    (make-long (#3%fx+ (#3%fx+ (long-hi 0) (long-hi 8)) (#3%fxsrl lo 32)) lo)))
(define (lsub/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (let ([lo (#3%fx- (long-lo 0) (long-lo 8))])
    (make-long (#3%fx+ (#3%fx- (long-hi 0) (long-hi 8)) (#3%fxsra lo 32)) lo)))
;; al*bl is 64 bits, so it is taken as al times bl's two 16-bit halves; the
;; cross products only need their low 32 bits, which wrapping at the
;; fixnum's 61 keeps
(define (lmul/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (let* ([al (long-lo 0)] [ah (long-hi 0)] [bl (long-lo 8)] [bh (long-hi 8)]
         [s (#3%fx* al (#3%fxlogand bl #xffff))]
         [u (#3%fx* al (#3%fxsrl bl 16))]
         [w (#3%fx+ s (#3%fxsll (#3%fxlogand u #xffff) 16))])
    (make-long (#3%fx+/wraparound (#3%fx+ (#3%fxsrl u 16) (#3%fxsrl w 32))
                                  (#3%fx+/wraparound (#3%fx*/wraparound al bh) (#3%fx*/wraparound ah bl)))
               w)))
(define (lneg/big x) (if (= x #x-8000000000000000) x (- x)))
(define (land/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (make-long (#3%fxlogand (long-hi 0) (long-hi 8)) (#3%fxlogand (long-lo 0) (long-lo 8))))
(define (lor/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (make-long (#3%fxlogor (long-hi 0) (long-hi 8)) (#3%fxlogor (long-lo 0) (long-lo 8))))
(define (lxor/big x y)
  (long-put! 0 x) (long-put! 8 y)
  (make-long (#3%fxlogxor (long-hi 0) (long-hi 8)) (#3%fxlogxor (long-lo 0) (long-lo 8))))
;; the shifts take n in 0..63
(define (lshl/big x n)
  (long-put! 0 x)
  (let ([lo (long-lo 0)] [hi (long-uhi 0)])
    (cond [(#3%fx= n 0) x]
          [(#3%fx< n 32) (make-long (#3%fxlogor (#3%fxsll hi n) (#3%fxsrl lo (#3%fx- 32 n))) (#3%fxsll lo n))]
          [else (make-long (#3%fxsll lo (#3%fx- n 32)) 0)])))
(define (lushr/big x n)
  (long-put! 0 x)
  (let ([lo (long-lo 0)] [hi (long-uhi 0)])
    (cond [(#3%fx= n 0) x]
          [(#3%fx< n 32) (make-long (#3%fxsrl hi n) (#3%fxlogor (#3%fxsrl lo n) (#3%fxsll hi (#3%fx- 32 n))))]
          [else (make-long 0 (#3%fxsrl hi (#3%fx- n 32)))])))
(define (lshr/big x n)
  (long-put! 0 x)
  (let ([lo (long-lo 0)] [hi (long-hi 0)])
    (cond [(#3%fx= n 0) x]
          [(#3%fx< n 32) (make-long (#3%fxsra hi n) (#3%fxlogor (#3%fxsrl lo n) (#3%fxsll hi (#3%fx- 32 n))))]
          [else (make-long (#3%fxsra hi 31) (#3%fxsra hi (#3%fx- n 32)))])))
(define (l2i/big x) (long-put! 0 x) (#3%bytevector-s32-native-ref long-buf 0))
(define (lcmp/big x y) (cond [(< x y) -1] [(> x y) 1] [else 0]))
(define-syntax ladd
  (syntax-rules () [(_ a b) (let ([x a] [y b]) (if (and (fixnum? x) (fixnum? y)) (+ x y) (ladd/big x y)))]))
(define-syntax lsub
  (syntax-rules () [(_ a b) (let ([x a] [y b]) (if (and (fixnum? x) (fixnum? y)) (- x y) (lsub/big x y)))]))
;; both under 2^30 in magnitude: the product is a fixnum
(define-syntax lmul
  (syntax-rules ()
    [(_ a b) (let ([x a] [y b])
               (if (and (fixnum? x) (fixnum? y) (#3%fx<= #x-3fffffff x #x3fffffff) (#3%fx<= #x-3fffffff y #x3fffffff))
                   (#3%fx* x y)
                   (lmul/big x y)))]))
(define-syntax lneg (syntax-rules () [(_ a) (let ([x a]) (if (fixnum? x) (- x) (lneg/big x)))]))
(define-syntax land
  (syntax-rules () [(_ a b) (let ([x a] [y b]) (if (and (fixnum? x) (fixnum? y)) (#3%fxlogand x y) (land/big x y)))]))
(define-syntax lor
  (syntax-rules () [(_ a b) (let ([x a] [y b]) (if (and (fixnum? x) (fixnum? y)) (#3%fxlogor x y) (lor/big x y)))]))
(define-syntax lxor
  (syntax-rules () [(_ a b) (let ([x a] [y b]) (if (and (fixnum? x) (fixnum? y)) (#3%fxlogxor x y) (lxor/big x y)))]))
;; a fixnum shifted left stays one when shifting back gives it again
(define-syntax lshl
  (syntax-rules ()
    [(_ a s) (let ([x a] [n (fxlogand s 63)])
               (if (and (fixnum? x) (#3%fx< n 61))
                   (let ([r (#3%fxsll/wraparound x n)]) (if (#3%fx= (#3%fxsra r n) x) r (lshl/big x n)))
                   (lshl/big x n)))]))
;; a fixnum has at most 60 bits besides its sign, so 60 shifts them all out
(define-syntax lshr
  (syntax-rules ()
    [(_ a s) (let ([x a] [n (fxlogand s 63)])
               (if (fixnum? x) (#3%fxsra x (#3%fxmin n 60)) (lshr/big x n)))]))
(define-syntax lushr
  (syntax-rules ()
    [(_ a s) (let ([x a] [n (fxlogand s 63)])
               (if (and (fixnum? x) (#3%fx>= x 0)) (#3%fxsrl x (#3%fxmin n 60)) (lushr/big x n)))]))
(define-syntax l2i
  (syntax-rules ()
    [(_ a) (let ([x a])
             (if (fixnum? x) (#3%fx- (#3%fxlogxor (#3%fxlogand x #xffffffff) #x80000000) #x80000000) (l2i/big x)))]))
(define-syntax lcmp
  (syntax-rules ()
    [(_ a b) (let ([x a] [y b])
               (if (and (fixnum? x) (fixnum? y)) (if (#3%fx< x y) -1 (if (#3%fx= x y) 0 1)) (lcmp/big x y)))]))
;; Longs kept as halves. Inside a method, codegen carries a long that long
;; operations make and use as two fixnums, its high and low 32 bits, both
;; unsigned, and makes the long (lh-box) only where it leaves them: a
;; call, a store, a return, a jump with it on the stack. These give one half
;; of a result each from the operands' halves, which are variables or
;; literals, so repeating one costs nothing. A product's partial sums are
;; lh-mul-u and lh-mul-w, which codegen binds first.
(define-syntax lh-u32 (syntax-rules () [(_ x) (#3%fxlogand x #xffffffff)]))
;; the long of two unsigned halves: both stores in their range, unlike
;; make-long's signed high half
(define-syntax lh-box
  (syntax-rules ()
    [(_ h l) (begin (#3%bytevector-u32-native-set! long-buf 0 l)
                    (#3%bytevector-u32-native-set! long-buf 4 h)
                    (#3%bytevector-s64-native-ref long-buf 0))]))
;; a boxed long's halves: lh-split-l right after lh-split-h, on the same
;; long, which put a bignum's bits in long-buf
(define-syntax lh-split-h
  (syntax-rules ()
    [(_ v) (if (fixnum? v) (lh-u32 (#3%fxsra v 32)) (begin (long-put! 0 v) (long-uhi 0)))]))
(define-syntax lh-split-l
  (syntax-rules () [(_ v) (if (fixnum? v) (lh-u32 v) (long-lo 0))]))
;; a sum's or difference's low halves first, as c: bit 32 of a sum is the
;; carry, a negative difference the borrow
(define-syntax lh-add-c (syntax-rules () [(_ al bl) (#3%fx+ al bl)]))
(define-syntax lh-add-h (syntax-rules () [(_ ah bh c) (lh-u32 (#3%fx+ (#3%fx+ ah bh) (#3%fxsrl c 32)))]))
(define-syntax lh-sub-c (syntax-rules () [(_ al bl) (#3%fx- al bl)]))
(define-syntax lh-sub-h (syntax-rules () [(_ ah bh c) (lh-u32 (#3%fx+ (#3%fx- ah bh) (#3%fxsra c 32)))]))
(define-syntax lh-and (syntax-rules () [(_ a b) (#3%fxlogand a b)]))
(define-syntax lh-or (syntax-rules () [(_ a b) (#3%fxlogor a b)]))
(define-syntax lh-xor (syntax-rules () [(_ a b) (#3%fxlogxor a b)]))
;; an int's high half: 0 or all ones
(define-syntax lh-i2l-h (syntax-rules () [(_ i) (lh-u32 (#3%fxsra i 32))]))
;; al*bl is s + u*2^16 with s = al*(bl's low 16 bits), u = al*(its high 16);
;; w is the low 32 bits of that sum plus s's carry, under 2^49
(define-syntax lh-mul-u (syntax-rules () [(_ al bl) (#3%fx* al (#3%fxsrl bl 16))]))
(define-syntax lh-mul-w
  (syntax-rules () [(_ al bl u) (#3%fx+ (#3%fx* al (#3%fxlogand bl #xffff)) (#3%fxsll (#3%fxlogand u #xffff) 16))]))
(define-syntax lh-mul-h
  (syntax-rules ()
    [(_ ah al bh bl u w)
     (lh-u32 (#3%fx+/wraparound (#3%fx+ (#3%fxsrl u 16) (#3%fxsrl w 32))
                                (#3%fx+/wraparound (#3%fx*/wraparound al bh) (#3%fx*/wraparound ah bl))))]))
;; the high half as a signed 32-bit value
(define-syntax lh-s32 (syntax-rules () [(_ h) (#3%fx- (#3%fxlogxor h #x80000000) #x80000000)]))
;; shifts by n in 0..63; at n = 0 the shifts by 32 give 0
(define-syntax lh-shl-h
  (syntax-rules ()
    [(_ ah al n) (if (#3%fx< n 32)
                     (lh-u32 (#3%fxlogor (#3%fxsll/wraparound ah n) (#3%fxsrl al (#3%fx- 32 n))))
                     (lh-u32 (#3%fxsll/wraparound al (#3%fx- n 32))))]))
(define-syntax lh-shl-l
  (syntax-rules () [(_ al n) (if (#3%fx< n 32) (lh-u32 (#3%fxsll/wraparound al n)) 0)]))
(define-syntax lh-shr-l
  (syntax-rules ()
    [(_ ah al n s) (if (#3%fx< n 32)
                       (lh-u32 (#3%fxlogor (#3%fxsrl al n) (#3%fxsll/wraparound ah (#3%fx- 32 n))))
                       (lh-u32 (#3%fxsra s (#3%fx- n 32))))]))
(define-syntax lh-shr-h
  (syntax-rules () [(_ s n) (lh-u32 (#3%fxsra s (#3%fxmin n 31)))]))
(define-syntax lh-ushr-l
  (syntax-rules ()
    [(_ ah al n) (if (#3%fx< n 32)
                     (lh-u32 (#3%fxlogor (#3%fxsrl al n) (#3%fxsll/wraparound ah (#3%fx- 32 n))))
                     (#3%fxsrl ah (#3%fx- n 32)))]))
(define-syntax lh-ushr-h
  (syntax-rules () [(_ ah n) (if (#3%fx< n 32) (#3%fxsrl ah n) 0)]))
(define-syntax lh=
  (syntax-rules () [(_ ah al bh bl) (and (#3%fx= ah bh) (#3%fx= al bl))]))
(define-syntax lh<
  (syntax-rules ()
    [(_ ah al bh bl) (or (#3%fx< (#3%fxlogxor ah #x80000000) (#3%fxlogxor bh #x80000000))
                         (and (#3%fx= ah bh) (#3%fx< al bl)))]))
(define-syntax lh-cmp
  (syntax-rules () [(_ ah al bh bl) (cond [(lh< ah al bh bl) -1] [(lh= ah al bh bl) 0] [else 1])]))
;; A guarded region of a Java method. The handler goes straight onto Chez's
;; (per-thread) handler stack, under a one-shot continuation: guard takes a full
;; call/cc and reads the continuation marks, about twice the cost. A clause whose
;; test holds escapes and returns (lambda () next) for the method's trampoline.
;; With no clause for it the exception goes on to the next handler from right
;; where it was raised, so nothing between is unwound and wound again. The
;; stack walk behind stack traces (reflect.ss raw-frames) crosses this
;; one-shot continuation because it splits each frame off first.
;; jolt enforces its stack limit at collections and throws
;; java.lang.StackOverflowError as an ex-info record of its own (rt.ss
;; jolt-stack-overflow!), which a Java handler, testing for a Java object,
;; let through and which ended the process. java-raise-of answers the Java
;; StackOverflowError for it, made once per raise (a handler that doesn't
;; match and the next one see the same object) with jolt's overflow flag
;; set, since the stack is still over the limit while it's built; anything
;; else as it is. The names are looked up once and are absent from a jolt
;; without them.
(define jolt-overflow-names
  (and (top-level-bound? 'jolt-throw-condition?) (top-level-bound? 'jolt-throw-condition-value)
       (list (top-level-value 'jolt-throw-condition?) (top-level-value 'jolt-throw-condition-value)
             (and (top-level-bound? 'jolt-stack-overflowing) (top-level-value 'jolt-stack-overflowing))
             (and (top-level-bound? 'jolt-stack-limit) (top-level-value 'jolt-stack-limit))
             (and (top-level-bound? 'sa-current-stack-bytes) (top-level-value 'sa-current-stack-bytes)))))
;; While an overflow unwinds, the stack is over the limit until enough frames
;; are gone, and jolt's check, run after every collection, would raise a new
;; StackOverflowError from each one on the way, building a trace over the
;; whole stack each time (a recursion through try/finally took minutes). Its
;; overflow flag stays set from the conversion while the thread's stack only
;; shrinks: champaca's procedure runs after each collection ahead of jolt's
;; check (install-overflow-recovery!), on every thread, and clears it when the
;; stack is back under the limit or has grown since the last collection (a new
;; recursion, which may have started from deep, before any collection saw the
;; stack shallow), so jolt's check right after it finds that one. HotSpot's
;; guard zones stay off while it unwinds the same way. A catch can't decide
;; it: the one that stops an overflow may still be deep.
(define (overflow-flag) (and jolt-overflow-names (caddr jolt-overflow-names)))
;; champaca's own work on a Java thread (compiling a method, registering a
;; class, defining one) runs with jolt's check held off: Chez's compiler
;; recurses as deep as a method is large (a 7,000-element array literal
;; passed a 1 MB bound), and an overflow from it would surface as the Java
;; program's StackOverflowError
(define champaca-work (make-thread-parameter #f))
(define (call-unbounded thunk)
  (let ([flag (overflow-flag)])
    (if flag (parameterize ([flag #t] [champaca-work #t]) (thunk)) (thunk))))
(define overflow-mark (make-thread-parameter #f))   ; the stack's size at the last look
(define (overflow-recovered!)
  (let ([flag (overflow-flag)])
    (when (and flag (flag) (not (champaca-work)))
      (let ([limit ((list-ref jolt-overflow-names 3))]
            [bytes ((list-ref jolt-overflow-names 4))]
            [mark (overflow-mark)])
        ;; grown by a quarter of the limit at least: while it unwinds, the
        ;; frames of a raise and its handler sit on the deep stack, and its
        ;; size wavers by them from one collection to the next
        (if (or (not limit) (not bytes) (< bytes limit) (and mark (> bytes (+ mark (max 65536 (quotient limit 4))))))
            (begin (flag #f) (overflow-mark #f))
            (overflow-mark bytes))))))
;; jolt keeps one procedure to run after each collection (its stack check,
;; sa-gc-install-after-rendezvous!); champaca's runs ahead of it. Once per
;; process; a jolt that sets a new stack limit later (jolt-stack-limit-set!)
;; puts its own back alone.
(define overflow-recovery-installed #f)
(define (install-overflow-recovery!)
  (when (and jolt-overflow-names (not overflow-recovery-installed)
             (top-level-bound? 'sa-after-rendezvous) (top-level-bound? 'sa-gc-install-after-rendezvous!))
    (let ([orig (top-level-value 'sa-after-rendezvous)])
      (when (procedure? orig)
        ((top-level-value 'sa-gc-install-after-rendezvous!) (lambda () (overflow-recovered!) (orig)))
        (set! overflow-recovery-installed #t)))))
(install-overflow-recovery!)
(define overflows-seen (make-weak-eq-hashtable))
;; jolt's throwable record by its uid: -v2 through 0.8.30, -v3 since 0.8.31
;; (its cause became mutable). Both keep the class name in field 0.
(define (jolt-stack-overflow? e)
  (and jolt-overflow-names (condition? e) ((car jolt-overflow-names) e)
       (let ([v ((cadr jolt-overflow-names) e)])
         (and (record? v)
              (memq (record-type-uid (record-rtd v)) '(jolt-ex-info-record-v2 jolt-ex-info-record-v3))
              (equal? ((record-accessor (record-rtd v) 0) v) "java.lang.StackOverflowError")))))
;; jolt's out-of-memory error is OutOfMemoryError to Java (arrays.ss)
(define (java-raise-of e)
  (cond [(jobj? e) e]
        [(jolt-out-of-memory e) => (lambda (message) (or (java-out-of-memory e message) e))]
        [(jolt-stack-overflow? e)
         (or (eq-hashtable-ref overflows-seen e #f)
             (let* ([flag (overflow-flag)]
                    [_ (when flag (flag #t) (overflow-mark ((list-ref jolt-overflow-names 4))))]   ; cleared by overflow-recovered!
                    [o (begin
                         (jinit "java/lang/StackOverflowError")
                         (let ([o (jnew "java/lang/StackOverflowError")])
                           ((method-proc "java/lang/StackOverflowError" "<init>()V") o)
                           o))])
               (eq-hashtable-set! overflows-seen e o)
               o))]
        [else e]))
;; A try region: body runs with a handler pushed on Chez's handler stack, and
;; the stack is put back as it was when call/1cc returns, whichever way: body's
;; value, or the thunk a caught exception escapes with (the trampoline calls
;; it outside the region). The escape alone would leave the stack as raise had
;; it, since raise calls the handler under a parameterize of the stack. Any
;; other way out runs through something that restores the stack itself: an
;; outer jtry, or the parameterize in Chez's with-exception-handler and guard
;; (run-guarded, thread-body, jolt's try). No dynamic-wind, which cost 7 of a
;; try entry's 18 ns.
(define-syntax jtry
  (syntax-rules ()
    [(_ e ([test next] ...) body)
     (let* ([old (#%$current-handler-stack)]
            [v (call/1cc
                (lambda (k)
                  (#%$current-handler-stack
                   (cons (lambda (raised)
                           (let ([e (java-raise-of raised)])
                             (cond [test (k (lambda () next))] ...
                                   [else (raise-continuable raised)])))
                         old))
                  body))])
       (#%$current-handler-stack old)
       v)]))
;; int shifts: a and the result are ints, so fixnums. ishl first drops the bits
;; that would leave the low 32, so the shift can't overflow a fixnum.
(define-syntax int-shl
  (syntax-rules ()
    [(_ a s) (let ([n (ix-and s 31)])
               (wrap-int (ix-sll (ix-and a (ix- (ix-sll 1 (ix- 32 n)) 1)) n)))]))
(define-syntax int-shr (syntax-rules () [(_ a s) (ix-sra a (ix-and s 31))]))
(define-syntax int-ushr
  (syntax-rules () [(_ a s) (wrap-int (ix-srl (ix-and a #xffffffff) (ix-and s 31)))]))
;; idiv and irem inline: the zero test, then the fixnum division. MIN_VALUE
;; / -1 is 2^31, which wrap-int turns back into MIN_VALUE as Java does. tag
;; and pc are codegen's site, as for array-ref's bounds-error.
(define (div-by-zero . site) (jthrow "java/lang/ArithmeticException" "/ by zero"))
(define-syntax int-div
  (syntax-rules ()
    [(_ a b tag pc) (let ([x a] [y b]) (if (ix= y 0) (div-by-zero tag pc) (wrap-int (ix-quotient x y))))]))
(define-syntax int-rem
  (syntax-rules ()
    [(_ a b tag pc) (let ([x a] [y b]) (if (ix= y 0) (div-by-zero tag pc) (ix-remainder x y)))]))

(define (f32 x)
  (let ([b (make-bytevector 4)])
    (bytevector-ieee-single-native-set! b 0 x)
    (bytevector-ieee-single-native-ref b 0)))

(define (idiv a b) (if (fx= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (i32 (quotient a b))))
(define (irem a b) (if (fx= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (remainder a b)))
(define (ldiv a b) (if (eqv? b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (let ([q (quotient a b)]) (if (fixnum? q) q (i64 q)))))
(define (lrem a b) (if (eqv? b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (remainder a b)))
(define (ishl a s) (i32 (ash a (fxlogand s 31))))
(define (ishr a s) (ash a (fx- 0 (fxlogand s 31))))
(define (iushr a s) (i32 (ash (logand a #xffffffff) (fx- 0 (fxlogand s 31)))))
(define (fx<> a b) (not (fx= a b)))
(define (i2b x) (fx- (fxlogxor (fxlogand x #xff) #x80) #x80))
(define (i2s x) (fx- (fxlogxor (fxlogand x #xffff) #x8000) #x8000))
;; copies between a Java byte[]'s vector and native memory, a byte at a
;; time through Chez's unchecked foreign-ref and foreign-set! (#3%): the
;; checked ones are procedure calls, measured 17 times slower, and every
;; byte a file, socket or zlib stream moves crosses here (spike 72). The
;; vector accesses stay checked.
(define (native->vector! p poff v voff n)
  (do ([i 0 (fx+ i 1)]) ((fx= i n))
    (vector-set! v (fx+ voff i) (i2b (#3%foreign-ref 'unsigned-8 p (fx+ poff i))))))
(define (vector->native! v voff p poff n)
  (do ([i 0 (fx+ i 1)]) ((fx= i n))
    (#3%foreign-set! 'unsigned-8 p (fx+ poff i) (fxlogand (vector-ref v (fx+ voff i)) #xff))))

(define (jfcmp a b nan-result)
  (cond [(or (nan? a) (nan? b)) nan-result] [(fl< a b) -1] [(fl> a b) 1] [else 0]))
;; Java's % on doubles and floats is C's fmod (JLS 15.17.3; HotSpot calls it):
;; a - b*q with q the quotient truncated, always exact, with a's sign even
;; when zero (-4.0 % 2.0 is -0.0), an infinite divisor giving a back. libm's
;; fmod does it (libSystem on macOS, libm.so.6 on glibc, where it isn't in
;; libc), about 10 ns a call; where it can't be loaded, exact arithmetic
;; gives the same answers at about 300 ns. The flonum a - b*trunc(a/b) it
;; replaces lost a zero's sign and went wrong once a/b passed 2^53.
(define (exact-frem a b)
  (cond [(or (nan? a) (nan? b) (infinite? a) (fl= b 0.0)) +nan.0]
        [(or (infinite? b) (fl= a 0.0)) a]
        [else (let* ([ea (exact a)] [eb (exact b)]
                     [r (inexact (- ea (* eb (truncate (/ ea eb)))))])
                (if (fl= r 0.0) (if (fl< a 0.0) -0.0 0.0) r))]))
(define libm-fmod
  (guard (e [#t #f])
    (load-shared-object (if (memq (machine-type) '(tarm64osx arm64osx ta6osx a6osx)) "libSystem.dylib" "libm.so.6"))
    (foreign-procedure "fmod" (double double) double)))
(define jfrem (or libm-fmod exact-frem))
(define (d2i x) (cond [(nan? x) 0] [(fl>= x 2147483647.0) 2147483647] [(fl<= x -2147483648.0) -2147483648]
                      [else (exact (fltruncate x))]))
(define (d2l x) (cond [(nan? x) 0] [(fl>= x 9223372036854775807.0) 9223372036854775807]
                      [(fl<= x -9223372036854775808.0) -9223372036854775808] [else (exact (fltruncate x))]))
;; String concatenation and record toString format a double or float with the
;; JDK's own Double.toString / Float.toString ("1.0E10", and 0.1f is "0.1").
(define (jdouble->string x)
  (jinit "java/lang/Double")
  (jstring->scheme ((method-proc "java/lang/Double" "toString(D)Ljava/lang/String;") x)))
(define (java-float->string x)
  (jinit "java/lang/Float")
  (jstring->scheme ((method-proc "java/lang/Float" "toString(F)Ljava/lang/String;") x)))
