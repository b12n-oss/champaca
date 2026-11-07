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
(define jlib-dlerror (foreign-procedure "dlerror" () string))

(define (ffi-open! impl cname name)
  (let ([h (jlib-dlopen (jstring->scheme (nn name)) RTLD_LAZY)])
    (unless (eqv? h 0) (jslot-set! impl (jfield-index cname "handle") h))
    h))

;; NativeLibraries loads the system lookup's libsyslookup and any library
;; System.loadLibrary or System.load names that the JDK doesn't ship (see
;; java-load-library). HotSpot's text for a library that fails to open is
;; "<name>: <dlerror's text>". HotSpot runs a library's JNI_OnLoad, which needs
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
           (jthrow "java/lang/UnsatisfiedLinkError"
                   (string-append (jstring->scheme name) ": " (or (jlib-dlerror) "dlopen failed"))))]
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
         ;; StaticProperty's values, read at startup, as the JDK's texts use
         [prop (lambda (m) (let ([v ((ffi-static "jdk/internal/util/StaticProperty" (string-append m "()Ljava/lang/String;")))])
                             (if v (jstring->scheme v) "null")))]
         [by-name (lambda (libs)
                    ((jvirt libs "loadLibrary(Ljava/lang/Class;Ljava/lang/String;)Ljdk/internal/loader/NativeLibrary;") libs from name))])
    (if (not loader)
        (or (by-name ((ffi-static "jdk/internal/loader/BootLoader" "getNativeLibraries()Ljdk/internal/loader/NativeLibraries;")))
            (jthrow "java/lang/UnsatisfiedLinkError"
                    (string-append "no " n " in system library path: " (prop "sunBootLibraryPath"))))
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
                          (string-append "no " n " in java.library.path: " (prop "javaLibraryPath")))))))))
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

;; eval for the generated forms: Chez refuses some signatures only when it
;; compiles the foreign-procedure (a struct over 16 bytes passed on the
;; stack, measured on macOS arm64: "unexpected Triv"), and that must reach
;; Java as an exception, not end the process
(define (ffi-eval form)
  (guard (e [(and (not (jobj? e)) (condition? e))
             (jthrow "java/lang/UnsupportedOperationException"
                     (string-append "champaca: Chez Scheme can't make this foreign call: "
                                    (call-with-string-output-port (lambda (p) (display-condition e p)))))])
    (eval form)))

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
               [m (ffi-eval `(lambda (a)
                           (let ([f (foreign-procedure ,@(if safe? '(__collect_safe) '())
                                                       ,@(if variadic `((__varargs_after ,variadic)) '())
                                                       a ,types ,ret)])
                             ,(if (pair? ret)
                                  `(lambda (,rp ,@xs) (f ,(wrap ret rp) ,@(map wrap types xs)))
                                  `(lambda ,xs (f ,@(map wrap types xs)))))))])
          (hashtable-set! ffi-makers key m)
          m))))

;; --- struct layouts as ftypes ------------------------------------------------------
;; A group layout is defined once as an ftype of the same shape. A struct's
;; padding is dropped: the linker accepts only the padding natural alignment
;; needs (AbstractLinker.checkLayouts refuses a member at any other offset),
;; which Chez puts back, and the ABIs classify a struct by its members, so a
;; padding byte would read as an integer member. struct-ftype checks the
;; size all the same. A union's padding, which only makes it larger, stays as
;; bytes.
(define (layout-is? l cname) (class-assignable? (jclass-cname (jobj-cls l)) cname))
(define (layout-group? l) (layout-is? l "java/lang/foreign/GroupLayout"))
(define (layout-size l) ((jvirt l "byteSize()J") l))
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
     `(struct
        ,@(let loop ([ms (layout-members l)] [i 0])
            (cond
              [(null? ms) '()]
              [(layout-is? (car ms) "java/lang/foreign/PaddingLayout") (loop (cdr ms) i)]
              [else
               (cons `(,(string->symbol (format "f~a" i)) ,(layout-ftype (car ms)))
                     (loop (cdr ms) (fx+ i 1)))])))]
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
          (ffi-eval `(define-ftype ,name ,ft))
          (unless (= (ffi-eval `(ftype-sizeof ,name)) (layout-size l))
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

