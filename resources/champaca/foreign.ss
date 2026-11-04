;; The foreign function and memory API's native side: library loading and
;; symbol lookup through dlopen and dlsym, and Linker downcalls through Chez's
;; foreign-procedure.
;;
;; The JDK's Java does the linker's checks: Linker.nativeLinker picks the
;; platform's linker (MacOsAArch64Linker, LinuxAArch64Linker, SysVx64Linker)
;; once ForeignLinkerSupport says it is supported, and downcallHandle checks
;; the layouts, the options and the variadic layouts, then adds the capture
;; segment check, the allocator and the bound symbol around what
;; arrangeDowncall returns. HotSpot's arrangeDowncall builds a calling
;; sequence of register moves for its own stubs. Here it returns a champaca
;; method handle of type (MemorySegment target, SegmentAllocator,
;; [MemorySegment capture,] args...) ret, which unboxes the arguments as the
;; JDK's bindings do (each segment's session acquired for the call, a heap
;; segment refused unless the call is critical and allows heap access), and
;; calls the target through a foreign-procedure made once per signature.

;; --- dlopen and dlsym -------------------------------------------------------------
(define RTLD_LAZY 1)
(define jlib-dlopen (foreign-procedure "dlopen" (utf-8 int) uptr))
(define jlib-dlsym (foreign-procedure "dlsym" (uptr utf-8) uptr))
(define jlib-dlclose (foreign-procedure "dlclose" (uptr) int))

(define (ffi-open! impl cname name)
  (let ([h (jlib-dlopen (jstring->scheme (nn name)) RTLD_LAZY)])
    (unless (eqv? h 0) (jslot-set! impl (jfield-index cname "handle") h))
    h))

