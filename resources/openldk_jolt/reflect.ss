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
  (let ([o (jnew cname)])
    (apply (method-proc cname (string-append "<init>" desc)) o args)
    o))

(defnative "java/lang/Class.getDeclaredMethods0(Z)[Ljava/lang/reflect/Method;" (this public-only)
  (let ([c (mirror-class this)])
    (make-jarr "Ljava/lang/reflect/Method;"
     (list->vector
      (map (lambda (im)
             (let* ([m (cdr im)] [name (car m)] [desc (cadr m)] [flags (caddr m)])
               (construct "java/lang/reflect/Method"
                          "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;IILjava/lang/String;[B[B[B)V"
                          this (jstring-lit name) (mirror-array (desc-args desc)) (desc->mirror (desc-ret desc))
                          (no-classes) (fxlogand flags #xffff) (car im) #f #f #f #f)))
           (indexed-members (meta-methods c)
                            (lambda (m) (and (not (char=? (string-ref (car m) 0) #\<))
                                             (or (eqv? public-only 0) (public? (caddr m)))))))))))

(defnative "java/lang/Class.getDeclaredConstructors0(Z)[Ljava/lang/reflect/Constructor;" (this public-only)
  (let ([c (mirror-class this)])
    (make-jarr "Ljava/lang/reflect/Constructor;"
     (list->vector
      (map (lambda (im)
             (let* ([m (cdr im)] [desc (cadr m)] [flags (caddr m)])
               (construct "java/lang/reflect/Constructor"
                          "(Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Class;IILjava/lang/String;[B[B)V"
                          this (mirror-array (desc-args desc)) (no-classes) (fxlogand flags #xffff) (car im) #f #f #f)))
           (indexed-members (meta-methods c)
                            (lambda (m) (and (string=? (car m) "<init>")
                                             (or (eqv? public-only 0) (public? (caddr m)))))))))))

(defnative "java/lang/Class.getDeclaredFields0(Z)[Ljava/lang/reflect/Field;" (this public-only)
  (let ([c (mirror-class this)])
    (make-jarr "Ljava/lang/reflect/Field;"
     (list->vector
      (map (lambda (im)
             (let* ([f (cdr im)] [flags (caddr f)])
               (construct "java/lang/reflect/Field"
                          "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;IZILjava/lang/String;[B)V"
                          this (jstring-lit (car f)) (desc->mirror (cadr f)) (fxlogand flags #xffff)
                          (if (and (static-flag? flags) (fxpositive? (fxlogand flags #x10))) 1 0)
                          (car im) #f #f)))
           (indexed-members (meta-fields c)
                            (lambda (f) (or (eqv? public-only 0) (public? (caddr f))))))))))

(defnative "java/lang/Class.getInterfaces0()[Ljava/lang/Class;" (this)
  (make-jarr "Ljava/lang/Class;" (list->vector (map jmirror-of (jclass-interfaces (mirror-class this))))))
(defnative "java/lang/Class.getDeclaringClass0()Ljava/lang/Class;" (this) #f)
(defnative "java/lang/Class.getSimpleBinaryName0()Ljava/lang/String;" (this) #f)
(defnative "java/lang/Class.getEnclosingMethod0()[Ljava/lang/Object;" (this) #f)
(defnative "java/lang/Class.getGenericSignature0()Ljava/lang/String;" (this) #f)
(defnative "java/lang/Class.getRawAnnotations()[B" (this) #f)
(defnative "java/lang/Class.getRawTypeAnnotations()[B" (this) #f)
(defnative "java/lang/Class.getConstantPool()Ljdk/internal/reflect/ConstantPool;" (this) #f)
(defnative "java/lang/Class.getDeclaredClasses0()[Ljava/lang/Class;" (this) (no-classes))
(defnative "java/lang/Class.getNestHost0()Ljava/lang/Class;" (this) this)
(defnative "java/lang/Class.getPermittedSubclasses0()[Ljava/lang/Class;" (this) #f)
(defnative "java/lang/Class.isRecord0()Z" (this)
  (let ([s (jclass-super (mirror-class this))]) (if (and s (string=? s "java/lang/Record")) 1 0)))
(defnative "java/lang/Class.getClassAccessFlagsRaw0()I" (this) (fxlogand (jclass-flags (mirror-class this)) #xffff))
(defnative "java/lang/Class.getClassFileVersion0()I" (this) 69)

;; getCallerClass walks the Chez continuation. Every translated method is a
;; top-level define named "Class.name(desc)" and its blocks are labelled
;; "Class.name(desc)#Bn", so a frame's code name says which Java method it
;; is. Nothing is tracked on normal calls; the walk only happens here.
(define (frame-java-classes)
  (call/cc
   (lambda (k)
     (let loop ([o (inspect/object k)] [acc '()] [n 0])
       (if (or (not (eq? (o 'type) 'continuation)) (fx= n 256))
           (reverse acc)
           (let* ([code (o 'code)]
                  [name (and code (code 'name))]
                  [cls (and (string? name) (java-frame-class name))])
             (loop (o 'link)
                   (if cls (cons (cons cls name) acc) acc)
                   (fx+ n 1))))))))

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

(define (method-of frame-name)
  ;; strip the "#Bn" block suffix so frames of one method compare equal
  (let loop ([i 0]) (cond [(fx= i (string-length frame-name)) frame-name]
                          [(char=? (string-ref frame-name i) #\#) (substring frame-name 0 i)]
                          [else (loop (fx+ i 1))])))

(define (reflection-frame? cls)
  (or (string=? cls "java/lang/reflect/Method")
      (string=? cls "java/lang/reflect/Constructor")
      (let ([p "jdk/internal/reflect/"]) (and (fx>= (string-length cls) (string-length p))
                                              (string=? p (substring cls 0 (string-length p)))))))

;; Java frames, innermost first, one entry per method activation. Under
;; OPENLDK_BT the shadow stack (jstack) is exact, and its fluid-let wrapper
;; hides frame names from the continuation walk, so it wins when present.
(define (java-frames)
  (if (pair? jstack)
      (map (lambda (n) (cons (java-frame-class n) n)) jstack)
      (frames-from-continuation)))

(define (frames-from-continuation)
  (let loop ([fs (frame-java-classes)] [acc '()])
    (cond [(null? fs) (reverse acc)]
          [(and (pair? acc) (string=? (method-of (cdar fs)) (cdar acc))) (loop (cdr fs) acc)]
          [else (loop (cdr fs) (cons (cons (caar fs) (method-of (cdar fs))) acc))])))

;; The JVM answers the caller of the method that called getCallerClass,
;; skipping reflection's own frames.
(define caller-debug? (getenv "OPENLDK_CALLER_DEBUG"))
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

(define (reflect-args ptypes args)
  (let ([argv (if args (vector->list (jarr-vec args)) '())])
    (unless (fx= (length argv) (length ptypes))
      (jthrow "java/lang/IllegalArgumentException"
              (string-append "wrong number of arguments: " (number->string (length argv))
                             " expected: " (number->string (length ptypes)))))
    (map (lambda (t v) (if (prim-desc? t) (junbox t (nn v)) v)) ptypes argv)))

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

(defnative "jdk/internal/reflect/DirectMethodHandleAccessor$NativeAccessor.invoke0(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
  (m obj args)
  (let-values ([(c meta) (reflected-member "java/lang/reflect/Method" m)])
    (let* ([cname (jclass-name c)] [desc (cadr meta)] [key (string-append (car meta) desc)]
           [static? (static-flag? (caddr meta))]
           [argv (reflect-args (desc-args desc) args)]
           [ret (desc-ret desc)])
      (when static? (jinit cname))
      (let ([r (invoke-wrapped
                (lambda ()
                  (if static?
                      (apply (method-proc cname key) argv)
                      (apply (jvirt obj key) obj argv))))])
        (cond [(string=? ret "V") #f] [(prim-desc? ret) (jbox ret r)] [else r])))))

(defnative "jdk/internal/reflect/DirectConstructorHandleAccessor$NativeAccessor.newInstance0(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;"
  (ctor args)
  (let-values ([(c meta) (reflected-member "java/lang/reflect/Constructor" ctor)])
    (let ([cname (jclass-name c)] [desc (cadr meta)])
      (jinit cname)
      (let ([argv (reflect-args (desc-args desc) args)])
        (invoke-wrapped
         (lambda ()
           (let ([o (jnew cname)])
             (apply (method-proc cname (string-append "<init>" desc)) o argv)
             o)))))))

;; --- field access (overrides of java.lang.reflect.Field) -----------------------------

;; -> (values getter setter field-descriptor) for field f on obj
(define (field-access f obj)
  (let* ([c (mirror-class (jslot f (jfield-index "java/lang/reflect/Field" "clazz")))]
         [slot (jslot f (jfield-index "java/lang/reflect/Field" "slot"))]
         [meta (list-ref (meta-fields c) slot)]
         [cname (jclass-name c)] [name (car meta)] [desc (cadr meta)])
    (if (static-flag? (caddr meta))
        (let ([sym (string->symbol (string-append cname ":" name))])
          (jinit cname)
          (values (lambda () (top-level-value sym))
                  (lambda (v) (set-top-level-value! sym v))
                  desc))
        (let ([i (jfield-index cname name)])
          (unless (jinstance? obj cname)
            (jthrow "java/lang/IllegalArgumentException"
                    (string-append "Can not get field " name " on " (if obj (class-name-of obj) "null"))))
          (values (lambda () (vector-ref (jobj-slots obj) i))
                  (lambda (v) (vector-set! (jobj-slots obj) i v))
                  desc)))))

(define-override! "java/lang/reflect/Field.get(Ljava/lang/Object;)Ljava/lang/Object;"
  (lambda (this obj)
    (let-values ([(get set desc) (field-access this obj)])
      (let ([v (get)]) (if (prim-desc? desc) (jbox desc v) v)))))

(define-override! "java/lang/reflect/Field.set(Ljava/lang/Object;Ljava/lang/Object;)V"
  (lambda (this obj v)
    (let-values ([(get set desc) (field-access this obj)])
      (set (if (prim-desc? desc) (junbox desc (nn v)) v)))))

(for-each
 (lambda (p)
   (let ([name (car p)] [t (cdr p)])
     (define-override! (string-append "java/lang/reflect/Field.get" name "(Ljava/lang/Object;)" t)
       (lambda (this obj) (let-values ([(get set desc) (field-access this obj)]) (get))))
     (define-override! (string-append "java/lang/reflect/Field.set" name "(Ljava/lang/Object;" t ")V")
       (lambda (this obj v) (let-values ([(get set desc) (field-access this obj)]) (set v))))))
 '(("Boolean" . "Z") ("Byte" . "B") ("Char" . "C") ("Short" . "S")
   ("Int" . "I") ("Long" . "J") ("Float" . "F") ("Double" . "D")))
