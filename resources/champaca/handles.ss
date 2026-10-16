;; Method handles, and the switch bootstraps behind pattern switches.
;;
;; A MethodHandle is a bare java/lang/invoke/MethodHandle object (allocated,
;; never constructed) whose `type` field holds a real MethodType, mapped in
;; mh-infos to a Scheme procedure and the descriptor it takes and returns.
;; The JDK builds handles out of MemberNames and LambdaForms, which need the
;; VM's resolver and run-time class generation, so the Lookup.find* methods,
;; asType, bindTo and the common MethodHandles combinators are overridden
;; here instead. Codegen sends invokeExact and invoke to mh-call with the call
;; site's descriptor, and mh-call converts arguments and result between the
;; two descriptors: boxing, unboxing and primitive widening, as asType would.
;; invokeExact is not checked for an exact type match, and access checks are
;; not done (spike).

(define-record-type mh-info (fields desc args ret proc varargs?))
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

(define (widen from to v)
  (if (and (memv (string-ref to 0) '(#\F #\D)) (not (memv (string-ref from 0) '(#\F #\D))))
      (inexact v)
      v))

;; v has type `from` and is wanted as `to`
(define (adapt from to v)
  (cond [(string=? from to) v]
        [(string=? to "V") v]
        [(string=? from "V") (if (prim-desc? to) (elem-default to) #f)]
        [(prim-desc? from) (if (prim-desc? to) (widen from to v) (jbox from v))]
        [(prim-desc? to)
         (let ([p (box-prim-of (nn v))])
           (unless p
             (jthrow "java/lang/ClassCastException"
                     (string-append "Cannot cast " (class-name-of v) " to " (cdr (assoc to prim-names)))))
           (widen p to (junbox p v)))]
        [else v]))

(define (adapt-all froms tos args)
  (if (equal? froms tos) args (map adapt froms tos args)))

;; a procedure taking `to-args` and returning `to-ret`, over info's procedure
(define (adapted-proc info to-args to-ret)
  (let ([p (mh-info-proc info)] [args (mh-info-args info)] [ret (mh-info-ret info)])
    (if (and (equal? args to-args) (string=? ret to-ret))
        p
        (lambda xs (adapt ret to-ret (apply p (adapt-all to-args args xs)))))))

(define (wrong-type info n)
  (jthrow "java/lang/invoke/WrongMethodTypeException"
          (string-append "handle takes " (number->string (length (mh-info-args info)))
                         " arguments, called with " (number->string n))))

;; a varargs collector called with the trailing arguments spread out
(define (collect-varargs info site-args xs)
  (let* ([fixed (fx- (length (mh-info-args info)) 1)]
         [arr-desc (list-ref (mh-info-args info) fixed)]
         [elem (substring arr-desc 1 (string-length arr-desc))]
         [rest-args (list-tail site-args fixed)])
    (when (fx< (length site-args) fixed) (wrong-type info (length site-args)))
    (append (adapt-all (list-head site-args fixed) (list-head (mh-info-args info) fixed) (list-head xs fixed))
            (list (make-jarr elem (list->vector (map (lambda (t v) (adapt t elem v)) rest-args (list-tail xs fixed))))))))

(define site-descs (make-weak-eq-hashtable))
(define (site-parts d)
  (or (eq-hashtable-ref site-descs d #f)
      (let ([p (cons (desc-args d) (desc-ret d))]) (eq-hashtable-set! site-descs d p) p)))

;; invokeExact and invoke: site is the call site's descriptor (a literal, so
;; its parse is cached by identity)
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

(define obj-desc "Ljava/lang/Object;")

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

(define (no-such-method c name desc how)
  (jthrow "java/lang/NoSuchMethodException"
          (string-append "no such method: " (slashes->dots (jclass-cname c)) "." name desc "/" how)))

(define (varargs-flag? meta) (fxpositive? (fxlogand (caddr meta) #x80)))

(define (static-mh c name desc)
  (let ([hit (find-method c name desc)])
    (unless (and hit (static-flag? (caddr (cdr hit)))) (no-such-method c name desc "invokeStatic"))
    (let* ([dc (jclass-cname (car hit))] [key (string-append name desc)])
      (make-mh desc (lambda xs (jinit dc) (apply (method-proc dc key) xs)) #f (varargs-flag? (cdr hit))))))

(define (virtual-mh c name desc)
  (let ([hit (find-method c name desc)])
    (unless (and hit (not (static-flag? (caddr (cdr hit))))) (no-such-method c name desc "invokeVirtual"))
    (let ([key (string-append name desc)])
      (make-mh (string-append "(" (cname->desc (jclass-cname c)) (substring desc 1 (string-length desc)))
               (lambda (o . xs) (apply (jvirt o key) o xs)) #f (varargs-flag? (cdr hit))))))

(define (special-mh c name desc caller)
  (let ([hit (find-method c name desc)])
    (unless hit (no-such-method c name desc "invokeSpecial"))
    (let ([dc (jclass-cname (car hit))] [key (string-append name desc)])
      (make-mh (string-append "(" (cname->desc (jclass-cname caller)) (substring desc 1 (string-length desc)))
               (lambda xs (apply (method-proc dc key) xs)) #f (varargs-flag? (cdr hit))))))

(define (constructor-mh c desc)
  (let* ([cname (jclass-cname c)]
         [m (find (lambda (m) (and (string=? (car m) "<init>") (string=? (cadr m) desc))) (meta-methods c))])
    (unless m (no-such-method c "<init>" desc "newInvokeSpecial"))
    (let ([key (string-append "<init>" desc)])
      ;; method-proc at call time: before its first call a method is a stub
      ;; that compiles it, and holding on to the stub would compile it again
      (make-mh (args->desc (desc-args desc) (cname->desc cname))
               (lambda xs (jinit cname) (let ([o (jnew cname)]) (apply (method-proc cname key) o xs) o))
               #f (varargs-flag? m)))))

;; getter or setter on field fname of c; static? from the lookup kind
(define (field-mh c fname type static? setter?)
  (let ([hit (find-field c fname static?)])
    (unless hit
      (jthrow "java/lang/NoSuchFieldException"
              (string-append "no such field: " (slashes->dots (jclass-cname c)) "." fname)))
    (let* ([dc (jclass-cname (car hit))] [desc (cadr (cdr hit))]
           [recv (cname->desc (jclass-cname c))])
      (if static?
          (let ([sym (string->symbol (string-append dc ":" fname))])
            (if setter?
                (make-mh (args->desc (list desc) "V") (lambda (v) (jinit dc) (set-top-level-value! sym v)) #f)
                (make-mh (args->desc '() desc) (lambda () (jinit dc) (top-level-value sym)) #f)))
          (let ([i (jfield-index dc fname)])
            (if setter?
                (make-mh (args->desc (list recv desc) "V") (lambda (o v) (vector-set! (jobj-slots (nn o)) i v)) #f)
                (make-mh (args->desc (list recv) desc) (lambda (o) (vector-ref (jobj-slots (nn o)) i)) #f)))))))

(define mh-lookup-class "java/lang/invoke/MethodHandles$Lookup")
(define (def-lookup name desc proc) (define-override! (string-append mh-lookup-class "." name desc) proc))
(define mh-ret "Ljava/lang/invoke/MethodHandle;")
(define (str-of s) (jstring->scheme (nn s)))

(def-lookup "findStatic" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c name mt) (static-mh (mirror-class (nn c)) (str-of name) (mt-desc mt))))
(def-lookup "findVirtual" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c name mt) (virtual-mh (mirror-class (nn c)) (str-of name) (mt-desc mt))))
(def-lookup "findSpecial" (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)" mh-ret)
  (lambda (this c name mt caller) (special-mh (mirror-class (nn c)) (str-of name) (mt-desc mt) (mirror-class (nn caller)))))
(def-lookup "findConstructor" (string-append "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)" mh-ret)
  (lambda (this c mt) (constructor-mh (mirror-class (nn c)) (mt-desc mt))))
(for-each
 (lambda (spec)
   (def-lookup (car spec) (string-append "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)" mh-ret)
     (lambda (this c name type) (field-mh (mirror-class (nn c)) (str-of name) type (cadr spec) (caddr spec)))))
 '(("findGetter" #f #f) ("findSetter" #f #t) ("findStaticGetter" #t #f) ("findStaticSetter" #t #t)))

;; reflective objects carry their class and their index in its meta list
(def-lookup "unreflect" (string-append "(Ljava/lang/reflect/Method;)" mh-ret)
  (lambda (this m)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" (nn m))])
      (if (static-flag? (caddr meta))
          (static-mh c (car meta) (cadr meta))
          (virtual-mh c (car meta) (cadr meta))))))
(def-lookup "unreflectSpecial" (string-append "(Ljava/lang/reflect/Method;Ljava/lang/Class;)" mh-ret)
  (lambda (this m caller)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" (nn m))])
      (special-mh c (car meta) (cadr meta) (mirror-class (nn caller))))))
(def-lookup "unreflectConstructor" (string-append "(Ljava/lang/reflect/Constructor;)" mh-ret)
  (lambda (this k)
    (let-values ([(c meta) (reflected-member "java/lang/reflect/Constructor" (nn k))])
      (constructor-mh c (cadr meta)))))
(for-each
 (lambda (spec)
   (def-lookup (car spec) (string-append "(Ljava/lang/reflect/Field;)" mh-ret)
     (lambda (this f)
       (let* ([c (mirror-class (jslot (nn f) (jfield-index "java/lang/reflect/Field" "clazz")))]
              [meta (list-ref (meta-fields c) (jslot f (jfield-index "java/lang/reflect/Field" "slot")))])
         (field-mh c (car meta) #f (static-flag? (caddr meta)) (cadr spec))))))
 '(("unreflectGetter" #f) ("unreflectSetter" #t)))

;; --- MethodHandle's own methods ------------------------------------------------------

(define (def-mh name desc proc) (define-override! (string-append "java/lang/invoke/MethodHandle." name desc) proc))

(define (as-type mh mt)
  (let* ([info (mh-info-of mh)] [desc (mt-desc mt)] [args (desc-args desc)])
    (unless (fx= (length args) (length (mh-info-args info))) (wrong-type info (length args)))
    (make-mh desc (adapted-proc info args (desc-ret desc)) mt)))

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
    (let* ([d (mirror->desc c)] [v (adapt obj-desc d x)])
      (make-mh (args->desc '() d) (lambda () v) #f))))
(def-mhs "identity" (string-append "(Ljava/lang/Class;)" mh-ret)
  (lambda (c) (let ([d (mirror->desc c)]) (make-mh (args->desc (list d) d) (lambda (x) x) #f))))
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
(def-mhs "filterReturnValue" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (target filter)
    (let* ([ti (mh-info-of target)] [fi (mh-info-of filter)] [tp (mh-info-proc ti)] [fp (mh-info-proc fi)])
      (unless (fx= 1 (length (mh-info-args fi))) (wrong-type fi 1))
      (let ([farg (car (mh-info-args fi))] [tret (mh-info-ret ti)])
        (make-mh (args->desc (mh-info-args ti) (mh-info-ret fi))
                 (lambda xs (fp (adapt tret farg (apply tp xs))))
                 #f)))))
(def-mhs "guardWithTest" (string-append "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)" mh-ret)
  (lambda (test target fallback)
    (let* ([ti (mh-info-of target)] [args (mh-info-args ti)] [ret (mh-info-ret ti)]
           [qi (mh-info-of test)] [n (length (mh-info-args qi))]
           [q (adapted-proc qi (list-head args n) "Z")]
           [on-true (mh-info-proc ti)] [on-false (adapted-proc (mh-info-of fallback) args ret)])
      (when (fx> n (length args)) (wrong-type qi (length args)))
      ;; the test takes a prefix of the target's arguments; booleans are 0 and 1
      (make-mh (mh-info-desc ti)
               (lambda xs (if (eqv? 0 (apply q (list-head xs n))) (apply on-false xs) (apply on-true xs)))
               #f))))
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
  as-type)

;; --- SwitchBootstraps -------------------------------------------------------------
;; javac compiles a pattern switch to invokedynamic typeSwitch or enumSwitch
;; with the case labels as static arguments. Codegen turns those labels into
;; a list and calls these directly: the answer is the index of the first
;; label at or after `restart` that matches, -1 for null, and the label count
;; when none does.
;;
;; label            matches
;; (class "C")      an instance of C
;; (prim "I")       any primitive value (only typeSwitch on a primitive has these)
;; (string "s")     a String equal to s
;; (int n)          an Integer, Short, Byte or Character holding n
;; (name "RED")     the enum constant named RED (enumSwitch)
;; (enum "C" "RED") enum class C's constant RED

(define (enum-name x) (str-of (jslot x (jfield-index "java/lang/Enum" "name"))))

(define (switch-label-matches? x label)
  (case (car label)
    [(class) (jinstance? x (cadr label))]
    [(prim) (number? x)]
    [(string) (and (jinstance? x "java/lang/String") (string=? (jstring->scheme x) (cadr label)))]
    [(int) (let ([p (box-prim-of x)])
             (and p (memv (string-ref p 0) '(#\I #\S #\B #\C)) (eqv? (junbox p x) (cadr label))))]
    [(name) (and (jinstance? x "java/lang/Enum") (string=? (enum-name x) (cadr label)))]
    [(enum) (and (jinstance? x (cadr label)) (string=? (enum-name x) (caddr label)))]
    [else #f]))

(define (jswitch-index x restart labels)
  (if (eq? x #f)
      -1
      (let loop ([ls (list-tail labels restart)] [i restart])
        (cond [(null? ls) i]
              [(switch-label-matches? x (car ls)) i]
              [else (loop (cdr ls) (fx+ i 1))]))))

;; --- other bootstraps ----------------------------------------------------------------
;; An invokedynamic whose bootstrap the translator doesn't know runs that
;; bootstrap for real on first use: a Lookup on the calling class, the call's
;; name and MethodType, then the static arguments. The CallSite it returns is
;; kept per call site, keyed by the identity of the site's literal key. A
;; ConstantCallSite's target is kept directly, any other CallSite is asked
;; for its target on every call. An ldc of a MethodHandle, MethodType or
;; dynamic constant resolves the same way, once per site.
;;
;; Constants arrive as data from codegen (const-datum):
;; (int n) (long n) (float x) (double x) (string "s") (class "C")
;; (methodtype "desc") (mh kind "C" "name" "desc")
;; (condy "name" "desc" bootstrap-mh (static-args ...))

(define (const-mh kind cname name desc)
  (let ([c (find-class cname)])
    (case kind
      [(1) (field-mh c name #f #f #f)]
      [(2) (field-mh c name #f #t #f)]
      [(3) (field-mh c name #f #f #t)]
      [(4) (field-mh c name #f #t #t)]
      [(5 9) (virtual-mh c name desc)]
      [(6) (static-mh c name desc)]
      [(7) (special-mh c name desc c)]
      [(8) (constructor-mh c desc)]
      [else (jthrow "java/lang/IllegalArgumentException" (string-append "method handle kind " (number->string kind)))])))

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
    [(mh) (apply const-mh (cdr d))]
    [(condy) (resolve-condy caller d)]
    [else (error 'champaca "bad bootstrap constant" d)]))

;; run bootstrap handle bsm-d with the leading arguments, then the static ones
(define (run-bootstrap caller bsm-d lead statics)
  (let ([args (append lead (map (lambda (d) (const->java caller d)) statics))])
    (invoke-with-arguments (const->java caller bsm-d)
                           (make-jarr obj-desc (list->vector args)))))

;; a dynamic constant: the bootstrap gets (lookup, name, type class, args...)
(define (resolve-condy caller d)
  (let ([name (list-ref d 1)] [desc (list-ref d 2)])
    (run-bootstrap caller (list-ref d 3)
                   (list (caller-lookup caller) (jstring-lit name) (desc->mirror desc))
                   (list-ref d 4))))

(define const-sites (make-eq-hashtable))
(define (jconst-at key caller d desc)
  (let ([v (or (eq-hashtable-ref const-sites key #f)
               (let ([v (const->java caller d)]) (eq-hashtable-set! const-sites key v) v))])
    (adapt obj-desc desc v)))

(define indy-sites (make-eq-hashtable))
(define constant-call-site "java/lang/invoke/ConstantCallSite")

(define (indy-link caller spec name desc)
  (let ([cs (run-bootstrap caller (car spec)
                           (list (caller-lookup caller) (jstring-lit name) (desc->method-type desc))
                           (cadr spec))])
    (unless (jinstance? cs "java/lang/invoke/CallSite")
      (jthrow "java/lang/BootstrapMethodError" (string-append "bootstrap for " name " returned no CallSite")))
    (if (jinstance? cs constant-call-site)
        (cons 'constant (call-site-target cs))
        (cons 'mutable cs))))

(define (call-site-target cs) ((jvirt cs "getTarget()Ljava/lang/invoke/MethodHandle;") cs))

(define (indy-call key caller spec name desc . args)
  (let ([site (or (eq-hashtable-ref indy-sites key #f)
                  (let ([s (indy-link caller spec name desc)]) (eq-hashtable-set! indy-sites key s) s))])
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
