;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Running a program: launching main through the JDK's LauncherHelper, the
;; java-halt that System.exit raises, and the uncaught-exception reports.
;; Split from runtime.ss; loaded right after it.

;; --- entry --------------------------------------------------------------------

;; HotSpot's JavaThread::exit when dispatchUncaughtException itself throws:
;; one line naming the class of what the handler threw and the thread, which
;; nothing dispatches again
(define (report-handler-failure t x)
  (flush-output-port (current-output-port))
  (let ([err (current-error-port)])
    (flush-output-port err)   ; Java's System.err shares the fd
    (put-string err (string-append "\nException: " (slashes->dots (class-name-of x))
                                   " thrown from the UncaughtExceptionHandler in thread \""
                                   (jstring->scheme (jslot t (jfield-index "java/lang/Thread" "name"))) "\"\n"))
    (flush-output-port err)))

;; Thread t's uncaught exception e through its handler: 'done, a java-halt
;; the handler raised (System.exit in it), or the Scheme condition the
;; dispatch failed with (one during boot, say). A Java throwable from the
;; handler is reported as HotSpot does and counts as done.
(define (dispatch-uncaught t e)
  (guard (x [(java-halt? x) x]
            [#t (let ([j (java-raise-of x)])
                  (if (jobj? j) (begin (report-handler-failure t j) 'done) x))])
    ((method-proc "java/lang/Thread" "dispatchUncaughtException(Ljava/lang/Throwable;)V") t e)
    'done))

;; Report an escaping exception or Scheme error on the main thread; the
;; status a handler's System.exit gave, else #f
(define (report-uncaught raised)
  (flush-output-port (current-output-port))
  (let ([err (current-error-port)] [e (java-raise-of raised)])
    (cond
      [(jobj? e)
       ;; through the thread's own uncaught handler, as HotSpot does for main
       ;; (ThreadGroup's prints "Exception in thread" and the trace); the
       ;; shadow stack only if that fails with a Scheme error
       (flush-output-port err)   ; Java's System.err shares the fd
       (let ([r (dispatch-uncaught current-jthread e)])
         (cond
           [(java-halt? r) (java-halt-status r)]
           [(eq? r 'done) #f]
           [else
            (put-string err (string-append "Exception in thread \"main\" " (guard (x [#t (class-name-of e)]) (jstring-of e)) "\n"))
            (print-jstack e err)
            #f]))]
      [else
       ;; the handler runs before the stack unwinds, so under CHAMPACA_BT
       ;; jstack still says where in the Java program this happened
       (put-string err "champaca: Scheme error: ")
       (display-condition e err)
       (newline err)
       (for-each (lambda (f) (put-string err (string-append "\tat " f "\n")))
                 (list-head jstack (min 25 (length jstack))))
       #f])))

;; Run thunk; report an escaping Java exception or Scheme error and return 1,
;; otherwise return 0.
;; System.exit ends in Shutdown.halt0, which raises this so the status
;; reaches whoever started the Java code: run-main returns it, and api.clj
;; exits the process with it, as a JVM would.
(define-record-type java-halt (fields status))
(define halted #f)

(define (run-guarded thunk)
  (call/cc
   (lambda (k)
     (with-exception-handler
      (lambda (e)
        (if (java-halt? e)
            (begin (flush-output-port (current-output-port)) (set! halted #t) (k (java-halt-status e)))
            (let ([st (report-uncaught e)])
              (flush-output-port (current-output-port))
              (if st (begin (set! halted #t) (k st)) (k 1)))))
      (lambda () (thunk) (flush-output-port (current-output-port)) 0)))))

;; The java launcher's way to main (JEP 512): the JDK's own
;; LauncherHelper.checkAndLoadMain loads the class, finds its main with
;; MethodFinder and refuses with the launcher's texts and status 1 (System.exit
;; from abort); it notes whether main is static and whether it takes the
;; arguments. A static main is called as JNI's GetStaticMethodID finds it, the
;; class (or interface) first, then its superclasses, whatever its access (a
;; private static main hiding the public one MethodFinder found runs, as on
;; HotSpot), the class initialized;
;; an instance main on an object its no-argument constructor makes, through
;; the vtable, so an inherited or default main runs.
;; mode 1 (LM_CLASS) takes a class name, 3 (LM_MODULE) java -m's
;; module[/class], whose class the launcher finds in the module (its
;; ModuleMainClass when none is given), with the launcher's errors
(define (launch-main what args mode)
  (let* ([helper "sun/launcher/LauncherHelper"]
         [_ (jinit helper)]
         [mirror ((method-proc helper "checkAndLoadMain(ZILjava/lang/String;)Ljava/lang/Class;")
                  1 mode (jstring (if (fx= mode 1) (slashes->dots what) what)))]
         [c (mirror-class mirror)]
         [name (jclass-cname c)]
         [static? (eqv? 1 (top-level-value (string->symbol (string-append helper ":isStaticMain"))))]
         [no-args? (eqv? 1 (top-level-value (string->symbol (string-append helper ":noArgMain"))))]
         [desc (if no-args? "()V" "([Ljava/lang/String;)V")]
         [jargs (if no-args? '() (list (scheme-list->jarray "Ljava/lang/String;" (map jstring args))))])
    (if static?
        (let up ([k c])
          (let ([m (find (lambda (m) (and (string=? (car m) "main") (string=? (cadr m) desc)
                                          (fxpositive? (fxlogand (caddr m) 8))))
                         (meta-methods k))])
            (cond [m (jinit name) (apply (method-proc (jclass-cname k) (string-append "main" desc)) jargs)]
                  [(jclass-super k) (up (find-class (jclass-super k)))]
                  [else (error 'champaca "main not found after the launcher found it" name)])))
        (let ([o (construct name "()V")])
          (apply (jvirt o (string-append "main" desc)) o jargs)))))
;; -version and its kin: the JDK's VersionProps.print, as the launcher calls
;; it once the VM is up, on stderr when err? (-version, -showversion), else
;; stdout (--version, --show-version). The exit status, 0 unless it threw.
(define (java-print-version err?)
  (run-guarded (lambda () ((method-proc "java/lang/VersionProps" "print(Z)V") (if err? 1 0)))))
(define (run-main cname . args) (run-main-as cname args 1))
;; java -m module[/class] args
(define (run-module-main what . args) (run-main-as what args 3))
;; java -jar app.jar args: LauncherHelper's jar mode, which reads the main
;; jar's manifest as HotSpot's launcher has it do (Main-Class, and the
;; Add-Opens, Add-Exports and Enable-Native-Access its program needs)
(define (run-jar-main jar . args) (run-main-as jar args 2))
(define (run-main-as what args mode)
  (set! halted #f)
  (let ([status (run-guarded (lambda () (launch-main what args mode)))])
    ;; as the java launcher: unless System.exit ended it, main's thread ends
    ;; (its joiners wake), then as DestroyJavaVM it waits for the other
    ;; non-daemon threads and runs the shutdown hooks, keeping main's status
    (if halted
        status
        (let ([s (run-guarded (lambda () (main-thread-exit!) (java-shutdown!)))])
          (if halted s status)))))
