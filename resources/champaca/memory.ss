;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Unsafe, scoped memory access and the Unaligned accessors: memory through
;; arrays, objects' fields and native addresses. Split from natives.ss, whose
;; defnative and helpers these use; loaded after it.

;; --- Unsafe -------------------------------------------------------------------------
;; An object field offset is its slot index; an array offset is the element
;; index (base 0, scale 1). Static fields are not addressable yet.

;; Unsafe with a null base and an absolute address reaches native memory:
;; malloc'd memory (allocateMemory0) that libc calls get pointers into
;; (files.ss). The startup cache keeps compiled code and the class table,
;; never Java objects, so no address outlives its process.
(define (native-alloc! init)
  (let ([a (foreign-alloc 8)]) (foreign-set! 'integer-64 a 0 init) a))

(define (foreign-type t)
  (case t
    [("B") 'integer-8] [("Z") 'unsigned-8] [("S") 'integer-16] [("C") 'unsigned-16]
    [("I") 'integer-32] [("J") 'integer-64] [("F") 'single-float] [("D") 'double-float]
    [else (error 'champaca "no off-heap references" t)]))
(define (native-get a t) (foreign-ref (foreign-type t) a 0))
(define (native-set! a v t) (foreign-set! (foreign-type t) a 0 v))

(define unsafe-prefix "jdk/internal/misc/Unsafe.")
;; Arrays are byte-addressed like a real heap: base offset 16, scale by
;; element size (references count 4), little-endian. An access whose width
;; differs from the element size (getLongUnaligned over a byte[], as
;; ArraysSupport.vectorizedMismatch does) is assembled byte by byte.
;; Objects keep slot-index offsets from objectFieldOffset.

(define array-base 16)
(define (type-size t) (case (string-ref t 0) [(#\Z #\B) 1] [(#\C #\S) 2] [(#\I #\F) 4] [(#\J #\D) 8] [else 4]))
(define (same-type? t u) (char=? (string-ref t 0) (string-ref u 0)))
(define (elem-type a) (let ([e (jarr-elem a)]) (if (memv (string-ref e 0) '(#\L #\[)) "L" e)))

;; a float[] or double[] element is its bits here, as in memory
(define (elem-bits t e)
  (case t [("F") (float->bits e)] [("D") (double->bits e)] [else e]))

(define (byte-of a bpos)
  (let* ([t (elem-type a)] [se (type-size t)]
         [e (elem-bits t (vector-ref (jarr-vec a) (quotient bpos se)))])
    (logand (ash (logand e (- (ash 1 (* 8 se)) 1)) (- (* 8 (remainder bpos se)))) #xff)))

(define (set-byte! a bpos b)
  (let* ([t (elem-type a)] [se (type-size t)] [i (quotient bpos se)] [sh (* 8 (remainder bpos se))]
         [mask (- (ash 1 (* 8 se)) 1)]
         [old (logand (elem-bits t (vector-ref (jarr-vec a) i)) mask)]
         [new (logior (logand old (lognot (ash #xff sh))) (ash b sh))])
    (vector-set! (jarr-vec a) i (signed-as t new))))

(define (signed-as t u)
  (case (string-ref t 0)
    [(#\B) (i2b u)] [(#\S) (i2s u)] [(#\C) (logand u #xffff)] [(#\Z) (logand u 1)]
    [(#\I) (i32 u)] [(#\J) (i64 u)] [(#\F) (bits->float (i32 u))] [(#\D) (bits->double (i64 u))]
    [else u]))

;; Static fields: like HotSpot, the base is the class mirror. The offset is
;; static-offset-tag plus the field's index in the class meta list, so it
;; can't collide with an instance slot of the mirror itself.
(define static-offset-tag #x100000)
(define (static-symbol o off)
  (and (fx>= off static-offset-tag)
       (let ([c (eq-hashtable-ref mirror->class o #f)])
         (and c
              (let ([meta (list-ref (cadr (jclass-meta c)) (fx- off static-offset-tag))])
                (jinit (jclass-cname c))
                (string->symbol (string-append (jclass-cname c) ":" (car meta))))))))

(define (unsafe-get o off t)
  (cond
    [(not o) (native-get off t)]
    [(and (jobj? o) (static-symbol o off)) => top-level-value]
    [(jobj? o) (vector-ref (jobj-slots o) off)]
    [else
     (let* ([b (- off array-base)] [et (elem-type o)] [se (type-size et)] [st (type-size t)])
       (if (and (= se st) (zero? (remainder b se)) (or (same-type? t et) (char=? (string-ref et 0) #\L) (char=? (string-ref t 0) #\L)))
           (vector-ref (jarr-vec o) (quotient b se))
           (let loop ([k (- st 1)] [acc 0])
             (if (< k 0) (signed-as t acc) (loop (- k 1) (logior (ash acc 8) (byte-of o (+ b k))))))))]))

(define (unsafe-set! o off v t)
  (cond
    [(not o) (native-set! off v t)]
    [(and (jobj? o) (static-symbol o off)) => (lambda (sym) (set-top-level-value! sym v))]
    [(jobj? o) (vector-set! (jobj-slots o) off v)]
    [else
     (let* ([b (- off array-base)] [et (elem-type o)] [se (type-size et)] [st (type-size t)])
       (if (and (= se st) (zero? (remainder b se)) (or (same-type? t et) (char=? (string-ref et 0) #\L) (char=? (string-ref t 0) #\L)))
           (vector-set! (jarr-vec o) (quotient b se) v)
           (let ([v (elem-bits t v)])
             (do ([k 0 (+ k 1)]) ((= k st))
               (set-byte! o (+ b k) (logand (ash v (* -8 k)) #xff))))))]))

;; --- scoped memory access and closing a shared arena ------------------------------
;; HotSpot closes a shared session with a handshake: a thread inside one of
;; ScopedMemoryAccess's @Scoped methods on that session gets the
;; ScopedAccessError thrown at it, so no access outlives the memory. Here a
;; thread can only be switched out at a safepoint (a backward branch) or a
;; blocking call. Most scoped methods check the session and then reach memory
;; with no branch in between, and the few with loops (vectorizedMismatch, the
;; Vector API's loads and stores through their Java fallbacks) run without
;; yields. So while a thread closes a session no other thread is inside a
;; scoped access, and closeScope0 has nothing to do.
(defnative "jdk/internal/misc/ScopedMemoryAccess.closeScope0(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/misc/ScopedMemoryAccess$ScopedAccessError;)V"
  (this session error) (void))

(define (scoped-rethrow thunk)
  (guard (e [(and (jobj? e) (jinstance? e "jdk/internal/misc/ScopedMemoryAccess$ScopedAccessError"))
             (raise ((jvirt e "newRuntimeException()Ljava/lang/RuntimeException;") e))])
    (thunk)))

(define-override! "jdk/internal/misc/ScopedMemoryAccess.vectorizedMismatch(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JII)I"
  (lambda args
    (scoped-rethrow
      (lambda ()
        (without-yields
          (lambda ()
            (apply (method-proc "jdk/internal/misc/ScopedMemoryAccess"
                                "vectorizedMismatchInternal(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JII)I")
                   args)))))))

;; the Vector API's static entries: the Internal method takes the segment's
;; session first, then the same arguments; seg is the segment's position
(for-each
  (lambda (spec)
    (let* ([name (car spec)] [args (cadr spec)] [ret (caddr spec)] [seg (cadddr spec)]
           [key (string-append name "ScopedInternal(Ljdk/internal/foreign/MemorySessionImpl;" args ")" ret)])
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess." name "(" args ")" ret)
        (lambda xs
          (let ([msp (list-ref xs seg)])
            (scoped-rethrow
              (lambda ()
                (without-yields
                  (lambda ()
                    (apply (method-proc "jdk/internal/misc/ScopedMemoryAccess" key)
                           ((jvirt msp "sessionImpl()Ljdk/internal/foreign/MemorySessionImpl;") msp)
                           xs))))))))))
  '(("loadFromMemorySegment"
     "Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$VectorSpecies;Ljdk/internal/vm/vector/VectorSupport$LoadOperation;"
     "Ljdk/internal/vm/vector/VectorSupport$Vector;" 3)
    ("loadFromMemorySegmentMasked"
     "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$VectorMask;Ljdk/internal/vm/vector/VectorSupport$VectorSpecies;ILjdk/internal/vm/vector/VectorSupport$LoadVectorMaskedOperation;"
     "Ljdk/internal/vm/vector/VectorSupport$Vector;" 4)
    ("storeIntoMemorySegment"
     "Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/vm/vector/VectorSupport$Vector;Ljdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$StoreVectorOperation;"
     "V" 4)
    ("storeIntoMemorySegmentMasked"
     "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/vm/vector/VectorSupport$Vector;Ljdk/internal/vm/vector/VectorSupport$VectorMask;Ljdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$StoreVectorMaskedOperation;"
     "V" 6)))

;; --- the Unaligned accessors ------------------------------------------------------
;; Unsafe.get<T>Unaligned(Object, long[, boolean]) and put<T>Unaligned, and
;; ScopedMemoryAccess's wrappers over them, which every ByteBuffer read and
;; write on a byte[] goes through (HeapByteBuffer.getShort and the rest).
;; HotSpot intrinsifies them; here the JDK's Java took a native call per
;; byte plus the assembly, and the scoped wrapper a try/finally per access
;; (a quarter of a warm Spring Boot fat-jar start was its launcher reading
;; zip headers this way). Overridden: a byte[]'s bytes are read from its
;; vector directly, in either byte order; native memory (a direct buffer)
;; through one foreign-ref; any other array byte by byte as before.
;; an instance field of an object, the case atomics and locks hit: its offset is the slot index
(define-syntax field-offset?
  (syntax-rules () [(_ o off) (and (jobj? o) (fx< off static-offset-tag))]))
(define-syntax byte-array?
  (syntax-rules () [(_ o) (and (jarr? o) (let ([e (jarr-elem o)]) (and (fx= (string-length e) 1) (char=? (string-ref e 0) #\B))))]))
;; the unsigned value of n bytes (n up to 4) at index i of byte vector v
(define (vec-bytes->fx v i n be?)
  (let loop ([k 0] [acc 0])
    (if (fx= k n)
        acc
        (loop (fx+ k 1)
              (fxlogor acc (fxsll (fxlogand (vector-ref v (fx+ i k)) #xff) (fx* 8 (if be? (fx- (fx- n 1) k) k))))))))
;; the index of the element of primitive array o that off and n cover
;; exactly, or #f: a heap memory segment over an int[] reads its ints this way
(define (whole-element o off n)
  (and (jarr? o)
       (let ([e (jarr-elem o)])
         (and (fx= (string-length e) 1)
              (fx= (type-size e) n)
              (let ([b (fx- off array-base)])
                (and (fx= 0 (fxremainder b n)) (fxquotient b n)))))))
;; the unsigned n-byte value at off in o: a byte[], native memory, or any
;; other array (mem-byte); n is 1, 2, 4 or 8
(define (unaligned-u o off n be?)
  (cond
    [(byte-array? o)
     (let ([v (jarr-vec o)] [i (fx- off array-base)])
       (if (fx= n 8)
           (logior (vec-bytes->fx v (if be? (fx+ i 4) i) 4 be?) (ash (vec-bytes->fx v (if be? i (fx+ i 4)) 4 be?) 32))
           (vec-bytes->fx v i n be?)))]
    [(not o)
     (let ([u (case n [(1) (#3%foreign-ref 'unsigned-8 off 0)] [(2) (#3%foreign-ref 'unsigned-16 off 0)] [(4) (#3%foreign-ref 'unsigned-32 off 0)] [else (#3%foreign-ref 'unsigned-64 off 0)])])
       (if be? (bswap u n) u))]
    [(whole-element o off n)
     => (lambda (i)
          (let ([u (logand (elem-bits (elem-type o) (vector-ref (jarr-vec o) i)) (- (ash 1 (fx* 8 n)) 1))])
            (if be? (bswap u n) u)))]
    [else
     (let loop ([k 0] [acc 0])
       (if (fx= k n)
           acc
           (loop (fx+ k 1) (logior acc (ash (mem-byte o (+ off k)) (fx* 8 (if be? (fx- (fx- n 1) k) k)))))))]))
;; the n low bytes of x (any sign) written at off in o, in either order
(define (unaligned-put! o off n x be?)
  (cond
    [(byte-array? o)
     (let ([v (jarr-vec o)] [i (fx- off array-base)])
       (do ([k 0 (fx+ k 1)]) ((fx= k n))
         (vector-set! v (fx+ i (if be? (fx- (fx- n 1) k) k)) (i2b (logand (ash x (fx* -8 k)) #xff)))))]
    [(not o)
     (let ([u (let ([u (logand x (- (ash 1 (fx* 8 n)) 1))]) (if be? (bswap u n) u))])
       (case n
         [(1) (#3%foreign-set! 'unsigned-8 off 0 u)] [(2) (#3%foreign-set! 'unsigned-16 off 0 u)]
         [(4) (#3%foreign-set! 'unsigned-32 off 0 u)] [else (#3%foreign-set! 'unsigned-64 off 0 u)]))]
    [(whole-element o off n)
     => (lambda (i)
          (let ([u (let ([u (logand x (- (ash 1 (fx* 8 n)) 1))]) (if be? (bswap u n) u))])
            (vector-set! (jarr-vec o) i (signed-as (elem-type o) u))))]
    [else
     (do ([k 0 (fx+ k 1)]) ((fx= k n))
       (mem-byte-set! o (+ off (if be? (fx- (fx- n 1) k) k)) (logand (ash x (fx* -8 k)) #xff)))]))
(define (bswap u n)
  (let loop ([k 0] [acc 0])
    (if (fx= k n) acc (loop (fx+ k 1) (logior (ash acc 8) (logand (ash u (fx* -8 k)) #xff))))))
;; (java type letter, descriptor, size, the signed value of the unsigned bits)
(define unaligned-types
  (list (list "Short" "S" 2 i2s) (list "Char" "C" 2 (lambda (u) u)) (list "Int" "I" 4 i32) (list "Long" "J" 8 i64)))
(for-each
  (lambda (spec)
    (let* ([name (car spec)] [d (cadr spec)] [n (caddr spec)] [signed (cadddr spec)]
           ;; an object base is a field, instance or static, as the plain accessor
           ;; takes it (never a ByteBuffer's; kept for Unsafe's generality, the byte
           ;; order flag meaning nothing for a slot)
           [get (lambda (o off be?)
                  (cond [(not (jobj? o)) (signed (unaligned-u o off n be?))]
                        [(field-offset? o off) (vector-ref (jobj-slots o) off)]
                        [else (unsafe-get o off d)]))]
           [put! (lambda (o off x be?)
                   (cond [(not (jobj? o)) (unaligned-put! o off n x be?)]
                         [(field-offset? o off) (vector-set! (jobj-slots o) off x)]
                         [else (unsafe-set! o off x d)]))]
           [scoped-check! (lambda (session)
                            ;; the wrapper's ScopedAccessError becomes its RuntimeException, as the JDK's catch does
                            (when session
                              (guard (e [(and (jobj? e) (jinstance? e "jdk/internal/misc/ScopedMemoryAccess$ScopedAccessError"))
                                         (raise ((jvirt e "newRuntimeException()Ljava/lang/RuntimeException;") e))])
                                ((jvirt session "checkValidStateRaw()V") session))))])
      (define-override! (string-append unsafe-prefix "get" name "Unaligned(Ljava/lang/Object;J)" d)
        (lambda (this o off) (get o off #f)))
      (define-override! (string-append unsafe-prefix "get" name "Unaligned(Ljava/lang/Object;JZ)" d)
        (lambda (this o off be) (get o off (eqv? be 1))))
      (define-override! (string-append unsafe-prefix "put" name "Unaligned(Ljava/lang/Object;J" d ")V")
        (lambda (this o off x) (put! o off x #f)))
      (define-override! (string-append unsafe-prefix "put" name "Unaligned(Ljava/lang/Object;J" d "Z)V")
        (lambda (this o off x be) (put! o off x (eqv? be 1))))
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess.get" name
                                       "Unaligned(Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JZ)" d)
        (lambda (this session o off be) (scoped-check! session) (get o off (eqv? be 1))))
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess.put" name
                                       "Unaligned(Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;J" d "Z)V")
        (lambda (this session o off x be) (scoped-check! session) (put! o off x (eqv? be 1))))))
  unaligned-types)

;; the value type an Unsafe accessor works in, from its name
(define (unsafe-type n)
  (let loop ([ts '(("Reference" . "L") ("Boolean" . "Z") ("Byte" . "B") ("Short" . "S") ("Char" . "C")
                   ("Int" . "I") ("Long" . "J") ("Float" . "F") ("Double" . "D"))])
    (cond [(null? ts) "L"]
          [(string-search (caar ts) n) (cdar ts)]
          [else (loop (cdr ts))])))

(defnative "jdk/internal/misc/Unsafe.arrayBaseOffset0(Ljava/lang/Class;)I" (this c) array-base)
(defnative "jdk/internal/misc/Unsafe.arrayIndexScale0(Ljava/lang/Class;)I" (this c)
  (let ([n (jclass-cname (mirror-class c))])
    (type-size (let ([e (substring n 1 (string-length n))]) (if (memv (string-ref e 0) '(#\L #\[)) "L" e)))))
(defnative "jdk/internal/misc/Unsafe.addressSize0()I" (this) 8)
(defnative "jdk/internal/misc/Unsafe.pageSize()I" (this) 4096)
(defnative "jdk/internal/misc/Unsafe.fullFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.loadFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.storeFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.objectFieldOffset1(Ljava/lang/Class;Ljava/lang/String;)J" (this c name)
  (jfield-index (jclass-cname (mirror-class c)) (jstring->scheme name)))
(define (field-mirror f) (jslot f (jfield-index "java/lang/reflect/Field" "clazz")))
;; the same offset, from a reflective Field (CompletableFuture's VarHandles use it)
(defnative "jdk/internal/misc/Unsafe.objectFieldOffset0(Ljava/lang/reflect/Field;)J" (this f)
  (jfield-index (jclass-cname (mirror-class (field-mirror (nn f))))
                (jstring->scheme (jslot f (jfield-index "java/lang/reflect/Field" "name")))))
(defnative "jdk/internal/misc/Unsafe.staticFieldBase0(Ljava/lang/reflect/Field;)Ljava/lang/Object;" (this f)
  (field-mirror (nn f)))
(defnative "jdk/internal/misc/Unsafe.staticFieldOffset0(Ljava/lang/reflect/Field;)J" (this f)
  (fx+ static-offset-tag (jslot (nn f) (jfield-index "java/lang/reflect/Field" "slot"))))
(defnative "jdk/internal/misc/Unsafe.ensureClassInitialized0(Ljava/lang/Class;)V" (this c)
  (jinit (jclass-cname (mirror-class c))))
(defnative "jdk/internal/misc/Unsafe.shouldBeInitialized0(Ljava/lang/Class;)Z" (this c)
  (if (eq? (jclass-state (mirror-class c)) 'initialized) 0 1))
;; between Java arrays and native memory (a null base), in any mix: whole
;; elements when two arrays' types and alignment allow, else bytes. The source
;; is read in full before writing, so overlap is safe.
(define (mem-byte o off)
  (if o (byte-of o (- off array-base)) (#3%foreign-ref 'unsigned-8 off 0)))
(define (mem-byte-set! o off b)
  (if o (set-byte! o (- off array-base) b) (#3%foreign-set! 'unsigned-8 off 0 b)))
(defnative "jdk/internal/misc/Unsafe.copyMemory0(Ljava/lang/Object;JLjava/lang/Object;JJ)V" (this src soff dst doff n)
  (cond
    [(and (jarr? src) (jarr? dst)) (copy-array-memory src soff dst doff n)]
    ;; native memory and a byte[], the shape every file and channel read and
    ;; write takes: one element per byte, no intermediate list
    [(and (not src) (jarr? dst) (string=? (elem-type dst) "B"))
     (native->vector! soff 0 (jarr-vec dst) (- doff array-base) n)]
    [(and (jarr? src) (not dst) (string=? (elem-type src) "B"))
     (vector->native! (jarr-vec src) (- soff array-base) doff 0 n)]
    [else
     (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (mem-byte src (+ soff i)) acc))))])
       (let loop ([i 0] [bs bytes])
         (unless (null? bs) (mem-byte-set! dst (+ doff i) (car bs)) (loop (+ i 1) (cdr bs)))))]))
;; the same, reversing the bytes of each elem-size element
(defnative "jdk/internal/misc/Unsafe.copySwapMemory0(Ljava/lang/Object;JLjava/lang/Object;JJJ)V" (this src soff dst doff n es)
  (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (mem-byte src (+ soff i)) acc))))])
    (let loop ([i 0] [bs bytes])
      (unless (null? bs)
        (let ([elem (list-head bs es)])
          (do ([k 0 (+ k 1)] [b (reverse elem) (cdr b)]) ((= k es)) (mem-byte-set! dst (+ doff i k) (car b)))
          (loop (+ i es) (list-tail bs es)))))))
(defnative "jdk/internal/misc/Unsafe.allocateMemory0(J)J" (this n) (foreign-alloc (max n 1)))
(defnative "jdk/internal/misc/Unsafe.freeMemory0(J)V" (this a) (unless (eqv? a 0) (foreign-free a)))
(defnative "jdk/internal/misc/Unsafe.setMemory0(Ljava/lang/Object;JJB)V" (this o off n b)
  (do ([i 0 (+ i 1)]) ((= i n)) (mem-byte-set! o (+ off i) (fxlogand b #xff))))
(define (copy-array-memory src soff dst doff n)
  (let* ([sb (- soff array-base)] [db (- doff array-base)]
         [et (elem-type src)] [se (type-size et)])
    (if (and (same-type? et (elem-type dst)) (zero? (remainder sb se)) (zero? (remainder db se)) (zero? (remainder n se)))
        ;; one pass, backwards when the same vector's ranges overlap with
        ;; the destination past the source (memmove)
        (let ([k (quotient n se)] [si (quotient sb se)] [di (quotient db se)] [sv (jarr-vec src)] [dv (jarr-vec dst)])
          (if (or (not (eq? sv dv)) (fx<= di si))
              (do ([i 0 (fx+ i 1)]) ((fx= i k)) (vector-set! dv (fx+ di i) (vector-ref sv (fx+ si i))))
              (do ([i (fx- k 1) (fx- i 1)]) ((fx< i 0)) (vector-set! dv (fx+ di i) (vector-ref sv (fx+ si i))))))
        (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (byte-of src (+ sb i)) acc))))])
          (let loop ([i 0] [bs bytes])
            (unless (null? bs) (set-byte! dst (+ db i) (car bs)) (loop (+ i 1) (cdr bs))))))))
(defnative "jdk/internal/misc/Unsafe.allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;" (this c)
  (jnew (jclass-cname (mirror-class c))))

(define (starts? s p) (and (fx>= (string-length s) (string-length p)) (string=? p (substring s 0 (string-length p)))))
(define (method-part n) (substring n (string-length unsafe-prefix) (string-length n)))

;; get<T>[Volatile](Object, long) / put<T>[Volatile](Object, long, T)
;; An instance field of an object, the case atomics and locks hit, skips
;; unsafe-get's dispatch: its offset is the slot index.
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "get") (string-search "(Ljava/lang/Object;J)" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off)
                  (if (field-offset? o off)
                      ;; a weak reference's referent slot holds a weak pair (references, below)
                      (let ([v (vector-ref (jobj-slots o) off)])
                        (if (weak-pair? v) (let ([x (car v)]) (if (bwp-object? x) #f x)) v))
                      (unsafe-get o off t))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "put") (string-search "(Ljava/lang/Object;J" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off v)
                  (if (field-offset? o off) (vector-set! (jobj-slots o) off v) (unsafe-set! o off v t))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndSet")))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off expect new)
                  (if (field-offset? o off)
                      (let ([v (jobj-slots o)])
                        (if (eqv? (vector-ref v off) expect) (begin (vector-set! v off new) 1) 0))
                      (if (eqv? (unsafe-get o off t) expect) (begin (unsafe-set! o off new t) 1) 0))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndExchange")))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off expect new)
                  (let ([old (unsafe-get o off t)])
                    (when (eqv? old expect) (unsafe-set! o off new t))
                    old)))))

(define (string-search needle hay)
  (let ([n (string-length needle)] [h (string-length hay)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i n) h) #f]
            [(string=? needle (substring hay i (fx+ i n))) i]
            [else (loop (fx+ i 1))]))))
