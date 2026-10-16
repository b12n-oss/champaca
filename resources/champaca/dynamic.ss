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
               (let ([r (apply f (map dyn-out arg-ds args))])
                 (if (char=? (string-ref ret-d 0) #\V) (void) (convert-arg r ret-d)))))))

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
  (let loop ([todo (list cname)] [seen '()] [acc '()])
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
                                  (public? (caddr m)) ; jolt calls from outside the class
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

(define (dyn-get-static cname fname)
  (let* ([cname (slashes cname)] [c (find-class cname)]
         [f (find (lambda (f) (and (string=? (car f) fname) (static-flag? (caddr f)))) (meta-fields c))])
    (unless f (api-fail (string-append "no static field " fname " in " cname)))
    (with-java-errors (lambda () (jinit cname)))
    (dyn-out (cadr f) (top-level-value (string->symbol (string-append cname ":" fname))))))

(define (dyn-get-field obj fname)
  (let* ([cname (class-name-of (nn obj))]
         [desc (let loop ([n cname])
                 (let* ([c (find-class n)]
                        [f (find (lambda (f) (and (string=? (car f) fname) (not (static-flag? (caddr f))))) (meta-fields c))])
                   (cond [f (cadr f)] [(jclass-super c) (loop (jclass-super c))]
                         [else (api-fail (string-append "no field " fname " in " cname))])))])
    (dyn-out desc (vector-ref (jobj-slots obj) (jfield-index cname fname)))))

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
