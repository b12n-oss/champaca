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
(define (environ-bytes name)
  (let ([envp (environ-pointer)] [n (string-length name)] [w (foreign-sizeof 'uptr)])
    (let loop ([i 0])
      (let ([p (if (eqv? envp 0) 0 (foreign-ref 'uptr envp (fx* i w)))])
        (cond [(eqv? p 0) #f]
              [(and (let match ([j 0]) (or (fx= j n) (and (fx= (foreign-ref 'unsigned-8 p j) (char->integer (string-ref name j))) (match (fx+ j 1)))))
                    (fx= (foreign-ref 'unsigned-8 p n) 61))
               (let ([len (cstring-length (+ p n 1))])
                 (list->string (let collect ([k 0]) (if (fx= k len) '() (cons (integer->char (foreign-ref 'unsigned-8 p (+ n 1 k))) (collect (fx+ k 1)))))))]
              [else (loop (fx+ i 1))])))))

;; the candidates JDK_execvpe tries for prog: prog itself when it has a
;; slash, else each directory of this process's PATH (an empty entry is the
;; working directory)
(define (spawn-candidates prog)
  (if (or (fx= (string-length prog) 0)
          (let loop ([i 0]) (and (fx< i (string-length prog)) (or (char=? (string-ref prog i) #\/) (loop (fx+ i 1))))))
      (list prog)
      (let split ([path (or (environ-bytes "PATH") ":/bin:/usr/bin")] [acc '()])
        (let ([colon (let loop ([i 0]) (cond [(fx= i (string-length path)) #f] [(char=? (string-ref path i) #\:) i] [else (loop (fx+ i 1))]))])
          (let* ([dir (if colon (substring path 0 colon) path)]
                 [full (if (fx= (string-length dir) 0) prog (string-append dir (if (char=? (string-ref dir (fx- (string-length dir) 1)) #\/) "" "/") prog))])
            (if colon
                (split (substring path (fx+ colon 1) (string-length path)) (cons full acc))
                (reverse (cons full acc))))))))

;; spawn path with argv0 and the other arguments (argv0 is the program as
;; the caller named it, never the path found, as JDK_execvpe keeps it) and
;; envp; the pid, or (error . errno). ENOEXEC runs it as /bin/sh path args,
;; as the JDK's execve_with_shell_fallback.
(define (spawn-one fa attr path argv0 arg-ptrs envp)
  (let* ([cpath (latin1->foreign path)]
         [argv (pointer-vector (cons argv0 arg-ptrs))]
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
(define (spawn-search fa attr prog dir argv0 arg-ptrs envp)
  (let loop ([cs (spawn-candidates prog)] [sticky #f])
    (if (null? cs)
        (cons 'error (or sticky 2))
        (let* ([e (access-errno (in-dir dir (car cs)))]
               [r (if (fx= e 0) (spawn-one fa attr (car cs) argv0 arg-ptrs envp) (cons 'error e))])
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
                   (if (fx= e 0) (spawn-search fa attr cprog ldir argv0 arg-ptrs envp) (cons 'error e)))))])
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

;; SIGKILL or SIGTERM, only when the start time still matches (isAlive0
;; answers 0, unknown, for a live process, so a 0 on either side matches)
(defnative "java/lang/ProcessHandleImpl.destroy0(JJZ)Z" (pid start force)
  (let ([now (if (pid-ok? pid) (if (or (eqv? 0 (libc-kill pid 0)) (eqv? (errno) 1)) 0 -1) -1)])
    (if (and (>= now 0) (or (eqv? start 0) (eqv? now 0) (eqv? start now)))
        (if (eqv? 0 (libc-kill pid (if (eqv? force 1) 9 15))) 1 0)
        0)))

;; ProcessHandle.Info is left empty: every field stays unknown
(defnative "java/lang/ProcessHandleImpl$Info.initIDs()V" () (void))
(defnative "java/lang/ProcessHandleImpl$Info.info0(J)V" (this pid) (void))
