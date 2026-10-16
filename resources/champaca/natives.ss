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
(defnative "java/lang/Shutdown.beforeHalt()V" () (void))
(defnative "java/lang/Shutdown.halt0(I)V" (status) (raise (make-java-halt status)))
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
  (let* ([n (jclass-cname (mirror-class this))]
         [s (jstring (list->string (map (lambda (c) (if (char=? c #\/) #\. c)) (string->list n))))])
    (jslot-set! this (jfield-index "java/lang/Class" "name") s)
    s))
(defnative "java/lang/Class.isInstance(Ljava/lang/Object;)Z" (this x)
  (if (jinstance? x (jclass-cname (mirror-class this))) 1 0))
(defnative "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z" (this other)
  (if (class-assignable? (jclass-cname (mirror-class other)) (jclass-cname (mirror-class this))) 1 0))
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

;; JEP 358's "Cannot invoke ... because ..." text needs the faulting bytecode;
;; null is what HotSpot answers when it has none, so getMessage stays null
(defnative "java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;" (this) #f)

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
    [(not o) (hashtable-ref native-memory off 0)]
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
    [(not o) (hashtable-set! native-memory off v)]
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
(defnative "jdk/internal/misc/Unsafe.staticFieldBase0(Ljava/lang/reflect/Field;)Ljava/lang/Object;" (this f)
  (field-mirror (nn f)))
(defnative "jdk/internal/misc/Unsafe.staticFieldOffset0(Ljava/lang/reflect/Field;)J" (this f)
  (fx+ static-offset-tag (jslot (nn f) (jfield-index "java/lang/reflect/Field" "slot"))))
(defnative "jdk/internal/misc/Unsafe.ensureClassInitialized0(Ljava/lang/Class;)V" (this c)
  (jinit (jclass-cname (mirror-class c))))
(defnative "jdk/internal/misc/Unsafe.shouldBeInitialized0(Ljava/lang/Class;)Z" (this c)
  (if (eq? (jclass-state (mirror-class c)) 'loaded) 1 0))
(defnative "jdk/internal/misc/Unsafe.allocateInstance(Ljava/lang/Class;)Ljava/lang/Object;" (this c)
  (jnew (jclass-cname (mirror-class c))))

(define (starts? s p) (and (fx>= (string-length s) (string-length p)) (string=? p (substring s 0 (string-length p)))))
(define (method-part n) (substring n (string-length unsafe-prefix) (string-length n)))

;; get<T>[Volatile](Object, long) / put<T>[Volatile](Object, long, T)
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "get") (string-search "(Ljava/lang/Object;J)" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))]) (lambda (this o off) (unsafe-get o off t)))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "put") (string-search "(Ljava/lang/Object;J" n)))
  (lambda (n) (let ([t (unsafe-type (method-part n))]) (lambda (this o off v) (unsafe-set! o off v t)))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndSet")))
  (lambda (n) (let ([t (unsafe-type (method-part n))])
                (lambda (this o off expect new)
                  (if (eqv? (unsafe-get o off t) expect) (begin (unsafe-set! o off new t) 1) 0)))))
(define-native-rule!
  (lambda (n) (and (starts? n unsafe-prefix) (starts? (method-part n) "compareAndExchange")))
  (lambda (n) (lambda (this o off expect new)
                (let* ([t (unsafe-type (method-part n))] [old (unsafe-get o off t)])
                  (when (eqv? old expect) (unsafe-set! o off new t))
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
     ("java.vm.name" . "champaca")
     ("java.vm.vendor" . "champaca")
     ("java.vm.version" . "0.0-spike")
     ("java.vm.specification.name" . "Java Virtual Machine Specification")
     ("java.vm.specification.vendor" . "Oracle Corporation")
     ("java.vm.specification.version" . "25")
     ("java.class.path" . "")
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
    (set "_os_name_NDX" "Mac OS X")
    (set "_os_arch_NDX" "aarch64")
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

;; --- file input -------------------------------------------------------------------
;; FileInputStream reads through Chez binary ports kept in a table keyed by the
;; int in FileDescriptor.fd; fds handed out here start at 3. fd 0 is stdin.

(define open-files (make-eqv-hashtable))
(define next-fd 3)

(define (stream-fd this)
  (jslot (jslot this (jfield-index "java/io/FileInputStream" "fd"))
         (jfield-index "java/io/FileDescriptor" "fd")))
(define (fd-port fd)
  (cond [(hashtable-ref open-files fd #f)]
        [(eqv? fd 0) (standard-input-port)]
        [else (jthrow "java/io/IOException" "Stream Closed")]))
(define (stream-port this) (fd-port (stream-fd this)))

(defnative "java/io/FileInputStream.open0(Ljava/lang/String;)V" (this name)
  (let ([p (jstring->scheme (nn name))])
    (cond
      [(not (file-exists? p))
       (jthrow "java/io/FileNotFoundException" (string-append p " (No such file or directory)"))]
      [(file-directory? p)
       (jthrow "java/io/FileNotFoundException" (string-append p " (Is a directory)"))]
      [else
       (let ([fd next-fd])
         (set! next-fd (fx+ next-fd 1))
         (hashtable-set! open-files fd (open-file-input-port p))
         (jslot-set! (jslot this (jfield-index "java/io/FileInputStream" "fd"))
                     (jfield-index "java/io/FileDescriptor" "fd") fd))])))

(defnative "java/io/FileInputStream.read0()I" (this)
  (let ([b (get-u8 (stream-port this))]) (if (eof-object? b) -1 b)))

(defnative "java/io/FileInputStream.readBytes([BII)I" (this bytes off len)
  (if (fx= len 0)
      0
      (let ([bv (get-bytevector-n (stream-port this) len)])
        (if (eof-object? bv)
            -1
            (let ([v (jarr-vec bytes)] [n (bytevector-length bv)])
              (do ([i 0 (fx+ i 1)]) ((fx= i n) n)
                (vector-set! v (fx+ off i) (i2b (bytevector-u8-ref bv i)))))))))

(define (seekable-port? port) (and (port-has-port-length? port) (port-has-port-position? port)))

(defnative "java/io/FileInputStream.available0()I" (this)
  (let ([port (stream-port this)])
    (if (seekable-port? port) (max 0 (- (port-length port) (port-position port))) 0)))
(defnative "java/io/FileInputStream.skip0(J)J" (this n)
  (let* ([port (stream-port this)] [pos (port-position port)]
         [to (min (port-length port) (+ pos (max n 0)))])
    (set-port-position! port to)
    (- to pos)))
(defnative "java/io/FileInputStream.length0()J" (this) (port-length (stream-port this)))
(defnative "java/io/FileInputStream.position0()J" (this) (port-position (stream-port this)))
(defnative "java/io/FileInputStream.isRegularFile0(Ljava/io/FileDescriptor;)Z" (this fdo)
  (let ([port (hashtable-ref open-files (jslot fdo (jfield-index "java/io/FileDescriptor" "fd")) #f)])
    (if (and port (seekable-port? port)) 1 0)))

(defnative "java/io/FileDescriptor.close0()V" (this)
  (let* ([i (jfield-index "java/io/FileDescriptor" "fd")] [fd (jslot this i)]
         [port (hashtable-ref open-files fd #f)])
    (when port
      (close-port port)
      (hashtable-delete! open-files fd))
    (jslot-set! this i -1)))

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
(defnative "java/lang/ref/PhantomReference.refersTo0(Ljava/lang/Object;)Z" (this o)
  (if (eq? (jslot this (jfield-index "java/lang/ref/Reference" "referent")) o) 1 0))
(defnative "java/lang/ref/PhantomReference.clear0()V" (this)
  (jslot-set! this (jfield-index "java/lang/ref/Reference" "referent") #f))
(defnative "java/lang/ref/Reference.waitForReferencePendingList()V" () (void))
(defnative "java/lang/ref/Reference.getAndClearReferencePendingList()Ljava/lang/ref/Reference;" () #f)
(defnative "java/lang/ref/Reference.hasReferencePendingList()Z" () 0)

;; --- overrides of Java-coded methods ----------------------------------------------

;; (none yet: Class.getEnumConstantsShared used to be one, before reflect.ss)

;; --- java.io.UnixFileSystem: the read-only queries --------------------------------
;; Enough for code that probes the file system (locale providers, resource
;; lookups). Writes, permissions and listing are not here yet.

(define (file-path f) (jstring->scheme (jslot f (jfield-index "java/io/File" "path"))))

(defnative "java/io/UnixFileSystem.canonicalize0(Ljava/lang/String;)Ljava/lang/String;" (this p)
  ;; spike: no symlink resolution; the JDK has already made the path absolute
  p)
(defnative "java/io/UnixFileSystem.getBooleanAttributes0(Ljava/io/File;)I" (this f)
  (let ([p (file-path f)])
    (if (file-exists? p)
        (fxlogor 1
                 (if (file-regular? p) 2 0)
                 (if (file-directory? p) 4 0))
        0)))
(defnative "java/io/UnixFileSystem.checkAccess0(Ljava/io/File;I)Z" (this f access)
  (if (file-exists? (file-path f)) 1 0))
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

;; --- modules and class loaders --------------------------------------------------
;; One flat class space: module wiring is recorded nowhere, and every loader
;; sees the same classes (user classpath, then java.base).

(defnative "jdk/internal/loader/BootLoader.setBootLoaderUnnamedModule0(Ljava/lang/Module;)V" (m) (void))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageNames()[Ljava/lang/String;" ()
  (make-jarr "Ljava/lang/String;" (vector)))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageLocation(Ljava/lang/String;)Ljava/lang/String;" (n) #f)
(defnative "java/lang/Module.defineModule0(Ljava/lang/Module;ZLjava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
  (m open? version location pns) (void))
(defnative "java/lang/Module.addReads0(Ljava/lang/Module;Ljava/lang/Module;)V" (from to) (void))
(defnative "java/lang/Module.addExports0(Ljava/lang/Module;Ljava/lang/String;Ljava/lang/Module;)V" (from pn to) (void))
(defnative "java/lang/Module.addExportsToAll0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))
(defnative "java/lang/Module.addExportsToAllUnnamed0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))

(define (try-class n)
  (guard (e [(not (jobj? e)) #f]) (find-class n)))
(defnative "java/lang/ClassLoader.findBootstrapClass(Ljava/lang/String;)Ljava/lang/Class;" (name)
  (let ([c (try-class (slashes (jstring->scheme name)))]) (and c (jmirror c))))
(defnative "java/lang/ClassLoader.findLoadedClass0(Ljava/lang/String;)Ljava/lang/Class;" (this name)
  (let ([c (hashtable-ref classes (slashes (jstring->scheme name)) #f)]) (and c (jmirror c))))

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

;; --- VarHandles -------------------------------------------------------------------
;; A VarHandle is a bare java/lang/invoke/VarHandle object (allocated, never
;; constructed, so instanceof and checkcast work) mapped to what it points
;; at. Codegen sends every signature-polymorphic call on VarHandle to vh-call
;; with the access mode's name, so one procedure serves get, set,
;; compareAndSet, getAndAdd and the rest. Memory-ordering suffixes (Volatile,
;; Acquire, Release, Opaque, Plain) don't matter on one thread.
;;
;; kind   coordinates      a                      b
;; field  (obj)            slot index             -
;; static ()               the static's symbol    class to initialize
;; array  (array index)    -                      -
;; view   (byte[] index)   width in bytes         big-endian?

(define-record-type vh-target (fields kind a b type))
(define vh-targets (make-weak-eq-hashtable))

(define (make-vh kind a b type)
  (let ([vh (jnew "java/lang/invoke/VarHandle")])
    (eq-hashtable-set! vh-targets vh (make-vh-target kind a b type))
    vh))

(define (view-decode t u)
  (case t [("S") (i2s u)] [("I") (i32 u)] [("J") (i64 u)]
          [("F") (bits->float (i32 u))] [("D") (bits->double (i64 u))] [else u]))
(define (view-encode t v)
  (case t [("F") (float->bits v)] [("D") (double->bits v)] [else v]))

;; getter and setter over one access's coordinates
(define (vh-access t coords)
  (case (vh-target-kind t)
    [(field)
     (let ([o (nn (car coords))] [i (vh-target-a t)])
       (values (lambda () (vector-ref (jobj-slots o) i))
               (lambda (v) (vector-set! (jobj-slots o) i v))))]
    [(static)
     (let ([sym (vh-target-a t)])
       (jinit (vh-target-b t))
       (values (lambda () (top-level-value sym)) (lambda (v) (set-top-level-value! sym v))))]
    [(array)
     (let ([a (car coords)] [i (cadr coords)])
       (values (lambda () (jaload a i)) (lambda (v) (jastore a i v))))]
    [(view)
     (let ([a (car coords)] [i (cadr coords)] [n (vh-target-a t)] [big? (vh-target-b t)] [ty (vh-target-type t)])
       (values (lambda () (view-decode ty (bytes-get a i n big?)))
               (lambda (v) (bytes-set! a i n big? (view-encode ty v)))))]))

(define (wrap-as t v)
  (case t [("I") (i32 v)] [("J") (i64 v)] [("S") (i2s v)] [("B") (i2b v)] [("C") (logand v #xffff)] [else v]))

(define (vh-call vh mode . args)
  (let* ([t (or (eq-hashtable-ref vh-targets (nn vh) #f)
                (jthrow "java/lang/UnsupportedOperationException" (string-append "VarHandle." mode)))]
         [ty (vh-target-type t)]
         [n (case (vh-target-kind t) [(field) 1] [(static) 0] [else 2])]
         [vals (list-tail args n)]
         [has? (lambda (p) (starts? mode p))])
    (let-values ([(get set) (vh-access t (list-head args n))])
      (define (update f) (let ([old (get)]) (set (wrap-as ty (f old))) old))
      (cond
        [(or (has? "compareAndSet") (has? "weakCompareAndSet"))
         (if (eqv? (get) (car vals)) (begin (set (cadr vals)) 1) 0)]
        [(has? "compareAndExchange")
         (let ([old (get)]) (when (eqv? old (car vals)) (set (cadr vals))) old)]
        [(has? "getAndSet") (let ([old (get)]) (set (car vals)) old)]
        [(has? "getAndAdd") (update (lambda (old) (+ old (car vals))))]
        [(has? "getAndBitwiseOr") (update (lambda (old) (logior old (car vals))))]
        [(has? "getAndBitwiseAnd") (update (lambda (old) (logand old (car vals))))]
        [(has? "getAndBitwiseXor") (update (lambda (old) (logxor old (car vals))))]
        [(has? "get") (get)]
        [(has? "set") (set (car vals))]
        [else (jthrow "java/lang/UnsupportedOperationException" (string-append "VarHandle." mode))]))))

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
    (let ([dc (car hit)] [desc (cadr (cdr hit))])
      (if static?
          (make-vh 'static (string->symbol (string-append (jclass-cname dc) ":" fname)) (jclass-cname dc) desc)
          (make-vh 'field (jfield-index (jclass-cname dc) fname) #f desc)))))

(define-override! "java/lang/invoke/MethodHandles$Lookup.findVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #f)))
(define-override! "java/lang/invoke/MethodHandles$Lookup.findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (this recv name type) (field-vh recv name type #t)))

(define (array-class-elem cls)
  (let ([n (jclass-cname (mirror-class (nn cls)))])
    (unless (char=? (string-ref n 0) #\[)
      (jthrow "java/lang/IllegalArgumentException" (string-append "not an array class: " n)))
    (substring n 1 (string-length n))))

(define-override! "java/lang/invoke/MethodHandles.arrayElementVarHandle(Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls) (make-vh 'array #f #f (array-class-elem cls))))
(define-override! "java/lang/invoke/MethodHandles.byteArrayViewVarHandle(Ljava/lang/Class;Ljava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;"
  (lambda (cls order)
    (let ([e (array-class-elem cls)]
          [big? (string=? "BIG_ENDIAN" (jstring->scheme (jslot (nn order) (jfield-index "java/nio/ByteOrder" "name"))))])
      (unless (member e '("S" "C" "I" "J" "F" "D"))
        (jthrow "java/lang/UnsupportedOperationException" (string-append "not a view type: " e)))
      (make-vh 'view (type-size e) big? e))))
