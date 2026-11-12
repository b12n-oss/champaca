;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Which module and loader a class belongs to: packages to modules, the
;; application loader's classes, hidden and lambda-site classes, and the
;; module-aware texts of ClassCastException and sealed-class refusals. Split
;; from runtime.ss; loaded right after it.

;; --- modules ---------------------------------------------------------------------
;; Class.getModule() is the boot layer's named module for a JDK class, found
;; by package; the application loader's unnamed module for a classpath class,
;; as on the JVM, once that loader exists (initPhase2 makes it); the boot
;; loader's unnamed module for anything else. Module.defineModule0 records
;; each module's packages while initPhase2 builds the layer and BootLoader's
;; <clinit> hands over the unnamed module; both refresh the mirrors made
;; before them (module and loader), as HotSpot patches the classes loaded
;; before java.base is defined. No classpath class is mirrored during the
;; boot (measured: none of the 100 mirrors at its end), so none needs the
;; application loader patched in. Until then a mirror's module is null, as
;; on the JVM.
(define unnamed-module #f)
;; the application class loader, once ClassLoaders' <clinit> has run
;; (initPhase2), and its unnamed module; #f before
(define (app-loader)
  (let ([s '|jdk/internal/loader/ClassLoaders:APP_LOADER|])
    (and (global-bound? s) (top-level-value s))))
(define (app-unnamed-module)
  (let ([l (app-loader)]) (and l (jslot l (jfield-index "java/lang/ClassLoader" "unnamedModule")))))
(define package-modules (make-hashtable string-hash string=?))   ; "java/util" -> Module
;; classes read from the classpath (link.clj registration-text): they stay in
;; the unnamed module whatever their package, as a classpath class does on the
;; JVM, where one in a package a JDK module owns is never even loaded
(define user-classes (make-hashtable string-hash string=?))
;; and the ones registered since the app loader's packages were last
;; defined (natives.ss define-app-packages!), newest first
(define user-classes-pending '())
(define (user-class! name)
  (hashtable-set! user-classes name #t)
  (set! user-classes-pending (cons name user-classes-pending)))
(define (user-class? name) (hashtable-contains? user-classes name))
;; classes defined at run time (ClassLoader.defineClass, natives.ss): the
;; loader that defined each, for Class.getClassLoader and findLoadedClass,
;; and the hidden ones (Lookup.defineHiddenClass), named <class>/0x<n> as
;; HotSpot names them, which no lookup by name finds
(define class-loaders (make-hashtable string-hash string=?))
(define hidden-classes (make-hashtable string-hash string=?))
;; A lambda's class is one per call site, named for the class holding the
;; site, <caller>$$Lambda/0x<n> (loader.clj lambda-class), a hidden class as
;; HotSpot's are: it sits in its caller's package, module and loader.
(define (lambda-site-caller n)
  (let ([k (string-length n)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i 11) k) #f]
            [(and (char=? (string-ref n i) #\$) (string=? (substring n i (fx+ i 11)) "$$Lambda/0x"))
             (substring n 0 i)]
            [else (loop (fx+ i 1))]))))
(define (defining-name n) (or (lambda-site-caller n) n))
(define (hidden-class? n) (or (hashtable-contains? hidden-classes n) (and (lambda-site-caller n) #t)))
(define (package-dir cname)
  (let loop ([i (fx- (string-length cname) 1)])
    (cond [(fx< i 0) ""]
          [(char=? (string-ref cname i) #\/)
           ;; a hidden class's /0x<n> isn't a package boundary (no package
           ;; starts with a digit)
           (if (and (fx< (fx+ i 2) (string-length cname))
                    (char=? (string-ref cname (fx+ i 1)) #\0) (char=? (string-ref cname (fx+ i 2)) #\x))
               (package-dir (substring cname 0 i))
               (substring cname 0 i))]
          [else (loop (fx- i 1))])))
;; the class an array's module comes from: its element type, down through the
;; dimensions ("[[Ljava/sql/Date;" -> "java/sql/Date"); a primitive stays a
;; primitive name ("[I" -> "int")
(define (element-name c)
  (let ([n (jclass-cname c)])
    (if (eq? (jclass-kind c) 'array)
        (let skip ([i 0])
          (cond [(char=? (string-ref n i) #\[) (skip (fx+ i 1))]
                [(char=? (string-ref n i) #\L) (substring n (fx+ i 1) (fx- (string-length n) 1))]
                [else (cdr (assoc (string (string-ref n i)) prim-names))]))
        n)))
;; the package a class's module is looked up by: primitives count as java.lang
(define (class-package c)
  (let ([e (element-name c)]) (if (prim-name? e) "java/lang" (package-dir e))))
;; a classpath class is in the application loader's unnamed module (the boot
;; loader's until the application loader exists); a class in a package a
;; module owns (a JDK class, a proxy in its dynamic module) is in that module;
;; a class a loader defined is in that loader's unnamed module
;; a class path or module path class whose package a named module of the
;; application loader holds (java -p: ModuleBootstrap defines the module path's
;; modules to it): that module, as the JVM puts it there
(define (named-app-module c)
  (let ([m (hashtable-ref package-modules (class-package c) #f)])
    (and m (app-loader) (eq? (module-loader m) (app-loader)) m)))
(define (class-module c)
  (let ([e (defining-name (element-name c))])
    (cond [(user-class? e) (or (named-app-module c) (app-unnamed-module) unnamed-module)]
          [(hashtable-ref package-modules (class-package c) #f)]
          [(hashtable-ref class-loaders e #f)
           => (lambda (ld) ((method-proc "java/lang/ClassLoader" "getUnnamedModule()Ljava/lang/Module;") ld))]
          [else unnamed-module])))
(define (set-mirror-module! m c)
  (let ([mod (class-module c)])
    (when mod (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "module") mod))))
;; the mirrors a module's definition has to patch, by package, so defining
;; the 70 modules of the boot layer touches each mirror once rather than
;; walking every mirror 70 times (a class is never unloaded, so this keeps
;; nothing alive that the class table doesn't)
(define package-mirrors (make-hashtable string-hash string=?))
(define (index-mirror! m c)
  (unless (user-class? (element-name c))
    (let ([d (class-package c)])
      (hashtable-set! package-mirrors d (cons m (hashtable-ref package-mirrors d '()))))))
(define (set-unnamed-module! m)
  (set! unnamed-module m)
  (vector-for-each (lambda (mirror) (set-mirror-module! mirror (mirror-class mirror))) (hashtable-keys mirror->class)))
;; a class's loader as Class.getClassLoader answers it: a defined class's
;; definer, a classpath class's the application loader, a JDK class's the
;; loader its module is defined to (null for the boot modules, the platform
;; loader for java.sql and the like), an array's its element's
(define (module-loader m) (and m (jslot m (jfield-index "java/lang/Module" "loader"))))
(define (class-loader-of c)
  (let ([e (defining-name (element-name c))])
    (or (hashtable-ref class-loaders e #f)
        (if (user-class? e)
            (app-loader)
            (module-loader (hashtable-ref package-modules (class-package c) #f))))))
;; HotSpot's place of a class in its linkage messages (Klass::
;; class_in_module_of_loader): "java.lang.String is in module java.base of
;; loader 'bootstrap'", "Foo is in unnamed module of loader 'app'"; a loader
;; of the program's own is "'name' @hash" or its class's name and @hash
;; (loader-text, HotSpot's loader_name_and_id). Module versions aren't shown
;; (HotSpot shows a non-JDK module's).
(define (loader-text ld)
  (if (not ld)
      "'bootstrap'"
      (let ([n (jslot ld (jfield-index "java/lang/ClassLoader" "name"))]
            [hash (string-append " @" (string-downcase (number->string (identity-hash ld) 16)))])
        (cond [(jinstance? ld "jdk/internal/loader/BuiltinClassLoader") (string-append "'" (jstring->scheme n) "'")]
              [n (string-append "'" (jstring->scheme n) "'" hash)]
              [else (string-append (slashes->dots (jclass-cname (jobj-cls ld))) hash)]))))
(define (module-name-of mod)
  (let ([mname (and mod (jslot mod (jfield-index "java/lang/Module" "name")))])
    (and mname (jstring->scheme mname))))
(define (class-place-of c)
  (let ([mname (module-name-of (class-module c))])
    (string-append (if mname (string-append "module " mname) "unnamed module")
                   " of loader " (loader-text (class-loader-of c)))))
(define (class-place-text cname)
  (string-append (slashes->dots cname) " is in " (class-place-of (find-class cname))))
;; The message of the ClassCastException a failed cast throws. Written from
;; HotSpot 25's observed messages: "class X cannot be cast to class Y (" then
;; either "X and Y are in <place>" when both classes share one module (the
;; same Module object) or "X is in <place>; Y is in <place>", closed by ")".
;; An array is placed by its element class, a primitive array in java.base.
(define (class-cast-message from target)
  (let ([fx-name (external-class-name from)]
        [tg-name (external-class-name target)]
        [fc (find-class from)]
        [tc (find-class target)])
    (string-append
     "class " fx-name " cannot be cast to class " tg-name " ("
     (if (eq? (class-module fc) (class-module tc))
         (string-append fx-name " and " tg-name " are in " (class-place-of fc))
         (string-append fx-name " is in " (class-place-of fc) "; " tg-name " is in " (class-place-of tc)))
     ")")))
;; Klass::external_name: dots, but a hidden class keeps the slash before its
;; /0x<n> suffix, inside an array's name too
(define (external-class-name n)
  (define k (let loop ([i 0]) (if (and (fx< i (string-length n)) (char=? (string-ref n i) #\[)) (loop (fx+ i 1)) i)))
  (cond
   [(and (fx> k 0) (fx< k (string-length n)) (char=? (string-ref n k) #\L))
    (string-append (substring n 0 (fx+ k 1))
                   (external-class-name (substring n (fx+ k 1) (fx- (string-length n) 1))) ";")]
   [(hidden-class? n)
    (let loop ([i (fx- (string-length n) 1)])
      (if (char=? (string-ref n i) #\/)
          (string-append (slashes->dots (substring n 0 i)) (substring n i (string-length n)))
          (loop (fx- i 1))))]
   [else (slashes->dots n)]))
;; a class name with dots, a hidden class keeping the slash before /0x<n>
;; (it isn't marked hidden until its definition ends)
(define (sealed-dotted-name n)
  (let ([i (let loop ([i (fx- (string-length n) 1)])
             (cond [(fx< i 0) #f] [(char=? (string-ref n i) #\/) i] [else (loop (fx- i 1))]))])
    (if (and i (fx< (fx+ i 2) (string-length n))
             (char=? (string-ref n (fx+ i 1)) #\0) (char=? (string-ref n (fx+ i 2)) #\x))
        (string-append (slashes->dots (substring n 0 i)) (substring n i (string-length n)))
        (slashes->dots n))))
(define (sealed-package-text n)
  (let ([d (package-dir n)]) (if (string=? d "") "unnamed" d)))
;; the module the class name being defined will be in, ld its loader
(define (sealed-subclass-module name defined? ld)
  (cond [(not defined?) (or (app-unnamed-module) unnamed-module)]
        [(hashtable-ref package-modules (package-dir name) #f)]
        [ld ((method-proc "java/lang/ClassLoader" "getUnnamedModule()Ljava/lang/Module;") ld)]
        [else unnamed-module]))   ; a null loader (Unsafe.defineClass0): the boot loader's
(define (same-module-refusal name mod sup smod)
  (string-append "Failed same module check: subclass " (sealed-dotted-name name) " is in module '"
                 (or (module-name-of mod) "unnamed module") "' with loader " (loader-text (module-loader mod))
                 ", and sealed class " (sealed-dotted-name sup) " is in module '"
                 (or (module-name-of smod) "unnamed module") "' with loader " (loader-text (module-loader smod))))
(define (same-package-refusal name ld sup sld)
  (string-append "Failed same package check: non-public subclass " (sealed-dotted-name name) " is in package '"
                 (sealed-package-text name) "' with classloader " (loader-text ld) ", and sealed class " (sealed-dotted-name sup)
                 " is in package '" (sealed-package-text sup) "' with classloader " (loader-text sld)))
(define (listed-refusal name sup)
  (string-append "Failed listed permitted subclass check: class " (sealed-dotted-name name)
                 " is not a permitted subclass of " (sealed-dotted-name sup)))
;; Why a sealed class or interface sup (JVMS 5.3.5) refuses the class name
;; being defined, in HotSpot's texts (InstanceKlass::has_as_permitted_subclass),
;; or #f when it permits it or isn't sealed: the two must share a module, a
;; non-public one must share sup's package, and sup's PermittedSubclasses
;; must list it (a hidden class's /0x<n> name never matches). name isn't in
;; the class table yet: a classpath class is the application loader's, in its
;; unnamed module, a defined one its definer's (resolving-loader, bound by
;; natives.ss define-class!), in a module owning its package or else in the
;; definer's unnamed module, as class-module puts them.
(define (sealed-refusal name public? defined? sup)
  (let* ([s (find-class sup)]
         [permitted (class-permitted-subclasses s)])
    (and permitted
         (let* ([ld (if defined? (resolving-loader) (app-loader))]
                [mod (sealed-subclass-module name defined? ld)]
                [smod (class-module s)]
                [sld (class-loader-of s)])
           (cond [(not (eq? mod smod)) (same-module-refusal name mod sup smod)]
                 [(and (not public?) (not (string=? (package-dir name) (package-dir sup))))
                  (same-package-refusal name ld sup sld)]
                 [(member name permitted) #f]
                 [else (listed-refusal name sup)])))))
(define (set-mirror-loader! m c)
  (let ([ld (class-loader-of c)])
    (when ld (vector-set! (jobj-slots m) (jfield-index "java/lang/Class" "classLoader") ld))))
(define (define-module! m package-names)   ; names with dots, as Module.defineModule0 gets them
  (let ([slot (jfield-index "java/lang/Class" "module")]
        [lslot (jfield-index "java/lang/Class" "classLoader")]
        [ld (module-loader m)])
    (for-each (lambda (pn)
                (let ([d (slashes pn)])
                  (hashtable-set! package-modules d m)
                  (for-each (lambda (mirror)
                              (vector-set! (jobj-slots mirror) slot m)
                              ;; a class a loader defined keeps its definer
                              (unless (hashtable-ref class-loaders (element-name (mirror-class mirror)) #f)
                                (vector-set! (jobj-slots mirror) lslot ld)))
                            (hashtable-ref package-mirrors d '()))))
              package-names)))

(define (jmirror-of name) (jmirror (find-class name)))
;; ldc of a class: its mirror, the class resolved when the ldc runs (a class
;; defined after the method was translated is found; one that fails to load
;; fails there) and through the defining loader of the class whose code it
;; is, which alone may see it (Quarkus's RunnerClassLoader)
(define (jldc-class name from)
  (if (hashtable-contains? classes name)
      (jmirror-of name)
      (parameterize ([resolving-loader (hashtable-ref class-loaders from #f)]) (jmirror-of name))))
(define (mirror-class m) (or (eq-hashtable-ref mirror->class (nn m) #f) (error 'champaca "not a mirror" m)))
