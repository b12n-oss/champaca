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
(define (jolt-stack-overflow? e)
  (and jolt-overflow-names (condition? e) ((car jolt-overflow-names) e)
       (let ([v ((cadr jolt-overflow-names) e)])
         (and (record? v)
              (eq? (record-type-uid (record-rtd v)) 'jolt-ex-info-record-v2)
              (equal? ((record-accessor (record-rtd v) 0) v) "java.lang.StackOverflowError")))))
(define (java-raise-of e)
  (cond [(jobj? e) e]
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
(define-syntax jtry
  (syntax-rules ()
    [(_ e ([test next] ...) body)
     (call/1cc
      (lambda (k)
        (let* ([old (#%$current-handler-stack)]
               [new (cons (lambda (raised)
                            (let ([e (java-raise-of raised)])
                              (cond [test (k (lambda () next))] ...
                                    [else (raise-continuable raised)])))
                          old)])
          (dynamic-wind
           (lambda () (#%$current-handler-stack new))
           (lambda () body)
           (lambda () (#%$current-handler-stack old))))))]))
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

;; --- object model ------------------------------------------------------------

;; kind is 'class, 'interface, 'array or 'prim. fields maps "Decl.name" to a
;; slot index. vtable entries map "name(desc)" to the symbol whose top-level
;; value is the method procedure, a JIT stub until first call.
;; jolt's own Scheme defines jclass? and jclass-name (and other names these
;; files used to redefine, breaking jolt's float printing and Java interop
;; once champaca loaded), so this record spells its predicate and name
;; accessor differently. examples/jolt-after-init.clj checks jolt still works.
(define-record-type (jclass make-jclass ldk-jclass?)
  (fields (immutable name jclass-cname) super interfaces template vtable fields flags kind meta (mutable state) (mutable mirror))
  (sealed #t) (nongenerative champaca-jclass))
;; Sealed and nongenerative: the record type is then known when code using it
;; compiles, so Chez inlines the accessors and the type check. Otherwise each
;; access is a procedure call, about three times the cost.
;; lock is the object's monitor once something synchronizes on it (natives.ss
;; monitor-of). It costs no memory: Chez rounds a record up to 16 bytes, and
;; three fields fit the 32 two did.
(define-record-type jobj (fields cls slots (mutable lock))
  (protocol (lambda (new) (lambda (cls slots) (new cls slots #f))))
  (sealed #t) (nongenerative champaca-jobj))
;; An array's element descriptor is interned, so every String[] shares one
;; string and an aastore cell can compare descriptors with eq?. The lookup is
;; by the descriptor object first (one eq? hash for the literal an allocation
;; site passes each time), then by its text.
(define-record-type (jarr make-jarr* jarr?) (fields elem vec) (sealed #t) (nongenerative champaca-jarr))
(define elem-by-object (make-ephemeron-eq-hashtable))
(define elem-by-text (make-hashtable string-hash string=?))
(define (intern-elem e)
  (or (eq-hashtable-ref elem-by-object e #f)
      (let ([i (or (hashtable-ref elem-by-text e #f) (begin (hashtable-set! elem-by-text e e) e))])
        (eq-hashtable-set! elem-by-object e i)
        i)))
(define (make-jarr e v) (make-jarr* (intern-elem e) v))

(define classes (make-hashtable string-hash string=?))

(define (alist->table al)
  (let ([t (make-hashtable string-hash string=?)])
    (for-each (lambda (p) (hashtable-set! t (car p) (cdr p))) al)
    t))

;; The reflection metadata's strings, one copy each: a descriptor or a
;; name recurs across classes, and each registration's literals are its own
;; strings (a warm Spring Boot held 180k of them, 20 MB, before this).
(define meta-strings (make-hashtable string-hash string=?))
(define (intern-meta x)
  (cond [(string? x) (or (hashtable-ref meta-strings x #f) (begin (hashtable-set! meta-strings x x) x))]
        [(pair? x) (cons (intern-meta (car x)) (intern-meta (cdr x)))]
        [else x]))
;; meta is ((methods ...) (fields ...)), each entry (name desc access-flags)
;; in classfile order; reflection reads it and a member's slot is its index.
;; A vtable is keyed by symbols, "name(desc)" interned, so a call looks its
;; method up with eq? rather than hashing a string each time.
;; vtable-alist holds the changes to the superclass's vtable: a new or
;; overriding entry, or (key . #f) for one this class drops.
(define (register-class! name super interfaces template tables flags meta)
  ;; tables: (vtable delta . field layout), one argument so the call stays
  ;; under jolt-metrics' parameter limit
  (define vtable-alist (car tables))
  (define field-alist (cdr tables))
  (hashtable-set! classes name
                  (make-jclass name super interfaces template
                               (let ([t (if super (hashtable-copy (jclass-vtable (find-class super)) #t) (make-eq-hashtable))])
                                 (for-each (lambda (p)
                                             (let ([k (string->symbol (car p))])
                                               (if (cdr p) (eq-hashtable-set! t k (cdr p)) (eq-hashtable-delete! t k))))
                                           vtable-alist)
                                 t)
                               (alist->table field-alist) flags
                               (if (fxpositive? (fxlogand flags #x200)) 'interface 'class)
                               (intern-meta meta) 'loaded #f)))

(define prim-names
  '(("Z" . "boolean") ("B" . "byte") ("C" . "char") ("S" . "short") ("I" . "int")
    ("J" . "long") ("F" . "float") ("D" . "double") ("V" . "void")))

(define (prim-name? n) (exists (lambda (p) (string=? (cdr p) n)) prim-names))

(define (pseudo-class! name kind)
  (let ([c (make-jclass name (if (eq? kind 'array) "java/lang/Object" #f)
                        (if (eq? kind 'array) '("java/lang/Cloneable" "java/io/Serializable") '())
                        (vector) (make-eq-hashtable) (make-hashtable string-hash string=?)
                        #x411 kind '(() ()) 'initialized #f)])
    (hashtable-set! classes name c)
    c))

;; Classes load lazily: a miss asks jolt to load the classfile and register it.
(define (find-class name)
  (or (hashtable-ref classes name #f)
      (cond
        [(fx= 0 (string-length name)) (error 'champaca "empty class name")]
        [(char=? (string-ref name 0) #\[) (pseudo-class! name 'array)]
        [(prim-name? name) (pseudo-class! name 'prim)]
        ;; a class whose bytes are nowhere, or whose superclass or an
        ;; interface's are, is the JVM's NoClassDefFoundError for the code
        ;; that named it (an ldc, a catch clause's type, a descriptor); one
        ;; that is there but fails otherwise stays the error it is, which
        ;; names the cause (class-exists?, condition-text: natives.ss)
        [else (guard (e [(and (not (jobj? e)) (missing-class-of e name)) => jthrow-missing]
                        [(and (not (jobj? e)) (class-format-of e))
                         => (lambda (msg) (jthrow "java/lang/ClassFormatError" msg))]
                        [(and (not (jobj? e)) (linkage-error-of e))
                         => (lambda (x) (jthrow (car x) (cdr x)))])
                (call-unbounded (lambda () (load-hook name))))
              (or (hashtable-ref classes name #f) (error 'champaca "class not registered" name))])))

;; the class a failed load says isn't there: name itself when its bytes are
;; nowhere, else the one a "class not found: X" from the linker names
(define (missing-class-of e name)
  (if (not (class-exists? name)) name (class-not-found-in e)))
;; the rest of t after the first k in it, or #f
(define (text-after t k)
  (let ([n (string-length t)] [m (string-length k)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(string=? (substring t i (fx+ i m)) k) (substring t (fx+ i m) n)]
            [else (loop (fx+ i 1))]))))
;; the index of the first c in s, or #f
(define (char-index-of s c)
  (let ([n (string-length s)])
    (let find ([k 0])
      (cond [(fx= k n) #f] [(char=? (string-ref s k) c) k] [else (find (fx+ k 1))]))))
;; the X of a linker's "class not found: X" in a failure's text, or #f
(define (class-not-found-in e)
  (text-after (condition-text e) "class not found: "))
;; HotSpot's ClassFormatError text from a load whose class file (the class
;; named or one it needs) isn't one: link.clj normalize's "class format
;; error in X: <text>", or #f
(define (class-format-of e)
  (let ([r (text-after (condition-text e) "class format error in ")])
    (and r (text-after r ": "))))
;; the same from generated code: an instruction naming such a class (codegen)
(define (jthrow-class-format name msg) (jthrow "java/lang/ClassFormatError" msg))
;; a class the JVM refuses to define (link.clj check-definition!): "linkage
;; error in X: <error class>|<text>" -> (error-class . text), or #f
(define (linkage-error-of e)
  (let* ([r (text-after (condition-text e) "linkage error in ")]
         [rest (and r (text-after r ": "))]
         [bar (and rest (char-index-of rest #\|))])
    (and bar (cons (substring rest 0 bar) (substring rest (fx+ bar 1) (string-length rest))))))
(define (jthrow-linkage name err msg) (jthrow err msg))
;; the JVM's error for a class nothing has: the slashed name, caused by a
;; ClassNotFoundException with the dotted one; or the error the class's
;; loader threw resolving it, every time, as JVMS 5.4.3 has a failed
;; resolution fail the same way again (natives.ss ask-resolving-loader)
(define resolution-errors (make-hashtable string-hash string=?))
;; An array of a missing class is the array's NoClassDefFoundError with the
;; element's cause, and the element's loader error if one was kept
(define (jthrow-missing name)
  (define elem
    (let loop ([i 0])
      (cond [(fx= i (string-length name)) name]
            [(char=? (string-ref name i) #\[) (loop (fx+ i 1))]
            [(and (fx> i 0) (char=? (string-ref name i) #\L)) (substring name (fx+ i 1) (fx- (string-length name) 1))]
            [else name])))
  (let ([le (hashtable-ref resolution-errors elem #f)])
    (when le (raise le)))
  (let ([e (construct "java/lang/NoClassDefFoundError" "(Ljava/lang/String;)V" (jstring name))]
        [cnfe (construct "java/lang/ClassNotFoundException" "(Ljava/lang/String;)V" (jstring (slashes->dots elem)))])
    ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;") e cnfe)
    (raise e)))
;; A call to a method its class doesn't have, which a JVM resolves only
;; when the call runs (JVMS 5.4.3.3): NoSuchMethodError with HotSpot's text,
;; 'ret Class.name(param, param)' in Java's spelling
;; 'int Cls.m(java.lang.String, long[])', as HotSpot names a method in its
;; linkage errors
;; a type descriptor in Java's spelling: "int", "java.lang.String[]"
(define (type-external d)
  (cond [(char=? (string-ref d 0) #\[) (string-append (type-external (substring d 1 (string-length d))) "[]")]
        [(assoc d prim-names) => cdr]
        [else (slashes->dots (desc->class-name d))]))
(define (method-external cname name desc)
  (define external type-external)
  (string-append "'" (external (desc-ret desc)) " " (slashes->dots cname) "." name "("
                 (let loop ([ps (desc-args desc)])
                   (cond [(null? ps) ""]
                         [(null? (cdr ps)) (external (car ps))]
                         [else (string-append (external (car ps)) ", " (loop (cdr ps)))]))
                 ")'"))
(define (jthrow-no-method cname name desc)
  (jthrow "java/lang/NoSuchMethodError" (method-external cname name desc)))
;; a call whose instruction doesn't match the static-ness of the method it
;; resolves to (link.clj check-kind): what as HotSpot's LinkResolver says it
(define (jthrow-icce what cname name desc)
  (jthrow "java/lang/IncompatibleClassChangeError" (string-append what " " (method-external cname name desc))))
(define (method-proc cname key)
  (top-level-value (string->symbol (string-append cname "." key))))

;; <clinit> runs on first active use, superclass first. While it runs the
;; class is 'initializing: the same thread goes ahead (a recursive request),
;; another thread waits for it to finish (JVMS 5.5). A <clinit> that throws
;; leaves the class 'erroneous, and any later use throws NoClassDefFoundError.
(define init-owners (make-weak-eq-hashtable))
(define init-errors (make-weak-eq-hashtable))   ; class -> (first failure . thread name)
(define init-waiters 0)

(define (jinit name)
  (let ([c (find-class name)])
    (unless (eq? (jclass-state c) 'initialized)
      (jinit-slow c name))))

;; JVMS 5.5 step 7: a class's initialization, after its superclass's,
;; initializes each superinterface, direct or not, that declares a
;; non-abstract, non-static method, walking each interfaces array in order
;; and an interface's superinterfaces before it. One with only abstract or
;; static methods waits for a use of its own.
(define (init-default-interfaces c)
  (let walk ([is (jclass-interfaces c)] [seen '()])
    (if (null? is)
        seen
        (let ([n (car is)])
          (if (member n seen)
              (walk (cdr is) seen)
              (let* ([i (find-class n)]
                     [seen (walk (jclass-interfaces i) (cons n seen))])
                (when (exists (lambda (m) (fxzero? (fxlogand (caddr m) #x408))) (car (jclass-meta i)))
                  (jinit n))
                (walk (cdr is) seen)))))))

;; the current Java thread's name, "main" outside any
(define (init-thread-name)
  (if current-jthread
      (jstring->scheme ((method-proc "java/lang/Thread" "getName()Ljava/lang/String;") current-jthread))
      "main"))

;; c's initializer failed with e: the failure and its thread are kept for
;; the NoClassDefFoundError of later uses; answers what reaches the caller,
;; anything but an Error wrapped in an ExceptionInInitializerError (JVMS
;; 5.5 step 11)
(define (init-failure! c e)
  (when (jobj? e)
    (eq-hashtable-set! init-errors c (cons e (init-thread-name))))
  (if (and (jobj? e) (not (jinstance? e "java/lang/Error")))
      (let ([w (begin (jinit "java/lang/ExceptionInInitializerError")
                      (jnew "java/lang/ExceptionInInitializerError"))])
        ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/Throwable;)V") w e)
        w)
      e))

;; the superclass, the interfaces with defaults, then c's own <clinit>
(define (run-initializer! c name)
  (when (jclass-super c) (jinit (jclass-super c)))
  (unless (eq? (jclass-kind c) 'interface) (init-default-interfaces c))
  (let ([sym (string->symbol (string-append name ".<clinit>()V"))])
    (when (global-bound? sym) ((top-level-value sym)))))

;; another thread is initializing c: wait until it's done
(define (wait-for-initializer! c)
  (set! init-waiters (fx+ init-waiters 1))
  (gil-block! (lambda () (not (eq? (jclass-state c) 'initializing))) #f)
  (set! init-waiters (fx- init-waiters 1)))

;; the NoClassDefFoundError for a use of c after its initializer failed,
;; with HotSpot's cause: an ExceptionInInitializerError naming the first
;; failure and its thread
(define (initializer-error c name)
  (let ([first (eq-hashtable-ref init-errors c #f)]
        [e (begin (jinit "java/lang/NoClassDefFoundError") (jnew "java/lang/NoClassDefFoundError"))])
    ((method-proc "java/lang/NoClassDefFoundError" "<init>(Ljava/lang/String;)V")
     e (jstring (string-append "Could not initialize class " (slashes->dots name))))
    (when first
      (let ([w (begin (jinit "java/lang/ExceptionInInitializerError") (jnew "java/lang/ExceptionInInitializerError"))])
        ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/String;)V")
         w (jstring (string-append "Exception " (jstring-of (car first)) " [in thread \"" (cdr first) "\"]")))
        ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;") e w)))
    e))

(define (jinit-slow c name)
  (case (jclass-state c)
    [(loaded)
     (jclass-state-set! c 'initializing)
     (eq-hashtable-set! init-owners c (self))
     (let ([done (lambda (state)
                   (jclass-state-set! c state)
                   (eq-hashtable-delete! init-owners c)
                   (when (fx> init-waiters 0) (gil-poke!)))])
       (guard (e [#t (done 'erroneous) (raise (init-failure! c e))])
         (run-initializer! c name))
       (done 'initialized))]
    [(initializing)
     (unless (eq? (eq-hashtable-ref init-owners c #f) (self))
       (wait-for-initializer! c)
       (jinit-slow c name))]
    [(erroneous) (raise (initializer-error c name))]
    [else (void)]))

(define (jnew name)
  (let ([c (find-class name)])
    (make-jobj c (vector-copy (jclass-template c)))))

(define (jfield-index cname fname)
  (let loop ([c (find-class cname)])
    (or (hashtable-ref (jclass-fields c) (string-append (jclass-cname c) "." fname) #f)
        (and (jclass-super c) (loop (find-class (jclass-super c))))
        (error 'champaca "no such field" cname fname))))

;; A Throwable's backtrace field holds its raw frames (natives.ss), the one
;; Scheme value a Java field keeps. To Java it is an opaque java.lang.Object,
;; as HotSpot's own backtrace is, so reflection reading the field gets
;; getClass, hashCode and toString and nothing else.
(define-record-type backtrace (fields frames) (sealed #t) (nongenerative champaca-backtrace))

(define (class-name-of x)
  (cond [(jobj? x) (jclass-cname (jobj-cls x))]
        [(jarr? x) (string-append "[" (jarr-elem x))]
        [(backtrace? x) "java/lang/Object"]
        [else (error 'champaca "not a Java value" x)]))

(define (class-of x) (find-class (class-name-of x)))

(define (subclass? cname target)
  (and cname
       (or (string=? cname target)
           (let ([c (find-class cname)])
             (or (subclass? (jclass-super c) target)
                 (exists (lambda (i) (subclass? i target)) (jclass-interfaces c)))))))

;; elem descriptors: "I", "Ljava/lang/String;", "[I" ...
(define (desc->class-name d)
  (case (string-ref d 0)
    [(#\L) (substring d 1 (fx- (string-length d) 1))]
    [(#\[) d]
    [else (cdr (assoc d prim-names))]))

(define (ref-desc? d) (memv (string-ref d 0) '(#\L #\[)))

(define (class-assignable? from to)
  (cond [(prim-name? from) (string=? from to)]
        [(string=? to "java/lang/Object") #t]
        [(char=? (string-ref from 0) #\[)
         (or (and (member to '("java/lang/Cloneable" "java/io/Serializable")) #t)
             (and (char=? (string-ref to 0) #\[)
                  (let ([fe (substring from 1 (string-length from))]
                        [te (substring to 1 (string-length to))])
                    (or (string=? fe te)
                        (and (ref-desc? fe) (ref-desc? te)
                             (class-assignable? (desc->class-name fe) (desc->class-name te)))))))]
        [else (subclass? from to)]))

(define (jinstance? x target)
  (and (or (jobj? x) (jarr? x)) (class-assignable? (class-name-of x) target)))

(define (nn x) (if x x (jthrow "java/lang/NullPointerException" #f)))

;; An NPE that generated code raises carries its site: the method's tag,
;; Class.name(desc), which each compiled method binds once as npe-tag, and
;; the bci. Both appear in the failing branch only, so the fast path pays
;; nothing. getExtendedNPEMessage (natives.ss) builds the
;; JVM's message from the site's bytecode. Any other NPE has no site and
;; keeps a null message.
(define npe-sites (make-weak-eq-hashtable))
(define (npe-at tag pc)
  (jinit "java/lang/NullPointerException")
  (let ([e (jnew "java/lang/NullPointerException")])
    ((method-proc "java/lang/NullPointerException" "<init>()V") e)
    (eq-hashtable-set! npe-sites e (cons tag pc))
    (raise e)))
(define-syntax nn-at
  (syntax-rules () [(_ x tag pc) (let ([o x]) (if o o (npe-at tag pc)))]))
(define (jathrow-at x tag pc) (raise (nn-at x tag pc)))

(define (jcheckcast x target . site)   ; site: codegen's tag and pc, for the trace
  (if (or (not x) (jinstance? x target))
      x
      (jthrow "java/lang/ClassCastException" (class-cast-message (class-name-of x) target))))

;; Generated code's instanceof and checkcast: a per-site cell holding the
;; last object class seen and the answer for it, since jinstance? compares
;; class names and walks the hierarchy.
(define-syntax jinstance-ic?
  (syntax-rules ()
    [(_ ic x target)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jinstance-miss ic o target)))]))
;; A site that sees many classes (Clojure's RT.seq, a compiler walking mixed
;; collections) misses its one-class cell most of the time, and jinstance?
;; then walks the class's whole hierarchy, every interface included, a
;; string comparison at each. So each class remembers its answers: target
;; string (a site's literal, compared by identity) -> #t or #f. The table per
;; class is found by identity too; both are weak, so neither keeps a class or
;; a unit's literals alive.
(define instance-answers (make-weak-eq-hashtable))
(define (class-instance-of? cls target)
  (let* ([t (or (eq-hashtable-ref instance-answers cls #f)
                (let ([t (make-weak-eq-hashtable)]) (eq-hashtable-set! instance-answers cls t) t))]
         [a (eq-hashtable-ref t target 'unknown)])
    (if (eq? a 'unknown)
        (let ([r (and (class-assignable? (jclass-cname cls) target) #t)])
          (eq-hashtable-set! t target r)
          r)
        a)))
(define (jinstance-miss ic o target)
  (if (jobj? o)
      (let ([r (class-instance-of? (jobj-cls o) target)])
        (set-car! ic (jobj-cls o)) (set-cdr! ic r)
        r)
      (jinstance? o target)))
(define-syntax jcheckcast-ic
  (syntax-rules ()
    [(_ ic x target) (jcheckcast-ic ic x target #f -1)]
    [(_ ic x target tag pc)
     (let ([o x])
       (if (or (not o) (and (jobj? o) (eq? (jobj-cls o) (car ic)) (cdr ic)))
           o
           (begin (unless (jinstance-miss ic o target) (jcheckcast o target tag pc)) o)))]))

(define (jslot x i) (vector-ref (jobj-slots (nn x)) i))
(define (jslot-set! x i v) (vector-set! (jobj-slots (nn x)) i v))

;; Virtual and interface dispatch: one hashtable probe on the receiver's class.
;; Arrays dispatch through Object's vtable, except clone.
(define (array-clone a) (make-jarr* (jarr-elem a) (#3%vector-copy (jarr-vec a))))
;; key is a symbol in generated code; the runtime's own callers pass strings.
(define clone-key (string->symbol "clone()Ljava/lang/Object;"))
(define (jvirt x key)
  (let ([x (nn x)] [k (if (symbol? key) key (string->symbol key))])
    (if (jobj? x)
        (let ([sym (eq-hashtable-ref (jclass-vtable (jobj-cls x)) k #f)])
          (if sym (top-level-value sym) (no-such-virtual (jobj-cls x) k)))
        (cond
          [(not (or (jarr? x) (backtrace? x))) (error 'champaca "virtual call on something that isn't a Java value" x (symbol->string k))]
          [(and (eq? k clone-key) (jarr? x)) array-clone]
          [else (let* ([c (find-class "java/lang/Object")] [sym (eq-hashtable-ref (jclass-vtable c) k #f)])
                  (if sym (top-level-value sym) (no-such-virtual c k)))]))))
;; a vtable miss is an abstract method nothing implemented; the message is
;; the JVM's for a reabstracted default (its other AME wordings differ)
(define (no-such-virtual c k)
  (jthrow "java/lang/AbstractMethodError"
          (string-append "Method " (slashes->dots (jclass-cname c)) "." (symbol->string k) " is abstract")))

;; the vtable entry for conflicting default methods (link.clj link)
(define (icce-stub msg)
  (lambda args (jthrow "java/lang/IncompatibleClassChangeError" msg)))

;; An inline cache: each virtual call site in generated code has a pair,
;; (class . procedure), for the receiver class it saw last, so a hit is one
;; eq? test and no lookup (top-level-value alone costs about 14 ns). A stub
;; is never cached: its method isn't compiled yet, and the compiled
;; procedure that replaces it is what the site should keep.
(define-syntax jvirt-ic
  (syntax-rules ()
    [(_ ic x key tag pc)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jvirt-miss ic o key tag pc)))]))
(define (jvirt-miss ic o key tag pc)
  (let ([proc (jvirt (or o (npe-at tag pc)) key)])
    (when (and (jobj? o) (not (eq-hashtable-contains? stub-procs proc)))
      (set-car! ic (jobj-cls o))
      (set-cdr! ic proc))
    proc))

;; Selection for a call whose resolved method is package-private (JVMS
;; 5.4.5). The vtable is keyed by name and descriptor, which is right when
;; the resolved method is public or protected: any method of that name and
;; descriptor overrides it, so the nearest one wins. A package-private mA is
;; overridden only by a method in its own run-time package (package and
;; defining loader), or by one overriding a method that overrides mA
;; (transitively, through a public or protected one between). So the
;; classes from just below the resolved method's down to the receiver's are
;; walked in that order, keeping every overriding declaration as an anchor
;; for the ones below it; the deepest overrider is selected. Generated code
;; reaches this through jvirt-pp-ic on a miss; reflection and method handles
;; through virtual-target.
(define (own-method-flags c name desc)
  (let loop ([ms (car (jclass-meta c))])
    (cond [(null? ms) #f]
          [(and (string=? name (caar ms)) (string=? desc (cadar ms))) (caddar ms)]
          [else (loop (cdr ms))])))
;; same-runtime-package? (package and defining loader) is reflect.ss's, for nest hosts
(define (package-private-selection rc dc name desc)
  ;; rc the receiver's class, dc the resolved method's; #f when rc isn't
  ;; below dc (dispatch then goes by the vtable)
  (let ([below (let loop ([c rc] [acc '()])
                 (cond [(eq? c dc) acc]
                       [(jclass-super c) (loop (find-class (jclass-super c)) (cons c acc))]
                       [else #f]))])
    (and below
         (let loop ([cs below] [anchors (list (cons dc (or (own-method-flags dc name desc) 0)))] [sel dc])
           (if (null? cs)
               sel
               (let* ([c (car cs)] [f (own-method-flags c name desc)])
                 (if (and f (fxzero? (fxlogand f #x000a))   ; neither private nor static
                          (exists (lambda (a) (or (fxpositive? (fxlogand (cdr a) #x0005))
                                                  (same-runtime-package? c (car a))))
                                  anchors))
                     (loop (cdr cs) (cons (cons c f) anchors) c)
                     (loop (cdr cs) anchors sel))))))))
(define (package-private-select o dc key)
  (let* ([i (let find ([i 0]) (if (char=? (string-ref key i) #\() i (find (fx+ i 1))))]
         [sel (and (jobj? o) (package-private-selection (jobj-cls o) dc (substring key 0 i) (substring key i (string-length key))))])
    (cond [(not sel) (jvirt o key)]
          ;; a selected method with no procedure (abstract, and the runtime
          ;; doesn't override it, as natives.ss does VarHandle's) is
          ;; AbstractMethodError, as for any abstract selection
          [(global-bound? (string->symbol (string-append (jclass-cname sel) "." key)))
           (method-proc (jclass-cname sel) key)]
          [else (no-such-virtual (jobj-cls o) (string->symbol key))])))
;; The selection depends on the receiver's class, the resolved method's and
;; the key alone, so it is kept per receiver class: a call site whose
;; receivers alternate (HashMap.putVal's newNode, with HashMap and
;; LinkedHashMap) walks once per class, not once per change. A stub isn't
;; kept, as the inline caches don't keep one.
(define package-private-selections (make-weak-eq-hashtable))   ; class -> eq table key symbol -> ((dc . proc) ...)
(define (package-private-target o dc key)
  (if (not (jobj? o))
      (jvirt o key)
      (let* ([rc (jobj-cls o)] [k (if (symbol? key) key (string->symbol key))]
             [t (or (eq-hashtable-ref package-private-selections rc #f)
                    (let ([t (make-eq-hashtable)]) (eq-hashtable-set! package-private-selections rc t) t))]
             [hit (assq dc (eq-hashtable-ref t k '()))])
        (if hit
            (cdr hit)
            (let ([proc (package-private-select o dc (if (string? key) key (symbol->string key)))])
              (unless (eq-hashtable-contains? stub-procs proc)
                (eq-hashtable-set! t k (cons (cons dc proc) (eq-hashtable-ref t k '()))))
              proc)))))
;; a reflected or looked-up instance method's target on receiver o: a private
;; one is its own (it overrides nothing and nothing overrides it), a
;; package-private one by the rule above, any other through the vtable
(define (virtual-target o dcname key flags)
  (cond [(fxpositive? (fxlogand flags #x0002)) (nn o) (method-proc dcname key)]
        [(fxzero? (fxlogand flags #x0005)) (package-private-target (nn o) (find-class dcname) key)]
        [else (jvirt o key)]))
;; generated code's call of a resolved package-private method: an inline
;; cache as jvirt-ic's, filled by the selection
(define-syntax jvirt-pp-ic
  (syntax-rules ()
    [(_ ic x key dc tag pc)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jvirt-pp-miss ic o key dc tag pc)))]))
(define (jvirt-pp-miss ic o key dc tag pc)
  (let ([proc (package-private-target (or o (npe-at tag pc)) (symbol-class dc) key)])
    (when (and (jobj? o) (not (eq-hashtable-contains? stub-procs proc)))
      (set-car! ic (jobj-cls o))
      (set-cdr! ic proc))
    proc))

;; Generated code names classes by symbol: the class is found through an eq
;; table instead of hashing its name on every new and every initialization
;; check.
(define class-by-symbol (make-eq-hashtable))
(define (symbol-class s)
  (or (eq-hashtable-ref class-by-symbol s #f)
      (let ([c (find-class (symbol->string s))]) (eq-hashtable-set! class-by-symbol s c) c)))
(define (jinit-sym s)
  (let ([c (symbol-class s)])
    (unless (eq? (jclass-state c) 'initialized) (jinit-slow c (jclass-cname c)))))
(define (jnew-sym s)
  (let ([c (symbol-class s)]) (make-jobj c (vector-copy (jclass-template c)))))
;; The same from generated code, through a per-site cell that holds the class
;; once it is initialized, which it then stays: the check becomes one car.
;; The trailing tag and pc (codegen's site) ride into the slow path's call,
;; where a stack trace of an initializer's failure reads the pc (reflect.ss)
(define-syntax jinit-ic
  (syntax-rules ()
    [(_ cell s) (unless (car cell) (jinit-cell! cell s))]
    [(_ cell s tag pc) (unless (car cell) (jinit-cell! cell s tag pc))]))
(define (jinit-cell! cell s . site)
  (jinit-sym s)
  (let ([c (symbol-class s)])
    (when (eq? (jclass-state c) 'initialized) (set-car! cell c))))
(define-syntax jnew-ic
  (syntax-rules ()
    [(_ cell s) (jnew-ic cell s #f -1)]
    [(_ cell s tag pc) (let ([c (car cell)])
                         (if c
                             (make-jobj c (vector-copy (jclass-template c)))
                             (begin (jinit-cell! cell s tag pc) (jnew-sym s))))]))

;; --- arrays -------------------------------------------------------------------

(define (jnewarray elem n default)
  (if (fx< n 0)
      (jthrow "java/lang/NegativeArraySizeException" (number->string n))
      (make-jarr elem (make-vector n default))))
;; generated code's newarray and anewarray, with elem already interned
(define (jnewarray-interned elem n default)
  (if (fx< n 0)
      (jthrow "java/lang/NegativeArraySizeException" (number->string n))
      (make-jarr* elem (make-vector n default))))

(define (elem-default d)
  (case (string-ref d 0) [(#\L #\[) #f] [(#\F #\D) 0.0] [else 0]))

(define (jmultiarray desc dims)
  ;; desc is the full array type, e.g. "[[I"; every dimension is checked
  ;; first, as multianewarray does, so new int[0][-1] throws too
  (for-each (lambda (n) (when (fx< n 0) (jthrow "java/lang/NegativeArraySizeException" (number->string n)))) dims)
  (jmultiarray* desc dims))
(define (jmultiarray* desc dims)
  (let ([elem (substring desc 1 (string-length desc))])
    (if (null? (cdr dims))
        (jnewarray elem (car dims) (elem-default elem))
        (let ([a (jnewarray elem (car dims) #f)])
          (do ([i 0 (fx+ i 1)]) ((fx= i (car dims)) a)
            (vector-set! (jarr-vec a) i (jmultiarray* elem (cdr dims))))))))

(define (bounds-error i v . site)   ; site: codegen's tag and pc, for the trace
  (jthrow "java/lang/ArrayIndexOutOfBoundsException"
          (string-append "Index " (number->string i) " out of bounds for length "
                         (number->string (vector-length v)))))

(define (jaload a i)
  (let ([v (jarr-vec (nn a))])
    (if (and (fx>= i 0) (fx< i (vector-length v))) (vector-ref v i) (bounds-error i v))))

(define (jastore a i x)
  (let ([v (jarr-vec (nn a))])
    (if (and (fx>= i 0) (fx< i (vector-length v))) (vector-set! v i x) (bounds-error i v))))

(define (jarraylength a) (vector-length (jarr-vec (nn a))))

;; Generated code's field and array access, inline: a call to jslot or jaload
;; costs more than the access itself. A null object throws through npe-at
;; with the site's method tag and bci.
(define-syntax field-ref
  (syntax-rules () [(_ x i tag pc) (let ([o x]) (if o (vector-ref (jobj-slots o) i) (npe-at tag pc)))]))
(define-syntax field-set!
  (syntax-rules () [(_ x i v tag pc) (let ([o x]) (if o (vector-set! (jobj-slots o) i v) (npe-at tag pc)))]))
;; jarr-vec is always a vector and the index has just been checked against
;; it, so the vector access itself can skip Chez's checks (#3%): safe code
;; checks the type and bounds again, which more than doubles a byte loop.
(define-syntax array-ref
  (syntax-rules ()
    [(_ a i tag pc) (let ([arr a] [j i])
                    (if arr
                        (let ([v (jarr-vec arr)])
                          (if (fx< -1 j (#3%vector-length v)) (#3%vector-ref v j) (bounds-error j v tag pc)))
                        (npe-at tag pc)))]))
;; A loop's fast copy (codegen's versioning) took the array's vector and
;; length once before the loop, which checked it was an array, so an access
;; checks the index alone.
(define-syntax vec-ref
  (syntax-rules () [(_ v n i tag pc) (let ([j i]) (if (fx< -1 j n) (#3%vector-ref v j) (bounds-error j v tag pc)))]))
(define-syntax vec-set!
  (syntax-rules () [(_ v n i x tag pc) (let ([j i] [y x]) (if (fx< -1 j n) (#3%vector-set! v j y) (bounds-error j v tag pc)))]))
(define-syntax array-set!
  (syntax-rules ()
    [(_ a i x tag pc) (let ([arr a] [j i])
                      (if arr
                          (let ([v (jarr-vec arr)])
                            (if (fx< -1 j (#3%vector-length v)) (#3%vector-set! v j x) (bounds-error j v tag pc)))
                          (npe-at tag pc)))]))
;; aastore: a reference array checks a non-null value against its element
;; type after the index, as the JVM does. Each site has a cell: its car is the
;; (interned) element descriptor it last stored into, its cdr #t when that is
;; Object (anything goes) or else the one or two value keys that passed, as a
;; key or a pair of keys. A key is an object's class or an array's element
;; descriptor, so a repeat store costs a few eq? tests.
(define-syntax store-key
  (syntax-rules () [(_ o) (cond [(jobj? o) (jobj-cls o)] [(jarr? o) (jarr-elem o)] [else #f])]))
(define-syntax array-store!
  (syntax-rules ()
    [(_ ic a i x tag pc)
     (let ([arr a] [j i] [o x])
       (if arr
           (let ([v (jarr-vec arr)])
             (if (fx< -1 j (#3%vector-length v))
                 (begin
                   (when (and o (not (and (eq? (jarr-elem arr) (car ic))
                                          (let ([c (cdr ic)])
                                            (or (eq? c #t)
                                                (let ([k (store-key o)])
                                                  (or (eq? c k)
                                                      (and (pair? c) (or (eq? (car c) k) (eq? (cdr c) k))))))))))
                     (array-store-miss ic arr o tag pc))
                   (#3%vector-set! v j o))
                 (bounds-error j v tag pc)))
           (npe-at tag pc)))]))
(define (array-store-ok? arr o)
  (let ([e (jarr-elem arr)])
    (or (string=? e "Ljava/lang/Object;") (jinstance? o (desc->class-name e)))))
;; The cell's car is cleared while it changes, so no store in between can
;; pair one element type with another's keys.
(define (array-store-miss ic arr o . site)   ; site: codegen's tag and pc, for the trace
  (unless (array-store-ok? arr o)
    (jthrow "java/lang/ArrayStoreException" (slashes->dots (class-name-of o))))
  (let ([e (jarr-elem arr)] [k (store-key o)] [c (cdr ic)])
    (cond [(string=? e "Ljava/lang/Object;") (set-car! ic #f) (set-cdr! ic #t) (set-car! ic e)]
          [(not k)]
          [(and (eq? (car ic) e) (pair? c)) (set-cdr! ic (cons k (car c)))]
          [(and (eq? (car ic) e) c (not (eq? c #t))) (set-cdr! ic (cons k c))]
          [else (set-car! ic #f) (set-cdr! ic k) (set-car! ic e)])))
(define-syntax array-length
  (syntax-rules () [(_ a tag pc) (let ([arr a]) (if arr (#3%vector-length (jarr-vec arr)) (npe-at tag pc)))]))
;; The same four as calls, for a method over codegen's ic-vector-limit. Every
;; inline copy carries Chez's sealed-record checks for the array (and, for a
;; store, the value), and the JDK's locale tables are methods of thousands of
;; array stores: FormatData.getContents compiled to 959 KB in 5.2 s inline and
;; to 374 KB in 0.8 s through these. Such methods run once, and a giant
;; method's loops aren't versioned anyway, so the call costs nothing that
;; matters.
(define (big-array-ref a i tag pc) (array-ref a i tag pc))
(define (big-array-set! a i x tag pc) (array-set! a i x tag pc))
(define (big-array-store! ic a i x tag pc) (array-store! ic a i x tag pc))
(define (big-array-length a tag pc) (array-length a tag pc))
(define-syntax wrap-byte (syntax-rules () [(_ x) (ix- (ix-xor (ix-and x #xff) #x80) #x80)]))
(define-syntax wrap-short (syntax-rules () [(_ x) (ix- (ix-xor (ix-and x #xffff) #x8000) #x8000)]))

(define (scheme-list->jarray elem xs) (make-jarr elem (list->vector xs)))

;; --- exceptions ------------------------------------------------------------
;; A Java throw is a Scheme raise of the jobj. Catch clauses are jtry
;; clauses that test jinstance?.

(define (jthrow cname msg)
  (jinit cname)
  (let ([e (jnew cname)])
    ((method-proc cname "<init>(Ljava/lang/String;)V") e (and msg (jstring msg)))
    (raise e)))

(define (jathrow x) (raise (nn x)))

;; --- Class mirrors ---------------------------------------------------------------

(define mirror->class (make-weak-eq-hashtable))

(define (jmirror c)
  (or (jclass-mirror c)
      (let ([m (jnew "java/lang/Class")])
        (jclass-mirror-set! c m)
        (eq-hashtable-set! mirror->class m c)
        ;; fields the VM sets directly, without running Class's constructor
        (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "primitive")
                     (if (eq? (jclass-kind c) 'prim) 1 0))
        (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "modifiers")
                     (class-modifiers c))
        (set-mirror-module! m c)
        (index-mirror! m c)
        ;; a class a loader defined reports that loader, a classpath class the
        ;; application loader (an array its element's), a JDK class null
        (set-mirror-loader! m c)
        (when (eq? (jclass-kind c) 'array)
          (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "componentType")
                       (jmirror (find-class (desc->class-name (substring (jclass-cname c) 1 (string-length (jclass-cname c))))))))
        m)))

;; Class.getModifiers: access flags minus ACC_SUPER (0x20), which shares its
;; bit with Modifier.SYNCHRONIZED; the JVM masks it off the same way. A member
;; class answers the flags from its own InnerClasses entry instead, which is
;; where static, private and protected live (the class's meta, reflect.ss).
;; An array is abstract and final with its innermost element's public,
;; private or protected, as HotSpot's ObjArrayKlass computes it; a primitive
;; array is public.
(define (class-modifiers c)
  (if (eq? (jclass-kind c) 'array)
      (let ([e (element-name c)])
        (fxior #x410 (if (prim-name? e) 1 (fxlogand (class-modifiers (find-class e)) 7))))
      (let* ([m (jclass-meta c)]
             [inner (and (pair? (cddr m)) (fx> (length (caddr m)) 2) (caddr (caddr m)))])
        (fxlogand (if inner (caddr inner) (jclass-flags c)) #x761f))))

;; --- identity hash ------------------------------------------------------------

(define id-hashes (make-weak-eq-hashtable))
(define id-counter 0)
(define (identity-hash x)
  (if (not x) 0
      (or (eq-hashtable-ref id-hashes x #f)
          (begin (set! id-counter (fx+ id-counter 1))
                 (let ([h (fxlogand (fx* id-counter 1103515245) #x7fffffff)])
                   (eq-hashtable-set! id-hashes x h) h)))))

;; --- native methods -------------------------------------------------------------
;; Native methods are looked up by global name when their class registers.
;; A missing one becomes a stub that fails loudly with its own name, so a run
;; reports exactly which native it needed next.

(define natives (make-hashtable string-hash string=?))
(define (define-native! name proc) (hashtable-set! natives name proc))
(define native-rules '())   ; list of (pred . make-proc) over the full name
(define (define-native-rule! pred make) (set! native-rules (append native-rules (list (cons pred make)))))

;; A native champaca lacks throws UnsatisfiedLinkError where it's called, as
;; the JVM does for a JNI method whose library isn't loaded, so a library's
;; try/catch around an optional feature behaves the same. Under CHAMPACA_BT
;; the name is also printed as it's hit, which is what finding the gap needs.
(define report-missing-natives? (and (getenv "CHAMPACA_BT") #t))
;; Generated code's int operations skip their checks (ix+ and friends), so a
;; native or an override declared to return int, boolean, byte, char or
;; short must give a fixnum in int's range, never #t or #f. Under
;; CHAMPACA_BT each such procedure is wrapped to check that, so the
;; battery's bt mode checks every one the suite calls.
(define (int-result-checked name proc)
  (if (and report-missing-natives? (procedure? proc)
           (memv (string-ref name (fx- (string-length name) 1)) '(#\I #\Z #\B #\C #\S))
           (char=? (string-ref name (fx- (string-length name) 2)) #\)))
      (lambda args
        (let ([v (apply proc args)])
          (if (and (fixnum? v) (fx<= -2147483648 v 2147483647))
              v
              (errorf 'champaca "~a returned ~s, not an int" name v))))
      proc))
(define (native-ref name)
  (or (int-result-checked name
       (or (hashtable-ref natives name #f)
           (let loop ([rs native-rules])
             (cond [(null? rs) #f]
                   [((caar rs) name) ((cdar rs) name)]
                   [else (loop (cdr rs))]))))
      (lambda args
        (when report-missing-natives?
          (put-string (current-error-port) (string-append "champaca: missing native " name "\n")))
        (jthrow "java/lang/UnsatisfiedLinkError" name))))

;; --- backtraces (CHAMPACA_BT) ------------------------------------------------------
;; Stack traces read each frame's pending call from Chez's inspector
;; information (reflect.ss return-sexpr), so code evaluated in the process
;; (user classes) is compiled with it whatever the jolt build's default. The
;; cache's compiles (JDK code) turn it off: quiet-compile-file says why.
(generate-inspector-information #t)
(define jstack '())
(define (jframe x) x)   ; codegen's non-tail wrapper for synchronized and CHAMPACA_BT methods
(define throw-stacks (make-weak-eq-hashtable))
(define (print-jstack e port)
  (let ([frames (eq-hashtable-ref throw-stacks e #f)])
    (when frames
      (let loop ([fs frames] [n 0])
        (unless (or (null? fs) (fx= n 25))
          (put-string port (string-append "\tat " (car fs) "\n"))
          (loop (cdr fs) (fx+ n 1)))))))

;; --- lambdas and boxing --------------------------------------------------------------

(define (jlambda cname proc)
  (let ([o (jnew cname)]) (vector-set! (jobj-slots o) 0 proc) o))
(define |champaca/Lambda.invoke| (lambda (this . args) (apply (vector-ref (jobj-slots this) 0) args)))

(define box-classes
  '(("Z" "java/lang/Boolean" "booleanValue()Z") ("B" "java/lang/Byte" "byteValue()B")
    ("C" "java/lang/Character" "charValue()C") ("S" "java/lang/Short" "shortValue()S")
    ("I" "java/lang/Integer" "intValue()I") ("J" "java/lang/Long" "longValue()J")
    ("F" "java/lang/Float" "floatValue()F") ("D" "java/lang/Double" "doubleValue()D")))
(define (jbox t v)
  (let ([e (assoc t box-classes)])
    (jinit (cadr e))
    ((method-proc (cadr e) (string-append "valueOf(" t ")L" (cadr e) ";")) v)))
(define (junbox t v)
  (let ([e (assoc t box-classes)])
    ((jvirt v (caddr e)) v)))

;; Overrides replace JDK methods that have bytecode, where running the real
;; body would need machinery the spike lacks (reflection, method handles).
(define overrides (make-hashtable string-hash string=?))
(define (define-override! name proc) (hashtable-set! overrides name proc))
(define (native-override name) (int-result-checked name (hashtable-ref overrides name #f)))

;; --- records (ObjectMethods.bootstrap) -----------------------------------------------
;; fields: list of (name slot descriptor). Formulas follow ObjectMethods.

(define (field-string v d)
  (case (string-ref d 0)
    [(#\Z) (jbool->string v)] [(#\C) (string (integer->char v))]
    [(#\F) (java-float->string v)] [(#\D) (jdouble->string v)] [(#\L #\[) (jstring-of v)] [else (number->string v)]))
(define (field-hash v d)
  (case (string-ref d 0)
    [(#\L #\[) (if v ((jvirt v "hashCode()I") v) 0)]
    [(#\Z) (if (eqv? v 0) 1237 1231)]
    [(#\J) (i32 (logxor v (ash (logand v #xffffffffffffffff) -32)))]
    ;; Float.hashCode and Double.hashCode: the bits with NaN made canonical,
    ;; a double's two halves xored (it took the double's low 32 bits)
    [(#\F) (if (nan? v) #x7fc00000 (float->bits v))]
    [(#\D) (let ([b (if (nan? v) #x7ff8000000000000 (logand (double->bits v) #xffffffffffffffff))])
              (i32 (logxor b (ash b -32))))]
    [else v]))
(define (record-slot o f) (vector-ref (jobj-slots o) (cadr f)))
;; the record class's getSimpleName, as ObjectMethods' toString takes it (a
;; local record's simple name drops javac's $1 prefix), once per class
(define record-simple-names (make-weak-eq-hashtable))
(define (record-simple-name this)
  (let ([c (jobj-cls this)])
    (or (eq-hashtable-ref record-simple-names c #f)
        (let ([s (jstring->scheme ((method-proc "java/lang/Class" "getSimpleName()Ljava/lang/String;") (jmirror c)))])
          (eq-hashtable-set! record-simple-names c s)
          s))))
;; "Name[a=1, b=x]"
(define (record-to-string fields this)
  (jstring (string-append (record-simple-name this) "["
                          (let loop ([fs fields] [acc ""])
                            (if (null? fs) acc
                                (loop (cdr fs)
                                      (string-append acc (if (string=? acc "") "" ", ")
                                                     (car (car fs)) "=" (field-string (record-slot this (car fs)) (caddr (car fs)))))))
                          "]")))
(define (record-hash fields this)
  (fold-left (lambda (h f) (i32 (+ (* 31 h) (field-hash (record-slot this f) (caddr f))))) 0 fields))
;; a reference field is equal when identical or by its own equals, anything else by eqv?
(define (record-field-equal? f a b)
  (if (memv (string-ref (caddr f) 0) '(#\L #\[))
      (or (eq? a b) (and a (not (eqv? 0 ((jvirt a "equals(Ljava/lang/Object;)Z") a b)))))
      (eqv? a b)))
(define (record-equals fields this o)
  (if (and o (jobj? o) (eq? (jobj-cls o) (jobj-cls this))
           (for-all (lambda (f) (record-field-equal? f (record-slot this f) (record-slot o f))) fields))
      1 0))
(define (jrecord-op op fields this . rest)
  (cond
    [(string=? op "toString") (record-to-string fields this)]
    [(string=? op "hashCode") (record-hash fields this)]
    [(string=? op "equals") (record-equals fields this (car rest))]
    [else (error 'champaca "record op" op)]))

;; Registration text is one-shot data plumbing (define a class, its statics,
;; a stub per method). Chez's interpreter runs it far cheaper than compile.
;; --- descriptors ----------------------------------------------------------------------

;; the index just past the field descriptor that starts at i in d
(define (desc-arg-end d i)
  (case (string-ref d i)
    [(#\[) (desc-arg-end d (fx+ i 1))]
    [(#\L) (let find ([k i]) (if (char=? (string-ref d k) #\;) (fx+ k 1) (find (fx+ k 1))))]
    [else (fx+ i 1)]))

(define (desc-args d)
  ;; "(ILjava/lang/String;[J)V" -> ("I" "Ljava/lang/String;" "[J")
  (let loop ([i 1] [acc '()])
    (if (char=? (string-ref d i) #\))
        (reverse acc)
        (let ([j (desc-arg-end d i)])
          (loop j (cons (substring d i j) acc))))))

(define (desc-ret d)
  (let loop ([i 0]) (if (char=? (string-ref d i) #\)) (substring d (fx+ i 1) (string-length d)) (loop (fx+ i 1)))))
