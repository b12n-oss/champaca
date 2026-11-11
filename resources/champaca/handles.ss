;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Method handles, and the switch bootstraps behind pattern switches.
;;
;; A MethodHandle is a bare java/lang/invoke/MethodHandle object (allocated,
;; never constructed) whose `type` field holds a real MethodType, mapped in
;; mh-infos to a Scheme procedure and the descriptor it takes and returns.
;; The JDK builds handles out of MemberNames and LambdaForms, which need the
;; VM's resolver and run-time class generation, so the Lookup.find* methods,
;; asType, bindTo and the common MethodHandles combinators are overridden
;; here instead. Codegen sends invoke to mh-call with the call site's
;; descriptor, and mh-call converts arguments and result between the two
;; descriptors: boxing, unboxing and primitive widening, as asType would.
;; invokeExact goes to mh-call-exact, which requires the call site's
;; descriptor to be the handle's type. Access checks are not done (spike).

(define-record-type mh-info (fields desc args ret proc varargs?) (sealed #t) (nongenerative champaca-mh-info))
(define obj-desc "Ljava/lang/Object;")
(define mh-infos (make-weak-eq-hashtable))

(define (prim->desc name)
  (let ([p (find (lambda (p) (string=? (cdr p) name)) prim-names)]) (and p (car p))))

(define (cname->desc n)
  (cond [(char=? (string-ref n 0) #\[) n]
        [(prim->desc n)]
        [else (string-append "L" n ";")]))

(define (mirror->desc m) (cname->desc (jclass-cname (mirror-class (nn m)))))

(define (mt-desc mt)
  (let ([r (jslot (nn mt) (jfield-index "java/lang/invoke/MethodType" "rtype"))]
        [ps (jslot mt (jfield-index "java/lang/invoke/MethodType" "ptypes"))])
    (string-append "(" (apply string-append (map mirror->desc (vector->list (jarr-vec ps)))) ")"
                   (mirror->desc r))))

(define (desc->method-type desc)
  (jinit "java/lang/invoke/MethodType")
  ((method-proc "java/lang/invoke/MethodType" "methodType(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;")
   (desc->mirror (desc-ret desc)) (mirror-array (desc-args desc))))

(define (args->desc args ret) (string-append "(" (apply string-append args) ")" ret))

;; type: the MethodType object to show, or #f to make one from desc
(define (make-mh desc proc type . varargs?)
  (let ([mh (jnew "java/lang/invoke/MethodHandle")])
    (jslot-set! mh (jfield-index "java/lang/invoke/MethodHandle" "type") (or type (desc->method-type desc)))
    (eq-hashtable-set! mh-infos mh (make-mh-info desc (desc-args desc) (desc-ret desc) proc
                                                 (and (pair? varargs?) (car varargs?))))
    mh))

(define (mh-info-of mh)
  (or (eq-hashtable-ref mh-infos (nn mh) #f)
      (jthrow "java/lang/UnsupportedOperationException" "not a champaca method handle")))

;; --- conversions between two descriptors -------------------------------------------

(define (box-prim-of x)
  (and (jobj? x)
       (let ([e (find (lambda (e) (string=? (cadr e) (jclass-cname (jobj-cls x)))) box-classes)])
         (and e (car e)))))

;; can asType convert a value of type `from` into `to`: identity, a prim
;; widening, boxing, an unboxing that widens, a reference cast, and anything
;; into or out of void (a void source reads as the default value)
(define (as-type-ok? from to)
  (cond [(string=? from to) #t]
        [(or (string=? from "V") (string=? to "V")) #t]
        [(prim-desc? from)
         (if (prim-desc? to)
             (prim-convertible? from to)
             ;; boxing: the wrapper must be assignable to the target reference
             (and (not (char=? #\[ (string-ref to 0)))
                  (class-assignable? (cadr (assoc from box-classes)) (desc->class-name to))))]
        [(prim-desc? to)
         ;; unboxing: any supertype of the wrapper (Object, Number, Comparable,
         ;; Constable, ...), or a wrapper whose primitive widens
         (and (not (char=? #\[ (string-ref from 0)))
              (let ([fname (desc->class-name from)]
                    [wname (cadr (assoc to box-classes))])
                (or (class-assignable? wname fname)
                    (let ([e (find (lambda (e) (string=? (cadr e) fname)) box-classes)])
                      (and e (prim-convertible? (car e) to))))))]
        [else #t]))

;; an integer widened to float rounds to float
(define (widen from to v)
  (if (and (memv (string-ref to 0) '(#\F #\D)) (not (memv (string-ref from 0) '(#\F #\D))))
      (if (char=? (string-ref to 0) #\F) (f32 (inexact v)) (inexact v))
      v))

;; whether asType converts a primitive `from` to a primitive `to`: the same
;; type or a widening one
(define prim-widenings
  '(("B" "S" "I" "J" "F" "D") ("S" "I" "J" "F" "D") ("C" "I" "J" "F" "D")
    ("I" "J" "F" "D") ("J" "F" "D") ("F" "D")))
(define (prim-convertible? from to)
  (or (string=? from to)
      (not (prim-desc? from)) (not (prim-desc? to))
      (let ([w (assoc from prim-widenings)]) (and w (member to (cdr w)) #t))))

;; v has type `from` and is wanted as `to`. explicitCastArguments passes
;; loose? to unbox any wrapper, which asType rejects when it would narrow.
(define (adapt from to v . loose?)
  (cond [(pair? loose?) (explicit-adapt from to v)]
        [(string=? from to) v]
        [(string=? to "V") v]
        [(string=? from "V") (if (prim-desc? to) (elem-default to) #f)]
        [(prim-desc? from) (if (prim-desc? to) (widen from to v) (jbox from v))]
        [(prim-desc? to)
         (let ([p (box-prim-of (nn v))])
           (unless p
             (jthrow "java/lang/ClassCastException"
                     (string-append "Cannot cast " (class-name-of v) " to " (cdr (assoc to prim-names)))))
           ;; only a widening unbox: a Long is not an int
           (unless (prim-convertible? p to)
             (jthrow "java/lang/ClassCastException"
                     (string-append "Cannot cast " (slashes->dots (class-name-of v)) " to "
                                    (slashes->dots (cadr (assoc to box-classes))))))
           (widen p to (junbox p v)))]
        ;; a reference narrowing is Class.cast, as asType makes it, with its text
        [(or (not v) (string=? to obj-desc)) v]
        [(jinstance? v (desc->class-name to)) v]
        [else (jthrow "java/lang/ClassCastException"
                      (string-append "Cannot cast " (external-class-name (class-name-of v))
                                     " to " (external-class-name (desc->class-name to))))]))

;; explicitCastArguments' conversions (MethodHandles' javadoc, the JDK's
;; ValueConversions): a primitive to a primitive by Java's casting
;; conversions, a boolean as the integer 1 or 0 and any value to boolean as
;; its low bit (a float or double first cast to int); a reference to a
;; primitive unboxed, null as zero or false, then cast the same way, through
;; Number when the reference isn't a wrapper, so a String fails as the
;; checkcast to Number does; a primitive to a reference boxed in its own
;; wrapper, then cast; a reference cast to an interface not checked at all
(define (explicit-adapt from to v)
  (cond [(string=? from to) v]
        [(string=? to "V") v]
        [(string=? from "V") (if (prim-desc? to) (elem-default to) #f)]
        [(prim-desc? from) (if (prim-desc? to) (prim-cast from to v) (explicit-ref-cast to (jbox from v)))]
        [(prim-desc? to) (explicit-unbox to v)]
        [else (explicit-ref-cast to v)]))

(define (prim-cast from to v)
  (let ([f (string-ref from 0)] [t (string-ref to 0)])
    (cond [(char=? f t) v]
          [(char=? t #\Z) (if (odd? (integral-of f #\I v)) 1 0)]
          [(char=? t #\F) (f32 (inexact v))]
          [(char=? t #\D) (inexact v)]
          [else (narrow-to t (integral-of f t v))])))
;; v of primitive type f as an integer: a float or double as d2l for a long,
;; d2i for anything narrower
(define (integral-of f t v)
  (if (memv f '(#\F #\D)) (if (char=? t #\J) (d2l v) (d2i v)) v))
;; an integer as integral type t keeps it: its low bits, sign-extended but
;; for char
(define (narrow-to t i)
  (case t
    [(#\J) (low-bits i 64 #t)]
    [(#\I) (low-bits i 32 #t)]
    [(#\S) (low-bits i 16 #t)]
    [(#\C) (low-bits i 16 #f)]
    [else (low-bits i 8 #t)]))
(define (low-bits x bits signed?)
  (let ([m (bitwise-bit-field x 0 bits)])
    (if (and signed? (bitwise-bit-set? m (fx- bits 1))) (- m (expt 2 bits)) m)))

(define (explicit-unbox to v)
  (let ([p (and v (box-prim-of v))])
    (cond [(not v) (elem-default to)]
          [p (let ([x (junbox p v)]) (if (string=? p to) x (prim-cast (if (string=? p "Z") "I" p) to x)))]
          ;; a Number that isn't a wrapper fails as Class.cast to the wanted
          ;; wrapper, anything else as the checkcast to Number
          [(jinstance? v "java/lang/Number")
           (jthrow "java/lang/ClassCastException"
                   (string-append "Cannot cast " (external-class-name (class-name-of v))
                                  " to " (external-class-name (cadr (assoc to box-classes)))))]
          [else (jthrow "java/lang/ClassCastException" (class-cast-message (class-name-of v) "java/lang/Number"))])))

(define (explicit-ref-cast to v)
  (let ([name (desc->class-name to)])
    (if (and v (not (char=? #\[ (string-ref to 0))) (eq? (jclass-kind (find-class name)) 'interface))
        v
        (adapt obj-desc to v))))

(define (adapt-all froms tos args)
  (if (equal? froms tos) args (map adapt froms tos args)))

;; a procedure taking `to-args` and returning `to-ret`, over info's procedure
(define (adapted-proc info to-args to-ret . loose?)
  (let ([p (mh-info-proc info)] [args (mh-info-args info)] [ret (mh-info-ret info)])
    (cond [(and (equal? args to-args) (string=? ret to-ret)) p]
          [(pair? loose?)
           (lambda xs (adapt ret to-ret (apply p (map (lambda (f t x) (adapt f t x #t)) to-args args xs)) #t))]
          [else (lambda xs (adapt ret to-ret (apply p (adapt-all to-args args xs))))])))

(define (wrong-type info n)
  (jthrow "java/lang/invoke/WrongMethodTypeException"
          (string-append "handle takes " (number->string (length (mh-info-args info)))
                         " arguments, called with " (number->string n))))

;; a varargs collector called with the trailing arguments spread out
(define (collect-varargs info site-args xs)
  (let ([fixed (fx- (length (mh-info-args info)) 1)])
    (when (fx< (length site-args) fixed) (wrong-type info (length site-args))))
  (let* ([fixed (fx- (length (mh-info-args info)) 1)]
         [arr-desc (list-ref (mh-info-args info) fixed)]
         [elem (substring arr-desc 1 (string-length arr-desc))]
         [rest-args (list-tail site-args fixed)])
    (append (adapt-all (list-head site-args fixed) (list-head (mh-info-args info) fixed) (list-head xs fixed))
            (list (make-jarr elem (list->vector (map (lambda (t v) (adapt t elem v)) rest-args (list-tail xs fixed))))))))

(define site-descs (make-weak-eq-hashtable))
(define (site-parts d)
  (or (eq-hashtable-ref site-descs d #f)
      (let ([p (cons (desc-args d) (desc-ret d))]) (eq-hashtable-set! site-descs d p) p)))

;; invoke (and the converting invokers, invokeWithArguments, call sites):
;; site is the call site's descriptor (a literal, so its parse is cached by
;; identity)
(define (mh-call mh site . xs)
  (let* ([info (mh-info-of mh)] [sp (site-parts site)] [sargs (car sp)] [targs (mh-info-args info)])
    (adapt (mh-info-ret info) (cdr sp)
           (apply (mh-info-proc info)
                  (cond [(and (mh-info-varargs? info)
                              (or (not (fx= (length sargs) (length targs)))
                                  (not (char=? #\[ (string-ref (list-ref sargs (fx- (length sargs) 1)) 0)))))
                         (collect-varargs info sargs xs)]
                        [(fx= (length sargs) (length targs)) (adapt-all sargs targs xs)]
                        [else (wrong-type info (length sargs))])))))

;; invokeExact: the call site's descriptor must be the handle's type exactly,
;; else WrongMethodTypeException with Invokers.newWrongMethodTypeException's
;; text, as checkExactType; the procedure then takes the arguments as they are
(define (mh-call-exact mh site . xs)
  (let ([info (mh-info-of mh)])
    (unless (string=? (mh-info-desc info) site)
      (let ([sp (site-parts site)])
        (jthrow "java/lang/invoke/WrongMethodTypeException"
                (string-append "handle's method type " (mh-type-text mh)
                               " but found " (type-text (car sp) (cdr sp))))))
    (apply (mh-info-proc info) xs)))

(define (invoke-with-arguments mh arr)
  (let ([xs (if arr (vector->list (jarr-vec arr)) '())])
    (apply mh-call mh (args->desc (map (lambda (x) obj-desc) xs) obj-desc) xs)))

;; --- lookups -------------------------------------------------------------------

;; (declaring-class . meta) of name+desc in class c, its superclasses, then
;; its interfaces
(define (find-method c name desc)
  (let loop ([c c])
    (and c
         (let ([m (find (lambda (m) (and (string=? (car m) name) (string=? (cadr m) desc))) (meta-methods c))])
           (if m
               (cons c m)
               (or (and (jclass-super c) (loop (find-class (jclass-super c))))
                   (exists (lambda (i) (loop (find-class i))) (jclass-interfaces c))))))))

;; A method's procedure is read from its global at call time: before its
;; first call a method is a stub that compiles it on every call, so a
;; handle that held on to the stub would compile the method again each time.
(define (method-sym cname key) (string->symbol (string-append cname "." key)))

(define (no-such-method c name desc how)
  (jthrow "java/lang/NoSuchMethodException"
          (string-append "no such method: " (slashes->dots (jclass-cname c)) "." name
                         (type-text (desc-args desc) (desc-ret desc)) "/" how)))

(define (varargs-flag? meta) (fxpositive? (fxlogand (caddr meta) #x80)))

;; the member a direct handle (a find*/unreflect* result) was made from:
;; (declaring class . method entry), or 'field; VarHandles.exceptionTypes
;; reads it, as the JDK's reads a DirectMethodHandle's MemberName
(define mh-members (make-weak-eq-hashtable))
(define (member-mh mh member) (eq-hashtable-set! mh-members mh member) mh)

(define (static-mh c name desc)
  (let ([hit (find-method c name desc)])
    (unless (and hit (static-flag? (caddr (cdr hit)))) (no-such-method c name desc "invokeStatic"))
    (let* ([dc (jclass-cname (car hit))] [key (string-append name desc)])
      (let ([sym (method-sym dc key)])
        (member-mh (make-mh desc (lambda xs (jinit dc) (apply (top-level-value sym) xs)) #f (varargs-flag? (cdr hit)))
                   hit)))))

(define (virtual-mh c name desc)
  (let ([hit (find-method c name desc)])
    (unless (and hit (not (static-flag? (caddr (cdr hit))))) (no-such-method c name desc "invokeVirtual"))
    (let ([key (string-append name desc)] [dc (jclass-cname (car hit))] [flags (caddr (cdr hit))])
      (member-mh
       (make-mh (string-append "(" (cname->desc (jclass-cname c)) (substring desc 1 (string-length desc)))
                ;; findVirtual on a private method is its own, as on the JVM,
                ;; and a package-private one is selected by run-time package
                (lambda (o . xs) (apply (virtual-target o dc key flags) o xs)) #f (varargs-flag? (cdr hit)))
       hit))))

(define (special-mh c name desc caller)
  (let ([hit (find-method c name desc)])
    (unless hit (no-such-method c name desc "invokeSpecial"))
    (let ([dc (jclass-cname (car hit))] [key (string-append name desc)])
      (member-mh
       (make-mh (string-append "(" (cname->desc (jclass-cname caller)) (substring desc 1 (string-length desc)))
                (let ([sym (method-sym dc key)]) (lambda xs (apply (top-level-value sym) xs)))
                #f (varargs-flag? (cdr hit)))
       hit))))

(define (constructor-mh c desc)
  (let* ([cname (jclass-cname c)]
         [m (find (lambda (m) (and (string=? (car m) "<init>") (string=? (cadr m) desc))) (meta-methods c))])
    (unless m (no-such-method c "<init>" desc "newInvokeSpecial"))
    (let ([sym (method-sym cname (string-append "<init>" desc))])
      (member-mh
       (make-mh (args->desc (desc-args desc) (cname->desc cname))
                (lambda xs (jinit cname) (let ([o (jnew cname)]) (apply (top-level-value sym) o xs) o))
                #f (varargs-flag? m))
       (cons c m)))))

;; getter or setter on field fname of c; static? from the lookup kind
;; lookup: the Lookup asking (find*Getter/Setter), whose setters refuse a
;; final field, or #f (unreflect, a method handle constant)
(define (field-mh c fname type static? setter? . lookup)
  (let* ([l (and (pair? lookup) (car lookup))]
         [hit (resolve-field c fname type static? setter? (and l #t) l)]
         [mh (field-mh* c fname static? setter? hit)])
    (member-mh (if static? mh (restrict-protected l hit mh)) 'field)))
(define (field-mh* c fname static? setter? hit)
  (let ()
    (let* ([dc (jclass-cname (car hit))] [desc (cadr (cdr hit))]
           [recv (cname->desc (jclass-cname c))])
      (if static?
          (let ([sym (string->symbol (string-append dc ":" fname))])
            (if setter?
                (make-mh (args->desc (list desc) "V") (lambda (v) (jinit dc) (set-top-level-value! sym v)) #f)
                (make-mh (args->desc '() desc) (lambda () (jinit dc) (top-level-value sym)) #f)))
          (let ([i (jfield-index dc fname)] [cn (jclass-cname c)])
            ;; the slot index is only right for instances of c
            (if setter?
                (make-mh (args->desc (list recv desc) "V")
                         (lambda (o v) (vector-set! (jobj-slots (nn (jcheckcast o cn))) i v)) #f)
                (make-mh (args->desc (list recv) desc)
                         (lambda (o) (vector-ref (jobj-slots (nn (jcheckcast o cn))) i)) #f)))))))

;; a handle's checked exceptions as VarHandles.exceptionTypes answers them:
;; a direct handle's member's Exceptions (none for a field), null for any
;; other handle (unknown), so Linker.upcallStub refuses a target declaring
;; one and a VarHandle filter adapts only when it can't tell
(define-override! "java/lang/invoke/VarHandles.exceptionTypes(Ljava/lang/invoke/MethodHandle;)[Ljava/lang/Class;"
  (lambda (mh)
    (let ([m (eq-hashtable-ref mh-members (nn mh) #f)])
      (cond [(not m) #f]
            [(eq? m 'field) (make-jarr "Ljava/lang/Class;" (vector))]
            [else (with-loader-of (jmirror (car m)) (lambda () (exception-mirrors (cdr m))))]))))

;; Access checks for the Lookup methods, in the JDK's order, through the
;; JDK's own Java: Lookup.checkSymbolicClass on the class named (find* only,
;; as the JDK's resolveOrFail), then for a method or constructor HotSpot's
;; resolution check (MethodHandleNatives.resolve refuses a member the lookup
;; class can't reach, which the JDK reports as IllegalAccessException "no
;; such method: ..."), then Lookup.checkAccess with a MemberName carrying
;; the member's modifiers, which gives the "member is private: ..., from ..."
;; texts. A TRUSTED lookup (the JDK's own IMPL_LOOKUP) passes everything, as
;; there.
(define mh-lookup-class "java/lang/invoke/MethodHandles$Lookup")
(define (lookup-slot l name) (jslot l (jfield-index mh-lookup-class name)))
(define (trusted-lookup? l) (eqv? -1 (lookup-slot l "allowedModes")))
(define (lookup-call name desc . args)
  (apply (method-proc mh-lookup-class (string-append name desc)) args))
;; a MemberName for member name of declaring class dc with modifiers mods,
;; its type a Class for a field (ref-kind 1-4), else a MethodType
(define (access-member-name dc name type ref-kind mods)
  (let ([m (construct "java/lang/invoke/MemberName"
                      (if (fx<= ref-kind 4)
                          "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;B)V"
                          "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V")
                      (jmirror dc) (jstring name) type ref-kind)]
        [i (jfield-index "java/lang/invoke/MemberName" "flags")])
    (jslot-set! m i (fxior (jslot m i) (fxlogand mods #xffff)))
    m))
(define (check-symbolic-class! l refc)
  (unless (trusted-lookup? l)
    (lookup-call "checkSymbolicClass" "(Ljava/lang/Class;)V" l (jmirror refc))))
;; HotSpot's resolution-time check of a method or constructor, JVMS 5.4.4
;; from the lookup class (a public lookup's is Object)
(define (resolution-access! l refc dc mods mn ctor?)
  (unless (eqv? 1 ((method-proc "sun/invoke/util/VerifyAccess"
                                "isMemberAccessible(Ljava/lang/Class;Ljava/lang/Class;ILjava/lang/Class;Ljava/lang/Class;I)Z")
                   (jmirror refc) (jmirror dc) (fxlogand mods #xffff) (lookup-slot l "lookupClass") #f #x1f))
    (jthrow "java/lang/IllegalAccessException"
            (string-append (if ctor? "no such constructor: " "no such method: ")
                           (jstring->scheme ((jvirt mn "toString()Ljava/lang/String;") mn))))))
;; the checks for a resolved member hit, (declaring class name desc flags
;; ...), reached as ref-kind through refc. An unreflected member isn't
;; resolved again, so only checkAccess runs, on a MemberName as
;; MemberName(Method) leaves it: a statically bound method (final, private,
;; or of a final class) is invokeSpecial, and its type is still the VM's
;; Object[] of return and parameter types, which is what HotSpot's message
;; prints
(define (lookup-access! l ref-kind refc hit . unreflected?)
  (when (and l (not (trusted-lookup? l)))
    (let* ([dc (car hit)] [meta (cdr hit)] [name (car meta)] [desc (cadr meta)] [mods (caddr meta)]
           [field? (fx<= ref-kind 4)]
           [own-kind (if (and (pair? unreflected?) (fx= ref-kind 5)
                              (or (fxpositive? (fxlogand mods #x12)) (fxpositive? (fxlogand (jclass-flags dc) #x10))))
                         7 ref-kind)]
           [mn (access-member-name dc name (if field? (desc->mirror desc) (desc->method-type desc)) own-kind mods)])
      (cond
        [field? #f]
        [(pair? unreflected?)
         (jslot-set! mn (jfield-index "java/lang/invoke/MemberName" "type")
                     (make-jarr "Ljava/lang/Object;"
                                (vector (desc->mirror (desc-ret desc)) (mirror-array (desc-args desc)))))]
        [else (resolution-access! l refc dc mods mn (fx= ref-kind 8))])
      (lookup-call "checkAccess" "(BLjava/lang/Class;Ljava/lang/invoke/MemberName;)V" l ref-kind (jmirror refc) mn))))
;; Lookup.restrictProtectedReceiver: a protected instance member of a class
;; in another package takes the lookup class as its receiver (checkAccess
;; passed, so the lookup class is a subclass of the declaring one); an
;; array's clone keeps its array receiver
(define (restrict-protected l hit mh)
  (let* ([dc (car hit)] [mods (caddr (cdr hit))] [lc (and l (lookup-slot l "lookupClass"))])
    (if (or (not l) (trusted-lookup? l) (not (fxpositive? (fxlogand mods #x4))) (static-flag? mods)
            (eq? (jmirror dc) lc)
            (string=? (package-dir (jclass-cname dc)) (package-dir (jclass-cname (mirror-class lc)))))
        mh
        (let* ([caller (jclass-cname (mirror-class lc))] [desc (mh-info-desc (mh-info-of mh))] [args (desc-args desc)])
          (if (or (char=? #\[ (string-ref (car args) 0)) (subclass? (desc->class-name (car args)) caller))
              mh
              (as-type mh (desc->method-type (args->desc (cons (cname->desc caller) (cdr args)) (desc-ret desc)))))))))
(define (def-lookup name desc proc) (define-override! (string-append mh-lookup-class "." name desc) proc))
;; a method handle from make, made for lookup l as ref-kind through refc:
;; the class check (find* only), then make resolves (NoSuchMethodException),
;; then the member's access; a protected instance method's receiver narrowed
(define (checked-method l ref-kind refc make . unreflected?)
  (when (and l (null? unreflected?)) (check-symbolic-class! l refc))
  (let* ([mh (make)] [hit (eq-hashtable-ref mh-members mh #f)])
    (apply lookup-access! l ref-kind refc hit unreflected?)
    (if (memv ref-kind '(5 9)) (member-mh (restrict-protected l hit mh) hit) mh)))
(define (virtual-kind c) (if (eq? (jclass-kind c) 'interface) 9 5))
;; the lookup an unreflect* checks against: none for a member made accessible
(define (unreflect-lookup l member)
  (if (eqv? 1 (jslot member (jfield-index "java/lang/reflect/AccessibleObject" "override"))) #f l))
(define mh-ret "Ljava/lang/invoke/MethodHandle;")
(define (str-of s) (jstring->scheme (nn s)))

(def-lookup "findStatic" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c name mt)
    (let ([c (mirror-class (nn c))])
      (checked-method this 6 c (lambda () (static-mh c (str-of name) (mt-desc mt)))))))
;; on MethodHandle, invoke and invokeExact are the invokers of the type asked
;; for, and on VarHandle an access mode's name is its invoker, as the JDK's
;; findVirtualForMH and findVirtualForVH
(def-lookup "findVirtual" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c name mt)
    (let ([c (mirror-class (nn c))] [name (str-of name)] [desc (mt-desc mt)])
      (cond [(and (string=? (jclass-cname c) "java/lang/invoke/MethodHandle") (member name '("invoke" "invokeExact")))
             (invoker-of desc (string=? name "invokeExact"))]
            [(and (string=? (jclass-cname c) "java/lang/invoke/VarHandle") (vh-mode-name? name))
             (vh-invoker name desc #f)]
            [else (checked-method this (virtual-kind c) c (lambda () (virtual-mh c name desc)))]))))
;; bind: the method as invokeSpecial finds it in the receiver's class (no
;; virtual dispatch beyond that), the receiver bound; a static one is
;; refused as the JDK's resolution refuses it
(def-lookup "bind" (string-append "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this receiver name mt)
    (unless receiver
      (jthrow "java/lang/NullPointerException" "Cannot invoke \"Object.getClass()\" because \"receiver\" is null"))
    (let* ([c (find-class (class-name-of receiver))] [name (str-of name)] [desc (mt-desc mt)]
           [hit (find-method c name desc)])
      (unless hit (no-such-method c name desc "invokeSpecial"))
      (when (static-flag? (caddr (cdr hit)))
        (jthrow "java/lang/IllegalAccessException"
                (string-append "no such method: " (slashes->dots (jclass-cname c)) "." name
                               (type-text (desc-args desc) (desc-ret desc)) "/invokeSpecial")))
      (let ([sym (method-sym (jclass-cname (car hit)) (string-append name desc))])
        (member-mh (make-mh desc (lambda xs (apply (top-level-value sym) receiver xs)) #f (varargs-flag? (cdr hit)))
                   hit)))))
(def-lookup "findSpecial" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)" mh-ret)
  (lambda (this c name mt caller)
    (let ([c (mirror-class (nn c))] [sc (mirror-class (nn caller))])
      (unless (trusted-lookup? this)
        (lookup-call "checkSpecialCaller" "(Ljava/lang/Class;Ljava/lang/Class;)V" this (jmirror sc) (jmirror c)))
      (checked-method this 7 c (lambda () (special-mh c (str-of name) (mt-desc mt) sc))))))
(def-lookup "findConstructor" (string-append "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c mt)
    (let ([c (mirror-class (nn c))])
      (checked-method this 8 c (lambda () (constructor-mh c (mt-desc mt)))))))
(for-each
 (lambda (spec)
   (def-lookup (car spec) (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)" mh-ret)
     (lambda (this c name type)
       (let ([c (mirror-class (nn c))])
         (check-symbolic-class! this c)
         (field-mh c (str-of name) (nn type) (cadr spec) (caddr spec) this)))))
 '(("findGetter" #f #f) ("findSetter" #f #t) ("findStaticGetter" #t #f) ("findStaticSetter" #t #t)))

;; reflective objects carry their class and their index in its meta list
(def-lookup "unreflect" (string-append "(Ljava/lang/reflect/Method;)" mh-ret)
  (lambda (this m)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" (nn m))])
      (let ([l (unreflect-lookup this m)])
        (if (static-flag? (caddr meta))
            (checked-method l 6 c (lambda () (static-mh c (car meta) (cadr meta))) #t)
            (checked-method l (virtual-kind c) c (lambda () (virtual-mh c (car meta) (cadr meta))) #t))))))
(def-lookup "unreflectSpecial" (string-append "(Ljava/lang/reflect/Method;Ljava/lang/Class;)" mh-ret)
  (lambda (this m caller)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" (nn m))])
      (special-mh c (car meta) (cadr meta) (mirror-class (nn caller))))))
(def-lookup "unreflectConstructor" (string-append "(Ljava/lang/reflect/Constructor;)" mh-ret)
  (lambda (this k)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Constructor" (nn k))])
      (checked-method (unreflect-lookup this k) 8 c (lambda () (constructor-mh c (cadr meta))) #t))))
(for-each
 (lambda (spec)
   (def-lookup (car spec) (string-append "(Ljava/lang/reflect/Field;)" mh-ret)
     (lambda (this f)
       (let* ([c (mirror-class (jslot (nn f) (jfield-index "java/lang/reflect/Field" "clazz")))]
              [meta (list-ref (meta-fields c) (jslot f (jfield-index "java/lang/reflect/Field" "slot")))])
         (field-mh c (car meta) (desc->mirror (cadr meta)) (static-flag? (caddr meta)) (cadr spec) (unreflect-lookup this f))))))
 '(("unreflectGetter" #f) ("unreflectSetter" #t)))

;; --- MethodHandle's own methods ------------------------------------------------------

(define (def-mh name desc proc) (define-override! (string-append "java/lang/invoke/MethodHandle." name desc) proc))

(define (as-type mh mt . loose?)
  (let* ([info (mh-info-of mh)] [desc (mt-desc mt)] [args (desc-args desc)])
    (if (string=? desc (mh-info-desc info))
        mh   ; the same type: the handle itself, as the JDK's (a direct one keeps its member)
        (as-type* mh mt info desc args loose?))))
(define (as-type* mh mt info desc args loose?)
  (begin
    (unless (fx= (length args) (length (mh-info-args info))) (wrong-type info (length args)))
    (unless (or (pair? loose?)
                (and (for-all as-type-ok? args (mh-info-args info))
                     (as-type-ok? (mh-info-ret info) (desc-ret desc))))
      (jthrow "java/lang/invoke/WrongMethodTypeException"
              (string-append "cannot convert MethodHandle" (mh-type-text mh)
                             " to " (type-text args (desc-ret desc)))))
    (make-mh desc (apply adapted-proc info args (desc-ret desc) loose?) mt)))

(def-mh "asType" (string-append "(Ljava/lang/invoke/MethodType;)" mh-ret) as-type)
(def-mh "bindTo" (string-append "(Ljava/lang/Object;)" mh-ret)
  (lambda (mh x)
    (let* ([info (mh-info-of mh)] [p (mh-info-proc info)] [args (mh-info-args info)])
      (when (or (null? args) (prim-desc? (car args)))
        (jthrow "java/lang/IllegalArgumentException" "no leading reference parameter"))
      (make-mh (args->desc (cdr args) (mh-info-ret info)) (lambda xs (apply p x xs)) #f))))
(def-mh "invokeWithArguments" "([Ljava/lang/Object;)Ljava/lang/Object;" invoke-with-arguments)
(def-mh "invokeWithArguments" "(Ljava/util/List;)Ljava/lang/Object;"
  (lambda (mh lst) (invoke-with-arguments mh ((jvirt (nn lst) "toArray()[Ljava/lang/Object;") lst))))
(def-mh "isVarargsCollector" "()Z" (lambda (mh) (if (mh-info-varargs? (mh-info-of mh)) 1 0)))
(def-mh "asFixedArity" (string-append "()" mh-ret)
  (lambda (mh)
    (let ([info (mh-info-of mh)])
      (if (mh-info-varargs? info) (make-mh (mh-info-desc info) (mh-info-proc info) #f) mh))))

;; --- MethodHandles combinators -------------------------------------------------------

(define (def-mhs name desc proc) (define-override! (string-append "java/lang/invoke/MethodHandles." name desc) proc))

(def-mhs "constant" (string-append "(Ljava/lang/Class;Ljava/lang/Object;)" mh-ret)
  (lambda (c x)
    (let ([d (mirror->desc (nn c))])
      (when (string=? d "V") (jthrow "java/lang/IllegalArgumentException" "void type"))
      ;; the JDK converts through Wrapper: a widening box passes, a wrong or
      ;; narrowing one is "not compatible", null into a primitive the helpful NPE
      (let ([v (vh-insert-value d x)])
        (make-mh (args->desc '() d) (lambda () v) #f)))))
(def-mhs "identity" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c)
    (unless c (jthrow "java/lang/NullPointerException"
                      "Cannot invoke \"java.lang.Class.isPrimitive()\" because \"type\" is null"))
    (let ([d (mirror->desc c)])
      (when (string=? d "V") (jthrow "java/lang/IllegalArgumentException" "parameter type cannot be void"))
      (make-mh (args->desc (list d) d) (lambda (x) x) #f))))
(define (zero-mh d) (make-mh (args->desc '() d) (lambda () (elem-default d)) #f))
(def-mhs "zero" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c) (zero-mh (mirror->desc (nn c)))))
(def-mhs "empty" (string-append "(Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (mt) (let ([d (mt-desc (nn mt))])
                 (drop-arguments (zero-mh (desc-ret d)) 0 (desc-args d)))))
(def-mhs "insertArguments" (string-append "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)" mh-ret)
  (lambda (mh pos vals)
    (let* ([info (mh-info-of mh)] [p (mh-info-proc info)] [args (mh-info-args info)]
           [vs (vector->list (jarr-vec vals))] [n (length vs)])
      (when (or (fx< pos 0) (fx> (fx+ pos n) (length args)))
        (jthrow "java/lang/IllegalArgumentException" "insertArguments: bad position"))
      (let ([bound (map (lambda (t v) (adapt obj-desc t v)) (list-head (list-tail args pos) n) vs)])
        (make-mh (args->desc (append (list-head args pos) (list-tail args (fx+ pos n))) (mh-info-ret info))
                 (lambda xs (apply p (append (list-head xs pos) bound (list-tail xs pos))))
                 #f)))))

(define (drop-arguments mh pos types)
  (let* ([info (mh-info-of mh)] [p (mh-info-proc info)] [args (mh-info-args info)] [n (length types)])
    (when (or (fx< pos 0) (fx> pos (length args)))
      (jthrow "java/lang/IllegalArgumentException" "dropArguments: bad position"))
    (make-mh (args->desc (append (list-head args pos) types (list-tail args pos)) (mh-info-ret info))
             (lambda xs (apply p (append (list-head xs pos) (list-tail xs (fx+ pos n)))))
             #f)))
(def-mhs "dropArguments" (string-append "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)" mh-ret)
  (lambda (mh pos cs) (drop-arguments mh pos (map mirror->desc (vector->list (jarr-vec cs))))))
(def-mhs "dropArguments" (string-append "(Ljava/lang/invoke/MethodHandle;ILjava/util/List;)" mh-ret)
  (lambda (mh pos lst)
    (drop-arguments mh pos (map mirror->desc (vector->list (jarr-vec ((jvirt (nn lst) "toArray()[Ljava/lang/Object;") lst)))))))
;; what the public dropArguments, dropArgumentsToMatch and the JDK's own
;; internal callers (the loop builders, zero) end in: the JDK's checks, with
;; its texts, then the same handle when nothing is dropped, as the JDK's
(define-override! "java/lang/invoke/MethodHandles.dropArgumentsTrusted(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"
  (lambda (mh pos cs)
    (let ([dropped ((method-proc "java/lang/invoke/MethodHandles" "dropArgumentChecks(Ljava/lang/invoke/MethodType;I[Ljava/lang/Class;)I")
                    (jslot (nn mh) (jfield-index "java/lang/invoke/MethodHandle" "type")) pos cs)])
      (if (eqv? dropped 0)
          mh
          (drop-arguments mh pos (map mirror->desc (vector->list (jarr-vec cs))))))))
;; the same handle when it returns void already, as the JDK's
(def-mhs "dropReturn" (string-append "(Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (mh)
    (let ([info (mh-info-of mh)])
      (if (string=? (mh-info-ret info) "V")
          mh
          (let ([p (mh-info-proc info)])
            (make-mh (args->desc (mh-info-args info) "V") (lambda xs (apply p xs) (void)) #f))))))
(def-mhs "filterReturnValue" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target filter)
    (let* ([ti (mh-info-of target)] [fi (mh-info-of filter)] [tp (mh-info-proc ti)] [fp (mh-info-proc fi)])
      (let ([tret (mh-info-ret ti)])
        ;; a void target's filter takes nothing
        (unless (fx= (if (string=? tret "V") 0 1) (length (mh-info-args fi))) (wrong-type fi 1))
        (make-mh (args->desc (mh-info-args ti) (mh-info-ret fi))
                 (if (string=? tret "V")
                     (lambda xs (apply tp xs) (fp))
                     (let ([farg (car (mh-info-args fi))]) (lambda xs (fp (adapt tret farg (apply tp xs))))))
                 #f)))))
(def-mhs "guardWithTest" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (test target fallback)
    (let* ([ti (mh-info-of target)] [args (mh-info-args ti)] [ret (mh-info-ret ti)]
           [qi (mh-info-of test)] [n (length (mh-info-args qi))] [fi (mh-info-of fallback)])
      (when (fx> n (length args)) (wrong-type qi (length args)))
      (unless (fx= (length (mh-info-args fi)) (length args)) (wrong-type fi (length args)))
      (let ([q (adapted-proc qi (list-head args n) "Z")]
            [on-true (mh-info-proc ti)] [on-false (adapted-proc fi args ret)])
      ;; the test takes a prefix of the target's arguments; booleans are 0 and 1
      (make-mh (mh-info-desc ti)
               (lambda xs (if (eqv? 0 (apply q (list-head xs n))) (apply on-false xs) (apply on-true xs)))
               #f)))))
(def-mhs "permuteArguments" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;[I)" mh-ret)
  (lambda (target mt reorder)
    (let* ([ti (mh-info-of target)] [tp (mh-info-proc ti)] [targs (mh-info-args ti)]
           [desc (mt-desc mt)] [nargs (desc-args desc)] [order (vector->list (jarr-vec reorder))])
      (unless (fx= (length order) (length targs)) (wrong-type ti (length order)))
      ;; target argument k is new argument (list-ref order k)
      (make-mh desc
               (lambda xs
                 (adapt (mh-info-ret ti) (desc-ret desc)
                        (apply tp (map (lambda (i t) (adapt (list-ref nargs i) t (list-ref xs i))) order targs))))
               mt))))
(def-mhs "explicitCastArguments" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (mh mt)
    (let ([desc (mt-desc (nn mt))])
      (unless (fx= (length (desc-args desc)) (length (mh-info-args (mh-info-of mh))))
        (jthrow "java/lang/invoke/WrongMethodTypeException"
                (string-append "cannot explicitly cast MethodHandle" (mh-type-text mh)
                               " to " (type-text (desc-args desc) (desc-ret desc)))))
      (as-type mh mt #t))))

;; a method type as MethodType.toString prints it, (int,int)int: simple names
;; by getSimpleName, so a local class is Local and an anonymous one empty
(define (type-text args ret) (method-type-text args ret))

(def-mhs "filterArguments" (string-append "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target pos filters)
    (let* ([ti (mh-info-of target)] [targs (mh-info-args ti)] [tp (mh-info-proc ti)]
           [fs (vector->list (jarr-vec (nn filters)))])
      (when (or (fx< pos 0) (fx> (fx+ pos (length fs)) (length targs)))
        (vh-iae "too many filters"))
      ;; a filter maps one incoming argument to the target's parameter; a null
      ;; filter leaves its argument alone
      (let ([rows (map (lambda (t i)
                         (let ([f (and (fx>= i pos) (fx< i (fx+ pos (length fs)))
                                       (list-ref fs (fx- i pos)))])
                           (if f
                               (let ([fi (mh-info-of f)])
                                 (unless (and (fx= 1 (length (mh-info-args fi)))
                                              (string=? (mh-info-ret fi) t))
                                   (vh-iae "target and filter types do not match: "
                                           (mh-type-text target) ", " (mh-type-text f)))
                                 (cons (car (mh-info-args fi)) (mh-info-proc fi)))
                               (cons t #f))))
                       targs (iota (length targs)))])
        (make-mh (args->desc (map car rows) (mh-info-ret ti))
                 (lambda xs
                   (apply tp (let loop ([rs rows] [vs xs] [acc '()])
                               (if (null? rs)
                                   (reverse acc)
                                   (loop (cdr rs) (cdr vs)
                                         (cons (if (cdar rs) ((cdar rs) (car vs)) (car vs)) acc))))))
                 #f)))))

;; the one-filter form the JDK's own code calls (iteratedLoop): filterArguments
;; with one filter
(define-override! "java/lang/invoke/MethodHandles.filterArgument(Ljava/lang/invoke/MethodHandle;ILjava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;"
  (lambda (target pos filter)
    ((method-proc "java/lang/invoke/MethodHandles" (string-append "filterArguments(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/invoke/MethodHandle;)" mh-ret))
     target pos (make-jarr "Ljava/lang/invoke/MethodHandle;" (vector filter)))))
;; foldArguments: the combiner reads target arguments pos.. and its result (when
;; it has one) becomes the target's argument at pos, dropped from the new type
(define (fold-arguments target pos combiner)
  (let* ([ti (mh-info-of target)] [targs (mh-info-args ti)] [tp (mh-info-proc ti)]
         [ci (mh-info-of combiner)] [cargs (mh-info-args ci)] [cp (mh-info-proc ci)]
         [void? (string=? (mh-info-ret ci) "V")]
         [n (length cargs)]
         [vals-at (if void? pos (fx+ pos 1))]
         [fail (lambda () (vh-iae "target and combiner types must match: "
                                  (mh-type-text target) " != " (mh-type-text combiner)))])
    (unless (and (fx>= pos 0) (fx<= (fx+ vals-at n) (length targs))) (fail))
    (unless (and (or void? (string=? (list-ref targs pos) (mh-info-ret ci)))
                 (equal? cargs (list-head (list-tail targs vals-at) n)))
      (fail))
    (make-mh (args->desc (if void? targs (append (list-head targs pos) (list-tail targs (fx+ pos 1))))
                         (mh-info-ret ti))
             (if void?
                 (lambda xs (apply cp (list-head (list-tail xs pos) n)) (apply tp xs))
                 (lambda xs
                   (let ([v (apply cp (list-head (list-tail xs pos) n))])
                     (apply tp (append (list-head xs pos) (list v) (list-tail xs pos))))))
             #f)))
(def-mhs "foldArguments" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target combiner) (fold-arguments target 0 combiner)))
(def-mhs "foldArguments" (string-append "(Ljava/lang/invoke/MethodHandle;ILjava/lang/invoke/MethodHandle;)" mh-ret)
  fold-arguments)

;; collectArguments: the filter's arguments replace the target's argument at
;; pos, whose value the filter computes; a void filter's replace nothing
(def-mhs "collectArguments" (string-append "(Ljava/lang/invoke/MethodHandle;ILjava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target pos filter)
    (let* ([ti (mh-info-of target)] [targs (mh-info-args ti)] [tp (mh-info-proc ti)]
           [fi (mh-info-of filter)] [fargs (mh-info-args fi)] [fp (mh-info-proc fi)]
           [void? (string=? (mh-info-ret fi) "V")] [n (length fargs)])
      (when (or (fx< pos 0) (fx> pos (fx- (length targs) (if void? 0 1))))
        (vh-iae "position is out of range for target: MethodHandle" (mh-type-text target)
                ", " (number->string pos)))
      (unless (or void? (string=? (mh-info-ret fi) (list-ref targs pos)))
        (vh-iae "target and filter types do not match: " (mh-type-text target) ", " (mh-type-text filter)))
      (make-mh (args->desc (append (list-head targs pos) fargs
                                   (list-tail targs (if void? pos (fx+ pos 1))))
                           (mh-info-ret ti))
               (if void?
                   (lambda xs
                     (apply fp (list-head (list-tail xs pos) n))
                     (apply tp (append (list-head xs pos) (list-tail xs (fx+ pos n)))))
                   (lambda xs
                     (let ([v (apply fp (list-head (list-tail xs pos) n))])
                       (apply tp (append (list-head xs pos) (list v) (list-tail xs (fx+ pos n)))))))
               #f))))

;; asSpreader and asCollector, at the end or (the forms with a position, which
;; Proxy.invokeDefault uses) at pos, with the JDK's checks in its order
(define (spreader mh pos c n)
  (let* ([info (mh-info-of mh)] [args (mh-info-args info)] [p (mh-info-proc info)]
         [ad (mirror->desc (nn c))])
    (unless (char=? #\[ (string-ref ad 0)) (vh-iae "not an array type: " (vh-class-text ad)))
    (when (or (fx< n 0) (fx> n (length args))) (vh-iae "bad spread array length"))
    (let ([pos (or pos (fx- (length args) n))])
      (when (or (fx< pos 0) (fx> (fx+ pos n) (length args))) (vh-iae "bad spread position"))
      (let* ([elem (substring ad 1 (string-length ad))]
             [before (list-head args pos)]
             [spread (list-head (list-tail args pos) n)]
             [after (list-tail args (fx+ pos n))])
        (unless (for-all (lambda (t) (as-type-ok? elem t)) spread)
          (jthrow "java/lang/invoke/WrongMethodTypeException"
                  (string-append "cannot convert MethodHandle" (mh-type-text mh) " to "
                                 (type-text (append before (map (lambda (t) elem) spread) after)
                                            (mh-info-ret info)))))
        (make-mh (args->desc (append before (list ad) after) (mh-info-ret info))
                 (spread-proc p pos elem spread)
                 #f)))))
;; p taking the array argument at pos as the spread types' arguments, each
;; adapted from the array's element type; the array must have their number
(define (spread-proc p pos elem spread)
  (let ([n (length spread)])
    (lambda xs
      (let ([arr (list-ref xs pos)])
        (when (and (not arr) (fx> n 0))
          (jthrow "java/lang/NullPointerException" "null array reference"))
        (unless (or (not arr) (fx= (jarraylength arr) n))
          (vh-iae "array is not of length " (number->string n)))
        (apply p (append (list-head xs pos)
                         (if arr (map (lambda (t v) (adapt elem t v)) spread (vector->list (jarr-vec arr))) '())
                         (list-tail xs (fx+ pos 1))))))))
(def-mh "asSpreader" (string-append "(Ljava/lang/Class;I)" mh-ret)
  (lambda (mh c n) (spreader mh #f c n)))
(def-mh "asSpreader" (string-append "(ILjava/lang/Class;I)" mh-ret)
  (lambda (mh pos c n) (spreader mh pos c n)))

(define (collector mh pos c n)
  (let* ([info (mh-info-of mh)] [args (mh-info-args info)] [p (mh-info-proc info)]
         [ad (mhs-array-desc c)]
         [pos (or pos (fx- (length args) 1))])
    (when (or (fx< pos 0) (fx>= pos (length args))) (vh-iae "bad collect position"))
    (let ([param (list-ref args pos)])
      (unless (and (not (prim-desc? param))
                   (class-assignable? (desc->class-name ad) (desc->class-name param)))
        (vh-iae "array type not assignable to argument: MethodHandle" (mh-type-text mh) ", " (vh-class-text ad))))
    (when (fx< n 0) (vh-iae "bad collect array length"))
    (let ([elem (substring ad 1 (string-length ad))])
      (make-mh (args->desc (append (list-head args pos) (map (lambda (i) elem) (iota n)) (list-tail args (fx+ pos 1)))
                           (mh-info-ret info))
               (lambda xs
                 (apply p (append (list-head xs pos)
                                  (list (make-jarr elem (list->vector (list-head (list-tail xs pos) n))))
                                  (list-tail xs (fx+ pos n)))))
               #f))))
(def-mh "asCollector" (string-append "(Ljava/lang/Class;I)" mh-ret)
  (lambda (mh c n) (collector mh #f c n)))
(def-mh "asCollector" (string-append "(ILjava/lang/Class;I)" mh-ret)
  (lambda (mh pos c n) (collector mh pos c n)))

;; array element access as handles, with the bytecode's checks and messages
(define (array-kind-text e)
  (case (string-ref e 0)
    [(#\Z #\B) "byte/boolean"]
    [(#\L #\[) "object"]
    [else (cdr (assoc e prim-names))]))
(define (mhs-array-desc c)
  (let ([ad (mirror->desc (nn c))])
    (unless (char=? #\[ (string-ref ad 0)) (vh-iae "not an array type: " (vh-class-text ad)))
    ad))
(def-mhs "arrayElementGetter" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c)
    (let* ([ad (mhs-array-desc c)] [e (substring ad 1 (string-length ad))])
      (make-mh (args->desc (list ad "I") e)
               (lambda (a i)
                 (unless a (jthrow "java/lang/NullPointerException"
                                   (string-append "Cannot load from " (array-kind-text e)
                                                  " array because \"a\" is null")))
                 (jaload a i))
               #f))))
(def-mhs "arrayElementSetter" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c)
    (let* ([ad (mhs-array-desc c)] [e (substring ad 1 (string-length ad))])
      (make-mh (args->desc (list ad "I" e) "V")
               (lambda (a i v)
                 (unless a (jthrow "java/lang/NullPointerException"
                                   (string-append "Cannot store to " (array-kind-text e)
                                                  " array because \"a\" is null")))
                 (array-store-check a v "set")
                 (jastore a i v))
               #f))))
(def-mhs "arrayLength" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c)
    (let ([ad (mhs-array-desc c)])
      (make-mh (args->desc (list ad) "I")
               (lambda (a)
                 (unless a (jthrow "java/lang/NullPointerException"
                                   "Cannot read the array length because \"a\" is null"))
                 (jarraylength a))
               #f))))

;; invokers: handles that take the target handle first
(define (invoker-of d exact?)
  (make-mh (args->desc (cons "Ljava/lang/invoke/MethodHandle;" (desc-args d)) (desc-ret d))
           (if exact?
               (lambda (mh . xs)
                 (let ([info (mh-info-of (nn mh))])
                   (unless (string=? (mh-info-desc info) d)
                     (jthrow "java/lang/invoke/WrongMethodTypeException"
                             (string-append "handle's method type " (mh-type-text mh)
                                            " but found " (type-text (desc-args d) (desc-ret d)))))
                   (apply (mh-info-proc info) xs)))
               (lambda (mh . xs) (apply mh-call (nn mh) d xs)))
           #f))
(def-mhs "invoker" (string-append "(Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (mt) (invoker-of (mt-desc (nn mt)) #f)))
(def-mhs "exactInvoker" (string-append "(Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (mt) (invoker-of (mt-desc (nn mt)) #t)))
(def-mhs "spreadInvoker" (string-append "(Ljava/lang/invoke/MethodType;I)" mh-ret)
  (lambda (mt n)
    (let* ([d (mt-desc (nn mt))] [args (desc-args d)])
      (when (or (fx< n 0) (fx> n (length args))) (vh-iae "bad argument count: " (number->string n)))
      ;; the spread values arrive boxed, so the call site declares them Object
      ;; and mh-call unboxes against the target
      (let ([site (args->desc (append (list-head args n) (map (lambda (t) obj-desc) (list-tail args n)))
                              (desc-ret d))]
            [k (fx- (length args) n)])
        (make-mh (args->desc (cons "Ljava/lang/invoke/MethodHandle;"
                                   (append (list-head args n) (list "[Ljava/lang/Object;")))
                             (desc-ret d))
                 (lambda (mh . xs)
                   (let ([arr (list-ref xs n)])
                     (when (and (not arr) (fx> k 0))
                       (jthrow "java/lang/NullPointerException" "null array reference"))
                     (unless (or (not arr) (fx= (jarraylength arr) k))
                       (vh-iae "array is not of length " (number->string k)))
                     (apply mh-call (nn mh) site
                            (append (list-head xs n)
                                    (if arr (vector->list (jarr-vec arr)) '())))))
                 #f)))))

;; exceptions as handles. A Java throw is a raise of the jobj, so guard with a
;; jinstance? test catches exactly what a bytecode handler would.
(def-mhs "catchException" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target exc handler)
    (let* ([ti (mh-info-of target)] [tp (mh-info-proc ti)] [targs (mh-info-args ti)]
           [hi (mh-info-of handler)] [hp (mh-info-proc hi)] [hargs (mh-info-args hi)]
           [exname (jclass-cname (mirror-class (nn exc)))])
      (unless (class-assignable? exname "java/lang/Throwable")
        (jthrow "java/lang/ClassCastException" (slashes->dots exname)))
      (unless (and (pair? hargs) (not (prim-desc? (car hargs)))
                   (class-assignable? exname (desc->class-name (car hargs))))
        (vh-iae "handler does not accept exception type " (vh-class-text (cname->desc exname))))
      (unless (string=? (mh-info-ret hi) (mh-info-ret ti))
        (vh-iae "target and handler return types must match: "
                (mh-type-text target) " != " (mh-type-text handler)))
      (let ([n (fx- (length hargs) 1)])
        (unless (and (fx<= n (length targs)) (equal? (cdr hargs) (list-head targs n)))
          (vh-iae "target and handler types must match: "
                  (mh-type-text target) " != " (mh-type-text handler)))
        ;; the handler takes the exception, then a prefix of the target's arguments
        (make-mh (mh-info-desc ti)
                 (lambda xs
                   (guard (e [(and (jobj? e) (jinstance? e exname))
                              (apply hp e (list-head xs n))])
                     (apply tp xs)))
                 #f)))))
;; Loops: MethodHandles.loop and the builders on it (whileLoop, doWhileLoop,
;; both countedLoops, iteratedLoop). The JDK's own Java checks the clauses,
;; with its texts, and fills in what a clause leaves out, so every handle
;; that reaches MethodHandleImpl.makeLoop has its exact type: an init takes
;; the loop's arguments, a step, pred and fini the variables then the
;; arguments. The JDK runs the loop through a LambdaForm; this runs it as
;; MethodHandleImpl.loop interprets it: each init gives its variable (a void
;; one gives none), then clause by clause, round and round, a step updates
;; its variable and a false pred ends the loop with that clause's fini.
(define-override! "java/lang/invoke/MethodHandleImpl.makeLoop(Ljava/lang/Class;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;)Ljava/lang/invoke/MethodHandle;"
  (lambda (tloop targs init step pred fini)
    (let ([clauses (map loop-clause (jlist->list init) (jlist->list step) (jlist->list pred) (jlist->list fini))])
      (make-mh (args->desc (map mirror->desc (jlist->list targs)) (mirror->desc tloop))
               (lambda xs (run-loop clauses xs))
               #f))))

;; one clause as #(init step pred fini init-void? step-void?), procedures
;; taking their arguments as the handles' types say
(define (loop-clause init step pred fini)
  (let ([proc (lambda (h) (mh-info-proc (mh-info-of h)))]
        [void? (lambda (h) (string=? (mh-info-ret (mh-info-of h)) "V"))])
    (vector (proc init) (proc step) (proc pred) (proc fini) (void? init) (void? step))))

(define (run-loop clauses args)
  (let ([vars (loop-inits clauses args)])
    (let again ()
      (let ([r (loop-pass clauses vars args)])
        (if (eq? r loop-goes-on) (again) r)))))
;; the loop variables, from the inits in clause order (a void init gives none)
(define (loop-inits clauses args)
  (let init ([cs clauses] [acc '()])
    (if (null? cs)
        (list->vector (reverse acc))
        (let ([v (apply (vector-ref (car cs) 0) args)])
          (init (cdr cs) (if (vector-ref (car cs) 4) acc (cons v acc)))))))
;; one round of the clauses: each step updates its variable (v counts the
;; variables so far), then a false pred ends the loop with that clause's fini
;; result; loop-goes-on when every pred held
(define loop-goes-on (list 'loop-goes-on))
(define (loop-pass clauses vars args)
  (define (vars-and-args) (append (vector->list vars) args))
  (let next ([cs clauses] [v 0])
    (if (null? cs)
        loop-goes-on
        (let* ([c (car cs)] [r (apply (vector-ref c 1) (vars-and-args))])
          (unless (vector-ref c 5) (vector-set! vars v r))
          (if (eqv? 0 (apply (vector-ref c 2) (vars-and-args)))
              (apply (vector-ref c 3) (vars-and-args))
              (next (cdr cs) (if (vector-ref c 5) v (fx+ v 1))))))))

(def-mhs "throwException" (string-append "(Ljava/lang/Class;Ljava/lang/Class;)" mh-ret)
  (lambda (ret exc)
    (make-mh (args->desc (list (mirror->desc (nn exc))) (mirror->desc (nn ret)))
             (lambda (e) (jathrow e)) #f)))
(def-mhs "tryFinally" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target cleanup)
    (let* ([ti (mh-info-of target)] [tp (mh-info-proc ti)] [tret (mh-info-ret ti)]
           [targs (mh-info-args ti)]
           [ci (mh-info-of cleanup)] [cp (mh-info-proc ci)] [cargs (mh-info-args ci)]
           [void? (string=? tret "V")]
           [n (fx- (length cargs) (if void? 1 2))])
      (unless (and (pair? cargs) (string=? (car cargs) "Ljava/lang/Throwable;"))
        (vh-iae "cleanup first argument and Throwable must match: "
                (mh-type-text cleanup) " != class java.lang.Throwable"))
      (unless (string=? (mh-info-ret ci) tret)
        (vh-iae "target and return types must match: "
                (vh-class-text (mh-info-ret ci)) " != " (vh-class-text tret)))
      (unless (and (fx>= n 0) (fx<= n (length targs))
                   (or void? (string=? (cadr cargs) tret))
                   (equal? (list-tail cargs (if void? 1 2)) (list-head targs n)))
        (vh-iae "target and cleanup types must match: "
                (mh-type-text target) " != " (mh-type-text cleanup)))
      ;; the cleanup takes the throwable (null on the normal path), the result
      ;; unless void (the default value on the thrown path), then an argument
      ;; prefix; its return is the result, and a thrown exception rethrows.
      ;; The normal-path cleanup sits outside the guard, so a cleanup that
      ;; throws runs once and its exception propagates, as the JDK's does
      (make-mh (mh-info-desc ti)
               (lambda xs
                 (let ([r (guard (e [(jobj? e)
                                     (begin (if void?
                                                (apply cp e (list-head xs n))
                                                (apply cp e (elem-default tret) (list-head xs n)))
                                            (raise e))])
                            (apply tp xs))])
                   (if void?
                       (begin (apply cp #f (list-head xs n)) r)
                       (apply cp #f r (list-head xs n)))))
               #f))))

;; --- SwitchBootstraps -------------------------------------------------------------
;; javac compiles a pattern switch to invokedynamic typeSwitch or enumSwitch
;; with the case labels as static arguments. Codegen turns those labels into
;; a list and calls these directly: the answer is the index of the first
;; label at or after `restart` that matches, -1 for null, and the label count
;; when none does. A switch on a primitive, or with a primitive pattern's
;; label (JEP 507, a preview in JDK 25), runs the JDK's bootstrap instead.
;;
;; label            matches
;; (class "C")      an instance of C
;; (string "s")     a String equal to s
;; (int n)          an Integer, Short, Byte or Character holding n
;; (name "RED")     the enum constant named RED (enumSwitch)
;; (enum "C" "RED") enum class C's constant RED

(define (enum-name x) (str-of (jslot x (jfield-index "java/lang/Enum" "name"))))

(define (switch-label-matches? x label)
  (case (car label)
    [(class) (jinstance? x (cadr label))]
    [(string) (and (jinstance? x "java/lang/String") (jstring-lit-equal? x (cadr label)))]
    [(int) (let ([p (box-prim-of x)])
             (and p (memv (string-ref p 0) '(#\I #\S #\B #\C)) (eqv? (junbox p x) (cadr label))))]
    [(name) (and (jinstance? x "java/lang/Enum") (string=? (enum-name x) (cadr label)))]
    [(enum) (and (jinstance? x (cadr label)) (string=? (enum-name x) (caddr label)))]
    [else #f]))

(define (jswitch-index x restart labels)
  (if (eq? x #f)
      -1
      (let loop ([ls (if (fx< restart (length labels)) (list-tail labels restart) '())] [i (fxmin restart (length labels))])
        (cond [(null? ls) i]
              [(switch-label-matches? x (car ls)) i]
              [else (loop (cdr ls) (fx+ i 1))]))))

;; --- other bootstraps ----------------------------------------------------------------
;; An invokedynamic whose bootstrap the translator doesn't know runs that
;; bootstrap for real on first use: a Lookup on the calling class, the call's
;; name and MethodType, then the static arguments. The CallSite it returns is
;; kept per call site. A
;; ConstantCallSite's target is kept directly, any other CallSite is asked
;; for its target on every call. The key is an interned symbol naming the
;; method and pc, so it is the same however often the method is compiled. An ldc of a MethodHandle, MethodType or
;; dynamic constant resolves the same way, once per site.
;;
;; Constants arrive as data from codegen (const-datum):
;; (int n) (long n) (float x) (double x) (string "s") (class "C")
;; (methodtype "desc") (mh kind "C" "name" "desc")
;; (condy "name" "desc" bootstrap-mh (static-args ...))
;; and the last three wrapped as (entry |Class#index| datum), resolved once
;; per constant-pool entry (resolve-once)

(define (const-mh kind cname name desc)
  (let ([c (find-class cname)])
    (case kind
      [(1) (field-mh c name (desc->mirror desc) #f #f)]
      [(2) (field-mh c name (desc->mirror desc) #t #f)]
      [(3) (field-mh c name (desc->mirror desc) #f #t)]
      [(4) (field-mh c name (desc->mirror desc) #t #t)]
      [(5 9) (virtual-mh c name desc)]
      [(6) (static-mh c name desc)]
      [(7) (special-mh c name desc c)]
      [(8) (constructor-mh c desc)]
      [else (jthrow "java/lang/IllegalArgumentException" (string-append "method handle kind " (number->string kind)))])))

;; a method handle constant whose member isn't there is the JVM's linkage
;; error with LinkResolver's text, where a Lookup throws NoSuchMethodException
;; and NoSuchFieldException (MethodHandleNatives.linkMethodHandleConstant)
(define (const-mh-linked kind cname name desc)
  (guard (e [(and (jobj? e) (jinstance? e "java/lang/NoSuchMethodException"))
             (jthrow "java/lang/NoSuchMethodError" (method-external cname name desc))]
            [(and (jobj? e) (jinstance? e "java/lang/NoSuchFieldException"))
             (jthrow "java/lang/NoSuchFieldError"
                     (string-append "Class " (slashes->dots cname) " does not have member field '"
                                    (type-external desc) " " name "'"))])
    (const-mh kind cname name desc)))

;; JVMS 5.4.3: a resolution that fails with a LinkageError fails the same way
;; every later time, without running anything again. HotSpot keeps the error's
;; class and message (and its cause's, for a constant) and throws new ones:
;; renew. Anything else that isn't a LinkageError (an Error a bootstrap
;; throws) isn't kept, so the next use resolves again. The first result kept
;; wins, as when two threads resolve at once.
(define failed-tag (list 'failed))
(define absent (list 'absent))
;; A null message is the entry's own text (null-msg), as HotSpot's
;; ConstantPool::exception_message gives one: a dynamic constant's or method
;; handle's name, a method type's descriptor; a call site's stays null. An
;; error class without a (String) constructor is thrown again as itself.
(define (renew e keep-cause? null-msg)
  (let* ([cname (jclass-cname (jobj-cls e))]
         [msg (or ((jvirt e "getMessage()Ljava/lang/String;") e) (and null-msg (jstring null-msg)))]
         [fresh (if (global-bound? (string->symbol (string-append cname ".<init>(Ljava/lang/String;)V")))
                    (construct cname "(Ljava/lang/String;)V" msg)
                    e)]
         [cause ((jvirt e "getCause()Ljava/lang/Throwable;") e)])
    (when (and keep-cause? cause (not (eq? fresh e)))
      ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;")
       fresh (renew cause #f #f)))
    fresh))
(define (failed? v) (and (pair? v) (eq? (car v) failed-tag)))
(define (resolve-once table key thunk keep-cause? null-msg)
  (let ([v (eq-hashtable-ref table key absent)])
    (cond [(failed? v) (raise (renew (cdr v) keep-cause? null-msg))]
          [(not (eq? v absent)) v]
          [else
           (let ([v (guard (e [(and (jobj? e) (jinstance? e "java/lang/LinkageError"))
                               ;; as HotSpot's save_and_throw_exception: kept when
                               ;; nothing is yet; another thread's failure kept first
                               ;; is thrown instead; after another thread's success,
                               ;; this thread still throws its own (only a class entry
                               ;; takes the success there)
                               (let ([kept (eq-hashtable-ref table key absent)])
                                 (cond [(eq? kept absent) (eq-hashtable-set! table key (cons failed-tag e)) (raise e)]
                                       [(failed? kept) (raise (renew (cdr kept) keep-cause? null-msg))]
                                       [else (raise e)]))])
                      (thunk))])
             (let ([kept (eq-hashtable-ref table key absent)])
               (cond [(failed? kept) (raise (renew (cdr kept) keep-cause? null-msg))]
                     [(not (eq? kept absent)) kept]
                     [else (eq-hashtable-set! table key v) v])))])))
;; the text a kept failure of entry datum d gets when its error had no message
(define (entry-null-msg d)
  (case (car d)
    [(condy) (cadr d)]
    [(mh) (list-ref d 3)]
    [(methodtype) (cadr d)]
    [else #f]))
(define entries (make-eq-hashtable))

(define (caller-lookup cname)
  (construct mh-lookup-class "(Ljava/lang/Class;)V" (jmirror-of cname)))

(define (const->java caller d)
  (case (car d)
    [(int) (jbox "I" (cadr d))]
    [(long) (jbox "J" (cadr d))]
    [(float) (jbox "F" (cadr d))]
    [(double) (jbox "D" (cadr d))]
    [(string) (jstring-lit (cadr d))]
    [(class) (jmirror-of (cadr d))]
    [(methodtype) (desc->method-type (cadr d))]
    [(mh) (apply const-mh-linked (cdr d))]
    [(condy) (resolve-condy caller d)]
    [(entry) (resolve-once entries (cadr d) (lambda () (const->java caller (caddr d))) #t (entry-null-msg (caddr d)))]
    [else (error 'champaca "bad bootstrap constant" d)]))

;; run bootstrap handle bsm-d with the leading arguments, then the static ones
(define (run-bootstrap caller bsm-d lead statics)
  (let ([args (append lead (map (lambda (d) (const->java caller d)) statics))])
    (invoke-with-arguments (const->java caller bsm-d)
                           (make-jarr obj-desc (list->vector args)))))

;; a dynamic constant: the bootstrap gets (lookup, name, type class, args...)
(define (resolve-condy caller d)
  (let ([name (list-ref d 1)] [desc (list-ref d 2)])
    (bootstrap-guarded name
      (lambda ()
        (run-bootstrap caller (list-ref d 3)
                       (list (caller-lookup caller) (jstring-lit name) (desc->mirror desc))
                       (list-ref d 4))))))

;; ConstantBootstraps.getStaticFinal asks the getter's handle for its
;; MemberName, which champaca's handles don't have (a closure): the same
;; resolution (resolve-field, as findStaticGetter's) and checks here, access
;; unchecked as for every Lookup here. javac's boolean case labels are this condy
;; (Boolean.TRUE, a primitive pattern's switch).
(define-override! "java/lang/invoke/ConstantBootstraps.getStaticFinal(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;"
  (lambda (lookup name type dc)
    (nn lookup) (nn name) (nn type) (nn dc)
    (let* ([fname (str-of name)]
           [hit (guard (e [(and (jobj? e) (jinstance? e "java/lang/ReflectiveOperationException"))
                           (jinit "java/lang/invoke/MethodHandleNatives")
                           (raise ((method-proc "java/lang/invoke/MethodHandleNatives"
                                                "mapLookupExceptionToError(Ljava/lang/ReflectiveOperationException;)Ljava/lang/LinkageError;")
                                   e))])
                  (resolve-field (mirror-class dc) fname type #t #f #f lookup))]
           [owner (jclass-cname (car hit))] [desc (list-ref hit 2)])
      (unless (fxpositive? (fxlogand (list-ref hit 3) #x10))
        (jthrow "java/lang/IncompatibleClassChangeError" (string-append "not a final field: " fname)))
      (jinit owner)
      (let ([v (top-level-value (string->symbol (string-append owner ":" fname)))])
        (if (fx= 1 (string-length desc)) (jbox desc v) v)))))

;; a bootstrap that throws anything but an Error throws BootstrapMethodError
;; with it as the cause, as the JVM's linkage does
(define (bme-guarded text thunk)
  (guard (e [(and (jobj? e) (not (jinstance? e "java/lang/Error")))
             (raise (construct "java/lang/BootstrapMethodError" "(Ljava/lang/String;Ljava/lang/Throwable;)V"
                               (jstring-lit text) e))])
    (thunk)))
(define (bootstrap-guarded name thunk) (bme-guarded "bootstrap method initialization exception" thunk))
(define (call-site-guarded thunk) (bme-guarded "CallSite bootstrap method initialization exception" thunk))

;; an ldc of a method type, method handle or dynamic constant: d is an entry
(define (jconst caller d desc) (adapt obj-desc desc (const->java caller d)))

(define indy-sites (make-eq-hashtable))
(define constant-call-site "java/lang/invoke/ConstantCallSite")

;; as CallSite.makeSite: what the bootstrap throws, and BootstrapMethodInvoker's
;; cast of what it returns, are "bootstrap method initialization exception";
;; no CallSite at all (null), or one whose target's type isn't exactly the
;; call's, is "CallSite bootstrap method initialization exception", with the
;; JDK's texts for the causes
(define (indy-link caller spec name desc)
  (let ([cs (bootstrap-guarded name
              (lambda ()
                (let ([cs (run-bootstrap caller (car spec)
                                         (list (caller-lookup caller) (jstring-lit name) (desc->method-type desc))
                                         (cadr spec))])
                  (when (and cs (not (jinstance? cs "java/lang/invoke/CallSite")))
                    (jthrow "java/lang/ClassCastException"
                            (string-append "Cannot cast " (slashes->dots (jclass-cname (jobj-cls cs))) " to java.lang.invoke.CallSite")))
                  cs)))])
    (call-site-guarded
     (lambda ()
       (unless cs
         (jthrow "java/lang/ClassCastException" "CallSite bootstrap method failed to produce an instance of CallSite"))
       (let* ([target (call-site-target cs)]
              [t (mt-desc ((jvirt target "type()Ljava/lang/invoke/MethodType;") target))])
         (unless (string=? t desc)
           (let ([str (lambda (o) (jstring->scheme ((jvirt o "toString()Ljava/lang/String;") o)))])
             (jthrow "java/lang/invoke/WrongMethodTypeException"
                     (string-append (str target) " should be of type " (str (desc->method-type desc)))))))))
    (if (jinstance? cs constant-call-site)
        (cons 'constant (call-site-target cs))
        (cons 'mutable cs))))

(define (call-site-target cs) ((jvirt cs "getTarget()Ljava/lang/invoke/MethodHandle;") cs))

(define (indy-call key caller spec name desc . args)
  ;; a link that failed fails again, a new error without its cause, as HotSpot's
  (let ([site (resolve-once indy-sites key (lambda () (indy-link caller spec name desc)) #f #f)])
    (apply mh-call (if (eq? (car site) 'constant) (cdr site) (call-site-target (cdr site))) desc args)))

;; a CallSite made from a MethodType has a target that throws until it is set
(define-override! "java/lang/invoke/CallSite.makeUninitializedCallSite(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
  (lambda (this mt)
    (let ([desc (mt-desc mt)])
      (make-mh desc (lambda xs (jthrow "java/lang/IllegalStateException" "uninitialized call site")) mt))))
;; a handle that calls whatever the site's target is at the time
(define-override! "java/lang/invoke/CallSite.makeDynamicInvoker()Ljava/lang/invoke/MethodHandle;"
  (lambda (cs)
    (let ([mt ((jvirt cs "type()Ljava/lang/invoke/MethodType;") cs)])
      (make-mh (mt-desc mt) (lambda xs (apply mh-call (call-site-target cs) (mt-desc mt) xs)) mt))))
(define (set-call-site-target! cs mh) (jslot-set! cs (jfield-index "java/lang/invoke/CallSite" "target") mh))
(defnative "java/lang/invoke/MethodHandleNatives.setCallSiteTargetNormal(Ljava/lang/invoke/CallSite;Ljava/lang/invoke/MethodHandle;)V" (cs mh)
  (set-call-site-target! cs mh))
(defnative "java/lang/invoke/MethodHandleNatives.setCallSiteTargetVolatile(Ljava/lang/invoke/CallSite;Ljava/lang/invoke/MethodHandle;)V" (cs mh)
  (set-call-site-target! cs mh))
