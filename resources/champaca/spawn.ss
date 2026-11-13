;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Child processes: ProcessImpl.forkAndExec through posix_spawn, the reaper's
;; waitpid, destroy, and an empty ProcessHandle.Info.
;;
;; The JDK launches through jspawnhelper (POSIX_SPAWN, the default) or vfork,
;; with a child-side C program that sets up the descriptors, changes
;; directory, closes everything else and searches PATH. Here posix_spawn's
;; file actions do the setup, whatever launch mechanism the JDK asked for:
;; dup2 onto 0, 1 and 2, chdir (posix_spawn_file_actions_addchdir_np, macOS
;; 10.15 and glibc 2.29), and every other descriptor closed
;; (POSIX_SPAWN_CLOEXEC_DEFAULT on macOS, addclosefrom_np from glibc 2.34;
;; on glibc 2.33 only the pipes made here are close-on-exec, so a file the
;; parent holds open reaches the child there). PATH is the
;; parent's and is searched here as the JDK's JDK_execvpe does: a directory
;; where the program is missing is skipped, EACCES is kept for the end, and
;; a file the kernel can't run (ENOEXEC) runs through /bin/sh. SIGPIPE is
;; reset to its default in the child, since Chez ignores it and an ignored
;; signal stays ignored across exec, where a JVM's child gets the default.
;; The signal mask is emptied too.

