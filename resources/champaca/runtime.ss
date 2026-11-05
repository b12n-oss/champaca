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
(define (register-class! name super interfaces template vtable-alist field-alist flags meta)
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
;; the X of a linker's "class not found: X" in a failure's text, or #f
(define (class-not-found-in e)
  (let* ([t (condition-text e)] [k "class not found: "] [n (string-length t)] [m (string-length k)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(string=? (substring t i (fx+ i m)) k) (substring t (fx+ i m) n)]
            [else (loop (fx+ i 1))]))))
;; HotSpot's ClassFormatError text from a load whose class file (the class
;; named or one it needs) isn't one: main.clj normalize's "class format
;; error in X: <text>", or #f
(define (class-format-of e)
  (let* ([t (condition-text e)] [k "class format error in "] [n (string-length t)] [m (string-length k)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(string=? (substring t i (fx+ i m)) k)
             (let skip ([j (fx+ i m)])
               (cond [(fx>= (fx+ j 1) n) #f]
                     [(and (char=? (string-ref t j) #\:) (char=? (string-ref t (fx+ j 1)) #\space)) (substring t (fx+ j 2) n)]
                     [else (skip (fx+ j 1))]))]
            [else (loop (fx+ i 1))]))))
;; the same from generated code: an instruction naming such a class (codegen)
(define (jthrow-class-format name msg) (jthrow "java/lang/ClassFormatError" msg))
;; a class the JVM refuses to define (main.clj check-definition!): "linkage
;; error in X: <error class>|<text>" -> (error-class . text), or #f
(define (linkage-error-of e)
  (let* ([t (condition-text e)] [k "linkage error in "] [n (string-length t)] [m (string-length k)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(string=? (substring t i (fx+ i m)) k)
             (let skip ([j (fx+ i m)])
               (cond [(fx>= (fx+ j 1) n) #f]
                     [(and (char=? (string-ref t j) #\:) (char=? (string-ref t (fx+ j 1)) #\space))
                      (let* ([rest (substring t (fx+ j 2) n)]
                             [bar (let find ([k 0]) (cond [(fx= k (string-length rest)) #f] [(char=? (string-ref rest k) #\|) k] [else (find (fx+ k 1))]))])
                        (and bar (cons (substring rest 0 bar) (substring rest (fx+ bar 1) (string-length rest)))))]
                     [else (skip (fx+ j 1))]))]
            [else (loop (fx+ i 1))]))))
(define (jthrow-linkage name err msg) (jthrow err msg))
;; the JVM's error for a class nothing has: the slashed name, caused by a
;; ClassNotFoundException with the dotted one; or the error the class's
;; loader threw resolving it, every time, as JVMS 5.4.3 has a failed
;; resolution fail the same way again (natives.ss ask-resolving-loader)
(define resolution-errors (make-hashtable string-hash string=?))
(define (jthrow-missing name)
  (let ([le (hashtable-ref resolution-errors name #f)])
    (when le (raise le)))
  (let ([e (construct "java/lang/NoClassDefFoundError" "(Ljava/lang/String;)V" (jstring name))]
        [cnfe (construct "java/lang/ClassNotFoundException" "(Ljava/lang/String;)V" (jstring (slashes->dots name)))])
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
;; resolves to (main.clj check-kind): what as HotSpot's LinkResolver says it
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

(define (jinit-slow c name)
  (case (jclass-state c)
    [(loaded)
     (jclass-state-set! c 'initializing)
     (eq-hashtable-set! init-owners c (self))
     (let ([done (lambda (state)
                   (jclass-state-set! c state)
                   (eq-hashtable-delete! init-owners c)
                   (when (fx> init-waiters 0) (gil-poke!)))])
       ;; anything but an Error reaches the caller wrapped in an
       ;; ExceptionInInitializerError (JVMS 5.5 step 11); the failure and
       ;; its thread are kept for the NoClassDefFoundError of later uses
       (guard (e [#t (done 'erroneous)
                     (when (jobj? e)
                       (eq-hashtable-set! init-errors c
                                          (cons e (if current-jthread
                                                      (jstring->scheme ((method-proc "java/lang/Thread" "getName()Ljava/lang/String;") current-jthread))
                                                      "main"))))
                     (raise (if (and (jobj? e) (not (jinstance? e "java/lang/Error")))
                                (let ([w (begin (jinit "java/lang/ExceptionInInitializerError")
                                                (jnew "java/lang/ExceptionInInitializerError"))])
                                  ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/Throwable;)V") w e)
                                  w)
                                e))])
         (when (jclass-super c) (jinit (jclass-super c)))
         (unless (eq? (jclass-kind c) 'interface) (init-default-interfaces c))
         (let ([sym (string->symbol (string-append name ".<clinit>()V"))])
           (when (global-bound? sym) ((top-level-value sym)))))
       (done 'initialized))]
    [(initializing)
     (unless (eq? (eq-hashtable-ref init-owners c #f) (self))
       (set! init-waiters (fx+ init-waiters 1))
       (gil-block! (lambda () (not (eq? (jclass-state c) 'initializing))) #f)
       (set! init-waiters (fx- init-waiters 1))
       (jinit-slow c name))]
    [(erroneous)
     ;; with HotSpot's cause: an ExceptionInInitializerError naming the
     ;; first failure and its thread
     (let ([first (eq-hashtable-ref init-errors c #f)]
           [e (begin (jinit "java/lang/NoClassDefFoundError") (jnew "java/lang/NoClassDefFoundError"))])
       ((method-proc "java/lang/NoClassDefFoundError" "<init>(Ljava/lang/String;)V")
        e (jstring (string-append "Could not initialize class " (slashes->dots name))))
       (when first
         (let ([w (begin (jinit "java/lang/ExceptionInInitializerError") (jnew "java/lang/ExceptionInInitializerError"))])
           ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/String;)V")
            w (jstring (string-append "Exception " (jstring-of (car first)) " [in thread \"" (cdr first) "\"]")))
           ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;") e w)))
       (raise e))]
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
      (jthrow "java/lang/ClassCastException"
              (string-append "class " (class-name-of x) " cannot be cast to class " target))))

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
(define (jinstance-miss ic o target)
  (let ([r (jinstance? o target)])
    (when (jobj? o) (set-car! ic (jobj-cls o)) (set-cdr! ic r))
    r))
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
(define (array-clone a) (make-jarr* (jarr-elem a) (vector-copy (jarr-vec a))))
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

;; the vtable entry for conflicting default methods (main.clj link)
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

;; --- strings ----------------------------------------------------------------
;; java.lang.String is the JDK's own class: value is a byte[] and coder is
;; 0 (LATIN1) or 1 (UTF16, low byte first because StringUTF16.isBigEndian
;; answers false).

(define string-value-slot #f)
(define string-coder-slot #f)
(define (string-slots!)
  (unless string-value-slot
    (set! string-value-slot (jfield-index "java/lang/String" "value"))
    (set! string-coder-slot (jfield-index "java/lang/String" "coder"))))

(define (code-units s)
  ;; Scheme string (code points) -> list of UTF-16 code units. A vector is a
  ;; classfile constant holding a lone surrogate, already in code units.
  (if (vector? s)
      (vector->list s)
      (let loop ([cs (reverse (string->list s))] [acc '()])
        (if (null? cs) acc
            (let ([cp (char->integer (car cs))])
              (if (fx> cp #xffff)
                  (let ([v (fx- cp #x10000)])
                    (loop (cdr cs) (cons (fx+ #xd800 (fxsrl v 10)) (cons (fx+ #xdc00 (fxlogand v #x3ff)) acc))))
                  (loop (cdr cs) (cons cp acc))))))))

(define (jstring s) (units->jstring (code-units s)))

;; a list of UTF-16 code units -> java.lang.String
(define (units->jstring units)
  (string-slots!)
  (let* ([latin1? (for-all (lambda (u) (fx< u 256)) units)]
         [bytes (if latin1?
                    (map i2b units)
                    (apply append (map (lambda (u) (list (i2b (fxlogand u #xff)) (i2b (fxsrl u 8)))) units)))]
         [o (jnew "java/lang/String")])
    (vector-set! (jobj-slots o) string-value-slot (make-jarr "B" (list->vector bytes)))
    (vector-set! (jobj-slots o) string-coder-slot (if latin1? 0 1))
    o))

(define interned (make-hashtable string-hash string=?))
(define interned-units (make-hashtable equal-hash equal?))
(define (jstring-lit s)
  (let ([t (if (string? s) interned interned-units)])
    (or (hashtable-ref t s #f)
        (let ([o (jstring s)]) (hashtable-set! t s o) o))))

(define (jstring->scheme o)
  (string-slots!)
  (let* ([v (jarr-vec (vector-ref (jobj-slots o) string-value-slot))]
         [n (vector-length v)])
    (if (eqv? 0 (vector-ref (jobj-slots o) string-coder-slot))
        (let ([s (make-string n)])
          (do ([i 0 (fx+ i 1)]) ((fx= i n) s)
            (string-set! s i (integer->char (fxlogand (vector-ref v i) #xff)))))
        (let loop ([i 0] [acc '()])
          (if (fx>= i n)
              (list->string (reverse acc))
              (let ([u (fxlogor (fxlogand (vector-ref v i) #xff) (fxsll (fxlogand (vector-ref v (fx+ i 1)) #xff) 8))])
                (if (and (fx>= u #xd800) (fx< u #xdc00) (fx< (fx+ i 2) n))
                    (let ([lo (fxlogor (fxlogand (vector-ref v (fx+ i 2)) #xff)
                                       (fxsll (fxlogand (vector-ref v (fx+ i 3)) #xff) 8))])
                      (if (and (fx>= lo #xdc00) (fx< lo #xe000))
                          (loop (fx+ i 4) (cons (integer->char (fx+ #x10000 (fxsll (fx- u #xd800) 10) (fx- lo #xdc00))) acc))
                          (loop (fx+ i 2) (cons (integer->char #xfffd) acc))))
                    ;; a lone surrogate has no Scheme char; U+FFFD, as an
                    ;; encoder would write it
                    (loop (fx+ i 2) (cons (if (and (fx>= u #xd800) (fx< u #xe000)) (integer->char #xfffd) (integer->char u)) acc)))))))))

;; a java.lang.String's UTF-16 code units, as a list
(define (jstring-units o)
  (string-slots!)
  (let* ([v (jarr-vec (vector-ref (jobj-slots o) string-value-slot))]
         [n (vector-length v)])
    (if (eqv? 0 (vector-ref (jobj-slots o) string-coder-slot))
        (map (lambda (b) (fxlogand b #xff)) (vector->list v))
        (let loop ([i (fx- n 2)] [acc '()])
          (if (fx< i 0) acc
              (loop (fx- i 2) (cons (fxlogor (fxlogand (vector-ref v i) #xff)
                                             (fxsll (fxlogand (vector-ref v (fx+ i 1)) #xff) 8))
                                    acc)))))))

;; UTF-16 code units -> Scheme string, a pair becoming one char; #f when a
;; surrogate stands alone (classfile/decode-mutf8 makes the same split)
(define (units->string-or-false units)
  (let loop ([us units] [acc '()])
    (cond
      [(null? us) (list->string (reverse acc))]
      [(or (fx< (car us) #xd800) (fx>= (car us) #xe000))
       (loop (cdr us) (cons (integer->char (car us)) acc))]
      [(and (fx< (car us) #xdc00) (pair? (cdr us))
            (fx>= (cadr us) #xdc00) (fx< (cadr us) #xe000))
       (loop (cddr us) (cons (integer->char (fx+ #x10000 (fxsll (fx- (car us) #xd800) 10) (fx- (cadr us) #xdc00))) acc))]
      [else #f])))

;; String.intern, keyed the way jstring-lit keys constants: by the Scheme
;; string, or by the code-unit vector when a surrogate stands alone (keying on
;; jstring->scheme would map every lone surrogate to U+FFFD and collide)
(define (jstring-intern! o)
  (let* ([units (jstring-units o)]
         [s (units->string-or-false units)]
         [t (if s interned interned-units)]
         [k (or s (list->vector units))])
    (or (hashtable-ref t k #f) (begin (hashtable-set! t k o) o))))

;; whether a java.lang.String equals a constant (a string or code-unit vector).
;; jstring->scheme maps a lone surrogate to U+FFFD, so string=? is exact only
;; when the label has no U+FFFD; otherwise compare code units.
(define (jstring-lit-equal? o lit)
  (if (and (string? lit) (not (memv (integer->char #xfffd) (string->list lit))))
      (string=? (jstring->scheme o) lit)
      (equal? (jstring-units o) (code-units lit))))

;; any Java reference -> Scheme string, the way string concatenation sees it
(define (jstring-of x)
  (cond [(not x) "null"]
        [(and (jobj? x) (string=? (jclass-cname (jobj-cls x)) "java/lang/String")) (jstring->scheme x)]
        [else (let ([s ((jvirt x "toString()Ljava/lang/String;") x)]) (if s (jstring->scheme s) "null"))]))

(define (jbool->string z) (if (eqv? z 0) "false" "true"))

;; any Java reference -> its UTF-16 code units, the way string concatenation
;; sees it; unlike jstring-of this keeps a lone surrogate
(define (jstring-units-of x)
  (cond [(not x) (code-units "null")]
        [(and (jobj? x) (string=? (jclass-cname (jobj-cls x)) "java/lang/String")) (jstring-units x)]
        [else (let ([s ((jvirt x "toString()Ljava/lang/String;") x)]) (if s (jstring-units s) (code-units "null")))]))

;; x's code units pushed onto acc, last first: x is a Scheme string, a list of
;; code units, or a classfile constant's code-unit vector
(define (units-onto x acc)
  (cond
    [(string? x)
     (let ([n (string-length x)])
       (let loop ([i 0] [acc acc])
         (if (fx= i n) acc
             (let ([cp (char->integer (string-ref x i))])
               (loop (fx+ i 1)
                     (if (fx> cp #xffff)
                         (let ([v (fx- cp #x10000)])
                           (cons (fx+ #xdc00 (fxlogand v #x3ff)) (cons (fx+ #xd800 (fxsrl v 10)) acc)))
                         (cons cp acc)))))))]
    [(vector? x) (units-onto (vector->list x) acc)]
    [(not x) (units-onto "null" acc)]
    [(or (jobj? x) (jarr? x)) (units-onto (jstring-units-of x) acc)]
    [else (fold-left (lambda (a u) (cons u a)) acc x)]))

;; makeConcatWithConstants: \x1 takes the next argument, \x2 the next
;; bootstrap constant. An argument arrives as a Scheme string (a number), a
;; list of code units (a char), or the Java reference itself. When every
;; piece is Latin-1 (a String with coder 0, a literal below U+0100) the
;; result's bytes are copied straight from the pieces; else it is built as
;; code units, so a lone surrogate in any part survives (jconcat-units).
;; The recipe is parsed once per recipe object (a call site's literal).
(define concat-recipes (make-weak-eq-hashtable))
(define (concat-parts recipe)
  (or (eq-hashtable-ref concat-recipes recipe #f)
      (let ([parts (let ([n (string-length recipe)])
                     (let loop ([i 0] [start 0] [acc '()])
                       (let ([lit (lambda () (if (fx> i start) (cons (substring recipe start i) acc) acc))])
                         (cond [(fx= i n) (reverse (lit))]
                               [(char=? (string-ref recipe i) #\x1) (loop (fx+ i 1) (fx+ i 1) (cons 1 (lit)))]
                               [(char=? (string-ref recipe i) #\x2) (loop (fx+ i 1) (fx+ i 1) (cons 2 (lit)))]
                               [else (loop (fx+ i 1) start acc)]))))])
        (eq-hashtable-set! concat-recipes recipe parts)
        parts)))
;; a reference argument as the piece it contributes: the String, its
;; toString, or "null" (jstring-units-of does the same for the slow path)
(define (concat-arg x)
  (cond [(not x) "null"]
        [(and (jobj? x) (string=? (jclass-cname (jobj-cls x)) "java/lang/String")) x]
        ;; an array too: javac before 19 passes any reference raw (19+ calls
        ;; String.valueOf first), and Object's toString answers for arrays
        [(or (jobj? x) (jarr? x)) (or ((jvirt x "toString()Ljava/lang/String;") x) "null")]
        [else x]))
;; a piece's byte length when it is Latin-1, else #f
(define (concat-latin1-length p)
  (cond [(string? p) (let ([n (string-length p)])
                       (let loop ([i 0])
                         (cond [(fx= i n) n]
                               [(fx< (char->integer (string-ref p i)) 256) (loop (fx+ i 1))]
                               [else #f])))]
        [(jobj? p) (and (eqv? 0 (vector-ref (jobj-slots p) string-coder-slot))
                        (vector-length (jarr-vec (vector-ref (jobj-slots p) string-value-slot))))]
        [(pair? p) (let loop ([l p] [n 0]) (cond [(null? l) n] [(fx< (car l) 256) (loop (cdr l) (fx+ n 1))] [else #f]))]
        [(null? p) 0]
        [else #f]))
(define (jconcat recipe args consts)
  (if (vector? recipe)
      (jconcat-units recipe args consts)
      (let* ([_ (string-slots!)]   ; concat-latin1-length reads the slots
             [pieces (let loop ([ps (concat-parts recipe)] [args args] [consts consts] [acc '()])
                       (if (null? ps)
                           (reverse acc)
                           (let ([p (car ps)])
                             (cond [(eqv? p 1) (loop (cdr ps) (cdr args) consts (cons (concat-arg (car args)) acc))]
                                   [(eqv? p 2) (loop (cdr ps) args (cdr consts) (cons (car consts) acc))]
                                   [else (loop (cdr ps) args consts (cons p acc))]))))]
             [total (let loop ([ps pieces] [n 0])
                      (cond [(null? ps) n]
                            [(concat-latin1-length (car ps)) => (lambda (k) (loop (cdr ps) (fx+ n k)))]
                            [else #f]))])
        (if total
            (let ([v (make-vector total)])
              (string-slots!)
              (let loop ([ps pieces] [at 0])
                (unless (null? ps)
                  (let ([p (car ps)])
                    (cond
                      [(string? p)
                       (let ([n (string-length p)])
                         (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! v (fx+ at i) (i2b (char->integer (string-ref p i)))))
                         (loop (cdr ps) (fx+ at n)))]
                      [(jobj? p)
                       (let* ([sv (jarr-vec (vector-ref (jobj-slots p) string-value-slot))] [n (vector-length sv)])
                         (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! v (fx+ at i) (vector-ref sv i)))
                         (loop (cdr ps) (fx+ at n)))]
                      [else
                       (let inner ([l p] [at at])
                         (if (null? l)
                             (loop (cdr ps) at)
                             (begin (vector-set! v at (i2b (car l))) (inner (cdr l) (fx+ at 1)))))]))))
              (let ([o (jnew "java/lang/String")])
                (vector-set! (jobj-slots o) string-value-slot (make-jarr "B" v))
                (vector-set! (jobj-slots o) string-coder-slot 0)
                o))
            (units->jstring (reverse (fold-left (lambda (acc p) (units-onto p acc)) '() pieces)))))))
;; the slow path, for a code-unit recipe (a constant holding a lone surrogate)
(define (jconcat-units recipe args consts)
  (let loop ([rs (code-units recipe)] [args args] [consts consts] [acc '()])
    (if (null? rs)
        (units->jstring (reverse acc))
        (let ([u (car rs)])
          (cond
            [(fx= u 1) (loop (cdr rs) (cdr args) consts (units-onto (car args) acc))]
            [(fx= u 2) (loop (cdr rs) args (cdr consts) (units-onto (car consts) acc))]
            [else (loop (cdr rs) args consts (cons u acc))])))))

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

;; --- modules ---------------------------------------------------------------------
;; Class.getModule() is the boot layer's named module for a JDK class, found
;; by package; the application loader's unnamed module for a classpath class,
;; as on the JVM, once that loader exists (initPhase2 makes it); the boot
;; loader's unnamed module for anything else. Module.defineModule0 records
;; each module's packages while initPhase2 builds the layer and BootLoader's
;; <clinit> hands over the unnamed module; both refresh the mirrors made
;; before them (module and loader), as HotSpot patches the classes loaded
;; before java.base is defined. No classpath class is mirrored during the
;; boot (measured: none of the 100 mirrors at its end), so none needs the
;; application loader patched in. Until then a mirror's module is null, as
;; on the JVM.
(define unnamed-module #f)
;; the application class loader, once ClassLoaders' <clinit> has run
;; (initPhase2), and its unnamed module; #f before
(define (app-loader)
  (let ([s '|jdk/internal/loader/ClassLoaders:APP_LOADER|])
    (and (global-bound? s) (top-level-value s))))
(define (app-unnamed-module)
  (let ([l (app-loader)]) (and l (jslot l (jfield-index "java/lang/ClassLoader" "unnamedModule")))))
(define package-modules (make-hashtable string-hash string=?))   ; "java/util" -> Module
;; classes read from the classpath (main.clj registration-text): they stay in
;; the unnamed module whatever their package, as a classpath class does on the
;; JVM, where one in a package a JDK module owns is never even loaded
(define user-classes (make-hashtable string-hash string=?))
(define (user-class! name) (hashtable-set! user-classes name #t))
(define (user-class? name) (hashtable-contains? user-classes name))
;; classes defined at run time (ClassLoader.defineClass, natives.ss): the
;; loader that defined each, for Class.getClassLoader and findLoadedClass,
;; and the hidden ones (Lookup.defineHiddenClass), named <class>/0x<n> as
;; HotSpot names them, which no lookup by name finds
(define class-loaders (make-hashtable string-hash string=?))
(define hidden-classes (make-hashtable string-hash string=?))
(define (hidden-class? n) (hashtable-contains? hidden-classes n))
(define (package-dir cname)
  (let loop ([i (fx- (string-length cname) 1)])
    (cond [(fx< i 0) ""]
          [(char=? (string-ref cname i) #\/)
           ;; a hidden class's /0x<n> isn't a package boundary (no package
           ;; starts with a digit)
           (if (and (fx< (fx+ i 2) (string-length cname))
                    (char=? (string-ref cname (fx+ i 1)) #\0) (char=? (string-ref cname (fx+ i 2)) #\x))
               (package-dir (substring cname 0 i))
               (substring cname 0 i))]
          [else (loop (fx- i 1))])))
;; the class an array's module comes from: its element type, down through the
;; dimensions ("[[Ljava/sql/Date;" -> "java/sql/Date"); a primitive stays a
;; primitive name ("[I" -> "int")
(define (element-name c)
  (let ([n (jclass-cname c)])
    (if (eq? (jclass-kind c) 'array)
        (let skip ([i 0])
          (cond [(char=? (string-ref n i) #\[) (skip (fx+ i 1))]
                [(char=? (string-ref n i) #\L) (substring n (fx+ i 1) (fx- (string-length n) 1))]
                [else (cdr (assoc (string (string-ref n i)) prim-names))]))
        n)))
;; the package a class's module is looked up by: primitives count as java.lang
(define (class-package c)
  (let ([e (element-name c)]) (if (prim-name? e) "java/lang" (package-dir e))))
;; a classpath class is in the application loader's unnamed module (the boot
;; loader's until the application loader exists); a class in a package a
;; module owns (a JDK class, a proxy in its dynamic module) is in that module;
;; a class a loader defined is in that loader's unnamed module
(define (class-module c)
  (let ([e (element-name c)])
    (cond [(user-class? e) (or (app-unnamed-module) unnamed-module)]
          [(hashtable-ref package-modules (class-package c) #f)]
          [(hashtable-ref class-loaders e #f)
           => (lambda (ld) ((method-proc "java/lang/ClassLoader" "getUnnamedModule()Ljava/lang/Module;") ld))]
          [else unnamed-module])))
(define (set-mirror-module! m c)
  (let ([mod (class-module c)])
    (when mod (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "module") mod))))
;; the mirrors a module's definition has to patch, by package, so defining
;; the 70 modules of the boot layer touches each mirror once rather than
;; walking every mirror 70 times (a class is never unloaded, so this keeps
;; nothing alive that the class table doesn't)
(define package-mirrors (make-hashtable string-hash string=?))
(define (index-mirror! m c)
  (unless (user-class? (element-name c))
    (let ([d (class-package c)])
      (hashtable-set! package-mirrors d (cons m (hashtable-ref package-mirrors d '()))))))
(define (set-unnamed-module! m)
  (set! unnamed-module m)
  (vector-for-each (lambda (mirror) (set-mirror-module! mirror (mirror-class mirror))) (hashtable-keys mirror->class)))
;; a class's loader as Class.getClassLoader answers it: a defined class's
;; definer, a classpath class's the application loader, a JDK class's the
;; loader its module is defined to (null for the boot modules, the platform
;; loader for java.sql and the like), an array's its element's
(define (module-loader m) (and m (jslot m (jfield-index "java/lang/Module" "loader"))))
(define (class-loader-of c)
  (let ([e (element-name c)])
    (or (hashtable-ref class-loaders e #f)
        (if (user-class? e)
            (app-loader)
            (module-loader (hashtable-ref package-modules (class-package c) #f))))))
;; HotSpot's place of a class in its linkage messages (Klass::
;; class_in_module_of_loader): "java.lang.String is in module java.base of
;; loader 'bootstrap'", "Foo is in unnamed module of loader 'app'"; a loader
;; of the program's own is "'name' @hash" or its class's name and @hash
;; (loader-text, HotSpot's loader_name_and_id). Module versions aren't shown
;; (HotSpot shows a non-JDK module's).
(define (loader-text ld)
  (if (not ld)
      "'bootstrap'"
      (let ([n (jslot ld (jfield-index "java/lang/ClassLoader" "name"))]
            [hash (string-append " @" (string-downcase (number->string (identity-hash ld) 16)))])
        (cond [(jinstance? ld "jdk/internal/loader/BuiltinClassLoader") (string-append "'" (jstring->scheme n) "'")]
              [n (string-append "'" (jstring->scheme n) "'" hash)]
              [else (string-append (slashes->dots (jclass-cname (jobj-cls ld))) hash)]))))
(define (module-name-of mod)
  (let ([mname (and mod (jslot mod (jfield-index "java/lang/Module" "name")))])
    (and mname (jstring->scheme mname))))
(define (class-place-text cname)
  (let* ([c (find-class cname)]
         [mname (module-name-of (class-module c))])
    (string-append (slashes->dots cname) " is in "
                   (if mname (string-append "module " mname) "unnamed module")
                   " of loader " (loader-text (class-loader-of c)))))
;; Why a sealed class or interface sup (JVMS 5.3.5) refuses the class name
;; being defined, in HotSpot's texts (InstanceKlass::has_as_permitted_subclass),
;; or #f when it permits it or isn't sealed: the two must share a module, a
;; non-public one must share sup's package, and sup's PermittedSubclasses
;; must list it (a hidden class's /0x<n> name never matches). name isn't in
;; the class table yet: a classpath class is the application loader's, in its
;; unnamed module, a defined one its definer's (resolving-loader, bound by
;; natives.ss define-class!), in a module owning its package or else in the
;; definer's unnamed module, as class-module puts them.
(define (sealed-refusal name public? defined? sup)
  (let* ([s (find-class sup)]
         [permitted (class-permitted-subclasses s)])
    (and permitted
         (let* ([ld (if defined? (resolving-loader) (app-loader))]
                [mod (cond [(not defined?) (or (app-unnamed-module) unnamed-module)]
                           [(hashtable-ref package-modules (package-dir name) #f)]
                           [ld ((method-proc "java/lang/ClassLoader" "getUnnamedModule()Ljava/lang/Module;") ld)]
                           [else unnamed-module])]   ; a null loader (Unsafe.defineClass0): the boot loader's
                [smod (class-module s)]
                [sld (class-loader-of s)]
                ;; a hidden class keeps the slash before /0x<n> (it isn't
                ;; marked hidden until its definition ends)
                [dotted (lambda (n)
                          (let ([i (let loop ([i (fx- (string-length n) 1)])
                                     (cond [(fx< i 0) #f] [(char=? (string-ref n i) #\/) i] [else (loop (fx- i 1))]))])
                            (if (and i (fx< (fx+ i 2) (string-length n))
                                     (char=? (string-ref n (fx+ i 1)) #\0) (char=? (string-ref n (fx+ i 2)) #\x))
                                (string-append (slashes->dots (substring n 0 i)) (substring n i (string-length n)))
                                (slashes->dots n))))]
                [pkg (lambda (n) (let ([d (package-dir n)]) (if (string=? d "") "unnamed" d)))])
           (cond [(not (eq? mod smod))
                  (string-append "Failed same module check: subclass " (dotted name) " is in module '"
                                 (or (module-name-of mod) "unnamed module") "' with loader " (loader-text (module-loader mod))
                                 ", and sealed class " (dotted sup) " is in module '"
                                 (or (module-name-of smod) "unnamed module") "' with loader " (loader-text (module-loader smod)))]
                 [(and (not public?) (not (string=? (package-dir name) (package-dir sup))))
                  (string-append "Failed same package check: non-public subclass " (dotted name) " is in package '"
                                 (pkg name) "' with classloader " (loader-text ld) ", and sealed class " (dotted sup)
                                 " is in package '" (pkg sup) "' with classloader " (loader-text sld))]
                 [(member name permitted) #f]
                 [else (string-append "Failed listed permitted subclass check: class " (dotted name)
                                      " is not a permitted subclass of " (dotted sup))])))))
(define (set-mirror-loader! m c)
  (let ([ld (class-loader-of c)])
    (when ld (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "classLoader") ld))))
(define (define-module! m package-names)   ; names with dots, as Module.defineModule0 gets them
  (let ([slot (jfield-index "java/lang/Class" "module")]
        [lslot (jfield-index "java/lang/Class" "classLoader")]
        [ld (module-loader m)])
    (for-each (lambda (pn)
                (let ([d (slashes pn)])
                  (hashtable-set! package-modules d m)
                  (for-each (lambda (mirror)
                              (vector-set! (jobj-slots mirror) slot m)
                              ;; a class a loader defined keeps its definer
                              (unless (hashtable-ref class-loaders (element-name (mirror-class mirror)) #f)
                                (vector-set! (jobj-slots mirror) lslot ld)))
                            (hashtable-ref package-mirrors d '()))))
              package-names)))

(define (jmirror-of name) (jmirror (find-class name)))
;; ldc of a class: its mirror, the class resolved when the ldc runs (a class
;; defined after the method was translated is found; one that fails to load
;; fails there) and through the defining loader of the class whose code it
;; is, which alone may see it (Quarkus's RunnerClassLoader)
(define (jldc-class name from)
  (if (hashtable-contains? classes name)
      (jmirror-of name)
      (parameterize ([resolving-loader (hashtable-ref class-loaders from #f)]) (jmirror-of name))))
(define (mirror-class m) (or (eq-hashtable-ref mirror->class (nn m) #f) (error 'champaca "not a mirror" m)))

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

;; --- entry --------------------------------------------------------------------

;; HotSpot's JavaThread::exit when dispatchUncaughtException itself throws:
;; one line naming the class of what the handler threw and the thread, which
;; nothing dispatches again
(define (report-handler-failure t x)
  (flush-output-port (current-output-port))
  (let ([err (current-error-port)])
    (flush-output-port err)   ; Java's System.err shares the fd
    (put-string err (string-append "\nException: " (slashes->dots (class-name-of x))
                                   " thrown from the UncaughtExceptionHandler in thread \""
                                   (jstring->scheme (jslot t (jfield-index "java/lang/Thread" "name"))) "\"\n"))
    (flush-output-port err)))

;; Thread t's uncaught exception e through its handler: 'done, a java-halt
;; the handler raised (System.exit in it), or the Scheme condition the
;; dispatch failed with (one during boot, say). A Java throwable from the
;; handler is reported as HotSpot does and counts as done.
(define (dispatch-uncaught t e)
  (guard (x [(java-halt? x) x]
            [#t (let ([j (java-raise-of x)])
                  (if (jobj? j) (begin (report-handler-failure t j) 'done) x))])
    ((method-proc "java/lang/Thread" "dispatchUncaughtException(Ljava/lang/Throwable;)V") t e)
    'done))

;; Report an escaping exception or Scheme error on the main thread; the
;; status a handler's System.exit gave, else #f
(define (report-uncaught raised)
  (flush-output-port (current-output-port))
  (let ([err (current-error-port)] [e (java-raise-of raised)])
    (cond
      [(jobj? e)
       ;; through the thread's own uncaught handler, as HotSpot does for main
       ;; (ThreadGroup's prints "Exception in thread" and the trace); the
       ;; shadow stack only if that fails with a Scheme error
       (flush-output-port err)   ; Java's System.err shares the fd
       (let ([r (dispatch-uncaught current-jthread e)])
         (cond
           [(java-halt? r) (java-halt-status r)]
           [(eq? r 'done) #f]
           [else
            (put-string err (string-append "Exception in thread \"main\" " (guard (x [#t (class-name-of e)]) (jstring-of e)) "\n"))
            (print-jstack e err)
            #f]))]
      [else
       ;; the handler runs before the stack unwinds, so under CHAMPACA_BT
       ;; jstack still says where in the Java program this happened
       (put-string err "champaca: Scheme error: ")
       (display-condition e err)
       (newline err)
       (for-each (lambda (f) (put-string err (string-append "\tat " f "\n")))
                 (list-head jstack (min 25 (length jstack))))
       #f])))

;; Run thunk; report an escaping Java exception or Scheme error and return 1,
;; otherwise return 0.
;; System.exit ends in Shutdown.halt0, which raises this so the status
;; reaches whoever started the Java code: run-main returns it, and api.clj
;; exits the process with it, as a JVM would.
(define-record-type java-halt (fields status))
(define halted #f)

(define (run-guarded thunk)
  (call/cc
   (lambda (k)
     (with-exception-handler
      (lambda (e)
        (if (java-halt? e)
            (begin (flush-output-port (current-output-port)) (set! halted #t) (k (java-halt-status e)))
            (let ([st (report-uncaught e)])
              (flush-output-port (current-output-port))
              (if st (begin (set! halted #t) (k st)) (k 1)))))
      (lambda () (thunk) (flush-output-port (current-output-port)) 0)))))

;; The java launcher's way to main (JEP 512): the JDK's own
;; LauncherHelper.checkAndLoadMain loads the class, finds its main with
;; MethodFinder and refuses with the launcher's texts and status 1 (System.exit
;; from abort); it notes whether main is static and whether it takes the
;; arguments. A static main is called as JNI's GetStaticMethodID finds it, the
;; class (or interface) first, then its superclasses, whatever its access (a
;; private static main hiding the public one MethodFinder found runs, as on
;; HotSpot), the class initialized;
;; an instance main on an object its no-argument constructor makes, through
;; the vtable, so an inherited or default main runs.
(define (launch-main cname args)
  (let* ([helper "sun/launcher/LauncherHelper"]
         [_ (jinit helper)]
         [mirror ((method-proc helper "checkAndLoadMain(ZILjava/lang/String;)Ljava/lang/Class;")
                  1 1 (jstring (slashes->dots cname)))]
         [c (mirror-class mirror)]
         [name (jclass-cname c)]
         [static? (eqv? 1 (top-level-value (string->symbol (string-append helper ":isStaticMain"))))]
         [no-args? (eqv? 1 (top-level-value (string->symbol (string-append helper ":noArgMain"))))]
         [desc (if no-args? "()V" "([Ljava/lang/String;)V")]
         [jargs (if no-args? '() (list (scheme-list->jarray "Ljava/lang/String;" (map jstring args))))])
    (if static?
        (let up ([k c])
          (let ([m (find (lambda (m) (and (string=? (car m) "main") (string=? (cadr m) desc)
                                          (fxpositive? (fxlogand (caddr m) 8))))
                         (meta-methods k))])
            (cond [m (jinit name) (apply (method-proc (jclass-cname k) (string-append "main" desc)) jargs)]
                  [(jclass-super k) (up (find-class (jclass-super k)))]
                  [else (error 'champaca "main not found after the launcher found it" name)])))
        (let ([o (construct name "()V")])
          (apply (jvirt o (string-append "main" desc)) o jargs)))))
(define (run-main cname . args)
  (set! halted #f)
  (let ([status (run-guarded (lambda () (launch-main cname args)))])
    ;; as the java launcher: unless System.exit ended it, main's thread ends
    ;; (its joiners wake), then as DestroyJavaVM it waits for the other
    ;; non-daemon threads and runs the shutdown hooks, keeping main's status
    (if halted
        status
        (let ([s (run-guarded (lambda () (main-thread-exit!) (java-shutdown!)))])
          (if halted s status)))))

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
(define (jrecord-op op simple fields this . rest)
  (let ([get (lambda (o f) (vector-ref (jobj-slots o) (cadr f)))])
    (cond
      [(string=? op "toString")
       (jstring (string-append simple "["
                               (let loop ([fs fields] [acc ""])
                                 (if (null? fs) acc
                                     (loop (cdr fs)
                                           (string-append acc (if (string=? acc "") "" ", ")
                                                          (car (car fs)) "=" (field-string (get this (car fs)) (caddr (car fs)))))))
                               "]"))]
      [(string=? op "hashCode")
       (fold-left (lambda (h f) (i32 (+ (* 31 h) (field-hash (get this f) (caddr f))))) 0 fields)]
      [(string=? op "equals")
       (let ([o (car rest)])
         (if (and o (jobj? o) (eq? (jobj-cls o) (jobj-cls this))
                  (for-all (lambda (f)
                             (let ([a (get this f)] [b (get o f)])
                               (if (memv (string-ref (caddr f) 0) '(#\L #\[))
                                   (or (eq? a b) (and a (not (eqv? 0 ((jvirt a "equals(Ljava/lang/Object;)Z") a b)))))
                                   (eqv? a b))))
                           fields))
             1 0))]
      [else (error 'champaca "record op" op)])))

;; Registration text is one-shot data plumbing (define a class, its statics,
;; a stub per method). Chez's interpreter runs it far cheaper than compile.
;; --- champaca's globals are their own top-level labels ------------------------------
;; In a mutable environment such as jolt's, Chez gives a top-level variable a
;; label gensym, #{Name *top*:Name}, on its first definition or reference, and
;; the variable's value lives in that gensym. Its unique name is a fresh
;; string, "*top*:" and the whole name, so the 65,000 globals of a warm Spring
;; Boot start (one per method, static field and conflict stub) held 20 MB of
;; them (spike 76). A symbol whose *top* property is the symbol itself is its
;; own label, as every symbol of Chez's scheme environment is, and its value
;; sits in its own slot: no gensym. Every form champaca generates is walked
;; before it is expanded, so a global is labelled before anything compiled
;; refers to it, in every process alike (a cached unit refers to the label it
;; was compiled against); a symbol that already has a label keeps it, and one
;; bound at the top level already (a Scheme or jolt procedure: its value in its
;; own slot) is left alone. A global's name holds "(" (a method) or ":" (a
;; field, a conflict stub); a local of the same shape (a block's name) gets a
;; property nothing reads.
(define (champaca-global-name? s)
  (and (symbol? s) (not (gensym? s))
       (let ([n (symbol->string s)])
         (let loop ([i 0])
           (and (fx< i (string-length n))
                (or (memv (string-ref n i) '(#\( #\:)) (loop (fx+ i 1))))))))
;; $sputprop is a plain read-modify-write of the property list, where Chez's
;; own label updates hold the tc-mutex; champaca's callers hold the GIL
(define (self-label! s)
  (unless (or (#%$sgetprop s '*top* #f) (#%$top-level-bound? s))
    (#%$sputprop s '*top* s)))
(define (self-label-form! x)
  (cond [(pair? x) (self-label-form! (car x)) (self-label-form! (cdr x))]
        [(vector? x) (vector-for-each self-label-form! x)]
        [(champaca-global-name? x) (self-label! x)]
        [else (void)]))
(define (define-global! sym v)
  (when (champaca-global-name? sym) (self-label! sym))
  (set-top-level-value! sym v))
;; top-level-bound? for a global that may not be defined yet (a class with no
;; <clinit>): Chez's own makes it a label gensym on the way
(define (global-bound? s)
  (when (champaca-global-name? s) (self-label! s))
  (top-level-bound? s))

;; A method's text evaluated outside the cache paths (main.clj
;; compile-method!), with inspector information as lines? decided, where
;; the global setting above would keep it for every method
(define (eval-method-text text lines?)
  (parameterize ([generate-inspector-information lines?])
    (let ([p (open-string-input-port text)])
      (let loop ([last (void)])
        (let ([form (read p)])
          (if (eof-object? form) last (begin (self-label-form! form) (loop (eval form)))))))))
(define (interpret-string s)
  (let ([p (open-input-string s)])
    (let loop ([r (void)])
      (let ([x (read p)])
        (if (eof-object? x) r (begin (self-label-form! x) (loop (interpret x))))))))

;; compile-file announces itself on stdout, which belongs to the Java program.
;; The cache's code carries no inspector information (free-variable names,
;; source): it nearly doubled boot.so (12.1 MB -> 6.8 MB without) and the time
;; to load it. Procedure names stay, and getCallerClass reads those. Stack
;; traces read a frame's line from that information (reflect.ss), so JDK
;; frames, whose code the cache holds, have none: keeping it for them made a
;; 6.5 MB cache of 4.2 and a warm start 0.02 to 0.04 s slower (Hello 0.30 to
;; 0.32 s, JdkLib 0.34 to 0.38 s, medians of 5 alternating rounds).
(define (quiet-compile-file in out)
  (parameterize ([current-output-port (open-output-string)]
                 [generate-inspector-information #f])
    (compile-file in out)))

;; Generations after boot are stored unit by unit: one class's registration,
;; or one method body. Each unit is compiled on its own and appended to the
;; generation's file, so a later run can load just the units it reaches.
;; add-unit! returns the unit's start offset; end-units! the file's length.
(define unit-out #f)

(define (begin-units! path)
  (set! unit-out (open-file-output-port path (file-options no-fail))))

(define (read-all text)   ; generated forms, their globals labelled (self-label-form!)
  (let ([in (open-input-string text)])
    (let loop ([forms '()])
      (let ([x (read in)])
        (if (eof-object? x) (reverse forms) (begin (self-label-form! x) (loop (cons x forms))))))))

;; inspect: keep inspector information, as a classpath class's method bodies
;; do, since stack traces read their line numbers from it (JDK code goes
;; without: see quiet-compile-file)
(define (add-unit! text . inspect)
  (let ([start (port-position unit-out)])
    (parameterize ([generate-inspector-information (and (pair? inspect) (car inspect))])
      (compile-to-port (read-all text) unit-out))
    start))

;; a unit compile-unit already compiled, appended as it is
(define (add-fasl! bv)
  (let ([start (port-position unit-out)])
    (put-bytevector unit-out bv)
    start))

;; Compile text once to object code, run it, and return the code's bytes for
;; the startup cache, which appends them unchanged (add-fasl!). Evaluating the
;; text and compiling it again for the cache cost a second compile per method.
(define (compile-unit text . inspect)   ; inspect as for add-unit!
  (let-values ([(p get) (open-bytevector-output-port)])
    (parameterize ([generate-inspector-information (and (pair? inspect) (car inspect))])
      (compile-to-port (read-all text) p))
    (let ([bv (get)])
      (load-compiled-from-port (open-bytevector-input-port bv))
      bv)))

(define (end-units!)
  (let ([n (port-position unit-out)])
    (close-port unit-out)
    (set! unit-out #f)
    n))

;; Generation files stay open once read; a unit is a slice of one.
(define unit-ports (make-hashtable string-hash string=?))

(define (load-unit path off len)
  (let ([p (or (hashtable-ref unit-ports path #f)
               (let ([p (open-file-input-port path)])
                 (hashtable-set! unit-ports path p)
                 p))])
    (set-port-position! p off)
    (load-compiled-from-port (open-bytevector-input-port (get-bytevector-n p len)))
    (void)))

;; One compiled procedure makes every method stub, so registration text (and
;; the boot cache built from it) carries a call per method, not a lambda. The
;; first call compiles the method, which replaces the stub's global; anything
;; still holding the stub after that is forwarded. Stubs are recorded so an
;; inline cache never keeps one.
(define stub-procs (make-weak-eq-hashtable))
(define (method-stub cname mname desc sym)
  (or (native-override (string-append cname "." mname desc))
      (let* ([compiled? #f]
             [p (lambda args
                  (unless compiled?
                    (jit! cname mname desc)
                    (set! compiled? #t))
                  (apply (top-level-value sym) args))])
        (eq-hashtable-set! stub-procs p #t)
        p)))

;; A class's method definitions from one form: ms holds (name desc symbol
;; native?) per method. One top-level define per method made compiling the
;; startup cache most of a cold start, Chez's cost per top-level form being
;; far above that of a quoted list.
(define (define-methods! cname ms)
  (for-each (lambda (m)
              (let ([mname (car m)] [desc (cadr m)] [sym (caddr m)])
                (define-global! sym
                  (if (cadddr m)
                      (native-ref (string-append cname "." mname desc))
                      (method-stub cname mname desc sym)))))
            ms))

;; "java.lang.String" -> "java/lang/String"
(define (slashes s) (list->string (map (lambda (c) (if (char=? c #\.) #\/ c)) (string->list s))))
(define (slashes->dots s) (list->string (map (lambda (c) (if (char=? c #\/) #\. c)) (string->list s))))

;; --- descriptors ----------------------------------------------------------------------

(define (desc-args d)
  ;; "(ILjava/lang/String;[J)V" -> ("I" "Ljava/lang/String;" "[J")
  (let loop ([i 1] [acc '()])
    (if (char=? (string-ref d i) #\))
        (reverse acc)
        (let scan ([j i])
          (case (string-ref d j)
            [(#\[) (scan (fx+ j 1))]
            [(#\L) (let find ([k j]) (if (char=? (string-ref d k) #\;)
                                         (loop (fx+ k 1) (cons (substring d i (fx+ k 1)) acc))
                                         (find (fx+ k 1))))]
            [else (loop (fx+ j 1) (cons (substring d i (fx+ j 1)) acc))])))))

(define (desc-ret d)
  (let loop ([i 0]) (if (char=? (string-ref d i) #\)) (substring d (fx+ i 1) (string-length d)) (loop (fx+ i 1)))))
