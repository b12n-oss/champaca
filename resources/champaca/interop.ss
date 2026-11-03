;; Calls from jolt into Java. jolt and Java share one Chez heap, so there is
;; no wire format: arguments and results are converted by descriptor, and any
;; Java object crosses as itself (an opaque jobj/jarr on the jolt side).
;;   jolt string <-> java.lang.String     jolt nil <-> null
;;   jolt boolean <-> Java boolean (0/1)  numbers pass through
;; A float/double parameter accepts a jolt integer and converts it.

;; jolt's truth: nil and false are false, anything else true
(define (jolt-true? v) (not (or (eq? v jolt-nil) (eq? v #f))))

;; An integral parameter or result takes a jolt integer in its type's range,
;; or a char as its code, and nothing else: generated code's int and long
;; operations don't check their operands (runtime.ss ix+, ladd), so 1.5 or
;; nil passed on would compute garbage. Anything else is the Java exception
;; cls (IllegalArgumentException for an argument, as Method.invoke throws,
;; ClassCastException for a jolt fn's result, as a lambda's cast would).
(define (ldk-integral d v cls)
  (let* ([c (string-ref d 0)]
         [x (if (char? v) (char->integer v) v)]
         [range (case c [(#\I) '(-2147483648 . 2147483647)] [(#\S) '(-32768 . 32767)]
                  [(#\B) '(-128 . 127)] [(#\C) '(0 . 65535)]
                  [else '(#x-8000000000000000 . #x7fffffffffffffff)])])
    (if (and (or (fixnum? x) (and (char=? c #\J) (exact? x) (integer? x)))
             (<= (car range) x (cdr range)))
        x
        (jthrow cls (format "~a is not a Java ~a"
                            (if (eq? v jolt-nil) "nil" (format "~s" v))
                            (case c [(#\I) "int"] [(#\S) "short"] [(#\B) "byte"] [(#\C) "char"] [else "long"]))))))

(define (ldk-in d v . cls)
  (cond
    [(char=? (string-ref d 0) #\Z) (if (jolt-true? v) 1 0)]
    [(memv (string-ref d 0) '(#\I #\S #\B #\C #\J))
     (ldk-integral d v (if (null? cls) "java/lang/IllegalArgumentException" (car cls)))]
    [(eq? v jolt-nil) #f]
    [else
     (case (string-ref d 0)
       [(#\F) (f32 (inexact v))]
       [(#\D) (inexact v)]
       [(#\L #\[) (if (string? v) (jstring v) v)]
       [else v])]))

(define (java-string? v) (and (jobj? v) (string=? (jclass-cname (jobj-cls v)) "java/lang/String")))

(define (ldk-out d v)
  (case (string-ref d 0)
    [(#\V) jolt-nil]
    [(#\Z) (not (eqv? v 0))]
    [(#\L #\[) (cond [(not v) jolt-nil] [(java-string? v) (jstring->scheme v)] [else v])]
    [else v]))

;; Every entry point runs inside a guard that turns an escaping Java
;; exception into a Scheme condition jolt can catch, carrying the Java
;; toString as its message.
(define-record-type java-exception (fields object message))

(define (with-java-errors thunk)
  (guard (e [(jobj? e) (raise (make-java-exception e (guard (x [#t (class-name-of e)]) (jstring-of e))))])
    (thunk)))

(define (ldk-invoke proc desc args)
  (with-java-errors
   (lambda ()
     (ldk-out (desc-ret desc) (apply proc (map ldk-in (desc-args desc) args))))))

(define (ldk-call-static cname mname desc . args)
  (jinit cname)
  (ldk-invoke (method-proc cname (string-append mname desc)) desc args))

(define (ldk-new cname desc . args)
  (jinit cname)
  (with-java-errors
   (lambda ()
     (let ([o (jnew cname)])
       (apply (method-proc cname (string-append "<init>" desc)) o (map ldk-in (desc-args desc) args))
       o))))

(define (ldk-call obj mname desc . args)
  (let ([key (string-append mname desc)])
    (with-java-errors
     (lambda ()
       (ldk-out (desc-ret desc)
                (apply (jvirt obj key) obj (map ldk-in (desc-args desc) args)))))))

;; Java calling jolt: a lambda-class instance whose closure converts Java
;; arguments to jolt values, calls f, and converts the result back.
(define (ldk-implement cname desc f)
  (let ([arg-ds (desc-args desc)] [ret-d (desc-ret desc)])
    (jlambda cname
             (lambda args
               (let ([r (call-from-java f (map ldk-out arg-ds args))])
                 (if (char=? (string-ref ret-d 0) #\V) (void) (ldk-in ret-d r "java/lang/ClassCastException")))))))

(define (ldk-to-string obj) (if (eq? obj jolt-nil) "null" (with-java-errors (lambda () (jstring-of obj)))))
(define (ldk-java-exception? e) (java-exception? e))
(define (ldk-exception-message e) (java-exception-message e))
(define (ldk-exception-object e) (java-exception-object e))

;; A jolt error escaping a jolt fn that Java called isn't a Java object, so
;; no Java catch or finally would see it (they test jobj?): a synchronized
;; block would keep its monitor. Every call from Java into a jolt fn goes
;; through call-from-java, whose handler raises in its place the Java
;; exception the api's hook (java-error-of) gives: a RuntimeException for a
;; jolt error, remembered here, so that when it comes back out of Java the
;; api throws the jolt error itself again. The hook runs only on an error.
(define jolt-error-hook #f)
(define (set-jolt-error-hook! f) (set! jolt-error-hook f))
(define (call-from-java f args)
  (with-exception-handler
   (lambda (e)
     (if (or (jobj? e) (java-halt? e) (warning? e) (not jolt-error-hook))
         (raise-continuable e)
         (raise (jolt-error-hook e))))
   (lambda () (apply f args))))
(define jolt-errors (make-weak-eq-hashtable))
(define (ldk-jolt-error->java e msg)
  (let ([x (ldk-new "java/lang/RuntimeException" "(Ljava/lang/String;)V" msg)])
    (eq-hashtable-set! jolt-errors x e)
    x))
(define (ldk-java-object? v) (jobj? v))
(define (ldk-jolt-error-of x) (eq-hashtable-ref jolt-errors x #f))
;; the jolt error behind x or behind one of its causes (a Java wrapper such
;; as ExecutionException), else #f
(define (ldk-jolt-cause x)
  (guard (e [(jobj? e) #f])
    (let loop ([x x] [n 0])
      (and x (fx< n 64)
           (or (eq-hashtable-ref jolt-errors x #f)
               (let ([c ((jvirt x "getCause()Ljava/lang/Throwable;") x)])
                 (and (not (eq? c x)) (loop c (fx+ n 1)))))))))

