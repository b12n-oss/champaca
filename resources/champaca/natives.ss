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
  (if (jarr? this) (array-clone this)
      (make-jobj (jobj-cls this) (vector-copy (jobj-slots this)))))

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
;; and class-verification times). ThreadInfo, CPU times and deadlock
;; detection stay missing.
(define vm-start-ms (quotient (now-nanos) 1000000))
(define vm-start-mono (current-time 'time-monotonic))   ; uptime survives a clock step
(define (uptime-ms)
  (let ([d (time-difference (current-time 'time-monotonic) vm-start-mono)])
    (+ (* 1000 (time-second d)) (quotient (time-nanosecond d) 1000000))))
(let ()
  (define cls "sun/management/VMManagementImpl")
  (define (def name proc) (define-native! (string-append cls "." name) proc))
  (def "getVersion0()Ljava/lang/String;" (lambda () (jstring "4.0")))
  (def "initOptionalSupportFields()V" (lambda () (void)))
  (for-each (lambda (n) (def n (lambda (this) 0)))
            '("isThreadContentionMonitoringEnabled()Z" "isThreadCpuTimeEnabled()Z"
              "isThreadAllocatedMemoryEnabled()Z" "getVerboseClass()Z" "getVerboseGC()Z"
              "getUnloadedClassCount()J" "getTotalCompileTime()J" "getSafepointCount()J"
              "getTotalSafepointTime()J" "getSafepointSyncTime()J" "getTotalApplicationNonStoppedTime()J"
              "getLoadedClassSize()J" "getUnloadedClassSize()J" "getClassLoadingTime()J"
              "getMethodDataSize()J" "getClassInitializationTime()J" "getClassVerificationTime()J"))
  (def "getProcessId()I" (lambda (this) (libc-getpid)))
  (def "getVmArguments0()[Ljava/lang/String;" (lambda (this) (make-jarr "Ljava/lang/String;" (vector))))
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
  (make-jarr "Ljava/lang/Thread;" (list->vector (cons main-thread (reverse java-live-threads)))))
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

(defnative "java/lang/Runtime.availableProcessors()I" (this) 1)
;; Chez's heap: committed and allocated bytes; max is a nominal 4 GB, or
;; the heap once it's bigger
(defnative "java/lang/Runtime.maxMemory()J" (this) (max 4294967296 (current-memory-bytes)))
(defnative "java/lang/Runtime.totalMemory()J" (this) (current-memory-bytes))
(defnative "java/lang/Runtime.freeMemory()J" (this) (max 0 (- (current-memory-bytes) (bytes-allocated))))
(defnative "java/lang/Runtime.gc()V" (this) (collect))

;; --- java.lang.Class --------------------------------------------------------------

(defnative "java/lang/Class.getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;" (n)
  (jmirror-of (jstring->scheme n)))
(defnative "java/lang/Class.desiredAssertionStatus0(Ljava/lang/Class;)Z" (c) 0)
(defnative "java/lang/Class.isArray()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'array) 1 0))
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
(define (type-size t) (case t [("Z" "B") 1] [("C" "S") 2] [("I" "F") 4] [("J" "D") 8] [else 4]))
(define (elem-type a) (let ([e (jarr-elem a)]) (if (memv (string-ref e 0) '(#\L #\[)) "L" e)))

(define (byte-of a bpos)
  (let* ([se (type-size (elem-type a))]
         [e (vector-ref (jarr-vec a) (quotient bpos se))])
    (logand (ash (logand e (- (ash 1 (* 8 se)) 1)) (- (* 8 (remainder bpos se)))) #xff)))

(define (set-byte! a bpos b)
  (let* ([t (elem-type a)] [se (type-size t)] [i (quotient bpos se)] [sh (* 8 (remainder bpos se))]
         [mask (- (ash 1 (* 8 se)) 1)]
         [old (logand (vector-ref (jarr-vec a) i) mask)]
         [new (logior (logand old (lognot (ash #xff sh))) (ash b sh))])
    (vector-set! (jarr-vec a) i (signed-as t new))))

(define (signed-as t u)
  (case t
    [("B") (i2b u)] [("S") (i2s u)] [("C") (logand u #xffff)] [("Z") (logand u 1)]
    [("I") (i32 u)] [("J") (i64 u)] [else u]))

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
       (if (and (= se st) (zero? (remainder b se)) (or (string=? t et) (string=? et "L") (string=? t "L")))
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
       (if (and (= se st) (zero? (remainder b se)) (or (string=? t et) (string=? et "L") (string=? t "L")))
           (vector-set! (jarr-vec o) (quotient b se) v)
           (do ([k 0 (+ k 1)]) ((= k st))
             (set-byte! o (+ b k) (logand (ash v (* -8 k)) #xff)))))]))

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
  (if o (byte-of o (- off array-base)) (foreign-ref 'unsigned-8 off 0)))
(define (mem-byte-set! o off b)
  (if o (set-byte! o (- off array-base) b) (foreign-set! 'unsigned-8 off 0 b)))
(defnative "jdk/internal/misc/Unsafe.copyMemory0(Ljava/lang/Object;JLjava/lang/Object;JJ)V" (this src soff dst doff n)
  (cond
    [(and (jarr? src) (jarr? dst)) (copy-array-memory src soff dst doff n)]
    ;; native memory and a byte[], the shape every file and channel read and
    ;; write takes: one element per byte, no intermediate list
    [(and (not src) (jarr? dst) (string=? (elem-type dst) "B"))
     (let ([v (jarr-vec dst)] [d (- doff array-base)])
       (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! v (fx+ d i) (foreign-ref 'integer-8 soff i))))]
    [(and (jarr? src) (not dst) (string=? (elem-type src) "B"))
     (let ([v (jarr-vec src)] [s (- soff array-base)])
       (do ([i 0 (fx+ i 1)]) ((fx= i n)) (foreign-set! 'integer-8 doff i (vector-ref v (fx+ s i)))))]
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
    (if (and (string=? et (elem-type dst)) (zero? (remainder sb se)) (zero? (remainder db se)) (zero? (remainder n se)))
        (let* ([k (quotient n se)] [si (quotient sb se)] [di (quotient db se)]
               [tmp (let ([v (make-vector k)]) (do ([i 0 (fx+ i 1)]) ((fx= i k) v) (vector-set! v i (vector-ref (jarr-vec src) (fx+ si i)))))])
          (do ([i 0 (fx+ i 1)]) ((fx= i k))
            (vector-set! (jarr-vec dst) (fx+ di i) (vector-ref tmp i))))
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
(define-syntax field-offset?
  (syntax-rules () [(_ o off) (and (jobj? o) (fx< off static-offset-tag))]))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "get") (string-search "(Ljava/lang/Object;J)" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off)
                  (if (field-offset? o off) (vector-ref (jobj-slots o) off) (unsafe-get o off t))))))
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
  (props-array
   `(("java.home" . ,java-home-dir)
     ("java.vm.name" . "champaca")
     ("java.vm.vendor" . "champaca")
     ("java.vm.version" . "0.0-spike")
     ("java.vm.specification.name" . "Java Virtual Machine Specification")
     ("java.vm.specification.vendor" . "Oracle Corporation")
     ("java.vm.specification.version" . "25")
     ("java.class.path" . ,classpath-string)
     ("sun.boot.library.path" . "")
     ("java.library.path" . "")
     ("jdk.debug" . "release")
     ;; reflection through invoke0/newInstance0 (reflect.ss), never method handles
     ("jdk.reflect.useNativeAccessorOnly" . "true")
     ;; sun.misc.Unsafe's default "warn" finds its caller with StackWalker,
     ;; which has no natives here; "allow" skips the stderr warning and the walk
     ("sun.misc.unsafe.memory.access" . "allow"))))

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
    (set "_java_io_tmpdir_NDX" "/tmp")
    (set "_user_dir_NDX" (current-directory))
    (set "_user_home_NDX" (or (getenv "HOME") "/"))
    (set "_user_name_NDX" (or (getenv "USER") "champaca"))
    (set "_sun_cpu_endian_NDX" "little")
    (set "_sun_arch_data_model_NDX" "64")
    (set "_sun_io_unicode_encoding_NDX" "UnicodeLittle")
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
;; java.lang.Thread, the CHAMPACA_BT shadow stack, the scoped value cache.
;; They are saved when a thread gives the GIL up and restored when it takes
;; it back.

(define-record-type jthread-state
  (fields (mutable jthread) (mutable jstack) (mutable svc) (mutable permit) (mutable depth) (mutable tid))
  (sealed #t) (nongenerative champaca-jthread-state))
;; This Chez thread's jthread-state. A forked Chez thread inherits its parent's
;; value, so a thread jolt started (a future, say) would see its parent's
;; state: gil-enter! checks the thread id before trusting it.
(define self (make-thread-parameter #f))
(define main-state (make-jthread-state #f '() #f #f 1 (get-thread-id)))
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

(define (gil-save! s)
  (jthread-state-jstack-set! s jstack)
  (jthread-state-svc-set! s scoped-value-cache))
(define (gil-restore! s)
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
         [s (if known s0 (let ([s (make-jthread-state #f '() #f #f 0 (get-thread-id))]) (self s) s))])
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

;; Let a thread that is ready run first, then queue for the GIL again.
(define (gil-yield!)
  (let ([n gil-takes])
    (gil-block! (lambda () (or (not (fx= gil-takes n)) (fx= gil-ready 0))) #f)))

;; Generated code checks gil-ready inline at each backward branch and calls
;; this only when some thread is waiting for the GIL.
(define safepoint-count 0)
(define (jsafepoint)
  (set! safepoint-count (fx+ safepoint-count 1))
  (when (fx>= safepoint-count 1000)
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
      (lambda (e)
        (k (cond
             [(java-halt? e) (exit-process (java-halt-status e))]
             [(jobj? e)
              (guard (e2 [#t (report-uncaught e2)])
                ((method-proc "java/lang/Thread" "dispatchUncaughtException(Ljava/lang/Throwable;)V") t e))]
             [else (report-uncaught e)])))
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
  (let ([s (make-jthread-state this '() #f #f 0 #f)])
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
    (thread-alive! t #t)))

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
    (thread-alive! main-thread #t)))

;; --- references: no finalization, no reference-processing thread ----------------
(defnative "java/lang/ref/Finalizer.isFinalizationEnabled()Z" () 0)
(defnative "java/lang/ref/Reference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (jslot this (jfield-index "java/lang/ref/Reference" "referent")) o) 1 0))
(defnative "java/lang/ref/Reference.clear0()V" (this)
  (jslot-set! this (jfield-index "java/lang/ref/Reference" "referent") #f))
(defnative "java/lang/ref/PhantomReference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (jslot this (jfield-index "java/lang/ref/Reference" "referent")) o) 1 0))
(defnative "java/lang/ref/PhantomReference.clear0()V" (this)
  (jslot-set! this (jfield-index "java/lang/ref/Reference" "referent") #f))
;; Nothing is ever pending (references are never cleared by GC here), so the
;; Reference Handler thread waits here for good rather than spinning.
(defnative "java/lang/ref/Reference.waitForReferencePendingList()V" () (gil-block! (lambda () #f) #f))
(defnative "java/lang/ref/Reference.getAndClearReferencePendingList()Ljava/lang/ref/Reference;" () #f)
(defnative "java/lang/ref/Reference.hasReferencePendingList()Z" () 0)

;; --- overrides of Java-coded methods ----------------------------------------------

;; (none yet: Class.getEnumConstantsShared used to be one, before reflect.ss)

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
    (and l (top-level-bound? s) (eq? l (top-level-value s)))))
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
;; (no version information, sealed), as BootLoader.definePackage builds it. A
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
          ;; package asked for before its module is defined (the hook says
          ;; jdk only for a package the runtime image owns) isn't kept, so a
          ;; later call gets the sealed one
          (if (and (string=? (car info) "jdk") (not module))
              p
              (or (hashtable-ref jpackage-table pn #f)
                  (begin (hashtable-set! jpackage-table pn p) p)))))))
(define-override! "java/lang/Class.getPackage()Ljava/lang/Package;"
  (lambda (this)
    (let ([cn (jclass-cname (mirror-class this))])
      (and (not (prim-name? cn)) (not (char=? (string-ref cn 0) #\[))
           (jpackage-of cn)))))

(defnative "jdk/internal/loader/BootLoader.setBootLoaderUnnamedModule0(Ljava/lang/Module;)V" (m) (set-unnamed-module! m))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageNames()[Ljava/lang/String;" ()
  (make-jarr "Ljava/lang/String;" (vector)))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageLocation(Ljava/lang/String;)Ljava/lang/String;" (n) #f)
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
;; link and register as a class of this run, never cached. The bytes cross
;; as a Latin-1 string, since a byte array doesn't. The defining loader is
;; kept (class-loaders, for getClassLoader and findLoadedClass) and a hidden
;; class's name marked (hidden-classes).
(define define-class-hook #f)
(define (set-define-class-hook! f) (set! define-class-hook f))
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
                     [(jobj? e) (hashtable-set! resolution-errors name e) #f])
             ((jvirt l "loadClass(Ljava/lang/String;)Ljava/lang/Class;") l (jstring (slashes->dots name)))
             (and (hashtable-ref classes name #f) #t))))))
(define (define-class! loader given v off len hidden? classdata pd)
  (unless (and (fx>= off 0) (fx>= len 0) (fx<= (fx+ off len) (vector-length v)))
    (jthrow "java/lang/ArrayIndexOutOfBoundsException"
            (format "Array region ~a..~a out of bounds for length ~a" off (+ off len) (vector-length v))))
  (let* ([name (and given (slashes (jstring->scheme given)))]
         ;; what fails past parsing (a superclass that isn't there, a link
         ;; error) is a NoClassDefFoundError naming the cause, as a class
         ;; the JVM can't define is a LinkageError
         [r (guard (e [(not (jobj? e)) (jthrow "java/lang/NoClassDefFoundError" (condition-text e))])
              (parameterize ([resolving-loader loader])
                (define-class-hook name (bytes->latin1 v off len) hidden?)))]
         [sp (let loop ([i 0]) (if (char=? (string-ref r i) #\space) i (loop (fx+ i 1))))]
         [kind (substring r 0 sp)]
         [arg (substring r (fx+ sp 1) (string-length r))])
    (cond [(string=? kind "bad") (jthrow "java/lang/ClassFormatError" arg)]
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
  ;; flags: 1 nestmate (the lookup class's nest, not kept here), 2 hidden, 4 strong, 8 vm annotations
  (let ([m (define-class! loader name (jarr-vec (nn b)) off len (fxpositive? (fxlogand flags 2)) classdata pd)])
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
      [(cas) (if (eqv? (get) (car vals)) (begin (set (cadr vals)) 1) 0)]
      [(cax) (let ([old (get)]) (when (eqv? old (car vals)) (set (cadr vals))) old)]
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
(define (find-field c fname static?)
  (let loop ([c c])
    (and c
         (let ([m (find (lambda (f) (and (string=? (car f) fname)
                                         (eq? static? (static-flag? (caddr f)))))
                        (cadr (jclass-meta c)))])
           (if m (cons c m) (and (jclass-super c) (loop (find-class (jclass-super c)))))))))

(define (field-vh recv name type static?)
  (let* ([c (mirror-class (nn recv))] [fname (jstring->scheme (nn name))]
         [hit (find-field c fname static?)])
    (unless hit
      ;; the JDK's MemberName text: "no such field: pkg.Cls.name/type/getField"
      (let ([dots (lambda (n) (list->string (map (lambda (ch) (if (char=? ch #\/) #\. ch)) (string->list n))))])
        (jthrow "java/lang/NoSuchFieldException"
                (string-append "no such field: " (dots (jclass-cname c)) "." fname "/"
                               (dots (jclass-cname (mirror-class (nn type))))
                               (if static? "/getStatic" "/getField")))))
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
  (lambda (this recv name type) (field-vh recv name type #f)))
(define-override! "java/lang/invoke/MethodHandles$Lookup.findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #t)))

;; from a reflective Field, which names the declaring class
(define-override! "java/lang/invoke/MethodHandles$Lookup.unreflectVarHandle(Ljava/lang/reflect/Field;)Ljava/lang/invoke/VarHandle;"
  (lambda (this f)
    (let ([slot (lambda (n) (jslot (nn f) (jfield-index "java/lang/reflect/Field" n)))])
      (field-vh (slot "clazz") (slot "name") (slot "type") (static-flag? (slot "modifiers"))))))

(define (array-class-elem cls)
  (let ([n (jclass-cname (mirror-class (nn cls)))])
    (unless (char=? (string-ref n 0) #\[)
      (jthrow "java/lang/IllegalArgumentException" (string-append "not an array class: " n)))
    (substring n 1 (string-length n))))

(define-override! "java/lang/invoke/MethodHandles.arrayElementVarHandle(Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls) (make-vh 'array #f #f (array-class-elem cls) (list (mirror->desc cls) "I") #f)))
(define-override! "java/lang/invoke/MethodHandles.byteArrayViewVarHandle(Ljava/lang/Class;Ljava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls order)
    (let ([e (array-class-elem cls)]
          [big? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))])
      (unless (member e '("S" "C" "I" "J" "F" "D"))
        (jthrow "java/lang/UnsupportedOperationException" (string-append "not a view type: " e)))
      (make-vh 'view (type-size e) big? e '("[B" "I") #f))))
