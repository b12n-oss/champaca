;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Java strings: java.lang.String's own representation (a byte[] and a coder)
;; to and from Scheme strings, concatenation. Split from runtime.ss; loaded
;; right after it.

;; --- strings ----------------------------------------------------------------
;; java.lang.String is the JDK's own class: value is a byte[] and coder is
;; 0 (LATIN1) or 1 (UTF16, low byte first because StringUTF16.isBigEndian
;; answers false).

(define string-value-slot #f)
(define string-coder-slot #f)
;; the String class and a byte[]'s interned element descriptor, looked up
;; once: by name each is a string-table probe, and a concatenation made two
(define string-class #f)
(define byte-elem #f)
(define (string-slots!)
  (unless string-value-slot
    (set! string-class (find-class "java/lang/String"))
    (set! byte-elem (intern-elem "B"))
    (set! string-coder-slot (jfield-index "java/lang/String" "coder"))
    (set! string-value-slot (jfield-index "java/lang/String" "value"))))

(define (code-units s)
  ;; Scheme string (code points) -> list of UTF-16 code units. A vector is a
  ;; classfile constant holding a lone surrogate, already in code units.
  (if (vector? s)
      (vector->list s)
      (let loop ([cs (reverse (string->list s))] [acc '()])
        (if (null? cs) acc
            (let ([cp (char->integer (car cs))])
              (if (fx> cp #xffff)
                  (let ([v (fx- cp #x10000)])
                    (loop (cdr cs) (cons (fx+ #xd800 (fxsrl v 10)) (cons (fx+ #xdc00 (fxlogand v #x3ff)) acc))))
                  (loop (cdr cs) (cons cp acc))))))))

(define (jstring s) (units->jstring (code-units s)))

;; a list of UTF-16 code units -> java.lang.String
(define (units->jstring units)
  (string-slots!)
  (let* ([latin1? (for-all (lambda (u) (fx< u 256)) units)]
         [bytes (if latin1?
                    (map i2b units)
                    (apply append (map (lambda (u) (list (i2b (fxlogand u #xff)) (i2b (fxsrl u 8)))) units)))]
         [o (jnew "java/lang/String")])
    (vector-set! (jobj-slots o) string-value-slot (make-jarr "B" (list->vector bytes)))
    (vector-set! (jobj-slots o) string-coder-slot (if latin1? 0 1))
    o))

(define interned (make-hashtable string-hash string=?))
(define interned-units (make-hashtable equal-hash equal?))
(define (jstring-lit s)
  (let ([t (if (string? s) interned interned-units)])
    (or (hashtable-ref t s #f)
        (let ([o (jstring s)]) (hashtable-set! t s o) o))))

(define (latin1-chars->string v n)
  (let ([s (make-string n)])
    (do ([i 0 (fx+ i 1)]) ((fx= i n) s)
      (string-set! s i (integer->char (fxlogand (vector-ref v i) #xff))))))

;; the UTF-16 unit at byte index i of a String's value: two bytes, low first
(define-syntax utf16-unit-at
  (syntax-rules () [(_ v i) (fxlogor (fxlogand (vector-ref v i) #xff) (fxsll (fxlogand (vector-ref v (fx+ i 1)) #xff) 8))]))
(define-syntax unit-within?
  (syntax-rules () [(_ u lo hi) (let ([x u]) (and (fx>= x lo) (fx< x hi)))]))

;; a surrogate pair becomes one char; a lone surrogate has no Scheme char, so
;; it becomes U+FFFD, as an encoder would write it
(define (utf16-chars->string v n)
  (let loop ([i 0] [acc '()])
    (if (fx>= i n)
        (list->string (reverse acc))
        (let ([u (utf16-unit-at v i)])
          (cond
            [(not (unit-within? u #xd800 #xe000)) (loop (fx+ i 2) (cons (integer->char u) acc))]
            [(and (fx< u #xdc00) (fx< (fx+ i 2) n) (unit-within? (utf16-unit-at v (fx+ i 2)) #xdc00 #xe000))
             (let ([lo (utf16-unit-at v (fx+ i 2))])
               (loop (fx+ i 4) (cons (integer->char (fx+ #x10000 (fxsll (fx- u #xd800) 10) (fx- lo #xdc00))) acc)))]
            [else (loop (fx+ i 2) (cons (integer->char #xfffd) acc))])))))

;; --- String.charAt, length and isEmpty at the call site --------------------------
;; HotSpot intrinsifies these. In Java, charAt is isLatin1(), then
;; StringLatin1.charAt, checkIndex and Preconditions before the byte is read:
;; five calls for one array read, and a loop over a string pays them per
;; character. The code generator writes these forms where a call names one
;; of the three methods on java.lang.String (intrinsics.clj direct-forms).
;; The receiver is already null-checked. An index in range reads the byte, or
;; the two of a UTF-16 unit (low byte first, as the JDK stores them on the
;; little-endian machines champaca runs on). Anything else calls the method
;; itself, passed as slow, so the exception, its message and its stack trace
;; are the JDK's own. The method is not overridden: a call through
;; CharSequence still runs the Java code.
(define-syntax jstring-value-vec   ; the byte[]'s vector, the slot numbers looked up once
  (syntax-rules ()
    [(_ slots)
     (begin (when (#3%eq? string-value-slot #f) (string-slots!))
            (jarr-vec (#3%vector-ref slots string-value-slot)))]))
(define-syntax jstring-char-at
  (syntax-rules ()
    [(_ s i slow)
     (let* ([o s] [k i] [slots (jobj-slots o)] [v (jstring-value-vec slots)])
       (if (#3%eq? 0 (#3%vector-ref slots string-coder-slot))
           (if (and (#3%fx>= k 0) (#3%fx< k (#3%vector-length v)))
               (#3%fxlogand (#3%vector-ref v k) #xff)
               (slow o k))
           (let ([j (#3%fx* 2 k)])
             (if (and (#3%fx>= k 0) (#3%fx< (#3%fx+ j 1) (#3%vector-length v)))
                 (#3%fxlogor (#3%fxlogand (#3%vector-ref v j) #xff)
                             (#3%fxsll (#3%fxlogand (#3%vector-ref v (#3%fx+ j 1)) #xff) 8))
                 (slow o k)))))]))
(define-syntax jstring-length
  (syntax-rules ()
    [(_ s slow)
     (let* ([slots (jobj-slots s)] [v (jstring-value-vec slots)])
       (#3%fxsra (#3%vector-length v) (#3%vector-ref slots string-coder-slot)))]))
(define-syntax jstring-empty?
  (syntax-rules ()
    [(_ s slow)
     (let* ([slots (jobj-slots s)] [v (jstring-value-vec slots)])
       (if (#3%fx= 0 (#3%vector-length v)) 1 0))]))

(define (jstring->scheme o)
  (string-slots!)
  (let* ([v (jarr-vec (vector-ref (jobj-slots o) string-value-slot))]
         [n (vector-length v)])
    (if (eqv? 0 (vector-ref (jobj-slots o) string-coder-slot))
        (latin1-chars->string v n)
        (utf16-chars->string v n))))

;; a java.lang.String's UTF-16 code units, as a list
(define (jstring-units o)
  (string-slots!)
  (let* ([v (jarr-vec (vector-ref (jobj-slots o) string-value-slot))]
         [n (vector-length v)])
    (if (eqv? 0 (vector-ref (jobj-slots o) string-coder-slot))
        (map (lambda (b) (fxlogand b #xff)) (vector->list v))
        (let loop ([i (fx- n 2)] [acc '()])
          (if (fx< i 0) acc
              (loop (fx- i 2) (cons (fxlogor (fxlogand (vector-ref v i) #xff)
                                             (fxsll (fxlogand (vector-ref v (fx+ i 1)) #xff) 8))
                                    acc)))))))

;; UTF-16 code units -> Scheme string, a pair becoming one char; #f when a
;; surrogate stands alone (classfile/decode-mutf8 makes the same split)
(define (units->string-or-false units)
  (let loop ([us units] [acc '()])
    (cond
      [(null? us) (list->string (reverse acc))]
      [(or (fx< (car us) #xd800) (fx>= (car us) #xe000))
       (loop (cdr us) (cons (integer->char (car us)) acc))]
      [(and (fx< (car us) #xdc00) (pair? (cdr us))
            (fx>= (cadr us) #xdc00) (fx< (cadr us) #xe000))
       (loop (cddr us) (cons (integer->char (fx+ #x10000 (fxsll (fx- (car us) #xd800) 10) (fx- (cadr us) #xdc00))) acc))]
      [else #f])))

;; String.intern, keyed the way jstring-lit keys constants: by the Scheme
;; string, or by the code-unit vector when a surrogate stands alone (keying on
;; jstring->scheme would map every lone surrogate to U+FFFD and collide)
(define (jstring-intern! o)
  (let* ([units (jstring-units o)]
         [s (units->string-or-false units)]
         [t (if s interned interned-units)]
         [k (or s (list->vector units))])
    (or (hashtable-ref t k #f) (begin (hashtable-set! t k o) o))))

;; whether a java.lang.String equals a constant (a string or code-unit vector).
;; jstring->scheme maps a lone surrogate to U+FFFD, so string=? is exact only
;; when the label has no U+FFFD; otherwise compare code units.
(define (jstring-lit-equal? o lit)
  (if (and (string? lit) (not (memv (integer->char #xfffd) (string->list lit))))
      (string=? (jstring->scheme o) lit)
      (equal? (jstring-units o) (code-units lit))))

;; any Java reference -> Scheme string, the way string concatenation sees it
(define (jstring-of x)
  (cond [(not x) "null"]
        [(and (jobj? x) (string=? (jclass-cname (jobj-cls x)) "java/lang/String")) (jstring->scheme x)]
        [else (let ([s ((jvirt x "toString()Ljava/lang/String;") x)]) (if s (jstring->scheme s) "null"))]))

(define (jbool->string z) (if (eqv? z 0) "false" "true"))

;; any Java reference -> its UTF-16 code units, the way string concatenation
;; sees it; unlike jstring-of this keeps a lone surrogate
(define (jstring-units-of x)
  (cond [(not x) (code-units "null")]
        [(and (jobj? x) (eq? (jobj-cls x) string-class)) (jstring-units x)]
        [else (let ([s ((jvirt x "toString()Ljava/lang/String;") x)]) (if s (jstring-units s) (code-units "null")))]))

;; x's code units pushed onto acc, last first: x is a Scheme string, a list of
;; code units, or a classfile constant's code-unit vector
(define (units-onto x acc)
  (cond
    [(string? x)
     (let ([n (string-length x)])
       (let loop ([i 0] [acc acc])
         (if (fx= i n) acc
             (let ([cp (char->integer (string-ref x i))])
               (loop (fx+ i 1)
                     (if (fx> cp #xffff)
                         (let ([v (fx- cp #x10000)])
                           (cons (fx+ #xdc00 (fxlogand v #x3ff)) (cons (fx+ #xd800 (fxsrl v 10)) acc)))
                         (cons cp acc)))))))]
    [(vector? x) (units-onto (vector->list x) acc)]
    [(fixnum? x) (units-onto (number->string x) acc)]
    [(not x) (units-onto "null" acc)]
    [(or (jobj? x) (jarr? x)) (units-onto (jstring-units-of x) acc)]
    [else (fold-left (lambda (a u) (cons u a)) acc x)]))

;; makeConcatWithConstants: \x1 takes the next argument, \x2 the next
;; bootstrap constant. An argument arrives as a Scheme string (a number), a
;; list of code units (a char), or the Java reference itself. When every
;; piece is Latin-1 (a String with coder 0, a literal below U+0100) the
;; result's bytes are copied straight from the pieces; else it is built as
;; code units, so a lone surrogate in any part survives (jconcat-units).
;; The recipe is parsed once per recipe object (a call site's literal).
(define concat-recipes (make-weak-eq-hashtable))
(define (concat-parts recipe)
  (or (eq-hashtable-ref concat-recipes recipe #f)
      (let ([parts (let ([n (string-length recipe)])
                     (let loop ([i 0] [start 0] [acc '()])
                       (let ([lit (lambda () (if (fx> i start) (cons (substring recipe start i) acc) acc))])
                         (cond [(fx= i n) (reverse (lit))]
                               [(char=? (string-ref recipe i) #\x1) (loop (fx+ i 1) (fx+ i 1) (cons 1 (lit)))]
                               [(char=? (string-ref recipe i) #\x2) (loop (fx+ i 1) (fx+ i 1) (cons 2 (lit)))]
                               [else (loop (fx+ i 1) start acc)]))))])
        (eq-hashtable-set! concat-recipes recipe parts)
        parts)))
;; a reference argument as the piece it contributes: the String, its
;; toString, or "null" (jstring-units-of does the same for the slow path)
(define (concat-arg x)
  (cond [(not x) "null"]
        [(and (jobj? x) (eq? (jobj-cls x) string-class)) x]
        ;; an int or a long goes as the number: its digits are written straight
        ;; into the result (latin1-fill-fixnum!). One that isn't a fixnum, or
        ;; whose negation isn't, is rare enough to print the usual way.
        [(fixnum? x) (if (fx= x (most-negative-fixnum)) (number->string x) x)]
        [(number? x) (number->string x)]
        ;; an array too: javac before 19 passes any reference raw (19+ calls
        ;; String.valueOf first), and Object's toString answers for arrays
        [(or (jobj? x) (jarr? x)) (or ((jvirt x "toString()Ljava/lang/String;") x) "null")]
        [else x]))
;; the characters of a fixnum in decimal, its sign included (not the most
;; negative fixnum, whose negation isn't one: concat-arg keeps that out)
(define (fixnum-digits n)
  (let count ([m (if (fx< n 0) (fx- 0 n) n)] [k (if (fx< n 0) 2 1)])
    (if (fx< m 10) k (count (fxquotient m 10) (fx+ k 1)))))
;; a piece's byte length when it is Latin-1, else #f
(define (concat-latin1-length p)
  (cond [(string? p) (let ([n (string-length p)])
                       (let loop ([i 0])
                         (cond [(fx= i n) n]
                               [(fx< (char->integer (string-ref p i)) 256) (loop (fx+ i 1))]
                               [else #f])))]
        [(jobj? p) (and (eqv? 0 (vector-ref (jobj-slots p) string-coder-slot))
                        (vector-length (jarr-vec (vector-ref (jobj-slots p) string-value-slot))))]
        [(fixnum? p) (fixnum-digits p)]
        [(pair? p) (let loop ([l p] [n 0]) (cond [(null? l) n] [(fx< (car l) 256) (loop (cdr l) (fx+ n 1))] [else #f]))]
        [(null? p) 0]
        [else #f]))
;; jconcat-parts takes the recipe already parsed, as the code generator
;; writes it for a call site (a quoted list: literals, 1 for the next
;; argument, 2 for the next constant), so no lookup stands before the work.
;; jconcat parses a recipe string, for a caller that has only that.
(define (jconcat recipe args consts)
  (if (vector? recipe)
      (jconcat-units recipe args consts)
      (jconcat-parts (concat-parts recipe) args consts)))
(define (jconcat-parts parts args consts)
      (let* ([_ (string-slots!)]   ; concat-latin1-length reads the slots
             [pieces (concat-pieces parts args consts)]
             [total (concat-latin1-total pieces)])
        (if total
            (let ([v (make-vector total)])
              (string-slots!)
              (concat-latin1-fill! v pieces)
              (latin1-jstring v))
            (units->jstring (reverse (fold-left (lambda (acc p) (units-onto p acc)) '() pieces))))))
;; the pieces of parsed recipe parts: a 1 takes the next argument's piece
;; (concat-arg), a 2 the next constant, a literal stays as it is
(define (concat-pieces parts args consts)
  (let loop ([ps parts] [args args] [consts consts] [acc '()])
    (if (null? ps)
        (reverse acc)
        (let ([p (car ps)])
          (cond [(eqv? p 1) (loop (cdr ps) (cdr args) consts (cons (concat-arg (car args)) acc))]
                [(eqv? p 2) (loop (cdr ps) args (cdr consts) (cons (car consts) acc))]
                [else (loop (cdr ps) args consts (cons p acc))])))))
;; the pieces' total byte length when every one is Latin-1, else #f
(define (concat-latin1-total pieces)
  (let loop ([ps pieces] [n 0])
    (cond [(null? ps) n]
          [(concat-latin1-length (car ps)) => (lambda (k) (loop (cdr ps) (fx+ n k)))]
          [else #f])))
;; Each kind of piece's bytes into v from at, answering the index after them.
;; Macros, not procedures: they expand inside concat-latin1-fill!, so the
;; steps cost no call (a top-level procedure here is a mutable binding Chez
;; can't inline; splitting into procedures cost concatenation 3%).
(define-syntax latin1-fill-string!   ; a Scheme string of Latin-1 characters
  (syntax-rules ()
    [(_ v p at)
     (let ([n (string-length p)])
       (do ([i 0 (#3%fx+ i 1)]) ((#3%fx= i n))
         (let ([c (char->integer (#3%string-ref p i))])
           (#3%vector-set! v (#3%fx+ at i) (if (#3%fx>= c 128) (#3%fx- c 256) c))))
       (#3%fx+ at n))]))
(define-syntax latin1-fill-jstring!   ; a Java String whose coder is Latin-1
  (syntax-rules ()
    [(_ v p at)
     (let* ([sv (jarr-vec (vector-ref (jobj-slots p) string-value-slot))] [n (vector-length sv)])
       (do ([i 0 (#3%fx+ i 1)]) ((#3%fx= i n)) (#3%vector-set! v (#3%fx+ at i) (#3%vector-ref sv i)))
       (#3%fx+ at n))]))
;; an int's or a long's decimal digits, last first, with its sign
(define-syntax latin1-fill-fixnum!
  (syntax-rules ()
    [(_ v p at)
     (let* ([end (#3%fx+ at (fixnum-digits p))] [neg? (#3%fx< p 0)])
       (let digit ([m (if neg? (#3%fx- 0 p) p)] [i (#3%fx- end 1)])
         (#3%vector-set! v i (#3%fx+ 48 (#3%fxremainder m 10)))
         (when (#3%fx>= m 10) (digit (#3%fxquotient m 10) (#3%fx- i 1))))
       (when neg? (#3%vector-set! v at 45))
       end)]))
(define-syntax latin1-fill-units!   ; a list of code units, each under 256
  (syntax-rules ()
    [(_ v p at0)
     (let inner ([l p] [at at0])
       (if (null? l)
           at
           (begin (vector-set! v at (i2b (car l))) (inner (cdr l) (fx+ at 1)))))]))
;; the Latin-1 pieces' bytes, one after another, into v
(define (concat-latin1-fill! v pieces)
  (let loop ([ps pieces] [at 0])
    (unless (null? ps)
      (let ([p (car ps)])
        (loop (cdr ps)
              (cond [(string? p) (latin1-fill-string! v p at)]
                    [(jobj? p) (latin1-fill-jstring! v p at)]
                    [(fixnum? p) (latin1-fill-fixnum! v p at)]
                    [else (latin1-fill-units! v p at)]))))))
;; a String with coder 0 (Latin-1) over the bytes in v
(define (latin1-jstring v)
  (string-slots!)
  (let ([o (make-jobj string-class (vector-copy (jclass-template string-class)))])
    (vector-set! (jobj-slots o) string-value-slot (make-jarr* byte-elem v))
    (vector-set! (jobj-slots o) string-coder-slot 0)
    o))
;; the slow path, for a code-unit recipe (a constant holding a lone surrogate)
(define (jconcat-units recipe args consts)
  (let loop ([rs (code-units recipe)] [args args] [consts consts] [acc '()])
    (if (null? rs)
        (units->jstring (reverse acc))
        (let ([u (car rs)])
          (cond
            [(fx= u 1) (loop (cdr rs) (cdr args) consts (units-onto (car args) acc))]
            [(fx= u 2) (loop (cdr rs) args (cdr consts) (units-onto (car consts) acc))]
            [else (loop (cdr rs) args consts (cons u acc))])))))