(deflibc libc-posix-spawn "posix_spawn" #f (uptr uptr uptr uptr uptr uptr) int)
(deflibc libc-spawn-fa-init "posix_spawn_file_actions_init" #f (uptr) int)
(deflibc libc-spawn-fa-destroy "posix_spawn_file_actions_destroy" #f (uptr) int)
(deflibc libc-spawn-fa-dup2 "posix_spawn_file_actions_adddup2" #f (uptr int int) int)
(deflibc libc-spawn-fa-chdir "posix_spawn_file_actions_addchdir_np" #f (uptr uptr) int)
(deflibc libc-spawn-fa-inherit (if fs-macos? "posix_spawn_file_actions_addinherit_np" "no-inherit-np-here") #f (uptr int) int)
(define spawn-closefrom?
  (and fs-linux? fs-libc-loaded (foreign-entry? "posix_spawn_file_actions_addclosefrom_np")))
(deflibc libc-spawn-fa-closefrom (if spawn-closefrom? "posix_spawn_file_actions_addclosefrom_np" "no-closefrom-here") #f (uptr int) int)
(deflibc libc-spawnattr-init "posix_spawnattr_init" #f (uptr) int)
(deflibc libc-spawnattr-destroy "posix_spawnattr_destroy" #f (uptr) int)
(deflibc libc-spawnattr-setflags "posix_spawnattr_setflags" #f (uptr short) int)
(deflibc libc-spawnattr-setsigdefault "posix_spawnattr_setsigdefault" #f (uptr uptr) int)
(deflibc libc-spawnattr-setsigmask "posix_spawnattr_setsigmask" #f (uptr uptr) int)
(deflibc libc-sigemptyset "sigemptyset" #f (uptr) int)
(deflibc libc-sigaddset "sigaddset" #f (uptr int) int)
(deflibc libc-access "access" #f (uptr int) int)
(deflibc-blocking libc-waitpid-blocking "waitpid" (int uptr int) int)

;; posix_spawn_file_actions_t and posix_spawnattr_t are pointers on macOS and
;; structs on glibc (80 and 336 bytes); sigset_t is 4 bytes on macOS and 128
;; on glibc. One generous size covers them all.
(define spawn-opaque-size 512)
(define spawn-flags   ; POSIX_SPAWN_SETSIGDEF 4, SETSIGMASK 8; macOS CLOEXEC_DEFAULT #x4000
  (fxlogor 4 8 (if fs-macos? #x4000 0)))

;; a Java byte[] (possibly holding NULs) copied into fresh foreign memory, with
;; a NUL after it
(define (jbytes->foreign a)
  (let* ([v (jarr-vec a)] [n (vector-length v)] [p (foreign-alloc (fx+ n 1))])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (foreign-set! 'unsigned-8 p i (fxlogand (vector-ref v i) 255)))
    (foreign-set! 'unsigned-8 p n 0)
    p))
;; the bytes of a Java byte[] as a Scheme string, ProcessImpl's toCString
;; NUL dropped (paths are compared and joined, never decoded)
(define (jbytes->latin1 a)
  (let* ([v (jarr-vec a)] [n (let loop ([n (vector-length v)]) (if (and (fx> n 0) (fx= 0 (vector-ref v (fx- n 1)))) (loop (fx- n 1)) n))])
    (list->string (let loop ([i 0]) (if (fx= i n) '() (cons (integer->char (fxlogand (vector-ref v i) 255)) (loop (fx+ i 1))))))))
(define (latin1->foreign s)
  (let* ([n (string-length s)] [p (foreign-alloc (fx+ n 1))])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (foreign-set! 'unsigned-8 p i (char->integer (string-ref s i))))
    (foreign-set! 'unsigned-8 p n 0)
    p))
;; the addresses of the NUL-terminated strings in a block of count of them
(define (block-strings base count)
  (let loop ([i 0] [p base] [acc '()])
    (if (fx= i count) (reverse acc) (loop (fx+ i 1) (+ p (cstring-length p) 1) (cons p acc)))))
;; a NULL-terminated char* vector in fresh foreign memory
(define (pointer-vector ptrs)
  (let* ([w (foreign-sizeof 'uptr)] [v (foreign-alloc (fx* w (fx+ (length ptrs) 1)))])
    (let loop ([i 0] [ps ptrs])
      (if (null? ps)
          (begin (foreign-set! 'uptr v (fx* i w) 0) v)
          (begin (foreign-set! 'uptr v (fx* i w) (car ps)) (loop (fx+ i 1) (cdr ps)))))))

;; a pipe whose ends are close-on-exec and above 2, as (read . write), or
;; the errno. With 0, 1 or 2 closed in this process, pipe() hands those
;; out, and an end sitting on a standard descriptor would be overwritten by
;; another stream's dup2, or kept close-on-exec where it should be inherited;
;; F_DUPFD_CLOEXEC (67 on macOS, 1030 on Linux) moves it to 3 or above.
(define f-dupfd-cloexec (if fs-macos? 67 1030))
(define (high-cloexec fd)
  (if (fx< fd 3)
      (let ([moved (libc-fcntl-int fd f-dupfd-cloexec 3)])
        (libc-close fd)
        moved)
      (begin (libc-fcntl-int fd 2 1) fd)))   ; F_SETFD, FD_CLOEXEC
(define (cloexec-pipe)
  (let ([fds (foreign-alloc 8)])
    (let* ([r (libc-pipe fds)] [e (errno)] [rd (foreign-ref 'int fds 0)] [wr (foreign-ref 'int fds 4)])
      (foreign-free fds)
      (if (< r 0) e (cons (high-cloexec rd) (high-cloexec wr))))))

;; the raw bytes of a variable in this process's environment, as a Latin-1
;; string (one char per byte, so a path round-trips to the same bytes), or #f
;; whether the environment entry at p is name=...
(define (environ-entry-for? p name)
  (let ([n (string-length name)])
    (and (let match ([j 0]) (or (fx= j n) (and (fx= (foreign-ref 'unsigned-8 p j) (char->integer (string-ref name j))) (match (fx+ j 1)))))
         (fx= (foreign-ref 'unsigned-8 p n) 61))))
;; the bytes after name= in the entry at p, one character each
(define (environ-entry-value p name)
  (let* ([start (+ p (string-length name) 1)] [len (cstring-length start)])
    (list->string (let collect ([k 0]) (if (fx= k len) '() (cons (integer->char (foreign-ref 'unsigned-8 start k)) (collect (fx+ k 1))))))))
(define (environ-bytes name)
  (let ([envp (environ-pointer)] [w (foreign-sizeof 'uptr)])
    (let loop ([i 0])
      (let ([p (if (eqv? envp 0) 0 (foreign-ref 'uptr envp (fx* i w)))])
        (cond [(eqv? p 0) #f]
              [(environ-entry-for? p name) (environ-entry-value p name)]
              [else (loop (fx+ i 1))])))))

;; dir/prog, prog itself (the working directory's) for an empty dir
(define (path-candidate dir prog)
  (if (fx= (string-length dir) 0)
      prog
      (string-append dir (if (char=? (string-ref dir (fx- (string-length dir) 1)) #\/) "" "/") prog)))

;; the entries of a colon-separated path, in order, empty ones kept
(define (path-entries path)
  (let split ([path path] [acc '()])
    (let ([colon (char-index-of path #\:)])
      (if colon
          (split (substring path (fx+ colon 1) (string-length path)) (cons (substring path 0 colon) acc))
          (reverse (cons path acc))))))

;; the candidates JDK_execvpe tries for prog: prog itself when it has a
;; slash, else each directory of this process's PATH (an empty entry is the
;; working directory)
(define (spawn-candidates prog)
  (if (or (fx= (string-length prog) 0) (char-index-of prog #\/))
      (list prog)
      (map (lambda (dir) (path-candidate dir prog))
           (path-entries (or (environ-bytes "PATH") ":/bin:/usr/bin")))))

;; what every candidate of one process start is spawned with: posix_spawn's
;; file actions and attributes, argv0, the other arguments and envp
(define-record-type spawn-call (fields fa attr argv0 arg-ptrs envp))
;; spawn path with sc's argv0 and the other arguments (argv0 is the program
;; as the caller named it, never the path found, as JDK_execvpe keeps it)
;; and envp; the pid, or (error . errno). ENOEXEC runs it as /bin/sh path
;; args, as the JDK's execve_with_shell_fallback.
(define (spawn-one sc path)
  (define fa (spawn-call-fa sc))
  (define attr (spawn-call-attr sc))
  (define arg-ptrs (spawn-call-arg-ptrs sc))
  (define envp (spawn-call-envp sc))
  (let* ([cpath (latin1->foreign path)]
         [argv (pointer-vector (cons (spawn-call-argv0 sc) arg-ptrs))]
         [pidp (foreign-alloc 4)]
         [r (libc-posix-spawn pidp cpath fa attr argv envp)]
         [r (if (fx= r 8)   ; ENOEXEC, 8 on macOS and Linux
                (let* ([sh (latin1->foreign "/bin/sh")] [shargv (pointer-vector (cons sh (cons cpath arg-ptrs)))]
                       [r2 (libc-posix-spawn pidp sh fa attr shargv envp)])
                  (foreign-free sh) (foreign-free shargv) r2)
                r)]
         [pid (foreign-ref 'int pidp 0)])
    (foreign-free cpath) (foreign-free argv) (foreign-free pidp)
    (if (fx= r 0) pid (cons 'error r))))

;; errno of access(path, X_OK), or 0. Each candidate is checked before it is
;; spawned, and the directory before anything: a glibc posix_spawn reports an
;; exec or chdir failure as its result, but one running under an emulator
;; (Docker's x86_64 on an arm64 Mac, probed) returns 0 and the child exits
;; 127, so the search can't rely on it. The ENOEXEC fallback still does: under
;; that emulator a script without #! exits 127 where it should run through
;; /bin/sh (ProcessCheck's "no shebang" row; native arm64 and macOS pass).
;; access checks the real uid where exec checks the effective one, which
;; only differs in a setuid process.
(define (access-errno path)
  (let* ([c (latin1->foreign path)] [r (libc-access c 1)] [e (errno)])
    (foreign-free c)
    (if (< r 0) e 0)))
;; a relative candidate is checked where the child will look for it
(define (in-dir dir path)
  (if (or (not dir) (and (fx> (string-length path) 0) (char=? (string-ref path 0) #\/))) path (string-append dir "/" path)))
(define (spawn-search sc prog dir)
  (let loop ([cs (spawn-candidates prog)] [sticky #f])
    (if (null? cs)
        (cons 'error (or sticky 2))
        (let* ([e (access-errno (in-dir dir (car cs)))]
               [r (if (fx= e 0) (spawn-one sc (car cs)) (cons 'error e))])
          (cond [(fixnum? r) r]
                [(null? (cdr cs)) (if (and sticky (memv (cdr r) '(2 20))) (cons 'error sticky) r)]
                [(memv (cdr r) '(2 20)) (loop (cdr cs) sticky)]   ; ENOENT, ENOTDIR: next directory
                [(fx= (cdr r) 13) (loop (cdr cs) 13)]             ; EACCES: kept, the search goes on
                [else r])))))

;; the pipes asked for (fd -1), made in order, as (child . parent) per
;; stream, #f for a stream given an fd; a failure closes the ones made and
;; throws as ProcessImpl_md.c does
(define (spawn-pipes given)
  (let loop ([i 0] [gs given] [acc '()])
    (if (null? gs)
        (reverse acc)
        (let ([p (and (fx= (car gs) -1) (cloexec-pipe))])
          (if (fixnum? p)
              (begin (for-each (lambda (q) (when q (libc-close (car q)) (libc-close (cdr q)))) acc)
                     (jthrow "java/io/IOException" (string-append "error=" (number->string p) ", " (strerror-text p))))
              (loop (fx+ i 1) (cdr gs)
                    (cons (and p (if (fx= i 0) p (cons (cdr p) (car p)))) acc)))))))

(defnative "java/lang/ProcessImpl.forkAndExec(I[B[B[BI[BI[B[IZ)I"
  (this mode helper prog arg-block argc env-block envc dir fds redirect-err)
  (let* ([fdv (jarr-vec (nn fds))]
         [given (vector->list fdv)]
         [pipes (spawn-pipes given)]
         [close-pipes! (lambda () (for-each (lambda (p) (when p (libc-close (car p)) (libc-close (cdr p)))) pipes))]
         ;; the fd the child gets as each of 0, 1 and 2; stderr from stdout when merged
         [child (map (lambda (i p fd) (cond [(and (fx= i 2) (eqv? redirect-err 1)) 1] [p (car p)] [else fd])) '(0 1 2) pipes given)]
         [fa (foreign-alloc spawn-opaque-size)] [attr (foreign-alloc spawn-opaque-size)]
         [sigs (foreign-alloc spawn-opaque-size)]
         [held '()]
         [hold! (lambda (p) (set! held (cons p held)) p)]
         [release! (lambda ()
                     (libc-spawn-fa-destroy fa) (libc-spawnattr-destroy attr)
                     (for-each foreign-free (list* fa attr sigs held))
                     (set! held '()))])
    (libc-spawn-fa-init fa)
    (libc-spawnattr-init attr)
    (let ([r (guard (e [#t (release!) (close-pipes!) (raise e)])
               (let* ([cprog (jbytes->latin1 (nn prog))]
                      [argv0 (hold! (jbytes->foreign prog))]
                      [args (and arg-block (hold! (jbytes->foreign arg-block)))]
                      [arg-ptrs (if args (block-strings args argc) '())]
                      [env (and env-block (hold! (jbytes->foreign env-block)))]
                      [envp (if env (hold! (pointer-vector (block-strings env envc))) (environ-pointer))]
                      [ldir (and dir (jbytes->latin1 dir))]
                      [cdir (and dir (hold! (jbytes->foreign dir)))])
                 (libc-spawnattr-setflags attr spawn-flags)
                 (libc-sigemptyset sigs) (libc-spawnattr-setsigmask attr sigs)
                 (libc-sigaddset sigs 13) (libc-spawnattr-setsigdefault attr sigs)   ; SIGPIPE
                 (for-each (lambda (i c)
                             (if (fx= c i)
                                 (when fs-macos? (libc-spawn-fa-inherit fa i))
                                 (libc-spawn-fa-dup2 fa c i)))
                           '(0 1 2) child)
                 (when cdir (libc-spawn-fa-chdir fa cdir))
                 (when spawn-closefrom? (libc-spawn-fa-closefrom fa 3))
                 ;; a child writing straight to our stdout or stderr comes after
                 ;; what was printed before it, as with the JVM's flushed
                 ;; System.out; Java's writes are flushed already, so this
                 ;; is for jolt's own buffered output
                 (when (or (memv 1 child) (memv 2 child)) (console-flush 1))
                 (let ([e (if ldir (access-errno ldir) 0)])
                   (if (fx= e 0) (spawn-search (make-spawn-call fa attr argv0 arg-ptrs envp) cprog ldir) (cons 'error e)))))])
      (release!)
      (if (pair? r)
          (begin (close-pipes!)
                 (jthrow "java/io/IOException"
                         (string-append "Exec failed, error: " (number->string (cdr r)) " (" (strerror-text (cdr r)) ") ")))
          (begin
            ;; the parent keeps its ends and closes the child's
            (for-each (lambda (i p)
                        (vector-set! fdv i (if p (cdr p) -1))
                        (when p (libc-close (car p))))
                      '(0 1 2) pipes)
            r)))))

;; The exit code as ProcessHandleImpl wants it: the status of a normal exit,
;; 0x80 plus the signal for one killed by a signal. Reaping waits in waitpid
;; with the GIL given up (the JDK's reaper thread sits there). Without
;; reaping (a ProcessHandle's onExit) the answer stays -2, NOT_A_CHILD, so
;; ProcessHandleImpl polls isAlive0: the JDK's waitid with WNOWAIT is only
;; reached for a child no ProcessImpl started, since a ProcessImpl's own
;; reaping completion is registered first and shared.
(defnative "java/lang/ProcessHandleImpl.waitForProcessExit0(JZ)I" (pid reap)
  (if (eqv? reap 1)
      (let ([st (foreign-alloc 4)])
        (let loop ()
          (let* ([r (released-call* (lambda () (libc-waitpid-blocking pid st 0)))] [e (errno)])
            (cond [(>= r 0)
                   (let ([s (foreign-ref 'int st 0)])
                     (foreign-free st)
                     (cond [(fx= (fxlogand s #x7f) 0) (fxlogand (fxsra s 8) #xff)]
                           [(not (fx= (fxlogand s #x7f) #x7f)) (fx+ #x80 (fxlogand s #x7f))]
                           [else s]))]
                  [(fx= e 4) (loop)]
                  [else (foreign-free st) (if (fx= e 10) -2 -1)]))))   ; ECHILD 10
      -2))


;; --- ProcessHandle: start times, parents, children and Info ----------------------
;; As the JDK's ProcessHandleImpl natives read them. A process is known by
;; its parent, its start time in milliseconds since the epoch and its CPU
;; time in nanoseconds (-1 unknown). macOS: sysctl KERN_PROC_PID's struct
;; kinfo_proc (648 bytes: p_starttime's seconds at 0 and 32-bit microseconds
;; at 8, p_pid at 40, cr_uid at 420, e_ppid at 560, the same on arm64 and
;; x86_64), which still answers for a zombie, and CPU time only for this
;; process (getrusage). Linux: /proc/<pid>/stat's ppid, utime, stime and
;; starttime after the parenthesized command, in clock ticks since the boot
;; time /proc/stat gives. A pid is a positive int; anything else is no
;; process.
(define (pid-ok? pid) (and (>= pid 1) (<= pid 2147483647)))
(deflibc libc-sysctl "sysctl" #f (uptr unsigned-32 uptr uptr uptr size_t) int)
(deflibc libc-sysconf "sysconf" #f (int) long)
(define kinfo-size 648)
(define (sysctl-failed e) (jthrow "java/lang/RuntimeException" (strerror-text e)))
(define (kinfo-start kp) (+ (* 1000 (foreign-ref 'integer-64 kp 0)) (quotient (foreign-ref 'integer-32 kp 8) 1000)))
(define (with-mib ints proc)
  (let ([mib (foreign-alloc (fx* 4 (length ints)))])
    (let loop ([i 0] [xs ints]) (unless (null? xs) (foreign-set! 'int mib (fx* 4 i) (car xs)) (loop (fx+ i 1) (cdr xs))))
    (let ([r (proc mib (length ints))]) (foreign-free mib) r)))
;; (ppid start uid) of pid, or #f when there is no such process
(define (macos-proc pid)
  (with-mib (list 1 14 1 pid)   ; CTL_KERN KERN_PROC KERN_PROC_PID
    (lambda (mib n)
      (let ([kp (foreign-alloc kinfo-size)] [len (foreign-alloc 8)])
        (foreign-set! 'unsigned-64 len 0 kinfo-size)
        (let* ([r (libc-sysctl mib n kp len 0 0)] [e (errno)]
               [got (foreign-ref 'unsigned-64 len 0)]
               [ans (and (>= r 0) (> got 0) (fx= (foreign-ref 'int kp 40) pid)
                         (list (foreign-ref 'int kp 560) (kinfo-start kp) (foreign-ref 'unsigned-32 kp 420)))])
          (foreign-free kp) (foreign-free len)
          (when (< r 0) (sysctl-failed e))
          ans)))))
(define clock-ticks (and (not fs-macos?) (libc-sysconf 2)))   ; _SC_CLK_TCK
(define boot-ms #f)
(define (linux-boot-ms)
  (or boot-ms
      (let ([ms (guard (e [#t 0])
                  (let loop ([lines (call-with-port (open-input-file "/proc/stat")
                                      (lambda (p) (let get ([acc '()]) (let ([l (get-line p)]) (if (eof-object? l) (reverse acc) (get (cons l acc)))))))])
                    (cond [(null? lines) 0]
                          [(and (fx> (string-length (car lines)) 6) (string=? (substring (car lines) 0 6) "btime "))
                           (* 1000 (string->number (substring (car lines) 6 (string-length (car lines)))))]
                          [else (loop (cdr lines))])))])
        (set! boot-ms ms)
        ms)))
;; (ppid start total) of pid from /proc/<pid>/stat, or #f
(define (linux-proc pid)
  (guard (e [#t #f])
    (let* ([text (call-with-port (open-input-file (string-append "/proc/" (number->string pid) "/stat")) get-string-all)]
           [close (let loop ([i (fx- (string-length text) 1)]) (if (char=? (string-ref text i) #\)) i (loop (fx- i 1))))]
           [fields (let split ([i (fx+ close 1)] [start #f] [acc '()])
                     (cond [(fx= i (string-length text)) (reverse (if start (cons (substring text start i) acc) acc))]
                           [(char-whitespace? (string-ref text i))
                            (split (fx+ i 1) #f (if start (cons (substring text start i) acc) acc))]
                           [else (split (fx+ i 1) (or start i) acc)]))]
           [num (lambda (k) (string->number (list-ref fields k)))])
      ;; state 0, ppid 1, utime 11, stime 12, starttime 19
      (list (num 1)
            (+ (linux-boot-ms) (quotient (* (num 19) 1000) clock-ticks))
            (* (+ (num 11) (num 12)) (quotient 1000000000 clock-ticks))))))
(define (self-cpu-ns)   ; getrusage(RUSAGE_SELF): user + system time
  (let ([ru (foreign-alloc 144)])
    (let ([ns (and (fx= 0 ((foreign-procedure "getrusage" (int uptr) int) 0 ru))
                   (* 1000 (+ (* 1000000 (+ (foreign-ref 'integer-64 ru 0) (foreign-ref 'integer-64 ru 16)))
                              (foreign-ref 'integer-32 ru 8) (foreign-ref 'integer-32 ru 24))))])
      (foreign-free ru)
      (or ns -1))))
;; (values ppid start total) as os_getParentPidAndTimings: ppid -1 when gone
(define (process-timings pid)
  (cond [(not (pid-ok? pid)) (values -1 0 -1)]
        [fs-macos? (let ([p (macos-proc pid)])
                     (if p
                         (values (car p) (cadr p) (if (= pid (libc-getpid)) (self-cpu-ns) -1))
                         (values -1 0 -1)))]
        [else (let ([p (linux-proc pid)])
                (if p (values (car p) (cadr p) (caddr p)) (values -1 0 -1)))]))

(defnative "java/lang/ProcessHandleImpl.isAlive0(J)J" (pid)
  (let-values ([(ppid start total) (process-timings pid)]) (if (< ppid 0) -1 start)))
(defnative "java/lang/ProcessHandleImpl.parent0(JJ)J" (pid start-time)
  (if (= pid (libc-getpid))
      (libc-getppid)
      (let-values ([(ppid start total) (process-timings pid)])
        (if (and (not (= start start-time)) (not (eqv? start 0)) (not (eqv? start-time 0))) -1 ppid))))
;; SIGKILL or SIGTERM, only when the start time still matches (0 is unknown)
(defnative "java/lang/ProcessHandleImpl.destroy0(JJZ)Z" (pid start-time force)
  (let ([start (let-values ([(ppid start total) (process-timings pid)]) (if (< ppid 0) -1 start))])
    (if (and (pid-ok? pid) (or (= start start-time) (eqv? start 0) (eqv? start-time 0)))
        (if (eqv? 0 (libc-kill pid (if (eqv? force 1) 9 15))) 1 0)
        0)))

;; every process (pid 0) or pid's children, as (pid ppid start) lists
;; (pid ppid start) of each of the count kinfo_proc structs at buf
(define (kinfo-procs buf count)
  (let loop ([i 0] [acc '()])
    (if (fx= i count)
        (reverse acc)
        (let ([kp (+ buf (* i kinfo-size))])
          (loop (fx+ i 1) (cons (list (foreign-ref 'int kp 40) (foreign-ref 'int kp 560) (kinfo-start kp)) acc))))))
(define (macos-all-procs)
  (with-mib (list 1 14 0 0)   ; KERN_PROC_ALL
    (lambda (mib n)
      (let ([len (foreign-alloc 8)])
        (let retry ([tries 100])
          (foreign-set! 'unsigned-64 len 0 0)
          (when (< (libc-sysctl mib n 0 len 0 0) 0) (let ([e (errno)]) (foreign-free len) (sysctl-failed e)))
          (let* ([size (+ (foreign-ref 'unsigned-64 len 0) (fx* 4 kinfo-size))] [buf (foreign-alloc size)])
            (foreign-set! 'unsigned-64 len 0 size)
            (let* ([r (libc-sysctl mib n buf len 0 0)] [e (errno)])
              (cond [(and (< r 0) (fx= e 12) (fx> tries 0)) (foreign-free buf) (retry (fx- tries 1))]   ; ENOMEM
                    [(< r 0) (foreign-free buf) (foreign-free len) (sysctl-failed e)]
                    [else
                     (let ([procs (kinfo-procs buf (quotient (foreign-ref 'unsigned-64 len 0) kinfo-size))])
                       (foreign-free buf) (foreign-free len)
                       procs)]))))))))
(define (linux-all-procs)
  (filter (lambda (x) x)
          (map (lambda (name)
                 (let ([pid (string->number name)])
                   (and pid (exact? pid) (fx> pid 0)
                        (let ([p (linux-proc pid)]) (and p (>= (car p) 0) (list pid (car p) (cadr p)))))))
               (guard (e [#t (jthrow "java/lang/RuntimeException" "Unable to open /proc")]) (directory-list "/proc")))))
(defnative "java/lang/ProcessHandleImpl.getProcessPids0(J[J[J[J)I" (pid pids ppids stimes)
  (let ([n (jarraylength pids)])
    (if (or (and ppids (not (fx= n (jarraylength ppids)))) (and stimes (not (fx= n (jarraylength stimes)))))
        (jthrow "java/lang/IllegalArgumentException" "array sizes not equal")
        (let loop ([ps (if fs-macos? (macos-all-procs) (linux-all-procs))] [count 0])
          (cond [(null? ps) count]
                [(or (eqv? pid 0) (= (cadr (car ps)) pid))
                 (when (fx< count n)
                   (vector-set! (jarr-vec pids) count (car (car ps)))
                   (when ppids (vector-set! (jarr-vec ppids) count (cadr (car ps))))
                   (when stimes (vector-set! (jarr-vec stimes) count (caddr (car ps)))))
                 (loop (cdr ps) (fx+ count 1))]
                [else (loop (cdr ps) count)])))))

;; Info: start and CPU times when the process is there, then its user and
;; command line as the JDK's os_getCmdlineAndUserInfo finds them: on macOS
;; the uid from kinfo_proc and the executable and argv from KERN_PROCARGS2
;; (refused for another user's process, then left out); on Linux the owner
;; of /proc/<pid>, the executable from /proc/<pid>/exe (ours only) and argv
;; from /proc/<pid>/cmdline, with the whole line as commandLine when the
;; executable can't be read or the line was cut at a page.
(define info-class "java/lang/ProcessHandleImpl$Info")
(define (info-set! info name v) (jslot-set! info (jfield-index info-class name) v))
(define (user-name uid)
  (let ([p (libc-getpwuid uid)])
    (and (not (eqv? p 0))
         (let ([name (c-string-text (foreign-ref 'uptr p 0))]) (and (fx> (string-length name) 0) name)))))
(define (bytes-text bv start end) (utf8->string (let ([b (make-bytevector (fx- end start))]) (bytevector-copy! bv start b 0 (fx- end start)) b)))
(define (bytes-nul bv i end) (let loop ([j i]) (if (or (fx>= j end) (fx= 0 (bytevector-u8-ref bv j))) j (loop (fx+ j 1)))))
;; unix_fillArgArray: the command, then nargs - 1 arguments after argv[0];
;; an empty argument or the end of the buffer leaves arguments unset
(define (fill-args! info nargs bv cp end command command-line)
  (info-set! info "command" (and command (jstring command)))
  (when (fx>= nargs 1)
    (let loop ([i 0] [cp cp] [acc '()])
      (if (fx= i (fx- nargs 1))
          (info-set! info "arguments" (make-jarr "Ljava/lang/String;" (list->vector (map jstring (reverse acc)))))
          (let ([cp (fx+ (bytes-nul bv cp end) 1)])
            (unless (or (fx>= cp end) (fx= 0 (bytevector-u8-ref bv cp)))
              (loop (fx+ i 1) cp (cons (bytes-text bv cp (bytes-nul bv cp end)) acc)))))))
  (when command-line (info-set! info "commandLine" (jstring command-line))))
(define (foreign->bytevector p n)
  (let ([bv (make-bytevector n)]) (do ([i 0 (fx+ i 1)]) ((fx= i n) bv) (bytevector-u8-set! bv i (foreign-ref 'unsigned-8 p i)))))
;; Info's user, from a uid when there is one with a name
(define (set-info-user! info uid)
  (let ([name (and uid (>= uid 0) (user-name uid))])
    (when name (info-set! info "user" (jstring name)))))

;; macOS: sysctl KERN_ARGMAX, the size KERN_PROCARGS2 needs
(define (kern-argmax)
  (with-mib (list 1 8)
    (lambda (mib n)
      (let ([v (foreign-alloc 4)] [len (foreign-alloc 8)])
        (foreign-set! 'unsigned-64 len 0 4)
        (let* ([r (libc-sysctl mib n v len 0 0)] [e (errno)] [m (foreign-ref 'int v 0)])
          (foreign-free v) (foreign-free len)
          (when (< r 0) (sysctl-failed e))
          m)))))
;; KERN_PROCARGS2 for pid as a bytevector, or #f when the kernel gives none
;; (EINVAL or EIO: another user's process, or one that is gone)
(define (kern-procargs pid maxargs)
  (with-mib (list 1 49 pid)
    (lambda (mib n)
      (let ([args (foreign-alloc maxargs)] [len (foreign-alloc 8)])
        (foreign-set! 'unsigned-64 len 0 maxargs)
        (let* ([r (libc-sysctl mib n args len 0 0)] [e (errno)] [size (foreign-ref 'unsigned-64 len 0)])
          (let ([bv (and (>= r 0) (foreign->bytevector args size))])
            (foreign-free args) (foreign-free len)
            (when (and (< r 0) (not (memv e '(22 5)))) (sysctl-failed e))
            bv))))))
;; KERN_PROCARGS2's layout: argc, the executable's path, NULs up to argv[0]
(define (fill-from-procargs! info bv)
  (let ([size (bytevector-length bv)])
    (when (fx>= size 4)
      (let* ([exe-end (bytes-nul bv 4 size)]
             [cp (let skip ([j exe-end]) (if (and (fx< j size) (fx= 0 (bytevector-u8-ref bv j))) (skip (fx+ j 1)) j))])
        (fill-args! info (bytevector-s32-native-ref bv 0) bv cp size (bytes-text bv 4 exe-end) #f)))))
(define (macos-command-info! info pid)
  (set-info-user! info (let ([p (macos-proc pid)]) (and p (caddr p))))
  (let ([bv (kern-procargs pid (kern-argmax))])
    (when bv (fill-from-procargs! info bv))))

;; Linux: /proc/<pid>/cmdline, at most a page as the kernel shows it
(define (read-cmdline dir page)
  (let ([cmdline (guard (e [#t #f]) (call-with-port (open-file-input-port (string-append dir "/cmdline")) get-bytevector-all))])
    (cond [(not cmdline) #f]
          [(eof-object? cmdline) (make-bytevector 0)]
          [(fx> (bytevector-length cmdline) page)
           (let ([b (make-bytevector page)]) (bytevector-copy! cmdline 0 b 0 page) b)]
          [else cmdline])))
;; /proc/<pid>/exe's target, readable for a process of ours only
(define (read-exe dir)
  (let* ([path (c-string (string-append dir "/exe"))] [buf (foreign-alloc 4097)] [n (libc-readlink path buf 4096)])
    (let ([s (and (> n 0) (bytes-text (foreign->bytevector buf n) 0 n))])
      (foreign-free buf) (foreign-free path)
      s)))
;; the arguments with their NULs as spaces, as the JDK's commandLine
(define (cmdline-as-line bv len)
  (let ([b (bytevector-copy bv)])
    (do ([i 0 (fx+ i 1)]) ((fx= i len)) (when (fx= 0 (bytevector-u8-ref b i)) (bytevector-u8-set! b i 32)))
    (bytes-text b 0 (bytes-nul b 0 len))))
;; the NUL-separated arguments up to the first empty one
(define (cmdline-count bv len)
  (let count ([s 0] [i 0])
    (if (or (fx>= s len) (fx= 0 (bytevector-u8-ref bv s))) i (count (fx+ (bytes-nul bv s len) 1) (fx+ i 1)))))
(define (linux-command-info! info pid)
  (let ([dir (string-append "/proc/" (number->string pid))])
    (when (and fs-stat-layout? (fx= 0 (libc-stat-string dir fs-stat-buf)))
      (set-info-user! info (fs-ref fs-stat-buf 'st_uid)))
    (let* ([page (libc-sysconf 30)]   ; _SC_PAGESIZE
           [bv (read-cmdline dir page)])
      (when bv
        (let* ([len (bytevector-length bv)]
               [exe (read-exe dir)]
               [truncated (and (fx= len page) (not (fx= 0 (bytevector-u8-ref bv (fx- page 1)))))]
               [line (and (fx> len 0) (or (not exe) truncated) (cmdline-as-line bv len))])
          (fill-args! info (if truncated 0 (cmdline-count bv len)) bv 0 len exe line))))))
(defnative "java/lang/ProcessHandleImpl$Info.initIDs()V" () (void))
(defnative "java/lang/ProcessHandleImpl$Info.info0(J)V" (this pid)
  (let-values ([(ppid start total) (process-timings pid)])
    (when (>= ppid 0)
      (info-set! this "totalTime" total)
      (info-set! this "startTime" start)))
  (when (pid-ok? pid)
    (if fs-macos? (macos-command-info! this pid) (linux-command-info! this pid))))