;; NativeLibraries loads the system lookup's libsyslookup and any library
;; System.loadLibrary or System.load names that the JDK doesn't ship (see
;; java-load-library). HotSpot's text for a library that fails to open is "Can't
;; load library: <name>". HotSpot runs a library's JNI_OnLoad, which needs
;; JNI; champaca refuses such a library instead. One without it loads, so
;; SymbolLookup.loaderLookup finds its symbols, and a Java native method it
;; would implement stays missing (UnsatisfiedLinkError when called).
(defnative "jdk/internal/loader/NativeLibraries.findBuiltinLib(Ljava/lang/String;)Ljava/lang/String;" (name) #f)
(defnative "jdk/internal/loader/NativeLibraries.load(Ljdk/internal/loader/NativeLibraries$NativeLibraryImpl;Ljava/lang/String;ZZ)Z"
  (impl name builtin? throw?)
  (let* ([cname "jdk/internal/loader/NativeLibraries$NativeLibraryImpl"]
         [h (ffi-open! impl cname name)])
    (cond
      [(eqv? h 0)
       (if (eqv? throw? 0)
           0
           (jthrow "java/lang/UnsatisfiedLinkError" (string-append "Can't load library: " (jstring->scheme name))))]
      [(not (eqv? (jlib-dlsym h "JNI_OnLoad") 0))
       (jslot-set! impl (jfield-index cname "handle") 0)
       (jlib-dlclose h)
       (jthrow "java/lang/UnsatisfiedLinkError"
               (string-append (jstring->scheme name) " has a JNI_OnLoad, and champaca runs no JNI code"))]
      [else
       (jslot-set! impl (jfield-index cname "jniVersion") #x00010001)
       1])))

;; ClassLoader.loadLibrary(Class, String) for a library the JDK doesn't
;; ship, as JDK 25 writes it: the boot loader's libraries for a class
;; without a loader, else the loader's findLibrary, else its libraries'
;; search of sun.boot.library.path and java.library.path, with the JDK's
;; texts when none has it.
(define (java-load-library from name)
  (let* ([n (jstring->scheme name)]
         [loader (and from ((jvirt from "getClassLoader()Ljava/lang/ClassLoader;") from))]
         [prop (lambda (k) (let ([v ((ffi-static "java/lang/System" "getProperty(Ljava/lang/String;)Ljava/lang/String;") (jstring k))])
                             (if v (jstring->scheme v) "null")))]
         [by-name (lambda (libs)
                    ((jvirt libs "loadLibrary(Ljava/lang/Class;Ljava/lang/String;)Ljdk/internal/loader/NativeLibrary;") libs from name))])
    (if (not loader)
        (or (by-name ((ffi-static "jdk/internal/loader/BootLoader" "getNativeLibraries()Ljdk/internal/loader/NativeLibraries;")))
            (jthrow "java/lang/UnsatisfiedLinkError"
                    (string-append "no " n " in system library path: " (prop "sun.boot.library.path"))))
        (let ([libs (jslot loader (jfield-index "java/lang/ClassLoader" "libraries"))]
              [file ((jvirt loader "findLibrary(Ljava/lang/String;)Ljava/lang/String;") loader name)])
          (if file
              (let ([f (begin (jinit "java/io/File") (jnew "java/io/File"))])
                ((method-proc "java/io/File" "<init>(Ljava/lang/String;)V") f file)
                (when (eqv? ((jvirt f "isAbsolute()Z") f) 0)
                  (jthrow "java/lang/UnsatisfiedLinkError"
                          (string-append "ClassLoader.findLibrary failed to return an absolute path: " (jstring->scheme file))))
                (or ((jvirt libs "loadLibrary(Ljava/lang/Class;Ljava/io/File;)Ljdk/internal/loader/NativeLibrary;") libs from f)
                    (jthrow "java/lang/UnsatisfiedLinkError" (string-append "Can't load " (jstring->scheme file)))))
              (or (by-name libs)
                  (jthrow "java/lang/UnsatisfiedLinkError"
                          (string-append "no " n " in java.library.path: " (prop "java.library.path")))))))))
(defnative "jdk/internal/loader/NativeLibraries.unload(Ljava/lang/String;ZJ)V" (name builtin? h)
  (unless (eqv? h 0) (jlib-dlclose h)))
(defnative "jdk/internal/loader/RawNativeLibraries.load0(Ljdk/internal/loader/RawNativeLibraries$RawNativeLibraryImpl;Ljava/lang/String;)Z"
  (impl name)
  (if (eqv? (ffi-open! impl "jdk/internal/loader/RawNativeLibraries$RawNativeLibraryImpl" name) 0) 0 1))
(defnative "jdk/internal/loader/RawNativeLibraries.unload0(Ljava/lang/String;J)V" (name h)
  (unless (eqv? h 0) (jlib-dlclose h)))
(defnative "jdk/internal/loader/NativeLibrary.findEntry0(JLjava/lang/String;)J" (h name)
  (jlib-dlsym h (jstring->scheme (nn name))))

;; --- downcalls ---------------------------------------------------------------------
(defnative "jdk/internal/vm/ForeignLinkerSupport.isSupported0()Z" () 1)

(define ms-desc "Ljava/lang/foreign/MemorySegment;")

;; a carrier's Chez foreign type
(define (ffi-type d)
  (case (string-ref d 0)
    [(#\I) 'integer-32] [(#\J) 'integer-64] [(#\S) 'integer-16] [(#\B) 'integer-8]
    [(#\C) 'unsigned-16] [(#\Z) 'unsigned-8] [(#\F) 'float] [(#\D) 'double]
    [(#\V) 'void] [else 'uptr]))

;; foreign-procedure makers, one per signature: (maker address) is the
;; procedure that calls the function at address. A struct argument is given
;; as the address of a native copy, a struct result as the address to store
;; it at, first; the maker wraps those as ftype pointers.
(define ffi-makers (make-hashtable equal-hash equal?))
(define (ffi-maker types ret variadic safe?)
  (let ([key (list types ret variadic safe?)])
    (or (hashtable-ref ffi-makers key #f)
        (let* ([xs (map (lambda (t) (gensym "x")) types)]
               [wrap (lambda (t x) (if (pair? t) `(make-ftype-pointer ,(cadr t) ,x) x))]
               [rp (gensym "r")]
               [m (eval `(lambda (a)
                           (let ([f (foreign-procedure ,@(if safe? '(__collect_safe) '())
                                                       ,@(if variadic `((__varargs_after ,variadic)) '())
                                                       a ,types ,ret)])
                             ,(if (pair? ret)
                                  `(lambda (,rp ,@xs) (f ,(wrap ret rp) ,@(map wrap types xs)))
                                  `(lambda ,xs (f ,@(map wrap types xs)))))))])
          (hashtable-set! ffi-makers key m)
          m))))

;; --- struct layouts as ftypes ------------------------------------------------------
;; A group layout is defined once as an ftype of the same shape: a struct's
;; padding is dropped where the next member's alignment (or, at the end, the
;; struct's) would put it anyway, since the ABIs classify a struct by its
;; members and a padding byte would read as an integer member; any other
;; padding stays as bytes.
(define (layout-is? l cname) (class-assignable? (jclass-cname (jobj-cls l)) cname))
(define (layout-group? l) (layout-is? l "java/lang/foreign/GroupLayout"))
(define (layout-size l) ((jvirt l "byteSize()J") l))
(define (layout-align l) ((jvirt l "byteAlignment()J") l))
(define (layout-members l)
  (let ([ms ((jvirt l "memberLayouts()Ljava/util/List;") l)])
    (vector->list (jarr-vec ((jvirt ms "toArray()[Ljava/lang/Object;") ms)))))
(define (round-up n a) (* a (quotient (+ n a -1) a)))

(define (layout-ftype l)
  (cond
    [(layout-is? l "java/lang/foreign/SequenceLayout")
     `(array ,((jvirt l "elementCount()J") l)
             ,(layout-ftype ((jvirt l "elementLayout()Ljava/lang/foreign/MemoryLayout;") l)))]
    [(layout-is? l "java/lang/foreign/UnionLayout")
     `(union ,@(let loop ([ms (layout-members l)] [i 0])
                 (cond [(null? ms) '()]
                       [(layout-is? (car ms) "java/lang/foreign/PaddingLayout")
                        (cons `(,(string->symbol (format "f~a" i)) (array ,(layout-size (car ms)) unsigned-8))
                              (loop (cdr ms) (fx+ i 1)))]
                       [else (cons `(,(string->symbol (format "f~a" i)) ,(layout-ftype (car ms)))
                                   (loop (cdr ms) (fx+ i 1)))])))]
    [(layout-group? l)
     (let ([total (layout-size l)] [align (layout-align l)])
       `(struct
          ,@(let loop ([ms (layout-members l)] [off 0] [i 0])
              (cond
                [(null? ms) '()]
                [(layout-is? (car ms) "java/lang/foreign/PaddingLayout")
                 (let* ([n (layout-size (car ms))] [next (+ off n)])
                   (if (= next (if (null? (cdr ms)) (round-up off align) (round-up off (layout-align (cadr ms)))))
                       (loop (cdr ms) next i)
                       (cons `(,(string->symbol (format "f~a" i)) (array ,n unsigned-8))
                             (loop (cdr ms) next (fx+ i 1)))))]
                [else
                 (cons `(,(string->symbol (format "f~a" i)) ,(layout-ftype (car ms)))
                       (loop (cdr ms) (+ off (layout-size (car ms))) (fx+ i 1)))]))))]
    [else
     (let ([d (mirror->desc ((jvirt l "carrier()Ljava/lang/Class;") l))])
       (case (string-ref d 0)
         [(#\I) 'integer-32] [(#\J) 'integer-64] [(#\S) 'integer-16] [(#\B) 'integer-8]
         [(#\C) 'unsigned-16] [(#\Z) 'unsigned-8] [(#\F) 'float] [(#\D) 'double]
         [else 'uptr]))]))

(define ffi-ftypes (make-hashtable equal-hash equal?))
;; the name of an ftype for group layout l, defined on first use
(define (struct-ftype l)
  (let ([ft (layout-ftype l)])
    (or (hashtable-ref ffi-ftypes ft #f)
        (let ([name (string->symbol (format "champaca-ffi-struct-~a" (hashtable-size ffi-ftypes)))])
          (eval `(define-ftype ,name ,ft))
          (unless (= (eval `(ftype-sizeof ,name)) (layout-size l))
            (jthrow "java/lang/UnsupportedOperationException"
                    (format "champaca: struct layout ~a doesn't map to a C struct" (jstring-of l))))
          (hashtable-set! ffi-ftypes ft name)
          name))))

;; A segment's session and a native segment's address are read from their
;; fields (AbstractMemorySegmentImpl.scope, NativeMemorySegmentImpl.min);
;; the virtual calls cost more than the C call.
(define seg-fields #f)   ; (scope-index . min-index), once the classes exist
(define (seg-field-indexes)
  (or seg-fields
      (begin
        (set! seg-fields (cons (jfield-index "jdk/internal/foreign/AbstractMemorySegmentImpl" "scope")
                               (jfield-index "jdk/internal/foreign/NativeMemorySegmentImpl" "min")))
        seg-fields)))
(define (seg-session s) (jslot s (car (seg-field-indexes))))
(define (seg-native? s)
  (class-assignable? (jclass-cname (jobj-cls s)) "jdk/internal/foreign/NativeMemorySegmentImpl"))
(define (global-session? ss)
  (string=? (jclass-cname (jobj-cls ss)) "jdk/internal/foreign/GlobalSession"))
(define (seg-size s) ((jvirt s "byteSize()J") s))
(define (seg-address s) ((jvirt s "address()J") s))

(define (ffi-static cname key)
  (jinit cname)
  (method-proc cname key))

;; Chez Scheme 10.4.1 lays out macOS arm64 stack arguments wrongly for a
;; struct of 16 bytes or less that isn't all floats or all doubles: Apple's
;; ABI packs only scalars at their natural alignment, and such a struct still
;; starts on an 8-byte boundary and takes a multiple of 8 bytes, as in
;; standard AAPCS64, where Chez packs it like a scalar (measured against
;; clang). The callee would read the wrong bytes, so a signature where that
;; changes anything is refused. Variadic arguments are placed right (8-byte
;; slots, measured). Linux arm64 and x86_64 aren't affected: Chez gives
;; every stack argument there a multiple of 8 bytes, as their ABIs do.
(define ffi-macos-arm64? (memq (machine-type) '(arm64osx tarm64osx)))

(define (layout-leaves l)
  (cond [(layout-is? l "java/lang/foreign/PaddingLayout") '()]
        [(layout-is? l "java/lang/foreign/SequenceLayout")
         (let ([e (layout-leaves ((jvirt l "elementLayout()Ljava/lang/foreign/MemoryLayout;") l))])
           (apply append (make-list ((jvirt l "elementCount()J") l) e)))]
        [(layout-group? l) (apply append (map layout-leaves (layout-members l)))]
        [else (let ([d (mirror->desc ((jvirt l "carrier()Ljava/lang/Class;") l))])
                (list (case (string-ref d 0) [(#\F) 'float] [(#\D) 'double] [else 'other])))]))

(define (hfa-of l)   ; the member type of a homogeneous float aggregate, or #f
  (let ([ls (layout-leaves l)])
    (and (pair? ls) (fx<= (length ls) 4) (memq (car ls) '(float double))
         (for-all (lambda (x) (eq? x (car ls))) ls)
         (car ls))))

(define (chez-misplaces? layouts variadic)
  (and ffi-macos-arm64?
       (let loop ([ls layouts] [i 0] [gp 0] [fp 0] [off 0] [ragged #f])
         (define (on-stack! size align)
           ;; a ragged struct before this stack argument is misplaced by Chez
           (or ragged (loop (cdr ls) (fx+ i 1) gp fp (+ (round-up off align) size) #f)))
         (cond
           [(null? ls) #f]
           [(and variadic (fx>= i variadic))   ; macOS variadics: 8-byte slots, Chez agrees
            (if ragged #t (loop (cdr ls) (fx+ i 1) gp fp (+ (round-up off 8) 8) #f))]
           [else
            (let* ([l (car ls)] [size (layout-size l)])
              (cond
                [(not (layout-group? l))
                 (let ([float? (memq (car (layout-leaves l)) '(float double))])
                   (cond [(and float? (fx< fp 8)) (loop (cdr ls) (fx+ i 1) gp (fx+ fp 1) off ragged)]
                         [(and (not float?) (fx< gp 8)) (loop (cdr ls) (fx+ i 1) (fx+ gp 1) fp off ragged)]
                         [else (on-stack! size size)]))]
                [(hfa-of l)
                 => (lambda (t)
                      (let ([n (length (layout-leaves l))])
                        (if (fx<= (fx+ fp n) 8)
                            (loop (cdr ls) (fx+ i 1) gp (fx+ fp n) off ragged)
                            (let ([m (if (eq? t 'float) 4 8)])
                              (if ragged #t (loop (cdr ls) (fx+ i 1) gp 8 (+ (round-up off m) size) #f))))))]
                [(> size 16)   ; passed as a pointer
                 (if (fx< gp 8) (loop (cdr ls) (fx+ i 1) (fx+ gp 1) fp off ragged) (on-stack! 8 8))]
                [else
                 (let ([words (quotient (+ size 7) 8)])
                   (if (fx<= (fx+ gp words) 8)
                       (loop (cdr ls) (fx+ i 1) (fx+ gp words) fp off ragged)
                       (cond [ragged #t]
                             [(not (= 0 (modulo off 8))) #t]
                             [else (loop (cdr ls) (fx+ i 1) 8 fp (+ off size) (not (= 0 (modulo size 8))))])))]))]))))

;; a native buffer of n bytes, and a segment over it for the JDK's copy
(define (native-temp n) (foreign-alloc (max n 1)))
(define (temp-segment p n)
  ((ffi-static "jdk/internal/foreign/Utils" "longToAddress(JJJ)Ljava/lang/foreign/MemorySegment;") p n 1))
(define ffi-memcpy (foreign-procedure "memcpy" (uptr uptr size_t) uptr))

;; gil-released for a C call: the thunk can't raise (its arguments are
;; numbers already), so there is no guard, and the condition is broadcast
;; only when a thread waits on it (one that starts waiting later sees the
;; GIL free before it waits).
(define (ffi-released thunk)
  (let ([s (self)])
    (gil-save! s)
    (mutex-acquire gil)
    (set! gil-owner #f)
    (when (fx> gil-blocked 0) (condition-broadcast gil-cv))
    (mutex-release gil)
    (let ([r (thunk)])
      (mutex-acquire gil)
      (gil-wait! s (lambda () #t) #f)
      (mutex-release gil)
      (gil-restore! s)
      r)))
(define (seg-copy! from from-off to to-off n)
  ((ffi-static "java/lang/foreign/MemorySegment"
               "copy(Ljava/lang/foreign/MemorySegment;JLjava/lang/foreign/MemorySegment;JJ)V")
   from from-off to to-off n))

(define (ffi-arrange mt fd options)
  (let* ([d (mt-desc mt)]
         [params (desc-args d)]
         [ret (desc-ret d)]
         [opt? (lambda (key) (not (eqv? ((jvirt options key) options) 0)))]
         [critical? (opt? "isCritical()Z")]
         [heap? (opt? "allowsHeapAccess()Z")]
         [capture? (opt? "hasCapturedCallState()Z")]
         [variadic (and (opt? "isVariadicFunction()Z")
                        ((jvirt options "firstVariadicArgIndex()I") options))]
         [layouts (let ([l ((jvirt fd "argumentLayouts()Ljava/util/List;") fd)])
                    (vector->list (jarr-vec ((jvirt l "toArray()[Ljava/lang/Object;") l))))]
         [ret-layout (let ([o ((jvirt fd "returnLayout()Ljava/util/Optional;") fd)])
                       (and (eqv? ((jvirt o "isPresent()Z") o) 1) ((jvirt o "get()Ljava/lang/Object;") o)))]
         ;; each argument: val, seg (an address), or a group layout's size
         [kinds (list->vector (map (lambda (p l) (cond [(layout-group? l) (layout-size l)]
                                                        [(string=? p ms-desc) 'seg]
                                                        [else 'val]))
                                   params layouts))]
         [types (map (lambda (p l) (if (layout-group? l) `(& ,(struct-ftype l)) (ffi-type p))) params layouts)]
         [ret-struct (and ret-layout (layout-group? ret-layout) ret-layout)]
         [_ (when (chez-misplaces? layouts variadic)
              (jthrow "java/lang/UnsupportedOperationException"
                      (string-append "champaca: Chez Scheme misplaces a struct passed on the stack here on macOS arm64: "
                                     (jstring-of fd))))]
         [maker (ffi-maker types (if ret-struct `(& ,(struct-ftype ret-struct)) (ffi-type ret)) variadic (not critical?))]
         [ret-address (and (not ret-struct) (string=? ret ms-desc)
                           (let ([f (ffi-static "jdk/internal/foreign/Utils"
                                                "longToAddress(JLjava/lang/foreign/AddressLayout;)Ljava/lang/foreign/MemorySegment;")])
                             (lambda (a) (f a ret-layout))))]
         [unbox-java (ffi-static "jdk/internal/foreign/abi/SharedUtils" "unboxSegment(Ljava/lang/foreign/MemorySegment;)J")]
         [unbox (lambda (s) (if (seg-native? s) (jslot s (cdr (seg-field-indexes))) (unbox-java s)))]
         [set-int (lambda (s v)
                    (jinit "java/lang/foreign/ValueLayout")
                    ((jvirt s "set(Ljava/lang/foreign/ValueLayout$OfInt;JI)V") s
                     (top-level-value (string->symbol "java/lang/foreign/ValueLayout:JAVA_INT")) 0 v))]
         [last (cons #f #f)]
         [desc (string-append "(" ms-desc "Ljava/lang/foreign/SegmentAllocator;"
                              (if capture? ms-desc "") (apply string-append params) ")" ret)])
    (make-mh desc
      (lambda all
        (let* ([target (nn (car all))]
               [alloc (cadr all)]
               [cap (and capture? (nn (caddr all)))]
               [args (if capture? (cdddr all) (cddr all))]
               [sessions '()]
               [temps '()]        ; native buffers, freed when the call ends
               [heap-copies '()]  ; (segment . temp) to copy back after the call
               [acquire! (lambda (s)
                           (let ([ss (seg-session s)])
                             (unless (or (global-session? ss) (memq ss sessions))
                               ((jvirt ss "acquire0()V") ss)
                               (set! sessions (cons ss sessions)))))]
               [temp! (lambda (n) (let ([p (native-temp n)]) (set! temps (cons p temps)) p))])
          (dynamic-wind
            void
            (lambda ()
              (acquire! target)
              (when cap (acquire! cap))
              (let* ([addr (unbox target)]
                     [raw (let loop ([as args] [i 0] [acc '()])
                            (if (null? as)
                                (reverse acc)
                                (let ([a (car as)] [k (vector-ref kinds i)])
                                  (loop (cdr as) (fx+ i 1)
                                        (cons (cond
                                                [(eq? k 'val) a]
                                                [(eq? k 'seg)
                                                 (let ([s (nn a)])
                                                   (acquire! s)
                                                   (if (and heap? (not (seg-native? s)))
                                                       (let* ([n (seg-size s)] [p (temp! n)] [ts (temp-segment p n)])
                                                         (seg-copy! s 0 ts 0 n)
                                                         (set! heap-copies (cons (cons s ts) heap-copies))
                                                         p)
                                                       (unbox s)))]
                                                [else
                                                 ;; a struct by value: a native segment's own
                                                 ;; bytes, else a native copy (the JDK's copy
                                                 ;; throws for a segment too small)
                                                 (let ([s (nn a)])
                                                   (acquire! s)
                                                   (if (and (seg-native? s) (>= (seg-size s) k))
                                                       (unbox s)
                                                       (let ([p (temp! k)])
                                                         (seg-copy! s 0 (temp-segment p k) 0 k)
                                                         p)))])
                                              acc)))))]
                     [rt (and ret-struct (temp! (layout-size ret-struct)))]
                     [f (if (eqv? (car last) addr)
                            (cdr last)
                            (let ([f (maker addr)]) (set! last (cons addr f)) f))]
                     [call (lambda ()
                             (let ([r (if rt (apply f rt raw) (apply f raw))])
                               (if cap (cons r (errno)) r)))]
                     ;; the GIL is handed off only when another Java thread
                     ;; could want it while the call runs in C
                     [r+e (if (or critical? (null? java-live-threads)) (call) (ffi-released call))]
                     [r (if cap (car r+e) r+e)])
                (when cap (set-int cap (cdr r+e)))
                (for-each (lambda (s+t)
                            (when (eqv? ((jvirt (car s+t) "isReadOnly()Z") (car s+t)) 0)
                              (seg-copy! (cdr s+t) 0 (car s+t) 0 (seg-size (car s+t)))))
                          heap-copies)
                (cond
                  [rt (let ([out ((jvirt alloc "allocate(Ljava/lang/foreign/MemoryLayout;)Ljava/lang/foreign/MemorySegment;")
                                  alloc ret-struct)]
                            [n (layout-size ret-struct)])
                        (if (and (seg-native? out) (>= (seg-size out) n) (eqv? ((jvirt out "isReadOnly()Z") out) 0))
                            (ffi-memcpy (unbox out) rt n)
                            (seg-copy! (temp-segment rt n) 0 out 0 n))
                        out)]
                  [else
                   (case (string-ref ret 0)
                     [(#\Z) (if (eqv? r 0) 0 1)]
                     [(#\V) (void)]
                     [else (if ret-address (ret-address r) r)])])))
            (lambda ()
              (for-each foreign-free temps)
              (set! temps '())
              (for-each (lambda (ss) ((jvirt ss "release0()V") ss)) sessions)
              (set! sessions '())))))
      #f)))

(for-each
  (lambda (cname)
    (define-override! (string-append cname ".arrangeDowncall(Ljava/lang/invoke/MethodType;Ljava/lang/foreign/FunctionDescriptor;Ljdk/internal/foreign/abi/LinkerOptions;)Ljava/lang/invoke/MethodHandle;")
      (lambda (this mt fd options) (ffi-arrange mt fd options))))
  '("jdk/internal/foreign/abi/aarch64/macos/MacOsAArch64Linker"
    "jdk/internal/foreign/abi/aarch64/linux/LinuxAArch64Linker"
    "jdk/internal/foreign/abi/x64/sysv/SysVx64Linker"))
