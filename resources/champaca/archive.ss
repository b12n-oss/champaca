;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The archived module graph (ROADMAP 12). A cold boot runs initPhase2 with
;; CDS's configStatus saying "dumping a static archive", so ModuleBootstrap's
;; step 8 fills ArchivedModuleGraph.archivedModuleGraph itself; that object's
;; graph is then written to the cache as rows, and a warm boot's
;; CDS.initializeFromArchive(ArchivedModuleGraph.class) rebuilds it, so boot2
;; takes the finder and configuration from it and never runs
;; SystemModules$default.
;;
;; A row is #(id kind name hash payload): kind o an object of class name, a
;; vector of its slots; a an array of element descriptor name, a vector of its
;; elements; s an interned string, payload its intern key. hash is the
;; identity hash the object had, or #f. A slot or element is a number, #f
;; (null), (r . id) another row, or (s . "C:f") the live value of that static
;; field: an object some other static holds (an enum constant, an empty
;; collection, a loader) is that object, never a copy.

(define module-graph-format 'champaca-module-graph-2)
(define module-graph-root "jdk/internal/module/ArchivedModuleGraph:archivedModuleGraph")
(define module-graph-archive #f)        ; the rows a warm boot read, or #f
(define module-graph-dumped #f)         ; what a cold boot wrote, or #f
(define module-graph-dumping #f)        ; this boot archives the graph (set by boot.clj)
(define (module-graph-dumping! on) (set! module-graph-dumping on))
;; ImmutableCollections places a SetN's or MapN's elements by a salt made
;; from CDS.getRandomSeedForDumping, else the clock: archived sets probe
;; right only under the dump's salt, so a boot that archives or reads the
;; graph gets this one (Set.of iteration is then the same every run, which
;; the spec allows)
(define module-graph-seed #x5eed0f1a2b3c4d5)
(define (module-graph-seed-now) (if (or module-graph-dumping module-graph-archive) module-graph-seed 0))

(define (module-graph-refuse why) (raise (list 'module-graph-refused why)))

;; every static of constant style (an upper-case field name, as the JDK
;; names what its <clinit> sets for good) holding a Java object or array, by
;; value. Another static may be a lazily filled cache (Builder.cachedVersion
;; is null when a warm boot reads it), so what only such a static holds is
;; copied. global-bound?, not Chez's top-level-bound?: asked of a symbol that
;; has no label yet (a static whose class hasn't registered), Chez's mints a
;; gensym label, the class's own code is then compiled against it, and the
;; next run, where the symbol is its own label, reads the static as unset
;; (ValueLayout.JAVA_INT for errno capture was null on every warm run)
(define (module-graph-statics skip)
  (let ([t (make-eq-hashtable)])
    (define (constant-style? n)
      (let ([i (char-index-of n #\:)])
        (and i (fx< (fx+ i 1) (string-length n)) (char-upper-case? (string-ref n (fx+ i 1))))))
    (for-each (lambda (s)
                (let ([n (symbol->string s)])
                  (when (and (champaca-global-name? s) (not (string=? n skip)) (constant-style? n) (global-bound? s))
                    (let ([v (top-level-value s)])
                      (when (and (or (jobj? v) (jarr? v)) (not (eq-hashtable-ref t v #f)))
                        (eq-hashtable-set! t v n))))))
              (oblist))
    t))

(define (module-graph-interned)
  (let ([t (make-eq-hashtable)])
    (let-values ([(ks vs) (hashtable-entries interned)])
      (vector-for-each (lambda (k v) (eq-hashtable-set! t v k)) ks vs))
    (let-values ([(ks vs) (hashtable-entries interned-units)])
      (vector-for-each (lambda (k v) (eq-hashtable-set! t v k)) ks vs))
    t))

;; an object whose identity the JVM's own structures hold: a copy would be a
;; second, disconnected one, so only a constant may refer to it
(define (identity-class? c)
  (let loop ([c c])
    (and c
         (or (member (jclass-cname c) '("java/lang/Class" "java/lang/Thread" "java/lang/ClassLoader" "java/lang/Module"
                                        "java/lang/ModuleLayer" "java/lang/module/ModuleReader"))
             (and (jclass-super c) (loop (find-class (jclass-super c))))))))

;; root's graph as a datum to write, or raises module-graph-refused
(define (module-graph-dump root)
  (let ([statics (module-graph-statics module-graph-root)]
        [interns (module-graph-interned)]
        [ids (make-eq-hashtable)]
        [rows '()]
        [static-hashes '()]   ; ("C:f" . hash): a constant's hash archived sets were laid out by
        [n 0])
    (define (enc x)
      (cond [(or (fixnum? x) (bignum? x) (flonum? x)) x]
            [(not x) #f]
            [(or (jobj? x) (jarr? x))
             (let ([s (eq-hashtable-ref statics x #f)])
               (if s
                   (begin (let ([h (eq-hashtable-ref id-hashes x #f)])
                            (when (and h (not (assoc s static-hashes))) (set! static-hashes (cons (cons s h) static-hashes))))
                          (cons 's s))
                   (cons 'r (visit x))))]
            [else (module-graph-refuse (format "a ~s in a slot" x))]))
    (define (visit x)
      (or (eq-hashtable-ref ids x #f)
          (let ([id n])
            (set! n (fx+ n 1))
            (eq-hashtable-set! ids x id)
            (let ([h (eq-hashtable-ref id-hashes x #f)])
              (cond
                [(eq-hashtable-ref interns x #f)
                 => (lambda (k) (add-row! (vector id 's "" h k)))]
                [(jarr? x)
                 ;; the payload first: enc visits children, which add rows
                 (let* ([e (jarr-elem x)]
                        [p (if (member e '("B" "C" "I" "J" "S" "Z" "F" "D"))
                               (vector-copy (jarr-vec x))
                               (vector-map enc (jarr-vec x)))])
                   (add-row! (vector id 'a e h p)))]
                [else
                 (let ([cn (jclass-cname (jobj-cls x))])
                   (when (identity-class? (jobj-cls x))
                     (module-graph-refuse (string-append "a " cn " no constant holds")))
                   (when (jobj-lock x) (module-graph-refuse (string-append "a locked " cn)))
                   (let ([p (vector-map enc (jobj-slots x))])
                     (add-row! (vector id 'o cn h p))))]))
            id)))
    (define (add-row! r) (set! rows (cons r rows)))
    (let ([root-id (visit root)])
      (vector module-graph-format root-id (list->vector (reverse rows)) static-hashes))))

;; the object a datum describes, or #f when it can't be rebuilt
(define module-graph-path #f)           ; where the archive was read, deleted when it fails
(define (module-graph-load datum)
  (guard (e [(not (java-halt? e)) (module-graph-unusable! e) #f])
    (check-module-graph-format! datum)
    (check-static-hashes! (vector-ref datum 3))
    (let* ([rows (vector-ref datum 2)] [objs (module-graph-objects rows)])
      (fill-module-graph! rows objs)
      ;; a class initializes before its first object is used, as it would
      ;; have for the boot that made them
      (vector-for-each (lambda (r) (when (eq? (vector-ref r 1) 'o) (jinit (vector-ref r 2)))) rows)
      (vector-ref objs (vector-ref datum 1)))))

;; what went wrong, for CHAMPACA_BT: a Java exception as its toString (the
;; object itself prints every class it reaches)
(define (module-graph-failure-text e)
  (cond [(and (pair? e) (eq? (car e) 'module-graph-refused)) (cadr e)]
        [(condition? e) (condition-text e)]
        [(jobj? e) (guard (_ [#t "a Java exception"]) (jstring->scheme ((jvirt e "toString()Ljava/lang/String;") e)))]
        [else e]))

;; a load that failed: said under CHAMPACA_BT, and the file is deleted so a
;; later boot doesn't try it again (it compiles SystemModules into a
;; generation instead)
(define (module-graph-unusable! e)
  (when (getenv "CHAMPACA_BT")
    (format (current-error-port) "module graph archive not used: ~a\n" (module-graph-failure-text e)))
  (when module-graph-path (guard (_ [#t #f]) (delete-file module-graph-path)))
  (set! module-graph-archive #f))

(define (check-module-graph-format! datum)
  (unless (and (vector? datum) (fx= (vector-length datum) 4) (eq? (vector-ref datum 0) module-graph-format))
    (error 'champaca "format")))

;; the value of a "Class:field" static, its class initialized first
(define (archived-static s)
  (jinit (substring s 0 (char-index-of s #\:)))
  (or (top-level-value (string->symbol s)) (error 'champaca "null static" s)))

;; a constant an archived set holds hashes as it did at the dump, or the set
;; is laid out for another hash
(define (check-static-hashes! static-hashes)
  (for-each
   (lambda (sh)
     (let* ([o (archived-static (car sh))] [now (eq-hashtable-ref id-hashes o #f)])
       (cond [(not now) (eq-hashtable-set! id-hashes o (cdr sh))]
             [(not (eqv? now (cdr sh))) (error 'champaca "constant hashed otherwise" (car sh))])))
   static-hashes))

;; each row's object, its slots still empty: objects first, then their slots,
;; since rows refer forward and back
(define (module-graph-objects rows)
  (let ([objs (make-vector (vector-length rows) #f)])
    (vector-for-each
     (lambda (r)
       (vector-set! objs (vector-ref r 0) (module-graph-object (vector-ref r 1) (vector-ref r 2) (vector-ref r 4))))
     rows)
    objs))
(define (module-graph-object kind name p)
  (case kind
    [(s) (jstring-lit p)]
    [(a) (make-jarr name (make-vector (vector-length p) 0))]
    [(o) (let ([c (find-class name)])
           (unless (fx= (vector-length (jclass-template c)) (vector-length p))
             (error 'champaca "field count changed" name))
           (make-jobj c (make-vector (vector-length p) #f)))]))

;; every array's elements and object's fields, and the identity hashes the
;; dump recorded
(define (fill-module-graph! rows objs)
  (define (dec x)
    (cond [(not (pair? x)) x]
          [(eq? (car x) 'r) (vector-ref objs (cdr x))]
          [else (archived-static (cdr x))]))
  (define (fill! v p) (do ([i 0 (fx+ i 1)]) ((fx= i (vector-length p))) (vector-set! v i (dec (vector-ref p i)))))
  (vector-for-each
   (lambda (r)
     (let ([o (vector-ref objs (vector-ref r 0))] [kind (vector-ref r 1)] [h (vector-ref r 3)] [p (vector-ref r 4)])
       (case kind
         [(a) (fill! (jarr-vec o) p)]
         [(o) (fill! (jobj-slots o) p)]
         [else (void)])
       (when (and h (not (eq? kind 's)) (not (eq-hashtable-ref id-hashes o #f)))
         (eq-hashtable-set! id-hashes o h))))
   rows))

(define (module-graph-write! path)
  (when module-graph-dumped
    (call-with-output-file path (lambda (p) (write module-graph-dumped p)) 'replace)))
(define (module-graph-read! path)
  (set! module-graph-path path)
  (set! module-graph-archive
        (and (file-exists? path)
             ;; an unreadable file (cut short, say) is deleted, as a failed load's is
             (guard (e [#t (guard (_ [#t #f]) (delete-file path)) #f]) (call-with-input-file path read)))))

;; the plain boot only: no module path, upgrade path, patch, limit, added
;; module, main module or resolution trace, which ModuleBootstrap would read
(define (module-graph-plain-boot?)
  (let ([get (method-proc "java/lang/System" "getProperty(Ljava/lang/String;)Ljava/lang/String;")])
    (for-all (lambda (p) (not (get (jstring p))))
             '("jdk.module.path" "jdk.module.upgrade.path" "jdk.module.patch.0" "jdk.module.limitmods"
               "jdk.module.addmods.0" "jdk.module.main" "jdk.module.showModuleResolution"))))

;; What a warm boot calls on the archived graph (ArchivedModuleGraph.get and
;; ModuleBootstrap.boot2), called once on the dumped one so the dumping boot
;; compiles them into boot.so: the dump's own boot2 found the static null and
;; called none of them, and a warm boot would otherwise compile them into a
;; generation (test/bundlecache's copied bundle compiles nothing)
(define (module-graph-warm-up! g)
  (let ([c "jdk/internal/module/ArchivedModuleGraph"])
    (for-each (lambda (k) ((method-proc c k) g))
              '("finder()Ljava/lang/module/ModuleFinder;" "configuration()Ljava/lang/module/Configuration;"
                "classLoaderFunction()Ljava/util/function/Function;" "hasSplitPackages()Z" "hasIncubatorModules()Z"))
    ((method-proc c "sameAddModules(Ljava/util/Set;)Z")
     (vector-ref (jobj-slots g) (jfield-index c "addModules")))))

;; initPhase2 for a cold boot: dumping when the boot is the plain one. Any
;; other doesn't archive, so it compiles SystemModules as before (jit.clj
;; reads module-graph-dumping)
(define (module-graph-phase2 run dumping?)
  (when (and dumping? (not (module-graph-plain-boot?))) (set! module-graph-dumping #f))
  (if (and dumping? module-graph-dumping)
      (let ([r (with-cds-status 4 run)])         ; IS_DUMPING_STATIC_ARCHIVE
        (when (eqv? r 0)
          (let ([g (top-level-value (string->symbol module-graph-root))])
            (set! module-graph-dumped (and g (archive-module-graph g)))))
        r)
      (run)))

;; run with CDS.configStatus set to status, then back to what it was
(define (with-cds-status status run)
  (let ([status-sym (string->symbol "jdk/internal/misc/CDS:configStatus")])
    (jinit "jdk/internal/misc/CDS")
    (let ([old (top-level-value status-sym)])
      (dynamic-wind
        (lambda () (set-top-level-value! status-sym status))
        run
        (lambda () (set-top-level-value! status-sym old))))))

;; the dumped graph, or #f: any failure means no archive, never a failed boot
(define (archive-module-graph g)
  (guard (e [(not (java-halt? e))
             (when (getenv "CHAMPACA_BT")
               (format (current-error-port) "module graph not archived: ~a\n" (module-graph-failure-text e)))
             #f])
    (let ([d (module-graph-dump g)]) (module-graph-warm-up! g) d)))
