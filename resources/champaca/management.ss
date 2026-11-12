;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; java.management's natives: the Runtime, Memory, OS, ClassLoading and Thread
;; beans, the memory pool and collector, GC records and notifications. Split
;; from natives.ss, whose defnative and helpers these use; loaded after it.

;; --- java.management ---------------------------------------------------------------------
;; Enough of sun.management for the Runtime, Memory, OperatingSystem and
;; ClassLoading beans and ThreadMXBean's counts: the VM's counters, from what
;; the runtime keeps, and zeros for the HotSpot-only ones (safepoints, JIT
;; and class-verification times), and the threads' CPU times, ThreadInfo
;; and deadlock detection (Thread section).
(define vm-start-ms (quotient (now-nanos) 1000000))
(define vm-start-mono (current-time 'time-monotonic))   ; uptime survives a clock step
(define (uptime-ms)
  (let ([d (time-difference (current-time 'time-monotonic) vm-start-mono)])
    (+ (* 1000 (time-second d)) (quotient (time-nanosecond d) 1000000))))
;; nanoseconds the JIT has spent translating and compiling methods
;; (jit.clj compile-method!, its outermost calls), the CompilationMXBean's
;; total compilation time; loading a method from the cache doesn't count
(define compile-time-ns 0)
(define (note-compile-time! ns) (set! compile-time-ns (+ compile-time-ns ns)))
(let ()
  (define cls "sun/management/VMManagementImpl")
  (define (def name proc) (define-native! (string-append cls "." name) proc))
  (def "getVersion0()Ljava/lang/String;" (lambda () (jstring "4.0")))
  ;; the CPU times of the current thread and of others are measured,
  ;; contention, monitor and synchronizer usage reported (Thread section),
  ;; and the JIT's compilation time;
  ;; the other optional supports stay off
  (def "initOptionalSupportFields()V"
       (lambda ()
         (for-each (lambda (f) (set-top-level-value! (string->symbol (string-append "sun/management/VMManagementImpl:" f)) 1))
                   '("currentThreadCpuTimeSupport" "otherThreadCpuTimeSupport" "threadContentionMonitoringSupport"
                     "objectMonitorUsageSupport" "synchronizerUsageSupport" "compTimeMonitoringSupport"))))
  (def "getTotalCompileTime()J" (lambda (this) (quotient compile-time-ns 1000000)))
  (def "isThreadCpuTimeEnabled()Z" (lambda (this) thread-cpu-enabled))
  (def "isThreadContentionMonitoringEnabled()Z" (lambda (this) contention-enabled))
  (for-each (lambda (n) (def n (lambda (this) 0)))
            '("isThreadAllocatedMemoryEnabled()Z" "getVerboseClass()Z" "getVerboseGC()Z"
              "getUnloadedClassCount()J" "getSafepointCount()J"
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
  ;; main, and DestroyJavaVM once it's attached, besides the started ones
  (def "getTotalThreadCount()J" (lambda (this) (fx+ (if destroy-jthread 2 1) threads-started)))
  (def "getLiveThreadCount()I" (lambda (this) (length (live-jthreads))))
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
;; One memory pool and one collector, as Chez has one heap and one
;; collector: the pool "Chez heap" (heap type) with Chez's allocated and
;; committed bytes, its peak the most memory Chez has held, no usage or
;; collection thresholds; the collector "Chez" managing it, jdk.management's
;; GarbageCollectorExtImpl as on HotSpot (java.management's own when that
;; module isn't in the layer), counting Chez's collections and the real
;; time they took. It gives no GcInfo and no notifications. Made once, as
;; MemoryImpl asks once.
(define memory-beans #f)   ; (pool . collector)
(define (memory-beans!)
  (or memory-beans
      (let* ([pool (begin (jinit "sun/management/MemoryPoolImpl") (jnew "sun/management/MemoryPoolImpl"))]
             [ext? (guard (e [#t #f]) (find-class "com/sun/management/internal/GarbageCollectorExtImpl") #t)]
             [gc-class (if ext? "com/sun/management/internal/GarbageCollectorExtImpl" "sun/management/GarbageCollectorImpl")]
             [gc (begin (jinit gc-class) (jnew gc-class))])
        ((method-proc "sun/management/MemoryPoolImpl" "<init>(Ljava/lang/String;ZJJ)V") pool (jstring "Chez heap") 1 -1 -1)
        ((method-proc gc-class "<init>(Ljava/lang/String;)V") gc (jstring "Chez"))
        (set! memory-beans (cons pool gc))
        memory-beans)))
(defnative "sun/management/MemoryImpl.getMemoryPools0()[Ljava/lang/management/MemoryPoolMXBean;" ()
  (make-jarr "Ljava/lang/management/MemoryPoolMXBean;" (vector (car (memory-beans!)))))
(defnative "sun/management/MemoryImpl.getMemoryManagers0()[Ljava/lang/management/MemoryManagerMXBean;" ()
  (make-jarr "Ljava/lang/management/MemoryManagerMXBean;" (vector (cdr (memory-beans!)))))
(defnative "sun/management/MemoryPoolImpl.getMemoryManagers0()[Ljava/lang/management/MemoryManagerMXBean;" (this)
  (make-jarr "Ljava/lang/management/MemoryManagerMXBean;" (vector (cdr (memory-beans!)))))
(defnative "sun/management/MemoryManagerImpl.getMemoryPools0()[Ljava/lang/management/MemoryPoolMXBean;" (this)
  (make-jarr "Ljava/lang/management/MemoryPoolMXBean;" (vector (car (memory-beans!)))))
(define (heap-usage)
  (let ([used (bytes-allocated)]) (memory-usage -1 used (max used (current-memory-bytes)) -1)))
(defnative "sun/management/MemoryPoolImpl.getUsage0()Ljava/lang/management/MemoryUsage;" (this) (heap-usage))
(defnative "sun/management/MemoryPoolImpl.getPeakUsage0()Ljava/lang/management/MemoryUsage;" (this)
  (let ([peak (max (maximum-memory-bytes) (current-memory-bytes))]) (memory-usage -1 peak peak -1)))
;; null, which the bean answers when the pool doesn't give it
(defnative "sun/management/MemoryPoolImpl.getCollectionUsage0()Ljava/lang/management/MemoryUsage;" (this) #f)
(defnative "sun/management/MemoryPoolImpl.resetPeakUsage0()V" (this) (reset-maximum-memory-bytes!))
(defnative "sun/management/MemoryPoolImpl.setPoolUsageSensor(Lsun/management/Sensor;)V" (this s) (void))
(defnative "sun/management/MemoryPoolImpl.setPoolCollectionSensor(Lsun/management/Sensor;)V" (this s) (void))
(defnative "sun/management/GarbageCollectorImpl.getCollectionCount()J" (this) (collections))
(defnative "sun/management/GarbageCollectorImpl.getCollectionTime()J" (this)
  (let ([t (sstats-gc-real (statistics))])
    (+ (* 1000 (time-second t)) (quotient (time-nanosecond t) 1000000))))
;; Each collection is noted as it ends, with the world stopped and no Java
;; code run: its number, start and end (milliseconds of uptime, as GcInfo
;; has them), the heap's used and committed bytes before and after, and its
;; action and cause as HotSpot names them. A collection Chez asks for is a
;; minor one for an allocation; System.gc() is a major one, and so is the
;; collection a thread dump makes to find locks. jolt's collect-request
;; handler gets wrapped once, at load; a full collection with no other
;; thread running doesn't go through it, so the caller notes that one.
(define-record-type gc-record
  (fields index start end used-before committed-before used-after committed-after action cause)
  (sealed #t) (nongenerative champaca-gc-record))
(define gc-index 0)
(define gc-last #f)
(define gc-pending '())      ; newest first, while notifications are on
(define gc-notifying #f)     ; the Notification Thread, once a listener asked
;; (action . cause) of the explicit collection under way, a global and not a
;; thread parameter: with other threads alive, jolt's full collection meets
;; them at a rendezvous and the collect-request handler can run on any of them
(define gc-cause #f)
(define (note-gc! collect)
  (let ([n gc-index] [t0 (uptime-ms)] [ub (bytes-allocated)] [cb (current-memory-bytes)]
        [why (or gc-cause '("end of minor GC" . "Allocation Failure"))])
    (collect)
    (when (fx= n gc-index)   ; not noted already by the handler within
      (set! gc-index (fx+ gc-index 1))
      (set! gc-last (make-gc-record gc-index t0 (uptime-ms) ub cb (bytes-allocated) (current-memory-bytes) (car why) (cdr why)))
      (when gc-notifying
        (set! gc-pending (if (fx< (length gc-pending) 64) (cons gc-last gc-pending) (list gc-last)))
        ;; wakes the Notification Thread once the world runs again; no
        ;; mutex is taken inside a collection
        (condition-broadcast gil-cv)))))
(let ([orig (collect-request-handler)])
  (collect-request-handler (lambda () (note-gc! orig))))
(define (explicit-collect! action cause)
  (dynamic-wind (lambda () (set! gc-cause (cons action cause)))
                (lambda () (note-gc! full-collect))
                (lambda () (set! gc-cause #f)))
  (gil-poke!))

;; GcInfo as the VM builds it, through its private constructor, the one pool's
;; usage before and after, and no extension attributes
(define (gc-info builder r)
  (let ([info (begin (jinit "com/sun/management/GcInfo") (jnew "com/sun/management/GcInfo"))]
        [usages (lambda (used committed) (make-jarr "Ljava/lang/management/MemoryUsage;" (vector (memory-usage -1 used (max used committed) -1))))])
    ((method-proc "com/sun/management/GcInfo"
                  "<init>(Lcom/sun/management/internal/GcInfoBuilder;JJJ[Ljava/lang/management/MemoryUsage;[Ljava/lang/management/MemoryUsage;[Ljava/lang/Object;)V")
     info builder (gc-record-index r) (gc-record-start r) (gc-record-end r)
     (usages (gc-record-used-before r) (gc-record-committed-before r))
     (usages (gc-record-used-after r) (gc-record-committed-after r))
     (make-jarr "Ljava/lang/Object;" (vector)))
    info))
(define gc-ext "com/sun/management/internal/GarbageCollectorExtImpl")
(define (gc-info-builder gc) ((method-proc gc-ext "getGcInfoBuilder()Lcom/sun/management/internal/GcInfoBuilder;") gc))
;; The JDK's own GC notification for each noted collection, sent on a
;; daemon "Notification Thread" in the system group, as HotSpot sends it,
;; started when the first listener is added; it waits for collections
;; without the GIL.
(define (deliver-gc! gc r)
  (guard (e [(java-halt? e) (raise e)] [#t (void)])   ; a listener's exception is dropped, as HotSpot's thread drops it
    ((method-proc gc-ext "createGCNotification(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/sun/management/GcInfo;)V")
     gc (quotient (now-nanos) 1000000) (jstring "Chez") (jstring (gc-record-action r)) (jstring (gc-record-cause r))
     (gc-info (gc-info-builder gc) r))))
(define (start-gc-notifier! gc)
  (let* ([run (lambda ()
                (let loop ()
                  (with-thread-status (status-parked #f) #f
                    (lambda () (gil-block! (lambda () (pair? gc-pending)) #f)))
                  (let ([rs (reverse gc-pending)])
                    (set! gc-pending '())
                    (for-each (lambda (r) (deliver-gc! gc r)) rs))
                  (loop)))]
         [t (begin (jinit "java/lang/Thread") (jnew "java/lang/Thread"))]
         [main-group ((method-proc "java/lang/Thread" "getThreadGroup()Ljava/lang/ThreadGroup;") main-thread)]
         [system-group (or ((method-proc "java/lang/ThreadGroup" "getParent()Ljava/lang/ThreadGroup;") main-group) main-group)])
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;)V")
     t system-group #f (jstring "Notification Thread"))
    (eq-hashtable-set! scheme-thread-bodies t run)
    ((method-proc "java/lang/Thread" "setDaemon(Z)V") t 1)
    ((jvirt t "start()V") t)
    t))
(defnative "com/sun/management/internal/GarbageCollectorExtImpl.setNotificationEnabled(Lcom/sun/management/GarbageCollectorMXBean;Z)V" (this gc on)
  (when (and (eqv? on 1) (not gc-notifying))
    (set! gc-notifying #t)
    (set! gc-notifying (start-gc-notifier! gc))))
(defnative "com/sun/management/internal/GcInfoBuilder.getNumGcExtAttributes(Ljava/lang/management/GarbageCollectorMXBean;)I" (this gc) 0)
(defnative "com/sun/management/internal/GcInfoBuilder.fillGcAttributeInfo(Ljava/lang/management/GarbageCollectorMXBean;I[Ljava/lang/String;[C[Ljava/lang/String;)V" (this gc n names types descs) (void))
;; the last collection's GcInfo, null before the first
(defnative "com/sun/management/internal/GcInfoBuilder.getLastGcInfo0(Ljava/lang/management/GarbageCollectorMXBean;I[Ljava/lang/Object;[C[Ljava/lang/management/MemoryUsage;[Ljava/lang/management/MemoryUsage;)Lcom/sun/management/GcInfo;" (this gc n values types before after)
  (and gc-last (gc-info this gc-last)))
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
