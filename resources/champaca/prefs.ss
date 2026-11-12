;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; java.util.prefs: FileSystemPreferences' lock files on Linux and
;; MacOSXPreferencesFile over CFPreferences on macOS. Split from files.ss, whose
;; deflibc bindings and helpers these use; loaded after it.

;; java.util.prefs on Linux (FileSystemPreferences), as its native does: a
;; lock file opened for writing (created with the permission given, umask
;; 0) or, for a shared lock, reading, and locked whole by F_SETLK; the
;; answer is [fd or 0, errno]. Unlocking closes it, chmod answers errno.
(defnative "java/util/prefs/FileSystemPreferences.lockFile0(Ljava/lang/String;IZ)[I" (name permission shared)
  (let* ([p (jstring->scheme (nn name))]
         [fd (if (eqv? shared 1)
                 (libc-open-string p (fs-const "O_RDONLY") 0)
                 (let* ([old (libc-umask 0)]
                        [fd (libc-open-string p (fxlogor (fs-const "O_WRONLY") (fs-const "O_CREAT")) permission)])
                   (libc-umask old)
                   fd))]
         [result (if (fx< fd 0)
                     (list 0 (errno))
                     (if (fx< (fcntl-lock fd 'F_SETLK (if (eqv? shared 1) 'F_RDLCK 'F_WRLCK) 0 0) 0)
                         (let ([e (errno)]) (libc-close fd) (list 0 e))
                         (list fd (errno))))])
    (make-jarr "I" (list->vector result))))
(defnative "java/util/prefs/FileSystemPreferences.unlockFile0(I)I" (fd)
  (if (fx< (fcntl-lock fd 'F_SETLK 'F_UNLCK 0 0) 0)
      (let ([e (errno)]) (libc-close fd) e)
      (if (fx< (libc-close fd) 0) (errno) 0)))
(defnative "java/util/prefs/FileSystemPreferences.chmod(Ljava/lang/String;I)I" (name permission)
  (if (fx= 0 (libc-chmod-string (jstring->scheme (nn name)) permission)) 0 (errno)))

;; java.util.prefs on macOS (MacOSXPreferencesFile): each file is a
;; CFPreferences application domain, given by name with the user and host
;; constants. A node's path ("/a/b/" , the root "/") is a top key of its
;; domain, "/" or the path up to its fourth slash, under which each further
;; component nests as a dictionary named with a trailing slash ("c/"); a
;; key is a string entry of its node's dictionary. That is the layout
;; HotSpot leaves in ~/Library/Preferences, so both read each other's.
;; Each change copies the top value deeply (mutable containers), edits it
;; and sets it back; synchronize is CFPreferencesSynchronize.
(define cf-prefs
  (let ([procs #f])
    (lambda ()
      (or procs
          (begin
            (load-shared-object "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
            (let ([data (lambda (n) (foreign-ref 'uptr (foreign-entry n) 0))])
              (set! procs
                    (vector (foreign-procedure "CFStringCreateWithBytes" (uptr u8* long unsigned-32 boolean) uptr)
                            (foreign-procedure "CFStringGetLength" (uptr) long)
                            (foreign-procedure "CFStringGetMaximumSizeForEncoding" (long unsigned-32) long)
                            (foreign-procedure "CFStringGetCString" (uptr uptr long unsigned-32) boolean)
                            (foreign-procedure "CFPreferencesCopyValue" (uptr uptr uptr uptr) uptr)
                            (foreign-procedure "CFPreferencesSetValue" (uptr uptr uptr uptr uptr) void)
                            (foreign-procedure "CFPreferencesSynchronize" (uptr uptr uptr) boolean)
                            (foreign-procedure "CFPropertyListCreateDeepCopy" (uptr uptr unsigned-long) uptr)
                            (foreign-procedure "CFDictionaryCreateMutable" (uptr long uptr uptr) uptr)
                            (foreign-procedure "CFDictionaryGetValue" (uptr uptr) uptr)
                            (foreign-procedure "CFDictionarySetValue" (uptr uptr uptr) void)
                            (foreign-procedure "CFDictionaryRemoveValue" (uptr uptr) void)
                            (foreign-procedure "CFDictionaryGetCount" (uptr) long)
                            (foreign-procedure "CFDictionaryGetKeysAndValues" (uptr uptr uptr) void)
                            (foreign-procedure "CFGetTypeID" (uptr) unsigned-long)
                            ((foreign-procedure "CFDictionaryGetTypeID" () unsigned-long))
                            ((foreign-procedure "CFStringGetTypeID" () unsigned-long))
                            (foreign-procedure "CFRelease" (uptr) void)
                            (foreign-entry "kCFTypeDictionaryKeyCallBacks")
                            (foreign-entry "kCFTypeDictionaryValueCallBacks")
                            (vector (data "kCFPreferencesCurrentUser") (data "kCFPreferencesAnyUser")
                                    (data "kCFPreferencesCurrentHost") (data "kCFPreferencesAnyHost"))))
              procs))))))
(define (cfp i) (vector-ref (cf-prefs) i))
(define cf-utf8 #x08000100)
(define (cf-string s)   ; a new CFString, to release
  (let ([b (string->utf8 s)]) ((cfp 0) 0 b (bytevector-length b) cf-utf8 #f)))
(define (cf-string->string cs)
  (let* ([n (fx+ 1 ((cfp 2) ((cfp 1) cs) cf-utf8))] [buf (foreign-alloc n)])
    (let ([s (and ((cfp 3) cs buf n cf-utf8) (c-string-text buf))])
      (foreign-free buf)
      s)))
(define (cf-dictionary? x) (and (not (eqv? x 0)) (= ((cfp 14) x) (cfp 15))))
(define (cf-string? x) (and (not (eqv? x 0)) (= ((cfp 14) x) (cfp 16))))
(define (cf-release x) (unless (eqv? x 0) ((cfp 17) x)))
(define (with-cf-strings strs proc)
  (let ([cs (map cf-string strs)])
    (dynamic-wind void (lambda () (apply proc cs)) (lambda () (for-each cf-release cs)))))
;; the top key of path, and the nested components after it ("c/" ...)
(define (prefs-split path)
  (let loop ([i 0] [slashes 0])
    (cond [(fx= i (string-length path)) (values "/" (prefs-components (substring path 1 (string-length path))))]
          [(char=? (string-ref path i) #\/)
           (if (fx= slashes 3)
               (values (substring path 0 (fx+ i 1)) (prefs-components (substring path (fx+ i 1) (string-length path))))
               (loop (fx+ i 1) (fx+ slashes 1)))]
          [else (loop (fx+ i 1) slashes)])))
(define (prefs-components s)   ; "c/d/" -> ("c/" "d/")
  (let loop ([i 0] [start 0] [acc '()])
    (cond [(fx= i (string-length s)) (reverse acc)]
          [(char=? (string-ref s i) #\/) (loop (fx+ i 1) (fx+ i 1) (cons (substring s start (fx+ i 1)) acc))]
          [else (loop (fx+ i 1) start acc)])))
(define (cf-empty-dict) ((cfp 8) 0 0 (cfp 18) (cfp 19)))
;; the node's dictionary inside top (a dictionary), made when create?; #f
;; when missing; (values dict created?)
(define (prefs-walk top comps create?)
  (let loop ([d top] [comps comps] [created #f])
    (if (null? comps)
        (values d created)
        (let ([child (with-cf-strings (list (car comps)) (lambda (k) ((cfp 9) d k)))])
          (cond [(cf-dictionary? child) (loop child (cdr comps) created)]
                [create?
                 (let ([n (cf-empty-dict)])
                   (with-cf-strings (list (car comps)) (lambda (k) ((cfp 10) d k n)))
                   (cf-release n)
                   (loop (with-cf-strings (list (car comps)) (lambda (k) ((cfp 9) d k))) (cdr comps) #t))]
                [else (values #f #f)])))))
;; run proc on the node's dictionary of a mutable deep copy of path's top
;; value (made when create?), then store the copy back when proc says so;
;; proc gets (dict created?) and answers (values result store?)
(define (prefs-edit name user host path create? proc)
  (let-values ([(top-key comps) (prefs-split path)])
    (with-cf-strings (list top-key name)
      (lambda (tk app)
        (let* ([old ((cfp 4) tk app user host)]
               [had-top? (cf-dictionary? old)]
               [top (cond [had-top? ((cfp 7) 0 old 1)]   ; kCFPropertyListMutableContainers
                          [create? (cf-empty-dict)]
                          [else #f])])
          (cf-release old)
          (if (not top)
              (let-values ([(result _) (proc #f #f)]) result)
              (let-values ([(d created) (prefs-walk top comps create?)])
                (let-values ([(result store?) (proc d (or created (not had-top?)))])
                  (when store? ((cfp 5) tk top app user host))
                  (cf-release top)
                  result))))))))
;; read-only: proc on the node's dictionary, or #f
(define (prefs-read name user host path proc)
  (let-values ([(top-key comps) (prefs-split path)])
    (with-cf-strings (list top-key name)
      (lambda (tk app)
        (let ([top ((cfp 4) tk app user host)])
          (if (cf-dictionary? top)
              (let-values ([(d _) (prefs-walk top comps #f)])
                (let ([r (proc d)]) (cf-release top) r))
              (begin (cf-release top) (proc #f))))))))
(define (cf-dict-entries d)   ; ((key-string . value-ref) ...)
  (let* ([n ((cfp 12) d)] [ks (foreign-alloc (fxmax 8 (fx* 8 n)))] [vs (foreign-alloc (fxmax 8 (fx* 8 n)))])
    ((cfp 13) d ks vs)
    (let ([r (let loop ([i 0] [acc '()])
               (if (fx= i n)
                   (reverse acc)
                   (let ([k (foreign-ref 'uptr ks (fx* 8 i))] [v (foreign-ref 'uptr vs (fx* 8 i))])
                     (loop (fx+ i 1) (if (cf-string? k) (cons (cons (cf-string->string k) v) acc) acc)))))])
      (foreign-free ks) (foreign-free vs)
      r)))
(define mpf "java/util/prefs/MacOSXPreferencesFile.")
(define-syntax defmpf
  (syntax-rules () [(_ sig args body ...) (defnative (string-append mpf sig) args body ...)]))
(define (prefs-js s) (jstring->scheme (nn s)))
(defmpf "currentUser()J" () (vector-ref (cfp 20) 0))
(defmpf "anyUser()J" () (vector-ref (cfp 20) 1))
(defmpf "currentHost()J" () (vector-ref (cfp 20) 2))
(defmpf "anyHost()J" () (vector-ref (cfp 20) 3))
;; true when the node didn't exist before
(defmpf "addNode(Ljava/lang/String;Ljava/lang/String;JJ)Z" (path name user host)
  (if (prefs-edit (prefs-js name) user host (prefs-js path) #t (lambda (d created?) (values created? created?))) 1 0))
(defmpf "removeNode(Ljava/lang/String;Ljava/lang/String;JJ)V" (path name user host)
  (let-values ([(top-key comps) (prefs-split (prefs-js path))])
    (if (null? comps)
        (with-cf-strings (list top-key (prefs-js name)) (lambda (tk app) ((cfp 5) tk 0 app user host)))
        (let* ([p (prefs-js path)] [parent (substring p 0 (fx- (string-length p) (string-length (list-ref comps (fx- (length comps) 1)))))])
          (prefs-edit (prefs-js name) user host parent #f
                      (lambda (d _)
                        (if d
                            (begin (with-cf-strings (list (list-ref comps (fx- (length comps) 1))) (lambda (k) ((cfp 11) d k)))
                                   (values (void) #t))
                            (values (void) #f))))))
    (void)))
(defmpf "addChildToNode(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)Z" (path child name user host)
  (if (prefs-edit (prefs-js name) user host (prefs-js path) #t
                  (lambda (d _)
                    (let ([had (with-cf-strings (list (prefs-js child)) (lambda (k) (cf-dictionary? ((cfp 9) d k))))])
                      (if had
                          (values #f #f)
                          (let ([n (cf-empty-dict)])
                            (with-cf-strings (list (prefs-js child)) (lambda (k) ((cfp 10) d k n)))
                            (cf-release n)
                            (values #t #t))))))
      1 0))
(defmpf "removeChildFromNode(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V" (path child name user host)
  (prefs-edit (prefs-js name) user host (prefs-js path) #f
              (lambda (d _)
                (if d
                    (begin (with-cf-strings (list (prefs-js child)) (lambda (k) ((cfp 11) d k))) (values (void) #t))
                    (values (void) #f))))
  (void))
(defmpf "addKeyToNode(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V" (path key value name user host)
  (prefs-edit (prefs-js name) user host (prefs-js path) #t
              (lambda (d _)
                (with-cf-strings (list (prefs-js key) (prefs-js value)) (lambda (k v) ((cfp 10) d k v)))
                (values (void) #t)))
  (void))
(defmpf "removeKeyFromNode(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V" (path key name user host)
  (prefs-edit (prefs-js name) user host (prefs-js path) #f
              (lambda (d _)
                (if d
                    (begin (with-cf-strings (list (prefs-js key)) (lambda (k) ((cfp 11) d k))) (values (void) #t))
                    (values (void) #f))))
  (void))
(defmpf "getKeyFromNode(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)Ljava/lang/String;" (path key name user host)
  (prefs-read (prefs-js name) user host (prefs-js path)
              (lambda (d)
                (and d (let ([v (with-cf-strings (list (prefs-js key)) (lambda (k) ((cfp 9) d k)))])
                         (and (cf-string? v) (jstring (cf-string->string v))))))))
(define (prefs-names d dict?)
  (make-jarr "Ljava/lang/String;"
             (list->vector
              (if d
                  (fold-right (lambda (e acc)
                                (cond [(and dict? (cf-dictionary? (cdr e)))
                                       (let ([k (car e)])   ; "c/" -> "c"
                                         (cons (jstring (if (and (fx> (string-length k) 0) (char=? (string-ref k (fx- (string-length k) 1)) #\/))
                                                            (substring k 0 (fx- (string-length k) 1)) k))
                                               acc))]
                                      [(and (not dict?) (cf-string? (cdr e))) (cons (jstring (car e)) acc)]
                                      [else acc]))
                              '() (cf-dict-entries d))
                  '()))))
(defmpf "getChildrenForNode(Ljava/lang/String;Ljava/lang/String;JJ)[Ljava/lang/String;" (path name user host)
  (prefs-read (prefs-js name) user host (prefs-js path) (lambda (d) (prefs-names d #t))))
(defmpf "getKeysForNode(Ljava/lang/String;Ljava/lang/String;JJ)[Ljava/lang/String;" (path name user host)
  (prefs-read (prefs-js name) user host (prefs-js path) (lambda (d) (prefs-names d #f))))
(defmpf "synchronize(Ljava/lang/String;JJ)Z" (name user host)
  (if (with-cf-strings (list (prefs-js name)) (lambda (app) ((cfp 6) app user host))) 1 0))
