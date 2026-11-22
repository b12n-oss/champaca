;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The rest of the core runtime: arrays, exceptions, Class mirrors, identity
;; hashes, the native method table, CHAMPACA_BT backtraces, lambdas and
;; boxing, records and descriptors. Split from runtime.ss; loaded right
;; after objects.ss.

;; --- arrays -------------------------------------------------------------------
;; making them, and the limits on their size, are in arrays.ss

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

;; An object keeps its identity hash in its lock field, as a fixnum, until it
;; gets a monitor, and in the monitor after that (threads.ss monitor-of moves
;; it), so asking again is a field read. HotSpot keeps it in the object's
;; header the same way. An array or any other value keeps it in a weak table.
(define id-hashes (make-weak-eq-hashtable))
(define id-counter 0)
(define (stored-hash x)
  (if (jobj? x)
      (let ([l (jobj-lock x)])
        (cond [(fixnum? l) l] [l (jmonitor-hash l)] [else #f]))
      (eq-hashtable-ref id-hashes x #f)))
(define (store-hash! x h)
  (let ([l (and (jobj? x) (jobj-lock x))])
    (cond [(not (jobj? x)) (eq-hashtable-set! id-hashes x h)]
          [(and l (not (fixnum? l))) (jmonitor-hash-set! l h)]
          [else (jobj-lock-set! x h)])))
(define (identity-hash x)
  (if (not x) 0
      (or (stored-hash x)
          (begin (set! id-counter (fx+ id-counter 1))
                 (let ([h (fxlogand (fx* id-counter 1103515245) #x7fffffff)])
                   (store-hash! x h) h)))))

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
