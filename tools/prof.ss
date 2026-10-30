;; A sampling profiler over Java frames, for tools/prof.clj (bb prof).
;;
;; A thread of its own sends SIGPROF to the thread running the program every
;; interval; the handler walks the continuation as fillInStackTrace does
;; (reflect.ss raw-frames, java-frames-of) and counts the innermost Java
;; frame (self), every Java frame on the stack once (inclusive) and the
;; innermost Scheme code names under the handler (what a native or the
;; runtime was doing). Wall time, so a wait shows as samples in the frame
;; that waits. Thread-directed, not a process-wide interval timer: a
;; process-directed signal can land on jolt's shutdown-watcher thread, whose
;; sigwait then answered as if a signal had arrived and ended the process
;; with status 128 (jolt issue). Only the thread the profiler started on is
;; sampled.
(define prof-self (make-hashtable string-hash string=?))
(define prof-incl (make-hashtable string-hash string=?))
(define prof-code (make-hashtable string-hash string=?))
(define prof-samples 0)
(define prof-no-java 0)
(define prof-depth 4)
(define prof-own '("prof-sample!" "prof-handler"))
(define (prof-sample!)
  (set! prof-samples (fx+ prof-samples 1))
  (let* ([raw (call/1cc (lambda (k) (raw-frames k)))]
         [fs (java-frames-of raw)]
         [name (lambda (f) (string-append (car f) "." (cadr f)))]
         [bump! (lambda (t k) (hashtable-update! t k (lambda (n) (fx+ n 1)) 0))])
    ;; the code names below the handler's own frames and Chez's event dispatch ($...)
    (let loop ([raw raw] [skipping #t] [chain '()] [n 0])
      (cond [(or (null? raw) (fx= n prof-depth))
             (bump! prof-code (apply string-append (reverse chain)))]
            [else (let ([nm (#%$code-name (caar raw))])
                    (cond [(and skipping (or (not (string? nm)) (member nm prof-own)
                                             (and (fx> (string-length nm) 1) (char=? (string-ref nm 0) #\$))))
                           (loop (cdr raw) #t chain n)]
                          [else (loop (cdr raw) #f (cons (string-append (if (string? nm) nm "?") (if (fx= (fx+ n 1) prof-depth) "" " < ")) chain) (fx+ n 1))]))]))
    (if (null? fs)
        (set! prof-no-java (fx+ prof-no-java 1))
        (begin
          (bump! prof-self (name (car fs)))
          (let loop ([fs fs] [seen '()])
            (unless (null? fs)
              (let ([n (name (car fs))])
                (unless (member n seen) (bump! prof-incl n))
                (loop (cdr fs) (cons n seen)))))))))
(define (prof-handler sig) (prof-sample!))
(define prof-pthread-self (foreign-procedure "pthread_self" () uptr))
(define prof-pthread-kill (foreign-procedure "pthread_kill" (uptr int) int))
(define prof-usleep (foreign-procedure __collect_safe "usleep" (unsigned-32) int))
(define prof-target #f)
(define prof-interval 0)
(define (prof-start! us depth)
  (set! prof-target (prof-pthread-self))
  (set! prof-interval us)
  (set! prof-depth depth)
  (register-signal-handler 27 prof-handler)
  (fork-thread (lambda ()
                 (let loop ()
                   (when (fx> prof-interval 0)
                     (prof-usleep prof-interval)
                     (when (fx> prof-interval 0) (prof-pthread-kill prof-target 27))
                     (loop))))))
(define (prof-stop!) (set! prof-interval 0) (prof-usleep 20000))
(define (prof-rows t n)
  (let* ([cells (vector->list (hashtable-cells t))]
         [sorted (list-sort (lambda (a b) (> (cdr a) (cdr b))) cells)])
    (let loop ([s sorted] [i 0])
      (unless (or (null? s) (fx= i n))
        (printf "  ~6d ~5,1f% ~a\n" (cdar s) (/ (* 100.0 (cdar s)) (max 1 prof-samples)) (caar s))
        (loop (cdr s) (fx+ i 1))))))
(define (prof-report n)
  (with-output-to-string
    (lambda ()
      (printf "~a samples (~a with no Java frame)\n" prof-samples prof-no-java)
      (printf "-- self: the innermost Java frame\n") (prof-rows prof-self n)
      (printf "-- inclusive: every Java frame on the stack\n") (prof-rows prof-incl n)
      (printf "-- innermost code: what ran under it, outermost last\n") (prof-rows prof-code n))))
