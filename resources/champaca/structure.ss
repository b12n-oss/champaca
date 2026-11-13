;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Structured method code (CHAMPACA_STRUCTURED=1): the generated form of a
;; method, rewritten before it's compiled so a person can read it. The code
;; generator writes one procedure per basic block and one let per stack slot;
;; here a block that one place calls moves to that place, and a let that only
;; renames a local or a constant goes away. What's left is the method's
;; conditionals in line, a procedure for each loop head and join point, and a
;; let for each value computed. Every runtime operation stays as it was, in
;; the same order, so the meaning is the generator's.
;;
;; A method the pass can't follow is left alone: one with exception handlers
;; (jtry), assigned locals (set!), or any binding form other than let, let*,
;; letrec and lambda. structure-stats counts those by reason.

(define structured? (and (getenv "CHAMPACA_STRUCTURED") #t))
(define structured-trace (getenv "CHAMPACA_STRUCTURED_TRACE"))

(define st-stats (make-eq-hashtable))
(define (st-count! k n) (eq-hashtable-update! st-stats k (lambda (m) (+ m n)) 0))
(define (structure-stats)
  (let-values ([(ks vs) (hashtable-entries st-stats)])
    (sort (lambda (a b) (string<? (symbol->string (car a)) (symbol->string (car b))))
          (map cons (vector->list ks) (vector->list vs)))))
(define (structure-stats-text)
  (apply string-append
         (map (lambda (p) (string-append (symbol->string (car p)) " " (number->string (cdr p)) "\n"))
              (structure-stats))))

;; forms whose operands aren't all expressions in the enclosing scope, or
;; that assign: a method using one is left as generated
(define st-declines
  '(jtry dynamic-wind fluid-let define case-lambda do case guard let-values let*-values
         parameterize quasiquote named-lambda let-syntax letrec-syntax letrec*))

(define (st-params ps)   ; the names a parameter list binds, or #f
  (let loop ([p ps] [acc '()])
    (cond [(null? p) (reverse acc)]
          [(symbol? p) (reverse (cons p acc))]
          [(and (pair? p) (symbol? (car p))) (loop (cdr p) (cons (car p) acc))]
          [else #f])))
(define (st-bindings? bs)
  (and (list? bs)
       (for-all (lambda (b) (and (pair? b) (symbol? (car b)) (pair? (cdr b)) (null? (cddr b)))) bs)))
(define (st-trivial? a) (or (symbol? a) (number? a) (boolean? a)))

;; --- what a method holds ------------------------------------------------------
;; names: each letrec-bound procedure -> #(uses bad? params body), bad when
;; something other than one call with plain arguments, outside its own body,
;; refers to it. decline is called with a reason when the method holds a form
;; the pass can't follow.
(define (st-note! r bad?)
  (vector-set! r 0 (fx+ 1 (vector-ref r 0)))
  (when bad? (vector-set! r 1 #t)))

(define (st-let-fault x)
  (cond [(and (pair? (cdr x)) (st-bindings? (cadr x))) #f]
        [(and (pair? (cdr x)) (symbol? (cadr x))) 'named-let]
        [else 'let-shape]))
(define (st-shape-fault x h)   ; why a form headed h can't be followed, or #f
  (cond
    [(memq h st-declines) h]
    ;; a static field is a global, and nothing here moves a global's read
    ;; past anything; an assigned local would break the renames
    [(eq? h 'set!) (and (not (and (fx= 3 (length x)) (champaca-global-name? (cadr x)))) 'set!)]
    [(eq? h 'lambda) (and (not (and (pair? (cdr x)) (st-params (cadr x)))) 'lambda-shape)]
    [(memq h '(let let*)) (st-let-fault x)]
    [(eq? h 'letrec) (and (not (and (pair? (cdr x)) (st-bindings? (cadr x)))) 'letrec-shape)]
    [else #f]))

(define (st-block-lambda? f)   ; a lambda with a plain parameter list
  (and (pair? f) (eq? (car f) 'lambda) (pair? (cdr f)) (list? (cadr f)) (for-all symbol? (cadr f))))
(define (st-register-labels! bs names decline)
  (for-each
    (lambda (b)
      (when (eq-hashtable-ref names (car b) #f) (decline 'label-bound-twice))
      (let ([f (cadr b)])
        (eq-hashtable-set! names (car b)
          (if (st-block-lambda? f) (vector 0 #f (cadr f) (cddr f)) (vector 0 #t #f #f)))))
    bs))

(define (st-scan-all xs inside names decline)
  (for-each (lambda (e) (st-scan-in e inside names decline)) xs))
(define (st-scan-letrec x inside names decline)
  (st-register-labels! (cadr x) names decline)
  (for-each
    (lambda (b)
      (let ([f (cadr b)])
        (if (and (pair? f) (eq? (car f) 'lambda))
            (let ([why (st-shape-fault f 'lambda)])
              (when why (decline why))
              (st-scan-all (cddr f) (cons (car b) inside) names decline))
            (st-scan-in f inside names decline))))
    (cadr x))
  (st-scan-all (cddr x) inside names decline))
(define (st-scan-call x r inside names decline)
  (st-note! r (or (memq (car x) inside)
                  (not (vector-ref r 2))
                  (not (fx= (length (cdr x)) (length (vector-ref r 2))))
                  (not (for-all st-trivial? (cdr x)))))
  (st-scan-all (cdr x) inside names decline))
(define (st-scan-form x h inside names decline)
  (let ([why (st-shape-fault x h)])
    (when why (decline why)))
  (cond
    [(eq? h 'set!) (st-scan-in (caddr x) inside names decline)]
    [(eq? h 'lambda) (st-scan-all (cddr x) inside names decline)]
    [(memq h '(let let*))
     (st-scan-all (map cadr (cadr x)) inside names decline)
     (st-scan-all (cddr x) inside names decline)]
    [(eq? h 'letrec) (st-scan-letrec x inside names decline)]
    [(and (symbol? h) (eq-hashtable-ref names h #f))
     => (lambda (r) (st-scan-call x r inside names decline))]
    [else (st-scan-all x inside names decline)]))
(define (st-scan-in x inside names decline)
  (cond
    [(symbol? x) (let ([r (eq-hashtable-ref names x #f)]) (when r (st-note! r #t)))]
    [(not (pair? x)) (void)]
    [(not (list? x)) (decline 'improper-form)]
    [(memq (car x) '(quote entry)) (void)]
    [else (st-scan-form x (car x) inside names decline)]))
(define (st-scan x names decline) (st-scan-in x '() names decline))

;; the free names of a form, as a list
(define (st-free x)
  (let ([bound (make-eq-hashtable)] [free (make-eq-hashtable)])
    (define (bind! s) (eq-hashtable-update! bound s (lambda (n) (fx+ n 1)) 0))
    (define (unbind! s) (eq-hashtable-update! bound s (lambda (n) (fx- n 1)) 0))
    (let walk ([x x])
      (cond
        [(symbol? x) (when (fx= 0 (eq-hashtable-ref bound x 0)) (eq-hashtable-set! free x #t))]
        [(not (pair? x)) (void)]
        [else
         (let ([h (car x)])
           (cond
             [(memq h '(quote entry)) (void)]
             [(eq? h 'lambda)
              (let ([ps (st-params (cadr x))])
                (for-each bind! ps) (for-each walk (cddr x)) (for-each unbind! ps))]
             [(eq? h 'let)
              (for-each (lambda (b) (walk (cadr b))) (cadr x))
              (for-each (lambda (b) (bind! (car b))) (cadr x))
              (for-each walk (cddr x))
              (for-each (lambda (b) (unbind! (car b))) (cadr x))]
             [(eq? h 'let*)
              (for-each (lambda (b) (walk (cadr b)) (bind! (car b))) (cadr x))
              (for-each walk (cddr x))
              (for-each (lambda (b) (unbind! (car b))) (cadr x))]
             [(eq? h 'letrec)
              (for-each (lambda (b) (bind! (car b))) (cadr x))
              (for-each (lambda (b) (walk (cadr b))) (cadr x))
              (for-each walk (cddr x))
              (for-each (lambda (b) (unbind! (car b))) (cadr x))]
             [else (for-each walk x)]))]))
    (vector->list (hashtable-keys free))))

;; --- the rewrite -----------------------------------------------------------------
;; Names in the generated form are keys; each maps to what the rewritten form
;; calls it: itself, a fresh name where keeping it would hide one still in
;; use, or the local or constant a dropped let gave it. A key's mappings are
;; a stack of (target . stamp), the stamp telling one binding from another.
(define (st-rewrite expr names)
  (let ([maps (make-eq-hashtable)]      ; key -> ((target . stamp) ...)
        [range (make-eq-hashtable)]     ; emitted name -> live keys that mean it under another name
        [bound (make-eq-hashtable)]     ; emitted name -> bindings in scope
        [inl (make-eq-hashtable)]       ; label -> #(params body free stamps own-stamp state)
        [stamp 0] [fresh 0] [inlined 0] [dropped 0])
    (define (top k) (let ([l (eq-hashtable-ref maps k '())]) (and (pair? l) (car l))))
    (define (top-stamp k) (let ([t (top k)]) (and t (cdr t))))
    (define (resolve k) (let ([t (top k)]) (if t (car t) k)))
    (define (range+ t k d)
      (when (and (symbol? t) (not (eq? t k))) (eq-hashtable-update! range t (lambda (n) (fx+ n d)) 0)))
    (define (push! k target)
      (let ([t (top k)]) (when t (range+ (car t) k -1)))
      (set! stamp (fx+ stamp 1))
      (eq-hashtable-update! maps k (lambda (l) (cons (cons target stamp) l)) '())
      (range+ target k 1))
    (define (pop! k)
      (let ([l (eq-hashtable-ref maps k '())])
        (range+ (caar l) k -1)
        (eq-hashtable-set! maps k (cdr l))
        (when (pair? (cdr l)) (range+ (caadr l) k 1))))
    (define (fresh-name v)
      (set! fresh (fx+ fresh 1))
      (let ([s (string->symbol (string-append (symbol->string v) "_" (number->string fresh)))])
        (if (or (fx> (eq-hashtable-ref bound s 0) 0) (top s)) (fresh-name v) s)))
    ;; a binding of v in the rewritten form: v itself unless a dropped let
    ;; still means the outer v
    (define (bind! v)
      (let ([e (if (fx> (eq-hashtable-ref range v 0) 0) (fresh-name v) v)])
        (eq-hashtable-update! bound e (lambda (n) (fx+ n 1)) 0)
        (push! v e)
        e))
    (define (unbind! v e)
      (pop! v)
      (eq-hashtable-update! bound e (lambda (n) (fx- n 1)) 0))
    ;; a value a name can stand for wherever it's used: a constant, or a local
    ;; (never assigned, the scan saw to that); a global may change between the
    ;; let and the use
    (define (alias? a)
      (or (number? a) (boolean? a) (and (symbol? a) (fx> (eq-hashtable-ref bound a 0) 0))))
    (define (body-form forms) (if (and (pair? forms) (null? (cdr forms))) (car forms) (cons 'begin forms)))
    (define (let-form kept forms)
      ;; (let ((x E)) x) stays as it is. With inspector information Chez
      ;; keeps the let, so a call in E isn't a tail call and its frame is
      ;; there for a stack trace and for getCallerClass; and a method's own
      ;; name is bound this way to name its lambda.
      (if (null? kept) (body-form forms) (cons* 'let kept forms)))

    (define (rw x)
      (cond
        [(symbol? x) (resolve x)]
        [(not (pair? x)) x]
        [else
         (let ([h (car x)])
           (cond
             [(memq h '(quote entry)) x]
             [(eq? h 'lambda) (rw-lambda x)]
             [(eq? h 'let) (rw-let (cadr x) (cddr x))]
             [(eq? h 'let*) (rw-let* (cadr x) (cddr x))]
             [(eq? h 'letrec) (rw-letrec (cadr x) (cddr x))]
             [(and (symbol? h) (eq-hashtable-ref inl h #f)) => (lambda (r) (rw-call x h r))]
             [else (map rw x)]))]))

    (define (rw-lambda x)
      (let* ([ps (cadr x)] [names (st-params ps)]
             [emitted (map bind! names)]
             [body (map rw (cddr x))])
        (for-each unbind! (reverse names) (reverse emitted))
        (cons* 'lambda
               (let shape ([p ps] [e emitted])   ; the list's own shape, a rest parameter kept
                 (cond [(null? p) '()] [(symbol? p) (car e)] [else (cons (car e) (shape (cdr p) (cdr e)))]))
               body)))

    ;; (let ((x E)) (let ((y x)) body)): the generator computes into a stack
    ;; slot and stores it to a local. The local's name is the better one, so
    ;; E is bound to y and x means y in the body.
    (define (store-shape bs body)
      (and (pair? bs) (null? (cdr bs)) (pair? body) (null? (cdr body))
           (not (st-trivial? (cadar bs)))
           (let ([b (car body)])
             (and (pair? b) (eq? (car b) 'let) (pair? (cdr b)) (pair? (cadr b)) (null? (cdadr b))
                  (eq? (cadr (caadr b)) (caar bs))
                  (not (eq? (car (caadr b)) (caar bs)))
                  b))))

    (define (rw-let bs body)
      (cond
        [(store-shape bs body)
         => (lambda (inner)
              (let* ([x (caar bs)] [y (car (caadr inner))]
                     [init (rw (cadar bs))])
                (push! x x)                 ; the old x is out of scope from here
                (let ([e (bind! y)])
                  (pop! x) (push! x e)
                  (set! dropped (fx+ dropped 1))
                  (let ([forms (map rw (cddr inner))])
                    (pop! x) (unbind! y e)
                    (let-form (list (list e init)) forms)))))]
        [else
         (let* ([inits (map (lambda (b) (rw (cadr b))) bs)]
                [done (map (lambda (b init)
                             (if (alias? init)
                                 (begin (push! (car b) init) (set! dropped (fx+ dropped 1)) #f)
                                 (bind! (car b))))
                           bs inits)]
                [forms (map rw body)])
           (for-each (lambda (b e) (if e (unbind! (car b) e) (pop! (car b)))) (reverse bs) (reverse done))
           (let-form (let keep ([d done] [i inits])
                       (cond [(null? d) '()]
                             [(car d) (cons (list (car d) (car i)) (keep (cdr d) (cdr i)))]
                             [else (keep (cdr d) (cdr i))]))
                     forms))]))

    (define (rw-let* bs body)
      (if (null? bs)
          (body-form (map rw body))
          (rw-let (list (car bs)) (list (cons* 'let* (cdr bs) body)))))

    (define (rw-letrec bs body)
      (let* ([emitted (map (lambda (b) (bind! (car b))) bs)]
             [mine '()])
        ;; a block one place calls waits for that place
        (for-each
          (lambda (b e)
            (let ([r (eq-hashtable-ref names (car b) #f)])
              (when (and r (eq? e (car b)) (fx= 1 (vector-ref r 0)) (not (vector-ref r 1)))
                (let ([free (st-free (cadr b))])
                  (eq-hashtable-set! inl (car b)
                    (vector (vector-ref r 2) (vector-ref r 3) free (map top-stamp free) (top-stamp (car b)) 'waiting))
                  (set! mine (cons (car b) mine))))))
          bs emitted)
        (let* ([kept (let keep ([bs bs] [es emitted])
                       (cond [(null? bs) '()]
                             [(eq-hashtable-ref inl (caar bs) #f) (keep (cdr bs) (cdr es))]
                             [else (cons (list (car es) (rw (cadar bs))) (keep (cdr bs) (cdr es)))]))]
               [forms (map rw body)]
               ;; a block that couldn't move (a name it uses means something
               ;; else at its call) stays a procedure; rewriting it can turn
               ;; up another
               [late (let loop ([acc '()])
                       (let ([k (find (lambda (k) (eq? 'stays (vector-ref (eq-hashtable-ref inl k #f) 5))) mine)])
                         (if k
                             (let ([r (eq-hashtable-ref inl k #f)])
                               (vector-set! r 5 'kept)
                               (loop (cons (list k (rw (cons* 'lambda (vector-ref r 0) (vector-ref r 1)))) acc)))
                             (reverse acc))))])
          (for-each (lambda (k) (eq-hashtable-delete! inl k)) mine)
          (for-each (lambda (b e) (unbind! (car b) e)) (reverse bs) (reverse emitted))
          (let ([all (append kept late)])
            (if (null? all) (body-form forms) (cons* 'letrec all forms))))))

    (define (rw-call x h r)
      (if (and (eq? 'waiting (vector-ref r 5))
               (eqv? (top-stamp h) (vector-ref r 4))
               (for-all (lambda (f s) (eqv? (top-stamp f) s)) (vector-ref r 2) (vector-ref r 3)))
          (begin
            (vector-set! r 5 'moved)
            (set! inlined (fx+ inlined 1))
            (rw-let (map list (vector-ref r 0) (cdr x)) (vector-ref r 1)))
          (begin
            (when (eq? 'waiting (vector-ref r 5)) (vector-set! r 5 'stays))
            (map rw x))))

    (let ([out (rw expr)])
      (st-count! 'blocks-moved inlined)
      (st-count! 'lets-dropped dropped)
      (st-count! 'names-made-fresh fresh)
      out)))

;; The trace is for reading. pretty-print writes a symbol holding ( or ; with
;; hex escapes, Gcd.gcd\x28;II\x29;I; st-bar-symbols puts such a token back
;; between bars, |Gcd.gcd(II)I|, as the generator wrote it. Strings are left
;; alone.
(define (st-pretty x)
  (let-values ([(p get) (open-string-output-port)])
    (parameterize ([pretty-line-length 110]) (pretty-print x p))
    (get)))
(define (st-unescape tok)   ; the token with each \xH; replaced, or #f if it has none
  (let ([n (string-length tok)])
    (let loop ([i 0] [acc '()] [any? #f])
      (cond
        [(fx= i n) (and any? (list->string (reverse acc)))]
        [(and (char=? (string-ref tok i) #\\) (fx< (fx+ i 1) n) (char=? (string-ref tok (fx+ i 1)) #\x))
         (let end ([j (fx+ i 2)])
           (cond [(fx= j n) #f]
                 [(char=? (string-ref tok j) #\;)
                  (let ([c (string->number (substring tok (fx+ i 2) j) 16)])
                    (and c (loop (fx+ j 1) (cons (integer->char c) acc) #t)))]
                 [else (end (fx+ j 1))]))]
        [else (loop (fx+ i 1) (cons (string-ref tok i) acc) any?)]))))
(define (st-delimiter? c) (or (char-whitespace? c) (memv c '(#\( #\) #\[ #\] #\' #\"))))
(define (st-string-end text i)   ; just past the string literal that opens at i
  (let ([n (string-length text)])
    (let end ([j (fx+ i 1)])
      (cond [(fx>= j n) n]
            [(char=? (string-ref text j) #\\) (end (fx+ j 2))]
            [(char=? (string-ref text j) #\") (fx+ j 1)]
            [else (end (fx+ j 1))]))))
(define (st-token-end text i)
  (let ([n (string-length text)])
    (let end ([j i]) (if (or (fx= j n) (st-delimiter? (string-ref text j))) j (end (fx+ j 1))))))
(define (st-put-token out tok)
  (let ([plain (st-unescape tok)])
    (if plain
        (begin (put-char out #\|) (put-string out plain) (put-char out #\|))
        (put-string out tok))))
(define (st-bar-symbols text)
  (let ([n (string-length text)])
    (let-values ([(out get) (open-string-output-port)])
      (let loop ([i 0])
        (when (fx< i n)
          (let* ([c (string-ref text i)]
                 [j (cond [(char=? c #\") (st-string-end text i)]
                          [(st-delimiter? c) (fx+ i 1)]
                          [else (st-token-end text i)])])
            (if (or (char=? c #\") (st-delimiter? c))
                (put-string out (substring text i j))
                (st-put-token out (substring text i j)))
            (loop j))))
      (get))))

(define (st-trace write-to)
  (when structured-trace
    (let ([p (open-file-output-port structured-trace (file-options no-fail no-truncate append)
                                    (buffer-mode block) (native-transcoder))])
      (write-to p) (close-port p))))

;; A generated top-level form, structured when it is a method's and the pass
;; can follow it.
(define (structure-form x)
  (if (and structured? (pair? x) (eq? (car x) 'set-top-level-value!) (list? x) (fx= 3 (length x)))
      (let* ([names (make-eq-hashtable)]
             [why (call/1cc (lambda (k) (st-scan (caddr x) names k) #f))])
        (st-count! 'methods 1)
        (if why
            (begin (st-count! (string->symbol (string-append "left-" (symbol->string why))) 1)
                   (st-trace (lambda (p) (put-string p ";; left as generated (") (put-string p (symbol->string why))
                                (put-string p "): ")
                                (put-string p (st-bar-symbols (call-with-string-output-port (lambda (q) (write (cadr (cadr x)) q)))))
                                (newline p)))
                   x)
            (let ([out (list (car x) (cadr x) (st-rewrite (caddr x) names))])
              (st-count! 'methods-structured 1)
              (st-count! 'blocks (hashtable-size names))
              (st-trace (lambda (p) (put-string p (st-bar-symbols (st-pretty out))) (newline p)))
              out)))
      x))
