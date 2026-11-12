;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Modules, the built-in class loaders and classes defined at run time.
;; Split from natives.ss, whose defnative and helpers these use; loaded after
;; it.

;; --- modules and class loaders --------------------------------------------------
;; One flat class space: module wiring is recorded nowhere, and every loader
;; sees the same classes (user classpath, then java.base).

;; Resources come from the class path (classpath.clj) the way classes do: the user
;; classpath, then the JDK module whose packages the path falls under. The
;; JDK finds its own resources in the runtime image, which isn't read here,
;; so the getResourceAsStream methods are overridden to ask the loader. The
;; hook answers a jolt byte array, a record around a bytevector, or false.
(define resource-hook (lambda (path) #f))
(define resource-urls-hook (lambda (path) ""))
(define (set-resource-hook! f urls) (set! resource-hook f) (set! resource-urls-hook urls))
(define (host-bytevector x)
  (let ([bv (if (record? x) ((record-accessor (record-rtd x) 0) x) x)])
    (unless (bytevector? bv) (error 'champaca "resource hook gave no bytes" x))
    bv))
(define (resource-stream path) (bytes-stream (resource-hook path)))
;; Class.getResource*: a name without a leading / is relative to the class's
;; package. A ClassLoader takes names without one, and finds nothing for a
;; name with one (the loader's hook refuses it).
(define (class-resource-path this name)
  (let ([n (jstring->scheme (nn name))])
    (if (and (fx> (string-length n) 0) (char=? (string-ref n 0) #\/))
        (substring n 1 (string-length n))
        (let* ([c (jclass-cname (mirror-class this))]
               [slash (let loop ([i (fx- (string-length c) 1)])
                        (cond [(fx< i 0) #f] [(char=? (string-ref c i) #\/) i] [else (loop (fx- i 1))]))])
          (if slash (string-append (substring c 0 (fx+ slash 1)) n) n)))))
;; A loader of the program's own (a URLClassLoader, a plugin or fat-jar
;; loader) runs the JDK's own logic: its parent first, or the boot loader,
;; then its findResource, which a URLClassLoader answers from its URLs
;; through the JDK's code; the built-in loaders answer from the classpath and
;; the image. A class such a loader defined asks it for its resources, as
;; Class.getResource does through its class's loader. Through a loader with
;; no parent, the boot loader answers a JDK class file (jdk-class-url).
(define (own-loader? l) (and l (not (jinstance? l "jdk/internal/loader/BuiltinClassLoader"))))
;; the platform loader has no class path: it sees the JDK's resources alone,
;; as streams, so a loader isolated under it doesn't see the application's
(define (platform-loader? l)
  (let ([s '|jdk/internal/loader/ClassLoaders:PLATFORM_LOADER|])
    (and l (global-bound? s) (eq? l (top-level-value s)))))
(define jdk-resource-hook (lambda (path) #f))
(define (set-jdk-resource-hook! f) (set! jdk-resource-hook f))
(define (bytes-stream found)
  (and found
       (let* ([bv (host-bytevector found)] [n (bytevector-length bv)] [v (make-vector n)])
         (do ([i 0 (fx+ i 1)]) ((fx= i n)) (vector-set! v i (bytevector-s8-ref bv i)))
         (construct "java/io/ByteArrayInputStream" "([B)V" (make-jarr "B" v)))))
;; whether a named application module (java -p) opens slashed package dir to
;; everyone: the application loader finds a resource of such a package in the
;; module only then, else on the class path alone (classpath.clj resource-entries,
;; as BuiltinClassLoader.findResource); #f for any other package
(define (app-package-open? dir)
  (let ([m (hashtable-ref package-modules dir #f)])
    (and m (app-loader) (eq? (module-loader m) (app-loader))
         (eqv? 1 ((jvirt m "isOpen(Ljava/lang/String;)Z") m (jstring (slashes->dots dir)))))))
;; a class of a named application module looks in its own module alone, as
;; Class.getResource* does for a named module: the module answers, for its
;; caller (Module.getResourceAsStream: its own code, or a package opened to
;; the caller, or a resource in no package); a class in the unnamed module
;; asks the loader, which hides what a named module doesn't open
(define (class-module-resource this path)
  (named-app-module (mirror-class this)))
(define module-resource-url-hook (lambda (mn path) #f))
(define (set-module-resource-url-hook! f) (set! module-resource-url-hook f))

;; a built-in loader's stream: the bytes, or for a classpath directory (which
;; has a URL but no bytes) the URL opened, which lists it, as the JDK's
;; getResourceAsStream opens getResource's URL
(define (builtin-resource-stream l path)
  (cond
   [(platform-loader? l) (bytes-stream (jdk-resource-hook path))]
   [else
      (or (resource-stream path)
          (let ([u (first-resource-url path)])
            (and u (guard (e [(and (jobj? e) (jinstance? e "java/io/IOException")) #f])
                     ((jvirt u "openStream()Ljava/io/InputStream;") u)))))]))
(define (class-own-loader this)
  (let ([l (jslot this (jfield-index "java/lang/Class" "classLoader"))]) (and (own-loader? l) l)))
(define (own-get-resource l name)
  (let* ([parent (jslot l (jfield-index "java/lang/ClassLoader" "parent"))]
         [up (if parent
                 ((jvirt parent "getResource(Ljava/lang/String;)Ljava/net/URL;") parent name)
                 (jdk-class-url (jstring->scheme name)))])
    (or up ((jvirt l "findResource(Ljava/lang/String;)Ljava/net/URL;") l name))))
(define (own-get-resource-stream l name)
  (let ([u ((jvirt l "getResource(Ljava/lang/String;)Ljava/net/URL;") l name)])
    ;; as the JDK's: an IOException opening it is no resource
    (and u (guard (e [(and (jobj? e) (jinstance? e "java/io/IOException")) #f])
             ((jvirt u "openStream()Ljava/io/InputStream;") u)))))
(define (own-get-resources l name)
  (let* ([parent (jslot l (jfield-index "java/lang/ClassLoader" "parent"))]
         [up (if parent
                 ((jvirt parent "getResources(Ljava/lang/String;)Ljava/util/Enumeration;") parent name)
                 (let ([j (jdk-class-url (jstring->scheme name))]) (resource-enumeration-of (if j (list j) '()))))]
         [mine ((jvirt l "findResources(Ljava/lang/String;)Ljava/util/Enumeration;") l name)])
    (construct "java/lang/CompoundEnumeration" "([Ljava/util/Enumeration;)V"
               (make-jarr "Ljava/util/Enumeration;" (vector up mine)))))
(define-override! "java/lang/Class.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this name)
    (let ([l (class-own-loader this)] [p (class-resource-path this name)])
      (cond
       [l ((jvirt l "getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;") l (jstring p))]
       [(class-module-resource this p)
        => (lambda (m) ((jvirt m "getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;") m (jstring p)))]
       [else (builtin-resource-stream #f p)]))))
(define-override! "java/lang/ClassLoader.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this name)
    (if (own-loader? this)
        (own-get-resource-stream this (nn name))
        (builtin-resource-stream this (jstring->scheme (nn name))))))
(define-override! "java/lang/ClassLoader.getSystemResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (name) (builtin-resource-stream #f (jstring->scheme (nn name)))))

;; URLs: a class file of a JDK module's package is jrt:/<module>/<path>, which
;; the JDK's own jrt handler opens, as BuiltinClassLoader.findResource gives
;; it (only a .class name: no JDK package is open, and the JDK hides any other
;; resource of a package that isn't), and first, since the JVM's loaders ask
;; their parents first; the rest are the classpath's (file: and jar:, which
;; the JDK opens with its own handlers)
(define (jdk-class-url path)
  (let ([n (string-length path)])
    (and (fx> n 6) (string=? (substring path (fx- n 6) n) ".class")
         (let ([m (hashtable-ref package-modules (package-dir path) #f)])
           (and m (not (eq? (module-loader m) (app-loader)))   ; a java -p module is the classpath's business
                (jdk-resource-hook path)
                (construct "java/net/URL" "(Ljava/lang/String;)V"
                           (jstring (string-append "jrt:/" (module-name-of m) "/" path))))))))
(define (resource-url-list path)
  (let ([j (jdk-class-url path)] [cp (classpath-url-list path)])
    (if j (cons j cp) cp)))
(define (classpath-url-list path)
  (let ([text (resource-urls-hook path)])
    (if (string=? text "")
        '()
        (map (lambda (u) (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring u)))
             (let loop ([i 0] [start 0] [acc '()])
               (cond [(fx= i (string-length text)) (reverse (cons (substring text start i) acc))]
                     [(char=? (string-ref text i) #\newline) (loop (fx+ i 1) (fx+ i 1) (cons (substring text start i) acc))]
                     [else (loop (fx+ i 1) start acc)]))))))
(define (first-resource-url path) (let ([us (resource-url-list path)]) (and (pair? us) (car us))))
(define (resource-enumeration-of urls)
  (let ([lst (construct "java/util/ArrayList" "()V")])
    (for-each (lambda (u) ((jvirt lst "add(Ljava/lang/Object;)Z") lst u)) urls)
    (jinit "java/util/Collections")
    ((method-proc "java/util/Collections" "enumeration(Ljava/util/Collection;)Ljava/util/Enumeration;") lst)))
(define (resource-enumeration path) (resource-enumeration-of (resource-url-list path)))
(define-override! "java/lang/Class.getResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (this name)
    (let ([l (class-own-loader this)] [p (class-resource-path this name)])
      (cond
       [l ((jvirt l "getResource(Ljava/lang/String;)Ljava/net/URL;") l (jstring p))]
       ;; the module decides for its caller, as for the stream; a URL when it
       ;; would answer one (its stream, opened to ask, is closed)
       [(class-module-resource this p)
        => (lambda (m)
             (let ([in ((jvirt m "getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;") m (jstring p))])
               (and in (begin ((jvirt in "close()V") in)
                              (let ([u (module-resource-url-hook (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name"))) p)])
                                (and u (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring u))))))))]
       [else (first-resource-url p)]))))
(define-override! "java/lang/ClassLoader.getResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (this name)
    (cond [(own-loader? this) (own-get-resource this (nn name))]
          [(platform-loader? this) (jdk-class-url (jstring->scheme (nn name)))]
          [else (first-resource-url (jstring->scheme (nn name)))])))
(define-override! "java/lang/ClassLoader.getSystemResource(Ljava/lang/String;)Ljava/net/URL;"
  (lambda (name) (first-resource-url (jstring->scheme (nn name)))))
(define-override! "java/lang/ClassLoader.getResources(Ljava/lang/String;)Ljava/util/Enumeration;"
  (lambda (this name)
    (if (own-loader? this)
        (own-get-resources this (nn name))
        (if (platform-loader? this)
            (let ([j (jdk-class-url (jstring->scheme (nn name)))]) (resource-enumeration-of (if j (list j) '())))
            (resource-enumeration (jstring->scheme (nn name)))))))

;; Class.getProtectionDomain: a field the VM sets when it defines a class. A
;; class defined with a domain keeps that one (define-class!); a classpath
;; class gets the application loader's for its entry's code source, made the
;; first time it is asked for, as BuiltinClassLoader defines it with
;; (SecureClassLoader.getProtectionDomain, which caches one per code source);
;; any other class the JDK's all-permission domain with no code source
(define code-source-hook (lambda (cname) #f))
(define (set-code-source-hook! f) (set! code-source-hook f))
(define-override! "java/lang/Class.getProtectionDomain()Ljava/security/ProtectionDomain;"
  (lambda (this)
    (let* ([slot (jfield-index "java/lang/Class" "protectionDomain")] [pd (jslot this slot)])
      (or pd
          (let* ([c (mirror-class this)]
                 [url (and (not (eq? (jclass-kind c) 'array)) (user-class? (jclass-cname c))
                           (code-source-hook (jclass-cname c)))])
            (and url
                 (let* ([cs (construct "java/security/CodeSource" "(Ljava/net/URL;[Ljava/security/CodeSigner;)V"
                                       (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring url)) #f)]
                        [pd ((method-proc "java/security/SecureClassLoader"
                                          "getProtectionDomain(Ljava/security/CodeSource;)Ljava/security/ProtectionDomain;")
                             (app-loader) cs)])
                   (jslot-set! this slot pd)
                   pd)))
          (begin (jinit "java/lang/Class$Holder") (top-level-value '|java/lang/Class$Holder:allPermDomain|))))))
(define-override! "java/lang/ClassLoader.getSystemResources(Ljava/lang/String;)Ljava/util/Enumeration;"
  (lambda (name)
    (let ([p (jstring->scheme (nn name))])
      (resource-enumeration p))))

;; Class.getPackage: every class here belongs to the boot loader, whose
;; packages the JDK finds through a runtime image this runtime doesn't have,
;; so it answered null for every class. One Package per name instead, built
;; the way the JDK's class loaders define one: a classpath class's from its
;; jar's manifest (the hook), a JDK class's as a package of its named module
;; (no version information, sealed), defined to its module's loader the way
;; ClassLoader.definePackage(Class) does (the boot loader's BootClassLoader
;; for a boot module), so Package.getPackage and getDefinedPackage answer the
;; same object. A
;; lambda class is champaca/$$Lambda$..., shared by every call site with the
;; same interface, so its package is champaca, in step with its getName (the
;; JVM's lambda lives in its caller's package).
(define jpackage-hook #f)
(define (set-jpackage-hook! f) (set! jpackage-hook f))
(define jpackage-table (make-hashtable string-hash string=?))
(define (text-lines s)
  (let loop ([i 0] [start 0] [acc '()])
    (cond [(fx= i (string-length s)) (reverse (cons (substring s start i) acc))]
          [(char=? (string-ref s i) #\newline) (loop (fx+ i 1) (fx+ i 1) (cons (substring s start i) acc))]
          [else (loop (fx+ i 1) start acc)])))
;; the hook's version attributes and seal base for a user package, as Java
;; values: (spec-title spec-version spec-vendor impl-title impl-version
;; impl-vendor seal-base), each null when absent
(define (package-attributes info)
  (let ([given (lambda (s) (and (char=? (string-ref s 0) #\+) (substring s 1 (string-length s))))])
    (append (map (lambda (s) (let ([v (given s)]) (and v (jstring v)))) (list-head (cdr info) 6))
            (list (let ([u (given (list-ref info 7))])
                    (and u (construct "java/net/URL" "(Ljava/lang/String;)V" (jstring u))))))))
;; A class with a class loader has the Package that loader defined under
;; its name, as ClassLoader.definePackage(Class) answers: one a loader of the
;; program's own defined before defining the class (URLClassLoader does,
;; from its jar's manifest), else one defined now through
;; ClassLoader.definePackage with the hook's attributes, the way
;; BuiltinClassLoader defines a classpath class's from its jar's manifest.
;; So getDefinedPackage, getDefinedPackages and Package.getPackage find it.
;; Another thread can define the name between the two calls (Java code runs
;; in both), and definePackage then refuses it: that one is the answer.
(define (loader-package ld pn cn)
  (let ([defined (lambda () ((method-proc "java/lang/ClassLoader" "getDefinedPackage(Ljava/lang/String;)Ljava/lang/Package;")
                             ld (jstring pn)))])
    (or (defined)
        (guard (e [(and (jobj? e) (jinstance? e "java/lang/IllegalArgumentException") (defined))])
          (apply (method-proc "java/lang/ClassLoader"
                              "definePackage(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/net/URL;)Ljava/lang/Package;")
                 ld (jstring pn) (package-attributes (text-lines (jpackage-hook cn))))))))
;; HotSpot's application loader defines a class's package as it defines
;; the class (BuiltinClassLoader.defineOrCheckPackage); champaca loads class
;; path classes itself, so the app loader's packages are defined when they're
;; looked up instead: getDefinedPackage, and packages() under
;; getDefinedPackages, Package.getPackage(s) and the rest, first define the
;; package of each class path class registered since the last look, in the
;; order they were registered, so a split package takes its first class's
;; manifest as before (each through loader-package).
(define (define-app-packages!)
  (let ([ld (app-loader)])
    (when (and ld (pair? user-classes-pending))
      (let ([names (reverse user-classes-pending)] [done (make-hashtable string-hash string=?)])
        (set! user-classes-pending '())   ; defining runs Java, which may register more
        (for-each
         (lambda (cn)
           (let ([dir (package-dir cn)])
             (unless (or (hashtable-ref done dir #f) (hashtable-ref package-modules dir #f))
               (hashtable-set! done dir #t)
               (loader-package ld (slashes->dots dir) cn))))
         names)))))
(define (loader-named-packages ld) (jslot ld (jfield-index "java/lang/ClassLoader" "packages")))
(define (named-package->package ld np)
  ((method-proc "java/lang/ClassLoader" "definePackage(Ljava/lang/String;Ljava/lang/Module;)Ljava/lang/Package;")
   ld ((jvirt np "packageName()Ljava/lang/String;") np) ((jvirt np "module()Ljava/lang/Module;") np)))
(define-override! "java/lang/ClassLoader.getDefinedPackage(Ljava/lang/String;)Ljava/lang/Package;"
  (lambda (this name)
    (unless name (jthrow "java/lang/NullPointerException" "name cannot be null"))
    (when (eq? this (app-loader)) (define-app-packages!))
    (let* ([m (loader-named-packages this)]
           [np ((jvirt m "get(Ljava/lang/Object;)Ljava/lang/Object;") m name)])
      (and np (named-package->package this np)))))
(define-override! "java/lang/ClassLoader.packages()Ljava/util/stream/Stream;"
  (lambda (this)
    (when (eq? this (app-loader)) (define-app-packages!))
    (let* ([m (loader-named-packages this)]
           [vs ((jvirt m "values()Ljava/util/Collection;") m)]
           [arr ((jvirt vs "toArray()[Ljava/lang/Object;") vs)])
      (jinit "java/util/Arrays")
      ((method-proc "java/util/Arrays" "stream([Ljava/lang/Object;)Ljava/util/stream/Stream;")
       (make-jarr "Ljava/lang/Package;" (vector-map (lambda (np) (named-package->package this np)) (jarr-vec arr)))))))
(define (jpackage-of cn)
  (let* ([dir (package-dir cn)]   ; hidden-class aware: Name/0x1 is in Name's package
         [pn (slashes->dots dir)]
         [ld (and (not (hashtable-ref package-modules dir #f)) (class-loader-of (find-class cn)))])
    (or (and ld (loader-package ld pn cn))
        (hashtable-ref jpackage-table pn #f)
        (let ([p (jdk-module-package pn)]) (when p (hashtable-set! jpackage-table pn p)) p)
        (table-package dir pn cn))))
;; a package with no loader to define it, kept in jpackage-table: a JDK
;; package of its module (no version information), anything else (a
;; lambda's champaca package) with the hook's attributes
(define (table-package dir pn cn)
  (let* ([info (text-lines (jpackage-hook cn))]
         [jdk? (string=? (car info) "jdk")]
         [module (and jdk? (not (string=? dir "")) (hashtable-ref package-modules dir #f))]
         [p (if module
                (construct "java/lang/Package" "(Ljava/lang/String;Ljava/lang/Module;)V" (jstring pn) module)
                (apply construct "java/lang/Package"
                       "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/net/URL;Ljava/lang/ClassLoader;)V"
                       (jstring pn) (append (if jdk? (make-list 7 #f) (package-attributes info)) (list #f))))])
    ;; constructing ran Java code, which can let another thread in to
    ;; define the same package first: keep whichever got there. A JDK
    ;; package asked for before its module and the boot loader object
    ;; exist (the hook says jdk only for a package the runtime image
    ;; owns) isn't kept, so a later call gets its loader's
    (if jdk?
        p
        (or (hashtable-ref jpackage-table pn #f)
            (begin (hashtable-set! jpackage-table pn p) p)))))
(define-override! "java/lang/Class.getPackage()Ljava/lang/Package;"
  (lambda (this)
    (let ([cn (jclass-cname (mirror-class this))])
      (and (not (prim-name? cn)) (not (char=? (string-ref cn 0) #\[))
           (jpackage-of cn)))))

(defnative "jdk/internal/loader/BootLoader.setBootLoaderUnnamedModule0(Ljava/lang/Module;)V" (m) (set-unnamed-module! m))
;; a JDK package's Package, from its module's loader, once the module and
;; the boot loader object exist (#f before, and for any other package)
(define (jdk-module-package pn)
  (let* ([m (hashtable-ref package-modules (slashes pn) #f)]
         [boot '|jdk/internal/loader/ClassLoaders:BOOT_LOADER|]
         [ld (and m (or (module-loader m) (and (global-bound? boot) (top-level-value boot))))])
    (and ld ((method-proc "java/lang/ClassLoader" "definePackage(Ljava/lang/String;Ljava/lang/Module;)Ljava/lang/Package;")
             ld (jstring pn) m))))

;; System packages are the boot modules' packages the boot loader has loaded
;; a class from, as HotSpot answers (Package.getPackage("javax.crypto") is
;; null until a class of it loads); a name with slashes or dots, as
;; BootLoader passes either. The location is the module's jrt: URL, which
;; BootLoader turns back into the module.
;; kept while neither the class table nor the module packages grow, which
;; is all either does
(define boot-packages-cache (list -1 -1 #f))   ; (classes packages table)
(define (boot-packages-loaded)
  (let ([nc (hashtable-size classes)] [np (hashtable-size package-modules)])
    (if (and (fx= (car boot-packages-cache) nc) (fx= (cadr boot-packages-cache) np))
        (caddr boot-packages-cache)
        (let ([t (boot-packages-scan)]) (set! boot-packages-cache (list nc np t)) t))))
(define (boot-packages-scan)
  (let ([t (make-hashtable string-hash string=?)])
    (vector-for-each
     (lambda (cn)
       (unless (or (char=? (string-ref cn 0) #\[) (prim-name? cn))
         (let* ([d (package-dir cn)] [m (hashtable-ref package-modules d #f)])
           (when (and m (not (module-loader m)) (not (user-class? cn))) (hashtable-set! t d m)))))
     (hashtable-keys classes))
    t))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageNames()[Ljava/lang/String;" ()
  (scheme-list->jarray "Ljava/lang/String;" (map jstring (vector->list (hashtable-keys (boot-packages-loaded))))))
(defnative "jdk/internal/loader/BootLoader.getSystemPackageLocation(Ljava/lang/String;)Ljava/lang/String;" (n)
  (let ([m (hashtable-ref (boot-packages-loaded) (slashes (jstring->scheme (nn n))) #f)])
    (and m (jstring (string-append "jrt:/" (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name"))))))))
(defnative "java/lang/Module.defineModule0(Ljava/lang/Module;ZLjava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
  (m open? version location pns)
  (define-module! m (map jstring->scheme (vector->list (jarr-vec pns)))))
(defnative "java/lang/Module.addReads0(Ljava/lang/Module;Ljava/lang/Module;)V" (from to) (void))
(defnative "java/lang/Module.addExports0(Ljava/lang/Module;Ljava/lang/String;Ljava/lang/Module;)V" (from pn to) (void))
(defnative "java/lang/Module.addExportsToAll0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))
(defnative "java/lang/Module.addExportsToAllUnnamed0(Ljava/lang/Module;Ljava/lang/String;)V" (from pn) (void))

;; A class that can be loaded: registered already, an array, or a class file
;; the loader can find (the resource hook looks where classes come from). Only
;; that is "not found". A class that exists but can't be loaded (an opcode or
;; attribute the translator lacks, a superclass that is missing) is a
;; NoClassDefFoundError naming the cause, as a class the JVM can't define is
;; a LinkageError: code catching Throwable or LinkageError around a probe for
;; an optional class goes on, code catching ClassNotFoundException alone stops
;; with the cause in front of it, and nothing is reported as absent that
;; isn't. A primitive's name is not a class's, as on the JVM.
(define (class-exists? n)
  (and (fx> (string-length n) 0)
       (not (prim-name? n))
       (not (hidden-class? n))   ; a hidden class can't be found by name
       (or (hashtable-ref classes n #f)
           (char=? (string-ref n 0) #\[)
           (and (resource-hook (string-append n ".class")) #t))))
(define (condition-text e)
  (let ([p (open-output-string)]) (display-condition e p) (get-output-string p)))
;; whether e is find-class's NoClassDefFoundError for n itself (jthrow-missing)
(define (missing-itself? e n)
  (and (jinstance? e "java/lang/NoClassDefFoundError")
       (let ([m ((method-proc "java/lang/Throwable" "getMessage()Ljava/lang/String;") e)])
         (and m (string=? (jstring->scheme m) n)))))
(define (try-class n)
  (and (class-exists? n)
       ;; a JDK class outside the boot layer is "class not found" from the
       ;; linker (classpath.clj outside-boot-layer?), which find-class makes a
       ;; NoClassDefFoundError naming the class itself: to a lookup by name
       ;; it isn't there, a ClassNotFoundException as on HotSpot. One that
       ;; is there but needs a missing class names that class, and stays
       (guard (e [(and (jobj? e) (missing-itself? e n)) #f]
                 [(not (jobj? e))
                  (jthrow "java/lang/NoClassDefFoundError"
                          (string-append (slashes->dots n) ": champaca could not load it: " (condition-text e)))])
         (find-class n))))
;; HotSpot's boot loader: the classes of the modules defined to it (a null
;; loader), never the classpath's, so a loader with no parent that delegates
;; up finds what it would on the JVM. A classpath class it hasn't seen isn't
;; loaded to be turned down: the asking loader may define that name itself.
(defnative "java/lang/ClassLoader.findBootstrapClass(Ljava/lang/String;)Ljava/lang/Class;" (name)
  (let* ([n (slashes (jstring->scheme name))]
         [c (and (or (hashtable-ref classes n #f) (jdk-package? n)) (try-class n))])
    (and c (builtin-loader-sees? #f c) (jmirror c))))
;; the classes a loader has loaded: for a built-in loader, those it can see
;; (below); any other loader has defined none, and finds what its parents
;; have by delegating, as ClassLoader.loadClass does next
(defnative "java/lang/ClassLoader.findLoadedClass0(Ljava/lang/String;)Ljava/lang/Class;" (this name)
  (let ([c (hashtable-ref classes (slashes (jstring->scheme name)) #f)])
    (and c
         (let ([dl (hashtable-ref class-loaders (jclass-cname c) #f)])
           (if dl
               (eq? dl this)   ; a class it defined
               (and (jinstance? this "jdk/internal/loader/BuiltinClassLoader") (builtin-loader-sees? this c))))
         (jmirror c))))

;; The built-in loaders (boot, platform, app) are this runtime's one class
;; table in disguise: each finds the classes the runtime can load and never
;; defines one from bytes itself, which is how the JDK's path would go (the
;; module's reader over jrt, then ClassLoader.defineClass). The
;; module-qualified lookups (Class.forName(Module, String), ServiceLoader
;; providers, resource bundles in jdk.localedata) land here too. What each
;; loader sees follows the JVM: the three delegate to whichever of them a
;; module is defined to, so each finds every JDK class, and only the
;; application loader has a class path, so a classpath class is found through
;; it alone. A resource stream from a named module
;; (Module.getResourceAsStream) comes from the same lookup as a classpath
;; one, for the module's own packages. Class.getResource and the ClassLoader
;; resource methods are overridden above, so the module-qualified
;; findResource is never reached.
;; What a built-in loader (this; #f is the boot loader proper, the one a null
;; loader names in Class.forName) would load, an array going by its element:
;; a class another loader defined is that loader's alone (a proxy's dynamic
;; module is defined to its loader and no built-in loader delegates to it,
;; probed: the platform loader doesn't find a proxy the app loader defined);
;; a classpath class is the application loader's; a JDK class is every
;; built-in loader object's (BootLoader's own BuiltinClassLoader included:
;; it delegates by package to the module's loader, as the JDK's does, and its
;; callers check the module), while the boot loader proper delegates to no
;; one and sees the boot modules only.
(define (builtin-loader-sees? this c)
  (let* ([e (element-name c)] [dl (hashtable-ref class-loaders e #f)])
    (cond [dl (eq? dl this)]
          ;; a class of a named application module (java -p) is the app
          ;; loader's, and every built-in loader finds it there, as they
          ;; delegate by package (the boot loader proper doesn't)
          [(user-class? e) (let ([a (app-loader)]) (and a (or (eq? this a) (and this (named-app-module c) #t))))]
          [(not (in-boot-layer? c)) #f]
          [this #t]
          [else (not (module-loader (class-module c)))])))
;; once initPhase2 has built the boot layer, a JDK class is there only when a
;; module of the layer owns its package: a module the layer didn't resolve
;; (jdk.incubator.vector without --add-modules, anything --limit-modules
;; leaves out) has no classes for the JVM's loaders. An array goes by its
;; element, a primitive is java.lang's (class-package), and champaca's own
;; classes (lambdas, annotations, package champaca) are always there.
(define boot-layer-complete #f)
(define (boot-layer-complete!) (set! boot-layer-complete #t))
(define (in-boot-layer? c)
  (or (not boot-layer-complete)
      (let ([p (class-package c)])
        (or (string=? p "champaca") (and (hashtable-ref package-modules p #f) #t)))))
;; the same for classpath.clj, which resolves the classes bytecode names
;; (outside-boot-layer?), and libcache.clj, which records the modules a library cache entry
;; needs (user-deps): the name of the module holding slashed package p,
;; #f when none does, #t before the boot layer exists
(define (layer-module-name p)
  (if boot-layer-complete
      (let ([m (hashtable-ref package-modules p #f)])
        (and m (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name")))))
      #t))
;; whether a class not loaded yet would be a JDK class: its package is one
;; the boot layer's modules own (before the layer exists, anything may be)
(define (jdk-package? n)
  (or (fxzero? (hashtable-size package-modules))
      (let ([m (hashtable-ref package-modules (package-dir n) #f)])
        ;; not a module of the application loader's (java -p)
        (and m (not (and (app-loader) (eq? (module-loader m) (app-loader)))) #t))))
;; the application loader's modules from files (java -p's module path), as
;; classpath.clj's lookups need them: name, a tab, the module's jar or exploded
;; directory, a line each, in the order the loader itself searches them for
;; a resource in no package (its nameToModule map's, which
;; BuiltinClassLoader.findMiscResource walks), so a module the path held twice
;; is the copy it resolved; #t before the boot layer exists
(define (app-module-roots)
  (if (not boot-layer-complete)
      #t
      (let* ([ld (app-loader)]
             [ntm (jslot ld (jfield-index "jdk/internal/loader/BuiltinClassLoader" "nameToModule"))]
             [vals ((jvirt ntm "values()Ljava/util/Collection;") ntm)]
             [arr ((jvirt vals "toArray()[Ljava/lang/Object;") vals)])
        (apply string-append
               (filter values
                       (map (lambda (mref)
                              (let* ([d ((jvirt mref "descriptor()Ljava/lang/module/ModuleDescriptor;") mref)]
                                     [name ((jvirt d "name()Ljava/lang/String;") d)]
                                     [loc ((jvirt mref "location()Ljava/util/Optional;") mref)])
                                (and (eqv? 1 ((jvirt loc "isPresent()Z") loc))
                                     (let ([uri ((jvirt loc "get()Ljava/lang/Object;") loc)])
                                       (and (equal? "file" (jstring->scheme ((jvirt uri "getScheme()Ljava/lang/String;") uri)))
                                            (string-append (jstring->scheme name) "\t"
                                                           (jstring->scheme ((jvirt uri "getPath()Ljava/lang/String;") uri))
                                                           "\n"))))))
                            (vector->list (jarr-vec arr))))))))
;; a named module's resource from its own jar or directory (classpath.clj's
;; hook): the bytes, or #f
(define module-resource-hook (lambda (mn path) #f))
(define (set-module-resource-hook! f) (set! module-resource-hook f))
;; the class file the application loader would read for a slashed name from
;; the class path (jit.clj's hook, which says what it answers), or #f
(define classpath-class-hook (lambda (cname parse?) #f))
(define (set-classpath-class-hook! f) (set! classpath-class-hook f))
;; what the JDK's application loader does with a name it can't load as a
;; class: it reads <s with . as />.class from the class path all the same
;; and defines it, so bytes there are a NoClassDefFoundError, from
;; ClassLoader.checkName for a name with a slash ("IllegalName: s") and from
;; the class file parser for one whose bytes name another class (a..b.C
;; finds a/b/C's file in a directory). URLClassPath finds nothing for a
;; path with a leading slash.
(define (misnamed-classpath-class s)
  (let* ([p (slashes s)]
         [slash? (memv #\/ (string->list s))]
         ;; checkName refuses a slashed name before the bytes are parsed
         [r (and (not (char=? (string-ref p 0) #\/)) (classpath-class-hook p (not slash?)))]
         [after (lambda (k) (and (fx>= (string-length r) (string-length k))
                                 (string=? (substring r 0 (string-length k)) k)
                                 (substring r (string-length k) (string-length r))))])
    (when r
      (cond [slash? (jthrow "java/lang/NoClassDefFoundError" (string-append "IllegalName: " s))]
            [(after "ok ") => (lambda (real) (jthrow "java/lang/NoClassDefFoundError" (string-append p " (wrong name: " real ")")))]
            [(after "format ") => (lambda (msg) (jthrow "java/lang/ClassFormatError" msg))]
            [(after "version ") => (lambda (msg) (jthrow "java/lang/UnsupportedClassVersionError" msg))]
            [(after "error ") => (lambda (msg) (jthrow "java/lang/NoClassDefFoundError"
                                                      (string-append (slashes->dots p) ": champaca could not load it: " msg)))]
            [else (error 'champaca "classpath-class-hook answered" r)]))
    #f))
(define (class-or-null this name)
  ;; loadClass takes a binary name: one with slashes or an array descriptor is
  ;; not found. A loader other than the application one never loads a
  ;; classpath class, not even to turn it down: a loader of the program's own
  ;; may be about to define that name itself
  (let* ([s (jstring->scheme (nn name))]
         [app? (let ([a (app-loader)]) (and a (eq? this a)))])
    (and (fx> (string-length s) 0)
         (not (char=? (string-ref s 0) #\[))
         (if (memv #\/ (string->list s))
             (and app? (misnamed-classpath-class s))
             (let ([n (slashes s)])
               (or (and (or (hashtable-ref classes n #f) (jdk-package? n) app?)
                        (let ([c (try-class n)])
                          (and c (builtin-loader-sees? this c) (jmirror c))))
                   ;; only a path the resource hook refuses (an empty
                   ;; segment inside it) can have a file class-exists?
                   ;; didn't see
                   (and app? (inner-empty-segment? n) (misnamed-classpath-class s))))))))
(define (inner-empty-segment? n)
  (let ([len (string-length n)])
    (let loop ([i 2])
      (and (fx< i len)
           (or (and (char=? (string-ref n i) #\/) (char=? (string-ref n (fx- i 1)) #\/))
               (loop (fx+ i 1)))))))
;; --- classes defined at run time -------------------------------------------------
;; ClassLoader.defineClass, Lookup.defineClass and defineHiddenClass, Proxy
;; (JavaLangAccess.defineClass) and Unsafe.defineClass0 end in one family of
;; natives, which hand the bytes to loader.clj (define-class-hook) to parse,
;; link and register as a class of this run, or to restore from the library
;; cache when a loader of the program's own (not a built-in one) defines a
;; named, non-hidden class from bytes an earlier run saw. The bytes cross
;; as a Latin-1 string, since a byte array doesn't. The defining loader is
;; kept (class-loaders, for getClassLoader and findLoadedClass) and a hidden
;; class's name marked (hidden-classes).
(define define-class-hook #f)
(define (set-define-class-hook! f) (set! define-class-hook f))
(define (define-class-hook* . args) (call-unbounded (lambda () (apply define-class-hook args))))   ; champaca's work, not the program's
(define (bytes->latin1 v off len)
  (let ([s (make-string len)])
    (do ([i 0 (fx+ i 1)]) ((fx= i len) s)
      (string-set! s i (integer->char (fxlogand (vector-ref v (fx+ off i)) #xff))))))
;; a class nothing else has, asked of the loader whose class names it (a
;; loader of the program's own: the built-in ones answer from the class table
;; already); #t when that defined it. A name already being asked on this
;; thread isn't asked again, so a loader whose loadClass names the class it
;; is defining can't loop. The loader's ClassNotFoundException means no.
(define asking-loader (make-thread-parameter '()))
(define (ask-resolving-loader name)
  (let ([l (resolving-loader)])
    (and l (not (jinstance? l "jdk/internal/loader/BuiltinClassLoader"))
         (not (member name (asking-loader)))
         ;; the loader's own code resolves nothing through it
         (parameterize ([asking-loader (cons name (asking-loader))] [resolving-loader #f])
           ;; only ClassNotFoundException means not there. Any other error
           ;; the loader throws (ClassFormatError, LinkageError) is the
           ;; resolution's, as on the JVM: it is kept for the name, and the
           ;; instruction that names the class throws it (jthrow-missing)
           (guard (e [(and (jobj? e) (jinstance? e "java/lang/ClassNotFoundException")) #f]
                     [(jobj? e) (hashtable-set! resolution-errors name e)
                                (set! resolution-error-count (fx+ resolution-error-count 1))
                                (hashtable-set! resolution-error-stamps name resolution-error-count)
                                #f])
             ((jvirt l "loadClass(Ljava/lang/String;)Ljava/lang/Class;") l (jstring (slashes->dots name)))
             (and (hashtable-ref classes name #f) #t))))))
;; when each name's resolution error was recorded: defineClass rethrows one
;; for a missing supertype only if this definition's own lookup recorded it
(define resolution-error-count 0)
(define resolution-error-stamps (make-hashtable string-hash string=?))
;; define-class-hook*'s answer for the bytes, "<kind> <arg>". What fails past
;; parsing (a superclass that isn't there, a link error) is a
;; NoClassDefFoundError naming the cause, as a class the JVM can't define is
;; a LinkageError; a supertype the loader itself failed on (a class extending
;; itself: its nested definition threw ClassCircularityError) is that error,
;; as the JVM rethrows a failed resolution's error, when this definition's
;; own lookup (after stamp) recorded it.
(define (definition-answer loader name v off len hidden? stamp)
  (guard (e [(and (not (jobj? e))
                  (let ([m (class-not-found-in e)])
                    (and m (fx> (hashtable-ref resolution-error-stamps m 0) stamp)
                         (hashtable-ref resolution-errors m #f))))
             => raise]
            ;; a supertype that's nowhere: the JVM's message is its slashed name
            [(and (not (jobj? e)) (class-not-found-in e)) => (lambda (m) (jthrow "java/lang/NoClassDefFoundError" m))]
            [(not (jobj? e)) (jthrow "java/lang/NoClassDefFoundError" (condition-text e))])
    (parameterize ([resolving-loader loader])
      (define-class-hook* name (bytes->latin1 v off len) hidden?
                         (and loader (not hidden?)
                              (not (jinstance? loader "jdk/internal/loader/BuiltinClassLoader")))))))
;; the JVM's error for an answer that isn't "ok": "bad", "link
;; <error class>|<text>" (link.clj check-definition!), "wrong" (the JVM's
;; message keeps both names slashed) or "dup"
(define (throw-definition-refusal kind arg name)
  (cond [(string=? kind "bad") (jthrow "java/lang/ClassFormatError" arg)]
        [(string=? kind "link")
         (let ([bar (let find ([k 0]) (if (char=? (string-ref arg k) #\|) k (find (fx+ k 1))))])
           (jthrow (substring arg 0 bar) (substring arg (fx+ bar 1) (string-length arg))))]
        [(string=? kind "wrong")
         (jthrow "java/lang/NoClassDefFoundError" (string-append name " (wrong name: " arg ")"))]
        [(string=? kind "dup")
         (jthrow "java/lang/LinkageError"
                 (string-append "loader attempted duplicate class definition for " (slashes->dots arg)
                                " (one class table here, whatever the loader)"))]))
;; the class named cname just defined: noted hidden and with its loader, its
;; mirror given the class data and the domain it was defined with (its
;; getProtectionDomain)
(define (defined-mirror cname loader hidden? classdata pd)
  (when hidden? (hashtable-set! hidden-classes cname #t))
  (when loader (hashtable-set! class-loaders cname loader))
  (let ([m (jmirror (find-class cname))])
    (when classdata (jslot-set! m (jfield-index "java/lang/Class" "classData") classdata))
    (when pd (jslot-set! m (jfield-index "java/lang/Class" "protectionDomain") pd))
    m))
(define (define-class! loader given v off len hidden? classdata pd)
  (unless (and (fx>= off 0) (fx>= len 0) (fx<= (fx+ off len) (vector-length v)))
    (jthrow "java/lang/ArrayIndexOutOfBoundsException"
            (format "Array region ~a..~a out of bounds for length ~a" off (+ off len) (vector-length v))))
  (let* ([stamp resolution-error-count]
         [name (and given (slashes (jstring->scheme given)))]
         [r (definition-answer loader name v off len hidden? stamp)]
         [sp (let loop ([i 0]) (if (char=? (string-ref r i) #\space) i (loop (fx+ i 1))))]
         [kind (substring r 0 sp)]
         [arg (substring r (fx+ sp 1) (string-length r))])
    (if (member kind '("bad" "link" "wrong" "dup"))
        (throw-definition-refusal kind arg name)
        (defined-mirror arg loader hidden? classdata pd))))
(defnative "java/lang/ClassLoader.defineClass1(Ljava/lang/ClassLoader;Ljava/lang/String;[BIILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;"
  (loader name b off len pd source)
  (define-class! loader name (jarr-vec (nn b)) off len #f #f pd))
(defnative "java/lang/ClassLoader.defineClass2(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/nio/ByteBuffer;IILjava/security/ProtectionDomain;Ljava/lang/String;)Ljava/lang/Class;"
  (loader name b off len pd source)
  ;; only a direct buffer gets here (ClassLoader.defineClass copies a heap
  ;; one into a byte[] first): its bytes sit in native memory at address,
  ;; from the position given, which is what Unsafe gave it
  (let ([hb (jslot (nn b) (jfield-index "java/nio/ByteBuffer" "hb"))])
    (if hb
        (define-class! loader name (jarr-vec hb) (fx+ off (jslot b (jfield-index "java/nio/ByteBuffer" "offset"))) len #f #f pd)
        (let* ([addr (jslot b (jfield-index "java/nio/Buffer" "address"))]
               [v (make-vector len)])
          (do ([i 0 (fx+ i 1)]) ((fx= i len))
            (vector-set! v i (foreign-ref 'unsigned-8 addr (fx+ off i))))
          (define-class! loader name v 0 len #f #f pd)))))
(defnative "java/lang/ClassLoader.defineClass0(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/String;[BIILjava/security/ProtectionDomain;ZILjava/lang/Object;)Ljava/lang/Class;"
  (loader lookup name b off len pd initialize flags classdata)
  ;; flags: 1 nestmate (the lookup class's nest host becomes its own), 2 hidden, 4 strong, 8 vm annotations
  (let ([m (define-class! loader name (jarr-vec (nn b)) off len (fxpositive? (fxlogand flags 2)) classdata pd)])
    (when (and lookup (fxpositive? (fxlogand flags 1)))
      (hashtable-set! dynamic-nest-hosts (jclass-cname (mirror-class m)) (nest-host (mirror-class lookup))))
    (when (eqv? initialize 1) (jinit (jclass-cname (mirror-class m))))
    m))
(defnative "jdk/internal/misc/Unsafe.defineClass0(Ljava/lang/String;[BIILjava/lang/ClassLoader;Ljava/security/ProtectionDomain;)Ljava/lang/Class;"
  (this name b off len loader pd)
  (define-class! loader name (jarr-vec (nn b)) off len #f #f pd))

(define-override! "jdk/internal/loader/BuiltinClassLoader.loadClassOrNull(Ljava/lang/String;Z)Ljava/lang/Class;"
  (lambda (this name resolve) (class-or-null this name)))
(define-override! "jdk/internal/loader/BuiltinClassLoader.findClass(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Class;"
  (lambda (this mn name) (class-or-null this name)))
(define-override! "jdk/internal/loader/BuiltinClassLoader.findResourceAsStream(Ljava/lang/String;Ljava/lang/String;)Ljava/io/InputStream;"
  (lambda (this mn name)
    (let ([path (jstring->scheme (nn name))])
      (if mn
          ;; a module of the application loader's (java -p) reads its own jar
          ;; or directory, a resource in no package (top.txt, META-INF/...)
          ;; too; a JDK module must own the resource's package
          (let ([b (module-resource-hook (jstring->scheme mn) path)]
                [m (hashtable-ref package-modules (package-dir path) #f)])
            (if b
                (bytes-stream b)
                (and m (string=? (jstring->scheme (jslot m (jfield-index "java/lang/Module" "name"))) (jstring->scheme mn))
                     (resource-stream path))))
          (resource-stream path)))))
