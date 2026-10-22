;; Calls from jolt into Java. jolt and Java share one Chez heap, so there is
;; no wire format: arguments and results are converted by descriptor, and any
;; Java object crosses as itself (an opaque jobj/jarr on the jolt side).
;;   jolt string <-> java.lang.String     jolt nil <-> null
;;   jolt boolean <-> Java boolean (0/1)  numbers pass through
;; A float/double parameter accepts a jolt integer and converts it.

(define (ldk-in d v)
  (cond
    [(eq? v jolt-nil) #f]
    [else
     (case (string-ref d 0)
       [(#\Z) (if v 1 0)]
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
               (let ([r (apply f (map ldk-out arg-ds args))])
                 (if (char=? (string-ref ret-d 0) #\V) (void) (ldk-in ret-d r)))))))

(define (ldk-to-string obj) (if (eq? obj jolt-nil) "null" (with-java-errors (lambda () (jstring-of obj)))))
(define (ldk-java-exception? e) (java-exception? e))
(define (ldk-exception-message e) (java-exception-message e))
(define (ldk-exception-object e) (java-exception-object e))

