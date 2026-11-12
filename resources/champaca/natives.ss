;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

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
;; set once Java's own exit sequence has begun (Shutdown.exit calls this first,
;; holding its lock), so a signal arriving then leaves the hooks to it
(define java-exit-begun #f)
(defnative "java/lang/Shutdown.beforeHalt()V" () (set! java-exit-begun #t))
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
    (arraycopy-arrays! src dst)
    (let* ([se (jarr-elem src)] [de (jarr-elem dst)] [sv (jarr-vec src)] [dv (jarr-vec dst)]
           [sname (arraycopy-type-name se)])
      (unless (if (prim-desc? se) (string=? se de) (not (prim-desc? de)))
        (arraycopy-fail "java/lang/ArrayStoreException"
                        (string-append "type mismatch: can not copy " sname "[] into "
                                       (arraycopy-type-name de) "[]")))
      (arraycopy-bounds! se sv sp de dv dp n)
      (if (arraycopy-whole? se de)
          (arraycopy-move! sv sp dv dp n)
          (arraycopy-elements! dst se sv sp de dv dp n)))))

;; src and dst must both be arrays
(define (arraycopy-arrays! src dst)
  (unless (jarr? src)
    (arraycopy-fail "java/lang/ArrayStoreException"
                    (string-append "source type " (slashes->dots (class-name-of src)) " is not an array")))
  (unless (jarr? dst)
    (arraycopy-fail "java/lang/ArrayStoreException"
                    (string-append "destination type " (slashes->dots (class-name-of dst)) " is not an array"))))

(define (arraycopy-bounds-fail msg) (arraycopy-fail "java/lang/ArrayIndexOutOfBoundsException" msg))
(define (arraycopy-out-of e v)
  (string-append " out of bounds for " (arraycopy-type-name e) "[" (number->string (vector-length v)) "]"))
;; the indexes and length, checked in HotSpot's order
(define (arraycopy-bounds! se sv sp de dv dp n)
  (cond [(fx< sp 0) (arraycopy-bounds-fail (string-append "source index " (number->string sp) (arraycopy-out-of se sv)))]
        [(fx< dp 0) (arraycopy-bounds-fail (string-append "destination index " (number->string dp) (arraycopy-out-of de dv)))]
        [(fx< n 0) (arraycopy-bounds-fail (string-append "length " (number->string n) " is negative"))]
        [(fx> (fx+ sp n) (vector-length sv))
         (arraycopy-bounds-fail (string-append "last source index " (number->string (fx+ sp n)) (arraycopy-out-of se sv)))]
        [(fx> (fx+ dp n) (vector-length dv))
         (arraycopy-bounds-fail (string-append "last destination index " (number->string (fx+ dp n)) (arraycopy-out-of de dv)))]))

;; whether every element of an se array fits a de array, so the copy needs
;; no per-element check; descriptors are interned, so the same element type
;; is eq?
(define (arraycopy-whole? se de)
  (or (eq? se de) (prim-desc? se) (string=? de "Ljava/lang/Object;")
      (class-assignable? (string-append "[" se) (string-append "[" de))))

;; each element, up to the first that doesn't fit; the message says whether
;; the copy narrows or the element types are unrelated
(define (arraycopy-elements! dst se sv sp de dv dp n)
  (do ([i 0 (fx+ i 1)]) ((fx= i n))
    (let ([x (vector-ref sv (fx+ sp i))])
      (unless (or (not x) (array-store-ok? dst x))
        (let ([sn (slashes->dots (desc->class-name se))] [dn (slashes->dots (desc->class-name de))])
          (arraycopy-fail "java/lang/ArrayStoreException"
                          (if (class-assignable? (string-append "[" de) (string-append "[" se))
                              (string-append "element type mismatch: can not cast one of the elements of "
                                             sn "[] to the type of the destination array, " dn)
                              (string-append "type mismatch: can not copy " sn "[] into " dn "[]")))))
      (vector-set! dv (fx+ dp i) x))))
(defnative "java/lang/System.setIn0(Ljava/io/InputStream;)V" (s) (set-top-level-value! '|java/lang/System:in| s))
(defnative "java/lang/System.setOut0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:out| s))
(defnative "java/lang/System.setErr0(Ljava/io/PrintStream;)V" (s) (set-top-level-value! '|java/lang/System:err| s))

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
(defnative "java/lang/System.mapLibraryName(Ljava/lang/String;)Ljava/lang/String;" (name)
  (jstring (string-append "lib" (jstring->scheme (nn name)) (if natives-macos? ".dylib" ".so"))))
;; The JDK's own libraries are champaca's natives, so loading one is a no-op.
;; Any other library loads as the JDK loads it (foreign.ss java-load-library),
;; for SymbolLookup.loaderLookup; its JNI code can't run.
(define-override! "java/lang/ClassLoader.loadLibrary(Ljava/lang/Class;Ljava/lang/String;)Ljdk/internal/loader/NativeLibrary;"
  (lambda (from name)
    (let ([n (jstring->scheme (nn name))])
      (if (file-exists? (string-append java-home-dir "/lib/lib" n (if natives-macos? ".dylib" ".so")))
          #f   ; Runtime.loadLibrary0 drops the result
          (java-load-library from name)))))

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
(defnative "java/lang/Runtime.gc()V" (this) (explicit-collect! "end of major GC" "System.gc()"))

;; --- java.lang.Class --------------------------------------------------------------

(defnative "java/lang/Class.getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;" (n)
  (jmirror-of (jstring->scheme n)))
;; --enable-preview, from champaca.main (set-preview!, before the boot);
;; classfile.clj's parser lets this release's preview class files load by the
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
(define (parent-package p)   ; "a/b/c" -> "a/b", "a" -> #f
  (let back ([j (fx- (string-length p) 1)])
    (cond [(fx< j 0) #f] [(char=? (string-ref p j) #\/) (substring p 0 j)] [else (back (fx- j 1))])))
;; the package's directive, else the nearest enclosing package's; a class in
;; the unnamed package matches -ea:... alone, one in a package never does
(define (package-directive pkg)
  (let loop ([p pkg])
    (or (assertion-directive #\p p)
        (let ([up (parent-package p)]) (and up (loop up))))))
(define (assertions-enabled? cname system?)
  (cond [(assertion-directive #\c cname) => cadr]
        [(package-directive (package-dir cname)) => cadr]
        [(find (lambda (d) (char=? (car d) (if system? #\s #\u))) assertion-directives) => cadr]
        [else #f]))
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
;; Class name checks for forName0, written from HotSpot 25's observed behavior
;; (probed with class loaders that count their loadClass calls). A name is
;; refused at once, without asking any loader, when:
;; - it is empty, or has an empty segment (a leading or doubled separator;
;;   one trailing separator is allowed, but not alone);
;; - it holds ; or [ (apart from an array descriptor's own), or a segment
;;   whose last char is NUL (a NUL elsewhere is fine);
;; - an array descriptor has over 255 dimensions, no element, a primitive
;;   element other than B C D F I J S Z, or a class element that is not
;;   L<valid name>; (one name, nothing after the semicolon).
;; n is the slashed form.
(define (cn-plain-name? n)
  (let ([len (string-length n)])
    (and (fx> len 0)
         (let loop ([i 0] [start 0])
           (cond
             [(fx= i len)
              ;; the last segment: empty only after a separator
              (and (or (fx> i start) (fx> start 0))
                   (or (fx= i start) (not (char=? (string-ref n (fx- i 1)) #\nul))))]
             [else
              (let ([c (string-ref n i)])
                (cond
                  [(or (char=? c #\;) (char=? c #\[)) #f]
                  [(char=? c #\/)
                   (and (fx> i start)
                        (not (char=? (string-ref n (fx- i 1)) #\nul))
                        (loop (fx+ i 1) (fx+ i 1)))]
                  [else (loop (fx+ i 1) start)]))])))))
(define (valid-class-name? n)
  (let ([len (string-length n)])
    (and (fx> len 0)
         (if (char=? (string-ref n 0) #\[)
             (let skip ([d 0])
               (cond
                 [(fx= d len) #f]
                 [(char=? (string-ref n d) #\[) (skip (fx+ d 1))]
                 [(fx> d 255) #f]
                 [(char=? (string-ref n d) #\L)
                  (and (fx> len (fx+ d 2)) (char=? (string-ref n (fx- len 1)) #\;)
                       (cn-plain-name? (substring n (fx+ d 1) (fx- len 1))))]
                 [else (and (fx= d (fx- len 1)) (memv (string-ref n d) '(#\B #\C #\D #\F #\I #\J #\S #\Z)) #t)]))
             (cn-plain-name? n)))))

;; The modified UTF-8 bytes of a list of UTF-16 units, as a bytevector.
(define (cn-mutf8-bytes units)
  (let* ([unit-size (lambda (u) (cond [(and (fx> u 0) (fx< u #x80)) 1] [(fx< u #x800) 2] [else 3]))]
         [bv (make-bytevector (fold-left (lambda (a u) (fx+ a (unit-size u))) 0 units))])
    (let loop ([us units] [i 0])
      (if (null? us) bv
          (let ([u (car us)])
            (case (unit-size u)
              [(1) (bytevector-u8-set! bv i u) (loop (cdr us) (fx+ i 1))]
              [(2) (bytevector-u8-set! bv i (fxlogor #xc0 (fxsrl u 6)))
                   (bytevector-u8-set! bv (fx+ i 1) (fxlogor #x80 (fxlogand u #x3f)))
                   (loop (cdr us) (fx+ i 2))]
              [else (bytevector-u8-set! bv i (fxlogor #xe0 (fxsrl u 12)))
                    (bytevector-u8-set! bv (fx+ i 1) (fxlogor #x80 (fxlogand (fxsrl u 6) #x3f)))
                    (bytevector-u8-set! bv (fx+ i 2) (fxlogor #x80 (fxlogand u #x3f)))
                    (loop (cdr us) (fx+ i 3))]))))))
;; Bytes lo..hi as UTF-16 units: a complete 2- or 3-byte sequence is one unit,
;; any other byte (a cut sequence's pieces) one unit of its own value.
(define (cn-bytes->units bv lo hi)
  (let ([cont? (lambda (i) (fx= (fxlogand (bytevector-u8-ref bv i) #xc0) #x80))])
    (let loop ([i lo] [acc '()])
      (if (fx>= i hi) (reverse acc)
          (let ([b (bytevector-u8-ref bv i)])
            (cond
              [(and (fx>= b #xc0) (fx< b #xe0) (fx< (fx+ i 1) hi) (cont? (fx+ i 1)))
               (loop (fx+ i 2) (cons (fxlogor (fxsll (fxlogand b #x1f) 6) (fxlogand (bytevector-u8-ref bv (fx+ i 1)) #x3f)) acc))]
              [(and (fx>= b #xe0) (fx< b #xf0) (fx< (fx+ i 2) hi) (cont? (fx+ i 1)) (cont? (fx+ i 2)))
               (loop (fx+ i 3)
                     (cons (fxlogor (fxsll (fxlogand b #x0f) 12)
                                    (fxsll (fxlogand (bytevector-u8-ref bv (fx+ i 1)) #x3f) 6)
                                    (fxlogand (bytevector-u8-ref bv (fx+ i 2)) #x3f))
                           acc))]
              [else (loop (fx+ i 1) (cons b acc))]))))))
;; A name over 65535 bytes of modified UTF-8 (counted on the slashed form) is
;; ClassNotFoundException "Class name exceeds maximum length of 65535: " plus
;; its first 128 bytes, " ... (N characters omitted) ... " (N = length - 256,
;; in bytes) and its last 128 bytes, each part decoded by cn-bytes->units.
;; The message has as many units as its text has bytes that aren't UTF-8
;; continuation bytes, so a cut sequence's stray bytes push the message's
;; last units off the end.
(define (overlong-class-name name)
  (let* ([units (map (lambda (u) (if (fx= u 46) 47 u)) (jstring-units name))]
         [size (fold-left (lambda (a u) (fx+ a (cond [(and (fx> u 0) (fx< u #x80)) 1] [(fx< u #x800) 2] [else 3]))) 0 units)])
    (when (fx> size 65535)
      (let ([bv (cn-mutf8-bytes units)])
        (let* ([pre (code-units "Class name exceeds maximum length of 65535: ")]
               [mid (code-units (string-append " ... (" (number->string (fx- size 256)) " characters omitted) ... "))]
               [all (append pre (cn-bytes->units bv 0 128) mid (cn-bytes->units bv (fx- size 128) size))]
               [lead-count (lambda (lo hi)
                             (let loop ([i lo] [k 0])
                               (if (fx= i hi) k
                                   (loop (fx+ i 1)
                                         (if (fx= (fxlogand (bytevector-u8-ref bv i) #xc0) #x80) k (fx+ k 1))))))]
               [keep (fx+ (length pre) (length mid) (lead-count 0 128) (lead-count (fx- size 128) size))])
          (jthrow "java/lang/ClassNotFoundException"
                  (list->vector (list-head all (fxmin keep (length all))))))))))

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
         [_ (cond
              ;; Class.c: a name with a slash is not found as given; one whose
              ;; slashed form isn't a class name or descriptor (an empty name,
              ;; over 255 dimensions) is not found by that slashed form
              [(memv #\/ (string->list s)) (jthrow "java/lang/ClassNotFoundException" s)]
              [(not (valid-class-name? n)) (jthrow "java/lang/ClassNotFoundException" n)]
              ;; one Scheme char is at most 6 bytes of modified UTF-8 (a code
              ;; point beyond the BMP), so a shorter name can't be too long
              [(fx> (string-length s) 10922) (overlong-class-name name)])]
         ;; an array with a class element exists once the element resolves
         ;; through the loader (its pseudo-class would be made for any name,
         ;; and mirrored, it loads its element)
         [c (cond [(and array? (not element)) #f]
                  [(and array? (not (eq? element 'prim))) (and (resolve element) (try-class n))]
                  [array? (try-class n)]
                  [else (resolve s)])])
    ;; HotSpot's texts: the boot loader names the slashed name, a loader
    ;; object the class it was asked for (an array's element, dotted)
    (unless c
      (jthrow "java/lang/ClassNotFoundException"
              (cond [(not loader) n] [(and array? (string? element)) element] [else s])))
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
(define (fill-ste! ste cname mname desc pc)
  (let* ([info (if frame-hook (frame-hook cname mname desc (if (eqv? pc -2) -1 pc)) (vector "" -1 ""))]
         [file (vector-ref info 0)] [line (if (eqv? pc -2) -2 (vector-ref info 1))] [module (vector-ref info 2)]
         [set (lambda (f v) (jslot-set! ste (slot-of "java/lang/StackTraceElement" f) v))])
    (set "declaringClassObject" (jmirror-of cname))
    (set "declaringClass" (jstring (slashes->dots cname)))
    (set "methodName" (jstring mname))
    (set "fileName" (and (fx> (string-length file) 0) (jstring file)))
    (set "lineNumber" line)
    (set "moduleName" (and (fx> (string-length module) 0) (jstring module)))
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
