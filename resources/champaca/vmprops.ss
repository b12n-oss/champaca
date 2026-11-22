;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Natives for strings and numbers, Throwable's stack traces, the VM and
;; CDS, and the system properties the JDK reads at boot. Split from
;; natives.ss, whose defnative and helpers these use; loaded right after it.

;; --- strings and numbers -------------------------------------------------------

(defnative "java/lang/String.intern()Ljava/lang/String;" (this) (jstring-intern! this))
(defnative "java/lang/StringUTF16.isBigEndian()Z" () 0)

(define (bits->float b) (let ([v (make-bytevector 4)]) (bytevector-u32-native-set! v 0 (logand b #xffffffff)) (bytevector-ieee-single-native-ref v 0)))
(define (float->bits f) (let ([v (make-bytevector 4)]) (bytevector-ieee-single-native-set! v 0 f) (i32 (bytevector-u32-native-ref v 0))))
(define (bits->double b) (let ([v (make-bytevector 8)]) (bytevector-u64-native-set! v 0 (logand b #xffffffffffffffff)) (bytevector-ieee-double-native-ref v 0)))
(define (double->bits d) (let ([v (make-bytevector 8)]) (bytevector-ieee-double-native-set! v 0 d) (i64 (bytevector-u64-native-ref v 0))))
;; the JVM's compare-and-set compares a float or double by its bits, so a NaN
;; with another payload doesn't match and -0.0 isn't 0.0; eqv? called every
;; NaN the same
(define (same-bits? a b)
  (if (and (flonum? a) (flonum? b)) (= (double->bits a) (double->bits b)) (eqv? a b)))
(defnative "java/lang/Float.floatToRawIntBits(F)I" (f) (float->bits f))
(defnative "java/lang/Float.intBitsToFloat(I)F" (b) (bits->float b))
(defnative "java/lang/Double.doubleToRawLongBits(D)J" (d) (double->bits d))
(defnative "java/lang/Double.longBitsToDouble(J)D" (b) (bits->double b))

;; --- Throwable ------------------------------------------------------------------

;; fillInStackTrace keeps the raw frames (reflect.ss raw-frames, a pair per
;; frame, about as cheap as HotSpot's own backtrace) in the Throwable's
;; backtrace field, in a backtrace record (runtime.ss: an opaque Object to
;; Java). A weak table under the Throwable instead kept each dead
;; throwable's frames alive until a collection swept its entry, which cost
;; another 1.5 us a throw over 100k of them. getOurStackTrace turns the
;; frames into StackTraceElements when something asks, with the file, line,
;; module and hidden flag from a jolt hook (jit.clj frame-info).
(define frame-hook #f)
(define (set-frame-hook! f) (set! frame-hook f))
(define (slot-of cname fname) (jfield-index cname fname))

(defnative "java/lang/Throwable.fillInStackTrace(I)Ljava/lang/Throwable;" (this dummy)
  ;; CHAMPACA_BT's shadow stack, empty without it: a weak-table insert per
  ;; throw costs about as much as the walk, so none for an empty one
  (unless (null? jstack) (eq-hashtable-set! throw-stacks this jstack))
  (jslot-set! this (slot-of "java/lang/Throwable" "backtrace") (make-backtrace (call/1cc (lambda (k) (raw-frames k)))))
  this)

;; HotSpot leaves out fillInStackTrace and the constructors of the
;; throwable's own class and its superclasses, not of a throwable it makes
;; inside one of its constructors
(define (trace-frames frames this)
  (let ([tc (class-name-of this)])
    (let loop ([fs frames])
      (if (and (pair? fs)
               (let ([cname (caar fs)] [mname (cadar fs)])
                 (or (string=? mname "fillInStackTrace")
                     (and (string=? mname "<init>") (class-assignable? tc cname)))))
          (loop (cdr fs))
          fs))))

;; frames of methods the JVM hides (@Hidden: Thread.runWith, lambda forms),
;; asked of the hook once per method
(define hidden-methods (make-hashtable string-hash string=?))
(define (frame-hidden? f)
  (let ([key (string-append (car f) "." (cadr f) (caddr f))])
    (let ([hit (hashtable-ref hidden-methods key 'none)])
      (if (eq? hit 'none)
          (let ([h (and frame-hook
                        (let ([info (frame-hook (car f) (cadr f) (caddr f) -1)])
                          (and (fx> (vector-length info) 3) (eqv? 1 (vector-ref info 3)))))])
            (hashtable-set! hidden-methods key h)
            h)
          hit))))
(define (visible-frames frames) (filter (lambda (f) (not (frame-hidden? f))) frames))

;; ste for cname.mname desc at pc, its format bits still to compute; pc -2
;; is a native method's frame, whose line -2 prints as Native Method
;; A named module's version as HotSpot's module entry has it: the
;; descriptor's raw version, the string Module hands defineModule0, or #f;
;; per Module object, which never changes its descriptor
(define module-versions (make-weak-eq-hashtable))
(define (module-raw-version mod)
  (let ([hit (eq-hashtable-ref module-versions mod 'none)])
    (if (eq? hit 'none)
        (let* ([d ((method-proc "java/lang/Module" "getDescriptor()Ljava/lang/module/ModuleDescriptor;") mod)]
               [o (and d ((method-proc "java/lang/module/ModuleDescriptor" "rawVersion()Ljava/util/Optional;") d))]
               [v (and o ((method-proc "java/util/Optional" "orElse(Ljava/lang/Object;)Ljava/lang/Object;") o #f))])
          (eq-hashtable-set! module-versions mod v)
          v)
        hit)))

;; ste for cname.mname desc at pc, its format bits still to compute; pc -2
;; is a native method's frame, whose line -2 prints as Native Method. As
;; HotSpot's fill_in: the class's module name and version once its module
;; is named (an application module's included; during the boot, before
;; modules are defined, the module the image puts the class in), and its
;; loader's name. StackTraceElement's own format then decides what prints
;; (no JDK module version, no built-in loader's name).
(define (fill-ste! ste cname mname desc pc)
  (let* ([info (if frame-hook (frame-hook cname mname desc (if (eqv? pc -2) -1 pc)) (vector "" -1 ""))]
         [file (vector-ref info 0)] [line (if (eqv? pc -2) -2 (vector-ref info 1))] [module (vector-ref info 2)]
         [c (find-class cname)]
         [mod (class-module c)]
         [named (and mod (jslot mod (jfield-index "java/lang/Module" "name")))]
         [ld (class-loader-of c)]
         [set (lambda (f v) (jslot-set! ste (slot-of "java/lang/StackTraceElement" f) v))])
    (set "declaringClassObject" (jmirror c))
    (set "declaringClass" (jstring (slashes->dots (plain-class-name cname))))
    (set "methodName" (jstring mname))
    (set "fileName" (and (fx> (string-length file) 0) (jstring file)))
    (set "lineNumber" line)
    (set "moduleName" (or named (and (fx> (string-length module) 0) (jstring module))))
    (set "moduleVersion" (and named (module-raw-version mod)))
    (set "classLoaderName" (and ld (jslot ld (jfield-index "java/lang/ClassLoader" "name"))))
    ste))

;; the elements filled in, their format still to compute (computeFormat
;; clears declaringClassObject, so it runs once: Thread.asyncGetStackTrace
;; runs it itself on what getStackTrace0 answers)
(define (stack-trace-elements frames)
  (jinit "java/lang/StackTraceElement")
  (make-jarr "Ljava/lang/StackTraceElement;"
             (list->vector (map (lambda (f) (fill-ste! (jnew "java/lang/StackTraceElement") (car f) (cadr f) (caddr f) (cadddr f)))
                                frames))))
(define (stack-trace-array frames)
  ;; of() computes each element's format: how the module and loader print
  ((method-proc "java/lang/StackTraceElement" "of([Ljava/lang/StackTraceElement;)[Ljava/lang/StackTraceElement;")
   (stack-trace-elements frames)))

(define-override! "java/lang/Throwable.getOurStackTrace()[Ljava/lang/StackTraceElement;"
  (lambda (this)
    (let* ([slot (slot-of "java/lang/Throwable" "stackTrace")]
           [cur (jslot this slot)]
           [unassigned (begin (jinit "java/lang/Throwable") (top-level-value 'java/lang/Throwable:UNASSIGNED_STACK))])
      (if (or (not cur) (eq? cur unassigned))
          (let ([bt (jslot this (slot-of "java/lang/Throwable" "backtrace"))])
            (if (backtrace? bt)
                (let* ([fs (visible-frames (trace-frames (java-frames-of (backtrace-frames bt)) this))]
                       [arr (stack-trace-array (if (fx> (length fs) 1024) (list-head fs 1024) fs))])
                  (jslot-set! this slot arr)
                  arr)
                unassigned))
          cur))))

;; StackWalker: callStackWalk hands the first batch of frames to Java's
;; doStackWalk and keeps the rest under the walker (one per walk, weakly: a
;; walk the JDK abandons before the end, limit(1) say, leaves nothing behind)
;; for fetchStackFrames. A frame is a ClassFrameInfo (its Class) or a
;; StackFrameInfo (a ResolvedMethodName holding the Class, plus name,
;; descriptor and bci).
(defnative "java/lang/StackStreamFactory.checkStackWalkModes()Z" () 1)
(define walk-anchors (make-weak-eq-hashtable))
(define (list-tail* ls n) (if (or (fx= n 0) (null? ls)) ls (list-tail* (cdr ls) (fx- n 1))))
(define (fill-frames! frames start size fs)
  (let ([v (jarr-vec frames)] [cmn (slot-of "java/lang/ClassFrameInfo" "classOrMemberName")])
    (let loop ([i start] [fs fs] [n 0])
      (if (or (fx>= i size) (null? fs))
          n
          (let* ([f (car fs)] [obj (vector-ref v i)] [mirror (jmirror-of (car f))])
            (if (jinstance? obj "java/lang/StackFrameInfo")
                (let ([rmn (jnew "java/lang/invoke/ResolvedMethodName")])
                  (jslot-set! rmn (slot-of "java/lang/invoke/ResolvedMethodName" "vmholder") mirror)
                  (jslot-set! obj cmn rmn)
                  (jslot-set! obj (slot-of "java/lang/StackFrameInfo" "name") (jstring (cadr f)))
                  (jslot-set! obj (slot-of "java/lang/StackFrameInfo" "type") (jstring (caddr f)))
                  (jslot-set! obj (slot-of "java/lang/StackFrameInfo" "bci") (cadddr f)))
                (jslot-set! obj cmn mirror))
            (loop (fx+ i 1) (cdr fs) (fx+ n 1)))))))
(defnative "java/lang/StackStreamFactory$AbstractStackWalker.callStackWalk(IILjdk/internal/vm/ContinuationScope;Ljdk/internal/vm/Continuation;II[Ljava/lang/Object;)Ljava/lang/Object;"
  (this mode skip scope cont size start frames)
  (let* ([fs (list-tail* (visible-frames (call/1cc (lambda (k) (java-frames-of (raw-frames k))))) skip)]
         [n (fill-frames! frames start size fs)])
    (eq-hashtable-set! walk-anchors this (list-tail* fs n))
    ((method-proc "java/lang/StackStreamFactory$AbstractStackWalker" "doStackWalk(JIIII)Ljava/lang/Object;")
     this 1 skip n start (fx+ start n))))
(defnative "java/lang/StackStreamFactory$AbstractStackWalker.fetchStackFrames(IJIII[Ljava/lang/Object;)I"
  (this mode anchor last size start frames)
  (let* ([fs (eq-hashtable-ref walk-anchors this '())]
         [n (fill-frames! frames start size fs)])
    (if (fx= n 0)
        (eq-hashtable-delete! walk-anchors this)
        (eq-hashtable-set! walk-anchors this (list-tail* fs n)))
    n))
(defnative "java/lang/StackStreamFactory$AbstractStackWalker.setContinuation(J[Ljava/lang/Object;Ljdk/internal/vm/Continuation;)V"
  (this anchor frames cont) (void))
(defnative "java/lang/StackFrameInfo.expandStackFrameInfo()V" (this) (void))   ; name and type are set already
(defnative "java/lang/StackTraceElement.initStackTraceElement(Ljava/lang/StackTraceElement;Ljava/lang/StackFrameInfo;)V" (ste sfi)
  (let* ([rmn (jslot sfi (slot-of "java/lang/ClassFrameInfo" "classOrMemberName"))]
         [cname (jclass-cname (mirror-class (jslot rmn (slot-of "java/lang/invoke/ResolvedMethodName" "vmholder"))))])
    (fill-ste! ste cname
               (jstring->scheme (jslot sfi (slot-of "java/lang/StackFrameInfo" "name")))
               (jstring->scheme (jslot sfi (slot-of "java/lang/StackFrameInfo" "type")))
               (jslot sfi (slot-of "java/lang/StackFrameInfo" "bci")))))

;; JEP 358's "Cannot invoke ... because ..." text, from the bytecode at the
;; site npe-at recorded (runtime.ss), through a jolt callback (champaca.npe).
;; An NPE without a site gets null, which is what HotSpot answers for one the
;; program constructed itself.
(define npe-hook #f)
(define (set-npe-hook! f) (set! npe-hook f))
(defnative "java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;" (this)
  (let ([site (eq-hashtable-ref npe-sites this #f)])
    (and site npe-hook
         (let ([m (npe-hook (string-append (symbol->string (car site)) "@" (number->string (cdr site))))])
           (and m (jstring m))))))

;; --- VM and CDS ---------------------------------------------------------------

(defnative "jdk/internal/misc/VM.initialize()V" () (void))
(defnative "jdk/internal/misc/VM.getNanoTimeAdjustment(J)J" (offset)
  (- (now-nanos) (* offset 1000000000)))
(defnative "jdk/internal/misc/CDS.isDumpingClassList0()Z" () 0)
(defnative "jdk/internal/misc/CDS.isDumpingArchive0()Z" () 0)
(defnative "jdk/internal/misc/CDS.isSharingEnabled0()Z" () 0)
(defnative "jdk/internal/misc/CDS.getCDSConfigStatus()I" () 0)
(defnative "jdk/internal/misc/CDS.getRandomSeedForDumping()J" () (module-graph-seed-now))
;; archive.ss: the module graph a warm boot read, for ArchivedModuleGraph
(defnative "jdk/internal/misc/CDS.initializeFromArchive(Ljava/lang/Class;)V" (c)
  (when (and module-graph-archive (string=? (jclass-cname (mirror-class c)) "jdk/internal/module/ArchivedModuleGraph"))
    (let ([g (module-graph-load module-graph-archive)])
      (when g (set-top-level-value! (string->symbol module-graph-root) g)))))

;; --- system properties ------------------------------------------------------------

(define (props-array pairs)
  (scheme-list->jarray "Ljava/lang/String;"
                       (apply append (map (lambda (p) (list (jstring (car p)) (jstring (cdr p)))) pairs))))

(defnative "jdk/internal/util/SystemProps$Raw.vmProperties()[Ljava/lang/String;" ()
  ;; the -D options come after java.library.path, which they can replace (the
  ;; JDK's cmdProperties keeps the last of a key), and before the properties
  ;; the VM owns, which they can't, as HotSpot ignores -Djava.vm.name and
  ;; -Djava.class.path. HotSpot takes -Djava.home (and fails to start when it
  ;; isn't a JDK) and appends -Dsun.boot.library.path to its own; champaca
  ;; keeps both, and the Unsafe and reflection modes it needs
  (props-array
   `(("java.library.path" . "")
     ,@command-line-properties
     ("java.home" . ,java-home-dir)
     ("java.vm.name" . "champaca")
     ("java.vm.vendor" . "champaca")
     ("java.vm.version" . "0.0-spike")
     ;; HotSpot's mode words (VersionProps prints them in -version's last
     ;; line): champaca compiles every method before it runs
     ("java.vm.info" . "compiled mode")
     ;; the JIT's name: ManagementFactory makes a CompilationMXBean only when
     ;; it's set, as HotSpot sets it unless -Xint leaves no compiler
     ("sun.management.compiler" . "champaca (Chez Scheme)")
     ("java.vm.specification.name" . "Java Virtual Machine Specification")
     ("java.vm.specification.vendor" . "Oracle Corporation")
     ("java.vm.specification.version" . "25")
     ("java.class.path" . ,classpath-string)
     ("sun.boot.library.path" . ,(string-append java-home-dir "/lib"))
     ("jdk.debug" . "release")
     ;; reflection through invoke0/newInstance0 (reflect.ss), never method handles
     ("jdk.reflect.useNativeAccessorOnly" . "true")
     ;; what the launcher sets: the main class or jar and its arguments,
     ;; when champaca.main started the program
     ,@(if java-command
           `(("sun.java.command" . ,java-command) ("sun.java.launcher" . "SUN_STANDARD"))
           '()))))
(define java-command #f)
(define (set-java-command! s) (set! java-command s))
;; champaca.main's -D options, in order: (key . value) pairs, given as one
;; string of key NUL value NUL ... (no argument holds a NUL)
(define command-line-properties '())
(define (set-command-line-properties! s)
  (set! command-line-properties
        (let loop ([fields (nul-fields s)])
          (if (or (null? fields) (null? (cdr fields)))
              '()
              (cons (cons (car fields) (cadr fields)) (loop (cddr fields)))))))
(define (nul-fields s)
  (let split ([i 0] [start 0])
    (cond [(fx= 0 (string-length s)) '()]
          [(fx= i (string-length s)) (list (substring s start i))]
          [(char=? (string-ref s i) #\nul)
           (cons (substring s start i) (split (fx+ i 1) (fx+ i 1)))]
          [else (split (fx+ i 1) start)])))
;; the VM options champaca.main read, as HotSpot's Arguments::jvm_args:
;; every option before the main class except the class path ones, in order,
;; and --add-modules=ALL-DEFAULT in source-file mode, as the launcher adds
;; it (RuntimeMXBean.getInputArguments, the source launcher's options);
;; none when jolt calls in
(define runtime-arguments '())
(define (set-runtime-arguments! s) (set! runtime-arguments (nul-fields s)))
(defnative "jdk/internal/misc/VM.getRuntimeArguments()[Ljava/lang/String;" ()
  (make-jarr "Ljava/lang/String;" (list->vector (map jstring runtime-arguments))))

;; The locale properties (user.language, user.script, user.country, and
;; their .format forms when the format locale differs) as the JDK finds
;; them: (language script country), any of them #f. macOS reads the user's
;; first preferred language from CoreFoundation for both, with the current
;; locale's region when that language names none; the environment is never
;; read there. Linux asks setlocale(LC_ALL, "") for LC_MESSAGES (display)
;; and LC_CTYPE (format), the process's own locale put back afterwards; a
;; locale that isn't installed leaves it C, which the JDK takes as en_US.
;; Of a POSIX name's @modifier only the script names are read; the JDK's
;; variant and alias tables (nynorsk, iw) aren't here.
(define (locale-parts lang-script-country)   ; "en-AU", "zh-Hans-CN", "en" -> (en #f AU) ...
  (let* ([parts (let split ([i 0] [start 0] [acc '()])
                  (let ([n (string-length lang-script-country)])
                    (cond [(fx= i n) (reverse (cons (substring lang-script-country start i) acc))]
                          [(char=? (string-ref lang-script-country i) #\-)
                           (split (fx+ i 1) (fx+ i 1) (cons (substring lang-script-country start i) acc))]
                          [else (split (fx+ i 1) start acc)])))]
         [script (and (pair? (cdr parts)) (fx= 4 (string-length (cadr parts))) (cadr parts))]
         [rest (if script (cddr parts) (cdr parts))])
    (list (car parts) script (and (pair? rest) (positive? (string-length (car rest))) (car rest)))))
(define (posix-locale-parts lc)   ; "sr_RS.UTF-8@latin" -> (sr Latn RS)
  (let* ([lc (if (or (not lc) (member lc '("C" "POSIX"))) "en_US" lc)]
         [n (string-length lc)]
         [upto (lambda (from stops)
                 (let loop ([i from]) (if (or (fx= i n) (memv (string-ref lc i) stops)) i (loop (fx+ i 1)))))]
         [le (upto 0 '(#\_ #\. #\@))]
         [lang (substring lc 0 le)]
         [country (and (fx< le n) (char=? (string-ref lc le) #\_)
                       (substring lc (fx+ le 1) (upto (fx+ le 1) '(#\. #\@))))]
         [at (upto 0 '(#\@))]
         [modifier (and (fx< at n) (substring lc (fx+ at 1) n))]
         [script (and modifier (let ([hit (assoc modifier '(("latin" . "Latn") ("cyrillic" . "Cyrl")
                                                              ("devanagari" . "Deva") ("iqtelif" . "Latn")))])
                                 (and hit (cdr hit))))])
    (list (if (member lang '("C" "POSIX")) "en" lang) script (and country (positive? (string-length country)) country))))
(define (macos-locale)
  (let ([buf (foreign-alloc 128)])
    (let ([r (macos-locale* buf)]) (foreign-free buf) r)))
;; the region of a CFLocale identifier, "en_AU" or "en_TH@rg=thzzzz": what
;; follows the last _ before any @, or #f
(define (locale-id-region id)
  (let* ([at (let loop ([i 0]) (if (or (fx= i (string-length id)) (char=? (string-ref id i) #\@)) i (loop (fx+ i 1))))]
         [id (substring id 0 at)]
         [us (let loop ([i (fx- (string-length id) 1)])
               (cond [(fx< i 0) #f] [(char=? (string-ref id i) #\_) i] [else (loop (fx- i 1))]))])
    (and us (substring id (fx+ us 1) (string-length id)))))
(define (macos-locale* buf)
  (guard (e [#t #f])
    (load-shared-object "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    (let* ([copy-languages (foreign-procedure "CFLocaleCopyPreferredLanguages" () uptr)]
           [array-count (foreign-procedure "CFArrayGetCount" (uptr) long)]
           [array-at (foreign-procedure "CFArrayGetValueAtIndex" (uptr long) uptr)]
           [string-text (foreign-procedure "CFStringGetCString" (uptr uptr long unsigned-32) unsigned-8)]
           [copy-current (foreign-procedure "CFLocaleCopyCurrent" () uptr)]
           [identifier (foreign-procedure "CFLocaleGetIdentifier" (uptr) uptr)]
           [release (foreign-procedure "CFRelease" (uptr) void)]
           [text (lambda (cf) (and (not (eqv? cf 0)) (fx> (string-text cf buf 128 #x08000100) 0) (c-string-text buf)))]
           [languages (copy-languages)]
           [first (and (not (eqv? languages 0)) (fx> (array-count languages) 0) (text (array-at languages 0)))]
           [region (let* ([cur (copy-current)] [id (text (identifier cur))])
                     (release cur)
                     (and id (locale-id-region id)))])
      (unless (eqv? languages 0) (release languages))
      (and first
           (let ([p (locale-parts first)])
             (if (and (not (caddr p)) region) (list (car p) (cadr p) region) p))))))
(define (linux-locales)   ; (display . format)
  (let* ([setlocale (foreign-procedure "setlocale" (int string) string)]
         [saved (setlocale 6 #f)]   ; LC_ALL
         [set? (setlocale 6 "")]
         [messages (and set? (setlocale 5 #f))]   ; LC_MESSAGES
         [ctype (and set? (setlocale 0 #f))])     ; LC_CTYPE
    (when set? (setlocale 6 saved))
    (cons (posix-locale-parts messages) (posix-locale-parts ctype))))
;; user.name and user.home as the JDK finds them: the password database's
;; entry for getuid() (name "?" without one), its home directory unless that
;; is missing or under two characters, then $HOME. USER and HOME don't
;; decide either (a container's root has no USER).
(define (password-entry)
  (let* ([getpwuid (foreign-procedure "getpwuid" (unsigned-32) uptr)]
         [getuid (foreign-procedure "getuid" () unsigned-32)]
         [pw (getpwuid (getuid))]
         ;; struct passwd: pw_name first; pw_dir after pw_change, pw_class and
         ;; pw_gecos on macOS (48), after pw_gecos on glibc (32)
         [field (lambda (off) (and (not (eqv? pw 0))
                                   (let ([p (foreign-ref 'uptr pw off)]) (and (not (eqv? p 0)) (c-string-text p)))))]
         [name (or (field 0) "?")]
         [dir (field (if natives-macos? 48 32))])
    (values name
            (if (and dir (fx>= (string-length dir) 2)) dir (or (getenv "HOME") dir "?")))))
(define (host-locales)
  (if natives-macos?
      (let ([l (or (macos-locale) (posix-locale-parts #f))]) (cons l l))
      (linux-locales)))
;; java.io.tmpdir: macOS's per-user directory (confstr's
;; _CS_DARWIN_USER_TEMP_DIR), else P_tmpdir, as the JDK
;; native.encoding as the JDK's launcher finds it (java_props_md.c
;; ParseLocale): the codeset of the user's LC_CTYPE, setlocale(LC_ALL, "")
;; and nl_langinfo(CODESET), the process's own locale put back afterwards.
;; A locale named .ISO8859-15 keeps that name, Solaris's 646 is ISO646-US,
;; an empty codeset ISO8859-1, Linux's EUC-JP the JDK's EUC-JP-LINUX, and on
;; macOS US-ASCII with none of LANG, LC_ALL, LC_CTYPE set is UTF-8 (a
;; program launched from the Finder). So a Linux shell with no locale gets
;; ANSI_X3.4-1968, as HotSpot does.
;; (LC_CTYPE's locale name . its codeset) under the user's locale, the
;; process's own put back after
(define (locale-ctype-codeset)
  (let* ([setlocale (foreign-procedure "setlocale" (int string) string)]
         [langinfo (foreign-procedure "nl_langinfo" (int) string)]
         [lc-all (if natives-macos? 0 6)]
         [saved (setlocale lc-all #f)])
    (setlocale lc-all "")
    (let ([r (cons (or (setlocale (if natives-macos? 2 0) #f) "C") (or (langinfo (if natives-macos? 0 14)) ""))])
      (setlocale lc-all saved)
      r)))
;; the encoding a locale name like "de_DE.ISO8859-15@euro" names, or #f
(define (locale-name-encoding ctype)
  (let ([dot (char-index-from ctype #\. 0)])
    (and dot (substring ctype (fx+ dot 1) (or (char-index-from ctype #\@ dot) (string-length ctype))))))
(define (char-index-from s c k)
  (cond [(fx= k (string-length s)) #f] [(char=? (string-ref s k) c) k] [else (char-index-from s c (fx+ k 1))]))
(define (env-unset? v) (let ([x (getenv v)]) (or (not x) (string=? x ""))))
;; the JDK's name for the codeset (ParseLocale's mapping)
(define (jdk-encoding-name ctype codeset)
  (let* ([p (if (equal? (locale-name-encoding ctype) "ISO8859-15") "ISO8859-15" codeset)]
         [p (cond [(string=? p "646") "ISO646-US"] [(string=? p "") "ISO8859-1"] [else p])])
    (cond [(and (not natives-macos?) (string=? p "EUC-JP")) "EUC-JP-LINUX"]
          [(and natives-macos? (string=? p "US-ASCII") (env-unset? "LANG") (env-unset? "LC_ALL") (env-unset? "LC_CTYPE")) "UTF-8"]
          [else p])))
(define (host-encoding)
  (let ([cc (locale-ctype-codeset)]) (jdk-encoding-name (car cc) (cdr cc))))
(define (host-tmpdir)
  (if natives-macos?
      (let* ([confstr (foreign-procedure "confstr" (int uptr size_t) size_t)]
             [buf (foreign-alloc 1024)]
             [n (confstr 65537 buf 1024)]
             [d (and (fx> n 0) (fx<= n 1024) (c-string-text buf))])
        (foreign-free buf)
        (or d "/var/tmp/"))
      "/tmp"))

;; os.version must parse as a version: CLDR's locale provider rejects "".
;; macOS reports the product version (as the JDK does), others the kernel's.
(define (first-line-of cmd)
  (guard (e [#t #f])
    (let-values ([(to-stdin from-stdout from-stderr pid)
                  (open-process-ports cmd (buffer-mode block) (native-transcoder))])
      (close-port to-stdin)
      (let ([l (get-line from-stdout)])
        (close-port from-stdout) (close-port from-stderr)
        (and (string? l) (positive? (string-length l)) l)))))
;; TimeZone's default: TZ if set, else the zone /etc/localtime links to
;; ("/var/db/timezone/zoneinfo/Asia/Bangkok" -> "Asia/Bangkok"), else GMT.
(define (zone-after-zoneinfo path)
  (let ([key "zoneinfo/"])
    (let loop ([i 0])
      (cond [(fx> (fx+ i (string-length key)) (string-length path)) #f]
            [(string=? key (substring path i (fx+ i (string-length key))))
             (substring path (fx+ i (string-length key)) (string-length path))]
            [else (loop (fx+ i 1))]))))
(defnative "java/util/TimeZone.getSystemTimeZoneID(Ljava/lang/String;)Ljava/lang/String;" (java-home)
  (jstring (let ([tz (getenv "TZ")])
             (if (and tz (positive? (string-length tz)))
                 (if (char=? (string-ref tz 0) #\:) (substring tz 1 (string-length tz)) tz)
                 (or (let ([l (first-line-of "readlink /etc/localtime 2>/dev/null")])
                       (and l (zone-after-zoneinfo l)))
                     "GMT")))))

(define (os-version)
  (or (first-line-of "sw_vers -productVersion 2>/dev/null")
      (first-line-of "uname -r 2>/dev/null")
      "0.0"))

(defnative "jdk/internal/util/SystemProps$Raw.platformProperties()[Ljava/lang/String;" ()
  (let* ([ndx (lambda (f) (top-level-value (string->symbol (string-append "jdk/internal/util/SystemProps$Raw:" f))))]
         [v (make-vector (ndx "FIXED_LENGTH") #f)]
         [set (lambda (f s) (vector-set! v (ndx f) (jstring s)))])
    (set "_file_separator_NDX" "/")
    (set "_path_separator_NDX" ":")
    (set "_line_separator_NDX" "\n")
    ;; the console streams take it only on a terminal; elsewhere the JDK
    ;; falls back to native.encoding itself; file names are UTF-8 on macOS
    (let ([enc (host-encoding)])
      (set "_native_encoding_NDX" enc)
      (set "_sun_jnu_encoding_NDX" (if natives-macos? "UTF-8" enc))
      (for-each (lambda (fd f) (when (fx= 1 (libc-isatty fd)) (set f enc)))
                '(0 1 2) '("_stdin_encoding_NDX" "_stdout_encoding_NDX" "_stderr_encoding_NDX")))
    ;; as the JVM names them: x86_64 is "amd64" on Linux, "x86_64" on macOS
    (set "_os_name_NDX" (if (memq (machine-type) '(tarm64osx arm64osx ta6osx a6osx)) "Mac OS X" "Linux"))
    ;; anything else isn't supported; it reports Chez's own machine name
    (set "_os_arch_NDX" (case (machine-type)
                          [(tarm64osx arm64osx tarm64le arm64le) "aarch64"]
                          [(ta6osx a6osx) "x86_64"]
                          [(ta6le a6le) "amd64"]
                          [else (symbol->string (machine-type))]))
    (set "_os_version_NDX" (os-version))
    (set "_java_io_tmpdir_NDX" (host-tmpdir))
    (let ([ls (host-locales)])
      (for-each (lambda (which parts)
                  (for-each (lambda (field v) (when v (set (string-append "_" which "_" field "_NDX") v)))
                            '("language" "script" "country") parts))
                '("display" "format") (list (car ls) (cdr ls))))
    (set "_user_dir_NDX" (current-directory))
    (let-values ([(name home) (password-entry)])
      (set "_user_home_NDX" home)
      (set "_user_name_NDX" name))
    (set "_sun_cpu_endian_NDX" "little")
    (set "_sun_arch_data_model_NDX" "64")
    ;; as the JDK's own: UnicodeBig on macOS whatever the byte order
    (set "_sun_io_unicode_encoding_NDX" (if natives-macos? "UnicodeBig" "UnicodeLittle"))
    (make-jarr "Ljava/lang/String;" v)))