;; Chez Scheme 10.4.1 places some macOS arm64 stack arguments where Apple's
;; ABI doesn't (measured against clang). Apple packs a scalar or a float or
;; double aggregate (HFA) at its natural alignment, but a struct of 16 bytes
;; or less that isn't an HFA starts on an 8-byte boundary and takes a
;; multiple of 8 bytes, as in standard AAPCS64, where Chez packs it like a
;; scalar. And Chez pads before a stack argument by its alignment minus the
;; previous end's offset mod 8, which goes negative when that offset is the
;; larger (an HFA of 3 floats, a char, then another HFA: the second starts
;; at 13, not 16; three chars and a short: the short at 2). chez-misplaces?
;; places each stack argument both ways and refuses a signature where any
;; starts differ. Registers are assigned alike by both; variadic arguments
;; take 8-byte slots in both. Linux arm64 and x86_64 aren't affected: Chez
;; gives every stack argument there a multiple of 8 bytes, as their ABIs do.
(define ffi-macos-arm64? (memq (machine-type) '(arm64osx tarm64osx)))

;; a layout's scalar leaves as (kind size . offset), kind float, double or
;; other, with a union's members at one offset and duplicates dropped, as
;; Chez's filter-union drops them
(define (layout-leaves l)
  (let walk ([l l] [base 0])
    (cond [(layout-is? l "java/lang/foreign/PaddingLayout") '()]
          [(layout-is? l "java/lang/foreign/SequenceLayout")
           (let ([e ((jvirt l "elementLayout()Ljava/lang/foreign/MemoryLayout;") l)])
             (let loop ([k 0])
               (if (= k ((jvirt l "elementCount()J") l))
                   '()
                   (append (walk e (+ base (* k (layout-size e)))) (loop (+ k 1))))))]
          [(layout-is? l "java/lang/foreign/UnionLayout")
           (fold-left (lambda (acc m) (append acc (remove-dups acc (walk m base)))) '() (layout-members l))]
          [(layout-group? l)
           (let loop ([ms (layout-members l)] [off base])
             (if (null? ms)
                 '()
                 (append (walk (car ms) off) (loop (cdr ms) (+ off (layout-size (car ms)))))))]
          [else (let ([d (mirror->desc ((jvirt l "carrier()Ljava/lang/Class;") l))])
                  (list (cons* (case (string-ref d 0) [(#\F) 'float] [(#\D) 'double] [else 'other])
                               (layout-size l) base)))])))
(define (remove-dups seen xs) (filter (lambda (x) (not (member x seen))) xs))

(define (hfa-of l)   ; an HFA's member kind, float or double, or #f
  (let ([ls (layout-leaves l)])
    (and (pair? ls) (fx<= (length ls) 4) (memq (caar ls) '(float double))
         (for-all (lambda (x) (eq? (car x) (caar ls))) ls)
         (caar ls))))

(define (chez-misplaces? layouts variadic)
  (and ffi-macos-arm64?
       ;; each stack argument as (apple-align apple-size chez-align chez-size)
       (let loop ([ls layouts] [i 0] [gp 0] [fp 0] [stack '()])
         (if (null? ls)
             (stack-starts-differ? (reverse stack))
             (let* ([l (car ls)] [size (layout-size l)] [group? (layout-group? l)]
                    [hfa (and group? (hfa-of l))]
                    [next (lambda (gp fp entry) (loop (cdr ls) (fx+ i 1) gp fp (if entry (cons entry stack) stack)))])
               (cond
                 [(and variadic (fx>= i variadic))
                  (let ([n (if (and group? (> size 16)) 8 (round-up size 8))])
                    (next gp fp (list 8 n 8 n)))]
                 [(not group?)
                  (if (memq (car (car (layout-leaves l))) '(float double))
                      (if (fx< fp 8) (next gp (fx+ fp 1) #f) (next gp fp (list size size size size)))
                      (if (fx< gp 8) (next (fx+ gp 1) fp #f) (next gp fp (list size size size size))))]
                 [hfa
                  (let ([n (length (layout-leaves l))] [m (if (eq? hfa 'float) 4 8)])
                    (if (fx<= (fx+ fp n) 8) (next gp (fx+ fp n) #f) (next gp 8 (list m size m size))))]
                 [(> size 16)   ; passed as a pointer
                  (if (fx< gp 8) (next (fx+ gp 1) fp #f) (next gp fp (list 8 8 8 8)))]
                 [else
                  (let ([words (quotient (+ size 7) 8)])
                    (if (fx<= (fx+ gp words) 8)
                        (next (fx+ gp words) fp #f)
                        (next 8 fp (list 8 (round-up size 8) (struct-align l) size))))]))))))

(define (struct-align l)
  (fold-left (lambda (a x) (max a (cadr x))) 1 (layout-leaves l)))

;; Apple's start for each: the end so far rounded up to its alignment.
;; Chez's: the end so far plus (align - end mod 8) when end mod 8 isn't a
;; multiple of align (s/arm64.ss alignment-via-lookahead).
(define (stack-starts-differ? entries)
  (let loop ([es entries] [apple 0] [chez 0] [first #t])
    (and (pair? es)
         (let* ([e (car es)]
                [a-start (round-up apple (car e))]
                [c-align (caddr e)]
                [c-start (if (or first (= 0 (modulo (modulo chez 8) c-align)))
                             chez
                             (+ chez (- c-align (modulo chez 8))))])
           (or (not (= a-start c-start))
               (loop (cdr es) (+ a-start (cadr e)) (+ c-start (cadddr e)) #f))))))

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

;; the JDK's own CallArranger.getBindings for this platform, run for its
;; checks alone: they are what HotSpot's arrangeDowncall does before it
;; generates code (a struct of size zero, say, is "Stack must be empty after
;; recipe")
(define (ffi-validate! mt fd options)
  (case (machine-type)
    [(arm64osx tarm64osx arm64le tarm64le)
     (let* ([c "jdk/internal/foreign/abi/aarch64/CallArranger"]
            [_ (jinit c)]
            [arranger (top-level-value (string->symbol (string-append c (if ffi-macos-arm64? ":MACOS" ":LINUX"))))])
       ((jvirt arranger "getBindings(Ljava/lang/invoke/MethodType;Ljava/lang/foreign/FunctionDescriptor;ZLjdk/internal/foreign/abi/LinkerOptions;)Ljdk/internal/foreign/abi/aarch64/CallArranger$Bindings;")
        arranger mt fd 0 options))]
    [else
     ((ffi-static "jdk/internal/foreign/abi/x64/sysv/CallArranger"
                  "getBindings(Ljava/lang/invoke/MethodType;Ljava/lang/foreign/FunctionDescriptor;ZLjdk/internal/foreign/abi/LinkerOptions;)Ljdk/internal/foreign/abi/x64/sysv/CallArranger$Bindings;")
      mt fd 0 options)]))

;; an address as the signed long Java sees (a uptr result is unsigned)
(define (signed-address a) (if (> a #x7fffffffffffffff) (- a (expt 2 64)) a))

(define (ffi-arrange mt fd options)
  (ffi-validate! mt fd options)
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
                      (string-append "champaca: Chez Scheme would put an argument passed on the stack in the wrong place here on macOS arm64: "
                                     (jstring-of fd))))]
         [maker (ffi-maker types (if ret-struct `(& ,(struct-ftype ret-struct)) (ffi-type ret)) variadic (not critical?))]
         [ret-address (and (not ret-struct) (string=? ret ms-desc)
                           (let ([f (ffi-static "jdk/internal/foreign/Utils"
                                                "longToAddress(JLjava/lang/foreign/AddressLayout;)Ljava/lang/foreign/MemorySegment;")])
                             (lambda (a) (f (signed-address a) ret-layout))))]
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
                     ;; (or a thread C started, through an upcall stub)
                     [r+e (if (or critical? (and (null? java-live-threads) (fx= (hashtable-size upcall-codes) 0)))
                              (call)
                              (ffi-released call))]
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

;; --- upcalls ------------------------------------------------------------------------
;; Linker.upcallStub runs the JDK's own Java: the checks, then the platform
;; linker's arrangeUpcall and UpcallLinker.makeFactory, which wrap the target
;; in the bindings for the calling sequence (a segment from an address, a
;; struct from its registers, an escaping exception through
;; SharedUtils.handleUncaughtException, which ends the VM) and leave a handle
;; that takes and returns primitives in the order the ABI passes them, an
;; address as a long. makeUpcallStub turns that handle into a C function: a
;; Chez foreign-callable of the same primitive types, its code object locked
;; so the collector neither moves nor frees it, its entry point the stub.
;; UpcallStubs.makeUpcall ties the stub to the arena, whose close frees it
;; (freeUpcallStub0). __collect_safe, since a downcall that may upcall runs
;; collect-safe too (ffi-maker), and the callable reactivates the thread.
(define upcall-codes (make-eqv-hashtable))   ; entry point -> locked code
(define upcall-makers (make-hashtable equal-hash equal?))
(define (upcall-maker types ret)
  (let ([key (cons types ret)])
    (or (hashtable-ref upcall-makers key #f)
        (let ([m (ffi-eval `(lambda (p) (foreign-callable __collect_safe p ,types ,ret)))])
          (hashtable-set! upcall-makers key m)
          m))))
;; Java code needs the GIL. An upcall on the thread that holds it (the
;; downcall kept it) runs as it is; one from inside a downcall that gave it up
;; takes it back for the call and gives it up after, as the downcall left it.
(define (upcall-take! s)
  (jthread-state-tid-set! s (get-thread-id))
  (self s)
  (with-mutex gil (gil-wait! s (lambda () #t) #f))
  (gil-restore! s))
(define (upcall-give! s)
  (gil-save! s)
  (with-mutex gil
    (set! gil-owner #f)
    (condition-broadcast gil-cv)))

;; A thread C started gets one java.lang.Thread for its life, as HotSpot
;; attaches it (AttachCurrentThreadAsDaemon): Thread-N in group main, a
;; daemon, live and listed until the C thread ends, when a pthread key's
;; destructor ends it through Thread.exit. Chez makes each upcall from such a
;; thread a new Chez thread, so its state is found by pthread_self; the table
;; is read and written under the gil mutex.
(define upcall-threads (make-eqv-hashtable))   ; pthread_self -> jthread-state
(define upcall-key #f)
(define upcall-key-code #f)
(define (upcall-thread-ended! value)
  (let* ([p (thread-pthread-self)]
         [s (with-mutex gil
              (let ([s (hashtable-ref upcall-threads p #f)])
                (hashtable-delete! upcall-threads p)
                s))])
    (when s
      (upcall-take! s)
      (let ([t (jthread-state-jthread s)])
        (guard (e [(java-halt? e) (exit-process (java-halt-status e))])
          (thread-terminated! t (lambda () (set! java-live-threads (remq t java-live-threads))))))
      (upcall-give! s))))
;; the key, made once; pthread_key_t is an unsigned long on macOS, an
;; unsigned int on glibc
(define (upcall-key!)
  (or upcall-key
      (let ([code (foreign-callable __collect_safe upcall-thread-ended! (uptr) void)]
            [buf (foreign-alloc 8)])
        (lock-object code)
        (foreign-set! 'unsigned-64 buf 0 0)
        (unless (fx= ((foreign-procedure "pthread_key_create" (uptr uptr) int) buf (foreign-callable-entry-point code)) 0)
          (foreign-free buf)
          (jthrow "java/lang/InternalError" "champaca: pthread_key_create failed for an upcall thread"))
        (set! upcall-key-code code)
        (set! upcall-key (foreign-ref (if natives-macos? 'unsigned-64 'unsigned-32) buf 0))
        (foreign-free buf)
        upcall-key)))
(define (upcall-attach! p)
  ;; depth 1, as any Java thread's: a jolt fn the upcall reaches enters and
  ;; leaves the GIL re-entrantly (with-gil), which must not give it up
  (let ([s (make-jthread-state #f '() #f #f 1 (get-thread-id) 0)])
    (upcall-take! s)   ; the GIL serializes making the key
    (let ([t (jnew "java/lang/Thread")])
      (jthread-state-jthread-set! s t)
      (set! current-jthread t)
      (eq-hashtable-set! jthread-states t s)
      ;; this == currentThread(): the constructor's attaching case, so no
      ;; parent; a null name is Thread-N
      ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;)V") t main-group #f)
      ((jvirt t "setDaemon(Z)V") t 1)
      (note-native-thread! t)
      (thread-alive! t #t)
      (set! java-live-threads (cons t java-live-threads))
      (set! threads-started (fx+ threads-started 1))
      (set! threads-peak (fxmax threads-peak (fx+ 1 (length java-live-threads)))))
    (with-mutex gil (hashtable-set! upcall-threads p s))
    ((foreign-procedure "pthread_setspecific" (uptr uptr) int) (upcall-key!) 1)
    s))
(define (upcall-run thunk)
  (let* ([s0 (self)] [known (and s0 (eqv? (jthread-state-tid s0) (get-thread-id)))])
    (cond
     [(and known (eq? gil-owner s0)) (thunk)]
     [known
      ;; a raise (System.exit's java-halt) leaves with the GIL held, back
      ;; into the Java code below the downcall
      (upcall-take! s0)
      (let ([r (thunk)]) (upcall-give! s0) r)]
     [else
      ;; nothing Java runs below: System.exit ends the process here, as on
      ;; a thread Java started; anything else escaping (the JDK's wrapper
      ;; catches every Throwable, so a champaca error) can't go back to C
      ;; either, and would leave the GIL with a thread that's gone: report it
      ;; and end the process
      (guard (e [(java-halt? e) (exit-process (java-halt-status e))]
                [#t (upcall-failed e)])
        (let* ([p (thread-pthread-self)]
               [s (with-mutex gil (hashtable-ref upcall-threads p #f))]
               [s (if s (begin (upcall-take! s) s) (upcall-attach! p))]
               [r (thunk)])
          (upcall-give! s)
          r))])))
(define (upcall-failed e)
  (let ([p (current-error-port)])
    (display "champaca: an upcall on a thread C started failed: " p)
    (if (condition? e) (display-condition e p) (write e p))
    (newline p)
    (flush-output-port p)
    (exit-process 1)))
(defnative "jdk/internal/foreign/abi/UpcallLinker.registerNatives()V" () (void))
(defnative "jdk/internal/foreign/abi/UpcallStubs.registerNatives()V" () (void))
;; A struct result: the bindings hand the stub the address to put it at as
;; the handle's first argument. When the struct comes back in registers
;; (needs-return-buffer), that is a buffer holding each return register's
;; value in CallRegs.retRegs order, an integer register's slot 8 bytes and a
;; vector register's 16 (BindingSpecializer, typeSize); the callable's result
;; is then a struct of one 8-byte field per register, integer-64 or double,
;; which Chez classifies to the same registers on both ABIs (a float is a
;; double's low bits, as an s register is a d register's), and each slot's
;; first 8 bytes are copied into it. A struct returned in memory comes in x8
;; on arm64, which only a (& ftype) result reads: Chez passes that pointer on
;; as the callable's first argument (s/arm64.ss indirect-result?), so any
;; ftype too big for registers stands in for the real one. On x86_64 the
;; pointer is the first argument in rdi, an ordinary uptr.
(define (vm-storage-type st) (jslot st (jfield-index "jdk/internal/foreign/abi/VMStorage" "type")))
(define (vm-storage-index st) (jslot st (jfield-index "jdk/internal/foreign/abi/VMStorage" "indexOrOffset")))
(define (call-regs conv field)
  (vector->list (jarr-vec (jslot conv (jfield-index "jdk/internal/foreign/abi/UpcallLinker$CallRegs" field)))))
(define upcall-reg-ftypes (make-hashtable equal-hash equal?))
(define (upcall-reg-ftype kinds)   ; kinds: 'int or 'vec per return register
  (or (hashtable-ref upcall-reg-ftypes kinds #f)
      (let ([name (string->symbol (format "champaca-ffi-regs-~a" (hashtable-size upcall-reg-ftypes)))])
        (ffi-eval `(define-ftype ,name
                     (struct ,@(map (lambda (k i) `[,(string->symbol (format "r~a" i)) ,(if (eq? k 'int) 'integer-64 'double)])
                                    kinds (iota (length kinds))))))
        (hashtable-set! upcall-reg-ftypes kinds name)
        name)))
(define upcall-memory-ftype #f)
(define (upcall-memory-ftype!)
  (or upcall-memory-ftype
      (begin
        (ffi-eval '(define-ftype champaca-ffi-in-memory (struct [a integer-64] [b integer-64] [c integer-64])))
        (set! upcall-memory-ftype 'champaca-ffi-in-memory)
        upcall-memory-ftype)))
;; StorageType.INTEGER and VECTOR are 0 and 1 on both architectures
(define (upcall-stub-proc info conv needs-return-buffer return-buffer-size)
  (let* ([proc (mh-info-proc info)] [args (mh-info-args info)] [ret (mh-info-ret info)]
         [arg-regs (call-regs conv "argRegs")]
         [run (lambda (p) (lambda xs (upcall-run (lambda () (apply p xs)))))])
    (cond
     [(eqv? needs-return-buffer 1)
      (let* ([kinds (map (lambda (st) (if (fx= (vm-storage-type st) 0) 'int 'vec)) (call-regs conv "retRegs"))]
             [offsets (let loop ([ks kinds] [off 0] [acc '()])
                        (if (null? ks) (reverse acc) (loop (cdr ks) (+ off (if (eq? (car ks) 'int) 8 16)) (cons off acc))))]
             [size return-buffer-size])
        (unless (and (pair? kinds) (= (+ (car (last-pair offsets)) (if (eq? (car (last-pair kinds)) 'int) 8 16)) size)
                     (for-all (lambda (st) (memv (vm-storage-type st) '(0 1))) (call-regs conv "retRegs")))
          (jthrow "java/lang/UnsupportedOperationException"
                  (format "champaca: an upcall's return registers don't match its ~a-byte return buffer" size)))
        (values (map ffi-type (cdr args)) `(& ,(upcall-reg-ftype kinds))
                (run (lambda (rp . xs)
                       (let ([buf (foreign-alloc size)] [to (ftype-pointer-address rp)])
                         (do ([i 0 (fx+ i 8)]) ((>= i size)) (foreign-set! 'integer-64 buf i 0))
                         (guard (e [#t (foreign-free buf) (raise e)]) (apply proc buf xs))
                         (for-each (lambda (off i) (foreign-set! 'integer-64 to (fx* i 8) (foreign-ref 'integer-64 buf off)))
                                   offsets (iota (length offsets)))
                         (foreign-free buf))))))]
     [(and (memq (machine-type) '(arm64osx tarm64osx arm64le tarm64le)) (pair? arg-regs)
           (fx= (vm-storage-type (car arg-regs)) 0) (fx= (vm-storage-index (car arg-regs)) 8))
      (values (map ffi-type (cdr args)) `(& ,(upcall-memory-ftype!))
              (run (lambda (rp . xs) (apply proc (ftype-pointer-address rp) xs) (void))))]
     [else (values (map ffi-type args) (ffi-type ret) (run proc))])))
(defnative "jdk/internal/foreign/abi/UpcallLinker.makeUpcallStub(Ljava/lang/invoke/MethodHandle;Ljdk/internal/foreign/abi/ABIDescriptor;Ljdk/internal/foreign/abi/UpcallLinker$CallRegs;ZJ)J"
  (mh abi conv needs-return-buffer return-buffer-size)
  (let-values ([(types ret p) (upcall-stub-proc (mh-info-of mh) conv needs-return-buffer return-buffer-size)])
    (let ([code ((upcall-maker types ret) p)])
      (lock-object code)
      (let ([entry (foreign-callable-entry-point code)])
        (hashtable-set! upcall-codes entry code)
        entry))))
(defnative "jdk/internal/foreign/abi/UpcallStubs.freeUpcallStub0(J)Z" (addr)
  (let ([code (hashtable-ref upcall-codes addr #f)])
    (if code
        (begin (unlock-object code) (hashtable-delete! upcall-codes addr) 1)
        0)))
