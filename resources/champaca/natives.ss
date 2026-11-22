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
;; run before a thread other than main ends the process (System.exit or
;; Runtime.halt there, as ABCL's interpreter thread does): champaca.main saves
;; the startup cache as main's return does, so such a program comes back warm.
;; A save that fails doesn't stop the exit; it says so on stderr.
(define off-main-exit-hook #f)
(define (set-off-main-exit-hook! f) (set! off-main-exit-hook f))
(defnative "java/lang/Shutdown.halt0(I)V" (status)
  (if (eq? (self) main-state)
      (raise (make-java-halt status))
      (begin (when off-main-exit-hook
               ;; champaca's work, so past the Java stack bound (call-unbounded):
               ;; a save under the 1 MB launcher bound overflowed on ABCL
               (guard (e [#t (let ([p (console-error-port)])
                               (display "champaca: couldn't save the startup cache at exit: " p)
                               (display (if (jobj? e) "a Java exception" (condition-text e)) p)
                               (newline p))])
                 (call-unbounded off-main-exit-hook)))
             (exit-process status))))
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
;; The copy itself, for indexes its callers have checked: Chez's vector-copy!,
;; which copies as if through a temporary, so an overlap within one vector
;; comes out right either way. Unchecked (#3%): at jolt's optimize level the
;; element-by-element loop this replaced cost 2.7 ns an element.
(define (arraycopy-move! sv sp dv dp n)
  (#3%vector-copy! sv sp dv dp n))

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
          (arraycopy-elements! dst se sv sp de dp n)))))

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
(define (arraycopy-elements! dst se sv sp de dp n)
  (do ([dv (jarr-vec dst)] [i 0 (fx+ i 1)]) ((fx= i n))
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
;; jolt's heap ceiling, which a large array is checked against (runtime.ss):
;; HotSpot's default maximum heap, or JOLT_MAX_HEAP; -Xmx is ignored
(defnative "java/lang/Runtime.maxMemory()J" (this)
  (max (or (jolt-heap-max-bytes) 4294967296) (current-memory-bytes)))
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
                         (slashes->dots (plain-class-name n))))])
    (jslot-set! this (jfield-index "java/lang/Class" "name") s)
    s))
;; both go through the answers each class keeps (objects.ss
;; class-instance-of?): a class's name is one string object for its life, so
;; it serves as the key a site's literal is there
(defnative "java/lang/Class.isInstance(Ljava/lang/Object;)Z" (this x)
  (let ([target (jclass-cname (mirror-class this))])
    (if (if (jobj? x) (class-instance-of? (jobj-cls x) target) (jinstance? x target)) 1 0)))
(defnative "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z" (this other)
  (if (class-instance-of? (mirror-class other) (jclass-cname (mirror-class this))) 1 0))
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
(define (string-has-char? s ch)
  (let ([len (string-length s)])
    (let loop ([i 0]) (and (fx< i len) (or (char=? (string-ref s i) ch) (loop (fx+ i 1)))))))
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
;; the modified UTF-8 length of UTF-16 units
(define (cn-mutf8-size units)
  (fold-left (lambda (a u) (fx+ a (cond [(and (fx> u 0) (fx< u #x80)) 1] [(fx< u #x800) 2] [else 3]))) 0 units))

;; how many characters start in bv's bytes lo..hi (bytes that aren't UTF-8
;; continuation bytes)
(define (cn-lead-count bv lo hi)
  (let loop ([i lo] [k 0])
    (if (fx= i hi) k
        (loop (fx+ i 1)
              (if (fx= (fxlogand (bytevector-u8-ref bv i) #xc0) #x80) k (fx+ k 1))))))

;; HotSpot's message for a class name of size bytes bv: its first and last
;; 128 bytes around the count omitted, cut to the characters those bytes start
(define (cn-overlong-message bv size)
  (let* ([pre (code-units "Class name exceeds maximum length of 65535: ")]
         [mid (code-units (string-append " ... (" (number->string (fx- size 256)) " characters omitted) ... "))]
         [all (append pre (cn-bytes->units bv 0 128) mid (cn-bytes->units bv (fx- size 128) size))]
         [keep (fx+ (length pre) (length mid) (cn-lead-count bv 0 128) (cn-lead-count bv (fx- size 128) size))])
    (list->vector (list-head all (fxmin keep (length all))))))

(define (overlong-class-name name)
  (let* ([units (map (lambda (u) (if (fx= u 46) 47 u)) (jstring-units name))]
         [size (cn-mutf8-size units)])
    (when (fx> size 65535)
      (jthrow "java/lang/ClassNotFoundException"
              (cn-overlong-message (cn-mutf8-bytes units) size)))))

;; a String forName0 was given -> #(its text, slashed, the class once found
;; through a built-in loader), for the names that passed its checks
(define forname-names (make-weak-eq-hashtable))
(define builtin-loader-class "jdk/internal/loader/BuiltinClassLoader")
;; try-class for the name n of entry known, remembering a class it finds: a
;; class record, once there, stays the answer to its name
(define (known-class known n)
  (or (and known (vector-ref known 2))
      (let ([c (try-class n)])
        (when (and c known) (vector-set! known 2 c))
        c)))
(defnative "java/lang/Class.forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;"
  (name init loader caller)
  ;; a binary name: one with slashes is not found, as on the JVM. A loader
  ;; of the program's own answers through its loadClass, as the JVM asks it
  ;; to (a loader that defines classes on demand needs that); for an array
  ;; it is asked for the element class, a primitive element needing no one.
  ;; The built-in loaders (null is the boot loader) go to the class table
  ;; and see what that loader would load (builtin-loader-sees?)
  (let* ([known (and name (#3%eq-hashtable-ref forname-names name #f))]
         [s (if known (vector-ref known 0) (jstring->scheme name))]
         [n (if known (vector-ref known 1) (slashes s))]
         [own? (and loader (not (class-instance-of? (jobj-cls loader) builtin-loader-class)))]
         [array? (and (fxpositive? (string-length s)) (char=? (string-ref s 0) #\[))]
         [element (and array? (array-element-binary-name s))]
         ;; a class by binary name, through the loader given
         [resolve (lambda (bn)
                    (if own?
                        (let ([m ((jvirt loader "loadClass(Ljava/lang/String;)Ljava/lang/Class;") loader (jstring bn))])
                          (and m (mirror-class m)))
                        (let ([c (if (eq? bn s) (known-class known n) (try-class (slashes bn)))])
                          (and c (builtin-loader-sees? loader c) c))))]
         [_ (cond
              [known (void)]   ; it passed what follows before
              ;; Class.c: a name with a slash is not found as given; one whose
              ;; slashed form isn't a class name or descriptor (an empty name,
              ;; over 255 dimensions) is not found by that slashed form
              [(string-has-char? s #\/) (jthrow "java/lang/ClassNotFoundException" s)]
              [(not (valid-class-name? n)) (jthrow "java/lang/ClassNotFoundException" n)]
              ;; one Scheme char is at most 6 bytes of modified UTF-8 (a code
              ;; point beyond the BMP), so a shorter name can't be too long
              [(fx> (string-length s) 10922) (overlong-class-name name)]
              ;; one that passed is kept for its String: a name in code is a
              ;; literal, the same object on every call
              [(not known) (set! known (vector s n #f)) (eq-hashtable-set! forname-names name known)])]
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
    (when (and (eqv? init 1) (not (eq? (jclass-state c) 'initialized))) (jinit (jclass-cname c)))
    (jmirror c)))
