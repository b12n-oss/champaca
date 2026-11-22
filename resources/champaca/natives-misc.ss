;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Natives for file descriptors and output, containers and signals,
;; references, the methods HotSpot intrinsifies, UnixFileSystem's queries,
;; performance counters, ByteArray and the zip checksums. Split from
;; natives.ss; loaded right after vmprops.ss.

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
    [(_ h e) (let ([y (#3%fxlogand (#3%fx+ (#3%fx* 31 h) e) #xffffffff)])
               (if (#3%fx>= y #x80000000) (#3%fx- y #x100000000) y))]))
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
    ;; the range is checked above, so the reads below aren't checked again:
    ;; at jolt's optimize level a checked vector-ref costs most of a step
    (let loop ([i from] [h init])
      (if (#3%fx>= i end)
          h
          (loop (#3%fx+ i 1)
                (hash-step h (if mask (#3%fxlogand (#3%vector-ref v i) mask) (#3%vector-ref v i))))))))
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
                     (if (#3%fx>= i end)
                         h
                         (loop (#3%fx+ i 1)
                               (hash-step h (#3%fxlogor (#3%fxlogand (#3%vector-ref v (#3%fx* 2 i)) #xff)
                                                        (#3%fxsll (#3%fxlogand (#3%vector-ref v (#3%fx+ (#3%fx* 2 i) 1)) #xff) 8)))))))
                 (vector-hash v from len init #f))]
        [(8 9 10) (vector-hash v from len init #f)]         ; T_BYTE, T_SHORT, T_INT: signed, as stored
        [else (jthrow "java/lang/IllegalArgumentException" (string-append "unrecognized basic type: " (number->string basic)))]))))
(define-override! "java/lang/StringLatin1.equals([B[B)Z"
  (lambda (value other)
    (let* ([v (jarr-vec (nn value))] [o (jarr-vec (nn other))] [n (vector-length v)])
      (if (fx= n (vector-length o))
          (let loop ([i 0])   ; both lengths are n
            (cond [(#3%fx= i n) 1]
                  [(#3%eq? (#3%vector-ref v i) (#3%vector-ref o i)) (loop (#3%fx+ i 1))]
                  [else 0]))
          0))))
(define-override! "java/lang/StringLatin1.indexOfChar([BIII)I"
  (lambda (value ch from max)
    (let ([v (jarr-vec (nn value))] [c (i2b ch)])   ; (byte) ch, as the JDK compares
      (when (fx< from max) (check-hash-range! from max (vector-length v)))
      (let loop ([i from])   ; inside the range just checked
        (cond [(#3%fx>= i max) -1]
              [(#3%eq? (#3%vector-ref v i) c) i]
              [else (loop (#3%fx+ i 1))])))))

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
