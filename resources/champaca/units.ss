;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Compiling generated code: champaca's globals as their own top-level labels,
;; method text evaluated or compiled into units, the startup cache's unit files
;; and the method stubs. Split from runtime.ss; loaded right after it.

;; --- champaca's globals are their own top-level labels ------------------------------
;; In a mutable environment such as jolt's, Chez gives a top-level variable a
;; label gensym, #{Name *top*:Name}, on its first definition or reference, and
;; the variable's value lives in that gensym. Its unique name is a fresh
;; string, "*top*:" and the whole name, so the 65,000 globals of a warm Spring
;; Boot start (one per method, static field and conflict stub) held 20 MB of
;; them (spike 76). A symbol whose *top* property is the symbol itself is its
;; own label, as every symbol of Chez's scheme environment is, and its value
;; sits in its own slot: no gensym. Every form champaca generates is walked
;; before it is expanded, so a global is labelled before anything compiled
;; refers to it, in every process alike (a cached unit refers to the label it
;; was compiled against); a symbol that already has a label keeps it, and one
;; bound at the top level already (a Scheme or jolt procedure: its value in its
;; own slot) is left alone. A global's name holds "(" (a method) or ":" (a
;; field, a conflict stub); a local of the same shape (a block's name) gets a
;; property nothing reads.
(define (champaca-global-name? s)
  (and (symbol? s) (not (gensym? s))
       (let ([n (symbol->string s)])
         (let loop ([i 0])
           (and (fx< i (string-length n))
                (or (memv (string-ref n i) '(#\( #\:)) (loop (fx+ i 1))))))))
;; $sputprop is a plain read-modify-write of the property list, where Chez's
;; own label updates hold the tc-mutex; champaca's callers hold the GIL
(define (self-label! s)
  (unless (or (#%$sgetprop s '*top* #f) (#%$top-level-bound? s))
    (#%$sputprop s '*top* s)))
(define (self-label-form! x)
  (cond [(pair? x) (self-label-form! (car x)) (self-label-form! (cdr x))]
        [(vector? x) (vector-for-each self-label-form! x)]
        [(champaca-global-name? x) (self-label! x)]
        [else (void)]))
(define (define-global! sym v)
  (when (champaca-global-name? sym) (self-label! sym))
  (set-top-level-value! sym v))
;; top-level-bound? for a global that may not be defined yet (a class with no
;; <clinit>): Chez's own makes it a label gensym on the way
(define (global-bound? s)
  (when (champaca-global-name? s) (self-label! s))
  (top-level-bound? s))

;; A method's text evaluated outside the cache paths (jit.clj
;; compile-method!), with inspector information as lines? decided, where
;; the global setting above would keep it for every method
(define (eval-method-text text lines?)
  (parameterize ([generate-inspector-information lines?])
    (let ([p (open-string-input-port text)])
      (let loop ([last (void)])
        (let ([form (read p)])
          (if (eof-object? form) last (begin (self-label-form! form) (loop (eval (structure-form form))))))))))
(define (interpret-string s)
  (let ([p (open-input-string s)])
    (let loop ([r (void)])
      (let ([x (read p)])
        (if (eof-object? x) r (begin (self-label-form! x) (loop (interpret (structure-form x)))))))))

;; compile-file announces itself on stdout, which belongs to the Java program.
;; The cache's code carries no inspector information (free-variable names,
;; source): it nearly doubled boot.so (12.1 MB -> 6.8 MB without) and the time
;; to load it. Procedure names stay, and getCallerClass reads those. Stack
;; traces read a frame's line from that information (reflect.ss), so JDK
;; frames, whose code the cache holds, have none: keeping it for them made a
;; 6.5 MB cache of 4.2 and a warm start 0.02 to 0.04 s slower (Hello 0.30 to
;; 0.32 s, JdkLib 0.34 to 0.38 s, medians of 5 alternating rounds).
(define (quiet-compile-file in out)
  (parameterize ([current-output-port (open-output-string)]
                 [generate-inspector-information #f])
    (compile-file in out)))

;; Generations after boot are stored unit by unit: one class's registration,
;; or one method body. Each unit is compiled on its own and appended to the
;; generation's file, so a later run can load just the units it reaches.
;; add-unit! returns the unit's start offset; end-units! the file's length.
(define unit-out #f)

(define (begin-units! path)
  (set! unit-out (open-file-output-port path (file-options no-fail))))

(define (read-all text)   ; generated forms, their globals labelled (self-label-form!)
  (let ([in (open-input-string text)])
    (let loop ([forms '()])
      (let ([x (read in)])
        (if (eof-object? x) (reverse forms) (begin (self-label-form! x) (loop (cons (structure-form x) forms))))))))

;; inspect: keep inspector information, as a classpath class's method bodies
;; do, since stack traces read their line numbers from it (JDK code goes
;; without: see quiet-compile-file)
(define (add-unit! text . inspect)
  (let ([start (port-position unit-out)])
    (parameterize ([generate-inspector-information (and (pair? inspect) (car inspect))])
      (compile-to-port (read-all text) unit-out))
    start))

;; a unit compile-unit already compiled, appended as it is
(define (add-fasl! bv)
  (let ([start (port-position unit-out)])
    (put-bytevector unit-out bv)
    start))

;; Compile text once to object code, run it, and return the code's bytes for
;; the startup cache, which appends them unchanged (add-fasl!). Evaluating the
;; text and compiling it again for the cache cost a second compile per method.
(define (compile-unit text . inspect)   ; inspect as for add-unit!
  (let-values ([(p get) (open-bytevector-output-port)])
    (parameterize ([generate-inspector-information (and (pair? inspect) (car inspect))])
      (compile-to-port (read-all text) p))
    (let ([bv (get)])
      (load-compiled-from-port (open-bytevector-input-port bv))
      bv)))

(define (end-units!)
  (let ([n (port-position unit-out)])
    (close-port unit-out)
    (set! unit-out #f)
    n))

;; Generation files stay open once read; a unit is a slice of one.
(define unit-ports (make-hashtable string-hash string=?))

(define (load-unit path off len)
  (let ([p (or (hashtable-ref unit-ports path #f)
               (let ([p (open-file-input-port path)])
                 (hashtable-set! unit-ports path p)
                 p))])
    (set-port-position! p off)
    (load-compiled-from-port (open-bytevector-input-port (get-bytevector-n p len)))
    (void)))

;; One compiled procedure makes every method stub, so registration text (and
;; the boot cache built from it) carries a call per method, not a lambda. The
;; first call compiles the method, which replaces the stub's global; anything
;; still holding the stub after that is forwarded. Stubs are recorded so an
;; inline cache never keeps one.
(define stub-procs (make-weak-eq-hashtable))
(define (method-stub cname mname desc sym)
  (or (native-override (string-append cname "." mname desc))
      (let* ([compiled? #f]
             [p (lambda args
                  (unless compiled?
                    (jit! cname mname desc)
                    (set! compiled? #t))
                  (apply (top-level-value sym) args))])
        (eq-hashtable-set! stub-procs p #t)
        p)))

;; A class's method definitions from one form: ms holds (name desc symbol
;; native?) per method. One top-level define per method made compiling the
;; startup cache most of a cold start, Chez's cost per top-level form being
;; far above that of a quoted list.
(define (define-methods! cname ms)
  (for-each (lambda (m)
              (let ([mname (car m)] [desc (cadr m)] [sym (caddr m)])
                (define-global! sym
                  (if (cadddr m)
                      (native-ref (string-append cname "." mname desc))
                      (method-stub cname mname desc sym)))))
            ms))

;; "java.lang.String" -> "java/lang/String"
(define (slashes s) (list->string (map (lambda (c) (if (char=? c #\.) #\/ c)) (string->list s))))
(define (slashes->dots s) (list->string (map (lambda (c) (if (char=? c #\/) #\. c)) (string->list s))))
