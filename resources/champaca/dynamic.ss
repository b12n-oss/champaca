;; Descriptor-free calls from jolt: find the method by name and argument
;; count, then pick the overload whose parameter types best fit the actual
;; jolt arguments, the way javac picks among overloads for a call site.
;;
;; Fit costs per argument (lower is better, #f means not applicable):
;;   exact primitive match 0, widening a few points more, boxing more still,
;;   passing to a supertype (Object, Number, CharSequence) the most.
;; A jolt fn fits a parameter whose type is a single-method interface and is
;; wrapped as an implementation of it. Results come back with boxed numbers,
;; Booleans and Characters unwrapped, and Strings as jolt strings.

;; lookup failures are reported to jolt as ex-info, not as Scheme errors
(define-record-type api-error (fields message))
(define (api-fail msg) (raise (make-api-error msg)))
(define (ldk-api-error? e) (api-error? e))
(define (ldk-api-error-message e) (api-error-message e))

(define lambda-class-hook #f)   ; set from jolt: (iface sam-key) -> class name
(define (set-lambda-class-hook! f) (set! lambda-class-hook f))


(define (exact-int? v) (and (number? v) (exact? v) (integer? v)))
(define (int-range? v) (and (exact-int? v) (<= -2147483648 v 2147483647)))
(define (long-range? v) (and (exact-int? v) (<= -9223372036854775808 v 9223372036854775807)))

(define boxes
  '(("java/lang/Integer" . "I") ("java/lang/Long" . "J") ("java/lang/Double" . "D")
    ("java/lang/Float" . "F") ("java/lang/Short" . "S") ("java/lang/Byte" . "B")
    ("java/lang/Boolean" . "Z") ("java/lang/Character" . "C")))

(define (ref-class d) (and (memv (string-ref d 0) '(#\L #\[)) (desc->class-name d)))

;; cost of boxing a value whose box class is `box` into reference type d
(define (box-cost box d base)
  (let ([target (ref-class d)])
    (cond [(not target) #f]
          [(string=? target box) base]
          [(class-assignable? box target) (fx+ base 2)]
          [else #f])))

(define (java-value? v) (or (jobj? v) (jarr? v)))

;; the single abstract method of interface iface, as "name(desc)", or #f
(define (sam-of iface)
  (guard (e [#t #f])
    (let ([c (find-class iface)])
      (and (eq? (jclass-kind c) 'interface)
           (let loop ([todo (list iface)] [seen '()] [found '()])
             (if (null? todo)
                 (and (= (length found) 1) (car found))
                 (let* ([n (car todo)] [k (find-class n)])
                   (if (member n seen)
                       (loop (cdr todo) seen found)
                       (loop (append (cdr todo) (jclass-interfaces k)) (cons n seen)
                             (fold-left
                              (lambda (acc m)
                                (let ([key (string-append (car m) (cadr m))])
                                  (if (and (fxpositive? (fxlogand (caddr m) #x400))
                                           (not (member key '("equals(Ljava/lang/Object;)Z" "hashCode()I"
                                                              "toString()Ljava/lang/String;")))
                                           (not (exists (lambda (f) (string=? (car (string-split-paren f)) (car m))) acc)))
                                      (cons key acc)
                                      acc)))
                              found (meta-methods k)))))))))))

(define (string-split-paren key)
  (let loop ([i 0]) (if (char=? (string-ref key i) #\() (list (substring key 0 i)) (loop (fx+ i 1)))))

(define (array-elem d) (substring d 1 (string-length d)))
(define (seq-arg? v) (or (pair? v) (null? v)))   ; a jolt vector, sent as a Scheme list

;; --- collections bridge ----------------------------------------------------------------
;; A jolt map, set or list arrives as a coll-arg (built by api.clj's ->arg):
;; kind 'map with (key . value) pairs, 'set or 'list with its items. It fits a
;; parameter its java.util class can be assigned to, and is copied into a fresh
;; LinkedHashMap, LinkedHashSet or ArrayList, keeping jolt's order. Inside a
;; collection every value is converted as an Object, and a nested vector
;; becomes an ArrayList rather than an array.
(define-record-type coll-arg (fields kind items))
(define (make-jolt-map pairs) (make-coll-arg 'map pairs))
(define (make-jolt-set items) (make-coll-arg 'set items))
(define (make-jolt-list items) (make-coll-arg 'list items))
(define (coll-class v)
  (case (coll-arg-kind v)
    [(map) "java/util/LinkedHashMap"] [(set) "java/util/LinkedHashSet"] [else "java/util/ArrayList"]))

(define (new-java-coll cname)
  (jinit cname)
  (let ([o (jnew cname)]) ((method-proc cname "<init>()V") o) o))

(define (java-list items)
  (let ([l (new-java-coll "java/util/ArrayList")])
    (for-each (lambda (x) ((jvirt l "add(Ljava/lang/Object;)Z") l (elem->java x))) items)
    l))

(define (coll->java v)
  (let ([o (new-java-coll (coll-class v))])
    (if (eq? (coll-arg-kind v) 'map)
        (for-each (lambda (kv)
                    ((jvirt o "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                     o (elem->java (car kv)) (elem->java (cdr kv))))
                  (coll-arg-items v))
        (for-each (lambda (x) ((jvirt o "add(Ljava/lang/Object;)Z") o (elem->java x)))
                  (coll-arg-items v)))
    o))

;; a value inside a collection, or given to ->java
(define (elem->java x)
  (cond [(coll-arg? x) (coll->java x)]
        [(seq-arg? x) (java-list x)]
        [else (convert-arg x "Ljava/lang/Object;")]))
(define (dyn->java x) (elem->java x))

;; the array type a vector becomes when nothing else says: int[], long[],
;; double[], String[], a nested array type, or Object[]
(define (inferred-array-desc v)
  (cond
    [(null? v) "[Ljava/lang/Object;"]
    [(for-all int-range? v) "[I"]
    [(for-all exact-int? v) "[J"]
    [(for-all flonum? v) "[D"]
    [(for-all string? v) "[Ljava/lang/String;"]
    [(for-all seq-arg? v)
     (let ([inner (map inferred-array-desc v)])
       (if (for-all (lambda (d) (string=? d (car inner))) inner)
           (string-append "[" (car inner))
           "[Ljava/lang/Object;"))]
    [else "[Ljava/lang/Object;"]))

(define (fit v d)
  (let ([c0 (string-ref d 0)])
    (cond
      [(eq? v jolt-nil) (and (ref-class d) 0)]
      ;; vector -> array: every element must fit the component type. Where
      ;; the parameter is Object-like, the array type comes from the contents.
      [(seq-arg? v)
       (if (char=? c0 #\[)
           (let ([costs (map (lambda (e) (fit e (array-elem d))) v)])
             (and (for-all (lambda (c) c) costs)
                  (fx+ 1 (fold-left fxmax 0 costs))))
           (let ([t (ref-class d)])
             (and t (or (class-assignable? (inferred-array-desc v) t)
                        (class-assignable? "java/util/ArrayList" t))
                  6)))]
      [(coll-arg? v)
       (let ([t (ref-class d)] [from (coll-class v)])
         (and t (class-assignable? from t) (if (string=? t from) 4 6)))]
      [(boolean? v) (if (char=? c0 #\Z) 0 (box-cost "java/lang/Boolean" d 1))]
      [(char? v) (case c0 [(#\C) 0] [(#\I) 2] [(#\J) 3] [else (box-cost "java/lang/Character" d 5)])]
      [(exact-int? v)
       (case c0
         [(#\I) (and (int-range? v) 0)]
         [(#\J) (and (long-range? v) 1)]
         [(#\S) #f] [(#\B) #f] [(#\C) #f]
         [(#\F) 4] [(#\D) 3] [(#\Z) #f]
         [else (if (int-range? v)
                   (or (box-cost "java/lang/Integer" d 5) (box-cost "java/lang/Long" d 7))
                   (box-cost "java/lang/Long" d 5))])]
      [(flonum? v)
       (case c0 [(#\D) 0] [(#\F) 1] [(#\I #\J #\S #\B #\C #\Z) #f]
         [else (box-cost "java/lang/Double" d 5)])]
      [(string? v)
       (let ([t (ref-class d)])
         (and t (cond [(string=? t "java/lang/String") 0]
                      [(class-assignable? "java/lang/String" t) 2]
                      [else #f])))]
      [(java-value? v)
       (let ([t (ref-class d)] [from (class-name-of v)])
         (cond
           [t (and (class-assignable? from t) (if (string=? from t) 0 1))]
           ;; a boxed Java number passed to a primitive parameter
           [(assoc from boxes) => (lambda (b) (and (string=? (cdr b) (string c0)) 3))]
           [else #f]))]
      [(procedure? v) (let ([t (ref-class d)]) (and t (sam-of t) 1))]
      [else #f])))

(define (convert-arg v d)
  (let ([c0 (string-ref d 0)])
    (cond
      [(eq? v jolt-nil) #f]
      [(seq-arg? v)
       (if (or (char=? c0 #\[) (class-assignable? (inferred-array-desc v) (ref-class d)))
           (let ([e (array-elem (if (char=? c0 #\[) d (inferred-array-desc v)))])
             (make-jarr e (list->vector (map (lambda (x) (convert-arg x e)) v))))
           ;; List, Collection, Iterable: an ArrayList
           (java-list v))]
      [(coll-arg? v) (coll->java v)]
      [(java-value? v)
       (if (memv c0 '(#\L #\[)) v (junbox (string c0) v))]
      [(memv c0 '(#\I #\J #\S #\B)) (if (char? v) (char->integer v) v)]
      [(char=? c0 #\C) (if (char? v) (char->integer v) v)]
      [(char=? c0 #\Z) (if v 1 0)]
      [(char=? c0 #\D) (inexact v)]
      [(char=? c0 #\F) (f32 (inexact v))]
      ;; reference parameter, jolt value: wrap
      [(string? v) (jstring v)]
      [(boolean? v) (jbox "Z" (if v 1 0))]
      [(char? v) (jbox "C" (char->integer v))]
      [(flonum? v) (let ([t (ref-class d)]) (if (string=? t "java/lang/Float") (jbox "F" (f32 v)) (jbox "D" v)))]
      [(exact-int? v)
       (let ([t (ref-class d)])
         (cond [(string=? t "java/lang/Long") (jbox "J" v)]
               [(int-range? v) (jbox "I" v)]
               [else (jbox "J" v)]))]
      [(procedure? v) (fn->java v (ref-class d))]
      [else v])))

;; jolt fn -> instance of the single-method interface iface
(define (fn->java f iface)
  (let* ([key (sam-of iface)]
         [desc (substring key (length-of-name key) (string-length key))]
         [cname (lambda-class-hook iface key)]
         [arg-ds (desc-args desc)] [ret-d (desc-ret desc)])
    (jlambda cname
             (lambda args
               (let ([r (call-from-java f (map dyn-out arg-ds args))])
                 (case (string-ref ret-d 0)
                   [(#\V) (void)]
                   [(#\Z) (if (jolt-true? r) 1 0)]
                   [else (convert-arg r ret-d)]))))))

(define (length-of-name key) (string-length (car (string-split-paren key))))

;; result -> jolt value, unwrapping boxes
(define (dyn-out d v)
  (case (string-ref d 0)
    [(#\V) jolt-nil]
    [(#\Z) (not (eqv? v 0))]
    [(#\C) (integer->char v)]
    [(#\L #\[)
     (cond [(not v) jolt-nil]
           [(java-string? v) (jstring->scheme v)]
           [(and (jobj? v) (assoc (class-name-of v) boxes))
            => (lambda (b) (dyn-out (cdr b) (junbox (cdr b) v)))]
           [else v])]
    [else v]))

;; --- candidate lookup ---------------------------------------------------------------

(define (params-of desc) (substring desc 0 (fx+ 1 (let loop ([i 0]) (if (char=? (string-ref desc i) #\)) i (loop (fx+ i 1)))))))

;; all (owner name desc flags) for name with n args, most-derived first,
;; one per descriptor; bridges and synthetics skipped
(define (candidates cname name n static?)
  (candidates-where cname name static? (lambda (np flags) (fx= np n))))

;; varargs methods (ACC_VARARGS) that n arguments can reach by packing
(define (varargs-candidates cname name n static?)
  (candidates-where cname name static?
                    (lambda (np flags) (and (fxpositive? (fxlogand flags #x80)) (fx<= (fx- np 1) n)))))

(define (candidates-where cname name static? arity-ok?)
  (candidates-from (list cname) name static? arity-ok? public?)) ; jolt calls from outside the class

(define (candidates-from roots name static? arity-ok? access-ok?)
  (let loop ([todo roots] [seen '()] [acc '()])
    (if (null? todo)
        (reverse acc)
        (let ([o (car todo)])
          (if (member o seen)
              (loop (cdr todo) seen acc)
              (let ([k (find-class o)])
                (loop (append (cdr todo)
                              (if (jclass-super k) (list (jclass-super k)) '())
                              (if static? '() (jclass-interfaces k)))
                      (cons o seen)
                      (fold-left
                       (lambda (acc m)
                         (if (and (string=? (car m) name)
                                  (arity-ok? (length (desc-args (cadr m))) (caddr m))
                                  (eq? static? (static-flag? (caddr m)))
                                  (access-ok? (caddr m))
                                  (fxzero? (fxlogand (caddr m) #x1040))
                                  ;; one per parameter list: a covariant override
                                  ;; (same params, narrower return) hides its parent's
                                  (not (exists (lambda (a) (string=? (params-of (caddr a)) (params-of (cadr m)))) acc)))
                             (cons (list o (car m) (cadr m) (caddr m)) acc)
                             acc))
                       acc (meta-methods k)))))))))

(define (score desc args)
  (let loop ([ds (desc-args desc)] [as args] [total 0])
    (if (null? ds) total
        (let ([c (fit (car as) (car ds))])
          (and c (loop (cdr ds) (cdr as) (fx+ total c)))))))

(define (describe-candidates cands)
  (apply string-append (map (lambda (c) (string-append "\n  " (car c) "." (cadr c) (caddr c))) cands)))

(define (best-of cname name scored)
  ;; scored: list of (score candidate . args). Lowest score wins; ties go to
  ;; the most specific method (JLS 15.12.2.5), e.g. String.valueOf(null) is
  ;; valueOf(char[]), not valueOf(Object).
  (let* ([best (fold-left min (caar scored) (map car scored))]
         [tied (filter (lambda (s) (fx= (car s) best)) scored)]
         [winners (let ([ms (filter (lambda (s) (for-all (lambda (o) (more-specific? (cadr s) (cadr o))) tied)) tied)])
                    (if (null? ms) tied ms))])
    (when (> (length winners) 1)
      (api-fail (string-append "ambiguous call to " cname "." name ":"
                               (describe-candidates (map cadr winners)))))
    (cdar winners)))

;; args packed for a varargs call: the trailing ones become one list (array)
(define (pack-varargs desc args)
  (let ([np (length (desc-args desc))])
    (append (list-head args (fx- np 1)) (list (list-tail args (fx- np 1))))))

;; Overload resolution in javac's order: fixed arity first (JLS phases 1
;; and 2, scored together), and varargs only when nothing fixed fits (phase 3).
;; Returns (candidate . effective-args).
(define (choose who cname name fixed varargs args)
  (let ([scored (filter car (map (lambda (c) (cons (score (caddr c) args) (cons c args))) fixed))])
    (if (pair? scored)
        (best-of cname name scored)
        (let ([vscored (filter car (map (lambda (c)
                                          (let ([packed (pack-varargs (caddr c) args)])
                                            (cons (score (caddr c) packed) (cons c packed))))
                                        varargs))])
          (when (null? vscored)
            (let ([all (append fixed varargs)])
              (api-fail
               (string-append "no " name " in " cname " fits " (number->string (length args)) " argument(s)"
                              (if (null? all) " (no method of that name and arity)"
                                  (string-append "; candidates:" (describe-candidates all)))))))
          (best-of cname name vscored)))))

;; m1 is at least as specific as m2: each parameter type of m1 converts to
;; m2's by subtyping or primitive widening
(define widening
  '(("B" "S" "I" "J" "F" "D") ("S" "I" "J" "F" "D") ("C" "I" "J" "F" "D")
    ("I" "J" "F" "D") ("J" "F" "D") ("F" "D") ("D") ("Z")))
(define (param-more-specific? a b)
  (cond [(string=? a b) #t]
        [(and (ref-class a) (ref-class b)) (class-assignable? (ref-class a) (ref-class b))]
        [(or (ref-class a) (ref-class b)) #f]
        [else (let ([w (assoc a widening)]) (and w (member b w) #t))]))
(define (more-specific? m1 m2)
  (for-all param-more-specific? (desc-args (caddr m1)) (desc-args (caddr m2))))

;; --- call-site cache -----------------------------------------------------------------
;; The overload choice depends on the arguments only through what `fit` looks
;; at: a Java value's class, an integer's range, the kind of a jolt value. So
;; (kind class name . arg-keys) names the choice, and a hit skips the candidate
;; walk and the scoring. A vector's fit depends on its contents, so a call with
;; one isn't cached. Failed lookups aren't cached, so they raise every time.
;; The entry keeps the method's symbol, not its procedure: a method compiles on
;; first call and its top-level value is replaced then.

(define-record-type call-site (fields pick packed? mkey sym arg-ds ret-d) (sealed #t) (nongenerative champaca-call-site))

(define call-cache (make-hashtable equal-hash equal?))
(define call-cache-on? (not (equal? (getenv "CHAMPACA_CALL_CACHE") "off")))
(define call-cache-limit 4096)   ; cleared when full, so a polymorphic loop can't grow it unbounded
(define (call-cache-size) (hashtable-size call-cache))
(define (call-cache-clear!) (hashtable-clear! call-cache))

(define (arg-key v)
  (cond [(eq? v jolt-nil) 'nil]
        [(java-value? v) (class-name-of v)]
        [(string? v) 'string]
        [(boolean? v) 'boolean]
        [(char? v) 'char]
        [(exact-int? v) (cond [(int-range? v) 'int] [(long-range? v) 'long] [else 'big])]
        [(flonum? v) 'double]
        [(coll-arg? v) (coll-arg-kind v)]
        [(procedure? v) 'fn]
        ;; a vector whose elements share one key fits like any other of that
        ;; key, whatever its length; a mixed one isn't cached
        [(null? v) 'empty-vector]
        [(pair? v) (let ([k (arg-key (car v))])
                     (and k (for-all (lambda (x) (equal? (arg-key x) k)) (cdr v)) (list 'vector k)))]
        [else #f]))

(define (args-key args)
  (let loop ([as args] [acc '()])
    (if (null? as)
        (reverse acc)
        (let ([k (arg-key (car as))]) (and k (loop (cdr as) (cons k acc)))))))

;; site for kind/cname/name/args, with the arguments as the call sees them
;; (varargs packed). sym-of: (owner mkey) -> the symbol to call, or #f.
(define (site-for kind cname name args find sym-of)
  (let* ([k (and call-cache-on? (args-key args))]
         [key (and k (cons* kind cname name k))]
         [hit (and key (hashtable-ref call-cache key #f))])
    (if hit
        (values hit (if (call-site-packed? hit) (pack-varargs (caddr (call-site-pick hit)) args) args))
        (let* ([chosen (find)]
               [pick (car chosen)] [desc (caddr pick)]
               [mkey (string-append (cadr pick) desc)]
               [site (make-call-site pick (not (eq? (cdr chosen) args)) mkey (sym-of (car pick) mkey)
                                     (desc-args desc) (desc-ret desc))])
          (when key
            (when (fx>= (hashtable-size call-cache) call-cache-limit) (hashtable-clear! call-cache))
            (hashtable-set! call-cache key site))
          (values site (cdr chosen))))))

(define (static-sym owner mkey) (string->symbol (string-append owner "." mkey)))

;; --- entry points --------------------------------------------------------------------

(define (dyn-new cname . args)
  (let-values ([(site args)
                (site-for 'new cname "<init>" args
                          (lambda ()
                            (let* ([cname (slashes cname)]
                                   [c (begin (with-java-errors (lambda () (jinit cname))) (find-class cname))]
                                   [cands (filter (lambda (m) (and (string=? (cadr m) "<init>") (public? (cadddr m))))
                                                  (map (lambda (m) (list cname (car m) (cadr m) (caddr m))) (meta-methods c)))]
                                   [fixed (filter (lambda (m) (fx= (length (desc-args (caddr m))) (length args))) cands)]
                                   [va (filter (lambda (m) (and (fxpositive? (fxlogand (cadddr m) #x80))
                                                                (fx<= (fx- (length (desc-args (caddr m))) 1) (length args)))) cands)])
                              (choose "new" cname "<init>" fixed va args)))
                          static-sym)])
    (let ([owner (car (call-site-pick site))])
      (with-java-errors
       (lambda ()
         (jinit owner)
         (let ([o (jnew owner)])
           (apply (top-level-value (call-site-sym site)) o (map convert-arg args (call-site-arg-ds site)))
           o))))))

(define (dyn-invoke obj name . args)
  (let* ([obj (if (string? obj) (jstring obj) obj)]
         [lookup (if (jarr? (nn obj)) "java/lang/Object" (class-name-of obj))])
    (let-values ([(site args)
                  (site-for 'virtual lookup name args
                            (lambda ()
                              (choose "invoke" lookup name (candidates lookup name (length args) #f)
                                      (varargs-candidates lookup name (length args) #f) args))
                            (lambda (owner mkey) #f))])
      (with-java-errors
       (lambda ()
         (dyn-out (call-site-ret-d site)
                  (apply (jvirt obj (call-site-mkey site)) obj (map convert-arg args (call-site-arg-ds site)))))))))

(define (dyn-invoke-static cname name . args)
  (let-values ([(site args)
                (site-for 'static cname name args
                          (lambda ()
                            (let ([cname (slashes cname)])
                              (find-class cname)
                              (choose "invoke-static" cname name (candidates cname name (length args) #t)
                                      (varargs-candidates cname name (length args) #t) args)))
                          static-sym)])
    (with-java-errors
     (lambda ()
       (jinit (car (call-site-pick site)))
       (dyn-out (call-site-ret-d site)
                (apply (top-level-value (call-site-sym site)) (map convert-arg args (call-site-arg-ds site))))))))

;; A static field as (owner desc flags), found as getstatic resolves it
;; (JVMS 5.4.3.2): the class, then its superinterfaces depth first, then its
;; superclass. An instance field the same way up the superclasses only.
(define (find-static-field cname fname)
  (let loop ([todo (list cname)] [seen '()])
    (and (pair? todo)
         (let ([o (car todo)])
           (if (member o seen)
               (loop (cdr todo) seen)
               (let* ([c (find-class o)]
                      [f (find (lambda (f) (and (string=? (car f) fname) (static-flag? (caddr f)))) (meta-fields c))])
                 (if f
                     (list o (cadr f) (caddr f))
                     (loop (append (jclass-interfaces c) (if (jclass-super c) (list (jclass-super c)) '()) (cdr todo))
                           (cons o seen)))))))))
(define (find-instance-field cname fname)
  (let loop ([n cname])
    (let* ([c (find-class n)]
           [f (find (lambda (f) (and (string=? (car f) fname) (not (static-flag? (caddr f))))) (meta-fields c))])
      (cond [f (list n (cadr f) (caddr f))] [(jclass-super c) (loop (jclass-super c))] [else #f]))))

(define (static-field cname fname)
  (let* ([cname (slashes cname)] [f (begin (find-class cname) (find-static-field cname fname))])
    (unless f (api-fail (string-append "no static field " fname " in " cname)))
    ;; only the declaring class is initialized, as getstatic does
    (with-java-errors (lambda () (jinit (car f))))
    f))
(define (instance-field obj fname)
  (let* ([cname (class-name-of (nn obj))] [f (find-instance-field cname fname)])
    (unless f (api-fail (string-append "no field " fname " in " cname)))
    f))
(define (static-sym-of f fname) (string->symbol (string-append (car f) ":" fname)))

;; a descriptor as Java writes the type: int, java.lang.String, int[]
(define (desc-java-name d)
  (case (string-ref d 0)
    [(#\[) (string-append (desc-java-name (substring d 1 (string-length d))) "[]")]
    [(#\L) (slashes->dots (substring d 1 (fx- (string-length d) 1)))]
    [else (cdr (assoc d prim-names))]))

;; v as the value of a field of type d: what a parameter of type d accepts,
;; plus an in-range integer for byte, short and char (an assignment's
;; narrowing of a constant, JLS 5.2)
(define (field-value f fname v)
  (let ([d (cadr f)])
    (when (fxpositive? (fxlogand (caddr f) #x10))
      (api-fail (string-append "field " fname " in " (car f) " is final")))
    (unless (or (fit v d)
                (and (exact-int? v)
                     (case (string-ref d 0)
                       [(#\B) (<= -128 v 127)] [(#\S) (<= -32768 v 32767)] [(#\C) (<= 0 v 65535)]
                       [else #f])))
      (api-fail (string-append "field " fname " in " (car f) " has type " (desc-java-name d) ", which the value doesn't fit")))
    (convert-arg v d)))

(define (dyn-get-static cname fname)
  (let ([f (static-field cname fname)])
    (dyn-out (cadr f) (top-level-value (static-sym-of f fname)))))
(define (dyn-set-static! cname fname v)
  (let* ([f (static-field cname fname)] [x (field-value f fname v)])
    (set-top-level-value! (static-sym-of f fname) x)
    (void)))

(define (dyn-get-field obj fname)
  (let ([f (instance-field obj fname)])
    (dyn-out (cadr f) (vector-ref (jobj-slots obj) (jfield-index (car f) fname)))))
(define (dyn-set-field! obj fname v)
  (let* ([f (instance-field obj fname)] [x (field-value f fname v)])
    (vector-set! (jobj-slots obj) (jfield-index (car f) fname) x)
    (void)))

;; arrays stay Java arrays across the boundary; ->clj copies one out
(define (dyn-array? v) (jarr? v))
(define (dyn-instance? v cname) (and (java-value? v) (jinstance? v cname)))
;; a Collection's elements, or a Map's keys and values alternating, as Object[]
(define (dyn-coll-array c) ((jvirt c "toArray()[Ljava/lang/Object;") c))
(define (dyn-map-array m)
  (let* ([es ((jvirt m "entrySet()Ljava/util/Set;") m)]
         [it ((jvirt es "iterator()Ljava/util/Iterator;") es)])
    (let loop ([acc '()])
      (if (eqv? 0 ((jvirt it "hasNext()Z") it))
          (make-jarr "Ljava/lang/Object;" (list->vector (reverse acc)))
          (let ([e ((jvirt it "next()Ljava/lang/Object;") it)])
            (loop (cons ((jvirt e "getValue()Ljava/lang/Object;") e)
                        (cons ((jvirt e "getKey()Ljava/lang/Object;") e) acc))))))))
(define (dyn-alength a) (vector-length (jarr-vec a)))
(define (dyn-aref a i) (dyn-out (jarr-elem a) (vector-ref (jarr-vec a) i)))

;; --- java.lang.reflect.Proxy from jolt -------------------------------------------------
;; A proxy for interfaces whose handler finds a jolt fn by method name
;; (lookup gives the fn or jolt nil). Arguments come out as dyn-out gives
;; them by its parameter types; the result goes back boxed for the method's
;; return type, so a long method can return 5 and a double one 2, and a
;; boolean one takes jolt's truth, nil being false. A result that doesn't fit
;; goes back as an Object, and the proxy's own cast throws, as on the JVM.
;; A method with no fn runs its own body on the proxy when it has one (a
;; default method, Object's equals, hashCode, toString), else throws
;; UnsupportedOperationException, as Clojure's proxy does.

(define proxy-method-infos (make-weak-eq-hashtable))
;; #(name param-descs return-desc declaring-class abstract?) of a Method
(define (proxy-method-info m)
  (or (eq-hashtable-ref proxy-method-infos m #f)
      (let* ([slot (lambda (n) (jslot m (jfield-index "java/lang/reflect/Method" n)))]
             [info (vector (jstring->scheme (slot "name"))
                           (map mirror->desc (vector->list (jarr-vec (slot "parameterTypes"))))
                           (mirror->desc (slot "returnType"))
                           (jclass-cname (mirror-class (slot "clazz")))
                           (fxpositive? (fxlogand (slot "modifiers") #x400)))])
        (eq-hashtable-set! proxy-method-infos m info)
        info)))

(define (proxy-result d r)
  (let ([c (string-ref d 0)])
    (cond
      [(char=? c #\V) #f]
      [(char=? c #\Z) (jbox "Z" (if (jolt-true? r) 1 0))]
      [(memv c '(#\L #\[)) (convert-arg r d)]
      ;; an int in range or a char already gets the right box below
      [(case c
         [(#\J) (long-range? r)]
         [(#\S) (and (exact-int? r) (<= -32768 r 32767))]
         [(#\B) (and (exact-int? r) (<= -128 r 127))]
         [(#\D #\F) (and (number? r) (real? r))]
         [else #f])
       (jbox d (convert-arg r d))]
      [else (convert-arg r "Ljava/lang/Object;")])))

(define (proxy-handler lookup)
  (lambda (proxy m args)
    (let* ([info (proxy-method-info m)]
           [name (vector-ref info 0)] [pds (vector-ref info 1)] [rd (vector-ref info 2)]
           [argv (if args (vector->list (jarr-vec args)) '())]
           [f (lookup name)])
      (cond
        [(procedure? f)
         (proxy-result rd (call-from-java f (map (lambda (d a) (dyn-out d (if (memv (string-ref d 0) '(#\L #\[)) a (junbox d a))))
                                                 pds argv)))]
        [(vector-ref info 4) (jthrow "java/lang/UnsupportedOperationException" name)]
        [else
         (let ([r (apply (method-proc (vector-ref info 3)
                                      (string-append name "(" (apply string-append pds) ")" rd))
                         proxy
                         (map (lambda (d a) (if (memv (string-ref d 0) '(#\L #\[)) a (junbox d a))) pds argv))])
           (case (string-ref rd 0)
             [(#\V) #f]
             [(#\L #\[) r]
             [else (jbox rd r)]))]))))

(define (dyn-proxy ifaces lookup)
  (with-java-errors (lambda () (make-proxy ifaces lookup))))
(define (make-proxy ifaces lookup)
  (let ([h (jlambda (lambda-class-hook "java/lang/reflect/InvocationHandler"
                                       "invoke(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;")
                    (proxy-handler lookup))])
    (jinit "java/lang/ClassLoader")
    (jinit "java/lang/reflect/Proxy")
    ((method-proc "java/lang/reflect/Proxy"
                  "newProxyInstance(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;")
     ((method-proc "java/lang/ClassLoader" "getSystemClassLoader()Ljava/lang/ClassLoader;"))
     (make-jarr "Ljava/lang/Class;" (list->vector (map (lambda (n) (jmirror-of (slashes n))) ifaces)))
     h)))

;; --- classes defined from jolt ---------------------------------------------------------
;; A real class (define-class in the api): a class file made here, with a
;; public constructor for each public or protected one of its superclass and
;; a public native method for each method a jolt fn implements, defined
;; through the application loader as ClassLoader.defineClass would. Each
;; native calls its fn with the object and the arguments converted as
;; invoke's results are, through call-from-java, and checks what the fn
;; returns against the method's return type. The fn sits in a box, so the
;; same definition again (the REPL) swaps fns without a new class. The
;; class is final, so the superclass invoke-super calls is always the one
;; it was defined with.

;; class file bytes, as a list of octets
(define (u2 n) (list (fxsrl (fxlogand n #xffff) 8) (fxlogand n #xff)))
(define (u4 n) (append (u2 (fxsrl n 16)) (u2 (fxlogand n #xffff))))
(define (modified-utf8 s)
  (let loop ([cs (string->list s)] [acc '()])
    (if (null? cs)
        (reverse acc)
        (let ([c (char->integer (car cs))])
          (cond
            [(and (fx>= c 1) (fx<= c #x7f)) (loop (cdr cs) (cons c acc))]
            [(fx<= c #x7ff)   ; NUL too, as two octets
             (loop (cdr cs) (cons* (fxior #x80 (fxlogand c #x3f)) (fxior #xc0 (fxsrl c 6)) acc))]
            [(fx<= c #xffff)
             (loop (cdr cs) (cons* (fxior #x80 (fxlogand c #x3f)) (fxior #x80 (fxlogand (fxsrl c 6) #x3f))
                                   (fxior #xe0 (fxsrl c 12)) acc))]
            [else   ; beyond the BMP: its UTF-16 surrogates, each as three octets
             (let* ([v (fx- c #x10000)]
                    [three (lambda (u acc) (cons* (fxior #x80 (fxlogand u #x3f)) (fxior #x80 (fxlogand (fxsrl u 6) #x3f))
                                                  (fxior #xe0 (fxsrl u 12)) acc))])
               (loop (cdr cs) (three (fxior #xdc00 (fxlogand v #x3ff)) (three (fxior #xd800 (fxsrl v 10)) acc))))])))))
;; the slots a descriptor's arguments take, and the load opcode of each
(define (arg-loads desc)
  (let loop ([ds (desc-args desc)] [slot 1] [acc '()])
    (if (null? ds)
        (values (reverse acc) slot)
        (let* ([c (string-ref (car ds) 0)]
               [op (case c [(#\J) #x16] [(#\F) #x17] [(#\D) #x18] [(#\L #\[) #x19] [else #x15])])
          (loop (cdr ds) (fx+ slot (if (memv c '(#\J #\D)) 2 1)) (cons (list op slot) acc))))))
;; name, super and interfaces slashed; fields ((name desc flags anns) ...);
;; ctors ((desc . flags) ...), the superclass's; methods ((name desc flags
;; anns) ...); class-anns and each anns a list of annotations, each
;; (type-desc (element . value) ...), a value an element_value (JVMS
;; 4.7.16.1) as a list: (tag n) for a primitive (boolean as 0 or 1),
;; ("s" string), ("e" enum-desc constant), ("c" class-desc), ("@"
;; annotation), ("[" value ...); ctor-anns ((desc . anns) ...)
(define (class-file-bytes name super ifaces fields ctors methods class-anns ctor-anns)
  (let ([pool '()] [count 0] [index (make-hashtable equal-hash equal?)])
    (define (entry key octets . wide)   ; a long or double takes two slots
      (or (hashtable-ref index key #f)
          (let ([i (fx+ count 1)])
            (set! pool (cons octets pool))
            (set! count (fx+ count (if (null? wide) 1 2)))
            (hashtable-set! index key i)
            i)))
    (define (utf8 s) (let ([b (modified-utf8 s)]) (entry (list 'utf8 s) (append '(1) (u2 (length b)) b))))
    (define (class n) (let ([u (utf8 n)]) (entry (list 'class n) (cons 7 (u2 u)))))
    (define (nat n d) (let ([a (utf8 n)] [b (utf8 d)]) (entry (list 'nat n d) (append '(12) (u2 a) (u2 b)))))
    (define (methodref c n d) (let ([a (class c)] [b (nat n d)]) (entry (list 'mref c n d) (append '(10) (u2 a) (u2 b)))))
    ;; the integer n as k big-endian octets, two's complement
    (define (octets n k)
      (let loop ([i 0] [n n] [acc '()])
        (if (fx= i k) acc (loop (fx+ i 1) (bitwise-arithmetic-shift-right n 8) (cons (bitwise-and n 255) acc)))))
    (define (ieee x single?)
      (let ([bv (make-bytevector (if single? 4 8))])
        (if single?
            (bytevector-ieee-single-set! bv 0 x (endianness big))
            (bytevector-ieee-double-set! bv 0 x (endianness big)))
        (bytevector->u8-list bv)))
    (define (element v)
      (let ([tag (string-ref (car v) 0)] [x (cdr v)])
        (cons (char->integer tag)
              (case tag
                [(#\B #\C #\I #\S #\Z) (u2 (entry (list 'int (car x)) (cons 3 (octets (car x) 4))))]
                [(#\J) (u2 (entry (list 'long (car x)) (cons 5 (octets (car x) 8)) #t))]
                [(#\F) (let ([o (ieee (inexact (car x)) #t)]) (u2 (entry (cons 'float o) (cons 4 o))))]
                [(#\D) (let ([o (ieee (inexact (car x)) #f)]) (u2 (entry (cons 'double o) (cons 6 o) #t)))]
                [(#\s #\c) (u2 (utf8 (car x)))]
                [(#\e) (append (u2 (utf8 (car x))) (u2 (utf8 (cadr x))))]
                [(#\@) (annotation (car x))]
                [else (append (u2 (length x)) (apply append (map element x)))]))))
    (define (annotation a)
      (append (u2 (utf8 (car a))) (u2 (length (cdr a)))
              (apply append (map (lambda (p) (append (u2 (utf8 (car p))) (element (cdr p)))) (cdr a)))))
    ;; a RuntimeVisibleAnnotations attribute, as a list of one, or none
    (define (annotations-attr anns)
      (if (null? anns)
          '()
          (let ([body (append (u2 (length anns)) (apply append (map annotation anns)))])
            (list (append (u2 (utf8 "RuntimeVisibleAnnotations")) (u4 (length body)) body)))))
    (define (member-bytes flags n d attrs)
      (append (u2 flags) (u2 (utf8 n)) (u2 (utf8 d)) (u2 (length attrs)) (apply append attrs)))
    (let* ([this (class name)]
           [sup (class super)]
           [iis (map class ifaces)]
           [code (utf8 "Code")]
           [fos (apply append (map (lambda (f) (member-bytes (caddr f) (car f) (cadr f) (annotations-attr (cadddr f)))) fields))]
           ;; aload_0, each argument, invokespecial the superclass's, return
           [cos (apply append
                       (map (lambda (c)
                              (let-values ([(loads slots) (arg-loads (car c))])
                                (let ([bc (append '(#x2a)
                                                  (apply append (map (lambda (l) (list (car l) (cadr l))) loads))
                                                  '(#xb7) (u2 (methodref super "<init>" (car c))) '(#xb1))])
                                  ;; public, and varargs when the superclass's is
                                  (member-bytes (fxior 1 (fxlogand (cdr c) #x80)) "<init>" (car c)
                                                (cons (append (u2 code) (u4 (fx+ 12 (length bc)))
                                                              (u2 slots) (u2 slots) (u4 (length bc)) bc (u2 0) (u2 0))
                                                      (annotations-attr (cond [(assoc (car c) ctor-anns) => cdr] [else '()])))))))
                            ctors))]
           [mos (apply append (map (lambda (m) (member-bytes (caddr m) (car m) (cadr m) (annotations-attr (cadddr m)))) methods))]
           [cattrs (annotations-attr class-anns)])
      (append '(#xca #xfe #xba #xbe) (u2 0) (u2 52)
              (u2 (fx+ count 1)) (apply append (reverse pool))
              (u2 #x0031) (u2 this) (u2 sup)   ; public final super
              (u2 (length iis)) (apply append (map u2 iis))
              (u2 (length fields)) fos
              (u2 (fx+ (length ctors) (length methods))) cos mos
              (u2 (length cattrs)) (apply append cattrs)))))

(define (dotted n) (slashes->dots n))
(define (class-flag? c bit) (fxpositive? (fxlogand (jclass-flags c) bit)))

;; the instance methods a class extending super and implementing ifaces
;; can override, as (name desc flags): the first declaration of each
;; signature, the superclasses' before the interfaces', public or protected,
;; neither final nor a bridge. A final one is (name desc #f), so the api
;; can say so when it is named.
(define (overridable-methods super ifaces)
  (let ([seen (make-hashtable string-hash string=?)] [acc '()])
    (define (visit c)
      (for-each (lambda (m)
                  (let ([n (car m)] [d (cadr m)] [f (caddr m)])
                    (unless (or (char=? (string-ref n 0) #\<) (static-flag? f) (fxpositive? (fxlogand f 2))
                                (hashtable-ref seen (string-append n d) #f))
                      (hashtable-set! seen (string-append n d) #t)
                      (when (and (fxpositive? (fxlogand f 5)) (fxzero? (fxlogand f #x1040)))
                        (set! acc (cons (list n d (if (fxpositive? (fxlogand f #x10)) #f f)) acc))))))
                (meta-methods c)))
    (let chain ([n super] [is '()])
      (if n
          (let ([c (find-class n)]) (visit c) (chain (jclass-super c) (append is (jclass-interfaces c))))
          (let walk ([todo (append ifaces is)] [done '()])
            (cond [(null? todo) (reverse acc)]
                  [(member (car todo) done) (walk (cdr todo) done)]
                  [else (let ([c (find-class (car todo))])
                          (visit c)
                          (walk (append (cdr todo) (jclass-interfaces c)) (cons (car todo) done)))]))))))

(define (prim-type-name c)
  (case c [(#\I) "int"] [(#\J) "long"] [(#\S) "short"] [(#\B) "byte"] [(#\C) "char"]
        [(#\D) "double"] [(#\F) "float"] [else "boolean"]))
(define (value-kind v)
  (cond [(eq? v jolt-nil) "nil"] [(string? v) "a string"] [(char? v) "a char"] [(boolean? v) "a boolean"]
        [(java-value? v) (string-append "a " (dotted (class-name-of v)))]
        [(number? v) (string-append "the number " (number->string v))]
        [(procedure? v) "a fn"]
        [else "a jolt value"]))
;; jolt data (a map, vector, set or seq) as the api tags arguments, so a
;; fn can return one for a reference type as invoke takes it; the hook
;; (api ->arg) runs only for a value Scheme can't read itself
(define jolt-data-hook #f)
(define (set-jolt-data-hook! f) (set! jolt-data-hook f))
(define (jolt-data r)
  (if (or (eq? r jolt-nil) (java-value? r) (string? r) (number? r) (char? r) (boolean? r) (procedure? r)
          (seq-arg? r) (coll-arg? r) (not jolt-data-hook))
      r
      (jolt-data-hook r)))
;; what a fn returned, as the method's return type d takes it. nil for a
;; primitive is a NullPointerException, a value of the wrong kind a
;; ClassCastException and a number out of the type's range an
;; IllegalArgumentException, as Clojure's casts throw.
(define (defined-result where d r)
  (let ([c (string-ref d 0)])
    (define (wrong-ref v t)
      (jthrow "java/lang/ClassCastException" (string-append where " returned " (value-kind v) ", not " (dotted t))))
    (define (wrong) (jthrow "java/lang/ClassCastException"
                            (string-append where " returned " (value-kind r) ", not " (prim-type-name c))))
    (define (range lo hi)
      (cond [(char? r) (let ([n (char->integer r)]) (if (<= lo n hi) n (wrong)))]
            [(not (exact-int? r)) (wrong)]
            [(<= lo r hi) r]
            [else (jthrow "java/lang/IllegalArgumentException"
                          (string-append where " returned " (number->string r) ", out of range for " (prim-type-name c)))]))
    (cond
      [(char=? c #\V) (void)]
      [(char=? c #\Z) (if (jolt-true? r) 1 0)]
      [(memv c '(#\L #\[))
       (let* ([r (jolt-data r)]
              [t (ref-class d)]
              [box (assoc t boxes)]
              [v (cond
                   [(eq? r jolt-nil) #f]
                   ;; a vector for an array: each element as the component
                   ;; type takes a result, so a string can't land in an int[]
                   [(and (char=? c #\[) (seq-arg? r))
                    (let ([e (array-elem d)])
                      (make-jarr e (list->vector (map (lambda (x) (defined-result where e x)) r))))]
                   ;; a number or char for a box: boxed for its declared type
                   [(and box (not (java-value? r)) (not (string=? (cdr box) "Z")))
                    (jbox (cdr box) (defined-result where (cdr box) r))]
                   ;; a fn only for an interface with one abstract method
                   [(and (procedure? r) (not (sam-of t))) (wrong-ref r t)]
                   [else (convert-arg r d)])])
         (if (or (not v) (jinstance? v t)) v (wrong-ref v t)))]
      [(eq? r jolt-nil) (jthrow "java/lang/NullPointerException" (string-append where " returned nil, not " (prim-type-name c)))]
      [else
       (case c
         [(#\I) (range -2147483648 2147483647)]
         [(#\J) (range -9223372036854775808 9223372036854775807)]
         [(#\S) (range -32768 32767)]
         [(#\B) (range -128 127)]
         [(#\C) (if (char? r) (char->integer r) (range 0 65535))]
         [(#\D) (if (and (number? r) (real? r)) (inexact r) (wrong))]
         [else (if (and (number? r) (real? r)) (f32 (inexact r)) (wrong))])])))

(define (defined-method-proc where desc cell)
  (let ([pds (desc-args desc)] [rd (desc-ret desc)])
    (lambda (this . args)
      (defined-result where rd (call-from-java (unbox cell) (cons this (map dyn-out pds args)))))))
;; a static method's: the fn gets the arguments alone
(define (defined-static-proc where desc cell)
  (let ([pds (desc-args desc)] [rd (desc-ret desc)])
    (lambda args
      (defined-result where rd (call-from-java (unbox cell) (map dyn-out pds args))))))

;; defined class -> (super . interfaces), for invoke-super
(define defined-supers (make-hashtable string-hash string=?))

;; name, super, ifaces (dots or slashes); fields ((name . desc) ...);
;; fns ((key . cell) ...), a key being a method name, for every method of
;; that name it can override, or "name(desc)ret" for that one method, which
;; wins over its name's, and may be a method of the class's own; sfields
;; and sfns the same for static fields and methods, every sfns key a
;; "name(desc)ret"; init a cell holding the fn of its static initializer,
;; or #f; own ((desc . super-desc) ...), the class's own constructors, or #f
;; for one per superclass constructor, with cells for the :init fn (args ->
;; the superclass constructor's args) and the :post-init fn (this, args), or
;; #f; anns (class field method ctor): the class's annotations, then
;; ((field-name . anns) ...), ((key . anns) ...) for a key of fns or sfns,
;; on every method it implements, and ((desc . anns) ...) for constructors,
;; each annotation as class-file-bytes takes it. Answers the Class.
(define (dyn-define-class name super ifaces fields fns sfields sfns init own-ctors ctor-init post-init anns)
  (with-java-errors
   (lambda ()
     (define-jolt-class (slashes name) (slashes super) (map slashes ifaces) fields fns sfields sfns init
                        own-ctors ctor-init post-init anns))))

;; A constructor of the class's own: a native <init> (champaca calls it as
;; any constructor; HotSpot would want code) that hands its arguments to
;; :init, checks what comes back against the superclass constructor's
;; parameters, runs that constructor on the object, then :post-init.
(define (defined-ctor-proc where super desc sdesc ctor-init post-init)
  (let ([pds (desc-args desc)] [sds (desc-args sdesc)] [sproc #f])
    (lambda (this . args)
      (let* ([jargs (map dyn-out pds args)]
             [sargs (if ctor-init
                        (let ([r (jolt-data (call-from-java (unbox ctor-init) jargs))])
                          (cond [(and (seq-arg? r) (fx= (length r) (length sds))) r]
                                [else (jthrow "java/lang/IllegalArgumentException"
                                              (format "~a :init returned ~a, not a vector of ~a argument(s) for ~a.<init>~a"
                                                      where (value-kind r) (length sds) (dotted super) sdesc))]))
                        '())])
        (unless sproc (set! sproc (method-proc super (string-append "<init>" sdesc))))
        (apply sproc this (map (lambda (d a) (defined-result (string-append where " :init") d a)) sds sargs))
        (when post-init (call-from-java (unbox post-init) (cons this jargs)))
        (void)))))

(define (define-jolt-class name super ifaces fields fns sfields sfns init own-ctors ctor-init post-init anns)
  (define (fail . parts) (api-fail (apply string-append "define-class: " parts)))
  (define (anns-of key alist) (cond [(assoc key alist) => cdr] [else '()]))
  (define (bad-name? n)   ; not a JVM unqualified name
    (or (fxzero? (string-length n)) (exists (lambda (ch) (memv ch '(#\. #\; #\[ #\/ #\< #\>))) (string->list n))))
  (when (exists bad-name?
                (let split ([cs (string->list name)] [cur '()] [acc '()])   ; its package parts and simple name
                  (cond [(null? cs) (cons (list->string (reverse cur)) acc)]
                        [(char=? (car cs) #\/) (split (cdr cs) '() (cons (list->string (reverse cur)) acc))]
                        [else (split (cdr cs) (cons (car cs) cur) acc)])))
    (fail (dotted name) " isn't a class name"))
  (when (hashtable-ref classes name #f) (fail (dotted name) " is already a class"))
  (let loop ([fs (append fields sfields)] [seen '()])
    (unless (null? fs)
      (let ([n (caar fs)] [d (cdar fs)])
        (when (bad-name? n) (fail (format "~s isn't a field name" n)))
        (when (member n seen) (fail "field " n " is named twice"))
        (let ([t (let strip ([d d]) (if (char=? (string-ref d 0) #\[) (strip (array-elem d)) d))])
          (when (and (char=? (string-ref t 0) #\L) (not (string=? (ref-class t) name)) (not (class-exists? (ref-class t))))
            (fail "field " n "'s type " (dotted (ref-class t)) " isn't a class (a nested class is Outer$Inner)")))
        (loop (cdr fs) (cons n seen)))))
  (let loop ([is ifaces])
    (when (pair? is)
      (when (member (car is) (cdr is)) (fail (dotted (car is)) " is in :implements twice"))
      (loop (cdr is))))
  (when (jdk-package? name) (fail (dotted name) " is in a package of the JDK's"))
  (let ([s (find-class super)])
    (when (eq? (jclass-kind s) 'interface) (fail (dotted super) " is an interface; name it in :implements"))
    (when (class-flag? s #x10) (fail (dotted super) " is final"))
    (for-each (lambda (i) (unless (eq? (jclass-kind (find-class i)) 'interface) (fail (dotted i) " is not an interface")))
              ifaces)
    (let* ([ms (overridable-methods super ifaces)]
           [ctors (filter (lambda (c) (fxpositive? (fxlogand (cdr c) 5)))
                          (map (lambda (m) (cons (cadr m) (caddr m)))
                               (filter (lambda (m) (string=? (car m) "<init>")) (meta-methods s))))]
           [exact (filter (lambda (f) (memv #\( (string->list (car f)))) fns)]
           ;; (name desc cell anns) for every instance native (not the
           ;; natives table)
           [own
            (append
             (map (lambda (f)
                    (let* ([k (car f)] [p (string-split-paren k)] [n (car p)] [d (substring k (string-length n) (string-length k))])
                      (when (exists (lambda (m) (and (string=? (car m) n) (string=? (cadr m) d) (not (caddr m)))) ms)
                        (fail k " is final in " (dotted super)))
                      (list n d (cdr f) (anns-of k (caddr anns)))))
                  exact)
             (apply append
                    (map (lambda (f)
                           (let* ([n (car f)]
                                  [own (filter (lambda (m) (string=? (car m) n)) ms)]
                                  [open (filter caddr own)])
                             (when (null? open)
                               (fail (if (null? own) (string-append "no method " n " to implement in ")
                                         (string-append n " is final in "))
                                     (dotted super)
                                     (apply string-append (map (lambda (i) (string-append ", " (dotted i))) ifaces))))
                             (map (lambda (m) (list n (cadr m) (cdr f) (anns-of n (caddr anns))))
                                  (filter (lambda (m) (not (exists (lambda (e) (string=? (car e) (string-append n (cadr m)))) exact)))
                                          open))))
                         (filter (lambda (f) (not (memv #\( (string->list (car f))))) fns))))])
      (when (null? ctors) (fail (dotted super) " has no public or protected constructor"))
      (when own-ctors
        (when (null? own-ctors) (fail ":constructors names no constructor"))
        (for-each (lambda (c)
                    (unless (assoc (cdr c) ctors)
                      (fail (dotted super) " has no public or protected constructor " (cdr c)))
                    (when (and (not ctor-init) (not (string=? (cdr c) "()V")))
                      (fail "the constructor " (car c) " passes arguments to " (dotted super) ".<init>" (cdr c)
                            ", so it needs :init")))
                  own-ctors))
      (for-each (lambda (c)
                  (unless (assoc (car c) (or own-ctors ctors))
                    (fail ":constructor-annotations names the constructor " (car c) ", which the class doesn't have")))
                (cadddr anns))
      ;; (name desc cell anns) for every static native; a static method can't
      ;; share a signature with an instance method, inherited or the class's
      (let ([statics
             (map (lambda (f)
                    (let* ([k (car f)] [n (car (string-split-paren k))] [d (substring k (string-length n) (string-length k))])
                      (when (or (exists (lambda (m) (and (string=? (car m) n) (string=? (cadr m) d))) own)
                                (exists (lambda (m) (and (string=? (car m) n) (string=? (cadr m) d))) ms))
                        (fail "static " k " has an instance method's name and descriptor"))
                      (list n d (cdr f) (anns-of k (caddr anns)))))
                  sfns)]
            ;; the static initializer: a static native <clinit>, which jinit
            ;; calls on the class's first active use as any class's, so a
            ;; failure is an ExceptionInInitializerError, then
            ;; NoClassDefFoundError (a class file only champaca loads: HotSpot
            ;; wants code in a <clinit>)
            [natives-ctors
             (map (lambda (c)
                    (list "<init>" (car c)
                          (defined-ctor-proc (string-append (dotted name) ".<init>" (car c)) super (car c) (cdr c)
                                             ctor-init post-init)))
                  (or own-ctors '()))]
            [clinit (if init
                        (list (list "<clinit>" "()V"
                                    (lambda () (call-from-java (unbox init) '()) (void))))
                        '())])
        (for-each (lambda (m) (define-native! (string-append name "." (car m) (cadr m)) (caddr m)))
                  (append clinit natives-ctors))
        (for-each (lambda (m)
                    (define-native! (string-append name "." (car m) (cadr m))
                      (defined-method-proc (string-append (dotted name) "." (car m) (cadr m)) (cadr m) (caddr m))))
                  own)
        (for-each (lambda (m)
                    (define-native! (string-append name "." (car m) (cadr m))
                      (defined-static-proc (string-append (dotted name) "." (car m) (cadr m)) (cadr m) (caddr m))))
                  statics)
        (let ([bytes (list->vector (class-file-bytes name super ifaces
                                                     (append (map (lambda (f) (list (car f) (cdr f) 1 (anns-of (car f) (cadr anns)))) fields)
                                                             (map (lambda (f) (list (car f) (cdr f) 9 (anns-of (car f) (cadr anns)))) sfields))
                                                     (if own-ctors '() ctors)
                                                     (append (map (lambda (m) (list (car m) (cadr m) #x0101 (cadddr m))) own)
                                                             (map (lambda (m) (list (car m) (cadr m) #x0109 (cadddr m))) statics)
                                                             (map (lambda (m) (list (car m) (cadr m) #x0108 '())) clinit)
                                                             (map (lambda (m) (list (car m) (cadr m) #x0101 (anns-of (cadr m) (cadddr anns))))
                                                                  natives-ctors))
                                                     (car anns) (cadddr anns)))])
          ;; the natives must be there when the class registers; a definition
          ;; that fails takes them back, so a later class of the name can't
          ;; pick them up
          (let ([m (guard (e [#t (for-each (lambda (m) (hashtable-delete! natives (string-append name "." (car m) (cadr m))))
                                           (append own statics clinit natives-ctors))
                                 (raise e)])
                     (define-class! (app-loader) (jstring name) bytes 0 (vector-length bytes) #f #f #f))])
            (hashtable-set! defined-supers name (cons super ifaces))
            m))))))

;; obj's superclass's method name (public or protected) that fits args,
;; called on obj without virtual dispatch, as super.name(args) in Java; an
;; interface's default among the class's own interfaces when the superclass
;; has none
(define (dyn-invoke-super obj name . args)
  (let* ([cname (class-name-of (nn obj))]
         [sup (or (hashtable-ref defined-supers cname #f)
                  (api-fail (string-append "invoke-super: " (dotted cname) " isn't a class define-class made")))]
         [ok? (lambda (f) (fxpositive? (fxlogand f 5)))])
    (let-values ([(site args)
                  (site-for 'super cname name args
                            (lambda ()
                              (choose "invoke-super" (car sup) name
                                      (candidates-from sup name #f (lambda (np f) (fx= np (length args))) ok?)
                                      (candidates-from sup name #f (lambda (np f) (and (fxpositive? (fxlogand f #x80)) (fx<= (fx- np 1) (length args)))) ok?)
                                      args))
                            (lambda (owner mkey) #f))])
      (with-java-errors
       (lambda ()
         (let* ([pick (call-site-pick site)]
                [k (string->symbol (call-site-mkey site))]
                [sym (eq-hashtable-ref (jclass-vtable (find-class (car sup))) k #f)]
                [proc (cond [sym (top-level-value sym)]
                            [(fxpositive? (fxlogand (cadddr pick) #x400))
                             (jthrow "java/lang/AbstractMethodError"
                                     (string-append "Method " (car pick) "." (call-site-mkey site) " is abstract"))]
                            [else (method-proc (car pick) (call-site-mkey site))])])
           (dyn-out (call-site-ret-d site)
                    (apply proc obj (map convert-arg args (call-site-arg-ds site))))))))))
