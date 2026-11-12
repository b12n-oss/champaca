;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

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
;; take 8-byte slots in both. Linux arm64 isn't affected: Chez gives every
;; stack argument there a multiple of 8 bytes, as its ABI does. x86_64 places
;; them right but copies some structs over the next one (sysv-stack-overrun?).
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
             (let-values ([(gp fp entry) (arm64-place-arg (car ls) i gp fp variadic)])
               (loop (cdr ls) (fx+ i 1) gp fp (if entry (cons entry stack) stack)))))))

;; argument i's place given gp general and fp float registers taken so far:
;; (values gp fp entry), entry #f for a register, else the stack entry
;; (apple-align apple-size chez-align chez-size)
(define (arm64-place-arg l i gp fp variadic)
  (let* ([size (layout-size l)] [group? (layout-group? l)]
         [hfa (and group? (hfa-of l))])
    (cond
      [(and variadic (fx>= i variadic))
       (let ([n (if (and group? (> size 16)) 8 (round-up size 8))])
         (values gp fp (list 8 n 8 n)))]
      [(not group?)
       (if (memq (car (car (layout-leaves l))) '(float double))
           (if (fx< fp 8) (values gp (fx+ fp 1) #f) (values gp fp (list size size size size)))
           (if (fx< gp 8) (values (fx+ gp 1) fp #f) (values gp fp (list size size size size))))]
      [hfa
       (let ([n (length (layout-leaves l))] [m (if (eq? hfa 'float) 4 8)])
         (if (fx<= (fx+ fp n) 8) (values gp (fx+ fp n) #f) (values gp 8 (list m size m size))))]
      [(> size 16)   ; passed as a pointer
       (if (fx< gp 8) (values (fx+ gp 1) fp #f) (values gp fp (list 8 8 8 8)))]
      [else
       (let ([words (quotient (+ size 7) 8)])
         (if (fx<= (fx+ gp words) 8)
             (values (fx+ gp words) fp #f)
             (values 8 fp (list 8 (round-up size 8) (struct-align l) size))))])))

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

;; Chez Scheme's x86_64 System V backend (s/x86_64.ss load-content-stack)
;; copies a struct passed on the stack in 8-, 4-, 2- and 1-byte pieces but
;; stores each piece as a whole 8-byte word. For a size whose remainder mod 8
;; is 3, 5, 6 or 7 the last store runs 2 to 6 bytes past the struct's slot,
;; over the next stack argument (a short after a 3-byte struct read as 0) or
;; past the argument area. sysv-stack-overrun? finds such a struct, placing
;; arguments as Chez does: a struct goes on the stack when its eightbytes are
;; memory class (over 16 bytes, empty, or a misaligned member) or don't fit
;; the 6 integer and 8 SSE registers left.
(define ffi-x86-64-sysv?
  (memq (machine-type) '(a6le ta6le a6osx ta6osx a6fb ta6fb a6ob ta6ob a6nb ta6nb)))

;; a struct's eightbyte classes as Chez's classify-eightbytes gives them
(define (sysv-eightbyte-classes l)
  (let ([size (layout-size l)])
    (if (or (> size 16) (= size 0))
        '(memory)
        (let ([v (make-vector (quotient (+ size 7) 8) 'no-class)])
          (let loop ([ls (layout-leaves l)])
            (if (null? ls)
                (vector->list v)
                (let* ([x (car ls)] [sz (cadr x)] [off (cddr x)])
                  (if (not (= 0 (modulo off sz)))
                      '(memory)
                      (let* ([pos (quotient off 8)]
                             [old (vector-ref v pos)]
                             [c (if (memq (car x) '(float double)) 'sse 'integer)])
                        (vector-set! v pos (if (or (eq? old 'no-class) (eq? old c)) c 'integer))
                        (loop (cdr ls)))))))))))

(define (sysv-count c cs) (length (filter (lambda (x) (eq? x c)) cs)))

;; ret-memory?: a struct result returned through a hidden pointer, which
;; takes the first integer register
(define (sysv-stack-overrun? layouts ret-memory?)
  (and ffi-x86-64-sysv?
       (let loop ([ls layouts] [iint (if ret-memory? 1 0)] [ifp 0])
         (and (pair? ls)
              (let ([l (car ls)])
                (if (layout-group? l)
                    (let* ([cs (sysv-eightbyte-classes l)]
                           [ints (sysv-count 'integer cs)]
                           [fps (sysv-count 'sse cs)])
                      (if (or (eq? (car cs) 'memory) (> (+ iint ints) 6) (> (+ ifp fps) 8))
                          (or (and (memv (modulo (layout-size l) 8) '(3 5 6 7)) #t)
                              (loop (cdr ls) iint ifp))
                          (loop (cdr ls) (+ iint ints) (+ ifp fps))))
                    (if (memq (car (car (layout-leaves l))) '(float double))
                        (loop (cdr ls) iint (if (< ifp 8) (+ ifp 1) ifp))
                        (loop (cdr ls) (if (< iint 6) (+ iint 1) iint) ifp))))))))

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
    (with-published-stack s
     (mutex-acquire gil)
     (set! gil-owner #f)
     (when (fx> gil-blocked 0) (condition-broadcast gil-cv))
     (mutex-release gil)
     (let ([r (thunk)])
       (mutex-acquire gil)
       (gil-wait! s (lambda () #t) #f)
       (mutex-release gil)
       (gil-restore! s)
       r))))
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

;; One downcall's state while it runs: the memory sessions it acquired, the
;; native buffers to free when it ends, and (segment . temp) pairs whose
;; temps are copied back to heap segments after the call.
(define-record-type ffi-call
  (fields (mutable sessions) (mutable temps) (mutable heap-copies))
  (sealed #t))
(define (ffi-acquire! st s)
  (let ([ss (seg-session s)])
    (unless (or (global-session? ss) (memq ss (ffi-call-sessions st)))
      ((jvirt ss "acquire0()V") ss)
      (ffi-call-sessions-set! st (cons ss (ffi-call-sessions st))))))
(define (ffi-temp! st n)
  (let ([p (native-temp n)])
    (ffi-call-temps-set! st (cons p (ffi-call-temps st)))
    p))
;; when the call ends, however: temps freed, then sessions released, each
;; list emptied so a re-entry frees nothing twice
(define (ffi-release! st)
  (for-each foreign-free (ffi-call-temps st))
  (ffi-call-temps-set! st '())
  (for-each (lambda (ss) ((jvirt ss "release0()V") ss)) (ffi-call-sessions st))
  (ffi-call-sessions-set! st '()))

(define (ffi-option? options key) (not (eqv? ((jvirt options key) options) 0)))
(define (ffi-argument-layouts fd)
  (let ([l ((jvirt fd "argumentLayouts()Ljava/util/List;") fd)])
    (vector->list (jarr-vec ((jvirt l "toArray()[Ljava/lang/Object;") l)))))
(define (ffi-return-layout fd)
  (let ([o ((jvirt fd "returnLayout()Ljava/util/Optional;") fd)])
    (and (eqv? ((jvirt o "isPresent()Z") o) 1) ((jvirt o "get()Ljava/lang/Object;") o))))
;; each argument: val, seg (an address), or a group layout's size
(define (ffi-arg-kinds params layouts)
  (list->vector (map (lambda (p l) (cond [(layout-group? l) (layout-size l)]
                                         [(string=? p ms-desc) 'seg]
                                         [else 'val]))
                     params layouts)))
(define (ffi-arg-types params layouts)
  (map (lambda (p l) (if (layout-group? l) `(& ,(struct-ftype l)) (ffi-type p))) params layouts))
;; an address result as the MemorySegment Java sees, or #f for another result
(define (ffi-return-address ret ret-struct ret-layout)
  (and (not ret-struct) (string=? ret ms-desc)
       (let ([f (ffi-static "jdk/internal/foreign/Utils"
                            "longToAddress(JLjava/lang/foreign/AddressLayout;)Ljava/lang/foreign/MemorySegment;")])
         (lambda (a) (f (signed-address a) ret-layout)))))
;; a segment's native address: a native segment's own field, else the JDK's
(define (ffi-unboxer)
  (let ([unbox-java (ffi-static "jdk/internal/foreign/abi/SharedUtils" "unboxSegment(Ljava/lang/foreign/MemorySegment;)J")])
    (lambda (s) (if (seg-native? s) (jslot s (cdr (seg-field-indexes))) (unbox-java s)))))
(define (ffi-set-captured! cap v)
  (jinit "java/lang/foreign/ValueLayout")
  ((jvirt cap "set(Ljava/lang/foreign/ValueLayout$OfInt;JI)V") cap
   (top-level-value (string->symbol "java/lang/foreign/ValueLayout:JAVA_INT")) 0 v))

;; an address argument: a heap segment, when heap access is allowed, as a
;; native copy that is copied back after the call
(define (ffi-segment-arg st a heap? unbox)
  (let ([s (nn a)])
    (ffi-acquire! st s)
    (if (and heap? (not (seg-native? s)))
        (let* ([n (seg-size s)] [p (ffi-temp! st n)] [ts (temp-segment p n)])
          (seg-copy! s 0 ts 0 n)
          (ffi-call-heap-copies-set! st (cons (cons s ts) (ffi-call-heap-copies st)))
          p)
        (unbox s))))
;; a struct by value, k bytes: a native segment's own bytes, else a native
;; copy (the JDK's copy throws for a segment too small)
(define (ffi-struct-arg st a k unbox)
  (let ([s (nn a)])
    (ffi-acquire! st s)
    (if (and (seg-native? s) (>= (seg-size s) k))
        (unbox s)
        (let ([p (ffi-temp! st k)])
          (seg-copy! s 0 (temp-segment p k) 0 k)
          p))))
(define (ffi-raw-args st kinds args heap? unbox)
  (let loop ([as args] [i 0] [acc '()])
    (if (null? as)
        (reverse acc)
        (let ([a (car as)] [k (vector-ref kinds i)])
          (loop (cdr as) (fx+ i 1)
                (cons (cond [(eq? k 'val) a]
                            [(eq? k 'seg) (ffi-segment-arg st a heap? unbox)]
                            [else (ffi-struct-arg st a k unbox)])
                      acc))))))
;; the procedure for addr, kept in the box last as a fresh (addr . proc) for
;; the next call, so no reader sees one half updated
(define (ffi-proc-for! last maker addr)
  (let ([p (unbox last)])
    (if (eqv? (car p) addr)
        (cdr p)
        (let ([f (maker addr)]) (set-box! last (cons addr f)) f))))
;; the GIL is handed off only when another Java thread could want it while
;; the call runs in C (or a thread C started, through an upcall stub)
(define (ffi-run call critical?)
  (if (or critical? (and (null? java-live-threads) (fx= (hashtable-size upcall-codes) 0)))
      (call)
      (ffi-released call)))
(define (ffi-copy-back! heap-copies)
  (for-each (lambda (s+t)
              (when (eqv? ((jvirt (car s+t) "isReadOnly()Z") (car s+t)) 0)
                (seg-copy! (cdr s+t) 0 (car s+t) 0 (seg-size (car s+t)))))
            heap-copies))
;; a struct result: allocated by the call's allocator and filled from rt
(define (ffi-struct-result alloc ret-struct rt unbox)
  (let ([out ((jvirt alloc "allocate(Ljava/lang/foreign/MemoryLayout;)Ljava/lang/foreign/MemorySegment;")
              alloc ret-struct)]
        [n (layout-size ret-struct)])
    (if (and (seg-native? out) (>= (seg-size out) n) (eqv? ((jvirt out "isReadOnly()Z") out) 0))
        (ffi-memcpy (unbox out) rt n)
        (seg-copy! (temp-segment rt n) 0 out 0 n))
    out))
(define (ffi-value-result ret r ret-address)
  (case (string-ref ret 0)
    [(#\Z) (if (eqv? r 0) 0 1)]
    [(#\V) (void)]
    [else (if ret-address (ret-address r) r)]))

(define (ffi-variadic-index options)
  (and (ffi-option? options "isVariadicFunction()Z")
       ((jvirt options "firstVariadicArgIndex()I") options)))
;; Whether this jolt's Chez places stack arguments right, as jolt's
;; (jolt.ffi/stack-args-correct?) finds by compiling the cases 10.4.1 gets
;; wrong (a Chez with jolt's patches 0006/0007, or a release with
;; cisco/ChezScheme#1091): #t only when jolt answers true; false, nil (it
;; can't tell) or a jolt without that function keep the refusals below. boot
;; sets the hook; it's asked once, on the first signature that would be
;; refused, so nothing else pays for the probe.
(define ffi-stack-args-hook #f)
(define (set-ffi-stack-args-hook! f) (set! ffi-stack-args-hook f))
(define ffi-stack-args-ok 'unknown)
(define (ffi-stack-args-correct?)
  (when (eq? ffi-stack-args-ok 'unknown)
    (set! ffi-stack-args-ok
          (and ffi-stack-args-hook (eq? #t (guard (e [#t #f]) (ffi-stack-args-hook))))))
  ffi-stack-args-ok)
;; the signatures Chez would pass wrongly are refused, macOS arm64 first,
;; unless this Chez is known to pass them right
(define (ffi-refuse-misplaced! fd layouts variadic ret-struct)
  (when (and (chez-misplaces? layouts variadic) (not (ffi-stack-args-correct?)))
    (jthrow "java/lang/UnsupportedOperationException"
            (string-append "champaca: Chez Scheme would put an argument passed on the stack in the wrong place here on macOS arm64: "
                           (jstring-of fd))))
  (when (and (sysv-stack-overrun? layouts (and ret-struct (eq? 'memory (car (sysv-eightbyte-classes ret-struct)))))
             (not (ffi-stack-args-correct?)))
    (jthrow "java/lang/UnsupportedOperationException"
            (string-append "champaca: Chez Scheme would overwrite the argument after a struct passed on the stack here on x86_64: "
                           (jstring-of fd)))))
;; the handle's descriptor: target, allocator, the captured state segment if
;; asked, then the arguments
(define (ffi-handle-desc params ret capture?)
  (string-append "(" ms-desc "Ljava/lang/foreign/SegmentAllocator;"
                 (if capture? ms-desc "") (apply string-append params) ")" ret))

;; What one downcall handle needs to run a call: argument kinds, the options
;; that change the call, the result shape, the unboxer, the procedure maker and
;; the box holding the last (addr . proc).
(define-record-type ffi-sig
  (fields kinds critical? heap? capture? ret ret-struct ret-address unbox maker last)
  (sealed #t))

;; one call, with st collecting what the end must release
(define (ffi-invoke sg st all)
  (let* ([capture? (ffi-sig-capture? sg)]
         [critical? (ffi-sig-critical? sg)]
         [ret-struct (ffi-sig-ret-struct sg)]
         [unbox (ffi-sig-unbox sg)]
         [target (nn (car all))]
         [alloc (cadr all)]
         [cap (and capture? (nn (caddr all)))]
         [args (if capture? (cdddr all) (cddr all))])
    (ffi-acquire! st target)
    (when cap (ffi-acquire! st cap))
    (let* ([addr (unbox target)]
           [raw (ffi-raw-args st (ffi-sig-kinds sg) args (ffi-sig-heap? sg) unbox)]
           [rt (and ret-struct (ffi-temp! st (layout-size ret-struct)))]
           [f (ffi-proc-for! (ffi-sig-last sg) (ffi-sig-maker sg) addr)]
           [r+e (ffi-run (lambda ()
                           (let ([r (if rt (apply f rt raw) (apply f raw))])
                             (if cap (cons r (errno)) r)))
                         critical?)]
           [r (if cap (car r+e) r+e)])
      (when cap (ffi-set-captured! cap (cdr r+e)))
      (ffi-copy-back! (ffi-call-heap-copies st))
      (if rt
          (ffi-struct-result alloc ret-struct rt unbox)
          (ffi-value-result (ffi-sig-ret sg) r (ffi-sig-ret-address sg))))))
(define (ffi-downcall-proc sg)
  (lambda all
    (let ([st (make-ffi-call '() '() '())])
      (dynamic-wind
        void
        (lambda () (ffi-invoke sg st all))
        (lambda () (ffi-release! st))))))

(define (ffi-arrange mt fd options)
  (ffi-validate! mt fd options)
  (let* ([d (mt-desc mt)]
         [params (desc-args d)]
         [ret (desc-ret d)]
         [critical? (ffi-option? options "isCritical()Z")]
         [heap? (ffi-option? options "allowsHeapAccess()Z")]
         [capture? (ffi-option? options "hasCapturedCallState()Z")]
         [variadic (ffi-variadic-index options)]
         [layouts (ffi-argument-layouts fd)]
         [ret-layout (ffi-return-layout fd)]
         [kinds (ffi-arg-kinds params layouts)]
         [types (ffi-arg-types params layouts)]
         [ret-struct (and ret-layout (layout-group? ret-layout) ret-layout)]
         [_ (ffi-refuse-misplaced! fd layouts variadic ret-struct)]
         [maker (ffi-maker types (if ret-struct `(& ,(struct-ftype ret-struct)) (ffi-type ret)) variadic (not critical?))]
         [ret-address (ffi-return-address ret ret-struct ret-layout)]
         [unbox (ffi-unboxer)]
         [last (box (cons #f #f))])
    (make-mh (ffi-handle-desc params ret capture?)
      (ffi-downcall-proc
        (make-ffi-sig kinds critical? heap? capture? ret ret-struct ret-address unbox maker last))
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
  (let ([s (make-jthread-state #f '() #f #f 1 (get-thread-id) 0 #f)])
    (upcall-take! s)   ; the GIL serializes making the key
    (let ([t (jnew "java/lang/Thread")])
      (bind-jthread! s t)
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
;; StorageType.INTEGER and VECTOR are 0 and 1 on both architectures, STACK
;; 2 on arm64 and 3 on x86_64
(define upcall-arm64? (and (memq (machine-type) '(arm64osx tarm64osx arm64le tarm64le)) #t))
(define upcall-stack-type (if upcall-arm64? 2 3))
(define (vm-storage-size st) (jslot st (jfield-index "jdk/internal/foreign/abi/VMStorage" "segmentMaskOrSize")))
;; the index of the register st names in regs, or #f
(define (storage-position st regs)
  (let loop ([rs regs] [i 0])
    (cond [(null? rs) #f]
          [(and (fx= (vm-storage-type (car rs)) (vm-storage-type st))
                (fx= (vm-storage-index (car rs)) (vm-storage-index st))) i]
          [else (loop (cdr rs) (fx+ i 1))])))
;; the 8-byte stack words the stack storages reach
(define (upcall-stack-words storages)
  (fold-left (lambda (words st)
               (if (fx= (vm-storage-type st) upcall-stack-type)
                   (max words (quotient (+ (vm-storage-index st) (vm-storage-size st) 7) 8))
                   words))
             0 storages))
;; how to read an argument of descriptor d from storage st: from the
;; received values xv when st is a register, whose slot in types it sets to
;; d's type, else from the stack words copied to bv
(define (upcall-getter d st int-regs vec-regs ni types)
  (let ([t (vm-storage-type st)] [ft (ffi-type d)])
    (cond
     [(and (fx< t 2) (storage-position st (if (fx= t 0) int-regs vec-regs)))
      => (lambda (i)
           (let ([at (if (fx= t 0) i (fx+ ni i))])
             (vector-set! types at ft)
             (lambda (xv bv) (vector-ref xv at))))]
     [(fx= t upcall-stack-type)
      (let ([off (vm-storage-index st)])
        (lambda (xv bv) (foreign-ref ft bv off)))]
     [else (jthrow "java/lang/UnsupportedOperationException"
                   "champaca: an upcall argument in a storage it doesn't know")])))
;; from what the callable receives (every register, then the stack words)
;; to the handle's arguments, through a buffer holding the stack words
(define (upcall-stack-reader getters words ni nv)
  (lambda (xs)
    (let* ([xv (list->vector xs)] [bv (foreign-alloc (fx* 8 (fxmax words 1)))])
      (do ([i 0 (fx+ i 1)]) ((fx= i words))
        (foreign-set! 'integer-64 bv (fx* 8 i) (vector-ref xv (fx+ ni nv i))))
      (let ([r (guard (e [#t (foreign-free bv) (raise e)]) (map (lambda (g) (g xv bv)) getters))])
        (foreign-free bv)
        r))))
;; Arguments that reach the stack (more than the registers hold, a struct the
;; ABI passes in memory, one the registers left can't hold, and on arm64
;; whatever comes after such a struct) can't be placed by Chez from the
;; handle's primitive types alone: it would put them in registers still free.
;; The JDK says where each one is, a register or a stack offset, so such a
;; callable takes every argument register in the ABI's order, then the
;; stack as 8-byte words, and each
;; value is read where the JDK put it. Answers the callable's types and a
;; procedure from what it receives to the handle's arguments, or #f for that
;; procedure when nothing is on the stack.
(define (upcall-placed params storages abi)
  (if (not (exists (lambda (st) (fx= (vm-storage-type st) upcall-stack-type)) storages))
      (values (map ffi-type params) #f)
      (let* ([input (jarr-vec (jslot abi (jfield-index "jdk/internal/foreign/abi/ABIDescriptor" "inputStorage")))]
             ;; the last integer entry isn't an argument C passes: x8, the
             ;; in-memory result's, on arm64, rax, a variadic call's vector
             ;; count, on x86_64
             [int-regs (list-head (vector->list (jarr-vec (vector-ref input 0))) (if upcall-arm64? 8 6))]
             [vec-regs (vector->list (jarr-vec (vector-ref input 1)))]
             [ni (length int-regs)] [nv (length vec-regs)]
             [types (let ([v (make-vector (fx+ ni nv) 'double)])
                      (do ([i 0 (fx+ i 1)]) ((fx= i ni) v) (vector-set! v i 'integer-64)))]
             [words (upcall-stack-words storages)]
             [getters (map (lambda (d st) (upcall-getter d st int-regs vec-regs ni types)) params storages)])
        (values (append (vector->list types) (make-list words 'integer-64))
                (upcall-stack-reader getters words ni nv)))))
(define (upcall-checked-placed params storages abi)
  (unless (= (length params) (length storages))
    (jthrow "java/lang/UnsupportedOperationException"
            (format "champaca: an upcall handle of ~a arguments for ~a storages" (length params) (length storages))))
  (upcall-placed params storages abi))
(define (upcall-runner p) (lambda xs (upcall-run (lambda () (apply p xs)))))
;; each return register's offset in the return buffer, 8 bytes for an
;; integer register, 16 for a vector one
(define (return-buffer-offsets kinds)
  (let loop ([ks kinds] [off 0] [acc '()])
    (if (null? ks) (reverse acc) (loop (cdr ks) (+ off (if (eq? (car ks) 'int) 8 16)) (cons off acc)))))
;; a struct returned in registers: proc fills a zeroed buffer of size bytes,
;; whose words are copied to the registers' struct
(define (upcall-buffer-stub proc args conv abi arg-regs size)
  (let* ([kinds (map (lambda (st) (if (fx= (vm-storage-type st) 0) 'int 'vec)) (call-regs conv "retRegs"))]
         [offsets (return-buffer-offsets kinds)])
    (unless (and (pair? kinds) (= (+ (car (last-pair offsets)) (if (eq? (car (last-pair kinds)) 'int) 8 16)) size)
                 (for-all (lambda (st) (memv (vm-storage-type st) '(0 1))) (call-regs conv "retRegs")))
      (jthrow "java/lang/UnsupportedOperationException"
              (format "champaca: an upcall's return registers don't match its ~a-byte return buffer" size)))
    ;; argRegs lists the buffer too (retBufAddrStorage), filled by the
    ;; stub itself, not by C
    (let-values ([(types convert)
                  (upcall-checked-placed
                   (cdr args)
                   (let ([rb (jslot abi (jfield-index "jdk/internal/foreign/abi/ABIDescriptor" "retBufAddrStorage"))])
                     (remp (lambda (st) (and (fx= (vm-storage-type st) (vm-storage-type rb))
                                             (fx= (vm-storage-index st) (vm-storage-index rb))))
                           arg-regs))
                   abi)])
      (values types `(& ,(upcall-reg-ftype kinds))
              (upcall-runner
               (lambda (rp . xs)
                 (let ([xs (if convert (convert xs) xs)] [buf (foreign-alloc size)] [to (ftype-pointer-address rp)])
                   (do ([i 0 (fx+ i 8)]) ((>= i size)) (foreign-set! 'integer-64 buf i 0))
                   (guard (e [#t (foreign-free buf) (raise e)]) (apply proc buf xs))
                   (for-each (lambda (off i) (foreign-set! 'integer-64 to (fx* i 8) (foreign-ref 'integer-64 buf off)))
                             offsets (iota (length offsets)))
                   (foreign-free buf))))))))
;; a struct returned in memory on arm64: the caller's x8 pointer is proc's
;; first argument
(define (upcall-memory-stub proc args abi arg-regs)
  (let-values ([(types convert) (upcall-checked-placed (cdr args) (cdr arg-regs) abi)])
    (values types `(& ,(upcall-memory-ftype!))
            (upcall-runner (lambda (rp . xs) (apply proc (ftype-pointer-address rp) (if convert (convert xs) xs)) (void))))))
(define (upcall-stub-proc info conv abi needs-return-buffer return-buffer-size)
  (let* ([proc (mh-info-proc info)] [args (mh-info-args info)] [ret (mh-info-ret info)]
         [arg-regs (call-regs conv "argRegs")])
    (cond
     [(eqv? needs-return-buffer 1) (upcall-buffer-stub proc args conv abi arg-regs return-buffer-size)]
     [(and upcall-arm64? (pair? arg-regs)
           (fx= (vm-storage-type (car arg-regs)) 0) (fx= (vm-storage-index (car arg-regs)) 8))
      (upcall-memory-stub proc args abi arg-regs)]
     [else
      (let-values ([(types convert) (upcall-checked-placed args arg-regs abi)])
        (values types (ffi-type ret) (upcall-runner (if convert (lambda xs (apply proc (convert xs))) proc))))])))
(defnative "jdk/internal/foreign/abi/UpcallLinker.makeUpcallStub(Ljava/lang/invoke/MethodHandle;Ljdk/internal/foreign/abi/ABIDescriptor;Ljdk/internal/foreign/abi/UpcallLinker$CallRegs;ZJ)J"
  (mh abi conv needs-return-buffer return-buffer-size)
  (let-values ([(types ret p) (upcall-stub-proc (mh-info-of mh) conv abi needs-return-buffer return-buffer-size)])
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
