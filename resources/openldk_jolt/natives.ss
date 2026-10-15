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
(defnative "java/lang/Object.notify()V" (this) (void))
(defnative "java/lang/Object.notifyAll()V" (this) (void))
(defnative "java/lang/Object.wait0(J)V" (this ms) (void))

;; --- java.lang.System ------------------------------------------------------------

(define (now-nanos) (let ([t (current-time 'time-utc)]) (+ (* (time-second t) 1000000000) (time-nanosecond t))))
(defnative "java/lang/System.currentTimeMillis()J" () (quotient (now-nanos) 1000000))
(defnative "java/lang/System.nanoTime()J" ()
  (let ([t (current-time 'time-monotonic)]) (+ (* (time-second t) 1000000000) (time-nanosecond t))))
(defnative "java/lang/System.identityHashCode(Ljava/lang/Object;)I" (x) (identity-hash x))
(defnative "java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V" (src sp dst dp n)
  (let ([sv (jarr-vec (nn src))] [dv (jarr-vec (nn dst))])
    (when (or (fx< sp 0) (fx< dp 0) (fx< n 0) (fx> (fx+ sp n) (vector-length sv)) (fx> (fx+ dp n) (vector-length dv)))
      (jthrow "java/lang/ArrayIndexOutOfBoundsException" "arraycopy: last source index out of bounds"))
    (if (and (eq? sv dv) (fx< sp dp))
        (do ([i (fx- n 1) (fx- i 1)]) ((fx< i 0)) (vector-set! dv (fx+ dp i) (vector-ref sv (fx+ sp i))))
        (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! dv (fx+ dp i) (vector-ref sv (fx+ sp i)))))))
(defnative "java/lang/System.setIn0(Ljava/io/InputStream;)V" (s) (set-top-level-value! '|java/lang/System:in| s))
(defnative "java/lang/System.setOut0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:out| s))
(defnative "java/lang/System.setErr0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:err| s))

(defnative "java/lang/Runtime.availableProcessors()I" (this) 1)
(defnative "java/lang/Runtime.maxMemory()J" (this) 4294967296)
(defnative "java/lang/Runtime.totalMemory()J" (this) 1073741824)
(defnative "java/lang/Runtime.freeMemory()J" (this) 536870912)
(defnative "java/lang/Runtime.gc()V" (this) (collect))

;; --- java.lang.Class --------------------------------------------------------------

(defnative "java/lang/Class.getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;" (n)
  (jmirror-of (jstring->scheme n)))
(defnative "java/lang/Class.desiredAssertionStatus0(Ljava/lang/Class;)Z" (c) 0)
(defnative "java/lang/Class.isArray()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'array) 1 0))
(defnative "java/lang/Class.isPrimitive()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'prim) 1 0))
(defnative "java/lang/Class.isInterface()Z" (this) (if (eq? (jclass-kind (mirror-class this)) 'interface) 1 0))
(defnative "java/lang/Class.isHidden()Z" (this) 0)
(defnative "java/lang/Class.getModifiers()I" (this) (class-modifiers (mirror-class this)))
(defnative "java/lang/Class.getSuperclass()Ljava/lang/Class;" (this)
  (let ([c (mirror-class this)])
    (and (jclass-super c) (not (eq? (jclass-kind c) 'interface)) (jmirror-of (jclass-super c)))))
(defnative "java/lang/Class.initClassName()Ljava/lang/String;" (this)
  (let* ([n (jclass-name (mirror-class this))]
         [s (jstring (list->string (map (lambda (c) (if (char=? c #\/) #\. c)) (string->list n))))])
    (jslot-set! this (jfield-index "java/lang/Class" "name") s)
    s))
(defnative "java/lang/Class.isInstance(Ljava/lang/Object;)Z" (this x)
  (if (jinstance? x (jclass-name (mirror-class this))) 1 0))
(defnative "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z" (this other)
  (if (class-assignable? (jclass-name (mirror-class other)) (jclass-name (mirror-class this))) 1 0))
(defnative "java/lang/Class.forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;"
  (name init loader caller)
  (let* ([n (list->string (map (lambda (c) (if (char=? c #\.) #\/ c)) (string->list (jstring->scheme name))))]
         [c (guard (e [(and (condition? e) (not (jobj? e))) #f]) (find-class n))])
    (unless c (jthrow "java/lang/ClassNotFoundException" (jstring->scheme name)))
    (when (eqv? init 1) (jinit n))
    (jmirror c)))

;; --- strings and numbers -------------------------------------------------------

(defnative "java/lang/String.intern()Ljava/lang/String;" (this)
  (let ([s (jstring->scheme this)])
    (or (hashtable-ref interned s #f) (begin (hashtable-set! interned s this) this))))
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

(defnative "java/lang/Throwable.fillInStackTrace(I)Ljava/lang/Throwable;" (this dummy)
  (eq-hashtable-set! throw-stacks this jstack)
  this)

;; --- Unsafe -------------------------------------------------------------------------
;; An object field offset is its slot index; an array offset is the element
;; index (base 0, scale 1). Static fields are not addressable yet.

;; Off-heap words that Unsafe reaches with a null base and an absolute
;; address (Thread's next-id counter, for one). Addresses are table keys.
(define native-memory (make-eqv-hashtable))
(define native-next 4096)
(define (native-alloc! init)
  (let ([a native-next]) (set! native-next (+ native-next 8)) (hashtable-set! native-memory a init) a))

(define unsafe-prefix "jdk/internal/misc/Unsafe.")
(define (unsafe-vec o) (if (jarr? o) (jarr-vec o) (jobj-slots (nn o))))
(define (unsafe-get o off) (if o (vector-ref (unsafe-vec o) off) (hashtable-ref native-memory off 0)))
(define (unsafe-set! o off v) (if o (vector-set! (unsafe-vec o) off v) (hashtable-set! native-memory off v)))
(defnative "jdk/internal/misc/Unsafe.arrayBaseOffset0(Ljava/lang/Class;)I" (this c) 0)
(defnative "jdk/internal/misc/Unsafe.arrayIndexScale0(Ljava/lang/Class;)I" (this c) 1)
(defnative "jdk/internal/misc/Unsafe.addressSize0()I" (this) 8)
(defnative "jdk/internal/misc/Unsafe.pageSize()I" (this) 4096)
(defnative "jdk/internal/misc/Unsafe.fullFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.loadFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.storeFence()V" (this) (void))
(defnative "jdk/internal/misc/Unsafe.objectFieldOffset1(Ljava/lang/Class;Ljava/lang/String;)J" (this c name)
  (jfield-index (jclass-name (mirror-class c)) (jstring->scheme name)))
(defnative "jdk/internal/misc/Unsafe.ensureClassInitialized0(Ljava/lang/Class;)V" (this c)
  (jinit (jclass-name (mirror-class c))))
(defnative "jdk/internal/misc/Unsafe.shouldBeInitialized0(Ljava/lang/Class;)Z" (this c)
  (if (eq? (jclass-state (mirror-class c)) 'loaded) 1 0))
(defnative "jdk/internal/misc/Unsafe.allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;" (this c)
  (jnew (jclass-name (mirror-class c))))

(define (starts? s p) (and (fx>= (string-length s) (string-length p)) (string=? p (substring s 0 (string-length p)))))
(define (method-part n) (substring n (string-length unsafe-prefix) (string-length n)))

;; get<T>[Volatile](Object, long) / put<T>[Volatile](Object, long, T)
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "get") (string-search "(Ljava/lang/Object;J)" n)))
  (lambda (n) (lambda (this o off) (unsafe-get o off))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "put") (string-search "(Ljava/lang/Object;J" n)))
  (lambda (n) (lambda (this o off v) (unsafe-set! o off v))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndSet")))
  (lambda (n) (lambda (this o off expect new)
                (if (eqv? (unsafe-get o off) expect) (begin (unsafe-set! o off new) 1) 0))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndExchange")))
  (lambda (n) (lambda (this o off expect new)
                (let ([old (unsafe-get o off)])
                  (when (eqv? old expect) (unsafe-set! o off new))
                  old))))

(define (string-search needle hay)
  (let ([n (string-length needle)] [h (string-length hay)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i n) h) #f]
            [(string=? needle (substring hay i (fx+ i n))) i]
            [else (loop (fx+ i 1))]))))

;; --- VM and CDS ---------------------------------------------------------------

(defnative "jdk/internal/misc/VM.initialize()V" () (void))
(defnative "jdk/internal/misc/VM.latestUserDefinedLoader0()Ljava/lang/ClassLoader;" () #f)
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
   `(("java.home" . ,(or (getenv "JAVA_HOME") "/"))
     ("java.vm.name" . "openldk-jolt")
     ("java.vm.vendor" . "openldk-jolt")
     ("java.vm.version" . "0.0-spike")
     ("java.vm.specification.name" . "Java Virtual Machine Specification")
     ("java.vm.specification.vendor" . "Oracle Corporation")
     ("java.vm.specification.version" . "25")
     ("java.class.path" . "")
     ("sun.boot.library.path" . "")
     ("java.library.path" . "")
     ("jdk.debug" . "release")
     ;; reflection through invoke0/newInstance0 (reflect.ss), never method handles
     ("jdk.reflect.useNativeAccessorOnly" . "true"))))

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
    (set "_os_name_NDX" "Mac OS X")
    (set "_os_arch_NDX" "aarch64")
    (set "_os_version_NDX" "")
    (set "_java_io_tmpdir_NDX" "/tmp")
    (set "_user_dir_NDX" (current-directory))
    (set "_user_home_NDX" (or (getenv "HOME") "/"))
    (set "_user_name_NDX" (or (getenv "USER") "openldk"))
    (set "_sun_cpu_endian_NDX" "little")
    (set "_sun_arch_data_model_NDX" "64")
    (set "_sun_io_unicode_encoding_NDX" "UnicodeLittle")
    (make-jarr "Ljava/lang/String;" v)))

;; --- file descriptors and output ---------------------------------------------------

(defnative "java/io/FileDescriptor.getHandle(I)J" (fd) -1)
(defnative "java/io/FileDescriptor.getAppend(I)Z" (fd) 0)

(define stdout-bin (standard-output-port))
(define stderr-bin (standard-error-port))

(defnative "java/io/FileOutputStream.writeBytes([BIIZ)V" (this bytes off len append)
  (let* ([fdo (jslot this (jfield-index "java/io/FileOutputStream" "fd"))]
         [fd (jslot fdo (jfield-index "java/io/FileDescriptor" "fd"))]
         [port (if (eqv? fd 2) stderr-bin stdout-bin)]
         [v (jarr-vec bytes)]
         [bv (make-bytevector len)])
    (do ([i 0 (fx+ i 1)]) ((fx= i len)) (bytevector-u8-set! bv i (fxlogand (vector-ref v (fx+ off i)) #xff)))
    (flush-output-port (current-output-port))
    (put-bytevector port bv)
    (flush-output-port port)))
(defnative "java/io/FileOutputStream.write(IZ)V" (this b append)
  (let* ([fdo (jslot this (jfield-index "java/io/FileOutputStream" "fd"))]
         [fd (jslot fdo (jfield-index "java/io/FileDescriptor" "fd"))]
         [port (if (eqv? fd 2) stderr-bin stdout-bin)])
    (put-u8 port (fxlogand b #xff))
    (flush-output-port port)))

;; --- signals: none are supported, which Terminator.setup tolerates ------------
(defnative "jdk/internal/misc/Signal.findSignal0(Ljava/lang/String;)I" (name) -1)
(defnative "jdk/internal/misc/Signal.handle0(IJ)J" (sig handler) 0)

;; --- threads: one platform thread, built the way the JVM builds it --------------
;; currentThread answers main-thread before its constructor has run, because
;; Thread's constructor itself asks for the current thread.

(define main-thread #f)
(defnative "java/lang/Thread.currentThread()Ljava/lang/Thread;" () main-thread)
(defnative "java/lang/Thread.currentCarrierThread()Ljava/lang/Thread;" () main-thread)
(defnative "java/lang/Thread.setCurrentThread(Ljava/lang/Thread;)V" (this t) (void))
(defnative "java/lang/Thread.setPriority0(I)V" (this p) (void))
(defnative "java/lang/Thread.setNativeName(Ljava/lang/String;)V" (this n) (void))
(defnative "java/lang/Thread.holdsLock(Ljava/lang/Object;)Z" (o) 1)
(defnative "java/lang/Thread.start0()V" (this) (void)) ; spike: started threads never run
(defnative "java/lang/Thread.yield0()V" () (void))
(defnative "java/lang/Thread.getNextThreadIdOffset()J" () (native-alloc! 3))
(define scoped-value-cache #f)
(defnative "java/lang/Thread.scopedValueCache()[Ljava/lang/Object;" () scoped-value-cache)
(defnative "java/lang/Thread.setScopedValueCache([Ljava/lang/Object;)V" (c) (set! scoped-value-cache c))
(defnative "java/lang/Thread.findScopedValueBindings()Ljava/lang/Object;" () #f)

(define (boot-threads!)
  (jinit "java/lang/ThreadGroup")
  (let ([system (jnew "java/lang/ThreadGroup")]
        [main (jnew "java/lang/ThreadGroup")])
    ((method-proc "java/lang/ThreadGroup" "<init>()V") system)
    ((method-proc "java/lang/ThreadGroup" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main system (jstring "main"))
    (jinit "java/lang/Thread")
    (set! main-thread (jnew "java/lang/Thread"))
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main-thread main (jstring "main"))))

;; --- references: no finalization, no reference-processing thread ----------------
(defnative "java/lang/ref/Finalizer.isFinalizationEnabled()Z" () 0)
(defnative "java/lang/ref/Reference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (jslot this (jfield-index "java/lang/ref/Reference" "referent")) o) 1 0))
(defnative "java/lang/ref/Reference.clear0()V" (this)
  (jslot-set! this (jfield-index "java/lang/ref/Reference" "referent") #f))
(defnative "java/lang/ref/Reference.waitForReferencePendingList()V" () (void))
(defnative "java/lang/ref/Reference.getAndClearReferencePendingList()Ljava/lang/ref/Reference;" () #f)
(defnative "java/lang/ref/Reference.hasReferencePendingList()Z" () 0)

;; --- overrides of Java-coded methods ----------------------------------------------

;; (none yet: Class.getEnumConstantsShared used to be one, before reflect.ss)
