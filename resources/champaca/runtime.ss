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
(define (jit! c m d) (jit-hook c m d))

;; --- numbers ---------------------------------------------------------------

(define (i32 x)
  (let ([y (logand x #xffffffff)])
    (if (fx>= y #x80000000) (fx- y #x100000000) y)))

(define (i64 x)
  (let ([y (logand x #xffffffffffffffff)])
    (if (>= y #x8000000000000000) (- y #x10000000000000000) y)))

;; Generated code wraps int and long results with these macros rather than
;; i32 and i64: a call to a top-level procedure can't be inlined, and i64's
;; logand with a bignum mask made every long add a bignum operation. The
;; fixnum case is inline; anything else (a bignum, which a long past 60 bits
;; or an int product can be) takes the procedure. A fixnum is always inside
;; long's range, since Chez's fixnums are 61 bits.
(define-syntax wrap-int
  (syntax-rules ()
    [(_ e) (let ([y e])
             (if (fixnum? y) (fx- (fxlogxor (fxlogand y #xffffffff) #x80000000) #x80000000) (i32 y)))]))
(define-syntax wrap-long
  (syntax-rules ()
    [(_ e) (let ([y e]) (if (fixnum? y) y (i64 y)))]))
;; A guarded region of a Java method. The handler goes straight onto Chez's
;; (per-thread) handler stack, under a one-shot continuation: guard takes a full
;; call/cc and reads the continuation marks, about twice the cost. A clause whose
;; test holds escapes and returns (lambda () next) for the method's trampoline.
;; With no clause for it the exception goes on to the next handler from right
;; where it was raised, so nothing between is unwound and wound again.
(define-syntax jtry
  (syntax-rules ()
    [(_ e ([test next] ...) body)
     (call/1cc
      (lambda (k)
        (let* ([old (#%$current-handler-stack)]
               [new (cons (lambda (e)
                            (cond [test (k (lambda () next))] ...
                                  [else (raise-continuable e)]))
                          old)])
          (dynamic-wind
           (lambda () (#%$current-handler-stack new))
           (lambda () body)
           (lambda () (#%$current-handler-stack old))))))]))
;; int shifts: a and the result are ints, so fixnums. ishl first drops the bits
;; that would leave the low 32, so the shift can't overflow a fixnum.
(define-syntax int-shl
  (syntax-rules ()
    [(_ a s) (let ([n (fxlogand s 31)])
               (wrap-int (fxsll (fxlogand a (fx- (fxsll 1 (fx- 32 n)) 1)) n)))]))
(define-syntax int-shr (syntax-rules () [(_ a s) (fxsra a (fxlogand s 31))]))
(define-syntax int-ushr
  (syntax-rules () [(_ a s) (wrap-int (fxsrl (fxlogand a #xffffffff) (fxlogand s 31)))]))

(define (f32 x)
  (let ([b (make-bytevector 4)])
    (bytevector-ieee-single-native-set! b 0 x)
    (bytevector-ieee-single-native-ref b 0)))

(define (idiv a b) (if (fx= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (i32 (quotient a b))))
(define (irem a b) (if (fx= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (remainder a b)))
(define (ldiv a b) (if (= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (i64 (quotient a b))))
(define (lrem a b) (if (= b 0) (jthrow "java/lang/ArithmeticException" "/ by zero") (remainder a b)))
(define (ishl a s) (i32 (ash a (fxlogand s 31))))
(define (ishr a s) (ash a (fx- 0 (fxlogand s 31))))
(define (iushr a s) (i32 (ash (logand a #xffffffff) (fx- 0 (fxlogand s 31)))))
(define (lshl a s) (i64 (ash a (fxlogand s 63))))
(define (lshr a s) (ash a (fx- 0 (fxlogand s 63))))
(define (lushr a s) (i64 (ash (logand a #xffffffffffffffff) (fx- 0 (fxlogand s 63)))))
(define (fx<> a b) (not (fx= a b)))
(define (lcmp a b) (cond [(< a b) -1] [(> a b) 1] [else 0]))
(define (i2b x) (fx- (fxlogxor (fxlogand x #xff) #x80) #x80))
(define (i2s x) (fx- (fxlogxor (fxlogand x #xffff) #x8000) #x8000))

(define (jfcmp a b nan-result)
  (cond [(or (nan? a) (nan? b)) nan-result] [(fl< a b) -1] [(fl> a b) 1] [else 0]))
(define (jfrem a b) (if (or (nan? a) (nan? b) (fl= b 0.0) (infinite? a)) +nan.0
                        (fl- a (fl* b (fltruncate (fl/ a b))))))
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
                               meta 'loaded #f)))

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
        [else (load-hook name)
              (or (hashtable-ref classes name #f) (error 'champaca "class not registered" name))])))

(define (method-proc cname key)
  (top-level-value (string->symbol (string-append cname "." key))))

;; <clinit> runs on first active use, superclass first. While it runs the
;; class is 'initializing: the same thread goes ahead (a recursive request),
;; another thread waits for it to finish (JVMS 5.5). A <clinit> that throws
;; leaves the class 'erroneous, and any later use throws NoClassDefFoundError.
(define init-owners (make-weak-eq-hashtable))
(define init-waiters 0)

(define (jinit name)
  (let ([c (find-class name)])
    (unless (eq? (jclass-state c) 'initialized)
      (jinit-slow c name))))

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
       ;; ExceptionInInitializerError (JVMS 5.5 step 11)
       (guard (e [#t (done 'erroneous)
                     (raise (if (and (jobj? e) (not (jinstance? e "java/lang/Error")))
                                (let ([w (begin (jinit "java/lang/ExceptionInInitializerError")
                                                (jnew "java/lang/ExceptionInInitializerError"))])
                                  ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/Throwable;)V") w e)
                                  w)
                                e))])
         (when (jclass-super c) (jinit (jclass-super c)))
         (let ([sym (string->symbol (string-append name ".<clinit>()V"))])
           (when (top-level-bound? sym) ((top-level-value sym)))))
       (done 'initialized))]
    [(initializing)
     (unless (eq? (eq-hashtable-ref init-owners c #f) (self))
       (set! init-waiters (fx+ init-waiters 1))
       (gil-block! (lambda () (not (eq? (jclass-state c) 'initializing))) #f)
       (set! init-waiters (fx- init-waiters 1))
       (jinit-slow c name))]
    [(erroneous)
     (jthrow "java/lang/NoClassDefFoundError"
             (string-append "Could not initialize class " (slashes->dots name)))]
    [else (void)]))

(define (jnew name)
  (let ([c (find-class name)])
    (make-jobj c (vector-copy (jclass-template c)))))

(define (jfield-index cname fname)
  (let loop ([c (find-class cname)])
    (or (hashtable-ref (jclass-fields c) (string-append (jclass-cname c) "." fname) #f)
        (and (jclass-super c) (loop (find-class (jclass-super c))))
        (error 'champaca "no such field" cname fname))))

(define (class-name-of x)
  (cond [(jobj? x) (jclass-cname (jobj-cls x))]
        [(jarr? x) (string-append "[" (jarr-elem x))]
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

;; An NPE that generated code raises carries its site, the quoted symbol
;; Class.name(desc)@bci, which appears in the failing branch only, so the
;; fast path pays nothing. getExtendedNPEMessage (natives.ss) builds the
;; JVM's message from the site's bytecode. Any other NPE has no site and
;; keeps a null message.
(define npe-sites (make-weak-eq-hashtable))
(define (npe-at site)
  (jinit "java/lang/NullPointerException")
  (let ([e (jnew "java/lang/NullPointerException")])
    ((method-proc "java/lang/NullPointerException" "<init>()V") e)
    (eq-hashtable-set! npe-sites e site)
    (raise e)))
(define-syntax nn-at
  (syntax-rules () [(_ x site) (let ([o x]) (if o o (npe-at site)))]))
(define (jathrow-at x site) (raise (nn-at x site)))

(define (jcheckcast x target)
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
    [(_ ic x target)
     (let ([o x])
       (if (or (not o) (and (jobj? o) (eq? (jobj-cls o) (car ic)) (cdr ic)))
           o
           (begin (unless (jinstance-miss ic o target) (jcheckcast o target)) o)))]))

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
          [(not (jarr? x)) (error 'champaca "virtual call on something that isn't a Java value" x (symbol->string k))]
          [(eq? k clone-key) array-clone]
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
    [(_ ic x key site)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (if o (jvirt-miss ic o key) (npe-at site))))]))
(define (jvirt-miss ic o key)
  (let ([proc (jvirt o key)])
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
(define-syntax jinit-ic
  (syntax-rules () [(_ cell s) (unless (car cell) (jinit-cell! cell s))]))
(define (jinit-cell! cell s)
  (jinit-sym s)
  (let ([c (symbol-class s)])
    (when (eq? (jclass-state c) 'initialized) (set-car! cell c))))
(define-syntax jnew-ic
  (syntax-rules ()
    [(_ cell s) (let ([c (car cell)])
                  (if c
                      (make-jobj c (vector-copy (jclass-template c)))
                      (begin (jinit-cell! cell s) (jnew-sym s))))]))

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
  ;; desc is the full array type, e.g. "[[I"
  (let ([elem (substring desc 1 (string-length desc))])
    (if (null? (cdr dims))
        (jnewarray elem (car dims) (elem-default elem))
        (let ([a (jnewarray elem (car dims) #f)])
          (do ([i 0 (fx+ i 1)]) ((fx= i (car dims)) a)
            (vector-set! (jarr-vec a) i (jmultiarray elem (cdr dims))))))))

(define (bounds-error i v)
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
;; with the site's symbol.
(define-syntax field-ref
  (syntax-rules () [(_ x i site) (let ([o x]) (if o (vector-ref (jobj-slots o) i) (npe-at site)))]))
(define-syntax field-set!
  (syntax-rules () [(_ x i v site) (let ([o x]) (if o (vector-set! (jobj-slots o) i v) (npe-at site)))]))
;; jarr-vec is always a vector and the index has just been checked against
;; it, so the vector access itself can skip Chez's checks (#3%): safe code
;; checks the type and bounds again, which more than doubles a byte loop.
(define-syntax array-ref
  (syntax-rules ()
    [(_ a i site) (let ([arr a] [j i])
                    (if arr
                        (let ([v (jarr-vec arr)])
                          (if (fx< -1 j (#3%vector-length v)) (#3%vector-ref v j) (bounds-error j v)))
                        (npe-at site)))]))
(define-syntax array-set!
  (syntax-rules ()
    [(_ a i x site) (let ([arr a] [j i])
                      (if arr
                          (let ([v (jarr-vec arr)])
                            (if (fx< -1 j (#3%vector-length v)) (#3%vector-set! v j x) (bounds-error j v)))
                          (npe-at site)))]))
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
    [(_ ic a i x site) (let ([arr a] [j i] [o x])
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
                                  (array-store-miss ic arr o))
                                (#3%vector-set! v j o))
                              (bounds-error j v)))
                        (npe-at site)))]))
(define (array-store-ok? arr o)
  (let ([e (jarr-elem arr)])
    (or (string=? e "Ljava/lang/Object;") (jinstance? o (desc->class-name e)))))
;; The cell's car is cleared while it changes, so no store in between can
;; pair one element type with another's keys.
(define (array-store-miss ic arr o)
  (unless (array-store-ok? arr o)
    (jthrow "java/lang/ArrayStoreException" (slashes->dots (class-name-of o))))
  (let ([e (jarr-elem arr)] [k (store-key o)] [c (cdr ic)])
    (cond [(string=? e "Ljava/lang/Object;") (set-car! ic #f) (set-cdr! ic #t) (set-car! ic e)]
          [(not k)]
          [(and (eq? (car ic) e) (pair? c)) (set-cdr! ic (cons k (car c)))]
          [(and (eq? (car ic) e) c (not (eq? c #t))) (set-cdr! ic (cons k c))]
          [else (set-car! ic #f) (set-cdr! ic k) (set-car! ic e)])))
(define-syntax array-length
  (syntax-rules () [(_ a site) (let ([arr a]) (if arr (#3%vector-length (jarr-vec arr)) (npe-at site)))]))
(define-syntax wrap-byte (syntax-rules () [(_ x) (fx- (fxlogxor (fxlogand x #xff) #x80) #x80)]))
(define-syntax wrap-short (syntax-rules () [(_ x) (fx- (fxlogxor (fxlogand x #xffff) #x8000) #x8000)]))

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
    [else (fold-left (lambda (a u) (cons u a)) acc x)]))

;; makeConcatWithConstants: \x1 takes the next argument (a Scheme string or a
;; list of code units), \x2 the next bootstrap constant. Built as code units, so
;; a lone surrogate in any part survives.
(define (jconcat recipe args consts)
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
        (when unnamed-module
          (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "module") unnamed-module))
        (when (eq? (jclass-kind c) 'array)
          (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "componentType")
                       (jmirror (find-class (desc->class-name (substring (jclass-cname c) 1 (string-length (jclass-cname c))))))))
        m)))

;; Class.getModifiers: access flags minus ACC_SUPER (0x20), which shares its
;; bit with Modifier.SYNCHRONIZED; the JVM masks it off the same way. A member
;; class answers the flags from its own InnerClasses entry instead, which is
;; where static, private and protected live (the class's meta, reflect.ss).
(define (class-modifiers c)
  (let* ([m (jclass-meta c)]
         [inner (and (pair? (cddr m)) (fx> (length (caddr m)) 2) (caddr (caddr m)))])
    (fxlogand (if inner (caddr inner) (jclass-flags c)) #x761f)))

;; Every class lives in one unnamed module, as in OpenLDK: Class.getModule()
;; must not be null (resource bundles, getResourceAsStream), and an unnamed
;; module skips module access checks. Created after initPhase1, then set on
;; the mirrors made before it existed.
(define unnamed-module #f)
(define (install-unnamed-module!)
  (jinit "java/lang/Module")
  (let ([m (jnew "java/lang/Module")]
        [slot (jfield-index "java/lang/Class" "module")])
    ((method-proc "java/lang/Module" "<init>(Ljava/lang/ClassLoader;)V") m #f)
    (set! unnamed-module m)
    (vector-for-each (lambda (mirror) (vector-set! (jobj-slots mirror) slot m))
                     (hashtable-keys mirror->class))))

(define (jmirror-of name) (jmirror (find-class name)))
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

(define-record-type missing-native (fields name stack))

(define (native-ref name)
  (or (hashtable-ref natives name #f)
      (let loop ([rs native-rules])
        (cond [(null? rs) #f]
              [((caar rs) name) ((cdar rs) name)]
              [else (loop (cdr rs))]))
      (lambda args (raise (make-missing-native name jstack)))))

;; --- entry --------------------------------------------------------------------

(define (report-uncaught e)
  (flush-output-port (current-output-port))
  (let ([err (current-error-port)])
    (cond
      [(jobj? e)
       (put-string err (string-append "Exception in thread \"main\" "
                                      (guard (x [#t (class-name-of e)]) (jstring-of e)) "\n"))
       (print-jstack e err)]
      [(missing-native? e)
       (put-string err (string-append "champaca: missing native " (missing-native-name e) "\n"))
       (eq-hashtable-set! throw-stacks e (missing-native-stack e))
       (print-jstack e err)]
      [else
       (put-string err "champaca: Scheme error: ")
       (display-condition e err)
       (newline err)])))

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
            (begin (report-uncaught e) (k 1))))
      (lambda () (thunk) (flush-output-port (current-output-port)) 0)))))

(define (run-main cname . args)
  (set! halted #f)
  (let ([status (run-guarded
                 (lambda ()
                   (jinit cname)
                   ((method-proc cname "main([Ljava/lang/String;)V")
                    (scheme-list->jarray "Ljava/lang/String;" (map jstring args)))))])
    ;; as the java launcher: unless System.exit ended it, wait for the other
    ;; non-daemon threads and run the shutdown hooks, keeping main's status
    (if halted
        status
        (let ([s (run-guarded java-shutdown!)])
          (if halted s status)))))

;; --- backtraces (CHAMPACA_BT) ------------------------------------------------------
(define jstack '())
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
(define (native-override name) (hashtable-ref overrides name #f))

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
    [(#\F #\D) (i32 (double->bits v))]
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
(define (interpret-string s)
  (let ([p (open-input-string s)])
    (let loop ([r (void)])
      (let ([x (read p)])
        (if (eof-object? x) r (loop (interpret x)))))))

;; compile-file announces itself on stdout, which belongs to the Java program.
;; The cache's code carries no inspector information (free-variable names,
;; source): it nearly doubled boot.so (12.1 MB -> 6.8 MB without) and the time
;; to load it. Procedure names stay, and getCallerClass reads those. (Turning
;; it off for run-time JIT compiles saved nothing measurable.)
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

(define (read-all text)
  (let ([in (open-input-string text)])
    (let loop ([forms '()])
      (let ([x (read in)])
        (if (eof-object? x) (reverse forms) (loop (cons x forms)))))))

(define (add-unit! text)
  (let ([start (port-position unit-out)])
    (parameterize ([generate-inspector-information #f])
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
(define (compile-unit text)
  (let-values ([(p get) (open-bytevector-output-port)])
    (parameterize ([generate-inspector-information #f])
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
                (define-top-level-value sym
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
