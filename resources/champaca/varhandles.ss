;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; VarHandles: field, array, buffer and segment handles, their access modes
;; and the coordinate combinators. Split from natives.ss, whose defnative and
;; helpers these use; loaded after it.

;; --- VarHandles -------------------------------------------------------------------
;; A VarHandle is a bare java/lang/invoke/VarHandle object (allocated, never
;; constructed, so instanceof and checkcast work) mapped to what it points
;; at. Codegen sends every signature-polymorphic call on VarHandle to vh-call
;; with the access mode's name, so one procedure serves get, set,
;; compareAndSet, getAndAdd and the rest. Memory-ordering suffixes (Volatile,
;; Acquire, Release, Opaque, Plain) don't matter here: every access is
;; already sequentially consistent. A mode the JVM doesn't support for the
;; handle throws UnsupportedOperationException named after the mode, before
;; the receiver is looked at, as the JVM does: numeric updates on references
;; and booleans, bitwise ones on references and floating point, anything but
;; reads on a final field, and anything but plain get and set on a byte[]
;; view (JDK 25 can't promise a heap byte[] is aligned).
;;
;; kind   coordinates      a                      b
;; field  (obj)            slot index             -
;; static ()               the static's symbol    class to initialize
;; array  (array index)    -                      -
;; view   (byte[] index)   width in bytes         big-endian?
;;
;; type is the variable's descriptor, coords the coordinates' descriptors
;; (what coordinateTypes() shows), ops the operations it supports (plain-only?
;; when only the plain modes of those are), and parts each access type's
;; #(args ret sites), made when first called: sites maps a call site's
;; descriptor to whether its types are already the access type's.

(define-record-type vh-target (fields kind a b type coords ops plain-only? parts)
  (sealed #t) (nongenerative champaca-vh-target))
(define vh-targets (make-weak-eq-hashtable))

(define (vh-target-new kind a b type coords final?)
  (let ([ref? (memv (string-ref type 0) '(#\L #\[))])
    (make-vh-target kind a b type coords
                    (cond [(eq? kind 'view) '(get set)]
                          [final? '(get)]
                          [else (append '(get set cas cax gas)
                                        (if (or ref? (string=? type "Z")) '() '(add))
                                        (if (or ref? (member type '("F" "D"))) '() '(or and xor)))])
                    (eq? kind 'view) (make-vector 5 #f))))

(define vh-exact-slot #f)
(define (vh-exact-index)
  (or vh-exact-slot
      (begin (set! vh-exact-slot (jfield-index "java/lang/invoke/VarHandle" "exact")) vh-exact-slot)))

(define (vh-of t exact?)
  (let ([vh (jnew "java/lang/invoke/VarHandle")])
    (jslot-set! vh (vh-exact-index) (if exact? 1 0))
    (eq-hashtable-set! vh-targets vh t)
    vh))

(define (make-vh kind a b type coords final?)
  (vh-of (vh-target-new kind a b type coords final?) #f))

;; access mode name -> operation
(define vh-mode-ops
  (let ([t (make-hashtable string-hash string=?)])
    (for-each (lambda (e) (for-each (lambda (n) (hashtable-set! t n (car e))) (cdr e)))
              '((get "get" "getVolatile" "getOpaque" "getAcquire")
                (set "set" "setVolatile" "setOpaque" "setRelease")
                (cas "compareAndSet" "weakCompareAndSetPlain" "weakCompareAndSet"
                     "weakCompareAndSetAcquire" "weakCompareAndSetRelease")
                (cax "compareAndExchange" "compareAndExchangeAcquire" "compareAndExchangeRelease")
                (gas "getAndSet" "getAndSetAcquire" "getAndSetRelease")
                (add "getAndAdd" "getAndAddAcquire" "getAndAddRelease")
                (or "getAndBitwiseOr" "getAndBitwiseOrAcquire" "getAndBitwiseOrRelease")
                (and "getAndBitwiseAnd" "getAndBitwiseAndAcquire" "getAndBitwiseAndRelease")
                (xor "getAndBitwiseXor" "getAndBitwiseXorAcquire" "getAndBitwiseXorRelease")))
    t))

;; mode -> (op . plain?), cached by the identity of the name, which at a call
;; site is a literal
(define vh-mode-infos (make-weak-eq-hashtable))
(define (vh-mode-info mode)
  (or (eq-hashtable-ref vh-mode-infos mode #f)
      (let ([i (cons (hashtable-ref vh-mode-ops mode #f) (and (member mode '("get" "set")) #t))])
        (eq-hashtable-set! vh-mode-infos mode i)
        i)))

(define (vh-supports? t mode)
  (let ([i (vh-mode-info mode)])
    (and (memq (car i) (vh-target-ops t)) (or (cdr i) (not (vh-target-plain-only? t))) #t)))

(define (vh-target-of vh mode)
  (or (eq-hashtable-ref vh-targets (nn vh) #f)
      (jthrow "java/lang/UnsupportedOperationException" mode)))

(define (view-decode t u)
  (case t [("S") (i2s u)] [("I") (i32 u)] [("J") (i64 u)]
          [("F") (bits->float (i32 u))] [("D") (bits->double (i64 u))] [else u]))
(define (view-encode t v)
  (case t [("F") (float->bits v)] [("D") (double->bits v)] [else v]))

;; A reference array checks what it stores against its own element type, as
;; aastore does. Only a plain set names the class, as on the JVM.
(define (array-store-check a v mode)
  (when (and v (not (prim-desc? (jarr-elem (nn a)))) (not (array-store-ok? a v)))
    (jthrow "java/lang/ArrayStoreException"
            (and (string=? mode "set") (slashes->dots (class-name-of v))))))

;; read and write the variable at one access's coordinates
(define (vh-get t coords)
  (case (vh-target-kind t)
    [(field) (vector-ref (jobj-slots (nn (car coords))) (vh-target-a t))]
    [(static) (top-level-value (vh-target-a t))]
    [(array) (jaload (car coords) (cadr coords))]
    [else (view-decode (vh-target-type t)
                       (bytes-get (car coords) (cadr coords) (vh-target-a t) (vh-target-b t)))]))

(define (vh-set! t coords v mode)
  (case (vh-target-kind t)
    [(field) (vector-set! (jobj-slots (nn (car coords))) (vh-target-a t) v)]
    [(static) (set-top-level-value! (vh-target-a t) v)]
    [(array) (jastore (car coords) (cadr coords) v)]
    [else (bytes-set! (car coords) (cadr coords) (vh-target-a t) (vh-target-b t)
                      (view-encode (vh-target-type t) v))]))

(define (wrap-as t v)
  (case t [("I") (i32 v)] [("J") (i64 v)] [("S") (i2s v)] [("B") (i2b v)] [("C") (logand v #xffff)]
          [("F") (f32 v)] [else v]))

(define access-types '#("GET" "SET" "COMPARE_AND_SET" "COMPARE_AND_EXCHANGE" "GET_AND_UPDATE"))
(define (op-access-type op)
  (case op [(get) 0] [(set) 1] [(cas) 2] [(cax) 3] [else 4]))

;; #(args ret sites) of the access type, made once per handle
(define (vh-parts t k)
  (or (vector-ref (vh-target-parts t) k)
      (let* ([d (vh-access-type-desc t (vector-ref access-types k))]
             [p (vector (desc-args d) (desc-ret d) (make-weak-eq-hashtable))])
        (vector-set! (vh-target-parts t) k p)
        p)))

(define (method-type-text args ret)
  (jstring-of (desc->method-type (args->desc args ret))))

;; the JDK's text: the handle's type and the site's, each with the VarHandle
;; as a leading argument
(define (cannot-convert targs tret sargs sret)
  (define (with-vh text)
    (string-append "(VarHandle" (if (char=? (string-ref text 1) #\)) "" ",")
                   (substring text 1 (string-length text))))
  (jthrow "java/lang/invoke/WrongMethodTypeException"
          (string-append "cannot convert MethodHandle" (with-vh (method-type-text targs tret))
                         " to " (with-vh (method-type-text sargs sret)))))

;; A static's class is initialized first. A reference array checks the index,
;; then the value it may store, before anything is read or compared, which
;; is the JVM's order: a failing compareAndSet still throws for a bad value.
;; a derived handle (a coordinate combinator's) maps its coordinates onto its
;; base and converts values both ways, then the base does the access, so
;; updates and compares happen in the base's domain, as the JVM's adapters do
(define (vh-run t op mode args)
  (if (eq? (vh-target-kind t) 'derived)
      (let ([n (length (vh-target-coords t))])
        (let-values ([(coords to from) ((vh-target-b t) (list-head args n))])
          (let ([r (vh-run (vh-target-a t) op mode
                           (append coords (if to (map to (list-tail args n)) (list-tail args n))))])
            (if (and from (memq op '(get cax gas add or and xor))) (from r) r))))
      (vh-run-direct t op mode args)))

(define (vh-run-direct t op mode args)
  (if (eq? (vh-target-kind t) 'segment)
      (segment-access t op mode args)
      (vh-run-plain t op mode args)))

(define (vh-run-plain t op mode args)
  (let* ([ty (vh-target-type t)]
         [vals (list-tail args (length (vh-target-coords t)))])
    (define (get) (vh-get t args))
    (define (set v) (vh-set! t args v mode))
    (define (update f) (let ([old (get)]) (set (wrap-as ty (f old))) old))
    (case (vh-target-kind t)
      [(static) (jinit (vh-target-b t))]
      [(array)
       (case op
         [(set gas) (jaload (car args) (cadr args)) (array-store-check (car args) (car vals) mode)]
         [(cas cax) (jaload (car args) (cadr args)) (array-store-check (car args) (cadr vals) mode)])])
    (case op
      [(get) (get)]
      [(set) (set (car vals))]
      [(cas) (if (same-bits? (get) (car vals)) (begin (set (cadr vals)) 1) 0)]
      [(cax) (let ([old (get)]) (when (same-bits? old (car vals)) (set (cadr vals))) old)]
      [(gas) (let ([old (get)]) (set (car vals)) old)]
      [(add) (update (lambda (old) (+ old (car vals))))]
      [(or) (update (lambda (old) (logior old (car vals))))]
      [(and) (update (lambda (old) (logand old (car vals))))]
      [(xor) (update (lambda (old) (logxor old (car vals))))])))

;; whether a call site's types are already the access type's, remembered per
;; site descriptor
(define (vh-site-same? e site)
  (let ([v (eq-hashtable-ref (vector-ref e 2) site 'unknown)])
    (if (eq? v 'unknown)
        (let* ([sp (site-parts site)]
               [same? (and (equal? (car sp) (vector-ref e 0)) (string=? (cdr sp) (vector-ref e 1)))])
          (eq-hashtable-set! (vector-ref e 2) site same?)
          same?)
        v)))

;; site is the call site's descriptor (a literal, so its parse is cached by
;; identity). Its argument and result types are adapted to the access type's,
;; as asType would; a handle with invoke-exact behavior wants them equal.
(define (vh-call vh mode site . args)
  (let* ([t (vh-target-of vh mode)]
         [op (car (vh-mode-info mode))])
    (unless (vh-supports? t mode)
      (jthrow "java/lang/UnsupportedOperationException" mode))
    (let ([e (vh-parts t (op-access-type op))])
      (if (vh-site-same? e site)
          (vh-run t op mode args)
          (let* ([sp (site-parts site)] [sargs (car sp)] [targs (vector-ref e 0)] [tret (vector-ref e 1)])
            (unless (fx= (length sargs) (length targs))
              (jthrow "java/lang/invoke/WrongMethodTypeException"
                      (string-append "expected " (method-type-text targs tret)
                                     " but found " (method-type-text sargs (cdr sp)))))
            ;; a void on either side is a dropped or default result
            (unless (and (for-all prim-convertible? sargs targs)
                         (or (member "V" (list tret (cdr sp))) (prim-convertible? tret (cdr sp))))
              (cannot-convert targs tret sargs (cdr sp)))
            (when (eqv? 1 (jslot vh (vh-exact-index)))
              (jthrow "java/lang/invoke/WrongMethodTypeException"
                      (string-append "handle's method type " (method-type-text targs tret)
                                     " but found " (method-type-text sargs (cdr sp)))))
            (adapt tret (cdr sp) (vh-run t op mode (adapt-all sargs targs args))))))))

;; VarHandle's own methods. varType, coordinateTypes, accessModeType and
;; toString are the JDK's, built on accessModeTypeUncached.
(define (vh-access-type-desc t type-name)
  (let ([cs (vh-target-coords t)] [v (vh-target-type t)])
    (case type-name
      [("GET") (args->desc cs v)]
      [("SET") (args->desc (append cs (list v)) "V")]
      [("COMPARE_AND_SET") (args->desc (append cs (list v v)) "Z")]
      [("COMPARE_AND_EXCHANGE") (args->desc (append cs (list v v)) v)]
      [("GET_AND_UPDATE") (args->desc (append cs (list v)) v)])))

(define (access-mode-name m)
  (str-of (jslot (nn m) (jfield-index "java/lang/invoke/VarHandle$AccessMode" "methodName"))))

(define-override! "java/lang/invoke/VarHandle.accessModeTypeUncached(Ljava/lang/invoke/VarHandle$AccessType;)Ljava/lang/invoke/MethodType;"
  (lambda (this at) (desc->method-type (vh-access-type-desc (vh-target-of this "accessModeType") (enum-name (nn at))))))
(define-override! "java/lang/invoke/VarHandle.isAccessModeSupported(Ljava/lang/invoke/VarHandle$AccessMode;)Z"
  (lambda (this m)
    (let ([mode (access-mode-name m)])
      (if (vh-supports? (vh-target-of this mode) mode) 1 0))))
;; an unsupported mode's handle throws when called, as the JDK's does
(define-override! "java/lang/invoke/VarHandle.toMethodHandle(Ljava/lang/invoke/VarHandle$AccessMode;)Ljava/lang/invoke/MethodHandle;"
  (lambda (this m)
    (let* ([mode (access-mode-name m)] [t (vh-target-of this mode)]
           [at (enum-name (jslot m (jfield-index "java/lang/invoke/VarHandle$AccessMode" "at")))]
           [d (vh-access-type-desc t at)])
      (make-mh d (lambda args (apply vh-call this mode d args)) #f))))

;; MethodHandles.varHandleInvoker and varHandleExactInvoker, and findVirtual
;; on VarHandle for an access mode's name: a handle taking the VarHandle
;; first, then the mode's arguments as type has them. The exact one wants
;; the VarHandle's access type to be type, as invokeExact would; the other
;; converts as a call site would (vh-call)
(define (vh-invoker mode desc exact?)
  (let ([vh-args (cons "Ljava/lang/invoke/VarHandle;" (desc-args desc))])
    (make-mh (args->desc vh-args (desc-ret desc))
             (lambda (vh . xs)
               (unless vh
                 (jthrow "java/lang/NullPointerException"
                         "Cannot invoke \"java.lang.invoke.VarHandle.asDirect()\" because \"handle\" is null"))
               (let* ([e (vh-parts (vh-target-of vh mode) (op-access-type (car (vh-mode-info mode))))]
                      [targs (vector-ref e 0)] [tret (vector-ref e 1)]
                      [their (lambda () (method-type-text (cons "Ljava/lang/invoke/VarHandle;" targs) tret))])
                 (if exact?
                     (unless (and (equal? targs (desc-args desc)) (string=? tret (desc-ret desc)))
                       (jthrow "java/lang/invoke/WrongMethodTypeException"
                               (string-append "handle's method type " (their)
                                              " but found " (method-type-text vh-args (desc-ret desc)))))
                     ;; the access type asType'd to the invoker's, as the JDK's
                     (unless (and (fx= (length targs) (length (desc-args desc)))
                                  (for-all as-type-ok? (desc-args desc) targs)
                                  (as-type-ok? tret (desc-ret desc)))
                       (jthrow "java/lang/invoke/WrongMethodTypeException"
                               (string-append "cannot convert MethodHandle" (their)
                                              " to " (method-type-text vh-args (desc-ret desc)))))))
               (apply vh-call vh mode desc xs))
             #f)))
(define (vh-mode-name? name) (and (hashtable-ref vh-mode-ops name #f) #t))
(define-override! "java/lang/invoke/MethodHandles.varHandleInvoker(Ljava/lang/invoke/VarHandle$AccessMode;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
  (lambda (m mt) (vh-invoker (access-mode-name m) (mt-desc (nn mt)) #f)))
(define-override! "java/lang/invoke/MethodHandles.varHandleExactInvoker(Ljava/lang/invoke/VarHandle$AccessMode;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
  (lambda (m mt) (vh-invoker (access-mode-name m) (mt-desc (nn mt)) #t)))

;; the field named fname in class c or a superclass: (declaring-class . meta)

;; A field by name and descriptor as JVMS 5.4.3.2 resolves one: the class's
;; own, then its superinterfaces', then its superclass's. (class name desc
;; flags ...) or #f.
(define (find-field c fname desc)
  (let loop ([c c])
    (and c
         (let ([m (find (lambda (f) (and (string=? (car f) fname) (string=? (cadr f) desc)))
                        (cadr (jclass-meta c)))])
           (cond [m (cons c m)]
                 [(exists (lambda (i) (loop (find-class i))) (jclass-interfaces c))]
                 [(jclass-super c) (loop (find-class (jclass-super c)))]
                 [else #f])))))

;; The field a Lookup or a field handle constant names, with the JDK's
;; failures (MemberName and its makeAccessException): NoSuchFieldException
;; caused by the VM's NoSuchFieldError when nothing has that name and type,
;; IllegalAccessException when it's static and wasn't asked to be or the
;; reverse, or when a setter is asked of a final field (final-checked?, a
;; Lookup's find*Setter, unless the Lookup is trusted). lookup is the Lookup asking, for the text's
;; "from", or #f. Access isn't checked.
(define (field-kind static? setter?) (if static? (if setter? "putStatic" "getStatic") (if setter? "putField" "getField")))
(define (mirror-name m) (jstring->scheme ((jvirt m "getName()Ljava/lang/String;") m)))
(define (lookup-from-text lookup)
  (cond [(not lookup) ""]
        [(eq? lookup (begin (jinit "java/lang/invoke/MethodHandles")
                            ((method-proc "java/lang/invoke/MethodHandles" "publicLookup()Ljava/lang/invoke/MethodHandles$Lookup;"))))
         ", from public Lookup"]
        [else
         (let* ([lk "java/lang/invoke/MethodHandles$Lookup"]
                [lc (jslot lookup (jfield-index lk "lookupClass"))]
                [plc (jslot lookup (jfield-index lk "prevLookupClass"))]
                [module-of (lambda (m) (jstring-of ((jvirt m "getModule()Ljava/lang/Module;") m)))])
           (string-append ", from " (jstring-of lc) " (" (module-of lc) ")"
                          (if plc (string-append ", previous lookup " (mirror-name plc) " (" (module-of plc) ")") "")))]))
(define (resolve-field c fname type static? setter? final-checked? lookup)
  (let* ([desc (mirror->desc type)] [hit (find-field c fname desc)]
         [member (lambda (cls kind) (string-append (slashes->dots (jclass-cname cls)) "." fname "/" (mirror-name type) "/" kind))]
         [access (lambda (what cls kind)
                   (jthrow "java/lang/IllegalAccessException" (string-append what ": " (member cls kind) (lookup-from-text lookup))))])
    (unless hit
      (let ([e (construct "java/lang/NoSuchFieldException" "(Ljava/lang/String;)V"
                          (jstring-lit (string-append "no such field: " (member c (field-kind static? setter?)))))])
        ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;")
         e (construct "java/lang/NoSuchFieldError" "(Ljava/lang/String;)V"
                      (jstring-lit (string-append "Class " (slashes->dots (jclass-cname c)) " does not have member field '"
                                                  (type-external desc) " " fname "'"))))
        (raise e)))
    (let ([found-static? (static-flag? (list-ref hit 3))])
      (unless (eq? found-static? static?)
        (access (if static? "expected a static field" "expected a non-static field") (car hit) (field-kind found-static? setter?)))
      ;; a TRUSTED lookup (allowedModes -1, the JDK's IMPL_LOOKUP) checks no access
      (when (and setter? final-checked? (fxpositive? (fxlogand (list-ref hit 3) #x10))
                 (not (eqv? -1 (jslot lookup (jfield-index "java/lang/invoke/MethodHandles$Lookup" "allowedModes")))))
        (access "unexpected set of a final field" (car hit) (field-kind static? setter?)))
      (when lookup
        (lookup-access! lookup (if static? (if setter? 4 2) (if setter? 3 1)) c hit))
      hit)))

(define (field-vh recv name type static? lookup)
  (let* ([c (mirror-class (nn recv))] [fname (jstring->scheme (nn name))]
         [_ (when lookup (check-symbolic-class! lookup c))]
         [hit (resolve-field c fname (nn type) static? #f #f lookup)])
    (let ([dc (car hit)] [desc (cadr (cdr hit))] [final? (fxpositive? (fxlogand (caddr (cdr hit)) #x10))])
      (if static?
          (make-vh 'static (string->symbol (string-append (jclass-cname dc) ":" fname)) (jclass-cname dc) desc
                   '() final?)
          (make-vh 'field (jfield-index (jclass-cname dc) fname) #f desc (list (cname->desc (jclass-cname c))) final?)))))

;; the same variable, with or without invoke-exact behavior
(define-override! "java/lang/invoke/VarHandle.withInvokeExactBehavior()Ljava/lang/invoke/VarHandle;"
  (lambda (this) (vh-of (vh-target-of this "withInvokeExactBehavior") #t)))
(define-override! "java/lang/invoke/VarHandle.withInvokeBehavior()Ljava/lang/invoke/VarHandle;"
  (lambda (this) (vh-of (vh-target-of this "withInvokeBehavior") #f)))

;; --- coordinate combinators ---------------------------------------------------------
;; Each returns a 'derived vh-target over the base: type and coords are the
;; new handle's, ops (and the byte[] view restriction) are the base's, as on
;; the JVM, and b is a procedure from the new coordinates to the base's plus
;; the value conversions. Checks run at construction, in the JDK's order and
;; with its messages (VarHandles.java), including printing coordinate i where
;; it means pos + i in the filter-type message.

(define (vh-derived base type coords adapter)
  (vh-of (make-vh-target 'derived base adapter type coords
                         (vh-target-ops base) (vh-target-plain-only? base) (make-vector 5 #f))
         #f))

(define (mh-type-text mh)
  (jstring-of (jslot (nn mh) (jfield-index "java/lang/invoke/MethodHandle" "type"))))
(define (vh-class-text d) (jstring-of (desc->mirror d)))
(define (vh-coords-text ds)
  (string-append "[" (fold-left (lambda (acc d) (if acc (string-append acc ", " (vh-class-text d)) (vh-class-text d))) #f ds) "]"))
(define (vh-iae . parts) (jthrow "java/lang/IllegalArgumentException" (apply string-append parts)))

;; the elements of a java.util.List, via its own size and get
(define (jlist->list l)
  (let ([size ((jvirt l "size()I") l)]
        [get (jvirt l "get(I)Ljava/lang/Object;")])
    (let loop ([i 0]) (if (fx= i size) '() (cons (get l i) (loop (fx+ i 1)))))))
(define (vh-bad-pos pos ds)
  (vh-iae "Invalid position " (number->string pos) " for coordinate types: " (vh-coords-text ds)))
(define (vh-check-pos pos ds) (when (or (fx< pos 0) (fx>= pos (length ds))) (vh-bad-pos pos ds)))

;; take, drop and splice over coordinate lists
(define (list-insert-at xs pos ys)
  (append (list-head xs pos) ys (list-tail xs pos)))
(define (list-remove-range xs pos k)
  (append (list-head xs pos) (list-tail xs (fx+ pos k))))

;; a value for a coordinate of descriptor d, checked the way the JDK inserts
;; one: a reference is cast (null passes), a primitive unboxes and may widen
(define (vh-insert-value d v)
  (if (prim-desc? d)
      (let ([p (begin (unless v (jthrow "java/lang/NullPointerException"
                                        "Cannot invoke \"Object.getClass()\" because \"x\" is null"))
                      (box-prim-of v))])
        ;; a widening box converts; the message names the wanted wrapper first
        (unless (and p (prim-convertible? p d))
          (jthrow "java/lang/ClassCastException"
                  (string-append "class " (slashes->dots (cadr (assoc d box-classes)))
                                 " is not compatible with class " (slashes->dots (class-name-of v)))))
        (widen p d (junbox p v)))
      (begin
        (when (and v (not (jinstance? v (desc->class-name d))))
          (jthrow "java/lang/ClassCastException"
                  (string-append "Cannot cast " (slashes->dots (class-name-of v)) " to " (slashes->dots (desc->class-name d)))))
        v)))

(define-override! "java/lang/invoke/MethodHandles.insertCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos values*)
    (let* ([t (vh-target-of (nn vh) "insertCoordinates")]
           [ds (vh-target-coords t)]
           [vs (vector->list (jarr-vec (nn values*)))])
      (vh-check-pos pos ds)
      (when (fx> (fx+ pos (length vs)) (length ds)) (vh-iae "Too many values"))
      (if (null? vs)
          vh
          (let ([converted (map vh-insert-value (list-head (list-tail ds pos) (length vs)) vs)])
            (vh-derived t (vh-target-type t) (list-remove-range ds pos (length vs))
                        (lambda (cs) (values (list-insert-at cs pos converted) #f #f))))))))

(define-override! "java/lang/invoke/MethodHandles.dropCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos types)
    (let* ([t (vh-target-of (nn vh) "dropCoordinates")]
           [ds (vh-target-coords t)]
           [extra (map mirror->desc (vector->list (jarr-vec (nn types))))])
      (when (or (fx< pos 0) (fx> pos (length ds))) (vh-bad-pos pos ds))
      (if (null? extra)
          vh
          (vh-derived t (vh-target-type t) (list-insert-at ds pos extra)
                      (lambda (cs) (values (list-remove-range cs pos (length extra)) #f #f)))))))

;; filter i converts new coordinate pos+i into the base's
(define-override! "java/lang/invoke/MethodHandles.filterCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos filters)
    (let* ([t (vh-target-of (nn vh) "filterCoordinates")]
           [ds (vh-target-coords t)]
           [fs (vector->list (jarr-vec (nn filters)))])
      (vh-check-pos pos ds)
      (when (fx> (fx+ pos (length fs)) (length ds)) (vh-iae "Too many filters"))
      (if (null? fs)
          vh
          (let loop ([i 0] [coords ds] [procs '()])
            (if (fx= i (length fs))
                (let ([pv (list->vector (reverse procs))])
                  (vh-derived t (vh-target-type t) coords
                              (lambda (cs)
                                (values (let conv ([cs cs] [k 0])
                                          (cond [(null? cs) '()]
                                                [(and (fx>= k pos) (fx< k (fx+ pos (length fs))))
                                                 (cons ((vector-ref pv (fx- k pos)) (car cs)) (conv (cdr cs) (fx+ k 1)))]
                                                [else (cons (car cs) (conv (cdr cs) (fx+ k 1)))]))
                                        #f #f))))
                (let* ([f (nn (list-ref fs i))] [info (mh-info-of f)])
                  (unless (fx= (length (mh-info-args info)) 1)
                    (vh-iae "Invalid filter type " (mh-type-text f)))
                  (unless (string=? (list-ref coords (fx+ pos i)) (mh-info-ret info))
                    ;; the JDK prints coordinate i here, not pos + i
                    (vh-iae "Invalid filter type " (mh-type-text f) " for coordinate type " (vh-class-text (list-ref coords i))))
                  (loop (fx+ i 1)
                        (append (list-head coords (fx+ pos i)) (list (car (mh-info-args info))) (list-tail coords (fx+ pos i 1)))
                        (cons (mh-info-proc info) procs)))))))))

(define-override! "java/lang/invoke/MethodHandles.permuteCoordinates(Ljava/lang/invoke/VarHandle;Ljava/util/List;[I)Ljava/lang/invoke/VarHandle;"
  (lambda (vh new-list reorder*)
    (let* ([t (vh-target-of (nn vh) "permuteCoordinates")]
           [ds (vh-target-coords t)]
           [new (map mirror->desc (jlist->list (nn new-list)))]
           [reorder (vector->list (jarr-vec (nn reorder*)))]
           [mt (lambda (as) (method-type-text as "V"))])
      (unless (fx= (length reorder) (length ds))
        (vh-iae "old type parameter count and reorder array length do not match: " (mt ds)
                ", [" (fold-left (lambda (acc k) (if acc (string-append acc ", " (number->string k)) (number->string k))) #f reorder) "]"))
      (let loop ([i 0])
        (when (fx< i (length reorder))
          (let ([k (list-ref reorder i)])
            (when (or (fx< k 0) (fx>= k (length new)))
              (vh-iae "index is out of bounds for new type: " (number->string k) ", " (mt new)))
            (unless (string=? (list-ref ds i) (list-ref new k))
              (vh-iae "parameter types do not match after reorder: " (mt ds) ", " (mt new)))
            (loop (fx+ i 1)))))
      (vh-derived t (vh-target-type t) new
                  (lambda (cs) (values (map (lambda (k) (list-ref cs k)) reorder) #f #f))))))

(define-override! "java/lang/invoke/MethodHandles.collectCoordinates(Ljava/lang/invoke/VarHandle;ILjava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos filter)
    (let* ([t (vh-target-of (nn vh) "collectCoordinates")]
           [ds (vh-target-coords t)]
           [info (mh-info-of (nn filter))]
           [k (length (mh-info-args info))]
           [void? (string=? (mh-info-ret info) "V")])
      (vh-check-pos pos ds)
      (unless (or void? (string=? (mh-info-ret info) (list-ref ds pos)))
        (vh-iae "Invalid filter type " (mh-type-text filter) " for coordinate type " (vh-class-text (list-ref ds pos))))
      ;; a void filter runs for effect: the coordinate at pos stays, and the
      ;; filter's parameters go in front of it
      (vh-derived t (vh-target-type t)
                  (append (list-head ds pos) (mh-info-args info) (list-tail ds (if void? pos (fx+ pos 1))))
                  (lambda (cs)
                    (let ([r (apply (mh-info-proc info) (list-head (list-tail cs pos) k))])
                      (values (append (list-head cs pos)
                                      (if void? '() (list r))
                                      (list-tail cs (fx+ pos k)))
                              #f #f)))))))

;; to converts a new-type value into the base's type, from the other way;
;; extra leading filter parameters become trailing coordinates
(define-override! "java/lang/invoke/MethodHandles.filterValue(Ljava/lang/invoke/VarHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh to from)
    (let* ([t (vh-target-of (nn vh) "filterValue")]
           [ti (mh-info-of (nn to))] [fi (mh-info-of (nn from))]
           [tn (length (mh-info-args ti))] [fn (length (mh-info-args fi))]
           [last (lambda (xs) (list-ref xs (fx- (length xs) 1)))]
           [vt (vh-target-type t)]
           [both (lambda () (string-append (mh-type-text from) ", " (mh-type-text to)))])
      (cond
        [(not (fx= fn tn))
         (vh-iae "filterFromTarget and filterToTarget have different arity: " (both))]
        [(fx< fn 1) (vh-iae "filterFromTarget filter type has wrong arity: " (mh-type-text from))]
        [(or (not (string=? (last (mh-info-args fi)) (mh-info-ret ti)))
             (not (string=? (last (mh-info-args ti)) (mh-info-ret fi))))
         (vh-iae "filterFromTarget and filterToTarget filter types do not match: " (both))]
        [(not (string=? vt (last (mh-info-args fi))))
         (vh-iae "filterFromTarget filter type does not match target var handle type: "
                 (mh-type-text from) ", " (vh-class-text vt))]
        [(not (string=? vt (mh-info-ret ti)))
         (vh-iae "filterFromTarget filter type does not match target var handle type: "
                 (mh-type-text to) ", " (vh-class-text vt))]
        [else
         (let ([extra (list-head (mh-info-args fi) (fx- fn 1))])
           (for-each (lambda (a b) (unless (string=? a b)
                                     (vh-iae "filterFromTarget and filterToTarget filter types do not match: " (both))))
                     extra (list-head (mh-info-args ti) (fx- tn 1)))
           (let ([nds (length (vh-target-coords t))]
                 [top (mh-info-proc ti)] [fromp (mh-info-proc fi)])
             (vh-derived t (mh-info-ret fi) (append (vh-target-coords t) extra)
                         (lambda (cs)
                           (let ([base (list-head cs nds)] [ex (list-tail cs nds)])
                             (values base
                                     (lambda (v) (apply top (append ex (list v))))
                                     (lambda (r) (apply fromp (append ex (list r))))))))))]))))

(define-override! "java/lang/invoke/MethodHandles$Lookup.findVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #f this)))
(define-override! "java/lang/invoke/MethodHandles$Lookup.findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #t this)))

;; from a reflective Field, which names the declaring class
(define-override! "java/lang/invoke/MethodHandles$Lookup.unreflectVarHandle(Ljava/lang/reflect/Field;)Ljava/lang/invoke/VarHandle;"
  (lambda (this f)
    (let ([slot (lambda (n) (jslot (nn f) (jfield-index "java/lang/reflect/Field" n)))])
      (field-vh (slot "clazz") (slot "name") (slot "type") (static-flag? (slot "modifiers")) #f))))

(define (array-class-elem cls)
  (let ([n (jclass-cname (mirror-class (nn cls)))])
    (unless (char=? (string-ref n 0) #\[)
      (jthrow "java/lang/IllegalArgumentException" (string-append "not an array class: " n)))
    (substring n 1 (string-length n))))

(define-override! "java/lang/invoke/MethodHandles.arrayElementVarHandle(Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls) (make-vh 'array #f #f (array-class-elem cls) (list (mirror->desc cls) "I") #f)))
;; A memory segment's VarHandle is the JDK's own SegmentVarHandle (its form
;; and fields, so checkSegment and the alignment checks run as written) held
;; by a champaca handle of kind segment, whose accesses call the static named
;; after the mode in the form's implClass or its superclass, as HotSpot links
;; them through the VarForm: VarHandleSegmentAsInts.get(VarHandle, Object,
;; long) and the rest, which reach memory through ScopedMemoryAccess.
;; Coordinates are (MemorySegment long), plus a second long when the offset
;; isn't constant. A mode is supported when such a static exists: an
;; unaligned layout's form (VarHandleSegmentAsInts) has plain get and set
;; only, an aligned one's (VarHandleSegmentAsIntsAligned) every mode its
;; carrier allows.
(define segment-forms
  '(("B" . "Bytes") ("C" . "Chars") ("S" . "Shorts") ("I" . "Ints") ("J" . "Longs")
    ("F" . "Floats") ("D" . "Doubles") ("Z" . "Booleans")))

;; the descriptor of a form's static for op: the JDK's VarHandle, the object
;; coordinate as Object, the other coordinates as they are, then the values
(define (segment-desc ty op coords)
  (string-append "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;" (apply string-append (cdr coords))
                 (case op [(get) ""] [(cas cax) (string-append ty ty)] [else ty])
                 ")" (case op [(set) "V"] [(cas) "Z"] [else ty])))

(define segment-op-modes
  '((get . "get") (set . "set") (cas . "compareAndSet") (cax . "compareAndExchange")
    (gas . "getAndSet") (add . "getAndAdd") (or . "getAndBitwiseOr")
    (and . "getAndBitwiseAnd") (xor . "getAndBitwiseXor")))

(define-override! "java/lang/invoke/VarHandles.memorySegmentViewHandle(Ljava/lang/Class;Ljava/lang/foreign/MemoryLayout;JZJLjava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (carrier enclosing mask constant? offset order)
    (let* ([d (mirror->desc (nn carrier))]
           [form (assoc d segment-forms)])
      (unless form
        (jthrow "java/lang/IllegalArgumentException"
                (string-append "Invalid carrier: " (jstring->scheme ((jvirt carrier "getName()Ljava/lang/String;") carrier)))))
      (let* ([fc (string-append "java/lang/invoke/VarHandleSegmentAs" (cdr form))]
             [be? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))]
             [_ (begin (jinit "java/lang/invoke/MethodHandleStatics") (jinit fc) (jinit "java/lang/invoke/SegmentVarHandle"))]
             [exact (top-level-value (string->symbol "java/lang/invoke/MethodHandleStatics:VAR_HANDLE_SEGMENT_FORCE_EXACT"))]
             [vform ((method-proc fc "selectForm(JZ)Ljava/lang/invoke/VarForm;") mask constant?)]
             [impl (find-class (jclass-cname (mirror-class (jslot vform (jfield-index "java/lang/invoke/VarForm" "implClass")))))]
             [coords (if (eqv? constant? 1)
                         '("Ljava/lang/foreign/MemorySegment;" "J")
                         '("Ljava/lang/foreign/MemorySegment;" "J" "J"))]
             [has? (lambda (op name) (and (find-method impl name (segment-desc d op coords)) #t))]
             [jvh (jnew "java/lang/invoke/SegmentVarHandle")])
        ((method-proc "java/lang/invoke/SegmentVarHandle"
                      "<init>(Ljava/lang/invoke/VarForm;ZLjava/lang/foreign/MemoryLayout;JZ)V")
         jvh vform (if be? 1 0) enclosing offset exact)
        (vh-of (make-vh-target 'segment jvh impl d coords
                               (map car (filter (lambda (e) (has? (car e) (cdr e))) segment-op-modes))
                               (not (has? 'get "getVolatile"))
                               (make-vector 5 #f))
               (eqv? exact 1))))))

;; the static for a mode, cached by the mode name's identity
(define segment-procs (make-weak-eq-hashtable))
(define (segment-access t op mode args)
  (let* ([procs (or (eq-hashtable-ref segment-procs t #f)
                    (let ([h (make-weak-eq-hashtable)]) (eq-hashtable-set! segment-procs t h) h))]
         [proc (or (eq-hashtable-ref procs mode #f)
                   (let* ([key (string-append mode (segment-desc (vh-target-type t) op (vh-target-coords t)))]
                          [m (find-method (vh-target-b t) mode (substring key (string-length mode) (string-length key)))]
                          [p (method-proc (jclass-cname (car m)) key)])
                     (eq-hashtable-set! procs mode p)
                     p))])
    (apply proc (vh-target-a t) args)))

;; MethodHandles.byteBufferViewVarHandle: the JDK's own ByteBufferHandle for
;; the view type, wrapped as a segment handle is, so each access mode calls
;; its form's static and the JDK's bounds, alignment and read-only checks
;; run as written
(define byte-buffer-views
  '(("J" . "Longs") ("I" . "Ints") ("S" . "Shorts") ("C" . "Chars") ("D" . "Doubles") ("F" . "Floats")))
(define-override! "java/lang/invoke/VarHandles.makeByteBufferViewHandle(Ljava/lang/Class;Z)Ljava/lang/invoke/VarHandle;"
  (lambda (cls be)
    (let ([d (mirror->desc (nn cls))])
      (unless (char=? (string-ref d 0) #\[)
        (jthrow "java/lang/IllegalArgumentException"
                (string-append "not an array: " (jstring->scheme ((jvirt cls "toString()Ljava/lang/String;") cls)))))
      (let* ([e (substring d 1 (string-length d))]
             [view (or (assoc e byte-buffer-views) (jthrow "java/lang/UnsupportedOperationException" #f))]
             [hc (string-append "java/lang/invoke/VarHandleByteArrayAs" (cdr view) "$ByteBufferHandle")]
             [_ (jinit hc)]
             [jvh (let ([o (jnew hc)]) ((method-proc hc "<init>(Z)V") o be) o)]
             [impl (find-class hc)]
             [coords '("Ljava/nio/ByteBuffer;" "I")]
             [has? (lambda (op name) (and (find-method impl name (segment-desc e op coords)) #t))])
        (vh-of (make-vh-target 'segment jvh impl e coords
                               (map car (filter (lambda (m) (has? (car m) (cdr m))) segment-op-modes))
                               (not (has? 'get "getVolatile"))
                               (make-vector 5 #f))
               #f)))))

(define-override! "java/lang/invoke/MethodHandles.byteArrayViewVarHandle(Ljava/lang/Class;Ljava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls order)
    (let ([e (array-class-elem cls)]
          [big? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))])
      (unless (member e '("S" "C" "I" "J" "F" "D"))
        (jthrow "java/lang/UnsupportedOperationException" (string-append "not a view type: " e)))
      (make-vh 'view (type-size e) big? e '("[B" "I") #f))))
