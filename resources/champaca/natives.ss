;; Native method implementations, keyed by the same global name the
;; translator uses: "Class.name(desc)". Instance natives take `this` first.
;; This is the spike's equivalent of OpenLDK's src/native/*.lisp.

(define-syntax defnative
  (syntax-rules ()
    [(_ name (args ...) body ...) (define-native! name (lambda (args ...) body ...))]))

(define (void-native . _) (void))

;; registerNatives/initIDs style hooks do nothing here.
(define-native-rule!
  (lambda (n) (exists (lambda (suffix) (let ([ls (string-length n)] [lx (string-length suffix)])
                                         (and (fx>= ls lx) (string=? suffix (substring n (fx- ls lx) ls)))))
                      '(".registerNatives()V" ".initIDs()V" ".initNative()V")))
  (lambda (n) void-native))

;; --- java.lang.Object ------------------------------------------------------------

(defnative "java/lang/Object.getClass()Ljava/lang/Class;" (this) (jmirror (class-of this)))
(defnative "java/lang/Object.hashCode()I" (this) (identity-hash this))
(defnative "java/lang/Object.clone()Ljava/lang/Object;" (this)
  (cond [(jarr? this) (array-clone this)]
        ;; HotSpot's check, its message the class's name
        [(not (jinstance? this "java/lang/Cloneable"))
         (jthrow "java/lang/CloneNotSupportedException" (slashes->dots (jclass-cname (jobj-cls this))))]
        [else (make-jobj (jobj-cls this) (vector-copy (jobj-slots this)))]))

;; --- java.lang.System ------------------------------------------------------------

(define (now-nanos) (let ([t (current-time 'time-utc)]) (+ (* (time-second t) 1000000000) (time-nanosecond t))))
(defnative "java/lang/Shutdown.beforeHalt()V" () (void))
;; On the main thread the status unwinds to run-guarded, so it reaches whoever
;; started the Java code. Any other thread ends the process from where it is,
;; through jolt's jolt-exit-process: Chez's exit there would end only the thread.
(define (exit-process status)
  (if (top-level-bound? 'jolt-exit-process)
      ((top-level-value 'jolt-exit-process) status)
      (begin (flush-output-port (current-output-port)) (exit status))))
(defnative "java/lang/Shutdown.halt0(I)V" (status)
  (if (eq? (self) main-state)
      (raise (make-java-halt status))
      (exit-process status)))
(defnative "java/lang/System.currentTimeMillis()J" () (quotient (now-nanos) 1000000))
(defnative "java/lang/System.nanoTime()J" ()
  (let ([t (current-time 'time-monotonic)]) (+ (* (time-second t) 1000000000) (time-nanosecond t))))
(defnative "java/lang/System.identityHashCode(Ljava/lang/Object;)I" (x) (identity-hash x))
;; HotSpot's checks, order and messages: both arrays, the same primitive type
;; or both reference arrays, then the indexes. A reference copy whose source
;; element type isn't assignable to the destination's checks each element and
;; stops at the first that doesn't fit, leaving the ones before it copied.
(define (arraycopy-type-name e) (if (prim-desc? e) (desc->class-name e) "object array"))
(define (arraycopy-fail cls msg) (jthrow cls (string-append "arraycopy: " msg)))
;; vector-copy!-style copy that handles overlap within one vector
(define (arraycopy-move! sv sp dv dp n)
  (if (and (eq? sv dv) (fx< sp dp))
      (do ([i (fx- n 1) (fx- i 1)]) ((fx< i 0)) (vector-set! dv (fx+ dp i) (vector-ref sv (fx+ sp i))))
      (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! dv (fx+ dp i) (vector-ref sv (fx+ sp i))))))

;; the common case first: two arrays of one (interned) element type and
;; indexes in range need none of the checks below
(defnative "java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V" (src sp dst dp n)
  (if (and (jarr? src) (jarr? dst) (eq? (jarr-elem src) (jarr-elem dst))
           (fx>= sp 0) (fx>= dp 0) (fx>= n 0)
           (fx<= (fx+ sp n) (vector-length (jarr-vec src))) (fx<= (fx+ dp n) (vector-length (jarr-vec dst))))
      (arraycopy-move! (jarr-vec src) sp (jarr-vec dst) dp n)
      (arraycopy-checked src sp dst dp n)))

(define (arraycopy-checked src sp dst dp n)
  (let ([src (nn src)] [dst (nn dst)])
    (unless (jarr? src)
      (arraycopy-fail "java/lang/ArrayStoreException"
                      (string-append "source type " (slashes->dots (class-name-of src)) " is not an array")))
    (unless (jarr? dst)
      (arraycopy-fail "java/lang/ArrayStoreException"
                      (string-append "destination type " (slashes->dots (class-name-of dst)) " is not an array")))
    (let* ([se (jarr-elem src)] [de (jarr-elem dst)] [sv (jarr-vec src)] [dv (jarr-vec dst)]
           [sname (arraycopy-type-name se)]
           [bounds (lambda (msg) (arraycopy-fail "java/lang/ArrayIndexOutOfBoundsException" msg))]
           [out-of (lambda (e v) (string-append " out of bounds for " (arraycopy-type-name e)
                                                "[" (number->string (vector-length v)) "]"))])
      (unless (if (prim-desc? se) (string=? se de) (not (prim-desc? de)))
        (arraycopy-fail "java/lang/ArrayStoreException"
                        (string-append "type mismatch: can not copy " sname "[] into "
                                       (arraycopy-type-name de) "[]")))
      (cond [(fx< sp 0) (bounds (string-append "source index " (number->string sp) (out-of se sv)))]
            [(fx< dp 0) (bounds (string-append "destination index " (number->string dp) (out-of de dv)))]
            [(fx< n 0) (bounds (string-append "length " (number->string n) " is negative"))]
            [(fx> (fx+ sp n) (vector-length sv))
             (bounds (string-append "last source index " (number->string (fx+ sp n)) (out-of se sv)))]
            [(fx> (fx+ dp n) (vector-length dv))
             (bounds (string-append "last destination index " (number->string (fx+ dp n)) (out-of de dv)))])
      (cond
        ;; descriptors are interned, so the same element type is eq?
        [(or (eq? se de) (prim-desc? se) (string=? de "Ljava/lang/Object;")
             (class-assignable? (string-append "[" se) (string-append "[" de)))
         (arraycopy-move! sv sp dv dp n)]
        ;; each element, up to the first that doesn't fit; the message says
        ;; whether the copy narrows or the element types are unrelated
        [else
         (do ([i 0 (fx+ i 1)]) ((fx= i n))
           (let ([x (vector-ref sv (fx+ sp i))])
             (unless (or (not x) (array-store-ok? dst x))
               (let ([sn (slashes->dots (desc->class-name se))] [dn (slashes->dots (desc->class-name de))])
                 (arraycopy-fail "java/lang/ArrayStoreException"
                                 (if (class-assignable? (string-append "[" de) (string-append "[" se))
                                     (string-append "element type mismatch: can not cast one of the elements of "
                                                    sn "[] to the type of the destination array, " dn)
                                     (string-append "type mismatch: can not copy " sn "[] into " dn "[]")))))
             (vector-set! dv (fx+ dp i) x)))]))))
(defnative "java/lang/System.setIn0(Ljava/io/InputStream;)V" (s) (set-top-level-value! '|java/lang/System:in| s))
(defnative "java/lang/System.setOut0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:out| s))
(defnative "java/lang/System.setErr0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:err| s))

;; --- java.management ---------------------------------------------------------------------
;; Enough of sun.management for the Runtime, Memory, OperatingSystem and
;; ClassLoading beans and ThreadMXBean's counts: the VM's counters, from what
;; the runtime keeps, and zeros for the HotSpot-only ones (safepoints, JIT
;; and class-verification times), and the threads' CPU times (Thread
;; section). ThreadInfo and deadlock detection stay missing.
(define vm-start-ms (quotient (now-nanos) 1000000))
(define vm-start-mono (current-time 'time-monotonic))   ; uptime survives a clock step
(define (uptime-ms)
  (let ([d (time-difference (current-time 'time-monotonic) vm-start-mono)])
    (+ (* 1000 (time-second d)) (quotient (time-nanosecond d) 1000000))))
(let ()
  (define cls "sun/management/VMManagementImpl")
  (define (def name proc) (define-native! (string-append cls "." name) proc))
  (def "getVersion0()Ljava/lang/String;" (lambda () (jstring "4.0")))
  ;; the CPU times of the current thread and of others are measured (below);
  ;; the other optional supports stay off
  (def "initOptionalSupportFields()V"
       (lambda ()
         (set-top-level-value! '|sun/management/VMManagementImpl:currentThreadCpuTimeSupport| 1)
         (set-top-level-value! '|sun/management/VMManagementImpl:otherThreadCpuTimeSupport| 1)))
  (def "isThreadCpuTimeEnabled()Z" (lambda (this) thread-cpu-enabled))
  (for-each (lambda (n) (def n (lambda (this) 0)))
            '("isThreadContentionMonitoringEnabled()Z"
              "isThreadAllocatedMemoryEnabled()Z" "getVerboseClass()Z" "getVerboseGC()Z"
              "getUnloadedClassCount()J" "getTotalCompileTime()J" "getSafepointCount()J"
              "getTotalSafepointTime()J" "getSafepointSyncTime()J" "getTotalApplicationNonStoppedTime()J"
              "getLoadedClassSize()J" "getUnloadedClassSize()J" "getClassLoadingTime()J"
              "getMethodDataSize()J" "getClassInitializationTime()J" "getClassVerificationTime()J"))
  (def "getProcessId()I" (lambda (this) (libc-getpid)))
  ;; the same list as VM.getRuntimeArguments (runtime-arguments, below), as HotSpot's jmm
  (def "getVmArguments0()[Ljava/lang/String;" (lambda (this) (make-jarr "Ljava/lang/String;" (list->vector (map jstring runtime-arguments)))))
  (def "getStartupTime()J" (lambda (this) vm-start-ms))
  (def "getUptime0()J" (lambda (this) (uptime-ms)))
  (def "getAvailableProcessors()I" (lambda (this) 1))   ; as Runtime says
  (def "getTotalClassCount()J" (lambda (this) (hashtable-size classes)))
  (def "getInitializedClassCount()J"
       (lambda (this) (length (filter (lambda (c) (eq? (jclass-state c) 'initialized)) (vector->list (hashtable-values classes))))))
  (def "getTotalThreadCount()J" (lambda (this) (fx+ 1 threads-started)))
  (def "getLiveThreadCount()I" (lambda (this) (fx+ 1 (length java-live-threads))))
  (def "getPeakThreadCount()I" (lambda (this) threads-peak))
  (def "getDaemonThreadCount()I" (lambda (this) (length (filter thread-daemon? java-live-threads)))))

;; heap: Chez's allocated and committed bytes, no limit; non-heap: nothing
(define (memory-usage init used committed max)
  (jinit "java/lang/management/MemoryUsage")
  (let ([u (jnew "java/lang/management/MemoryUsage")])
    ((method-proc "java/lang/management/MemoryUsage" "<init>(JJJJ)V") u init used committed max)
    u))
(defnative "sun/management/MemoryImpl.getMemoryUsage0(Z)Ljava/lang/management/MemoryUsage;" (this heap)
  (if (eqv? heap 1)
      (let ([used (bytes-allocated)]) (memory-usage -1 used (max used (current-memory-bytes)) -1))
      (memory-usage -1 0 0 -1)))
(defnative "sun/management/MemoryImpl.getMemoryPools0()[Ljava/lang/management/MemoryPoolMXBean;" ()
  (make-jarr "Ljava/lang/management/MemoryPoolMXBean;" (vector)))
(defnative "sun/management/MemoryImpl.getMemoryManagers0()[Ljava/lang/management/MemoryManagerMXBean;" ()
  (make-jarr "Ljava/lang/management/MemoryManagerMXBean;" (vector)))
(defnative "sun/management/MemoryImpl.setVerboseGC(Z)V" (this on) (void))
(defnative "sun/management/ClassLoadingImpl.setVerboseClass(Z)V" (on) (void))
;; OperatingSystemMXBean.getSystemLoadAverage: the 1, 5 and 15 minute figures
(define libc-getloadavg (foreign-procedure "getloadavg" (uptr int) int))
(defnative "jdk/internal/misc/Unsafe.getLoadAverage0([DI)I" (this arr n)
  (let* ([n (fxmin n 3 (vector-length (jarr-vec (nn arr))))] [buf (foreign-alloc 24)])
    (let ([got (libc-getloadavg buf n)])
      (do ([i 0 (fx+ i 1)]) ((fx>= i got)) (vector-set! (jarr-vec arr) i (foreign-ref 'double buf (fx* i 8))))
      (foreign-free buf)
      got)))
(defnative "sun/management/ThreadImpl.getThreads()[Ljava/lang/Thread;" ()
  (make-jarr "Ljava/lang/Thread;" (list->vector (live-jthreads))))
;; jdk.management's OperatingSystemImpl is the OS bean once the module system
;; has booted (ManagementFactory finds its provider in the boot layer, as the
;; JVM does): process CPU time from the runtime, the host's CPU counts from
;; sysconf, and -1 for what isn't measured here (memory and swap sizes, CPU
;; loads, file descriptor counts), which the bean's contract allows.
(let ()
  (define cls "com/sun/management/internal/OperatingSystemImpl")
  (define (def name proc) (define-native! (string-append cls "." name) proc))
  (define libc-sysconf (foreign-procedure "sysconf" (int) long))
  (define (cpu-count online?)   ; _SC_NPROCESSORS_CONF / _SC_NPROCESSORS_ONLN
    (max 1 (libc-sysconf (if natives-macos? (if online? 58 57) (if online? 84 83)))))
  (def "initialize0()V" (lambda () (void)))
  (def "getProcessCpuTime0()J"
       (lambda (this) (let ([t (current-time 'time-process)]) (+ (* 1000000000 (time-second t)) (time-nanosecond t)))))
  (def "getHostConfiguredCpuCount0()I" (lambda (this) (cpu-count #f)))
  (def "getHostOnlineCpuCount0()I" (lambda (this) (cpu-count #t)))
  (for-each (lambda (n) (def n (lambda (this) -1)))
            '("getCommittedVirtualMemorySize0()J" "getFreeMemorySize0()J" "getFreeSwapSpaceSize0()J"
              "getMaxFileDescriptorCount0()J" "getOpenFileDescriptorCount0()J" "getTotalMemorySize0()J"
              "getTotalSwapSpaceSize0()J" "getHostTotalCpuTicks0()J"))
  (for-each (lambda (n) (def n (lambda (this) -1.0))) '("getProcessCpuLoad0()D" "getCpuLoad0()D"))
  (def "getSingleCpuLoad0(I)D" (lambda (this cpu) -1.0)))

;; --- environment, process ids, native libraries ----------------------------------------

(define natives-macos? (and (memq (machine-type) '(tarm64osx arm64osx ta6osx a6osx)) #t))
(define java-home-dir (or (getenv "JAVA_HOME") "/"))   ; the java.home property too
;; the user classpath as given, for java.class.path (the launcher's -cp)
(define classpath-string "")
(define (set-classpath! s) (set! classpath-string s))

;; The environment as ProcessEnvironment wants it: name and value bytes
;; alternating, each entry split at its first '='. macOS reaches environ
;; through _NSGetEnviron(); Linux exports the variable itself.
(define (environ-pointer)
  (cond [(foreign-entry? "_NSGetEnviron") (foreign-ref 'uptr ((foreign-procedure "_NSGetEnviron" () uptr)) 0)]
        [(foreign-entry? "environ") (foreign-ref 'uptr (foreign-entry "environ") 0)]
        [else 0]))
(defnative "java/lang/ProcessEnvironment.environ()[[B" ()
  (let ([envp (environ-pointer)])
    (let loop ([i 0] [acc '()])
      (let ([p (if (eqv? envp 0) 0 (foreign-ref 'uptr envp (fx* i (foreign-sizeof 'uptr))))])
        (if (eqv? p 0)
            (make-jarr "[B" (list->vector (reverse acc)))
            (let* ([n (cstring-length p)]
                   [eq (let find ([j 0])
                         (cond [(fx= j n) #f] [(fx= (foreign-ref 'unsigned-8 p j) 61) j] [else (find (fx+ j 1))]))])
              (loop (fx+ i 1)
                    (if eq
                        (cons (cbytes (+ p eq 1) (fx- n eq 1)) (cons (cbytes p eq) acc))
                        acc))))))))

(define libc-getpid (foreign-procedure "getpid" () int))
(define libc-getppid (foreign-procedure "getppid" () int))
(define libc-kill (foreign-procedure "kill" (int int) int))
;; ProcessImpl's class setup; launching, waiting and destroying are in
;; spawn.ss. ProcessEnvironment needs its charset.
(defnative "java/lang/ProcessImpl.init()V" () (void))
(defnative "java/lang/ProcessHandleImpl.initNative()V" () (void))
(defnative "java/lang/ProcessHandleImpl.getCurrentPid0()J" () (libc-getpid))
;; The JVM answers a live process's start time, or -1 for one that is gone;
;; 0 is a start time ProcessHandleImpl treats as unknown. kill(pid, 0) tells
;; the two apart, and EPERM still means the process exists.
;; A pid is a positive int; kill would read 0 and -1 as process groups, and
;; a long outside int range can't even be passed.
(define (pid-ok? pid) (and (>= pid 1) (<= pid 2147483647)))
(defnative "java/lang/ProcessHandleImpl.isAlive0(J)J" (pid)
  (if (and (pid-ok? pid) (or (eqv? 0 (libc-kill pid 0)) (eqv? (errno) 1))) 0 -1))
;; The parent of any process: proc_pidinfo's proc_bsdinfo on macOS (ppid is
;; the 32-bit field at byte 16, size 136, measured on arm64 and x86_64), or
;; /proc/<pid>/stat on Linux, whose ppid follows the parenthesized command
;; name (which may itself hold spaces and parens) and the state letter.
(define libc-proc-pidinfo
  (and natives-macos? (foreign-procedure "proc_pidinfo" (int int unsigned-64 uptr int) int)))
(define (parent-pid pid)
  (if natives-macos?
      (let* ([buf (foreign-alloc 136)]
             [n (libc-proc-pidinfo pid 3 0 buf 136)]   ; 3 = PROC_PIDTBSDINFO
             [ppid (if (fx= n 136) (foreign-ref 'unsigned-32 buf 16) -1)])
        (foreign-free buf)
        ppid)
      (guard (e [#t -1])
        (let* ([text (call-with-port (open-input-file (string-append "/proc/" (number->string pid) "/stat")) get-string-all)]
               [start (let loop ([i (fx- (string-length text) 1)])
                        (if (char=? (string-ref text i) #\)) (fx+ i 4) (loop (fx- i 1))))]
               [end (let loop ([j start]) (if (char-numeric? (string-ref text j)) (loop (fx+ j 1)) j))])
          (string->number (substring text start end))))))
(defnative "java/lang/ProcessHandleImpl.parent0(JJ)J" (pid start)
  (cond [(not (pid-ok? pid)) -1]
        [(eqv? pid (libc-getpid)) (libc-getppid)]
        [else (parent-pid pid)]))

(defnative "java/lang/System.mapLibraryName(Ljava/lang/String;)Ljava/lang/String;" (name)
  (jstring (string-append "lib" (jstring->scheme (nn name)) (if natives-macos? ".dylib" ".so"))))
;; The JDK's own libraries are champaca's natives, so loading one is a no-op.
;; Nothing else can load (there is no JNI), which is the JVM's error for a
;; library that isn't on java.library.path.
(define-override! "java/lang/ClassLoader.loadLibrary(Ljava/lang/Class;Ljava/lang/String;)Ljdk/internal/loader/NativeLibrary;"
  (lambda (from name)
    (let ([n (jstring->scheme (nn name))])
      (if (file-exists? (string-append java-home-dir "/lib/lib" n (if natives-macos? ".dylib" ".so")))
          #f   ; Runtime.loadLibrary0 drops the result
          (jthrow "java/lang/UnsatisfiedLinkError"
                  (string-append "no " n " in java.library.path, and champaca loads no JNI libraries"))))))

;; --- jdk.internal.vm.vector (the Vector API's VM side) --------------------------
;; Every VectorSupport intrinsic is a Java method with a Java fallback, which
;; is what runs here; the VM answers only these. A vector's width is
;; HotSpot's on the machines measured: 128 bits on arm64 (NEON, -Xint too),
;; and AVX2's 256 on x86_64, whatever the CPU has.
(define vector-bytes (case (machine-type) [(ta6osx a6osx ta6le a6le) 32] [else 16]))
(defnative "jdk/internal/vm/vector/VectorSupport.registerNatives()I" () 0)
(defnative "jdk/internal/vm/vector/VectorSupport.getMaxLaneCount(Ljava/lang/Class;)I" (etype)
  (let ([size (case (mirror->desc etype) [("B" "Z") 1] [("S" "C") 2] [("I" "F") 4] [("J" "D") 8] [else #f])])
    (if size (fxquotient vector-bytes size) -1)))
(defnative "jdk/internal/vm/vector/VectorSupport.getCPUFeatures()Ljava/lang/String;" () (jstring ""))
;; The vector math library (SLEEF, SVML) is found through FFM symbol lookup,
;; which champaca lacks, so it fails to load as a missing library does on
;; HotSpot, and VectorMathLibrary falls back to its Java implementations.
(define-override! "jdk/internal/vm/vector/VectorSupport.loadNativeLibrary(Ljava/lang/String;)V"
  (lambda (name)
    (jthrow "java/lang/UnsatisfiedLinkError"
            (string-append "no " (jstring->scheme (nn name)) ": champaca has no vector math library"))))

(defnative "java/lang/Runtime.availableProcessors()I" (this) 1)
;; Chez's heap: committed and allocated bytes; max is a nominal 4 GB, or
;; the heap once it's bigger
(defnative "java/lang/Runtime.maxMemory()J" (this) (max 4294967296 (current-memory-bytes)))
(defnative "java/lang/Runtime.totalMemory()J" (this) (current-memory-bytes))
(defnative "java/lang/Runtime.freeMemory()J" (this) (max 0 (- (current-memory-bytes) (bytes-allocated))))
;; a full collection through jolt's own entry (jolt-collect-full!), which
;; waits for the other threads at a rendezvous; Chez's (collect) refuses while
;; another thread is active, and ended the process with a Scheme error
(define full-collect   ; jolt has had it since before 0.8.13; the pin is newer
  (if (top-level-bound? 'jolt-collect-full!) (top-level-value 'jolt-collect-full!) (lambda () (collect (collect-maximum-generation)))))
(defnative "java/lang/Runtime.gc()V" (this) (full-collect))

;; --- java.lang.Class --------------------------------------------------------------

(defnative "java/lang/Class.getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;" (n)
  (jmirror-of (jstring->scheme n)))
;; --enable-preview, from champaca.main (set-preview!, before the boot);
;; main.clj's parser lets this release's preview class files load by the
;; same flag
(define preview-enabled #f)
(define (set-preview! on) (set! preview-enabled on))
(defnative "jdk/internal/misc/PreviewFeatures.isPreviewEnabled()Z" () (if preview-enabled 1 0))

;; Assertions, from champaca.main's -ea, -da, -esa and -dsa options
;; (set-assertion-directives!, before the boot, as -D properties are): each
;; directive is "c" (a class), "p" (a package, "" the unnamed one), "u" (the
;; default for classes the boot loader didn't define) or "s" (for those it
;; did), then "+" or "-", then the name, slashed. HotSpot's JavaAssertions:
;; the class's own directive, else its most specific package's, else the
;; default, the last directive of each winning; a platform loader's class is
;; a user class here as there.
(define assertion-directives '())   ; ((kind enabled? name) ...), latest first
(define (set-assertion-directives! s)
  (set! assertion-directives
        (let split ([i 0] [start 0] [acc '()])
          (cond [(fx= 0 (string-length s)) acc]
                [(or (fx= i (string-length s)) (char=? (string-ref s i) #\nul))
                 (let ([d (substring s start i)])
                   (let ([acc (cons (list (string-ref d 0) (char=? (string-ref d 1) #\+) (substring d 2 (string-length d))) acc)])
                     (if (fx= i (string-length s)) acc (split (fx+ i 1) (fx+ i 1) acc))))]
                [else (split (fx+ i 1) start acc)]))))
(define (assertion-directive kind name)
  (find (lambda (d) (and (char=? (car d) kind) (string=? (caddr d) name))) assertion-directives))
(define (assertions-enabled? cname system?)
  (let ([pkg (package-dir cname)])
    (cond [(assertion-directive #\c cname) => cadr]
          ;; the package, then each enclosing one; a class in the unnamed
          ;; package matches -ea:... alone, one in a package never does
          [(let loop ([p pkg])
             (or (assertion-directive #\p p)
                 (let ([i (let back ([j (fx- (string-length p) 1)])
                            (cond [(fx< j 0) #f] [(char=? (string-ref p j) #\/) j] [else (back (fx- j 1))]))])
                   (and i (loop (substring p 0 i))))))
           => cadr]
          [(find (lambda (d) (char=? (car d) (if system? #\s #\u))) assertion-directives) => cadr]
          [else #f])))
(defnative "java/lang/Class.desiredAssertionStatus0(Ljava/lang/Class;)Z" (c)
  (let ([k (mirror-class c)])
    ;; an array or a primitive never has assertions on, as on HotSpot
    (if (and (not (memq (jclass-kind k) '(array prim)))
             (assertions-enabled? (jclass-cname k) (not (class-loader-of k))))
        1 0)))
;; ClassLoader's own maps start from these (initializeJavaAssertionMaps):
;; classes and packages dotted, in command-line order, so the last one wins
(defnative "java/lang/ClassLoader.retrieveDirectives()Ljava/lang/AssertionStatusDirectives;" ()
  (let* ([d (construct "java/lang/AssertionStatusDirectives" "()V")]
         [in-order (reverse assertion-directives)]
         [of (lambda (kind) (filter (lambda (x) (char=? (car x) kind)) in-order))]
         [names (lambda (ds) (make-jarr "Ljava/lang/String;" (list->vector (map (lambda (x) (jstring (slashes->dots (caddr x)))) ds))))]
         [flags (lambda (ds) (make-jarr "Z" (list->vector (map (lambda (x) (if (cadr x) 1 0)) ds))))]
         [set (lambda (f v) (jslot-set! d (slot-of "java/lang/AssertionStatusDirectives" f) v))])
    (set "classes" (names (of #\c)))
    (set "classEnabled" (flags (of #\c)))
    (set "packages" (names (of #\p)))
    (set "packageEnabled" (flags (of #\p)))
    (set "deflt" (let ([u (find (lambda (x) (char=? (car x) #\u)) assertion-directives)]) (if (and u (cadr u)) 1 0)))
    d))
(defnative "java/lang/Class.isArray()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'array) 1 0))
;; the JDK's own jimage reader (the launcher's resource bundles, jrt:) asks
;; for the VM's map of lib/modules first; null is "no map", so it reads the
;; file through a channel, as on a VM that didn't open it
(defnative "jdk/internal/jimage/NativeImageBuffer.getNativeMap(Ljava/lang/String;)Ljava/nio/ByteBuffer;" (path) #f)
(defnative "java/lang/Class.isPrimitive()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'prim) 1 0))
(defnative "java/lang/Class.isInterface()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'interface) 1 0))
(defnative "java/lang/Class.isHidden()Z" (this) (if (hidden-class? (jclass-cname (mirror-class this))) 1 0))
(defnative "java/lang/Class.getModifiers()I" (this) (class-modifiers (mirror-class this)))
(defnative "java/lang/Class.getSuperclass()Ljava/lang/Class;" (this)
  (let ([c (mirror-class this)])
    (and (jclass-super c) (not (eq? (jclass-kind c) 'interface)) (jmirror-of (jclass-super c)))))
(defnative "java/lang/Class.initClassName()Ljava/lang/String;" (this)
  (let* ([n (jclass-cname (mirror-class this))]
         [s (jstring (if (hidden-class? n)
                         ;; pkg/Name/0x1 prints as pkg.Name/0x1, as HotSpot prints a hidden class
                         (let ([i (let loop ([i (fx- (string-length n) 1)]) (if (char=? (string-ref n i) #\/) i (loop (fx- i 1))))])
                           (string-append (slashes->dots (substring n 0 i)) (substring n i (string-length n))))
                         (slashes->dots n)))])
    (jslot-set! this (jfield-index "java/lang/Class" "name") s)
    s))
(defnative "java/lang/Class.isInstance(Ljava/lang/Object;)Z" (this x)
  (if (jinstance? x (jclass-cname (mirror-class this))) 1 0))
(defnative "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z" (this other)
  (if (class-assignable? (jclass-cname (mirror-class other)) (jclass-cname (mirror-class this))) 1 0))
;; the element of an array descriptor given as a binary name: "[[Lp.Foo;" ->
;; "p.Foo", a primitive element -> 'prim, a malformed name ("[", "[[", "[X",
;; "[LFoo") -> #f, not found as on the JVM
(define (array-element-binary-name s)
  (let ([len (string-length s)])
    (let skip ([i 0])
      (cond [(fx= i len) #f]
            [(char=? (string-ref s i) #\[) (skip (fx+ i 1))]
            [(char=? (string-ref s i) #\L)
             (and (fx> (fx- len 1) (fx+ i 1)) (char=? (string-ref s (fx- len 1)) #\;)
                  (substring s (fx+ i 1) (fx- len 1)))]
            [(and (fx= i (fx- len 1)) (memv (string-ref s i) '(#\B #\C #\D #\F #\I #\J #\S #\Z))) 'prim]
            [else #f]))))
(defnative "java/lang/Class.forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;"
  (name init loader caller)
  ;; a binary name: one with slashes is not found, as on the JVM. A loader
  ;; of the program's own answers through its loadClass, as the JVM asks it
  ;; to (a loader that defines classes on demand needs that); for an array
  ;; it is asked for the element class, a primitive element needing no one.
  ;; The built-in loaders (null is the boot loader) go to the class table
  ;; and see what that loader would load (builtin-loader-sees?)
  (let* ([s (jstring->scheme name)]
         [n (slashes s)]
         [own? (and loader (not (jinstance? loader "jdk/internal/loader/BuiltinClassLoader")))]
         [array? (and (fxpositive? (string-length s)) (char=? (string-ref s 0) #\[))]
         [element (and array? (array-element-binary-name s))]
         ;; a class by binary name, through the loader given
         [resolve (lambda (bn)
                    (if own?
                        (let ([m ((jvirt loader "loadClass(Ljava/lang/String;)Ljava/lang/Class;") loader (jstring bn))])
                          (and m (mirror-class m)))
                        (let ([c (try-class (slashes bn))])
                          (and c (builtin-loader-sees? loader c) c))))]
         [c (cond [(or (fxzero? (string-length s)) (memv #\/ (string->list s))) #f]
                  ;; an array name that isn't a well-formed descriptor is not
                  ;; found; one with a class element exists once the element
                  ;; resolves through the loader (its pseudo-class would be
                  ;; made for any name, and mirrored, it loads its element)
                  [(and array? (not element)) #f]
                  [(and array? (not (eq? element 'prim))) (and (resolve element) (try-class n))]
                  [array? (try-class n)]
                  [else (resolve s)])])
    (unless c (jthrow "java/lang/ClassNotFoundException" s))
    (when (eqv? init 1) (jinit (jclass-cname c)))
    (jmirror c)))

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
;; module and hidden flag from a jolt hook (main.clj frame-info).
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

;; ste for cname.mname desc at pc, its format bits still to compute
(define (fill-ste! ste cname mname desc pc)
  (let* ([info (if frame-hook (frame-hook cname mname desc pc) (vector "" -1 ""))]
         [file (vector-ref info 0)] [line (vector-ref info 1)] [module (vector-ref info 2)]
         [set (lambda (f v) (jslot-set! ste (slot-of "java/lang/StackTraceElement" f) v))])
    (set "declaringClassObject" (jmirror-of cname))
    (set "declaringClass" (jstring (slashes->dots cname)))
    (set "methodName" (jstring mname))
    (set "fileName" (and (fx> (string-length file) 0) (jstring file)))
    (set "lineNumber" line)
    (set "moduleName" (and (fx> (string-length module) 0) (jstring module)))
    ste))

(define (stack-trace-array frames)
  (jinit "java/lang/StackTraceElement")
  (let ([arr (make-jarr "Ljava/lang/StackTraceElement;"
                        (list->vector (map (lambda (f) (fill-ste! (jnew "java/lang/StackTraceElement") (car f) (cadr f) (caddr f) (cadddr f)))
                                           frames)))])
    ;; of() computes each element's format: how the module and loader print
    ((method-proc "java/lang/StackTraceElement" "of([Ljava/lang/StackTraceElement;)[Ljava/lang/StackTraceElement;") arr)))

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

;; --- Unsafe -------------------------------------------------------------------------
;; An object field offset is its slot index; an array offset is the element
;; index (base 0, scale 1). Static fields are not addressable yet.

;; Unsafe with a null base and an absolute address reaches native memory:
;; malloc'd memory (allocateMemory0) that libc calls get pointers into
;; (files.ss). The startup cache keeps compiled code and the class table,
;; never Java objects, so no address outlives its process.
(define (native-alloc! init)
  (let ([a (foreign-alloc 8)]) (foreign-set! 'integer-64 a 0 init) a))

(define (foreign-type t)
  (case t
    [("B") 'integer-8] [("Z") 'unsigned-8] [("S") 'integer-16] [("C") 'unsigned-16]
    [("I") 'integer-32] [("J") 'integer-64] [("F") 'single-float] [("D") 'double-float]
    [else (error 'champaca "no off-heap references" t)]))
(define (native-get a t) (foreign-ref (foreign-type t) a 0))
(define (native-set! a v t) (foreign-set! (foreign-type t) a 0 v))

(define unsafe-prefix "jdk/internal/misc/Unsafe.")
;; Arrays are byte-addressed like a real heap: base offset 16, scale by
;; element size (references count 4), little-endian. An access whose width
;; differs from the element size (getLongUnaligned over a byte[], as
;; ArraysSupport.vectorizedMismatch does) is assembled byte by byte.
;; Objects keep slot-index offsets from objectFieldOffset.

(define array-base 16)
(define (type-size t) (case (string-ref t 0) [(#\Z #\B) 1] [(#\C #\S) 2] [(#\I #\F) 4] [(#\J #\D) 8] [else 4]))
(define (same-type? t u) (char=? (string-ref t 0) (string-ref u 0)))
(define (elem-type a) (let ([e (jarr-elem a)]) (if (memv (string-ref e 0) '(#\L #\[)) "L" e)))

;; a float[] or double[] element is its bits here, as in memory
(define (elem-bits t e)
  (case t [("F") (float->bits e)] [("D") (double->bits e)] [else e]))

(define (byte-of a bpos)
  (let* ([t (elem-type a)] [se (type-size t)]
         [e (elem-bits t (vector-ref (jarr-vec a) (quotient bpos se)))])
    (logand (ash (logand e (- (ash 1 (* 8 se)) 1)) (- (* 8 (remainder bpos se)))) #xff)))

(define (set-byte! a bpos b)
  (let* ([t (elem-type a)] [se (type-size t)] [i (quotient bpos se)] [sh (* 8 (remainder bpos se))]
         [mask (- (ash 1 (* 8 se)) 1)]
         [old (logand (elem-bits t (vector-ref (jarr-vec a) i)) mask)]
         [new (logior (logand old (lognot (ash #xff sh))) (ash b sh))])
    (vector-set! (jarr-vec a) i (signed-as t new))))

(define (signed-as t u)
  (case (string-ref t 0)
    [(#\B) (i2b u)] [(#\S) (i2s u)] [(#\C) (logand u #xffff)] [(#\Z) (logand u 1)]
    [(#\I) (i32 u)] [(#\J) (i64 u)] [(#\F) (bits->float (i32 u))] [(#\D) (bits->double (i64 u))]
    [else u]))

;; Static fields: like HotSpot, the base is the class mirror. The offset is
;; static-offset-tag plus the field's index in the class meta list, so it
;; can't collide with an instance slot of the mirror itself.
(define static-offset-tag #x100000)
(define (static-symbol o off)
  (and (fx>= off static-offset-tag)
       (let ([c (eq-hashtable-ref mirror->class o #f)])
         (and c
              (let ([meta (list-ref (cadr (jclass-meta c)) (fx- off static-offset-tag))])
                (jinit (jclass-cname c))
                (string->symbol (string-append (jclass-cname c) ":" (car meta))))))))

(define (unsafe-get o off t)
  (cond
    [(not o) (native-get off t)]
    [(and (jobj? o) (static-symbol o off)) => top-level-value]
    [(jobj? o) (vector-ref (jobj-slots o) off)]
    [else
     (let* ([b (- off array-base)] [et (elem-type o)] [se (type-size et)] [st (type-size t)])
       (if (and (= se st) (zero? (remainder b se)) (or (same-type? t et) (char=? (string-ref et 0) #\L) (char=? (string-ref t 0) #\L)))
           (vector-ref (jarr-vec o) (quotient b se))
           (let loop ([k (- st 1)] [acc 0])
             (if (< k 0) (signed-as t acc) (loop (- k 1) (logior (ash acc 8) (byte-of o (+ b k))))))))]))

(define (unsafe-set! o off v t)
  (cond
    [(not o) (native-set! off v t)]
    [(and (jobj? o) (static-symbol o off)) => (lambda (sym) (set-top-level-value! sym v))]
    [(jobj? o) (vector-set! (jobj-slots o) off v)]
    [else
     (let* ([b (- off array-base)] [et (elem-type o)] [se (type-size et)] [st (type-size t)])
       (if (and (= se st) (zero? (remainder b se)) (or (same-type? t et) (char=? (string-ref et 0) #\L) (char=? (string-ref t 0) #\L)))
           (vector-set! (jarr-vec o) (quotient b se) v)
           (let ([v (elem-bits t v)])
             (do ([k 0 (+ k 1)]) ((= k st))
               (set-byte! o (+ b k) (logand (ash v (* -8 k)) #xff))))))]))

;; --- scoped memory access and closing a shared arena ------------------------------
;; HotSpot closes a shared session with a handshake: a thread inside one of
;; ScopedMemoryAccess's @Scoped methods on that session gets the
;; ScopedAccessError thrown at it, so no access outlives the memory. Here a
;; thread can only be switched out at a safepoint (a backward branch) or a
;; blocking call. Most scoped methods check the session and then reach memory
;; with no branch in between, and the few with loops (vectorizedMismatch, the
;; Vector API's loads and stores through their Java fallbacks) run without
;; yields. So while a thread closes a session no other thread is inside a
;; scoped access, and closeScope0 has nothing to do.
(defnative "jdk/internal/misc/ScopedMemoryAccess.closeScope0(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/misc/ScopedMemoryAccess$ScopedAccessError;)V"
  (this session error) (void))

(define (scoped-rethrow thunk)
  (guard (e [(and (jobj? e) (jinstance? e "jdk/internal/misc/ScopedMemoryAccess$ScopedAccessError"))
             (raise ((jvirt e "newRuntimeException()Ljava/lang/RuntimeException;") e))])
    (thunk)))

(define-override! "jdk/internal/misc/ScopedMemoryAccess.vectorizedMismatch(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JII)I"
  (lambda args
    (scoped-rethrow
      (lambda ()
        (without-yields
          (lambda ()
            (apply (method-proc "jdk/internal/misc/ScopedMemoryAccess"
                                "vectorizedMismatchInternal(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JII)I")
                   args)))))))

;; the Vector API's static entries: the Internal method takes the segment's
;; session first, then the same arguments; seg is the segment's position
(for-each
  (lambda (spec)
    (let* ([name (car spec)] [args (cadr spec)] [ret (caddr spec)] [seg (cadddr spec)]
           [key (string-append name "ScopedInternal(Ljdk/internal/foreign/MemorySessionImpl;" args ")" ret)])
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess." name "(" args ")" ret)
        (lambda xs
          (let ([msp (list-ref xs seg)])
            (scoped-rethrow
              (lambda ()
                (without-yields
                  (lambda ()
                    (apply (method-proc "jdk/internal/misc/ScopedMemoryAccess" key)
                           ((jvirt msp "sessionImpl()Ljdk/internal/foreign/MemorySessionImpl;") msp)
                           xs))))))))))
  '(("loadFromMemorySegment"
     "Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$VectorSpecies;Ljdk/internal/vm/vector/VectorSupport$LoadOperation;"
     "Ljdk/internal/vm/vector/VectorSupport$Vector;" 3)
    ("loadFromMemorySegmentMasked"
     "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$VectorMask;Ljdk/internal/vm/vector/VectorSupport$VectorSpecies;ILjdk/internal/vm/vector/VectorSupport$LoadVectorMaskedOperation;"
     "Ljdk/internal/vm/vector/VectorSupport$Vector;" 4)
    ("storeIntoMemorySegment"
     "Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/vm/vector/VectorSupport$Vector;Ljdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$StoreVectorOperation;"
     "V" 4)
    ("storeIntoMemorySegmentMasked"
     "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;ILjdk/internal/vm/vector/VectorSupport$Vector;Ljdk/internal/vm/vector/VectorSupport$VectorMask;Ljdk/internal/foreign/AbstractMemorySegmentImpl;JLjdk/internal/vm/vector/VectorSupport$StoreVectorMaskedOperation;"
     "V" 6)))

;; --- the Unaligned accessors ------------------------------------------------------
;; Unsafe.get<T>Unaligned(Object, long[, boolean]) and put<T>Unaligned, and
;; ScopedMemoryAccess's wrappers over them, which every ByteBuffer read and
;; write on a byte[] goes through (HeapByteBuffer.getShort and the rest).
;; HotSpot intrinsifies them; here the JDK's Java took a native call per
;; byte plus the assembly, and the scoped wrapper a try/finally per access
;; (a quarter of a warm Spring Boot fat-jar start was its launcher reading
;; zip headers this way). Overridden: a byte[]'s bytes are read from its
;; vector directly, in either byte order; native memory (a direct buffer)
;; through one foreign-ref; any other array byte by byte as before.
;; an instance field of an object, the case atomics and locks hit: its offset is the slot index
(define-syntax field-offset?
  (syntax-rules () [(_ o off) (and (jobj? o) (fx< off static-offset-tag))]))
(define-syntax byte-array?
  (syntax-rules () [(_ o) (and (jarr? o) (let ([e (jarr-elem o)]) (and (fx= (string-length e) 1) (char=? (string-ref e 0) #\B))))]))
;; the unsigned value of n bytes (n up to 4) at index i of byte vector v
(define (vec-bytes->fx v i n be?)
  (let loop ([k 0] [acc 0])
    (if (fx= k n)
        acc
        (loop (fx+ k 1)
              (fxlogor acc (fxsll (fxlogand (vector-ref v (fx+ i k)) #xff) (fx* 8 (if be? (fx- (fx- n 1) k) k))))))))
;; the index of the element of primitive array o that off and n cover
;; exactly, or #f: a heap memory segment over an int[] reads its ints this way
(define (whole-element o off n)
  (and (jarr? o)
       (let ([e (jarr-elem o)])
         (and (fx= (string-length e) 1)
              (fx= (type-size e) n)
              (let ([b (fx- off array-base)])
                (and (fx= 0 (fxremainder b n)) (fxquotient b n)))))))
;; the unsigned n-byte value at off in o: a byte[], native memory, or any
;; other array (mem-byte); n is 1, 2, 4 or 8
(define (unaligned-u o off n be?)
  (cond
    [(byte-array? o)
     (let ([v (jarr-vec o)] [i (fx- off array-base)])
       (if (fx= n 8)
           (logior (vec-bytes->fx v (if be? (fx+ i 4) i) 4 be?) (ash (vec-bytes->fx v (if be? i (fx+ i 4)) 4 be?) 32))
           (vec-bytes->fx v i n be?)))]
    [(not o)
     (let ([u (case n [(1) (#3%foreign-ref 'unsigned-8 off 0)] [(2) (#3%foreign-ref 'unsigned-16 off 0)] [(4) (#3%foreign-ref 'unsigned-32 off 0)] [else (#3%foreign-ref 'unsigned-64 off 0)])])
       (if be? (bswap u n) u))]
    [(whole-element o off n)
     => (lambda (i)
          (let ([u (logand (elem-bits (elem-type o) (vector-ref (jarr-vec o) i)) (- (ash 1 (fx* 8 n)) 1))])
            (if be? (bswap u n) u)))]
    [else
     (let loop ([k 0] [acc 0])
       (if (fx= k n)
           acc
           (loop (fx+ k 1) (logior acc (ash (mem-byte o (+ off k)) (fx* 8 (if be? (fx- (fx- n 1) k) k)))))))]))
;; the n low bytes of x (any sign) written at off in o, in either order
(define (unaligned-put! o off n x be?)
  (cond
    [(byte-array? o)
     (let ([v (jarr-vec o)] [i (fx- off array-base)])
       (do ([k 0 (fx+ k 1)]) ((fx= k n))
         (vector-set! v (fx+ i (if be? (fx- (fx- n 1) k) k)) (i2b (logand (ash x (fx* -8 k)) #xff)))))]
    [(not o)
     (let ([u (let ([u (logand x (- (ash 1 (fx* 8 n)) 1))]) (if be? (bswap u n) u))])
       (case n
         [(1) (#3%foreign-set! 'unsigned-8 off 0 u)] [(2) (#3%foreign-set! 'unsigned-16 off 0 u)]
         [(4) (#3%foreign-set! 'unsigned-32 off 0 u)] [else (#3%foreign-set! 'unsigned-64 off 0 u)]))]
    [(whole-element o off n)
     => (lambda (i)
          (let ([u (let ([u (logand x (- (ash 1 (fx* 8 n)) 1))]) (if be? (bswap u n) u))])
            (vector-set! (jarr-vec o) i (signed-as (elem-type o) u))))]
    [else
     (do ([k 0 (fx+ k 1)]) ((fx= k n))
       (mem-byte-set! o (+ off (if be? (fx- (fx- n 1) k) k)) (logand (ash x (fx* -8 k)) #xff)))]))
(define (bswap u n)
  (let loop ([k 0] [acc 0])
    (if (fx= k n) acc (loop (fx+ k 1) (logior (ash acc 8) (logand (ash u (fx* -8 k)) #xff))))))
;; (java type letter, descriptor, size, the signed value of the unsigned bits)
(define unaligned-types
  (list (list "Short" "S" 2 i2s) (list "Char" "C" 2 (lambda (u) u)) (list "Int" "I" 4 i32) (list "Long" "J" 8 i64)))
(for-each
  (lambda (spec)
    (let* ([name (car spec)] [d (cadr spec)] [n (caddr spec)] [signed (cadddr spec)]
           ;; an object base is a field, instance or static, as the plain accessor
           ;; takes it (never a ByteBuffer's; kept for Unsafe's generality, the byte
           ;; order flag meaning nothing for a slot)
           [get (lambda (o off be?)
                  (cond [(not (jobj? o)) (signed (unaligned-u o off n be?))]
                        [(field-offset? o off) (vector-ref (jobj-slots o) off)]
                        [else (unsafe-get o off d)]))]
           [put! (lambda (o off x be?)
                   (cond [(not (jobj? o)) (unaligned-put! o off n x be?)]
                         [(field-offset? o off) (vector-set! (jobj-slots o) off x)]
                         [else (unsafe-set! o off x d)]))]
           [scoped-check! (lambda (session)
                            ;; the wrapper's ScopedAccessError becomes its RuntimeException, as the JDK's catch does
                            (when session
                              (guard (e [(and (jobj? e) (jinstance? e "jdk/internal/misc/ScopedMemoryAccess$ScopedAccessError"))
                                         (raise ((jvirt e "newRuntimeException()Ljava/lang/RuntimeException;") e))])
                                ((jvirt session "checkValidStateRaw()V") session))))])
      (define-override! (string-append unsafe-prefix "get" name "Unaligned(Ljava/lang/Object;J)" d)
        (lambda (this o off) (get o off #f)))
      (define-override! (string-append unsafe-prefix "get" name "Unaligned(Ljava/lang/Object;JZ)" d)
        (lambda (this o off be) (get o off (eqv? be 1))))
      (define-override! (string-append unsafe-prefix "put" name "Unaligned(Ljava/lang/Object;J" d ")V")
        (lambda (this o off x) (put! o off x #f)))
      (define-override! (string-append unsafe-prefix "put" name "Unaligned(Ljava/lang/Object;J" d "Z)V")
        (lambda (this o off x be) (put! o off x (eqv? be 1))))
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess.get" name
                                       "Unaligned(Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JZ)" d)
        (lambda (this session o off be) (scoped-check! session) (get o off (eqv? be 1))))
      (define-override! (string-append "jdk/internal/misc/ScopedMemoryAccess.put" name
                                       "Unaligned(Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;J" d "Z)V")
        (lambda (this session o off x be) (scoped-check! session) (put! o off x (eqv? be 1))))))
  unaligned-types)

;; the value type an Unsafe accessor works in, from its name
(define (unsafe-type n)
  (let loop ([ts '(("Reference" . "L") ("Boolean" . "Z") ("Byte" . "B") ("Short" . "S") ("Char" . "C")
                   ("Int" . "I") ("Long" . "J") ("Float" . "F") ("Double" . "D"))])
    (cond [(null? ts) "L"]
          [(string-search (caar ts) n) (cdar ts)]
          [else (loop (cdr ts))])))

(defnative "jdk/internal/misc/Unsafe.arrayBaseOffset0(Ljava/lang/Class;)I" (this c) array-base)
(defnative "jdk/internal/misc/Unsafe.arrayIndexScale0(Ljava/lang/Class;)I" (this c)
  (let ([n (jclass-cname (mirror-class c))])
    (type-size (let ([e (substring n 1 (string-length n))]) (if (memv (string-ref e 0) '(#\L #\[)) "L" e)))))
(defnative "jdk/internal/misc/Unsafe.addressSize0()I" (this) 8)
(defnative "jdk/internal/misc/Unsafe.pageSize()I" (this) 4096)
(defnative "jdk/internal/misc/Unsafe.fullFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.loadFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.storeFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.objectFieldOffset1(Ljava/lang/Class;Ljava/lang/String;)J" (this c name)
  (jfield-index (jclass-cname (mirror-class c)) (jstring->scheme name)))
(define (field-mirror f) (jslot f (jfield-index "java/lang/reflect/Field" "clazz")))
;; the same offset, from a reflective Field (CompletableFuture's VarHandles use it)
(defnative "jdk/internal/misc/Unsafe.objectFieldOffset0(Ljava/lang/reflect/Field;)J" (this f)
  (jfield-index (jclass-cname (mirror-class (field-mirror (nn f))))
                (jstring->scheme (jslot f (jfield-index "java/lang/reflect/Field" "name")))))
(defnative "jdk/internal/misc/Unsafe.staticFieldBase0(Ljava/lang/reflect/Field;)Ljava/lang/Object;" (this f)
  (field-mirror (nn f)))
(defnative "jdk/internal/misc/Unsafe.staticFieldOffset0(Ljava/lang/reflect/Field;)J" (this f)
  (fx+ static-offset-tag (jslot (nn f) (jfield-index "java/lang/reflect/Field" "slot"))))
(defnative "jdk/internal/misc/Unsafe.ensureClassInitialized0(Ljava/lang/Class;)V" (this c)
  (jinit (jclass-cname (mirror-class c))))
(defnative "jdk/internal/misc/Unsafe.shouldBeInitialized0(Ljava/lang/Class;)Z" (this c)
  (if (eq? (jclass-state (mirror-class c)) 'initialized) 0 1))
;; between Java arrays and native memory (a null base), in any mix: whole
;; elements when two arrays' types and alignment allow, else bytes. The source
;; is read in full before writing, so overlap is safe.
(define (mem-byte o off)
  (if o (byte-of o (- off array-base)) (#3%foreign-ref 'unsigned-8 off 0)))
(define (mem-byte-set! o off b)
  (if o (set-byte! o (- off array-base) b) (#3%foreign-set! 'unsigned-8 off 0 b)))
(defnative "jdk/internal/misc/Unsafe.copyMemory0(Ljava/lang/Object;JLjava/lang/Object;JJ)V" (this src soff dst doff n)
  (cond
    [(and (jarr? src) (jarr? dst)) (copy-array-memory src soff dst doff n)]
    ;; native memory and a byte[], the shape every file and channel read and
    ;; write takes: one element per byte, no intermediate list
    [(and (not src) (jarr? dst) (string=? (elem-type dst) "B"))
     (native->vector! soff 0 (jarr-vec dst) (- doff array-base) n)]
    [(and (jarr? src) (not dst) (string=? (elem-type src) "B"))
     (vector->native! (jarr-vec src) (- soff array-base) doff 0 n)]
    [else
     (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (mem-byte src (+ soff i)) acc))))])
       (let loop ([i 0] [bs bytes])
         (unless (null? bs) (mem-byte-set! dst (+ doff i) (car bs)) (loop (+ i 1) (cdr bs)))))]))
;; the same, reversing the bytes of each elem-size element
(defnative "jdk/internal/misc/Unsafe.copySwapMemory0(Ljava/lang/Object;JLjava/lang/Object;JJJ)V" (this src soff dst doff n es)
  (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (mem-byte src (+ soff i)) acc))))])
    (let loop ([i 0] [bs bytes])
      (unless (null? bs)
        (let ([elem (list-head bs es)])
          (do ([k 0 (+ k 1)] [b (reverse elem) (cdr b)]) ((= k es)) (mem-byte-set! dst (+ doff i k) (car b)))
          (loop (+ i es) (list-tail bs es)))))))
(defnative "jdk/internal/misc/Unsafe.allocateMemory0(J)J" (this n) (foreign-alloc (max n 1)))
(defnative "jdk/internal/misc/Unsafe.freeMemory0(J)V" (this a) (unless (eqv? a 0) (foreign-free a)))
(defnative "jdk/internal/misc/Unsafe.setMemory0(Ljava/lang/Object;JJB)V" (this o off n b)
  (do ([i 0 (+ i 1)]) ((= i n)) (mem-byte-set! o (+ off i) (fxlogand b #xff))))
(define (copy-array-memory src soff dst doff n)
  (let* ([sb (- soff array-base)] [db (- doff array-base)]
         [et (elem-type src)] [se (type-size et)])
    (if (and (same-type? et (elem-type dst)) (zero? (remainder sb se)) (zero? (remainder db se)) (zero? (remainder n se)))
        ;; one pass, backwards when the same vector's ranges overlap with
        ;; the destination past the source (memmove)
        (let ([k (quotient n se)] [si (quotient sb se)] [di (quotient db se)] [sv (jarr-vec src)] [dv (jarr-vec dst)])
          (if (or (not (eq? sv dv)) (fx<= di si))
              (do ([i 0 (fx+ i 1)]) ((fx= i k)) (vector-set! dv (fx+ di i) (vector-ref sv (fx+ si i))))
              (do ([i (fx- k 1) (fx- i 1)]) ((fx< i 0)) (vector-set! dv (fx+ di i) (vector-ref sv (fx+ si i))))))
        (let ([bytes (let loop ([i (- n 1)] [acc '()]) (if (< i 0) acc (loop (- i 1) (cons (byte-of src (+ sb i)) acc))))])
          (let loop ([i 0] [bs bytes])
            (unless (null? bs) (set-byte! dst (+ db i) (car bs)) (loop (+ i 1) (cdr bs))))))))
(defnative "jdk/internal/misc/Unsafe.allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;" (this c)
  (jnew (jclass-cname (mirror-class c))))

(define (starts? s p) (and (fx>= (string-length s) (string-length p)) (string=? p (substring s 0 (string-length p)))))
(define (method-part n) (substring n (string-length unsafe-prefix) (string-length n)))

;; get<T>[Volatile](Object, long) / put<T>[Volatile](Object, long, T)
;; An instance field of an object, the case atomics and locks hit, skips
;; unsafe-get's dispatch: its offset is the slot index.
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "get") (string-search "(Ljava/lang/Object;J)" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off)
                  (if (field-offset? o off)
                      ;; a weak reference's referent slot holds a weak pair (references, below)
                      (let ([v (vector-ref (jobj-slots o) off)])
                        (if (weak-pair? v) (let ([x (car v)]) (if (bwp-object? x) #f x)) v))
                      (unsafe-get o off t))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "put") (string-search "(Ljava/lang/Object;J" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off v)
                  (if (field-offset? o off) (vector-set! (jobj-slots o) off v) (unsafe-set! o off v t))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndSet")))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off expect new)
                  (if (field-offset? o off)
                      (let ([v (jobj-slots o)])
                        (if (eqv? (vector-ref v off) expect) (begin (vector-set! v off new) 1) 0))
                      (if (eqv? (unsafe-get o off t) expect) (begin (unsafe-set! o off new t) 1) 0))))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndExchange")))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off expect new)
                  (let ([old (unsafe-get o off t)])
                    (when (eqv? old expect) (unsafe-set! o off new t))
                    old)))))

(define (string-search needle hay)
  (let ([n (string-length needle)] [h (string-length hay)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i n) h) #f]
            [(string=? needle (substring hay i (fx+ i n))) i]
            [else (loop (fx+ i 1))]))))

;; --- VM and CDS ---------------------------------------------------------------

(defnative "jdk/internal/misc/VM.initialize()V" () (void))
(defnative "jdk/internal/misc/VM.getNanoTimeAdjustment(J)J" (offset)
  (- (now-nanos) (* offset 1000000000)))
(defnative "jdk/internal/misc/CDS.isDumpingClassList0()Z" () 0)
(defnative "jdk/internal/misc/CDS.isDumpingArchive0()Z" () 0)
(defnative "jdk/internal/misc/CDS.isSharingEnabled0()Z" () 0)
(defnative "jdk/internal/misc/CDS.getCDSConfigStatus()I" () 0)
(defnative "jdk/internal/misc/CDS.getRandomSeedForDumping()J" () 0)
(defnative "jdk/internal/misc/CDS.initializeFromArchive(Ljava/lang/Class;)V" (c) (void))

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
     ("java.vm.specification.name" . "Java Virtual Machine Specification")
     ("java.vm.specification.vendor" . "Oracle Corporation")
     ("java.vm.specification.version" . "25")
     ("java.class.path" . ,classpath-string)
     ("sun.boot.library.path" . ,(string-append java-home-dir "/lib"))
     ("jdk.debug" . "release")
     ;; reflection through invoke0/newInstance0 (reflect.ss), never method handles
     ("jdk.reflect.useNativeAccessorOnly" . "true")
     ;; sun.misc.Unsafe's default "warn" finds its caller with StackWalker
     ;; and prints a warning on stderr; "allow" skips both
     ("sun.misc.unsafe.memory.access" . "allow")
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
                     ;; "en_AU" or "en_TH@rg=thzzzz": what follows the last _
                     (and id (let* ([at (let loop ([i 0]) (if (or (fx= i (string-length id)) (char=? (string-ref id i) #\@)) i (loop (fx+ i 1))))]
                                    [id (substring id 0 at)]
                                    [us (let loop ([i (fx- (string-length id) 1)])
                                          (cond [(fx< i 0) #f] [(char=? (string-ref id i) #\_) i] [else (loop (fx- i 1))]))])
                               (and us (substring id (fx+ us 1) (string-length id))))))])
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
    (set "_native_encoding_NDX" "UTF-8")
    (set "_sun_jnu_encoding_NDX" "UTF-8")
    (set "_stdout_encoding_NDX" "UTF-8")
    (set "_stderr_encoding_NDX" "UTF-8")
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

;; --- file descriptors and output ---------------------------------------------------

(defnative "java/io/FileDescriptor.getHandle(I)J" (fd) -1)
(defnative "java/io/FileDescriptor.getAppend(I)Z" (fd) 0)

(define stdout-bin (standard-output-port))
(define stderr-bin (standard-error-port))

;; fds 1 and 2 go through Chez's ports, which jolt's own output shares, so
;; the two interleave in order. FileOutputStream's natives are in files.ss.
(define (console-write-bytes fd bytes off len)
  (let ([port (if (eqv? fd 2) stderr-bin stdout-bin)]
        [v (jarr-vec bytes)]
        [bv (make-bytevector len)])
    (do ([i 0 (fx+ i 1)]) ((fx= i len)) (bytevector-u8-set! bv i (fxlogand (vector-ref v (fx+ off i)) #xff)))
    (flush-output-port (current-output-port))
    (put-bytevector port bv)
    (flush-output-port port)))
(define (console-write-byte fd b)
  (let ([port (if (eqv? fd 2) stderr-bin stdout-bin)])
    (put-u8 port (fxlogand b #xff))
    (flush-output-port port)))

;; --- Linux containers: the JDK's cgroup support stays off, as with
;; -XX:-UseContainerSupport, so the host's memory and CPU count are reported
(defnative "jdk/internal/platform/CgroupMetrics.isUseContainerSupport()Z" () 0)
(defnative "jdk/internal/platform/CgroupMetrics.isContainerized0()Z" () 0)
(defnative "jdk/internal/platform/CgroupMetrics.getTotalMemorySize0()J" () 0)
(defnative "jdk/internal/platform/CgroupMetrics.getTotalSwapSize0()J" () 0)

;; --- signals: none are supported, which Terminator.setup tolerates ------------
(defnative "jdk/internal/misc/Signal.findSignal0(Ljava/lang/String;)I" (name) -1)
(defnative "jdk/internal/misc/Signal.handle0(IJ)J" (sig handler) 0)

;; --- threads -------------------------------------------------------------------
;; Each started Java thread is a Chez thread. One global lock, the GIL, lets
;; one of them run Java code at a time, so the class table, the JIT and the
;; runtime's hashtables need no locks of their own. A thread gives the GIL up
;; when it blocks (a contended monitor, wait, sleep, park, a class another
;; thread is initializing), and at backward branches (jsafepoint) once
;; another thread is ready to run.
;;
;; The runtime keeps some per-thread values in globals: the current
;; java.lang.Thread, the CHAMPACA_BT shadow stack, the scoped value cache,
;; the no-yield depth.
;; They are saved when a thread gives the GIL up and restored when it takes
;; it back.

(define-record-type jthread-state
  (fields (mutable jthread) (mutable jstack) (mutable svc) (mutable permit) (mutable depth) (mutable tid)
          (mutable no-yield))
  (sealed #t) (nongenerative champaca-jthread-state-2))
;; This Chez thread's jthread-state. A forked Chez thread inherits its parent's
;; value, so a thread jolt started (a future, say) would see its parent's
;; state: gil-enter! checks the thread id before trusting it.
(define self (make-thread-parameter #f))
(define main-state (make-jthread-state #f '() #f #f 1 (get-thread-id) 0))
(self main-state)

(define gil (make-mutex))
(define gil-cv (make-condition))  ; broadcast whenever something a blocked thread waits for may have changed
(define gil-owner main-state)      ; the jthread-state running Java code, or #f
(define gil-blocked 0)             ; threads waiting in gil-wait!
(define gil-ready 0)               ; of those, the ones ready to run but for the GIL
(define gil-takes 0)               ; how many times the GIL has changed hands

(define main-thread #f)
(define current-jthread #f)        ; the running thread's java.lang.Thread
(define scoped-value-cache #f)
(define no-yield-depth 0)          ; see without-yields

(define (gil-save! s)
  (jthread-state-jstack-set! s jstack)
  (jthread-state-svc-set! s scoped-value-cache)
  (jthread-state-no-yield-set! s no-yield-depth))
(define (gil-restore! s)
  (set! no-yield-depth (jthread-state-no-yield s))
  (set! current-jthread (jthread-state-jthread s))
  (set! jstack (jthread-state-jstack s))
  (set! scoped-value-cache (jthread-state-svc s)))

;; With gil held and the GIL not ours: wait until (ready?) holds, or deadline
;; (a time-utc, or #f) passes, and the GIL is free; then take it. Returns
;; what ready? said.
(define (gil-wait! s ready? deadline)
  (set! gil-blocked (fx+ gil-blocked 1))
  (let loop ([counted #f])
    (let* ([r (ready?)]
           [go (or r (and deadline (time>=? (current-time 'time-utc) deadline)))])
      (if (and go (not gil-owner))
          (begin
            (when counted (set! gil-ready (fx- gil-ready 1)))
            (set! gil-blocked (fx- gil-blocked 1))
            (set! gil-owner s)
            (set! gil-takes (fx+ gil-takes 1))
            ;; a thread that yielded waits for this take; woken, it counts
            ;; itself ready, so the new owner yields back at a safepoint
            (when (fx> gil-blocked 0) (condition-broadcast gil-cv))
            r)
          (begin
            (unless (eq? go counted)
              (set! gil-ready (if go (fx+ gil-ready 1) (fx- gil-ready 1)))
              (condition-broadcast gil-cv))     ; a yielding thread watches gil-ready
            (if (and deadline (not go))
                (condition-wait gil-cv gil deadline)
                (condition-wait gil-cv gil))
            (loop go))))))

;; The running thread blocks: give the GIL up until (ready?) or the deadline.
(define (gil-block! ready? deadline)
  (let ([s (self)])
    (gil-save! s)
    (let ([r (with-mutex gil
               (set! gil-owner #f)
               (condition-broadcast gil-cv)
               (gil-wait! s ready? deadline))])
      (gil-restore! s)
      r)))

;; Enter Java code from a thread that may not hold the GIL (a new thread, or
;; jolt calling in); re-entrant. A Chez thread champaca has not seen before
;; (one jolt started) gets a java.lang.Thread of its own, the way a JVM
;; attaches a native thread.
(define (gil-enter!)
  (let* ([s0 (self)]
         [known (and s0 (eqv? (jthread-state-tid s0) (get-thread-id)))]
         [s (if known s0 (let ([s (make-jthread-state #f '() #f #f 0 (get-thread-id) 0)]) (self s) s))])
    (if (eq? gil-owner s)
        (jthread-state-depth-set! s (fx+ (jthread-state-depth s) 1))
        (begin
          (with-mutex gil (gil-wait! s (lambda () #t) #f))
          (jthread-state-depth-set! s 1)
          (gil-restore! s)
          (unless known (attach-jthread! s))))))

(define (gil-leave!)
  (let* ([s (self)] [d (fx- (jthread-state-depth s) 1)])
    (jthread-state-depth-set! s d)
    (when (fx= d 0)
      (gil-save! s)
      (with-mutex gil
        (set! gil-owner #f)
        (condition-broadcast gil-cv)))))

;; Run thunk without the GIL, for a call that may wait in C indefinitely (a
;; pipe or terminal read, files.ss): other Java threads run meanwhile. The
;; thunk must not touch Java objects or runtime state. The GIL is taken back
;; before anything leaves, a raise included.
(define (gil-released thunk)
  (let ([s (self)])
    (gil-save! s)
    (with-mutex gil
      (set! gil-owner #f)
      (condition-broadcast gil-cv))
    (let ([retake (lambda () (with-mutex gil (gil-wait! s (lambda () #t) #f)) (gil-restore! s))])
      (let ([r (guard (e [#t (retake) (raise e)]) (thunk))])
        (retake)
        r))))

;; Tell blocked threads to look again at what they wait for.
(define (gil-poke!)
  (when (fx> gil-blocked 0)
    (with-mutex gil (condition-broadcast gil-cv))))

;; thunk runs without giving the GIL up at a safepoint. The depth is a
;; per-thread value (saved and restored with the GIL), so a thunk that does
;; block, waiting for another thread's <clinit> or a monitor, holds back no
;; other thread's safepoints.
(define (without-yields thunk)
  (dynamic-wind
    (lambda () (set! no-yield-depth (fx+ no-yield-depth 1)))
    thunk
    (lambda () (set! no-yield-depth (fx- no-yield-depth 1)))))

;; Let a thread that is ready run first, then queue for the GIL again.
(define (gil-yield!)
  (let ([n gil-takes])
    (gil-block! (lambda () (or (not (fx= gil-takes n)) (fx= gil-ready 0))) #f)))

;; Generated code checks gil-ready inline at each backward branch and calls
;; this only when some thread is waiting for the GIL. While no-yield-depth is
;; above zero the thread keeps the GIL (see without-yields).
(define safepoint-count 0)
(define (jsafepoint)
  (set! safepoint-count (fx+ safepoint-count 1))
  (when (and (fx>= safepoint-count 1000) (fx= no-yield-depth 0))
    (set! safepoint-count 0)
    (gil-yield!)))

(define (deadline-after-nanos n)
  (add-duration (current-time 'time-utc) (make-time 'time-duration (remainder n 1000000000) (quotient n 1000000000))))

;; --- monitors -----------------------------------------------------------------------
;; One per object that has been locked, found through a weak table. A waiter
;; is a vector #(thread notified? deadline). blocked counts the threads waiting to enter
;; it, so releasing an uncontended monitor wakes nobody: a thread parked for
;; good elsewhere (the Reference Handler) would otherwise make every exit
;; take the GIL's mutex and broadcast, which cost 60% on synchronized code.

(define-record-type jmonitor (fields (mutable owner) (mutable count) (mutable waiters) (mutable blocked))
  (sealed #t) (nongenerative champaca-jmonitor))
;; An object keeps its monitor in its lock field; an array's sits in a weak
;; table. Only the GIL's owner runs Java code, so no other thread races the
;; first lock.
(define monitors (make-weak-eq-hashtable))
(define (monitor-of o)
  (if (jobj? o)
      (or (jobj-lock o)
          (let ([m (make-jmonitor #f 0 '() 0)]) (jobj-lock-set! o m) m))
      (or (eq-hashtable-ref monitors o #f)
          (let ([m (make-jmonitor #f 0 '() 0)]) (eq-hashtable-set! monitors o m) m))))

;; wait, holding the GIL, until m is free, then own it with count n
(define (monitor-acquire! m s n)
  (when (jmonitor-owner m)
    (jmonitor-blocked-set! m (fx+ (jmonitor-blocked m) 1))
    (gil-block! (lambda () (not (jmonitor-owner m))) #f)
    (jmonitor-blocked-set! m (fx- (jmonitor-blocked m) 1)))
  (jmonitor-owner-set! m s)
  (jmonitor-count-set! m n))

(define (monitor-release! m)
  (jmonitor-owner-set! m #f)
  (jmonitor-count-set! m 0)
  (when (fx> (jmonitor-blocked m) 0) (gil-poke!)))

;; The thread running Java code is always the GIL's owner, which a global
;; holds; (self) reads a thread parameter.
(define (jmonitor-enter o)
  (let ([m (monitor-of (nn o))] [s gil-owner])
    (if (eq? (jmonitor-owner m) s)
        (jmonitor-count-set! m (fx+ (jmonitor-count m) 1))
        (monitor-acquire! m s 1))))

;; Unbalanced exits are ignored rather than thrown, as monitors predate threads here.
(define (jmonitor-exit o)
  (let ([m (monitor-of (nn o))])
    (when (eq? (jmonitor-owner m) gil-owner)
      (let ([n (fx- (jmonitor-count m) 1)])
        (if (fx= n 0)
            (monitor-release! m)
            (jmonitor-count-set! m n))))))

(define (owned-monitor o)
  (let ([m (monitor-of (nn o))])
    (unless (eq? (jmonitor-owner m) (self))
      (jthrow "java/lang/IllegalMonitorStateException" "current thread is not owner"))
    m))

(define interrupted-slot #f)
(define (interrupted? t) (fx= 1 (jslot t interrupted-slot)))
(define (take-interrupt! t)
  (and (interrupted? t) (begin (jslot-set! t interrupted-slot 0) #t)))

;; A notified thread returns normally even if it was interrupted too (its
;; interrupt stays pending), which JLS 17.2.4 allows, so a notify is never
;; lost to an interrupt. notify skips a waiter whose timeout has passed or
;; that was interrupted: it is leaving anyway, and the notify goes to one that
;; is still waiting.
(define (waiter-leaving? w)
  (or (interrupted? (vector-ref w 0))
      (let ([d (vector-ref w 2)]) (and d (time>=? (current-time 'time-utc) d)))))

(defnative "java/lang/Object.wait0(J)V" (this ms)
  (let* ([m (owned-monitor this)] [s (self)] [t current-jthread]
         [deadline (and (> ms 0) (deadline-after-nanos (* ms 1000000)))]
         [w (vector t #f deadline)] [count (jmonitor-count m)])
    (when (take-interrupt! t) (jthrow "java/lang/InterruptedException" #f))
    (jmonitor-waiters-set! m (append (jmonitor-waiters m) (list w)))
    (monitor-release! m)
    (gil-block! (lambda () (or (vector-ref w 1) (interrupted? t))) deadline)
    (jmonitor-waiters-set! m (remq w (jmonitor-waiters m)))
    (monitor-acquire! m s count)
    (when (and (not (vector-ref w 1)) (take-interrupt! t))
      (jthrow "java/lang/InterruptedException" #f))))

(defnative "java/lang/Object.notify()V" (this)
  (let ([m (owned-monitor this)])
    (let ([w (find (lambda (w) (not (or (vector-ref w 1) (waiter-leaving? w)))) (jmonitor-waiters m))])
      (when w (vector-set! w 1 #t) (gil-poke!)))))

(defnative "java/lang/Object.notifyAll()V" (this)
  (let ([m (owned-monitor this)])
    (for-each (lambda (w) (vector-set! w 1 #t)) (jmonitor-waiters m))
    (gil-poke!)))

(defnative "java/lang/Thread.holdsLock(Ljava/lang/Object;)Z" (o)
  (if (eq? (jmonitor-owner (monitor-of (nn o))) (self)) 1 0))

;; --- Thread ---------------------------------------------------------------------------
;; currentThread answers main-thread before its constructor has run, because
;; Thread's constructor itself asks for the current thread.

(define jthread-states (make-weak-eq-hashtable))  ; java.lang.Thread -> jthread-state
(define java-live-threads '())                          ; started and not yet terminated

;; JVMTI thread state bits, as Thread.holder.threadStatus holds them
(define status-runnable 5)    ; ALIVE | RUNNABLE
(define status-terminated 2)

(define (thread-status-set! t v)
  (let ([holder (jslot t (jfield-index "java/lang/Thread" "holder"))])
    (jslot-set! holder (jfield-index "java/lang/Thread$FieldHolder" "threadStatus") v)))
(define (thread-daemon? t)
  (let ([holder (jslot t (jfield-index "java/lang/Thread" "holder"))])
    (fx= 1 (jslot holder (jfield-index "java/lang/Thread$FieldHolder" "daemon")))))
;; eetop is the VM thread's address; isAlive() is eetop != 0
(define (thread-alive! t on?)
  (jslot-set! t (jfield-index "java/lang/Thread" "eetop") (if on? 1 0))
  (thread-status-set! t (if on? status-runnable status-terminated)))

(defnative "java/lang/Thread.currentThread()Ljava/lang/Thread;" () current-jthread)
(defnative "java/lang/Thread.currentCarrierThread()Ljava/lang/Thread;" () current-jthread)
(defnative "java/lang/Thread.setCurrentThread(Ljava/lang/Thread;)V" (this t) (void))
(defnative "java/lang/Thread.setPriority0(I)V" (this p) (void))
(defnative "java/lang/Thread.setNativeName(Ljava/lang/String;)V" (this n) (void))
(defnative "java/lang/Thread.getNextThreadIdOffset()J" () (native-alloc! 3))
(defnative "java/lang/Thread.scopedValueCache()[Ljava/lang/Object;" () scoped-value-cache)
(defnative "java/lang/Thread.setScopedValueCache([Ljava/lang/Object;)V" (c) (set! scoped-value-cache c))
(defnative "java/lang/Thread.findScopedValueBindings()Ljava/lang/Object;" () #f)
(defnative "java/lang/Thread.ensureMaterializedForStackWalk(Ljava/lang/Object;)V" (o) (void))
(defnative "java/lang/Thread.clearInterruptEvent()V" () (void))
(defnative "java/lang/Thread.interrupt0()V" (this)
  (let ([s (eq-hashtable-ref jthread-states this #f)])
    (when s (jthread-state-permit-set! s #t)))   ; HotSpot unparks it too
  (gil-poke!))
(defnative "java/lang/Thread.yield0()V" () (when (fx> gil-ready 0) (gil-yield!)))

(defnative "java/lang/Thread.sleepNanos0(J)V" (nanos)
  (let ([t current-jthread])
    (when (take-interrupt! t) (jthrow "java/lang/InterruptedException" "sleep interrupted"))
    (when (gil-block! (lambda () (interrupted? t)) (deadline-after-nanos (max nanos 0)))
      (take-interrupt! t)
      (jthrow "java/lang/InterruptedException" "sleep interrupted"))))

;; Virtual threads need continuations, which are not here; the JDK then
;; makes Thread.ofVirtual() unsupported and runs everything else on platform
;; threads.
(defnative "jdk/internal/vm/ContinuationSupport.isSupported0()Z" () 0)

;; LockSupport.park / unpark: one permit per thread.
(defnative "jdk/internal/misc/Unsafe.park(ZJ)V" (this absolute time)
  (let ([s (self)] [t current-jthread])
    (unless (or (jthread-state-permit s) (interrupted? t)
                (and (fx= absolute 1) (<= time (quotient (now-nanos) 1000000))))
      (gil-block! (lambda () (or (jthread-state-permit s) (interrupted? t)))
                  (cond [(fx= absolute 1)
                         (let ([n (* time 1000000)])
                           (make-time 'time-utc (remainder n 1000000000) (quotient n 1000000000)))]
                        [(> time 0) (deadline-after-nanos time)]
                        [else #f])))
    (jthread-state-permit-set! s #f)))

(defnative "jdk/internal/misc/Unsafe.unpark(Ljava/lang/Object;)V" (this t)
  (let ([s (and t (eq-hashtable-ref jthread-states t #f))])
    (when s
      (jthread-state-permit-set! s #t)
      (gil-poke!))))

;; A started thread: run(), then what HotSpot does on the way out. An
;; uncaught exception goes to dispatchUncaughtException, which prints it the
;; JDK's way; anything else (a runtime bug) is reported here. Then
;; Thread.exit(), and the thread is marked terminated and its monitor
;; notified, which is what join() waits on.
(define (thread-body t)
  (call/cc
   (lambda (k)
     (with-exception-handler
      (lambda (raised)
        (k (let ([e (java-raise-of raised)])
            (cond
             [(java-halt? e) (exit-process (java-halt-status e))]
             [(jobj? e)
              (guard (e2 [#t (report-uncaught e2)])
                ((method-proc "java/lang/Thread" "dispatchUncaughtException(Ljava/lang/Throwable;)V") t e))]
             [else (report-uncaught e)]))))
      (lambda () ((jvirt t "run()V") t)))))
  (guard (e [#t (void)]) ((method-proc "java/lang/Thread" "exit()V") t))
  (jmonitor-enter t)
  (thread-alive! t #f)
  ((native-ref "java/lang/Object.notifyAll()V") t)
  (jmonitor-exit t)
  (set! java-live-threads (remq t java-live-threads))
  (flush-output-port (current-output-port)))

(define threads-started 0)   ; for the management beans; main isn't counted
(define threads-peak 1)
(defnative "java/lang/Thread.start0()V" (this)
  (let ([s (make-jthread-state this '() #f #f 0 #f 0)])
    (eq-hashtable-set! jthread-states this s)
    (thread-alive! this #t)
    (set! java-live-threads (cons this java-live-threads))
    (set! threads-started (fx+ threads-started 1))
    (set! threads-peak (fxmax threads-peak (fx+ 1 (length java-live-threads))))
    ;; jolt's fork-thread/daemon, so jolt's own exit waits for the thread
    ;; exactly when a JVM would: a daemon thread doesn't hold the process open
    (fork-thread/daemon
     (thread-daemon? this)
     (lambda ()
       (jthread-state-tid-set! s (get-thread-id))
       (self s)
       (gil-enter!)
       (note-native-thread! this)   ; the table is shared: under the GIL
       (thread-body this)
       (gil-leave!)))
    (void)))

;; A java.lang.Thread for a Chez thread that calls in from jolt, named
;; jolt-<id> in main's group. Like the main thread at boot, currentThread
;; answers it before its constructor has run.
(define (attach-jthread! s)
  (let ([t (jnew "java/lang/Thread")])
    (jthread-state-jthread-set! s t)
    (set! current-jthread t)
    (eq-hashtable-set! jthread-states t s)
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     t ((jvirt main-thread "getThreadGroup()Ljava/lang/ThreadGroup;") main-thread)
     (jstring (string-append "jolt-" (number->string (get-thread-id)))))
    (note-native-thread! t)
    (thread-alive! t #t)))

;; The live Java threads, main first, as Thread.getThreads and the
;; management beans list them (a jolt thread calling in isn't one, as a
;; native thread attached to a JVM is)
(define (live-jthreads) (cons main-thread (reverse java-live-threads)))
(defnative "java/lang/Thread.getThreads()[Ljava/lang/Thread;" ()
  (make-jarr "Ljava/lang/Thread;" (list->vector (live-jthreads))))
;; Thread.getAllStackTraces: the current thread's stack, from the native's
;; caller down, with the native's own frame on top as the JVM shows it; no
;; other thread's stack can be read here, which the method's contract allows
;; as a zero-length array
(defnative "java/lang/Thread.dumpThreads([Ljava/lang/Thread;)[[Ljava/lang/StackTraceElement;" (threads)
  (make-jarr "[Ljava/lang/StackTraceElement;"
             (vector-map (lambda (t)
                           (if (eq? t current-jthread)
                               (let ([arr (stack-trace-array
                                           (cons (list "java/lang/Thread" "dumpThreads" "([Ljava/lang/Thread;)[[Ljava/lang/StackTraceElement;" -1)
                                                 (visible-frames (java-frames-of (call/1cc (lambda (k) (raw-frames k)))))))])
                                 ;; line -2 is how a StackTraceElement says Native Method
                                 (jslot-set! (vector-ref (jarr-vec arr) 0) (slot-of "java/lang/StackTraceElement" "lineNumber") -2)
                                 arr)
                               (make-jarr "Ljava/lang/StackTraceElement;" (vector))))
                         (jarr-vec (nn threads)))))

;; ThreadMXBean's CPU times, measured as HotSpot measures them. On macOS that
;; is thread_info's user and system times (microseconds) through the
;; thread's mach port; on Linux the total is the thread's CPU clock
;; (pthread_getcpuclockid) and the user time utime in
;; /proc/self/task/<tid>/stat. Each Java thread's pthread_t, and on Linux
;; its kernel tid, is noted on the thread itself once it holds the GIL, so a
;; thread asked about before that (just started) answers -1. A thread is
;; looked up among the live ones under the GIL, and a thread leaves that
;; list before its OS thread can end, so its pthread_t is still good.
(define thread-natives (make-weak-eq-hashtable))   ; java.lang.Thread -> (pthread . tid)
(define thread-pthread-self (foreign-procedure "pthread_self" () uptr))
(define thread-gettid   ; glibc 2.30+; without it, user times are -1
  (or (and (not natives-macos?) (guard (e [#t #f]) (foreign-procedure "gettid" () int))) (lambda () 0)))
(define (note-native-thread! t) (eq-hashtable-set! thread-natives t (cons (thread-pthread-self) (thread-gettid))))
(define thread-cpu-enabled 1)   ; HotSpot measures by default
(defnative "sun/management/ThreadImpl.setThreadCpuTimeEnabled0(Z)V" (on) (set! thread-cpu-enabled on))

;; macOS: (user-ns . system-ns), or #f
(define mach-thread-of (and natives-macos? (foreign-procedure "pthread_mach_thread_np" (uptr) unsigned-32)))
(define mach-thread-info (and natives-macos? (foreign-procedure "thread_info" (unsigned-32 int uptr uptr) int)))
(define (mach-thread-times pthread)
  ;; struct thread_basic_info: user_time and system_time, each two int32s
  ;; (seconds, microseconds), then six more; THREAD_BASIC_INFO is 3, its
  ;; count 10 natural_t
  (let ([info (foreign-alloc 40)] [count (foreign-alloc 4)])
    (foreign-set! 'unsigned-32 count 0 10)
    (let* ([ok (eqv? 0 (mach-thread-info (mach-thread-of pthread) 3 info count))]
           [us (lambda (off) (+ (* 1000000 (foreign-ref 'integer-32 info off)) (foreign-ref 'integer-32 info (fx+ off 4))))]
           [r (and ok (cons (* 1000 (us 0)) (* 1000 (us 8))))])
      (foreign-free info)
      (foreign-free count)
      r)))
;; Linux
(define cpu-clock-of (and (not natives-macos?) (foreign-procedure "pthread_getcpuclockid" (uptr uptr) int)))
(define cpu-clock-gettime (foreign-procedure "clock_gettime" (int uptr) int))
(define (linux-thread-cpu-ns pthread)
  (let ([id (foreign-alloc 4)] [ts (foreign-alloc 16)])
    (let ([r (and (eqv? 0 (cpu-clock-of pthread id))
                  (eqv? 0 (cpu-clock-gettime (foreign-ref 'integer-32 id 0) ts))
                  (+ (* 1000000000 (foreign-ref 'integer-64 ts 0)) (foreign-ref 'integer-64 ts 8)))])
      (foreign-free id)
      (foreign-free ts)
      r)))
(define clock-ticks (and (not natives-macos?) ((foreign-procedure "sysconf" (int) long) 2)))   ; _SC_CLK_TCK
(define (linux-thread-user-ns tid)
  ;; tid 0: no gettid. The fields after the command's closing paren: state is the first,
  ;; utime (field 14) the twelfth
  (guard (e [#t #f])
    (let* ([_ (when (eqv? tid 0) (raise 'no-tid))]
           [line (call-with-input-file (string-append "/proc/self/task/" (number->string tid) "/stat") get-line)]
           [rest (let loop ([i (fx- (string-length line) 1)])
                   (if (char=? (string-ref line i) #\)) (substring line (fx+ i 2) (string-length line)) (loop (fx- i 1))))]
           [fields (let split ([i 0] [start 0] [acc '()])
                     (cond [(fx= i (string-length rest)) (reverse (cons (substring rest start i) acc))]
                           [(char=? (string-ref rest i) #\space) (split (fx+ i 1) (fx+ i 1) (cons (substring rest start i) acc))]
                           [else (split (fx+ i 1) start acc)]))])
      (* (string->number (list-ref fields 11)) (quotient 1000000000 clock-ticks)))))
;; id 0 is the current thread; -1 for a thread that isn't alive
(define (thread-cpu-ns id user?)
  (let* ([tid-slot (jfield-index "java/lang/Thread" "tid")]
         [t (if (eqv? id 0) current-jthread (find (lambda (t) (eqv? id (jslot t tid-slot))) (live-jthreads)))]
         [n (and t (eq-hashtable-ref thread-natives t #f))])
    (or (and n (if natives-macos?
                   (let ([ts (mach-thread-times (car n))]) (and ts (if user? (car ts) (+ (car ts) (cdr ts)))))
                   (if user? (linux-thread-user-ns (cdr n)) (linux-thread-cpu-ns (car n)))))
        -1)))
(defnative "sun/management/ThreadImpl.getThreadTotalCpuTime0(J)J" (id) (thread-cpu-ns id #f))
(defnative "sun/management/ThreadImpl.getThreadUserCpuTime0(J)J" (id) (thread-cpu-ns id #t))
(define (thread-cpu-times! ids result user?)
  (let ([iv (jarr-vec (nn ids))] [rv (jarr-vec (nn result))])
    (do ([i 0 (fx+ i 1)]) ((fx= i (vector-length iv))) (vector-set! rv i (thread-cpu-ns (vector-ref iv i) user?)))))
(defnative "sun/management/ThreadImpl.getThreadTotalCpuTime1([J[J)V" (ids result) (thread-cpu-times! ids result #f))
(defnative "sun/management/ThreadImpl.getThreadUserCpuTime1([J[J)V" (ids result) (thread-cpu-times! ids result #t))

;; The launcher's DestroyJavaVM: wait for every non-daemon thread, then run
;; the shutdown hooks.
(define (await-non-daemon-threads!)
  (let ([others (lambda () (filter (lambda (t) (not (thread-daemon? t))) java-live-threads))])
    (unless (null? (others))
      (gil-block! (lambda () (null? (others))) #f))))

(define (java-shutdown!)
  (await-non-daemon-threads!)
  (jinit "java/lang/Shutdown")
  ((method-proc "java/lang/Shutdown" "shutdown()V")))

(define (boot-threads!)
  (jinit "java/lang/ThreadGroup")
  (let ([system (jnew "java/lang/ThreadGroup")]
        [main (jnew "java/lang/ThreadGroup")])
    ((method-proc "java/lang/ThreadGroup" "<init>()V") system)
    ((method-proc "java/lang/ThreadGroup" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main system (jstring "main"))
    (jinit "java/lang/Thread")
    (set! main-thread (jnew "java/lang/Thread"))
    (set! current-jthread main-thread)
    (jthread-state-jthread-set! main-state main-thread)
    (eq-hashtable-set! jthread-states main-thread main-state)
    (set! interrupted-slot (jfield-index "java/lang/Thread" "interrupted"))
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main-thread main (jstring "main"))
    (note-native-thread! main-thread)
    (thread-alive! main-thread #t)))

;; --- references -------------------------------------------------------------------
;; A WeakReference's or PhantomReference's referent (and a subclass's: a
;; WeakHashMap or ThreadLocal entry, a Cleaner's) sits in the referent slot
;; inside a Chez weak pair, so the collector clears it as the JVM's does, and
;; Reference.get, refersTo0 and clear0 are the only readers of that slot for
;; those kinds (the JDK's FinalReference accessors touch FinalReferences
;; only). A SoftReference keeps its referent strongly, which the JVM may do
;; until memory runs short; FinalReference too (no finalization here). One
;; with a queue, or a jdk.internal.ref.Cleaner, is kept in `queued-refs` (weak
;; keys), and once a collection has cleared its referent the Reference
;; Handler thread gets it from the pending list, linked through `discovered`,
;; as the JVM hands them over: the JDK's own Java enqueues it, or runs the
;; Cleaner (a direct buffer's memory is freed that way).
(defnative "java/lang/ref/Finalizer.isFinalizationEnabled()Z" () 0)
;; ScopedValue rethrows a scope's failure through it
(defnative "jdk/internal/misc/Unsafe.throwException(Ljava/lang/Throwable;)V" (this t) (raise (nn t)))
;; the referent's slot, looked up once: refersTo runs on every soft-reference
;; cache hit (a warm Spring Boot start spent 0.1 s in the lookup)
(define referent-slot #f)
(define (referent-index)
  (or referent-slot (begin (set! referent-slot (jfield-index "java/lang/ref/Reference" "referent")) referent-slot)))
(define (referent-of r)   ; the referent, #f once cleared
  (let ([v (jslot r (referent-index))])
    (if (weak-pair? v) (let ([x (car v)]) (if (bwp-object? x) #f x)) v)))
(define queued-refs (make-weak-eq-hashtable))
;; Whether a reference class holds its referent weakly, per class: the class
;; tests, the queue slot and ReferenceQueue.NULL_QUEUE are looked up once,
;; since every reference constructed comes here (a lookup each time made a
;; construction 20 to 30 times slower)
(define weak-ref-classes (make-weak-eq-hashtable))
(define (weak-kind? r)
  (let ([c (jobj-cls r)])
    (let ([k (eq-hashtable-ref weak-ref-classes c 'unknown)])
      (if (eq? k 'unknown)
          (let ([w (or (jinstance? r "java/lang/ref/WeakReference") (jinstance? r "java/lang/ref/PhantomReference"))])
            (eq-hashtable-set! weak-ref-classes c w)
            w)
          k))))
(define queue-slot #f)
(define null-queue #f)
(define-override! "java/lang/ref/Reference.<init>(Ljava/lang/Object;Ljava/lang/ref/ReferenceQueue;)V"
  (lambda (this referent queue)
    (unless null-queue
      (jinit "java/lang/ref/ReferenceQueue")
      (set! queue-slot (jfield-index "java/lang/ref/Reference" "queue"))
      (set! null-queue (top-level-value '|java/lang/ref/ReferenceQueue:NULL_QUEUE|)))
    (let ([weak (and referent (weak-kind? this))])
      (jslot-set! this (referent-index) (if weak (weak-cons referent #f) referent))
      (jslot-set! this queue-slot (or queue null-queue))
      ;; a jdk.internal.ref.Cleaner passes a queue of its own, so it's here too
      (when (and weak queue (not (eq? queue null-queue)))
        (eq-hashtable-set! queued-refs this #t)))))
(define-override! "java/lang/ref/Reference.get()Ljava/lang/Object;" (lambda (this) (referent-of this)))
(defnative "java/lang/ref/Reference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (referent-of this) o) 1 0))
(defnative "java/lang/ref/Reference.clear0()V" (this)
  (eq-hashtable-delete! queued-refs this)
  (jslot-set! this (referent-index) #f))
(defnative "java/lang/ref/PhantomReference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (referent-of this) o) 1 0))
(defnative "java/lang/ref/PhantomReference.clear0()V" (this)
  (eq-hashtable-delete! queued-refs this)
  (jslot-set! this (referent-index) #f))
;; The pending list: references whose referent a collection cleared, taken out
;; of queued-refs (each is handed over once), linked through `discovered`.
;; Looked for when the collection count has moved since the last look.
(define pending-refs #f)
(define refs-looked-at -1)
(define (find-pending-refs!)
  (let ([n (collections)])
    (unless (= n refs-looked-at)
      (set! refs-looked-at n)
      (let ([discovered (jfield-index "java/lang/ref/Reference" "discovered")]
            [cleared (filter (lambda (r) (let ([v (jslot r (referent-index))]) (and (weak-pair? v) (bwp-object? (car v)))))
                             (vector->list (hashtable-keys queued-refs)))])
        (for-each (lambda (r)
                    (eq-hashtable-delete! queued-refs r)
                    (jslot-set! r (referent-index) #f)
                    (jslot-set! r discovered pending-refs)
                    (set! pending-refs r))
                  cleared))))
  pending-refs)
;; The Reference Handler thread waits here: until a collection has cleared
;; something, looking again every 100 ms with the GIL given up
(defnative "java/lang/ref/Reference.waitForReferencePendingList()V" ()
  (let loop ()
    (unless (find-pending-refs!)
      (gil-block! (lambda () #f) (deadline-after-nanos 100000000))
      (loop))))
(defnative "java/lang/ref/Reference.getAndClearReferencePendingList()Ljava/lang/ref/Reference;" ()
  (let ([r (find-pending-refs!)]) (set! pending-refs #f) r))
(defnative "java/lang/ref/Reference.hasReferencePendingList()Z" () (if (find-pending-refs!) 1 0))

;; --- overrides of Java-coded methods ----------------------------------------------

;; (none yet: Class.getEnumConstantsShared used to be one, before reflect.ss)

;; --- what HotSpot intrinsifies: hashing and Latin-1 string scans -------------------
;; ArraysSupport.vectorizedHashCode is behind every String.hashCode and
;; Arrays.hashCode, StringLatin1.equals behind String.equals and
;; indexOfChar behind String.indexOf(int); HotSpot replaces all three with
;; vector code, and the Java loops they are here cost a warm Spring Boot
;; start 0.2 s (spike 75). The same loops in Scheme, over the arrays'
;; vectors: the range checked once (the JDK's callers check theirs before
;; the intrinsic; a bad one throws as the interpreter would) and no wrap
;; per step beyond the int one. A basic type the JDK doesn't hash here is
;; its exception.
(define-syntax hash-step   ; 31 * h + e, as a Java int
  (syntax-rules ()
    [(_ h e) (let ([y (fxlogand (fx+ (fx* 31 h) e) #xffffffff)])
               (if (fx>= y #x80000000) (fx- y #x100000000) y))]))
;; A range past the vector: the exception the JVM's interpreter throws from
;; the loop, naming the first index it would have read (the intrinsic's
;; behaviour is undefined there; no JDK caller passes one, but a Scheme
;; error would escape every catch)
(define (check-hash-range! from end length)
  (when (or (fx< from 0) (fx> end length))
    (jthrow "java/lang/ArrayIndexOutOfBoundsException"
            (format "Index ~a out of bounds for length ~a" (if (fx< from 0) from (fxmax from length)) length))))
(define (vector-hash v from len init mask)   ; mask #f for the element itself
  (let ([end (fx+ from len)])
    (check-hash-range! from end (vector-length v))
    (let loop ([i from] [h init])
      (if (fx>= i end)
          h
          (loop (fx+ i 1) (hash-step h (if mask (fxlogand (vector-ref v i) mask) (vector-ref v i))))))))
(define-override! "jdk/internal/util/ArraysSupport.vectorizedHashCode(Ljava/lang/Object;IIII)I"
  (lambda (a from len init basic)
    (let ([v (jarr-vec (nn a))] [et (string-ref (jarr-elem a) 0)])
      (case basic
        [(4) (vector-hash v from len init #xff)]            ; T_BOOLEAN: unsigned bytes (Latin-1 strings)
        [(5) (if (char=? et #\B)                            ; T_CHAR: UTF-16 units of a byte[], or a char[]
                 (let ([end (fx+ from len)])
                   ;; in chars; the index named is the byte the JDK's getChar reads first
                   (check-hash-range! (fx* 2 from) (fx* 2 end) (vector-length v))
                   (let loop ([i from] [h init])
                     (if (fx>= i end)
                         h
                         (loop (fx+ i 1)
                               (hash-step h (fxlogor (fxlogand (vector-ref v (fx* 2 i)) #xff)
                                                     (fxsll (fxlogand (vector-ref v (fx+ (fx* 2 i) 1)) #xff) 8)))))))
                 (vector-hash v from len init #f))]
        [(8 9 10) (vector-hash v from len init #f)]         ; T_BYTE, T_SHORT, T_INT: signed, as stored
        [else (jthrow "java/lang/IllegalArgumentException" (string-append "unrecognized basic type: " (number->string basic)))]))))
(define-override! "java/lang/StringLatin1.equals([B[B)Z"
  (lambda (value other)
    (let* ([v (jarr-vec (nn value))] [o (jarr-vec (nn other))] [n (vector-length v)])
      (if (fx= n (vector-length o))
          (let loop ([i 0])
            (cond [(fx= i n) 1]
                  [(fx= (vector-ref v i) (vector-ref o i)) (loop (fx+ i 1))]
                  [else 0]))
          0))))
(define-override! "java/lang/StringLatin1.indexOfChar([BIII)I"
  (lambda (value ch from max)
    (let ([v (jarr-vec (nn value))] [c (i2b ch)])   ; (byte) ch, as the JDK compares
      (when (fx< from max) (check-hash-range! from max (vector-length v)))
      (let loop ([i from])
        (cond [(fx>= i max) -1]
              [(fx= (vector-ref v i) c) i]
              [else (loop (fx+ i 1))])))))

;; --- java.io.UnixFileSystem: the read-only queries --------------------------------
;; Answered with Chez's own file procedures. Changes, access checks and
;; canonical paths go through libc in files.ss.

(define (file-path f) (jstring->scheme (jslot f (jfield-index "java/io/File" "path"))))

(defnative "java/io/UnixFileSystem.getBooleanAttributes0(Ljava/io/File;)I" (this f)
  (let ([p (file-path f)])
    (if (file-exists? p)
        (fxlogor 1
                 (if (file-regular? p) 2 0)
                 (if (file-directory? p) 4 0))
        0)))
(defnative "java/io/UnixFileSystem.getLastModifiedTime0(Ljava/io/File;)J" (this f)
  (let ([p (file-path f)])
    (if (file-exists? p)
        (let ([t (file-modification-time p)]) (+ (* (time-second t) 1000) (quotient (time-nanosecond t) 1000000)))
        0)))
(defnative "java/io/UnixFileSystem.getLength0(Ljava/io/File;)J" (this f)
  (let ([p (file-path f)])
    (if (file-regular? p) (call-with-port (open-file-input-port p) port-length) 0)))
(defnative "java/io/UnixFileSystem.list0(Ljava/io/File;)[Ljava/lang/String;" (this f)
  (let ([p (file-path f)])
    (and (file-directory? p)
         (make-jarr "Ljava/lang/String;" (list->vector (map jstring (directory-list p)))))))

;; --- performance counters ---------------------------------------------------------
;; jdk.internal.perf counters (ZipFile counts the zips it opens) are plain
;; heap buffers here: nothing outside the process reads them.
(defnative "jdk/internal/perf/Perf.createLong(Ljava/lang/String;IIJ)Ljava/nio/ByteBuffer;" (this name variability units value)
  (jinit "java/nio/ByteBuffer")
  (let ([bb ((method-proc "java/nio/ByteBuffer" "allocate(I)Ljava/nio/ByteBuffer;") 8)])
    ((jvirt bb "putLong(IJ)Ljava/nio/ByteBuffer;") bb 0 value)
    bb))

;; --- modules and class loaders --------------------------------------------------
;; One flat class space: module wiring is recorded nowhere, and every loader
;; sees the same classes (user classpath, then java.base).

;; Resources come from the loader (main.clj) the way classes do: the user
;; classpath, then the JDK module whose packages the path falls under. The
;; JDK finds its own resources in the runtime image, which isn't read here,
;; so the getResourceAsStream methods are overridden to ask the loader. The
;; hook answers a jolt byte array, a record around a bytevector, or false.
(define resource-hook (lambda (path) #f))
(define resource-urls-hook (lambda (path) ""))
(define (set-resource-hook! f urls) (set! resource-hook f) (set! resource-urls-hook urls))
(define (host-bytevector x)
  (let ([bv (if (record? x) ((record-accessor (record-rtd x) 0) x) x)])
    (unless (bytevector? bv) (error 'champaca "resource hook gave no bytes" x))
    bv))
(define (resource-stream path) (bytes-stream (resource-hook path)))
;; Class.getResource*: a name without a leading / is relative to the class's
;; package. A ClassLoader takes names without one, and finds nothing for a
;; name with one (the loader's hook refuses it).
(define (class-resource-path this name)
  (let ([n (jstring->scheme (nn name))])
    (if (and (fx> (string-length n) 0) (char=? (string-ref n 0) #\/))
        (substring n 1 (string-length n))
        (let* ([c (jclass-cname (mirror-class this))]
               [slash (let loop ([i (fx- (string-length c) 1)])
                        (cond [(fx< i 0) #f] [(char=? (string-ref c i) #\/) i] [else (loop (fx- i 1))]))])
          (if slash (string-append (substring c 0 (fx+ slash 1)) n) n)))))
;; A loader of the program's own (a URLClassLoader, a plugin or fat-jar
;; loader) runs the JDK's own logic: its parent first, or the boot loader,
;; then its findResource, which a URLClassLoader answers from its URLs
;; through the JDK's code; the built-in loaders answer from the classpath and
;; the image. A class such a loader defined asks it for its resources, as
;; Class.getResource does through its class's loader. The boot loader has no
;; URLs here (JDK resources are streams only), so through a loader with no
;; parent a JDK resource is found by that loader alone.
(define (own-loader? l) (and l (not (jinstance? l "jdk/internal/loader/BuiltinClassLoader"))))
;; the platform loader has no class path: it sees the JDK's resources alone,
;; as streams, so a loader isolated under it doesn't see the application's
(define (platform-loader? l)
  (let ([s '|jdk/internal/loader/ClassLoaders:PLATFORM_LOADER|])
    (and l (global-bound? s) (eq? l (top-level-value s)))))
(define jdk-resource-hook (lambda (path) #f))
(define (set-jdk-resource-hook! f) (set! jdk-resource-hook f))
(define (bytes-stream found)
  (and found
       (let* ([bv (host-bytevector found)] [n (bytevector-length bv)] [v (make-vector n)])
         (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! v i (bytevector-s8-ref bv i)))
         (construct "java/io/ByteArrayInputStream" "([B)V" (make-jarr "B" v)))))
;; a built-in loader's stream: the bytes, or for a classpath directory (which
;; has a URL but no bytes) the URL opened, which lists it, as the JDK's
;; getResourceAsStream opens getResource's URL
(define (builtin-resource-stream l path)
  (if (platform-loader? l)
      (bytes-stream (jdk-resource-hook path))
      (or (resource-stream path)
          (let ([u (first-resource-url path)])
            (and u (guard (e [(and (jobj? e) (jinstance? e "java/io/IOException")) #f])
                     ((jvirt u "openStream()Ljava/io/InputStream;") u)))))))
(define (class-own-loader this)
  (let ([l (jslot this (jfield-index "java/lang/Class" "classLoader"))]) (and (own-loader? l) l)))
(define (own-get-resource l name)
  (let* ([parent (jslot l (jfield-index "java/lang/ClassLoader" "parent"))]
         [up (and parent ((jvirt parent "getResource(Ljava/lang/String;)Ljava/net/URL;") parent name))])
    (or up ((jvirt l "findResource(Ljava/lang/String;)Ljava/net/URL;") l name))))
(define (own-get-resource-stream l name)
  (let ([u ((jvirt l "getResource(Ljava/lang/String;)Ljava/net/URL;") l name)])
    ;; as the JDK's: an IOException opening it is no resource
    (and u (guard (e [(and (jobj? e) (jinstance? e "java/io/IOException")) #f])
             ((jvirt u "openStream()Ljava/io/InputStream;") u)))))
(define (own-get-resources l name)
  (let* ([parent (jslot l (jfield-index "java/lang/ClassLoader" "parent"))]
         [up (if parent
                 ((jvirt parent "getResources(Ljava/lang/String;)Ljava/util/Enumeration;") parent name)
                 (resource-enumeration-of '()))]
         [mine ((jvirt l "findResources(Ljava/lang/String;)Ljava/util/Enumeration;") l name)])
    (construct "java/lang/CompoundEnumeration" "([Ljava/util/Enumeration;)V"
               (make-jarr "Ljava/util/Enumeration;" (vector up mine)))))
(define-override! "java/lang/Class.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this name)
    (let ([l (class-own-loader this)] [p (class-resource-path this name)])
      (if l
          ((jvirt l "getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;") l (jstring p))
          (builtin-resource-stream #f p)))))
(define-override! "java/lang/ClassLoader.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this name)
    (if (own-loader? this)
        (own-get-resource-stream this (nn name))
        (builtin-resource-stream this (jstring->scheme (nn name))))))
(define-override! "java/lang/ClassLoader.getSystemResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (name) (builtin-resource-stream #f (jstring->scheme (nn name)))))

;; URLs, for classpath resources only (file: and jar:, which the JDK opens
;; with its own handlers)
(define (resource-url-list path)
  (let ([text (resource-urls-hook path)])
    (if (string=? text "")
        '()
        (map (lambda (u) (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring u)))
             (let loop ([i 0] [start 0] [acc '()])
               (cond [(fx= i (string-length text)) (reverse (cons (substring text start i) acc))]
                     [(char=? (string-ref text i) #\newline) (loop (fx+ i 1) (fx+ i 1) (cons (substring text start i) acc))]
                     [else (loop (fx+ i 1) start acc)]))))))
(define (first-resource-url path) (let ([us (resource-url-list path)]) (and (pair? us) (car us))))
(define (resource-enumeration-of urls)
  (let ([lst (construct "java/util/ArrayList" "()V")])
    (for-each (lambda (u) ((jvirt lst "add(Ljava/lang/Object;)Z") lst u)) urls)
    (jinit "java/util/Collections")
    ((method-proc "java/util/Collections" "enumeration(Ljava/util/Collection;)Ljava/util/Enumeration;") lst)))
(define (resource-enumeration path) (resource-enumeration-of (resource-url-list path)))
(define-override! "java/lang/Class.getResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (this name)
    (let ([l (class-own-loader this)] [p (class-resource-path this name)])
      (if l
          ((jvirt l "getResource(Ljava/lang/String;)Ljava/net/URL;") l (jstring p))
          (first-resource-url p)))))
(define-override! "java/lang/ClassLoader.getResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (this name)
    (if (own-loader? this)
        (own-get-resource this (nn name))
        (and (not (platform-loader? this)) (first-resource-url (jstring->scheme (nn name)))))))
(define-override! "java/lang/ClassLoader.getSystemResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (name) (first-resource-url (jstring->scheme (nn name)))))
(define-override! "java/lang/ClassLoader.getResources(Ljava/lang/String;)Ljava/util/Enumeration;"
  (lambda (this name)
    (if (own-loader? this)
        (own-get-resources this (nn name))
        (if (platform-loader? this)
            (resource-enumeration-of '())
            (resource-enumeration (jstring->scheme (nn name)))))))

;; Class.getProtectionDomain: a field the VM sets when it defines a class. A
;; class defined with a domain keeps that one (define-class!); a classpath
;; class gets the application loader's for its entry's code source, made the
;; first time it is asked for, as BuiltinClassLoader defines it with
;; (SecureClassLoader.getProtectionDomain, which caches one per code source);
;; any other class the JDK's all-permission domain with no code source
(define code-source-hook (lambda (cname) #f))
(define (set-code-source-hook! f) (set! code-source-hook f))
(define-override! "java/lang/Class.getProtectionDomain()Ljava/security/ProtectionDomain;"
  (lambda (this)
    (let* ([slot (jfield-index "java/lang/Class" "protectionDomain")] [pd (jslot this slot)])
      (or pd
          (let* ([c (mirror-class this)]
                 [url (and (not (eq? (jclass-kind c) 'array)) (user-class? (jclass-cname c))
                           (code-source-hook (jclass-cname c)))])
            (and url
                 (let* ([cs (construct "java/security/CodeSource" "(Ljava/net/URL;[Ljava/security/CodeSigner;)V"
                                       (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring url)) #f)]
                        [pd ((method-proc "java/security/SecureClassLoader"
                                          "getProtectionDomain(Ljava/security/CodeSource;)Ljava/security/ProtectionDomain;")
                             (app-loader) cs)])
                   (jslot-set! this slot pd)
                   pd)))
          (begin (jinit "java/lang/Class$Holder") (top-level-value '|java/lang/Class$Holder:allPermDomain|))))))
(define-override! "java/lang/ClassLoader.getSystemResources(Ljava/lang/String;)Ljava/util/Enumeration;"
  (lambda (name) (resource-enumeration (jstring->scheme (nn name)))))

;; Class.getPackage: every class here belongs to the boot loader, whose
;; packages the JDK finds through a runtime image this runtime doesn't have,
;; so it answered null for every class. One Package per name instead, built
;; the way the JDK's class loaders define one: a classpath class's from its
;; jar's manifest (the hook), a JDK class's as a package of its named module
;; (no version information, sealed), defined to its module's loader the way
;; ClassLoader.definePackage(Class) does (the boot loader's BootClassLoader
;; for a boot module), so Package.getPackage and getDefinedPackage answer the
;; same object. A
;; lambda class is champaca/Lambda$..., shared by every call site with the
;; same interface, so its package is champaca, in step with its getName (the
;; JVM's lambda lives in its caller's package).
(define jpackage-hook #f)
(define (set-jpackage-hook! f) (set! jpackage-hook f))
(define jpackage-table (make-hashtable string-hash string=?))
(define (text-lines s)
  (let loop ([i 0] [start 0] [acc '()])
    (cond [(fx= i (string-length s)) (reverse (cons (substring s start i) acc))]
          [(char=? (string-ref s i) #\newline) (loop (fx+ i 1) (fx+ i 1) (cons (substring s start i) acc))]
          [else (loop (fx+ i 1) start acc)])))
(define (jpackage-of cn)
  (let* ([dir (package-dir cn)]   ; hidden-class aware: Name/0x1 is in Name's package
         [pn (slashes->dots dir)])
    (or (hashtable-ref jpackage-table pn #f)
        (let ([p (jdk-module-package pn)]) (when p (hashtable-set! jpackage-table pn p)) p)
        (let* ([info (text-lines (jpackage-hook cn))]
               [given (lambda (s) (and (char=? (string-ref s 0) #\+) (substring s 1 (string-length s))))]
               [module (and (not (string=? dir "")) (string=? (car info) "jdk") (hashtable-ref package-modules dir #f))]
               [attrs (if (string=? (car info) "jdk")
                          (make-list 6 #f)
                          (map (lambda (s) (let ([v (given s)]) (and v (jstring v)))) (list-head (cdr info) 6)))]
               [seal (and (string=? (car info) "user")
                          (let ([u (given (list-ref info 7))])
                            (and u (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring u)))))]
               [p (if module
                      (construct "java/lang/Package" "(Ljava/lang/String;Ljava/lang/Module;)V" (jstring pn) module)
                      (apply construct "java/lang/Package"
                             "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/net/URL;Ljava/lang/ClassLoader;)V"
                             (jstring pn) (append attrs (list seal #f))))])
          ;; constructing ran Java code, which can let another thread in to
          ;; define the same package first: keep whichever got there. A JDK
          ;; package asked for before its module and the boot loader object
          ;; exist (the hook says jdk only for a package the runtime image
          ;; owns) isn't kept, so a later call gets its loader's
          (if (string=? (car info) "jdk")
              p
              (or (hashtable-ref jpackage-table pn #f)
                  (begin (hashtable-set! jpackage-table pn p) p)))))))
(define-override! "java/lang/Class.getPackage()Ljava/lang/Package;"
  (lambda (this)
    (let ([cn (jclass-cname (mirror-class this))])
      (and (not (prim-name? cn)) (not (char=? (string-ref cn 0) #\[))
           (jpackage-of cn)))))

(defnative "jdk/internal/loader/BootLoader.setBootLoaderUnnamedModule0(Ljava/lang/Module;)V" (m) (set-unnamed-module! m))
;; a JDK package's Package, from its module's loader, once the module and
;; the boot loader object exist (#f before, and for any other package)
(define (jdk-module-package pn)
  (let* ([m (hashtable-ref package-modules (slashes pn) #f)]
         [boot '|jdk/internal/loader/ClassLoaders:BOOT_LOADER|]
         [ld (and m (or (module-loader m) (and (global-bound? boot) (top-level-value boot))))])
    (and ld ((method-proc "java/lang/ClassLoader" "definePackage(Ljava/lang/String;Ljava/lang/Module;)Ljava/lang/Package;")
             ld (jstring pn) m))))

;; System packages are the boot modules' packages the boot loader has loaded
;; a class from, as HotSpot answers (Package.getPackage("javax.crypto") is
;; null until a class of it loads); a name with slashes or dots, as
;; BootLoader passes either. The location is the module's jrt: URL, which
;; BootLoader turns back into the module.
;; kept while neither the class table nor the module packages grow, which
;; is all either does
(define boot-packages-cache (list -1 -1 #f))   ; (classes packages table)
(define (boot-packages-loaded)
  (let ([nc (hashtable-size classes)] [np (hashtable-size package-modules)])
    (if (and (fx= (car boot-packages-cache) nc) (fx= (cadr boot-packages-cache) np))
        (caddr boot-packages-cache)
        (let ([t (boot-packages-scan)]) (set! boot-packages-cache (list nc np t)) t))))
(define (boot-packages-scan)
  (let ([t (make-hashtable string-hash string=?)])
    (vector-for-each
     (lambda (cn)
       (unless (or (char=? (string-ref cn 0) #\[) (prim-name? cn))
         (let* ([d (package-dir cn)] [m (hashtable-ref package-modules d #f)])
           (when (and m (not (module-loader m)) (not (user-class? cn))) (hashtable-set! t d m)))))
     (hashtable-keys classes))
    t))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageNames()[Ljava/lang/String;" ()
  (scheme-list->jarray "Ljava/lang/String;" (map jstring (vector->list (hashtable-keys (boot-packages-loaded))))))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageLocation(Ljava/lang/String;)Ljava/lang/String;" (n)
  (let ([m (hashtable-ref (boot-packages-loaded) (slashes (jstring->scheme (nn n))) #f)])
    (and m (jstring (string-append "jrt:/" (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name"))))))))
(defnative "java/lang/Module.defineModule0(Ljava/lang/Module;ZLjava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
  (m open? version location pns)
  (define-module! m (map jstring->scheme (vector->list (jarr-vec pns)))))
(defnative "java/lang/Module.addReads0(Ljava/lang/Module;Ljava/lang/Module;)V" (from to) (void))
(defnative "java/lang/Module.addExports0(Ljava/lang/Module;Ljava/lang/String;Ljava/lang/Module;)V" (from pn to) (void))
(defnative "java/lang/Module.addExportsToAll0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))
(defnative "java/lang/Module.addExportsToAllUnnamed0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))

;; A class that can be loaded: registered already, an array, or a class file
;; the loader can find (the resource hook looks where classes come from). Only
;; that is "not found". A class that exists but can't be loaded (an opcode or
;; attribute the translator lacks, a superclass that is missing) is a
;; NoClassDefFoundError naming the cause, as a class the JVM can't define is
;; a LinkageError: code catching Throwable or LinkageError around a probe for
;; an optional class goes on, code catching ClassNotFoundException alone stops
;; with the cause in front of it, and nothing is reported as absent that
;; isn't. A primitive's name is not a class's, as on the JVM.
(define (class-exists? n)
  (and (fx> (string-length n) 0)
       (not (prim-name? n))
       (not (hidden-class? n))   ; a hidden class can't be found by name
       (or (hashtable-ref classes n #f)
           (char=? (string-ref n 0) #\[)
           (and (resource-hook (string-append n ".class")) #t))))
(define (condition-text e)
  (let ([p (open-output-string)]) (display-condition e p) (get-output-string p)))
(define (try-class n)
  (and (class-exists? n)
       (guard (e [(not (jobj? e))
                  (jthrow "java/lang/NoClassDefFoundError"
                          (string-append (slashes->dots n) ": champaca could not load it: " (condition-text e)))])
         (find-class n))))
;; HotSpot's boot loader: the classes of the modules defined to it (a null
;; loader), never the classpath's, so a loader with no parent that delegates
;; up finds what it would on the JVM. A classpath class it hasn't seen isn't
;; loaded to be turned down: the asking loader may define that name itself.
(defnative "java/lang/ClassLoader.findBootstrapClass(Ljava/lang/String;)Ljava/lang/Class;" (name)
  (let* ([n (slashes (jstring->scheme name))]
         [c (and (or (hashtable-ref classes n #f) (jdk-package? n)) (try-class n))])
    (and c (builtin-loader-sees? #f c) (jmirror c))))
;; the classes a loader has loaded: for a built-in loader, those it can see
;; (below); any other loader has defined none, and finds what its parents
;; have by delegating, as ClassLoader.loadClass does next
(defnative "java/lang/ClassLoader.findLoadedClass0(Ljava/lang/String;)Ljava/lang/Class;" (this name)
  (let ([c (hashtable-ref classes (slashes (jstring->scheme name)) #f)])
    (and c
         (let ([dl (hashtable-ref class-loaders (jclass-cname c) #f)])
           (if dl
               (eq? dl this)   ; a class it defined
               (and (jinstance? this "jdk/internal/loader/BuiltinClassLoader") (builtin-loader-sees? this c))))
         (jmirror c))))

;; The built-in loaders (boot, platform, app) are this runtime's one class
;; table in disguise: each finds the classes the runtime can load and never
;; defines one from bytes itself, which is how the JDK's path would go (the
;; module's reader over jrt, then ClassLoader.defineClass). The
;; module-qualified lookups (Class.forName(Module, String), ServiceLoader
;; providers, resource bundles in jdk.localedata) land here too. What each
;; loader sees follows the JVM: the three delegate to whichever of them a
;; module is defined to, so each finds every JDK class, and only the
;; application loader has a class path, so a classpath class is found through
;; it alone. A resource stream from a named module
;; (Module.getResourceAsStream) comes from the same lookup as a classpath
;; one, for the module's own packages. Class.getResource and the ClassLoader
;; resource methods are overridden above, so the module-qualified
;; findResource is never reached.
;; What a built-in loader (this; #f is the boot loader proper, the one a null
;; loader names in Class.forName) would load, an array going by its element:
;; a class another loader defined is that loader's alone (a proxy's dynamic
;; module is defined to its loader and no built-in loader delegates to it,
;; probed: the platform loader doesn't find a proxy the app loader defined);
;; a classpath class is the application loader's; a JDK class is every
;; built-in loader object's (BootLoader's own BuiltinClassLoader included:
;; it delegates by package to the module's loader, as the JDK's does, and its
;; callers check the module), while the boot loader proper delegates to no
;; one and sees the boot modules only.
(define (builtin-loader-sees? this c)
  (let* ([e (element-name c)] [dl (hashtable-ref class-loaders e #f)])
    (cond [dl (eq? dl this)]
          [(user-class? e) (let ([a (app-loader)]) (and a (eq? this a)))]
          [this #t]
          [else (not (module-loader (class-module c)))])))
;; whether a class not loaded yet would be a JDK class: its package is one
;; the boot layer's modules own (before the layer exists, anything may be)
(define (jdk-package? n)
  (or (fxzero? (hashtable-size package-modules))
      (and (hashtable-ref package-modules (package-dir n) #f) #t)))
(define (class-or-null this name)
  ;; loadClass takes a binary name: one with slashes or an array descriptor is
  ;; not found. A loader other than the application one never loads a
  ;; classpath class, not even to turn it down: a loader of the program's own
  ;; may be about to define that name itself
  (let ([s (jstring->scheme (nn name))])
    (and (fx> (string-length s) 0)
         (not (char=? (string-ref s 0) #\[))
         (not (memv #\/ (string->list s)))
         (let ([n (slashes s)])
           (and (or (hashtable-ref classes n #f)
                    (jdk-package? n)
                    (let ([a (app-loader)]) (and a (eq? this a))))
                (let ([c (try-class n)])
                  (and c (builtin-loader-sees? this c) (jmirror c))))))))
;; --- classes defined at run time -------------------------------------------------
;; ClassLoader.defineClass, Lookup.defineClass and defineHiddenClass, Proxy
;; (JavaLangAccess.defineClass) and Unsafe.defineClass0 end in one family of
;; natives, which hand the bytes to main.clj (define-class-hook) to parse,
;; link and register as a class of this run, or to restore from the library
;; cache when a loader of the program's own (not a built-in one) defines a
;; named, non-hidden class from bytes an earlier run saw. The bytes cross
;; as a Latin-1 string, since a byte array doesn't. The defining loader is
;; kept (class-loaders, for getClassLoader and findLoadedClass) and a hidden
;; class's name marked (hidden-classes).
(define define-class-hook #f)
(define (set-define-class-hook! f) (set! define-class-hook f))
(define (define-class-hook* . args) (call-unbounded (lambda () (apply define-class-hook args))))   ; champaca's work, not the program's
(define (bytes->latin1 v off len)
  (let ([s (make-string len)])
    (do ([i 0 (fx+ i 1)]) ((fx= i len) s)
      (string-set! s i (integer->char (fxlogand (vector-ref v (fx+ off i)) #xff))))))
;; a class nothing else has, asked of the loader whose class names it (a
;; loader of the program's own: the built-in ones answer from the class table
;; already); #t when that defined it. A name already being asked on this
;; thread isn't asked again, so a loader whose loadClass names the class it
;; is defining can't loop. The loader's ClassNotFoundException means no.
(define asking-loader (make-thread-parameter '()))
(define (ask-resolving-loader name)
  (let ([l (resolving-loader)])
    (and l (not (jinstance? l "jdk/internal/loader/BuiltinClassLoader"))
         (not (member name (asking-loader)))
         ;; the loader's own code resolves nothing through it
         (parameterize ([asking-loader (cons name (asking-loader))] [resolving-loader #f])
           ;; only ClassNotFoundException means not there. Any other error
           ;; the loader throws (ClassFormatError, LinkageError) is the
           ;; resolution's, as on the JVM: it is kept for the name, and the
           ;; instruction that names the class throws it (jthrow-missing)
           (guard (e [(and (jobj? e) (jinstance? e "java/lang/ClassNotFoundException")) #f]
                     [(jobj? e) (hashtable-set! resolution-errors name e)
                                (set! resolution-error-count (fx+ resolution-error-count 1))
                                (hashtable-set! resolution-error-stamps name resolution-error-count)
                                #f])
             ((jvirt l "loadClass(Ljava/lang/String;)Ljava/lang/Class;") l (jstring (slashes->dots name)))
             (and (hashtable-ref classes name #f) #t))))))
;; when each name's resolution error was recorded: defineClass rethrows one
;; for a missing supertype only if this definition's own lookup recorded it
(define resolution-error-count 0)
(define resolution-error-stamps (make-hashtable string-hash string=?))
(define (define-class! loader given v off len hidden? classdata pd)
  (unless (and (fx>= off 0) (fx>= len 0) (fx<= (fx+ off len) (vector-length v)))
    (jthrow "java/lang/ArrayIndexOutOfBoundsException"
            (format "Array region ~a..~a out of bounds for length ~a" off (+ off len) (vector-length v))))
  (let* ([stamp resolution-error-count]
         [name (and given (slashes (jstring->scheme given)))]
         ;; what fails past parsing (a superclass that isn't there, a link
         ;; error) is a NoClassDefFoundError naming the cause, as a class
         ;; the JVM can't define is a LinkageError
         ;; a supertype the loader itself failed on (a class extending itself:
         ;; its nested definition threw ClassCircularityError) is that error,
         ;; as the JVM rethrows a failed resolution's error
         [r (guard (e [(and (not (jobj? e))
                            (let ([m (class-not-found-in e)])
                              (and m (fx> (hashtable-ref resolution-error-stamps m 0) stamp)
                                   (hashtable-ref resolution-errors m #f))))
                       => raise]
                      ;; a supertype that's nowhere: the JVM's message is its slashed name
                      [(and (not (jobj? e)) (class-not-found-in e)) => (lambda (m) (jthrow "java/lang/NoClassDefFoundError" m))]
                      [(not (jobj? e)) (jthrow "java/lang/NoClassDefFoundError" (condition-text e))])
              (parameterize ([resolving-loader loader])
                (define-class-hook* name (bytes->latin1 v off len) hidden?
                                   (and loader (not hidden?)
                                        (not (jinstance? loader "jdk/internal/loader/BuiltinClassLoader"))))))]
         [sp (let loop ([i 0]) (if (char=? (string-ref r i) #\space) i (loop (fx+ i 1))))]
         [kind (substring r 0 sp)]
         [arg (substring r (fx+ sp 1) (string-length r))])
    (cond [(string=? kind "bad") (jthrow "java/lang/ClassFormatError" arg)]
          ;; a definition the JVM refuses (main.clj check-definition!): "<error class>|<text>"
          [(string=? kind "link")
           (let ([bar (let find ([k 0]) (if (char=? (string-ref arg k) #\|) k (find (fx+ k 1))))])
             (jthrow (substring arg 0 bar) (substring arg (fx+ bar 1) (string-length arg))))]
          [(string=? kind "wrong")   ; the JVM's message keeps both names slashed
           (jthrow "java/lang/NoClassDefFoundError" (string-append name " (wrong name: " arg ")"))]
          [(string=? kind "dup")
           (jthrow "java/lang/LinkageError"
                   (string-append "loader attempted duplicate class definition for " (slashes->dots arg)
                                  " (one class table here, whatever the loader)"))]
          [else
           (when hidden? (hashtable-set! hidden-classes arg #t))
           (when loader (hashtable-set! class-loaders arg loader))
           (let ([m (jmirror (find-class arg))])
             (when classdata (jslot-set! m (jfield-index "java/lang/Class" "classData") classdata))
             ;; the domain it was defined with is its getProtectionDomain
             (when pd (jslot-set! m (jfield-index "java/lang/Class" "protectionDomain") pd))
             m)])))
(defnative "java/lang/ClassLoader.defineClass1(Ljava/lang/ClassLoader;Ljava/lang/String;[BIILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;"
  (loader name b off len pd source)
  (define-class! loader name (jarr-vec (nn b)) off len #f #f pd))
(defnative "java/lang/ClassLoader.defineClass2(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/nio/ByteBuffer;IILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;"
  (loader name b off len pd source)
  ;; only a direct buffer gets here (ClassLoader.defineClass copies a heap
  ;; one into a byte[] first): its bytes sit in native memory at address,
  ;; from the position given, which is what Unsafe gave it
  (let ([hb (jslot (nn b) (jfield-index "java/nio/ByteBuffer" "hb"))])
    (if hb
        (define-class! loader name (jarr-vec hb) (fx+ off (jslot b (jfield-index "java/nio/ByteBuffer" "offset"))) len #f #f pd)
        (let* ([addr (jslot b (jfield-index "java/nio/Buffer" "address"))]
               [v (make-vector len)])
          (do ([i 0 (fx+ i 1)]) ((fx= i len))
            (vector-set! v i (foreign-ref 'unsigned-8 addr (fx+ off i))))
          (define-class! loader name v 0 len #f #f pd)))))
(defnative "java/lang/ClassLoader.defineClass0(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/String;[BIILjava/security/ProtectionDomain;ZILjava/lang/Object;)Ljava/lang/Class;"
  (loader lookup name b off len pd initialize flags classdata)
  ;; flags: 1 nestmate (the lookup class's nest host becomes its own), 2 hidden, 4 strong, 8 vm annotations
  (let ([m (define-class! loader name (jarr-vec (nn b)) off len (fxpositive? (fxlogand flags 2)) classdata pd)])
    (when (and lookup (fxpositive? (fxlogand flags 1)))
      (hashtable-set! dynamic-nest-hosts (jclass-cname (mirror-class m)) (nest-host (mirror-class lookup))))
    (when (eqv? initialize 1) (jinit (jclass-cname (mirror-class m))))
    m))
(defnative "jdk/internal/misc/Unsafe.defineClass0(Ljava/lang/String;[BIILjava/lang/ClassLoader;Ljava/security/ProtectionDomain;)Ljava/lang/Class;"
  (this name b off len loader pd)
  (define-class! loader name (jarr-vec (nn b)) off len #f #f pd))

(define-override! "jdk/internal/loader/BuiltinClassLoader.loadClassOrNull(Ljava/lang/String;Z)Ljava/lang/Class;"
  (lambda (this name resolve) (class-or-null this name)))
(define-override! "jdk/internal/loader/BuiltinClassLoader.findClass(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Class;"
  (lambda (this mn name) (class-or-null this name)))
(define-override! "jdk/internal/loader/BuiltinClassLoader.findResourceAsStream(Ljava/lang/String;Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this mn name)
    (let ([path (jstring->scheme (nn name))])
      (if mn
          ;; the module named must own the resource's package
          (let ([m (hashtable-ref package-modules (package-dir path) #f)])
            (and m (string=? (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name"))) (jstring->scheme mn))
                 (resource-stream path)))
          (resource-stream path)))))

;; --- jdk.internal.util.ByteArray and ByteArrayLittleEndian -------------------------
;; The JDK reads multi-byte values out of a byte[] through VarHandles
;; (DataInputStream, ZoneInfoFile, ObjectInputStream), which this runtime does
;; not have yet. These overrides read and write the bytes directly.

(define (bytes-get a off n big?)
  (let loop ([k 0] [acc 0])
    (if (fx= k n)
        acc
        (loop (fx+ k 1)
              (logior (ash acc 8) (fxlogand (jaload a (fx+ off (if big? k (fx- (fx- n k) 1)))) #xff))))))

(define (bytes-set! a off n big? v)
  ;; byte k counts from the least significant end
  (do ([k 0 (fx+ k 1)]) ((fx= k n))
    (jastore a (fx+ off (if big? (fx- (fx- n k) 1) k)) (i2b (logand (ash v (* -8 k)) #xff)))))

(for-each
 (lambda (cls+big)
   (let ([cls (car cls+big)] [big? (cdr cls+big)])
     (define (def name desc proc) (define-override! (string-append cls "." name desc) proc))
     (def "getBoolean" "([BI)Z" (lambda (a i) (if (zero? (bytes-get a i 1 big?)) 0 1)))
     (def "getChar" "([BI)C" (lambda (a i) (bytes-get a i 2 big?)))
     (def "getShort" "([BI)S" (lambda (a i) (i2s (bytes-get a i 2 big?))))
     (def "getUnsignedShort" "([BI)I" (lambda (a i) (bytes-get a i 2 big?)))
     (def "getInt" "([BI)I" (lambda (a i) (i32 (bytes-get a i 4 big?))))
     (def "getLong" "([BI)J" (lambda (a i) (i64 (bytes-get a i 8 big?))))
     (def "getFloat" "([BI)F" (lambda (a i) (bits->float (i32 (bytes-get a i 4 big?)))))
     (def "getFloatRaw" "([BI)F" (lambda (a i) (bits->float (i32 (bytes-get a i 4 big?)))))
     (def "getDouble" "([BI)D" (lambda (a i) (bits->double (i64 (bytes-get a i 8 big?)))))
     (def "getDoubleRaw" "([BI)D" (lambda (a i) (bits->double (i64 (bytes-get a i 8 big?)))))
     (def "setBoolean" "([BIZ)V" (lambda (a i v) (bytes-set! a i 1 big? (if (eqv? v 0) 0 1))))
     (def "setChar" "([BIC)V" (lambda (a i v) (bytes-set! a i 2 big? v)))
     (def "setShort" "([BIS)V" (lambda (a i v) (bytes-set! a i 2 big? v)))
     (def "setUnsignedShort" "([BII)V" (lambda (a i v) (bytes-set! a i 2 big? v)))
     (def "setInt" "([BII)V" (lambda (a i v) (bytes-set! a i 4 big? v)))
     (def "setLong" "([BIJ)V" (lambda (a i v) (bytes-set! a i 8 big? v)))
     (def "setFloat" "([BIF)V" (lambda (a i v) (bytes-set! a i 4 big? (float->bits v))))
     (def "setFloatRaw" "([BIF)V" (lambda (a i v) (bytes-set! a i 4 big? (float->bits v))))
     (def "setDouble" "([BID)V" (lambda (a i v) (bytes-set! a i 8 big? (double->bits v))))
     (def "setDoubleRaw" "([BID)V" (lambda (a i v) (bytes-set! a i 8 big? (double->bits v))))))
 '(("jdk/internal/util/ByteArray" . #t) ("jdk/internal/util/ByteArrayLittleEndian" . #f)))

;; --- native libraries and java.util.zip checksums ---------------------------------
;; The JDK's own natives are the Scheme definitions in this file, so a JDK
;; library ("zip", "net", "nio") has nothing left to load.
(define-override! "jdk/internal/loader/BootLoader.loadLibrary(Ljava/lang/String;)V" (lambda (name) (void)))

;; CRC-32 (IEEE, reflected, polynomial #xEDB88320); crc is a Java int.
(define crc32-table
  (let ([t (make-vector 256)])
    (do ([n 0 (fx+ n 1)]) ((fx= n 256) t)
      (vector-set! t n (let loop ([c n] [k 0])
                         (if (fx= k 8) c
                             (loop (if (fxodd? c) (fxlogxor #xEDB88320 (fxsrl c 1)) (fxsrl c 1)) (fx+ k 1))))))))
(define (crc32-byte c b)
  (fxlogxor (vector-ref crc32-table (fxlogand (fxlogxor c b) #xff)) (fxsrl c 8)))
(define (crc32-bytes crc bytes off len)
  (let ([v (jarr-vec bytes)])
    (let loop ([i 0] [c (fxlogxor (fxlogand crc #xffffffff) #xffffffff)])
      (if (fx= i len)
          (i32 (fxlogxor c #xffffffff))
          (loop (fx+ i 1) (crc32-byte c (fxlogand (vector-ref v (fx+ off i)) #xff)))))))
(defnative "java/util/zip/CRC32.update(II)I" (crc b)
  (i32 (fxlogxor (crc32-byte (fxlogxor (fxlogand crc #xffffffff) #xffffffff) (fxlogand b #xff)) #xffffffff)))
(defnative "java/util/zip/CRC32.updateBytes0(I[BII)I" (crc bytes off len) (crc32-bytes crc bytes off len))

;; Adler-32: two sums mod 65521, b in the high half; adler is a Java int.
(define (adler32-bytes adler bytes off len)
  (let ([v (jarr-vec bytes)])
    (let loop ([i 0] [a (fxlogand adler #xffff)] [b (fxlogand (fxsrl (fxlogand adler #xffffffff) 16) #xffff)])
      (if (fx= i len)
          (i32 (fxlogor (fxsll b 16) a))
          (let ([a (fxmodulo (fx+ a (fxlogand (vector-ref v (fx+ off i)) #xff)) 65521)])
            (loop (fx+ i 1) a (fxmodulo (fx+ b a) 65521)))))))
(defnative "java/util/zip/Adler32.update(II)I" (adler b)
  (adler32-bytes adler (make-jarr "B" (vector b)) 0 1))
(defnative "java/util/zip/Adler32.updateBytes(I[BII)I" (adler bytes off len) (adler32-bytes adler bytes off len))

;; --- VarHandles -------------------------------------------------------------------
;; A VarHandle is a bare java/lang/invoke/VarHandle object (allocated, never
;; constructed, so instanceof and checkcast work) mapped to what it points
;; at. Codegen sends every signature-polymorphic call on VarHandle to vh-call
;; with the access mode's name, so one procedure serves get, set,
;; compareAndSet, getAndAdd and the rest. Memory-ordering suffixes (Volatile,
;; Acquire, Release, Opaque, Plain) don't matter here: every access is
;; already sequentially consistent. A mode the JVM doesn't support for the
;; handle throws UnsupportedOperationException named after the mode, before
;; the receiver is looked at, as the JVM does: numeric updates on references
;; and booleans, bitwise ones on references and floating point, anything but
;; reads on a final field, and anything but plain get and set on a byte[]
;; view (JDK 25 can't promise a heap byte[] is aligned).
;;
;; kind   coordinates      a                      b
;; field  (obj)            slot index             -
;; static ()               the static's symbol    class to initialize
;; array  (array index)    -                      -
;; view   (byte[] index)   width in bytes         big-endian?
;;
;; type is the variable's descriptor, coords the coordinates' descriptors
;; (what coordinateTypes() shows), ops the operations it supports (plain-only?
;; when only the plain modes of those are), and parts each access type's
;; #(args ret sites), made when first called: sites maps a call site's
;; descriptor to whether its types are already the access type's.

(define-record-type vh-target (fields kind a b type coords ops plain-only? parts)
  (sealed #t) (nongenerative champaca-vh-target))
(define vh-targets (make-weak-eq-hashtable))

(define (vh-target-new kind a b type coords final?)
  (let ([ref? (memv (string-ref type 0) '(#\L #\[))])
    (make-vh-target kind a b type coords
                    (cond [(eq? kind 'view) '(get set)]
                          [final? '(get)]
                          [else (append '(get set cas cax gas)
                                        (if (or ref? (string=? type "Z")) '() '(add))
                                        (if (or ref? (member type '("F" "D"))) '() '(or and xor)))])
                    (eq? kind 'view) (make-vector 5 #f))))

(define vh-exact-slot #f)
(define (vh-exact-index)
  (or vh-exact-slot
      (begin (set! vh-exact-slot (jfield-index "java/lang/invoke/VarHandle" "exact")) vh-exact-slot)))

(define (vh-of t exact?)
  (let ([vh (jnew "java/lang/invoke/VarHandle")])
    (jslot-set! vh (vh-exact-index) (if exact? 1 0))
    (eq-hashtable-set! vh-targets vh t)
    vh))

(define (make-vh kind a b type coords final?)
  (vh-of (vh-target-new kind a b type coords final?) #f))

;; access mode name -> operation
(define vh-mode-ops
  (let ([t (make-hashtable string-hash string=?)])
    (for-each (lambda (e) (for-each (lambda (n) (hashtable-set! t n (car e))) (cdr e)))
              '((get "get" "getVolatile" "getOpaque" "getAcquire")
                (set "set" "setVolatile" "setOpaque" "setRelease")
                (cas "compareAndSet" "weakCompareAndSetPlain" "weakCompareAndSet"
                     "weakCompareAndSetAcquire" "weakCompareAndSetRelease")
                (cax "compareAndExchange" "compareAndExchangeAcquire" "compareAndExchangeRelease")
                (gas "getAndSet" "getAndSetAcquire" "getAndSetRelease")
                (add "getAndAdd" "getAndAddAcquire" "getAndAddRelease")
                (or "getAndBitwiseOr" "getAndBitwiseOrAcquire" "getAndBitwiseOrRelease")
                (and "getAndBitwiseAnd" "getAndBitwiseAndAcquire" "getAndBitwiseAndRelease")
                (xor "getAndBitwiseXor" "getAndBitwiseXorAcquire" "getAndBitwiseXorRelease")))
    t))

;; mode -> (op . plain?), cached by the identity of the name, which at a call
;; site is a literal
(define vh-mode-infos (make-weak-eq-hashtable))
(define (vh-mode-info mode)
  (or (eq-hashtable-ref vh-mode-infos mode #f)
      (let ([i (cons (hashtable-ref vh-mode-ops mode #f) (and (member mode '("get" "set")) #t))])
        (eq-hashtable-set! vh-mode-infos mode i)
        i)))

(define (vh-supports? t mode)
  (let ([i (vh-mode-info mode)])
    (and (memq (car i) (vh-target-ops t)) (or (cdr i) (not (vh-target-plain-only? t))) #t)))

(define (vh-target-of vh mode)
  (or (eq-hashtable-ref vh-targets (nn vh) #f)
      (jthrow "java/lang/UnsupportedOperationException" mode)))

(define (view-decode t u)
  (case t [("S") (i2s u)] [("I") (i32 u)] [("J") (i64 u)]
          [("F") (bits->float (i32 u))] [("D") (bits->double (i64 u))] [else u]))
(define (view-encode t v)
  (case t [("F") (float->bits v)] [("D") (double->bits v)] [else v]))

;; A reference array checks what it stores against its own element type, as
;; aastore does. Only a plain set names the class, as on the JVM.
(define (array-store-check a v mode)
  (when (and v (not (prim-desc? (jarr-elem (nn a)))) (not (array-store-ok? a v)))
    (jthrow "java/lang/ArrayStoreException"
            (and (string=? mode "set") (slashes->dots (class-name-of v))))))

;; read and write the variable at one access's coordinates
(define (vh-get t coords)
  (case (vh-target-kind t)
    [(field) (vector-ref (jobj-slots (nn (car coords))) (vh-target-a t))]
    [(static) (top-level-value (vh-target-a t))]
    [(array) (jaload (car coords) (cadr coords))]
    [else (view-decode (vh-target-type t)
                       (bytes-get (car coords) (cadr coords) (vh-target-a t) (vh-target-b t)))]))

(define (vh-set! t coords v mode)
  (case (vh-target-kind t)
    [(field) (vector-set! (jobj-slots (nn (car coords))) (vh-target-a t) v)]
    [(static) (set-top-level-value! (vh-target-a t) v)]
    [(array) (jastore (car coords) (cadr coords) v)]
    [else (bytes-set! (car coords) (cadr coords) (vh-target-a t) (vh-target-b t)
                      (view-encode (vh-target-type t) v))]))

(define (wrap-as t v)
  (case t [("I") (i32 v)] [("J") (i64 v)] [("S") (i2s v)] [("B") (i2b v)] [("C") (logand v #xffff)]
          [("F") (f32 v)] [else v]))

(define access-types '#("GET" "SET" "COMPARE_AND_SET" "COMPARE_AND_EXCHANGE" "GET_AND_UPDATE"))
(define (op-access-type op)
  (case op [(get) 0] [(set) 1] [(cas) 2] [(cax) 3] [else 4]))

;; #(args ret sites) of the access type, made once per handle
(define (vh-parts t k)
  (or (vector-ref (vh-target-parts t) k)
      (let* ([d (vh-access-type-desc t (vector-ref access-types k))]
             [p (vector (desc-args d) (desc-ret d) (make-weak-eq-hashtable))])
        (vector-set! (vh-target-parts t) k p)
        p)))

(define (method-type-text args ret)
  (jstring-of (desc->method-type (args->desc args ret))))

;; the JDK's text: the handle's type and the site's, each with the VarHandle
;; as a leading argument
(define (cannot-convert targs tret sargs sret)
  (define (with-vh text)
    (string-append "(VarHandle" (if (char=? (string-ref text 1) #\)) "" ",")
                   (substring text 1 (string-length text))))
  (jthrow "java/lang/invoke/WrongMethodTypeException"
          (string-append "cannot convert MethodHandle" (with-vh (method-type-text targs tret))
                         " to " (with-vh (method-type-text sargs sret)))))

;; A static's class is initialized first. A reference array checks the index,
;; then the value it may store, before anything is read or compared, which
;; is the JVM's order: a failing compareAndSet still throws for a bad value.
;; a derived handle (a coordinate combinator's) maps its coordinates onto its
;; base and converts values both ways, then the base does the access, so
;; updates and compares happen in the base's domain, as the JVM's adapters do
(define (vh-run t op mode args)
  (if (eq? (vh-target-kind t) 'derived)
      (let ([n (length (vh-target-coords t))])
        (let-values ([(coords to from) ((vh-target-b t) (list-head args n))])
          (let ([r (vh-run (vh-target-a t) op mode
                           (append coords (if to (map to (list-tail args n)) (list-tail args n))))])
            (if (and from (memq op '(get cax gas add or and xor))) (from r) r))))
      (vh-run-direct t op mode args)))

(define (vh-run-direct t op mode args)
  (if (eq? (vh-target-kind t) 'segment)
      (segment-access t op mode args)
      (vh-run-plain t op mode args)))

(define (vh-run-plain t op mode args)
  (let* ([ty (vh-target-type t)]
         [vals (list-tail args (length (vh-target-coords t)))])
    (define (get) (vh-get t args))
    (define (set v) (vh-set! t args v mode))
    (define (update f) (let ([old (get)]) (set (wrap-as ty (f old))) old))
    (case (vh-target-kind t)
      [(static) (jinit (vh-target-b t))]
      [(array)
       (case op
         [(set gas) (jaload (car args) (cadr args)) (array-store-check (car args) (car vals) mode)]
         [(cas cax) (jaload (car args) (cadr args)) (array-store-check (car args) (cadr vals) mode)])])
    (case op
      [(get) (get)]
      [(set) (set (car vals))]
      [(cas) (if (same-bits? (get) (car vals)) (begin (set (cadr vals)) 1) 0)]
      [(cax) (let ([old (get)]) (when (same-bits? old (car vals)) (set (cadr vals))) old)]
      [(gas) (let ([old (get)]) (set (car vals)) old)]
      [(add) (update (lambda (old) (+ old (car vals))))]
      [(or) (update (lambda (old) (logior old (car vals))))]
      [(and) (update (lambda (old) (logand old (car vals))))]
      [(xor) (update (lambda (old) (logxor old (car vals))))])))

;; whether a call site's types are already the access type's, remembered per
;; site descriptor
(define (vh-site-same? e site)
  (let ([v (eq-hashtable-ref (vector-ref e 2) site 'unknown)])
    (if (eq? v 'unknown)
        (let* ([sp (site-parts site)]
               [same? (and (equal? (car sp) (vector-ref e 0)) (string=? (cdr sp) (vector-ref e 1)))])
          (eq-hashtable-set! (vector-ref e 2) site same?)
          same?)
        v)))

;; site is the call site's descriptor (a literal, so its parse is cached by
;; identity). Its argument and result types are adapted to the access type's,
;; as asType would; a handle with invoke-exact behavior wants them equal.
(define (vh-call vh mode site . args)
  (let* ([t (vh-target-of vh mode)]
         [op (car (vh-mode-info mode))])
    (unless (vh-supports? t mode)
      (jthrow "java/lang/UnsupportedOperationException" mode))
    (let ([e (vh-parts t (op-access-type op))])
      (if (vh-site-same? e site)
          (vh-run t op mode args)
          (let* ([sp (site-parts site)] [sargs (car sp)] [targs (vector-ref e 0)] [tret (vector-ref e 1)])
            (unless (fx= (length sargs) (length targs))
              (jthrow "java/lang/invoke/WrongMethodTypeException"
                      (string-append "expected " (method-type-text targs tret)
                                     " but found " (method-type-text sargs (cdr sp)))))
            ;; a void on either side is a dropped or default result
            (unless (and (for-all prim-convertible? sargs targs)
                         (or (member "V" (list tret (cdr sp))) (prim-convertible? tret (cdr sp))))
              (cannot-convert targs tret sargs (cdr sp)))
            (when (eqv? 1 (jslot vh (vh-exact-index)))
              (jthrow "java/lang/invoke/WrongMethodTypeException"
                      (string-append "handle's method type " (method-type-text targs tret)
                                     " but found " (method-type-text sargs (cdr sp)))))
            (adapt tret (cdr sp) (vh-run t op mode (adapt-all sargs targs args))))))))

;; VarHandle's own methods. varType, coordinateTypes, accessModeType and
;; toString are the JDK's, built on accessModeTypeUncached.
(define (vh-access-type-desc t type-name)
  (let ([cs (vh-target-coords t)] [v (vh-target-type t)])
    (case type-name
      [("GET") (args->desc cs v)]
      [("SET") (args->desc (append cs (list v)) "V")]
      [("COMPARE_AND_SET") (args->desc (append cs (list v v)) "Z")]
      [("COMPARE_AND_EXCHANGE") (args->desc (append cs (list v v)) v)]
      [("GET_AND_UPDATE") (args->desc (append cs (list v)) v)])))

(define (access-mode-name m)
  (str-of (jslot (nn m) (jfield-index "java/lang/invoke/VarHandle$AccessMode" "methodName"))))

(define-override! "java/lang/invoke/VarHandle.accessModeTypeUncached(Ljava/lang/invoke/VarHandle$AccessType;)Ljava/lang/invoke/MethodType;"
  (lambda (this at) (desc->method-type (vh-access-type-desc (vh-target-of this "accessModeType") (enum-name (nn at))))))
(define-override! "java/lang/invoke/VarHandle.isAccessModeSupported(Ljava/lang/invoke/VarHandle$AccessMode;)Z"
  (lambda (this m)
    (let ([mode (access-mode-name m)])
      (if (vh-supports? (vh-target-of this mode) mode) 1 0))))
;; an unsupported mode's handle throws when called, as the JDK's does
(define-override! "java/lang/invoke/VarHandle.toMethodHandle(Ljava/lang/invoke/VarHandle$AccessMode;)Ljava/lang/invoke/MethodHandle;"
  (lambda (this m)
    (let* ([mode (access-mode-name m)] [t (vh-target-of this mode)]
           [at (enum-name (jslot m (jfield-index "java/lang/invoke/VarHandle$AccessMode" "at")))]
           [d (vh-access-type-desc t at)])
      (make-mh d (lambda args (apply vh-call this mode d args)) #f))))

;; the field named fname in class c or a superclass: (declaring-class . meta)

;; A field by name and descriptor as JVMS 5.4.3.2 resolves one: the class's
;; own, then its superinterfaces', then its superclass's. (class name desc
;; flags ...) or #f.
(define (find-field c fname desc)
  (let loop ([c c])
    (and c
         (let ([m (find (lambda (f) (and (string=? (car f) fname) (string=? (cadr f) desc)))
                        (cadr (jclass-meta c)))])
           (cond [m (cons c m)]
                 [(exists (lambda (i) (loop (find-class i))) (jclass-interfaces c))]
                 [(jclass-super c) (loop (find-class (jclass-super c)))]
                 [else #f])))))

;; The field a Lookup or a field handle constant names, with the JDK's
;; failures (MemberName and its makeAccessException): NoSuchFieldException
;; caused by the VM's NoSuchFieldError when nothing has that name and type,
;; IllegalAccessException when it's static and wasn't asked to be or the
;; reverse, or when a setter is asked of a final field (final-checked?, a
;; Lookup's find*Setter, unless the Lookup is trusted). lookup is the Lookup asking, for the text's
;; "from", or #f. Access isn't checked.
(define (field-kind static? setter?) (if static? (if setter? "putStatic" "getStatic") (if setter? "putField" "getField")))
(define (mirror-name m) (jstring->scheme ((jvirt m "getName()Ljava/lang/String;") m)))
(define (lookup-from-text lookup)
  (cond [(not lookup) ""]
        [(eq? lookup (begin (jinit "java/lang/invoke/MethodHandles")
                            ((method-proc "java/lang/invoke/MethodHandles" "publicLookup()Ljava/lang/invoke/MethodHandles$Lookup;"))))
         ", from public Lookup"]
        [else
         (let* ([lk "java/lang/invoke/MethodHandles$Lookup"]
                [lc (jslot lookup (jfield-index lk "lookupClass"))]
                [plc (jslot lookup (jfield-index lk "prevLookupClass"))]
                [module-of (lambda (m) (jstring-of ((jvirt m "getModule()Ljava/lang/Module;") m)))])
           (string-append ", from " (jstring-of lc) " (" (module-of lc) ")"
                          (if plc (string-append ", previous lookup " (mirror-name plc) " (" (module-of plc) ")") "")))]))
(define (resolve-field c fname type static? setter? final-checked? lookup)
  (let* ([desc (mirror->desc type)] [hit (find-field c fname desc)]
         [member (lambda (cls kind) (string-append (slashes->dots (jclass-cname cls)) "." fname "/" (mirror-name type) "/" kind))]
         [access (lambda (what cls kind)
                   (jthrow "java/lang/IllegalAccessException" (string-append what ": " (member cls kind) (lookup-from-text lookup))))])
    (unless hit
      (let ([e (construct "java/lang/NoSuchFieldException" "(Ljava/lang/String;)V"
                          (jstring-lit (string-append "no such field: " (member c (field-kind static? setter?)))))])
        ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;")
         e (construct "java/lang/NoSuchFieldError" "(Ljava/lang/String;)V"
                      (jstring-lit (string-append "Class " (slashes->dots (jclass-cname c)) " does not have member field '"
                                                  (type-external desc) " " fname "'"))))
        (raise e)))
    (let ([found-static? (static-flag? (list-ref hit 3))])
      (unless (eq? found-static? static?)
        (access (if static? "expected a static field" "expected a non-static field") (car hit) (field-kind found-static? setter?)))
      ;; a TRUSTED lookup (allowedModes -1, the JDK's IMPL_LOOKUP) checks no access
      (when (and setter? final-checked? (fxpositive? (fxlogand (list-ref hit 3) #x10))
                 (not (eqv? -1 (jslot lookup (jfield-index "java/lang/invoke/MethodHandles$Lookup" "allowedModes")))))
        (access "unexpected set of a final field" (car hit) (field-kind static? setter?)))
      hit)))

(define (field-vh recv name type static? lookup)
  (let* ([c (mirror-class (nn recv))] [fname (jstring->scheme (nn name))]
         [hit (resolve-field c fname (nn type) static? #f #f lookup)])
    (let ([dc (car hit)] [desc (cadr (cdr hit))] [final? (fxpositive? (fxlogand (caddr (cdr hit)) #x10))])
      (if static?
          (make-vh 'static (string->symbol (string-append (jclass-cname dc) ":" fname)) (jclass-cname dc) desc
                   '() final?)
          (make-vh 'field (jfield-index (jclass-cname dc) fname) #f desc (list (cname->desc (jclass-cname c))) final?)))))

;; the same variable, with or without invoke-exact behavior
(define-override! "java/lang/invoke/VarHandle.withInvokeExactBehavior()Ljava/lang/invoke/VarHandle;"
  (lambda (this) (vh-of (vh-target-of this "withInvokeExactBehavior") #t)))
(define-override! "java/lang/invoke/VarHandle.withInvokeBehavior()Ljava/lang/invoke/VarHandle;"
  (lambda (this) (vh-of (vh-target-of this "withInvokeBehavior") #f)))

;; --- coordinate combinators ---------------------------------------------------------
;; Each returns a 'derived vh-target over the base: type and coords are the
;; new handle's, ops (and the byte[] view restriction) are the base's, as on
;; the JVM, and b is a procedure from the new coordinates to the base's plus
;; the value conversions. Checks run at construction, in the JDK's order and
;; with its messages (VarHandles.java), including printing coordinate i where
;; it means pos + i in the filter-type message.

(define (vh-derived base type coords adapter)
  (vh-of (make-vh-target 'derived base adapter type coords
                         (vh-target-ops base) (vh-target-plain-only? base) (make-vector 5 #f))
         #f))

(define (mh-type-text mh)
  (jstring-of (jslot (nn mh) (jfield-index "java/lang/invoke/MethodHandle" "type"))))
(define (vh-class-text d) (jstring-of (desc->mirror d)))
(define (vh-coords-text ds)
  (string-append "[" (fold-left (lambda (acc d) (if acc (string-append acc ", " (vh-class-text d)) (vh-class-text d))) #f ds) "]"))
(define (vh-iae . parts) (jthrow "java/lang/IllegalArgumentException" (apply string-append parts)))

;; the elements of a java.util.List, via its own size and get
(define (jlist->list l)
  (let ([size ((jvirt l "size()I") l)]
        [get (jvirt l "get(I)Ljava/lang/Object;")])
    (let loop ([i 0]) (if (fx= i size) '() (cons (get l i) (loop (fx+ i 1)))))))
(define (vh-bad-pos pos ds)
  (vh-iae "Invalid position " (number->string pos) " for coordinate types: " (vh-coords-text ds)))
(define (vh-check-pos pos ds) (when (or (fx< pos 0) (fx>= pos (length ds))) (vh-bad-pos pos ds)))

;; take, drop and splice over coordinate lists
(define (list-insert-at xs pos ys)
  (append (list-head xs pos) ys (list-tail xs pos)))
(define (list-remove-range xs pos k)
  (append (list-head xs pos) (list-tail xs (fx+ pos k))))

;; a value for a coordinate of descriptor d, checked the way the JDK inserts
;; one: a reference is cast (null passes), a primitive unboxes and may widen
(define (vh-insert-value d v)
  (if (prim-desc? d)
      (let ([p (begin (unless v (jthrow "java/lang/NullPointerException"
                                        "Cannot invoke \"Object.getClass()\" because \"x\" is null"))
                      (box-prim-of v))])
        ;; a widening box converts; the message names the wanted wrapper first
        (unless (and p (prim-convertible? p d))
          (jthrow "java/lang/ClassCastException"
                  (string-append "class " (slashes->dots (cadr (assoc d box-classes)))
                                 " is not compatible with class " (slashes->dots (class-name-of v)))))
        (widen p d (junbox p v)))
      (begin
        (when (and v (not (jinstance? v (desc->class-name d))))
          (jthrow "java/lang/ClassCastException"
                  (string-append "Cannot cast " (slashes->dots (class-name-of v)) " to " (slashes->dots (desc->class-name d)))))
        v)))

(define-override! "java/lang/invoke/MethodHandles.insertCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos values*)
    (let* ([t (vh-target-of (nn vh) "insertCoordinates")]
           [ds (vh-target-coords t)]
           [vs (vector->list (jarr-vec (nn values*)))])
      (vh-check-pos pos ds)
      (when (fx> (fx+ pos (length vs)) (length ds)) (vh-iae "Too many values"))
      (if (null? vs)
          vh
          (let ([converted (map vh-insert-value (list-head (list-tail ds pos) (length vs)) vs)])
            (vh-derived t (vh-target-type t) (list-remove-range ds pos (length vs))
                        (lambda (cs) (values (list-insert-at cs pos converted) #f #f))))))))

(define-override! "java/lang/invoke/MethodHandles.dropCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos types)
    (let* ([t (vh-target-of (nn vh) "dropCoordinates")]
           [ds (vh-target-coords t)]
           [extra (map mirror->desc (vector->list (jarr-vec (nn types))))])
      (when (or (fx< pos 0) (fx> pos (length ds))) (vh-bad-pos pos ds))
      (if (null? extra)
          vh
          (vh-derived t (vh-target-type t) (list-insert-at ds pos extra)
                      (lambda (cs) (values (list-remove-range cs pos (length extra)) #f #f)))))))

;; filter i converts new coordinate pos+i into the base's
(define-override! "java/lang/invoke/MethodHandles.filterCoordinates(Ljava/lang/invoke/VarHandle;I[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos filters)
    (let* ([t (vh-target-of (nn vh) "filterCoordinates")]
           [ds (vh-target-coords t)]
           [fs (vector->list (jarr-vec (nn filters)))])
      (vh-check-pos pos ds)
      (when (fx> (fx+ pos (length fs)) (length ds)) (vh-iae "Too many filters"))
      (if (null? fs)
          vh
          (let loop ([i 0] [coords ds] [procs '()])
            (if (fx= i (length fs))
                (let ([pv (list->vector (reverse procs))])
                  (vh-derived t (vh-target-type t) coords
                              (lambda (cs)
                                (values (let conv ([cs cs] [k 0])
                                          (cond [(null? cs) '()]
                                                [(and (fx>= k pos) (fx< k (fx+ pos (length fs))))
                                                 (cons ((vector-ref pv (fx- k pos)) (car cs)) (conv (cdr cs) (fx+ k 1)))]
                                                [else (cons (car cs) (conv (cdr cs) (fx+ k 1)))]))
                                        #f #f))))
                (let* ([f (nn (list-ref fs i))] [info (mh-info-of f)])
                  (unless (fx= (length (mh-info-args info)) 1)
                    (vh-iae "Invalid filter type " (mh-type-text f)))
                  (unless (string=? (list-ref coords (fx+ pos i)) (mh-info-ret info))
                    ;; the JDK prints coordinate i here, not pos + i
                    (vh-iae "Invalid filter type " (mh-type-text f) " for coordinate type " (vh-class-text (list-ref coords i))))
                  (loop (fx+ i 1)
                        (append (list-head coords (fx+ pos i)) (list (car (mh-info-args info))) (list-tail coords (fx+ pos i 1)))
                        (cons (mh-info-proc info) procs)))))))))

(define-override! "java/lang/invoke/MethodHandles.permuteCoordinates(Ljava/lang/invoke/VarHandle;Ljava/util/List;[I)Ljava/lang/invoke/VarHandle;"
  (lambda (vh new-list reorder*)
    (let* ([t (vh-target-of (nn vh) "permuteCoordinates")]
           [ds (vh-target-coords t)]
           [new (map mirror->desc (jlist->list (nn new-list)))]
           [reorder (vector->list (jarr-vec (nn reorder*)))]
           [mt (lambda (as) (method-type-text as "V"))])
      (unless (fx= (length reorder) (length ds))
        (vh-iae "old type parameter count and reorder array length do not match: " (mt ds)
                ", [" (fold-left (lambda (acc k) (if acc (string-append acc ", " (number->string k)) (number->string k))) #f reorder) "]"))
      (let loop ([i 0])
        (when (fx< i (length reorder))
          (let ([k (list-ref reorder i)])
            (when (or (fx< k 0) (fx>= k (length new)))
              (vh-iae "index is out of bounds for new type: " (number->string k) ", " (mt new)))
            (unless (string=? (list-ref ds i) (list-ref new k))
              (vh-iae "parameter types do not match after reorder: " (mt ds) ", " (mt new)))
            (loop (fx+ i 1)))))
      (vh-derived t (vh-target-type t) new
                  (lambda (cs) (values (map (lambda (k) (list-ref cs k)) reorder) #f #f))))))

(define-override! "java/lang/invoke/MethodHandles.collectCoordinates(Ljava/lang/invoke/VarHandle;ILjava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh pos filter)
    (let* ([t (vh-target-of (nn vh) "collectCoordinates")]
           [ds (vh-target-coords t)]
           [info (mh-info-of (nn filter))]
           [k (length (mh-info-args info))]
           [void? (string=? (mh-info-ret info) "V")])
      (vh-check-pos pos ds)
      (unless (or void? (string=? (mh-info-ret info) (list-ref ds pos)))
        (vh-iae "Invalid filter type " (mh-type-text filter) " for coordinate type " (vh-class-text (list-ref ds pos))))
      ;; a void filter runs for effect: the coordinate at pos stays, and the
      ;; filter's parameters go in front of it
      (vh-derived t (vh-target-type t)
                  (append (list-head ds pos) (mh-info-args info) (list-tail ds (if void? pos (fx+ pos 1))))
                  (lambda (cs)
                    (let ([r (apply (mh-info-proc info) (list-head (list-tail cs pos) k))])
                      (values (append (list-head cs pos)
                                      (if void? '() (list r))
                                      (list-tail cs (fx+ pos k)))
                              #f #f)))))))

;; to converts a new-type value into the base's type, from the other way;
;; extra leading filter parameters become trailing coordinates
(define-override! "java/lang/invoke/MethodHandles.filterValue(Ljava/lang/invoke/VarHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/VarHandle;"
  (lambda (vh to from)
    (let* ([t (vh-target-of (nn vh) "filterValue")]
           [ti (mh-info-of (nn to))] [fi (mh-info-of (nn from))]
           [tn (length (mh-info-args ti))] [fn (length (mh-info-args fi))]
           [last (lambda (xs) (list-ref xs (fx- (length xs) 1)))]
           [vt (vh-target-type t)]
           [both (lambda () (string-append (mh-type-text from) ", " (mh-type-text to)))])
      (cond
        [(not (fx= fn tn))
         (vh-iae "filterFromTarget and filterToTarget have different arity: " (both))]
        [(fx< fn 1) (vh-iae "filterFromTarget filter type has wrong arity: " (mh-type-text from))]
        [(or (not (string=? (last (mh-info-args fi)) (mh-info-ret ti)))
             (not (string=? (last (mh-info-args ti)) (mh-info-ret fi))))
         (vh-iae "filterFromTarget and filterToTarget filter types do not match: " (both))]
        [(not (string=? vt (last (mh-info-args fi))))
         (vh-iae "filterFromTarget filter type does not match target var handle type: "
                 (mh-type-text from) ", " (vh-class-text vt))]
        [(not (string=? vt (mh-info-ret ti)))
         (vh-iae "filterFromTarget filter type does not match target var handle type: "
                 (mh-type-text to) ", " (vh-class-text vt))]
        [else
         (let ([extra (list-head (mh-info-args fi) (fx- fn 1))])
           (for-each (lambda (a b) (unless (string=? a b)
                                     (vh-iae "filterFromTarget and filterToTarget filter types do not match: " (both))))
                     extra (list-head (mh-info-args ti) (fx- tn 1)))
           (let ([nds (length (vh-target-coords t))]
                 [top (mh-info-proc ti)] [fromp (mh-info-proc fi)])
             (vh-derived t (mh-info-ret fi) (append (vh-target-coords t) extra)
                         (lambda (cs)
                           (let ([base (list-head cs nds)] [ex (list-tail cs nds)])
                             (values base
                                     (lambda (v) (apply top (append ex (list v))))
                                     (lambda (r) (apply fromp (append ex (list r))))))))))]))))

(define-override! "java/lang/invoke/MethodHandles$Lookup.findVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #f this)))
(define-override! "java/lang/invoke/MethodHandles$Lookup.findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #t this)))

;; from a reflective Field, which names the declaring class
(define-override! "java/lang/invoke/MethodHandles$Lookup.unreflectVarHandle(Ljava/lang/reflect/Field;)Ljava/lang/invoke/VarHandle;"
  (lambda (this f)
    (let ([slot (lambda (n) (jslot (nn f) (jfield-index "java/lang/reflect/Field" n)))])
      (field-vh (slot "clazz") (slot "name") (slot "type") (static-flag? (slot "modifiers")) #f))))

(define (array-class-elem cls)
  (let ([n (jclass-cname (mirror-class (nn cls)))])
    (unless (char=? (string-ref n 0) #\[)
      (jthrow "java/lang/IllegalArgumentException" (string-append "not an array class: " n)))
    (substring n 1 (string-length n))))

(define-override! "java/lang/invoke/MethodHandles.arrayElementVarHandle(Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls) (make-vh 'array #f #f (array-class-elem cls) (list (mirror->desc cls) "I") #f)))
;; A memory segment's VarHandle is the JDK's own SegmentVarHandle (its form
;; and fields, so checkSegment and the alignment checks run as written) held
;; by a champaca handle of kind segment, whose accesses call the static named
;; after the mode in the form's implClass or its superclass, as HotSpot links
;; them through the VarForm: VarHandleSegmentAsInts.get(VarHandle, Object,
;; long) and the rest, which reach memory through ScopedMemoryAccess.
;; Coordinates are (MemorySegment long), plus a second long when the offset
;; isn't constant. A mode is supported when such a static exists: an
;; unaligned layout's form (VarHandleSegmentAsInts) has plain get and set
;; only, an aligned one's (VarHandleSegmentAsIntsAligned) every mode its
;; carrier allows.
(define segment-forms
  '(("B" . "Bytes") ("C" . "Chars") ("S" . "Shorts") ("I" . "Ints") ("J" . "Longs")
    ("F" . "Floats") ("D" . "Doubles") ("Z" . "Booleans")))

(define (segment-desc ty op ncoords)
  (string-append "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;" (if (fx= ncoords 2) "J" "JJ")
                 (case op [(get) ""] [(cas cax) (string-append ty ty)] [else ty])
                 ")" (case op [(set) "V"] [(cas) "Z"] [else ty])))

(define segment-op-modes
  '((get . "get") (set . "set") (cas . "compareAndSet") (cax . "compareAndExchange")
    (gas . "getAndSet") (add . "getAndAdd") (or . "getAndBitwiseOr")
    (and . "getAndBitwiseAnd") (xor . "getAndBitwiseXor")))

(define-override! "java/lang/invoke/VarHandles.memorySegmentViewHandle(Ljava/lang/Class;Ljava/lang/foreign/MemoryLayout;JZJLjava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (carrier enclosing mask constant? offset order)
    (let* ([d (mirror->desc (nn carrier))]
           [form (assoc d segment-forms)])
      (unless form
        (jthrow "java/lang/IllegalArgumentException"
                (string-append "Invalid carrier: " (jstring->scheme ((jvirt carrier "getName()Ljava/lang/String;") carrier)))))
      (let* ([fc (string-append "java/lang/invoke/VarHandleSegmentAs" (cdr form))]
             [be? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))]
             [_ (begin (jinit "java/lang/invoke/MethodHandleStatics") (jinit fc) (jinit "java/lang/invoke/SegmentVarHandle"))]
             [exact (top-level-value (string->symbol "java/lang/invoke/MethodHandleStatics:VAR_HANDLE_SEGMENT_FORCE_EXACT"))]
             [vform ((method-proc fc "selectForm(JZ)Ljava/lang/invoke/VarForm;") mask constant?)]
             [impl (find-class (jclass-cname (mirror-class (jslot vform (jfield-index "java/lang/invoke/VarForm" "implClass")))))]
             [coords (if (eqv? constant? 1)
                         '("Ljava/lang/foreign/MemorySegment;" "J")
                         '("Ljava/lang/foreign/MemorySegment;" "J" "J"))]
             [has? (lambda (op name) (and (find-method impl name (segment-desc d op (length coords))) #t))]
             [jvh (jnew "java/lang/invoke/SegmentVarHandle")])
        ((method-proc "java/lang/invoke/SegmentVarHandle"
                      "<init>(Ljava/lang/invoke/VarForm;ZLjava/lang/foreign/MemoryLayout;JZ)V")
         jvh vform (if be? 1 0) enclosing offset exact)
        (vh-of (make-vh-target 'segment jvh impl d coords
                               (map car (filter (lambda (e) (has? (car e) (cdr e))) segment-op-modes))
                               (not (has? 'get "getVolatile"))
                               (make-vector 5 #f))
               (eqv? exact 1))))))

;; the static for a mode, cached by the mode name's identity
(define segment-procs (make-weak-eq-hashtable))
(define (segment-access t op mode args)
  (let* ([procs (or (eq-hashtable-ref segment-procs t #f)
                    (let ([h (make-weak-eq-hashtable)]) (eq-hashtable-set! segment-procs t h) h))]
         [proc (or (eq-hashtable-ref procs mode #f)
                   (let* ([key (string-append mode (segment-desc (vh-target-type t) op (length (vh-target-coords t))))]
                          [m (find-method (vh-target-b t) mode (substring key (string-length mode) (string-length key)))]
                          [p (method-proc (jclass-cname (car m)) key)])
                     (eq-hashtable-set! procs mode p)
                     p))])
    (apply proc (vh-target-a t) args)))

(define-override! "java/lang/invoke/MethodHandles.byteArrayViewVarHandle(Ljava/lang/Class;Ljava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls order)
    (let ([e (array-class-elem cls)]
          [big? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))])
      (unless (member e '("S" "C" "I" "J" "F" "D"))
        (jthrow "java/lang/UnsupportedOperationException" (string-append "not a view type: " e)))
      (make-vh 'view (type-size e) big? e '("[B" "I") #f))))
