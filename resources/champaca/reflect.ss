;; Core reflection: the VM half of java.lang.Class's member queries and of
;; Method.invoke / Constructor.newInstance.
;;
;; The JDK picks its "native accessor" (invoke0, newInstance0 below) when
;; java.lang.invoke is not initialized, which it never is here, or when
;; jdk.reflect.useNativeAccessorOnly=true (set in vmProperties too). Field
;; access has no native path in JDK 25, only method handles, so the Field
;; get/set methods are overridden outright.
;;
;; A reflective object's slot is its member's index in the class meta list.
;; Access control is not enforced on field access (spike).

(define (meta-methods c) (car (jclass-meta c)))
(define (meta-fields c) (cadr (jclass-meta c)))
;; the class's own (signature annotations); pseudo-classes have none
(define (meta-class-refl c)
  (let ([m (jclass-meta c)]) (if (pair? (cddr m)) (caddr m) '(#f #f))))

;; Reflection attributes as the JDK's constructors take them: a Signature as a
;; String, raw annotation bytes as a fresh byte[] (the bytes are stored
;; unsigned), #f for none. Member entries carry them after (name desc flags).
(define (refl-string x) (and x (jstring-lit x)))
(define (refl-bytes bv)
  (and bv (let* ([n (bytevector-length bv)] [v (make-vector n)])
            (do ([i 0 (fx+ i 1)]) ((fx= i n) (make-jarr "B" v))
              (vector-set! v i (bytevector-s8-ref bv i))))))
(define (member-attr m i) (and (fx> (length m) i) (list-ref m i)))

(define (desc->mirror d) (jmirror-of (desc->class-name d)))
(define (mirror-array ds)
  (make-jarr "Ljava/lang/Class;" (list->vector (map desc->mirror ds))))
(define no-classes (lambda () (make-jarr "Ljava/lang/Class;" (vector))))

(define (public? flags) (fxpositive? (fxlogand flags 1)))
(define (static-flag? flags) (fxpositive? (fxlogand flags 8)))

;; indexed members of class c that satisfy keep?
(define (indexed-members lst keep?)
  (let loop ([ms lst] [i 0] [acc '()])
    (if (null? ms) (reverse acc)
        (loop (cdr ms) (fx+ i 1) (if (keep? (car ms)) (cons (cons i (car ms)) acc) acc)))))

(define (construct cname desc . args)
  ;; as bytecode's new does: the class is initialized before its instance
  (jinit cname)
  (let ([o (jnew cname)])
    (apply (method-proc cname (string-append "<init>" desc)) o args)
    o))

;; a method entry's Exceptions, class names, as the Class[] Method and
;; Constructor take
(define (exception-mirrors m)
  (make-jarr "Ljava/lang/Class;" (list->vector (map jmirror-of (or (member-attr m 8) '())))))

;; the Method for (index . entry) of the class whose mirror is mirror
(define (method-object mirror im)
  (let* ([m (cdr im)] [name (car m)] [desc (cadr m)] [flags (caddr m)])
    (construct "java/lang/reflect/Method"
               "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;IILjava/lang/String;[B[B[B)V"
               mirror (jstring-lit name) (mirror-array (desc-args desc)) (desc->mirror (desc-ret desc))
               (exception-mirrors m) (fxlogand flags #xffff) (car im)
               (refl-string (member-attr m 3)) (refl-bytes (member-attr m 4))
               (refl-bytes (member-attr m 5)) (refl-bytes (member-attr m 6)))))

;; Run thunk resolving names through the defining loader of the class whose
;; mirror is mirror, as the JVM resolves a member's types through its
;; declaring class's loader: a field typed by a class only that loader sees
;; (Quarkus's generated classes, under its RunnerClassLoader) is found.
(define (with-loader-of mirror thunk)
  (parameterize ([resolving-loader (hashtable-ref class-loaders (jclass-cname (mirror-class mirror)) #f)])
    (thunk)))

(defnative "java/lang/Class.getDeclaredMethods0(Z)[Ljava/lang/reflect/Method;" (this public-only)
  (with-loader-of this (lambda ()
    (let ([c (mirror-class this)])
      (make-jarr "Ljava/lang/reflect/Method;"
       (list->vector
        (map (lambda (im) (method-object this im))
             (indexed-members (meta-methods c)
                              (lambda (m) (and (not (char=? (string-ref (car m) 0) #\<))
                                               (or (eqv? public-only 0) (public? (caddr m)))))))))))))

(defnative "java/lang/Class.getDeclaredConstructors0(Z)[Ljava/lang/reflect/Constructor;" (this public-only)
  (with-loader-of this (lambda ()
    (let ([c (mirror-class this)])
      (make-jarr "Ljava/lang/reflect/Constructor;"
       (list->vector
        (map (lambda (im)
               (let* ([m (cdr im)] [desc (cadr m)] [flags (caddr m)])
                 (construct "java/lang/reflect/Constructor"
                            "(Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Class;IILjava/lang/String;[B[B)V"
                            ;; never native: only a class define-class made has a
                            ;; native <init>, which Java sees as an ordinary one
                            this (mirror-array (desc-args desc)) (exception-mirrors m) (fxlogand flags #xfeff) (car im)
                            (refl-string (member-attr m 3)) (refl-bytes (member-attr m 4)) (refl-bytes (member-attr m 5)))))
             (indexed-members (meta-methods c)
                              (lambda (m) (and (string=? (car m) "<init>")
                                               (or (eqv? public-only 0) (public? (caddr m)))))))))))))

(defnative "java/lang/Class.getDeclaredFields0(Z)[Ljava/lang/reflect/Field;" (this public-only)
  (with-loader-of this (lambda ()
    (let ([c (mirror-class this)])
      (make-jarr "Ljava/lang/reflect/Field;"
       (list->vector
        (map (lambda (im)
               (let* ([f (cdr im)] [flags (caddr f)])
                 (construct "java/lang/reflect/Field"
                            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;IZILjava/lang/String;[B)V"
                            this (jstring-lit (car f)) (desc->mirror (cadr f)) (fxlogand flags #xffff)
                            (if (and (static-flag? flags) (fxpositive? (fxlogand flags #x10))) 1 0)
                            (car im) (refl-string (member-attr f 3)) (refl-bytes (member-attr f 4)))))
             (indexed-members (meta-fields c)
                              (lambda (f) (or (eqv? public-only 0) (public? (caddr f))))))))))))

(defnative "java/lang/Class.getInterfaces0()[Ljava/lang/Class;" (this)
  (make-jarr "Ljava/lang/Class;" (list->vector (map jmirror-of (jclass-interfaces (mirror-class this))))))
;; nesting, from the class's meta: inner is (outer simple-name flags) and
;; enclosing is (class name desc), either #f
(define (class-inner c) (let ([r (meta-class-refl c)]) (and (fx> (length r) 2) (caddr r))))
(define (class-enclosing c) (let ([r (meta-class-refl c)]) (and (fx> (length r) 3) (cadddr r))))
(defnative "java/lang/Class.getDeclaringClass0()Ljava/lang/Class;" (this)
  (with-loader-of this (lambda ()
    (let ([inner (class-inner (mirror-class this))])
      (and inner (car inner) (jmirror-of (car inner)))))))
(defnative "java/lang/Class.getSimpleBinaryName0()Ljava/lang/String;" (this)
  (let ([inner (class-inner (mirror-class this))])
    (and inner (cadr inner) (jstring-lit (cadr inner)))))
(defnative "java/lang/Class.getEnclosingMethod0()[Ljava/lang/Object;" (this)
  (with-loader-of this (lambda ()
    (let ([e (class-enclosing (mirror-class this))])
      (and e (make-jarr "Ljava/lang/Object;"
                        (vector (jmirror-of (car e)) (refl-string (cadr e)) (refl-string (caddr e)))))))))
(defnative "java/lang/Class.getGenericSignature0()Ljava/lang/String;" (this)
  (refl-string (car (meta-class-refl (mirror-class this)))))
(defnative "java/lang/Class.getRawAnnotations()[B" (this)
  (refl-bytes (cadr (meta-class-refl (mirror-class this)))))
(defnative "java/lang/Class.getRawTypeAnnotations()[B" (this)
  (let ([r (meta-class-refl (mirror-class this))])
    (and (fx> (length r) 5) (refl-bytes (list-ref r 5)))))
;; Record: (name desc signature annotations type-annotations) per component,
;; #f for a class that isn't a record
(define (class-record c)
  (let ([r (meta-class-refl c)]) (and (fx> (length r) 4) (list-ref r 4))))
;; as the JVM's: each component with its accessor, the declared method named
;; after it taking nothing and returning its type
(defnative "java/lang/Class.getRecordComponents0()[Ljava/lang/reflect/RecordComponent;" (this)
  (with-loader-of this (lambda ()
    (let* ([c (mirror-class this)]
           [rc-class "java/lang/reflect/RecordComponent"]
           [set (lambda (o f v) (jslot-set! o (jfield-index rc-class f) v))])
      (jinit rc-class)
      (make-jarr "Ljava/lang/reflect/RecordComponent;"
       (list->vector
        (map (lambda (rc)
               (let* ([name (car rc)] [desc (cadr rc)] [getter (string-append "()" desc)]
                      [im (let loop ([ms (meta-methods c)] [i 0])
                            (cond [(null? ms) #f]
                                  [(and (string=? (caar ms) name) (string=? (cadar ms) getter)) (cons i (car ms))]
                                  [else (loop (cdr ms) (fx+ i 1))]))]
                      [o (jnew rc-class)])
                 (set o "clazz" this)
                 (set o "name" (jstring-lit name))
                 (set o "type" (desc->mirror desc))
                 (set o "accessor" (and im (method-object this im)))
                 (set o "signature" (refl-string (caddr rc)))
                 (set o "annotations" (refl-bytes (cadddr rc)))
                 (set o "typeAnnotations" (refl-bytes (list-ref rc 4)))
                 o))
             (or (class-record c) '()))))))))
;; The constant pool that AnnotationParser reads names and values from. Its oop
;; is the class mirror; entries come from the parsed classfile through a jolt
;; callback (set-cp-hook!), which answers the entry's value (a String's text,
;; a number) or a Class entry's name.
(define cp-hook #f)
(define (set-cp-hook! f) (set! cp-hook f))
(defnative "java/lang/Class.getConstantPool()Ljdk/internal/reflect/ConstantPool;" (this)
  (let ([cp (jnew "jdk/internal/reflect/ConstantPool")])
    (jslot-set! cp (jfield-index "jdk/internal/reflect/ConstantPool" "constantPoolOop") this)
    cp))
(define (cp-value oop i) (cp-hook (jclass-cname (mirror-class (nn oop))) i))
(defnative "jdk/internal/reflect/ConstantPool.getUTF8At0(Ljava/lang/Object;I)Ljava/lang/String;" (this oop i)
  (jstring (cp-value oop i)))
(defnative "jdk/internal/reflect/ConstantPool.getStringAt0(Ljava/lang/Object;I)Ljava/lang/String;" (this oop i)
  (jstring (cp-value oop i)))
(defnative "jdk/internal/reflect/ConstantPool.getIntAt0(Ljava/lang/Object;I)I" (this oop i) (cp-value oop i))
(defnative "jdk/internal/reflect/ConstantPool.getLongAt0(Ljava/lang/Object;I)J" (this oop i) (cp-value oop i))
(defnative "jdk/internal/reflect/ConstantPool.getFloatAt0(Ljava/lang/Object;I)F" (this oop i) (cp-value oop i))
(defnative "jdk/internal/reflect/ConstantPool.getDoubleAt0(Ljava/lang/Object;I)D" (this oop i) (cp-value oop i))
(defnative "jdk/internal/reflect/ConstantPool.getClassAt0(Ljava/lang/Object;I)Ljava/lang/Class;" (this oop i)
  (jmirror-of (cp-value oop i)))
(defnative "java/lang/Class.getDeclaredClasses0()[Ljava/lang/Class;" (this) (no-classes))
;; Nests (JVMS 5.4.4): a class's host is the class its NestHost attribute
;; names, once that class loads, is in the same runtime package and lists
;; this one in its NestMembers; any other class is its own host. A hidden
;; class defined as a nestmate (Lookup.defineHiddenClass with NESTMATE) has
;; its lookup class's host (dynamic-nest-hosts, from defineClass0).
;; Reflection.areNestMates (below) and Class.isNestmateOf compare hosts.
(define (class-nest-host-name c) (let ([r (meta-class-refl c)]) (and (fx> (length r) 6) (list-ref r 6))))
(define (class-nest-members c) (let ([r (meta-class-refl c)]) (or (and (fx> (length r) 7) (list-ref r 7)) '())))
;; a sealed class's PermittedSubclasses names, '() when it lists none, #f
;; for a class that isn't sealed
(define (class-permitted-subclasses c) (let ([r (meta-class-refl c)]) (and (fx> (length r) 8) (list-ref r 8))))
(define dynamic-nest-hosts (make-hashtable string-hash string=?))   ; hidden class name -> host jclass
(define (same-runtime-package? a b)
  (and (string=? (package-dir (jclass-cname a)) (package-dir (jclass-cname b)))
       (eq? (class-loader-of a) (class-loader-of b))))
;; a class's host is worked out once, as HotSpot keeps it once resolved
(define nest-hosts (make-weak-eq-hashtable))
(define (nest-host c)
  (or (hashtable-ref dynamic-nest-hosts (jclass-cname c) #f)
      (eq-hashtable-ref nest-hosts c #f)
      (let* ([hn (class-nest-host-name c)]
             [h (and hn (guard (e [#t #f]) (find-class hn)))]
             [host (if (and h (same-runtime-package? c h) (member (jclass-cname c) (class-nest-members h))) h c)])
        (eq-hashtable-set! nest-hosts c host)
        host)))
(defnative "java/lang/Class.getNestHost0()Ljava/lang/Class;" (this) (jmirror (nest-host (mirror-class this))))
;; the host, then each of its NestMembers that loads and has it as host, as
;; HotSpot leaves out a member that fails either way
(defnative "java/lang/Class.getNestMembers0()[Ljava/lang/Class;" (this)
  (let ([h (nest-host (mirror-class this))])
    (make-jarr "Ljava/lang/Class;"
               (list->vector
                (cons (jmirror h)
                      (fold-right (lambda (n acc)
                                    (let ([m (guard (e [#t #f]) (find-class n))])
                                      (if (and m (eq? (nest-host m) h)) (cons (jmirror m) acc) acc)))
                                  '() (class-nest-members h)))))))
;; Sealed classes: each name in PermittedSubclasses that resolves through the
;; class's own loader, in order, as HotSpot's JVM_GetPermittedSubclasses,
;; which leaves out a name that fails to load; Class.getPermittedSubclasses
;; then keeps the direct subtypes. null for a class that isn't sealed.
(defnative "java/lang/Class.getPermittedSubclasses0()[Ljava/lang/Class;" (this)
  (let* ([c (mirror-class this)]
         [names (and (memq (jclass-kind c) '(class interface)) (class-permitted-subclasses c))])
    (and names
         (make-jarr "Ljava/lang/Class;"
                    (list->vector
                     (parameterize ([resolving-loader (hashtable-ref class-loaders (jclass-cname c) #f)])
                       (fold-right (lambda (n acc)
                                     (let ([m (guard (e [(and (jobj? e) (jinstance? e "java/lang/VirtualMachineError")) (raise e)]
                                                        [#t #f])
                                                (jmirror-of n))])
                                       (if m (cons m acc) acc)))
                                   '() names)))))))
(defnative "java/lang/Class.isRecord0()Z" (this)
  (if (class-record (mirror-class this)) 1 0))
(defnative "java/lang/Class.getClassAccessFlagsRaw0()I" (this) (fxlogand (jclass-flags (mirror-class this)) #xffff))
(defnative "java/lang/Class.getClassFileVersion0()I" (this) 69)

;; --- Java frames ---------------------------------------------------------------------
;; Every translated method is a top-level define named "Class.name(desc)", an
;; inlined leaf callee a let-bound "Caller.m(desc)#I:Class.name(desc)", and a
;; block within either "...#Bpc" (a method with handlers runs its blocks
;; through a trampoline; a method without usually has them folded into its
;; own code object). A Java activation is a Chez frame carrying such a name,
;; so a walk of the continuation is the Java stack, and nothing is tracked on
;; calls. raw-frames takes the walk cheaply, a (code . return-offset) pair
;; per frame, which is what fillInStackTrace keeps; names and pcs are read
;; off the code objects only when a trace is asked for. The pc comes from
;; Chez's inspector information: the return point's recorded expression is
;; the pending call, whose operator codegen wraps as (begin '(jpc . pc) op),
;; so the pc of that call, hence its line, is right there. A try or
;; synchronized body (and a CHAMPACA_BT method body) runs in an unnamed thunk
;; under dynamic-wind, so its pending pc belongs to the next named frame
;; out. getCallerClass and the stack traces (natives.ss) both read this.

;; A continuation object covers a stack segment of several frames; splitting
;; the first frame off, as the inspector does, makes the link the next frame
;; rather than the next segment. About 12 ns a frame.
(define (raw-frames k)
  (let loop ([k k] [n 0] [acc '()])
    (if (or (eq? k #%$null-continuation) (not (#%$continuation? k)) (fx= n 8192))
        (reverse acc)
        (begin
          (#%$split-continuation k 0)
          (loop (#%$continuation-link k) (fx+ n 1)
                (cons (cons (#%$continuation-return-code k) (#%$continuation-return-offset k)) acc))))))

;; The return point at offset in code: its recorded expression, or #f. The
;; code-info and rp-info records are Chez's own (fields rpis; offset, sexpr),
;; read through record reflection, their accessors found once.
(define rp-accessors #f)
(define (record-field-accessor rtd name)
  (let loop ([i 0] [ns (vector->list (record-type-field-names rtd))])
    (cond [(null? ns) #f] [(eq? (car ns) name) (record-accessor rtd i)] [else (loop (fx+ i 1) (cdr ns))])))
(define (return-sexpr code offset)
  (let ([info (#%$code-info code)])
    (and (record? info)
         (begin
           (unless rp-accessors
             (let* ([rpis-of (record-field-accessor (record-rtd info) 'rpis)]
                    [rpis (and rpis-of (rpis-of info))]
                    [rp (and (vector? rpis) (fx> (vector-length rpis) 0) (vector-ref rpis 0))])
               (when (and rp (record? rp))
                 (set! rp-accessors (list rpis-of
                                          (record-field-accessor (record-rtd rp) 'offset)
                                          (record-field-accessor (record-rtd rp) 'sexpr))))))
           (and rp-accessors
                (let ([rpis ((car rp-accessors) info)] [offset-of (cadr rp-accessors)] [sexpr-of (caddr rp-accessors)])
                  (and (vector? rpis)
                       (let loop ([i 0])
                         (cond [(fx= i (vector-length rpis)) #f]
                               [(eqv? (offset-of (vector-ref rpis i)) offset) (sexpr-of (vector-ref rpis i))]
                               [else (loop (fx+ i 1))])))))))))

(define (string-find s ch start)
  (let loop ([i start])
    (cond [(fx>= i (string-length s)) #f] [(char=? (string-ref s i) ch) i] [else (loop (fx+ i 1))])))
(define (string-find-sub s sub)
  (let ([n (string-length s)] [m (string-length sub)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(let same ([j 0]) (or (fx= j m) (and (char=? (string-ref s (fx+ i j)) (string-ref sub j)) (same (fx+ j 1))))) i]
            [else (loop (fx+ i 1))]))))

;; a code name -> (cname mname desc block?), or #f for a frame that isn't a
;; Java method's; block? for a "#B", "#E" or "#T" procedure of the method
;; rather than its own lambda. An inlined leaf is "Caller#I:Callee@pc" (one
;; copy per call site), the callee's own activation. champaca's own classes
;; (lambda bodies) aren't frames the JVM has. Cached per name.
(define frame-methods (make-hashtable string-hash string=?))
(define (frame-method name)
  (let ([hit (hashtable-ref frame-methods name 'none)])
    (if (eq? hit 'none)
        (let ([m (parse-frame-method name)]) (hashtable-set! frame-methods name m) m)
        hit)))
(define (parse-frame-method name)
  (let* ([inl (string-find-sub name "#I:")]
         [name (if inl (substring name (fx+ inl 3) (string-length name)) name)]
         [paren (string-find name #\( 0)])
    (and paren
         (let ([dot (let back ([i (fx- paren 1)])
                      (cond [(fx< i 0) #f] [(char=? (string-ref name i) #\.) i] [else (back (fx- i 1))]))]
               [hash (string-find name #\# paren)]
               [at (string-find name #\@ paren)])
           (and dot
                (let ([cname (substring name 0 dot)]
                      [end (cond [(and hash at) (fxmin hash at)] [hash hash] [at at] [else (string-length name)])])
                  (and (not (and (fx>= (string-length cname) 9) (string=? (substring cname 0 9) "champaca/")))
                       (list cname (substring name (fx+ dot 1) paren)
                             (substring name paren end)
                             (and hash #t)))))))))

;; The marker in a pending call's recorded expression, in its operator or
;; one of its arguments: a pc, or (site-pc callee-tag . pc) inside an
;; inlined leaf (codegen's jpc), or #f.
(define (jpc-datum x)
  (and (pair? x) (eq? (car x) 'begin) (pair? (cdr x))
       (let ([q (cadr x)])
         (and (pair? q) (eq? (car q) 'quote) (pair? (cdr q))
              (let ([m (cadr q)])
                (and (pair? m) (eq? (car m) 'jpc)
                     (let ([d (cdr m)]) (and (or (fixnum? d) (pair? d)) d))))))))
(define (frame-marker v)
  (and (pair? v) (list? v)
       (let loop ([xs v])
         (cond [(null? xs) #f]
               [(jpc-datum (car xs)) => (lambda (d) d)]
               [else (loop (cdr xs))]))))

;; a marker for the frame of method m (cname mname desc ...): its own pc,
;; and the callee activation to put above it when the marker is an inlined
;; leaf's and this frame isn't the leaf's own (a copy Chez didn't fold keeps
;; a frame of its own, named for the callee)
(define (marker-pcs d m)
  (cond [(not d) (values -1 #f)]
        [(fixnum? d) (values d #f)]
        [else (let* ([spc (car d)] [tag (symbol->string (cadr d))] [cpc (cddr d)]
                     [callee (frame-method tag)])
                (if (and callee (string=? (car callee) (car m)) (string=? (cadr callee) (cadr m)) (string=? (caddr callee) (caddr m)))
                    (values cpc #f)
                    (values spc (and callee (list (car callee) (cadr callee) (caddr callee) cpc)))))]))

;; the Java stack of raw frames, innermost first: (cname mname desc pc) per
;; activation, pc -1 for a frame that recorded none (getOurStackTrace keeps
;; 1024 of them, as HotSpot does). A method with handlers runs its blocks through a
;; trampoline, so an activation is a block frame and then the method's own:
;; the two merge, the block's pc winning since its call is the pending one.
;; A block frame on its own (a block Chez didn't fold, tail-called) is an
;; activation by itself. An inlined leaf's marker adds the leaf's activation
;; above the frame it was folded into.
(define (java-frames-of frames)   ; frames from raw-frames
  (let loop ([fs frames] [pending #f] [acc '()] [n 0])
    (if (or (null? fs) (fx>= n 4096))
        (reverse (map (lambda (f) (list-head f 4)) acc))
        (let* ([code (caar fs)]
               [name (#%$code-name code)]
               [m (and (string? name) (frame-method name))]
               [d (frame-marker (return-sexpr code (cdar fs)))])
          (cond
            [(not m) (loop (cdr fs) (or pending d) acc n)]
            [(and (not (cadddr m)) (pair? acc)
                  (let ([last (car acc)])
                    (and (car (cddddr last))   ; a block frame of this same method
                         (string=? (car last) (car m)) (string=? (cadr last) (cadr m)) (string=? (caddr last) (caddr m)))))
             ;; the block's pc and any callee above it stay as they are
             (let ([last (car acc)])
               (if (fx>= (cadddr last) 0)
                   (loop (cdr fs) #f (cons (list (car m) (cadr m) (caddr m) (cadddr last) #f) (cdr acc)) n)
                   (let-values ([(pc callee) (marker-pcs (or d pending) m)])
                     (loop (cdr fs) #f
                           (cons (list (car m) (cadr m) (caddr m) pc #f)
                                 (if callee (cons (append callee (list #f)) (cdr acc)) (cdr acc)))
                           (if callee (fx+ n 1) n)))))]
            [else
             (let-values ([(pc callee) (marker-pcs (or d pending) m)])
               (loop (cdr fs) #f
                     (cons (list (car m) (cadr m) (caddr m) pc (cadddr m))
                           (if callee (cons (append callee (list #f)) acc) acc))
                     (fx+ n (if callee 2 1))))])))))

(define (java-frame-class name)
  ;; "pkg/Cls.meth(desc)#B4" -> "pkg/Cls"
  (let ([paren (let find ([i 0]) (cond [(fx= i (string-length name)) #f]
                                       [(char=? (string-ref name i) #\() i]
                                       [else (find (fx+ i 1))]))])
    (and paren
         (let back ([i (fx- paren 1)])
           (cond [(fx< i 0) #f]
                 [(char=? (string-ref name i) #\.) (substring name 0 i)]
                 [else (back (fx- i 1))])))))

(define (reflection-frame? cls)
  (or (string=? cls "java/lang/reflect/Method")
      (string=? cls "java/lang/reflect/Constructor")
      (let ([p "jdk/internal/reflect/"]) (and (fx>= (string-length cls) (string-length p))
                                              (string=? p (substring cls 0 (string-length p)))))))

;; Java frames for getCallerClass, innermost first, as (cname . "cname.name(desc)").
;; Under CHAMPACA_BT the shadow stack (jstack) is used, as before the walk
;; could see through its wrapper.
(define (java-frames)
  (if (pair? jstack)
      (map (lambda (n) (cons (java-frame-class n) n)) jstack)
      (map (lambda (f) (cons (car f) (string-append (car f) "." (cadr f) (caddr f))))
           (java-frames-of (call/1cc (lambda (k) (raw-frames k)))))))

;; The JVM answers the caller of the method that called getCallerClass,
;; skipping reflection's own frames.
(define caller-debug? (getenv "CHAMPACA_CALLER_DEBUG"))
(defnative "jdk/internal/reflect/Reflection.getCallerClass()Ljava/lang/Class;" ()
  (let ([fs (java-frames)])
    (when caller-debug?
      (put-string (current-error-port) (format "getCallerClass frames: ~s\n" (map cdr (list-head fs (min 5 (length fs)))))))
    (let loop ([fs (if (pair? fs) (cdr fs) fs)])
      (cond [(null? fs) #f]
            [(reflection-frame? (caar fs)) (loop (cdr fs))]
            [else (jmirror-of (caar fs))]))))
(defnative "jdk/internal/reflect/Reflection.getClassAccessFlags(Ljava/lang/Class;)I" (c)
  (fxlogand (jclass-flags (mirror-class c)) #xffff))

;; --- invocation ------------------------------------------------------------------

(define (prim-desc? d) (not (memv (string-ref d 0) '(#\L #\[))))

;; a class name as Class.getName gives it: dots, but a hidden class keeps
;; the slash before its suffix (Name/0x1000)
(define (binary-name n)
  (let ([i (string-search "/0x" n)])
    (if i
        (string-append (slashes->dots (substring n 0 i)) (substring n i (string-length n)))
        (slashes->dots n))))

(define (reflect-arg-list ptypes args . ctor?)
  (let ([argv (if args (vector->list (jarr-vec args)) '())])
    (unless (fx= (length argv) (length ptypes))
      (jthrow "java/lang/IllegalArgumentException"
              (string-append "wrong number of arguments: " (number->string (length argv))
                             " expected: " (number->string (length ptypes)))))
    (map (lambda (t v) (reflect-arg t v (pair? ctor?))) ptypes argv)))

;; an argument as the JVM's reflection takes it: for a primitive parameter a
;; box of that type or of one that widens to it (never narrows), unboxed;
;; for a reference parameter null or an instance. Anything else is
;; IllegalArgumentException "argument type mismatch". null for a primitive
;; is the IllegalArgumentException the JDK's method handle accessor throws,
;; wrapping the NullPointerException its unboxing hits. A constructor's
;; mismatch carries the ClassCastException the JDK's constructor accessor
;; keeps as its cause (DirectConstructorHandleAccessor; the method accessor
;; drops it): "Cannot cast" to the parameter's class, or to its box for a
;; primitive one given a box, and HotSpot's checkcast text against Number
;; for anything else, without the module clause.
(define (reflect-arg t v ctor?)
  (define (mismatch)
    (if ctor?
        (raise (construct "java/lang/IllegalArgumentException" "(Ljava/lang/String;Ljava/lang/Throwable;)V"
                          (jstring-lit "argument type mismatch")
                          (construct "java/lang/ClassCastException" "(Ljava/lang/String;)V"
                                     (jstring-lit
                                      (let ([from (binary-name (class-name-of v))])
                                        (cond [(not (prim-desc? t)) (string-append "Cannot cast " from " to " (field-type-name t))]
                                              [(exists (lambda (b) (jinstance? v b)) '("java/lang/Number" "java/lang/Character" "java/lang/Boolean"))
                                               (string-append "Cannot cast " from " to " (binary-name (cadr (assoc t box-classes))))]
                                              [else (string-append "class " from " cannot be cast to class java.lang.Number")]))))))
        (reflect-type-mismatch)))
  (cond
    [(prim-desc? t)
     (let ([e (and v (jobj? v) (find (lambda (b) (string=? (cadr b) (class-name-of v))) box-classes))])
       (cond
         [(not v)
          (raise (construct "java/lang/IllegalArgumentException" "(Ljava/lang/Throwable;)V"
                            (construct "java/lang/NullPointerException" "(Ljava/lang/String;)V"
                                       (jstring-lit
                                        (string-append
                                         "Cannot invoke \"java.lang.Number."
                                         (case (string-ref t 0)
                                           [(#\J) "long"] [(#\F) "float"] [(#\D) "double"] [(#\S) "short"] [(#\B) "byte"]
                                           [else "int"])
                                         "Value()\" because the return value of \"sun.invoke.util.ValueConversions"
                                         ".primitiveConversion(sun.invoke.util.Wrapper, Object, boolean)\" is null")))))]
         [(not e) (mismatch)]
         [(string=? (car e) t) (junbox t v)]
         [(let ([w (assoc (car e) prim-widenings)]) (and w (member t (cdr w))))
          (widen (car e) t (junbox (car e) v))]
         [else (mismatch)]))]
    [(or (not v) (jinstance? v (desc->class-name t))) v]
    [else (mismatch)]))

;; Method.invoke's receiver: as DirectMethodHandleAccessor.checkReceiver.
;; Method.invoke's own access check reaches obj.getClass() first unless the
;; method was made accessible, so these texts are the accessible case's.
(define (reflect-receiver obj cname)
  (unless obj
    (jthrow "java/lang/NullPointerException" "Cannot invoke \"Object.getClass()\" because \"o\" is null"))
  (unless (jinstance? obj cname)
    (jthrow "java/lang/IllegalArgumentException"
            (string-append "object of type " (binary-name (class-name-of obj))
                           " is not an instance of " (binary-name cname)))))

;; Run thunk; a Java exception it throws comes back wrapped in
;; InvocationTargetException, as Method.invoke promises.
(define (invoke-wrapped thunk)
  (let ([r (guard (e [(jobj? e) (cons 'thrown e)]) (cons 'ok (thunk)))])
    (if (eq? (car r) 'ok)
        (cdr r)
        (let ([ite (construct "java/lang/reflect/InvocationTargetException" "(Ljava/lang/Throwable;)V" (cdr r))])
          (raise ite)))))

(define (reflected-member obj-class-field obj)
  (let* ([c (mirror-class (jslot obj (jfield-index obj-class-field "clazz")))]
         [slot (jslot obj (jfield-index obj-class-field "slot"))])
    (values c (list-ref (meta-methods c) slot))))

;; a Method or Constructor's entry in its class's meta
(define (executable-meta e)
  (let-values ([(c meta) (reflected-member (class-name-of e) e)]) meta))

;; MethodParameters, or null so that Executable synthesizes arg0, arg1...
(defnative "java/lang/reflect/Executable.getParameters0()[Ljava/lang/reflect/Parameter;" (this)
  (let ([ps (member-attr (executable-meta this) 9)])
    (and ps
         (make-jarr "Ljava/lang/reflect/Parameter;"
          (list->vector
           (let loop ([ps ps] [i 0])
             (if (null? ps) '()
                 (cons (construct "java/lang/reflect/Parameter" "(Ljava/lang/String;ILjava/lang/reflect/Executable;I)V"
                                  (refl-string (caar ps)) (cadar ps) this i)
                       (loop (cdr ps) (fx+ i 1))))))))))
(defnative "java/lang/reflect/Executable.getTypeAnnotationBytes0()[B" (this)
  (refl-bytes (member-attr (executable-meta this) 7)))
(defnative "java/lang/reflect/Field.getTypeAnnotationBytes0()[B" (this)
  (let* ([c (mirror-class (jslot this (jfield-index "java/lang/reflect/Field" "clazz")))]
         [f (list-ref (meta-fields c) (jslot this (jfield-index "java/lang/reflect/Field" "slot")))])
    (refl-bytes (member-attr f 5))))

(defnative "jdk/internal/reflect/DirectMethodHandleAccessor$NativeAccessor.invoke0(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
  (m obj args)
  (reflect-invoke m obj args))

;; A caller-sensitive method called through Method.invoke. The JDK's
;; native accessor puts the caller first in its adapter's arguments, where
;; findCSMethodAdapter gives the adapter (Class.forName(String, Class)) the
;; caller last; HotSpot takes that path only before java.lang.invoke is up,
;; champaca always. So the caller goes last here, and a method without an
;; adapter runs as itself, finding its caller by the frames as any call does
;; (the JDK's other path needs java.lang.invoke).
(define-override! "jdk/internal/reflect/DirectMethodHandleAccessor$NativeAccessor.invoke(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;"
  (lambda (this obj args caller)
    (let* ([cn "jdk/internal/reflect/DirectMethodHandleAccessor$NativeAccessor"]
           [adapter (jslot this (jfield-index cn "csmAdapter"))])
      (if adapter
          (reflect-invoke adapter obj
                          (make-jarr "Ljava/lang/Object;"
                                     (list->vector (append (if args (vector->list (jarr-vec args)) '()) (list caller)))))
          (reflect-invoke (jslot this (jfield-index cn "method")) obj args)))))

(define (reflect-invoke m obj args)
  (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" m)])
    (let* ([cname (jclass-cname c)] [desc (cadr meta)] [key (string-append (car meta) desc)]
           [static? (static-flag? (caddr meta))]
           [argv (begin (unless static? (reflect-receiver obj cname))
                        (reflect-arg-list (desc-args desc) args))]
           [ret (desc-ret desc)])
      (when static? (jinit cname))
      (let ([r (invoke-wrapped
                (lambda ()
                  (if static?
                      (apply (method-proc cname key) argv)
                      ;; a private method is its own, a package-private one
                      ;; selected by run-time package (runtime.ss)
                      (apply (virtual-target obj cname key (caddr meta)) obj argv))))])
        (cond [(string=? ret "V") #f] [(prim-desc? ret) (jbox ret r)] [else r])))))

(defnative "jdk/internal/reflect/DirectConstructorHandleAccessor$NativeAccessor.newInstance0(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;"
  (ctor args)
  (let-values ([(c meta) (reflected-member "java/lang/reflect/Constructor" ctor)])
    (let* ([cname (jclass-cname c)] [desc (cadr meta)]
           ;; a serialization constructor allocates the serializable class and
           ;; runs its first non-serializable superclass's constructor on it
           [serial (eq-hashtable-ref serial-allocs ctor #f)]
           [alloc (or serial cname)])
      ;; the class first, then the arguments: the JVM initializes the class
      ;; even when an argument doesn't fit
      (jinit alloc)
      (let ([argv (reflect-arg-list (desc-args desc) args 'ctor)])
        (invoke-wrapped
         (lambda ()
           (when serial (check-allocatable alloc))
           (let ([o (jnew alloc)])
             (apply (method-proc cname (string-append "<init>" desc)) o argv)
             o)))))))

;; HotSpot's check_valid_for_instantiation, for the class a serialization
;; constructor allocates: the JVM's allocator throws these out of newInstance
;; wrapped in InvocationTargetException. The ordinary path never gets here
;; with an abstract class (ReflectionFactory.newConstructorAccessor refuses it
;; first), and interfaces and arrays have no constructors.
(define (check-allocatable cname)
  (let ([c (find-class cname)])
    (cond [(string=? cname "java/lang/Class") (jthrow "java/lang/IllegalAccessException" "java.lang.Class")]
          [(or (memq (jclass-kind c) '(array interface)) (fxpositive? (fxlogand (jclass-flags c) #x400)))
           (jthrow "java/lang/InstantiationException" (binary-name cname))]
          [else (void)])))

;; --- serialization ---------------------------------------------------------------
;; ObjectStreamClass asks ReflectionFactory for a constructor that allocates
;; the serializable class and then runs the no-arg constructor of its first
;; non-serializable superclass (Java Object Serialization Specification 3.1,
;; step 11). The JDK builds that from a method handle on a MemberName, which
;; needs MethodHandleNatives; here the accessor is the JDK's own native one
;; (newInstance0 above), and this table tells it which class to allocate in
;; place of the constructor's.
(define serial-allocs (make-weak-eq-hashtable))   ; Constructor -> class name to allocate

;; as MethodHandleAccessorFactory.constructorInSuperclass: decl itself or a
;; class up its superclass chain (an interface has none, an array's is Object)
(define (constructor-in-superclass? dn cn)
  (and (not (prim-name? dn))
       (not (eq? (jclass-kind (find-class dn)) 'interface))
       (let loop ([n dn])
         (cond [(not n) #f]
               [(string=? n cn) #t]
               [else (loop (jclass-super (find-class n)))]))))

(define-override! "jdk/internal/reflect/MethodHandleAccessorFactory.newSerializableConstructorAccessor(Ljava/lang/Class;Ljava/lang/reflect/Constructor;)Ljdk/internal/reflect/ConstructorAccessorImpl;"
  (lambda (decl ctor)
    (let ([dn (jclass-cname (mirror-class decl))]
          [cn (jclass-cname (mirror-class (jslot ctor (jfield-index "java/lang/reflect/Constructor" "clazz"))))])
      (unless (constructor-in-superclass? dn cn)
        (jthrow "java/lang/UnsupportedOperationException"
                (string-append (jstring->scheme ((jvirt ctor "toString()Ljava/lang/String;") ctor))
                               " not a superclass of " (binary-name dn))))
      ;; as the JDK: initialized here, so an ExceptionInInitializerError
      ;; isn't wrapped in an InvocationTargetException later
      (jinit dn)
      ;; the accessor gets a copy of the Constructor that nothing else holds,
      ;; and that copy is the table's key: the caller's object may be reused
      ;; for another class (Objenesis hands Object's constructor to every
      ;; class), and newInstance0 sees whatever the accessor holds
      (let ([key (make-jobj (jobj-cls ctor) (vector-copy (jobj-slots ctor)))])
        (eq-hashtable-set! serial-allocs key dn)
        (jinit "jdk/internal/reflect/DirectConstructorHandleAccessor")
        ((method-proc "jdk/internal/reflect/DirectConstructorHandleAccessor"
                      "nativeAccessor(Ljava/lang/reflect/Constructor;)Ljdk/internal/reflect/ConstructorAccessorImpl;")
         key)))))

;; A class's <clinit> is bound as its method global when it has one (jinit
;; calls it the same way). Part of the default serialVersionUID.
(defnative "java/io/ObjectStreamClass.hasStaticInitializer(Ljava/lang/Class;)Z" (c)
  (if (global-bound? (string->symbol (string-append (jclass-cname (mirror-class c)) ".<clinit>()V"))) 1 0))

;; The first frame up the stack whose class has a loader other than the boot
;; and platform ones, as HotSpot answers it: a class defined at run time has
;; its defining loader, a classpath class the application loader. When no
;; frame qualifies the caller is jolt itself (champaca.api), whose classes
;; the JVM would hold in the application loader, so that is the answer. The
;; JVM's null (the platform loader, through VM.latestUserDefinedLoader) is
;; for a stack of JDK code alone, which here also gets the application
;; loader: a Timer or common-pool thread deserializing from JDK code alone
;; sees more than on the JVM, never less. ObjectInputStream resolves class
;; names through it. app-loader (runtime.ss) is bound by the time any
;; program runs: initPhase2 initializes ClassLoaders.
(defnative "jdk/internal/misc/VM.latestUserDefinedLoader0()Ljava/lang/ClassLoader;" ()
  (let loop ([fs (java-frames)])
    (cond [(null? fs) (app-loader)]
          [(hashtable-ref class-loaders (caar fs) #f)]
          [(user-class? (caar fs)) (app-loader)]
          [else (loop (cdr fs))])))

;; Reflection.verifyMemberAccess asks this for a private member, and
;; ReflectionFactory when a serializable class's superclass has private
;; constructors only.
(defnative "jdk/internal/reflect/Reflection.areNestMates(Ljava/lang/Class;Ljava/lang/Class;)Z" (a b)
  (if (eq? (nest-host (mirror-class a)) (nest-host (mirror-class b))) 1 0))

;; --- field access (overrides of java.lang.reflect.Field) -----------------------------

;; -> (values getter setter field-descriptor what qname read-only?) for
;; field f on obj, as mode ('get, 'set for Field.set, 'set-typed for setInt
;; and friends) reaches it. read-only? is the JDK's: a static final field,
;; a final one not made accessible, and a record's or hidden class's final
;; field, which Field refuses to set with IllegalAccessException.
;; what names the field as the JDK's messages do ("static final int field
;; A.x"), qname is "A.x".
;; A null receiver for an instance field is a NullPointerException and one
;; of another class an IllegalArgumentException, with the JDK's texts for
;; mode. These overrides replace Field's own
;; access check too, which reaches obj.getClass() first unless the field was
;; made accessible, so a null receiver's text depends on that.
(define (field-access f obj mode)
  (let* ([c (mirror-class (jslot f (jfield-index "java/lang/reflect/Field" "clazz")))]
         [slot (jslot f (jfield-index "java/lang/reflect/Field" "slot"))]
         [meta (list-ref (meta-fields c) slot)]
         [cname (jclass-cname c)] [name (car meta)] [desc (cadr meta)]
         [static? (static-flag? (caddr meta))]
         [final? (fxpositive? (fxlogand (caddr meta) #x10))]
         [override? (eqv? 1 (jslot f (jfield-index "java/lang/reflect/Field" "override")))]
         [read-only? (and final? (or static? (not override?) (equal? (jclass-super c) "java/lang/Record")
                                     (string-search "/0x" cname)))]
         [qname (string-append (binary-name cname) "." name)]
         [what (string-append (if static? "static " "") (if final? "final " "")
                              (field-type-name desc) " field " qname)])
    (if static?
        (let ([sym (string->symbol (string-append cname ":" name))])
          (jinit cname)
          (values (lambda () (top-level-value sym))
                  (lambda (v) (set-top-level-value! sym v))
                  desc what qname read-only?))
        (let ([i (jfield-index cname name)])
          (unless obj
            (jthrow "java/lang/NullPointerException"
                    (cond [(not override?) "Cannot invoke \"Object.getClass()\" because \"obj\" is null"]
                          [(eq? mode 'set) "Cannot invoke \"Object.getClass()\" because \"o\" is null"]
                          [else #f])))
          (unless (jinstance? obj cname)
            (jthrow "java/lang/IllegalArgumentException"
                    (string-append "Can not " (if (eq? mode 'get) "get " "set ") what (if (eq? mode 'set) " to " " on ")
                                   (binary-name (class-name-of obj)))))
          (values (lambda () (vector-ref (jobj-slots obj) i))
                  (lambda (v) (vector-set! (jobj-slots obj) i v))
                  desc what qname read-only?)))))

(define (field-type-name d)
  (cond [(assoc d prim-names) => cdr] [else (binary-name (desc->class-name d))]))

;; whether a primitive of type from fits one of type to: the same or wider
(define (prim-widens? from to)
  (or (string=? from to) (let ([w (assoc from prim-widenings)]) (and w (member to (cdr w)) #t))))

(define-override! "java/lang/reflect/Field.get(Ljava/lang/Object;)Ljava/lang/Object;"
  (lambda (this obj)
    (let-values ([(get set desc what qname read-only?) (field-access this obj 'get)])
      (let ([v (get)]) (if (prim-desc? desc) (jbox desc v) v)))))

;; set takes v as Method.invoke takes an argument: a box unboxed and
;; widened for a primitive field, null or an instance for a reference one
(define-override! "java/lang/reflect/Field.set(Ljava/lang/Object;Ljava/lang/Object;)V"
  (lambda (this obj v)
    (let-values ([(get set desc what qname read-only?) (field-access this obj 'set)])
      (define (refuse cname)
        (jthrow cname (string-append "Can not set " what " to " (if v (binary-name (class-name-of v)) "null value"))))
      (when read-only? (refuse "java/lang/IllegalAccessException"))
      (set (cond
             [(prim-desc? desc)
              (let ([e (and v (jobj? v) (find (lambda (b) (string=? (cadr b) (class-name-of v))) box-classes))])
                (if (and e (prim-widens? (car e) desc))
                    (widen (car e) desc (junbox (car e) v))
                    (refuse "java/lang/IllegalArgumentException")))]
             [(or (not v) (jinstance? v (desc->class-name desc))) v]
             [else (refuse "java/lang/IllegalArgumentException")])))))

;; getInt and friends: a primitive field whose type widens to the getter's;
;; setInt and friends: a primitive field the setter's type widens to
(for-each
 (lambda (p)
   (let ([name (car p)] [t (cdr p)] [tname (cdr (assoc (cdr p) prim-names))])
     (define-override! (string-append "java/lang/reflect/Field.get" name "(Ljava/lang/Object;)" t)
       (lambda (this obj)
         (let-values ([(get set desc what qname read-only?) (field-access this obj 'get)])
           (unless (and (prim-desc? desc) (prim-widens? desc t))
             (jthrow "java/lang/IllegalArgumentException"
                     (string-append "Attempt to get " (field-type-name desc) " field \"" qname
                                    "\" with illegal data type conversion to " tname)))
           (widen desc t (get)))))
     (define-override! (string-append "java/lang/reflect/Field.set" name "(Ljava/lang/Object;" t ")V")
       (lambda (this obj v)
         (let-values ([(get set desc what qname read-only?) (field-access this obj 'set-typed)])
           (when read-only?
             (jthrow "java/lang/IllegalAccessException"
                     (string-append "Can not set " what " to (" tname ")" (jstring-of (jbox t v)))))
           (unless (and (prim-desc? desc) (prim-widens? t desc))
             (jthrow "java/lang/IllegalArgumentException"
                     (string-append "Can not set " what " to (" tname ")" (jstring-of (jbox t v)))))
           (set (widen t desc v)))))))
 '(("Boolean" . "Z") ("Byte" . "B") ("Char" . "C") ("Short" . "S")
   ("Int" . "I") ("Long" . "J") ("Float" . "F") ("Double" . "D")))

;; --- java.lang.reflect.Array ----------------------------------------------------------
;; Elements of primitive arrays are stored unboxed, so get boxes and set unboxes
;; by the array's element descriptor.

(define (class-name->desc n)
  (cond [(char=? (string-ref n 0) #\[) n]
        [(find (lambda (p) (string=? (cdr p) n)) prim-names) => car]
        [else (string-append "L" n ";")]))

(define (reflect-array a)
  (if (jarr? (nn a)) a
      (jthrow "java/lang/IllegalArgumentException" "Argument is not an array")))

(defnative "java/lang/reflect/Array.getLength(Ljava/lang/Object;)I" (a)
  (jarraylength (reflect-array a)))

(defnative "java/lang/reflect/Array.newArray(Ljava/lang/Class;I)Ljava/lang/Object;" (cls n)
  (when (string=? (jclass-cname (mirror-class (nn cls))) "void") (jthrow "java/lang/IllegalArgumentException" #f))   ; HotSpot's
  (let ([elem (class-name->desc (jclass-cname (mirror-class (nn cls))))])
    (jnewarray elem n (elem-default elem))))

(defnative "java/lang/reflect/Array.multiNewArray(Ljava/lang/Class;[I)Ljava/lang/Object;" (cls dims)
  ;; HotSpot's limits: no dimensions, or more than 255 counting the element
  ;; type's own, is IllegalArgumentException without a message
  (let ([n (jarraylength (nn dims))] [c (jclass-cname (mirror-class (nn cls)))])
    (when (or (fx= n 0) (string=? c "void") (fx> (fx+ n (let loop ([i 0]) (if (and (fx< i (string-length c)) (char=? (string-ref c i) #\[)) (loop (fx+ i 1)) i))) 255))
      (jthrow "java/lang/IllegalArgumentException" #f)))
  (jmultiarray (string-append (make-string (jarraylength dims) #\[)
                              (class-name->desc (jclass-cname (mirror-class (nn cls)))))
               (vector->list (jarr-vec dims))))

;; HotSpot's checks and order: an array (of primitives, for the typed
;; methods), then the index (no message), then the value's type, which may
;; widen (an int into a long[]) but never narrow.
(define (reflect-prim-array a)
  (let ([a (reflect-array a)])
    (if (prim-desc? (jarr-elem a)) a
        (jthrow "java/lang/IllegalArgumentException" "Argument is not an array of primitive type"))))
(define (reflect-index a i)
  (unless (and (fx>= i 0) (fx< i (vector-length (jarr-vec a))))
    (jthrow "java/lang/ArrayIndexOutOfBoundsException" #f)))
(define (reflect-type-mismatch) (jthrow "java/lang/IllegalArgumentException" "argument type mismatch"))
;; x has primitive type `from`
(define (reflect-prim-set! a i from x)
  (let ([e (jarr-elem a)])
    (unless (prim-convertible? from e) (reflect-type-mismatch))
    (vector-set! (jarr-vec a) i (widen from e x))))

(defnative "java/lang/reflect/Array.get(Ljava/lang/Object;I)Ljava/lang/Object;" (a i)
  (let* ([a (reflect-array a)] [e (jarr-elem a)])
    (reflect-index a i)
    (let ([v (vector-ref (jarr-vec a) i)]) (if (prim-desc? e) (jbox e v) v))))

(defnative "java/lang/reflect/Array.set(Ljava/lang/Object;ILjava/lang/Object;)V" (a i v)
  (let* ([a (reflect-array a)] [e (jarr-elem a)])
    (cond
      [(prim-desc? e)
       (unless v (jthrow "java/lang/IllegalArgumentException" #f))
       (reflect-index a i)
       (let ([p (box-prim-of v)])
         (unless p (reflect-type-mismatch))
         (reflect-prim-set! a i p (junbox p v)))]
      [else
       (reflect-index a i)
       (when (and v (not (array-store-ok? a v)))
         (jthrow "java/lang/IllegalArgumentException" "array element type mismatch"))
       (vector-set! (jarr-vec a) i v)])))

;; getInt, setLong and the rest
(for-each
 (lambda (p)
   (let ([name (car p)] [t (cdr p)])
     (define-native! (string-append "java/lang/reflect/Array.get" name "(Ljava/lang/Object;I)" t)
       (lambda (a i)
         (let* ([a (reflect-prim-array a)] [e (jarr-elem a)])
           (reflect-index a i)
           (unless (prim-convertible? e t) (reflect-type-mismatch))
           (widen e t (vector-ref (jarr-vec a) i)))))
     (define-native! (string-append "java/lang/reflect/Array.set" name "(Ljava/lang/Object;I" t ")V")
       (lambda (a i x)
         (let ([a (reflect-prim-array a)])
           (reflect-index a i)
           (reflect-prim-set! a i t x))))))
 '(("Boolean" . "Z") ("Byte" . "B") ("Char" . "C") ("Short" . "S")
   ("Int" . "I") ("Long" . "J") ("Float" . "F") ("Double" . "D")))

;; --- annotation instances ---------------------------------------------------------
;; The JDK builds an annotation with java.lang.reflect.Proxy, which generates a
;; class at run time. Here annotationForMap makes a synthetic class implementing
;; the annotation interface instead (from the lambda-class machinery, through
;; set-annotation-class-hook!), points each element method at a procedure that
;; reads the member map, and keeps that map and the annotation type beside the
;; instance. toString, equals and hashCode are the JDK's own, from an
;; AnnotationInvocationHandler kept beside the instance too.

(define annotation-class-hook #f)
(define (set-annotation-class-hook! f) (set! annotation-class-hook f))
(define annotation-state (make-weak-eq-hashtable))

(define (annotation-member self name ret)
  (let* ([st (eq-hashtable-ref annotation-state self #f)]
         [m (vector-ref st 0)]
         [v ((jvirt m "get(Ljava/lang/Object;)Ljava/lang/Object;") m (jstring name))])
    (cond [(jarr? v) (array-clone v)]  ; the JDK hands out a copy of an array member
          [(prim-desc? ret) (junbox ret v)]
          [else v])))

;; one procedure per element key "name()desc", shared by every annotation type
(define (annotation-proc-symbol key)
  (let ([sym (string->symbol (string-append "champaca/Annotation." key))])
    (unless (global-bound? sym)
      (let* ([paren (let find ([i 0]) (if (char=? (string-ref key i) #\() i (find (fx+ i 1))))]
             [name (substring key 0 paren)]
             [ret (substring key (fx+ paren 2) (string-length key))])
        (set-top-level-value! sym (lambda (self) (annotation-member self name ret)))))
    sym))

(define-override! "sun/reflect/annotation/AnnotationParser.annotationForMap(Ljava/lang/Class;Ljava/util/Map;)Ljava/lang/annotation/Annotation;"
  (lambda (type values)
    (let* ([iface (mirror-class (nn type))]
           [keys (map (lambda (m) (string-append (car m) (cadr m)))
                      (filter (lambda (m) (and (fxpositive? (fxlogand (caddr m) #x400))
                                               (not (static-flag? (caddr m)))))
                              (meta-methods iface)))]
           [cname (annotation-class-hook (jclass-cname iface)
                                         (fold-left (lambda (acc k) (if (string=? acc "") k (string-append acc "|" k)))
                                                    "" keys))]
           [c (find-class cname)]
           [o (jnew cname)])
      (for-each (lambda (k) (eq-hashtable-set! (jclass-vtable c) (string->symbol k) (annotation-proc-symbol k))) keys)
      (for-each (lambda (key proc)
                  (let ([sym (string->symbol (string-append "champaca/Annotation." key))])
                    (define-global! sym proc)
                    (eq-hashtable-set! (jclass-vtable c) (string->symbol key) sym)))
                '("annotationType()Ljava/lang/Class;" "toString()Ljava/lang/String;"
                  "hashCode()I" "equals(Ljava/lang/Object;)Z")
                (list (lambda (self) (vector-ref (annotation-of self) 1))
                      (lambda (self) (annotation-handler-call self "toStringImpl()Ljava/lang/String;"))
                      (lambda (self) (annotation-handler-call self "hashCodeImpl()I"))
                      (lambda (self other)
                        (junbox "Z" (annotation-handler-call self "equalsImpl(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Boolean;"
                                                             self other)))))
      (eq-hashtable-set! annotation-state o
                         (vector values type
                                 (construct annotation-handler "(Ljava/lang/Class;Ljava/util/Map;)V" type values)))
      o)))

(define annotation-handler "sun/reflect/annotation/AnnotationInvocationHandler")
(define (annotation-of self) (eq-hashtable-ref annotation-state self #f))
;; one of the handler's private ...Impl methods, on this instance's handler
(define (annotation-handler-call self key . args)
  (apply (method-proc annotation-handler key) (vector-ref (annotation-of self) 2) args))
