;; openldk-jolt runtime: the Chez Scheme half. Generated method bodies call
;; into these. Java values map to Scheme as:
;;   int/short/byte/char/boolean  fixnum (booleans are 0/1, as in bytecode)
;;   long                         exact integer, wrapped to 64 bits
;;   float/double                 flonum (float rounded through ieee-single)
;;   null                         #f
;;   String                       Chez string (spike shortcut, not java.lang.String)
;;   object                       jobj record: class + slot vector
;;   array                        jarr record: element descriptor + vector

;; --- numbers ---------------------------------------------------------------

(define (i32 x)
  (let ([y (logand x #xffffffff)])
    (if (fx>= y #x80000000) (fx- y #x100000000) y)))

(define (i64 x)
  (let ([y (logand x #xffffffffffffffff)])
    (if (>= y #x8000000000000000) (- y #x10000000000000000) y)))

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
(define (lcmp a b) (cond [(< a b) -1] [(> a b) 1] [else 0]))

;; --- object model ------------------------------------------------------------

(define-record-type jclass
  (fields name super interfaces template vtable (mutable state)))
(define-record-type jobj (fields cls slots))
(define-record-type jarr (fields elem vec))

(define classes (make-hashtable string-hash string=?))

;; vtable entries are (key . symbol); the symbol's top-level value is the
;; method procedure, which starts life as a JIT stub and is replaced in place.
(define (register-class! name super interfaces template vtable-alist)
  (let ([vt (make-hashtable string-hash string=?)])
    (for-each (lambda (p) (hashtable-set! vt (car p) (cdr p))) vtable-alist)
    (hashtable-set! classes name
                    (make-jclass name super interfaces template vt 'loaded))))

(define (find-class name)
  (or (hashtable-ref classes name #f)
      (error 'openldk "class not registered" name)))

;; <clinit> runs on first active use, superclass first.
(define (jinit name)
  (let ([c (find-class name)])
    (when (eq? (jclass-state c) 'loaded)
      (jclass-state-set! c 'initialized)
      (when (jclass-super c) (jinit (jclass-super c)))
      (let ([sym (string->symbol (string-append name ".<clinit>()V"))])
        (when (top-level-bound? sym) ((top-level-value sym)))))))

(define (jnew name)
  (let ([c (find-class name)])
    (make-jobj c (vector-copy (jclass-template c)))))

(define (class-of x)
  (cond [(jobj? x) (jclass-name (jobj-cls x))]
        [(string? x) "java/lang/String"]
        [else "java/lang/Object"]))

(define (subclass? cname target)
  (and cname
       (or (string=? cname target)
           (let ([c (hashtable-ref classes cname #f)])
             (and c
                  (or (subclass? (jclass-super c) target)
                      (exists (lambda (i) (subclass? i target)) (jclass-interfaces c))))))))

(define (jinstance? x target)
  (and x
       (or (string=? target "java/lang/Object")
           (cond [(jarr? x) #f]
                 [(string? x) (string=? target "java/lang/String")]
                 [else (subclass? (class-of x) target)]))))

(define (nn x) (if x x (jthrow "java/lang/NullPointerException" #f)))

(define (jcheckcast x target)
  (if (or (not x) (jinstance? x target))
      x
      (jthrow "java/lang/ClassCastException" (string-append (class-of x) " cannot be cast to " target))))

(define (jslot x i) (vector-ref (jobj-slots (nn x)) i))
(define (jslot-set! x i v) (vector-set! (jobj-slots (nn x)) i v))

;; Virtual and interface dispatch: one hashtable probe on the receiver's class.
(define (jvirt x key)
  (let ([x (nn x)])
    (cond
      [(jobj? x)
       (let ([sym (hashtable-ref (jclass-vtable (jobj-cls x)) key #f)])
         (if sym (top-level-value sym) (error 'openldk "no such method" (class-of x) key)))]
      [(string? x) (string-method key)]
      [else (error 'openldk "virtual call on unsupported value" x key)])))

;; --- arrays -------------------------------------------------------------------

(define (jnewarray elem n default)
  (if (fx< n 0)
      (jthrow "java/lang/NegativeArraySizeException" (number->string n))
      (make-jarr elem (make-vector n default))))

(define (jaload a i)
  (let ([v (jarr-vec (nn a))])
    (if (and (fx>= i 0) (fx< i (vector-length v)))
        (vector-ref v i)
        (jthrow "java/lang/ArrayIndexOutOfBoundsException" (number->string i)))))

(define (jastore a i x)
  (let ([v (jarr-vec (nn a))])
    (if (and (fx>= i 0) (fx< i (vector-length v)))
        (vector-set! v i x)
        (jthrow "java/lang/ArrayIndexOutOfBoundsException" (number->string i)))))

(define (jarraylength a) (vector-length (jarr-vec (nn a))))

;; --- exceptions ------------------------------------------------------------
;; A Java throw is a Scheme raise of the jobj. Catch clauses are guard
;; clauses that test jinstance?.

(define (jthrow cname msg)
  (let ([e (jnew cname)])
    (vector-set! (jobj-slots e) 0 msg)
    (raise e)))

(define (jathrow x) (raise (nn x)))

;; Trampoline used by methods with exception handlers: a block returns the
;; next block (a procedure) or the method's result (never a procedure).
(define (trampoline b)
  (let loop ([r (b)])
    (if (procedure? r) (loop (r)) r)))

;; --- strings ----------------------------------------------------------------

(define (jstring-of x)
  (cond [(not x) "null"]
        [(string? x) x]
        [else ((jvirt x "toString()Ljava/lang/String;") x)]))

(define (jbool->string z) (if (eqv? z 0) "false" "true"))

;; makeConcatWithConstants: \x1; in the recipe takes the next argument,
;; already converted to a string by the generated code.
(define (jconcat recipe . args)
  (let ([out (open-output-string)])
    (let loop ([i 0] [args args])
      (if (fx= i (string-length recipe))
          (get-output-string out)
          (let ([c (string-ref recipe i)])
            (if (char=? c #\x1)
                (begin (put-string out (car args)) (loop (fx+ i 1) (cdr args)))
                (begin (put-char out c) (loop (fx+ i 1) args))))))))

(define (string-method key)
  (cond
    [(string=? key "length()I") (lambda (s) (string-length s))]
    [(string=? key "toString()Ljava/lang/String;") (lambda (s) s)]
    [(string=? key "hashCode()I")
     (lambda (s) (let loop ([i 0] [h 0])
                   (if (fx= i (string-length s)) h
                       (loop (fx+ i 1) (i32 (+ (* 31 h) (char->integer (string-ref s i))))))))]
    [else (error 'openldk "String method not in spike" key)]))

;; --- shimmed JDK classes ------------------------------------------------------
;; Hand-written stand-ins. The codegen side lists the same classes in
;; openldk-jolt.shims so it can lay out fields and vtables.

(define |java/lang/Object.<init>()V| (lambda (this) (void)))
(define |java/lang/Object.toString()Ljava/lang/String;|
  (lambda (this) (string-append (class-of this) "@" (number->string (eq-hashtable-hash this) 16))))
(define (eq-hashtable-hash x) (logand (equal-hash x) #x7fffffff))
(define |java/lang/Object.hashCode()I| (lambda (this) (eq-hashtable-hash this)))

(define |java/lang/Throwable.<init>()V| (lambda (this) (void)))
(define |java/lang/Throwable.<init>(Ljava/lang/String;)V| (lambda (this m) (jslot-set! this 0 m)))
(define |java/lang/Throwable.getMessage()Ljava/lang/String;| (lambda (this) (jslot this 0)))
(define |java/lang/Throwable.toString()Ljava/lang/String;|
  (lambda (this)
    (let ([n (list->string (map (lambda (c) (if (char=? c #\/) #\. c)) (string->list (class-of this))))]
          [m (jslot this 0)])
      (if m (string-append n ": " m) n))))

(define |java/io/PrintStream.println(Ljava/lang/String;)V|
  (lambda (this s) (put-string (current-output-port) (jstring-of s)) (newline)))
(define |java/io/PrintStream.println(Ljava/lang/Object;)V|
  (lambda (this o) (put-string (current-output-port) (jstring-of o)) (newline)))
(define |java/io/PrintStream.println(I)V|
  (lambda (this i) (put-string (current-output-port) (number->string i)) (newline)))
(define |java/io/PrintStream.println(J)V|
  (lambda (this i) (put-string (current-output-port) (number->string i)) (newline)))
(define |java/io/PrintStream.println(Z)V|
  (lambda (this z) (put-string (current-output-port) (jbool->string z)) (newline)))
(define |java/io/PrintStream.println(C)V|
  (lambda (this c) (put-char (current-output-port) (integer->char c)) (newline)))
(define |java/io/PrintStream.println()V|
  (lambda (this) (newline)))

(define |java/lang/System:out| #f)
(define |java/lang/System.<clinit>()V|
  (lambda () (set! |java/lang/System:out| (jnew "java/io/PrintStream"))))

;; --- entry --------------------------------------------------------------------

;; Run Main.main(String[]) and report an uncaught Java exception the way the
;; JVM does. Returns the process exit status.
(define (run-main cname . args)
  (call/cc
   (lambda (k)
     (with-exception-handler
      (lambda (e)
        (if (jobj? e)
            (begin
              (flush-output-port (current-output-port))
              (put-string (current-error-port)
                          (string-append "Exception in thread \"main\" " (jstring-of e) "\n"))
              (k 1))
            (raise-continuable e)))
      (lambda ()
        (jinit cname)
        ((top-level-value (string->symbol (string-append cname ".main([Ljava/lang/String;)V")))
         (make-jarr "Ljava/lang/String;" (list->vector args)))
        (flush-output-port (current-output-port))
        0)))))

(define (i2b x) (fx- (fxlogxor (fxlogand x #xff) #x80) #x80))
(define (i2s x) (fx- (fxlogxor (fxlogand x #xffff) #x8000) #x8000))
