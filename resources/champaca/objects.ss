;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The object model: object and array records, the class table, class
;; initialization, linkage errors, instanceof and checkcast, null checks,
;; and virtual dispatch with its inline caches. Split from runtime.ss, whose
;; macros and number helpers these use; loaded right after it.

;; --- object model ------------------------------------------------------------

;; kind is 'class, 'interface, 'array or 'prim. fields maps "Decl.name" to a
;; slot index. vtable entries map "name(desc)" to the symbol whose top-level
;; value is the method procedure, a JIT stub until first call.
;; jolt's own Scheme defines jclass? and jclass-name (and other names these
;; files used to redefine, breaking jolt's float printing and Java interop
;; once champaca loaded), so this record spells its predicate and name
;; accessor differently. examples/jolt-after-init.clj checks jolt still works.
(define-record-type (jclass make-jclass ldk-jclass?)
  (fields (immutable name jclass-cname) super interfaces template vtable fields flags kind meta (mutable state) (mutable mirror))
  (sealed #t) (nongenerative champaca-jclass))
;; Sealed and nongenerative: the record type is then known when code using it
;; compiles, so Chez inlines the accessors and the type check. Otherwise each
;; access is a procedure call, about three times the cost.
;; lock is the object's monitor once something synchronizes on it (natives.ss
;; monitor-of). It costs no memory: Chez rounds a record up to 16 bytes, and
;; three fields fit the 32 two did.
;; make-jobj is a macro over the record's own constructor, so an allocation
;; is in line where it's written: a protocol's constructor is a closure,
;; called for every new.
;; lock: #f, the identity hash (a fixnum), or the jmonitor (threads.ss)
(define-record-type (jobj make-jobj* jobj?) (fields cls slots (mutable lock))
  (sealed #t) (nongenerative champaca-jobj))
(define-syntax make-jobj
  (syntax-rules () [(_ cls slots) (make-jobj* cls slots #f)]))
;; An array's element descriptor is interned, so every String[] shares one
;; string and an aastore cell can compare descriptors with eq?. The lookup is
;; by the descriptor object first (one eq? hash for the literal an allocation
;; site passes each time), then by its text.
(define-record-type (jarr make-jarr* jarr?) (fields elem vec) (sealed #t) (nongenerative champaca-jarr))
(define elem-by-object (make-ephemeron-eq-hashtable))
(define elem-by-text (make-hashtable string-hash string=?))
(define (intern-elem e)
  (or (eq-hashtable-ref elem-by-object e #f)
      (let ([i (or (hashtable-ref elem-by-text e #f) (begin (hashtable-set! elem-by-text e e) e))])
        (eq-hashtable-set! elem-by-object e i)
        i)))
(define (make-jarr e v) (make-jarr* (intern-elem e) v))

(define classes (make-hashtable string-hash string=?))

(define (alist->table al)
  (let ([t (make-hashtable string-hash string=?)])
    (for-each (lambda (p) (hashtable-set! t (car p) (cdr p))) al)
    t))

;; The reflection metadata's strings, one copy each: a descriptor or a
;; name recurs across classes, and each registration's literals are its own
;; strings (a warm Spring Boot held 180k of them, 20 MB, before this).
(define meta-strings (make-hashtable string-hash string=?))
(define (intern-meta x)
  (cond [(string? x) (or (hashtable-ref meta-strings x #f) (begin (hashtable-set! meta-strings x x) x))]
        [(pair? x) (cons (intern-meta (car x)) (intern-meta (cdr x)))]
        [else x]))
;; meta is ((methods ...) (fields ...)), each entry (name desc access-flags)
;; in classfile order; reflection reads it and a member's slot is its index.
;; A vtable is keyed by symbols, "name(desc)" interned, so a call looks its
;; method up with eq? rather than hashing a string each time.
;; vtable-alist holds the changes to the superclass's vtable: a new or
;; overriding entry, or (key . #f) for one this class drops.
(define (register-class! name super interfaces template tables flags meta)
  ;; tables: (vtable delta . field layout), one argument so the call stays
  ;; under jolt-metrics' parameter limit
  (define vtable-alist (car tables))
  (define field-alist (cdr tables))
  (hashtable-set! classes name
                  (make-jclass name super interfaces template
                               (let ([t (if super (hashtable-copy (jclass-vtable (find-class super)) #t) (make-eq-hashtable))])
                                 (for-each (lambda (p)
                                             (let ([k (string->symbol (car p))])
                                               (if (cdr p) (eq-hashtable-set! t k (cdr p)) (eq-hashtable-delete! t k))))
                                           vtable-alist)
                                 t)
                               (alist->table field-alist) flags
                               (if (fxpositive? (fxlogand flags #x200)) 'interface 'class)
                               (intern-meta meta) 'loaded #f)))

(define prim-names
  '(("Z" . "boolean") ("B" . "byte") ("C" . "char") ("S" . "short") ("I" . "int")
    ("J" . "long") ("F" . "float") ("D" . "double") ("V" . "void")))

(define (prim-name? n) (exists (lambda (p) (string=? (cdr p) n)) prim-names))

(define (pseudo-class! name kind)
  (let ([c (make-jclass name (if (eq? kind 'array) "java/lang/Object" #f)
                        (if (eq? kind 'array) '("java/lang/Cloneable" "java/io/Serializable") '())
                        (vector) (make-eq-hashtable) (make-hashtable string-hash string=?)
                        #x411 kind '(() ()) 'initialized #f)])
    (hashtable-set! classes name c)
    c))

;; Classes load lazily: a miss asks jolt to load the classfile and register it.
(define (find-class name)
  (or (hashtable-ref classes name #f)
      (cond
        [(fx= 0 (string-length name)) (error 'champaca "empty class name")]
        [(char=? (string-ref name 0) #\[) (pseudo-class! name 'array)]
        [(prim-name? name) (pseudo-class! name 'prim)]
        ;; a class whose bytes are nowhere, or whose superclass or an
        ;; interface's are, is the JVM's NoClassDefFoundError for the code
        ;; that named it (an ldc, a catch clause's type, a descriptor); one
        ;; that is there but fails otherwise stays the error it is, which
        ;; names the cause (class-exists?, condition-text: natives.ss)
        [else (guard (e [(and (not (jobj? e)) (missing-class-of e name)) => jthrow-missing]
                        [(and (not (jobj? e)) (class-format-of e))
                         => (lambda (msg) (jthrow "java/lang/ClassFormatError" msg))]
                        [(and (not (jobj? e)) (linkage-error-of e))
                         => (lambda (x) (jthrow (car x) (cdr x)))])
                (call-unbounded (lambda () (load-hook name))))
              (or (hashtable-ref classes name #f) (error 'champaca "class not registered" name))])))

;; the class a failed load says isn't there: name itself when its bytes are
;; nowhere, else the one a "class not found: X" from the linker names
(define (missing-class-of e name)
  (if (not (class-exists? name)) name (class-not-found-in e)))
;; the rest of t after the first k in it, or #f
(define (text-after t k)
  (let ([n (string-length t)] [m (string-length k)])
    (let loop ([i 0])
      (cond [(fx> (fx+ i m) n) #f]
            [(string=? (substring t i (fx+ i m)) k) (substring t (fx+ i m) n)]
            [else (loop (fx+ i 1))]))))
;; the index of the first c in s, or #f
(define (char-index-of s c)
  (let ([n (string-length s)])
    (let find ([k 0])
      (cond [(fx= k n) #f] [(char=? (string-ref s k) c) k] [else (find (fx+ k 1))]))))
;; the X of a linker's "class not found: X" in a failure's text, or #f
(define (class-not-found-in e)
  (text-after (condition-text e) "class not found: "))
;; HotSpot's ClassFormatError text from a load whose class file (the class
;; named or one it needs) isn't one: link.clj normalize's "class format
;; error in X: <text>", or #f
(define (class-format-of e)
  (let ([r (text-after (condition-text e) "class format error in ")])
    (and r (text-after r ": "))))
;; the same from generated code: an instruction naming such a class (codegen)
(define (jthrow-class-format name msg) (jthrow "java/lang/ClassFormatError" msg))
;; a class the JVM refuses to define (link.clj check-definition!): "linkage
;; error in X: <error class>|<text>" -> (error-class . text), or #f
(define (linkage-error-of e)
  (let* ([r (text-after (condition-text e) "linkage error in ")]
         [rest (and r (text-after r ": "))]
         [bar (and rest (char-index-of rest #\|))])
    (and bar (cons (substring rest 0 bar) (substring rest (fx+ bar 1) (string-length rest))))))
(define (jthrow-linkage name err msg) (jthrow err msg))
;; the JVM's error for a class nothing has: the slashed name, caused by a
;; ClassNotFoundException with the dotted one; or the error the class's
;; loader threw resolving it, every time, as JVMS 5.4.3 has a failed
;; resolution fail the same way again (natives.ss ask-resolving-loader)
(define resolution-errors (make-hashtable string-hash string=?))
;; An array of a missing class is the array's NoClassDefFoundError with the
;; element's cause, and the element's loader error if one was kept
(define (jthrow-missing name)
  (define elem
    (let loop ([i 0])
      (cond [(fx= i (string-length name)) name]
            [(char=? (string-ref name i) #\[) (loop (fx+ i 1))]
            [(and (fx> i 0) (char=? (string-ref name i) #\L)) (substring name (fx+ i 1) (fx- (string-length name) 1))]
            [else name])))
  (let ([le (hashtable-ref resolution-errors elem #f)])
    (when le (raise le)))
  (let ([e (construct "java/lang/NoClassDefFoundError" "(Ljava/lang/String;)V" (jstring name))]
        [cnfe (construct "java/lang/ClassNotFoundException" "(Ljava/lang/String;)V" (jstring (slashes->dots elem)))])
    ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;") e cnfe)
    (raise e)))
;; A call to a method its class doesn't have, which a JVM resolves only
;; when the call runs (JVMS 5.4.3.3): NoSuchMethodError with HotSpot's text,
;; 'ret Class.name(param, param)' in Java's spelling
;; 'int Cls.m(java.lang.String, long[])', as HotSpot names a method in its
;; linkage errors
;; a type descriptor in Java's spelling: "int", "java.lang.String[]"
(define (type-external d)
  (cond [(char=? (string-ref d 0) #\[) (string-append (type-external (substring d 1 (string-length d))) "[]")]
        [(assoc d prim-names) => cdr]
        [else (slashes->dots (desc->class-name d))]))
(define (method-external cname name desc)
  (define external type-external)
  (string-append "'" (external (desc-ret desc)) " " (slashes->dots cname) "." name "("
                 (let loop ([ps (desc-args desc)])
                   (cond [(null? ps) ""]
                         [(null? (cdr ps)) (external (car ps))]
                         [else (string-append (external (car ps)) ", " (loop (cdr ps)))]))
                 ")'"))
(define (jthrow-no-method cname name desc)
  (jthrow "java/lang/NoSuchMethodError" (method-external cname name desc)))
;; a call whose instruction doesn't match the static-ness of the method it
;; resolves to (link.clj check-kind): what as HotSpot's LinkResolver says it
(define (jthrow-icce what cname name desc)
  (jthrow "java/lang/IncompatibleClassChangeError" (string-append what " " (method-external cname name desc))))
(define (method-proc cname key)
  (top-level-value (string->symbol (string-append cname "." key))))

;; <clinit> runs on first active use, superclass first. While it runs the
;; class is 'initializing: the same thread goes ahead (a recursive request),
;; another thread waits for it to finish (JVMS 5.5). A <clinit> that throws
;; leaves the class 'erroneous, and any later use throws NoClassDefFoundError.
(define init-owners (make-weak-eq-hashtable))
(define init-errors (make-weak-eq-hashtable))   ; class -> (first failure . thread name)
(define init-waiters 0)

(define (jinit name)
  (let ([c (find-class name)])
    (unless (eq? (jclass-state c) 'initialized)
      (jinit-slow c name))))

;; JVMS 5.5 step 7: a class's initialization, after its superclass's,
;; initializes each superinterface, direct or not, that declares a
;; non-abstract, non-static method, walking each interfaces array in order
;; and an interface's superinterfaces before it. One with only abstract or
;; static methods waits for a use of its own.
(define (init-default-interfaces c)
  (let walk ([is (jclass-interfaces c)] [seen '()])
    (if (null? is)
        seen
        (let ([n (car is)])
          (if (member n seen)
              (walk (cdr is) seen)
              (let* ([i (find-class n)]
                     [seen (walk (jclass-interfaces i) (cons n seen))])
                (when (exists (lambda (m) (fxzero? (fxlogand (caddr m) #x408))) (car (jclass-meta i)))
                  (jinit n))
                (walk (cdr is) seen)))))))

;; the current Java thread's name, "main" outside any
(define (init-thread-name)
  (if current-jthread
      (jstring->scheme ((method-proc "java/lang/Thread" "getName()Ljava/lang/String;") current-jthread))
      "main"))

;; c's initializer failed with e: the failure and its thread are kept for
;; the NoClassDefFoundError of later uses; answers what reaches the caller,
;; anything but an Error wrapped in an ExceptionInInitializerError (JVMS
;; 5.5 step 11)
(define (init-failure! c e)
  (when (jobj? e)
    (eq-hashtable-set! init-errors c (cons e (init-thread-name))))
  (if (and (jobj? e) (not (jinstance? e "java/lang/Error")))
      (let ([w (begin (jinit "java/lang/ExceptionInInitializerError")
                      (jnew "java/lang/ExceptionInInitializerError"))])
        ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/Throwable;)V") w e)
        w)
      e))

;; the superclass, the interfaces with defaults, then c's own <clinit>
(define (run-initializer! c name)
  (when (jclass-super c) (jinit (jclass-super c)))
  (unless (eq? (jclass-kind c) 'interface) (init-default-interfaces c))
  (let ([sym (string->symbol (string-append name ".<clinit>()V"))])
    (when (global-bound? sym) ((top-level-value sym)))))

;; another thread is initializing c: wait until it's done
(define (wait-for-initializer! c)
  (set! init-waiters (fx+ init-waiters 1))
  (gil-block! (lambda () (not (eq? (jclass-state c) 'initializing))) #f)
  (set! init-waiters (fx- init-waiters 1)))

;; the NoClassDefFoundError for a use of c after its initializer failed,
;; with HotSpot's cause: an ExceptionInInitializerError naming the first
;; failure and its thread
(define (initializer-error c name)
  (let ([first (eq-hashtable-ref init-errors c #f)]
        [e (begin (jinit "java/lang/NoClassDefFoundError") (jnew "java/lang/NoClassDefFoundError"))])
    ((method-proc "java/lang/NoClassDefFoundError" "<init>(Ljava/lang/String;)V")
     e (jstring (string-append "Could not initialize class " (slashes->dots name))))
    (when first
      (let ([w (begin (jinit "java/lang/ExceptionInInitializerError") (jnew "java/lang/ExceptionInInitializerError"))])
        ((method-proc "java/lang/ExceptionInInitializerError" "<init>(Ljava/lang/String;)V")
         w (jstring (string-append "Exception " (jstring-of (car first)) " [in thread \"" (cdr first) "\"]")))
        ((method-proc "java/lang/Throwable" "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;") e w)))
    e))

(define (jinit-slow c name)
  (case (jclass-state c)
    [(loaded)
     (jclass-state-set! c 'initializing)
     (eq-hashtable-set! init-owners c (self))
     (let ([done (lambda (state)
                   (jclass-state-set! c state)
                   (eq-hashtable-delete! init-owners c)
                   (when (fx> init-waiters 0) (gil-poke!)))])
       (guard (e [#t (done 'erroneous) (raise (init-failure! c e))])
         (run-initializer! c name))
       (done 'initialized))]
    [(initializing)
     (unless (eq? (eq-hashtable-ref init-owners c #f) (self))
       (wait-for-initializer! c)
       (jinit-slow c name))]
    [(erroneous) (raise (initializer-error c name))]
    [else (void)]))

(define (jnew name)
  (let ([c (find-class name)])
    (make-jobj c (#3%vector-copy (jclass-template c)))))

(define (jfield-index cname fname)
  (let loop ([c (find-class cname)])
    (or (hashtable-ref (jclass-fields c) (string-append (jclass-cname c) "." fname) #f)
        (and (jclass-super c) (loop (find-class (jclass-super c))))
        (error 'champaca "no such field" cname fname))))

;; A Throwable's backtrace field holds its raw frames (natives.ss), the one
;; Scheme value a Java field keeps. To Java it is an opaque java.lang.Object,
;; as HotSpot's own backtrace is, so reflection reading the field gets
;; getClass, hashCode and toString and nothing else.
(define-record-type backtrace (fields frames) (sealed #t) (nongenerative champaca-backtrace))

(define (class-name-of x)
  (cond [(jobj? x) (jclass-cname (jobj-cls x))]
        [(jarr? x) (string-append "[" (jarr-elem x))]
        [(backtrace? x) "java/lang/Object"]
        [else (error 'champaca "not a Java value" x)]))

;; An object's class is its record already; it went to its name and hashed
;; that back into the class table, on every getClass. An array's comes from
;; a table keyed by its element descriptor, one interned string per array
;; type, so its name is built and hashed once per type.
(define array-classes (make-weak-eq-hashtable))
(define (class-of x)
  (cond [(jobj? x) (jobj-cls x)]
        [(jarr? x)
         (let ([elem (jarr-elem x)])
           (or (#3%eq-hashtable-ref array-classes elem #f)
               (let ([c (find-class (class-name-of x))])
                 (eq-hashtable-set! array-classes elem c)
                 c)))]
        [else (find-class (class-name-of x))]))

;; Every name a class answers to: its own, its superclasses' and all their
;; interfaces'. Built the first time something asks, from the supertypes'
;; own sets, so a subtype question is one string-table probe where it was a
;; walk over the hierarchy comparing names. Weak on the class.
(define ancestor-sets (make-weak-eq-hashtable))
(define (ancestor-names c)
  (or (eq-hashtable-ref ancestor-sets c #f)
      (let ([set (make-hashtable string-hash string=?)])
        (hashtable-set! set (jclass-cname c) #t)
        (for-each (lambda (name)
                    (when name
                      (vector-for-each (lambda (k) (hashtable-set! set k #t))
                                       (hashtable-keys (ancestor-names (find-class name))))))
                  (cons (jclass-super c) (jclass-interfaces c)))
        (eq-hashtable-set! ancestor-sets c set)
        set)))
(define (subclass? cname target)
  (and cname
       (or (string=? cname target)
           (hashtable-contains? (ancestor-names (find-class cname)) target))))

;; elem descriptors: "I", "Ljava/lang/String;", "[I" ...
(define (desc->class-name d)
  (case (string-ref d 0)
    [(#\L) (substring d 1 (fx- (string-length d) 1))]
    [(#\[) d]
    [else (cdr (assoc d prim-names))]))

(define (ref-desc? d) (memv (string-ref d 0) '(#\L #\[)))

(define (class-assignable? from to)
  (cond [(prim-name? from) (string=? from to)]
        [(string=? to "java/lang/Object") #t]
        [(char=? (string-ref from 0) #\[)
         (or (and (member to '("java/lang/Cloneable" "java/io/Serializable")) #t)
             (and (char=? (string-ref to 0) #\[)
                  (let ([fe (substring from 1 (string-length from))]
                        [te (substring to 1 (string-length to))])
                    (or (string=? fe te)
                        (and (ref-desc? fe) (ref-desc? te)
                             (class-assignable? (desc->class-name fe) (desc->class-name te)))))))]
        [else (subclass? from to)]))

(define (jinstance? x target)
  (and (or (jobj? x) (jarr? x)) (class-assignable? (class-name-of x) target)))

(define (nn x) (if x x (jthrow "java/lang/NullPointerException" #f)))

;; An NPE that generated code raises carries its site: the method's tag,
;; Class.name(desc), which each compiled method binds once as npe-tag, and
;; the bci. Both appear in the failing branch only, so the fast path pays
;; nothing. getExtendedNPEMessage (natives.ss) builds the
;; JVM's message from the site's bytecode. Any other NPE has no site and
;; keeps a null message.
(define npe-sites (make-weak-eq-hashtable))
(define (npe-at tag pc)
  (jinit "java/lang/NullPointerException")
  (let ([e (jnew "java/lang/NullPointerException")])
    ((method-proc "java/lang/NullPointerException" "<init>()V") e)
    (eq-hashtable-set! npe-sites e (cons tag pc))
    (raise e)))
(define-syntax nn-at
  (syntax-rules () [(_ x tag pc) (let ([o x]) (if o o (npe-at tag pc)))]))
(define (jathrow-at x tag pc) (raise (nn-at x tag pc)))

(define (jcheckcast x target . site)   ; site: codegen's tag and pc, for the trace
  (if (or (not x) (jinstance? x target))
      x
      (jthrow "java/lang/ClassCastException" (class-cast-message (class-name-of x) target))))

;; Generated code's instanceof and checkcast: a per-site cell holding the
;; last object class seen and the answer for it, since jinstance? compares
;; class names and walks the hierarchy.
(define-syntax jinstance-ic?
  (syntax-rules ()
    [(_ ic x target)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jinstance-miss ic o target)))]))
;; A site that sees many classes (Clojure's RT.seq, a compiler walking mixed
;; collections) misses its one-class cell most of the time, and jinstance?
;; then walks the class's whole hierarchy, every interface included, a
;; string comparison at each. So each class remembers its answers: target
;; string (a site's literal, compared by identity) -> #t or #f. The table per
;; class is found by identity too; both are weak, so neither keeps a class or
;; a unit's literals alive.
(define instance-answers (make-weak-eq-hashtable))
(define (class-instance-of? cls target)
  (let* ([t (or (#3%eq-hashtable-ref instance-answers cls #f)
                (let ([t (make-weak-eq-hashtable)]) (eq-hashtable-set! instance-answers cls t) t))]
         [a (#3%eq-hashtable-ref t target 'unknown)])
    (if (eq? a 'unknown)
        (let ([r (and (class-assignable? (jclass-cname cls) target) #t)])
          (eq-hashtable-set! t target r)
          r)
        a)))
;; An array's answers the same way, by its element descriptor, which is one
;; interned string for every array of that type (jnewarray-interned, clone).
;; jinstance? on an array built its class name with string-append and took
;; substrings of it on every test: Clojure's PersistentHashMap.cloneAndSet
;; (a cast of a cloned INode[]) and ArraySeq.<init> (a test for Object[])
;; had at least 5% of a profiled run from source there. An element string that isn't the
;; interned one only misses the table, so it answers right either way.
(define array-answers (make-weak-eq-hashtable))
(define (array-instance-of? elem target)
  (let* ([t (or (#3%eq-hashtable-ref array-answers elem #f)
                (let ([t (make-weak-eq-hashtable)]) (eq-hashtable-set! array-answers elem t) t))]
         [a (#3%eq-hashtable-ref t target 'unknown)])
    (if (eq? a 'unknown)
        (let ([r (and (class-assignable? (string-append "[" elem) target) #t)])
          (eq-hashtable-set! t target r)
          r)
        a)))
(define (jinstance-miss ic o target)
  (cond [(jobj? o)
         (let ([r (class-instance-of? (jobj-cls o) target)])
           (set-car! ic (jobj-cls o)) (set-cdr! ic r)
           r)]
        [(jarr? o) (array-instance-of? (jarr-elem o) target)]
        [else (jinstance? o target)]))
(define-syntax jcheckcast-ic
  (syntax-rules ()
    [(_ ic x target) (jcheckcast-ic ic x target #f -1)]
    [(_ ic x target tag pc)
     (let ([o x])
       (if (or (not o) (and (jobj? o) (eq? (jobj-cls o) (car ic)) (cdr ic)))
           o
           (begin (unless (jinstance-miss ic o target) (jcheckcast o target tag pc)) o)))]))

(define (jslot x i) (vector-ref (jobj-slots (nn x)) i))
(define (jslot-set! x i v) (vector-set! (jobj-slots (nn x)) i v))

;; Virtual and interface dispatch: one hashtable probe on the receiver's class.
;; Arrays dispatch through Object's vtable, except clone.
(define (array-clone a) (make-jarr* (jarr-elem a) (#3%vector-copy (jarr-vec a))))
;; key is a symbol in generated code; the runtime's own callers pass strings.
(define clone-key (string->symbol "clone()Ljava/lang/Object;"))
(define (jvirt x key)
  (let ([x (nn x)] [k (if (symbol? key) key (string->symbol key))])
    (if (jobj? x)
        (let ([sym (eq-hashtable-ref (jclass-vtable (jobj-cls x)) k #f)])
          (if sym (top-level-value sym) (no-such-virtual (jobj-cls x) k)))
        (cond
          [(not (or (jarr? x) (backtrace? x))) (error 'champaca "virtual call on something that isn't a Java value" x (symbol->string k))]
          [(and (eq? k clone-key) (jarr? x)) array-clone]
          [else (let* ([c (find-class "java/lang/Object")] [sym (eq-hashtable-ref (jclass-vtable c) k #f)])
                  (if sym (top-level-value sym) (no-such-virtual c k)))]))))
;; a vtable miss is an abstract method nothing implemented; the message is
;; the JVM's for a reabstracted default (its other AME wordings differ)
(define (no-such-virtual c k)
  (jthrow "java/lang/AbstractMethodError"
          (string-append "Method " (slashes->dots (jclass-cname c)) "." (symbol->string k) " is abstract")))

;; the vtable entry for conflicting default methods (link.clj link)
(define (icce-stub msg)
  (lambda args (jthrow "java/lang/IncompatibleClassChangeError" msg)))

;; An inline cache: each virtual call site in generated code has a pair,
;; (class . procedure), for the receiver class it saw last, so a hit is one
;; eq? test and no lookup (top-level-value alone costs about 14 ns). A stub
;; is never cached: its method isn't compiled yet, and the compiled
;; procedure that replaces it is what the site should keep.
(define-syntax jvirt-ic
  (syntax-rules ()
    [(_ ic x key tag pc)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jvirt-miss ic o key tag pc)))]))
;; A site that sees many receiver classes misses its cell on most calls, and
;; the lookup behind a miss is a vtable probe, top-level-value (14 ns) and a
;; probe of the stubs. So each class keeps the procedures found for it, by
;; method key: a miss for a class and method seen before is two probes. Only
;; compiled procedures are kept, as in the cell, never a stub. Weak on the
;; class.
(define virt-procs (make-weak-eq-hashtable))
(define (jvirt-miss ic o key tag pc)
  (if (jobj? o)
      (let* ([cls (jobj-cls o)]
             [found (or (#3%eq-hashtable-ref virt-procs cls #f)
                        (let ([t (make-eq-hashtable)]) (eq-hashtable-set! virt-procs cls t) t))]
             [kept (#3%eq-hashtable-ref found key #f)])
        (if kept
            (begin (set-car! ic cls) (set-cdr! ic kept) kept)
            (let ([proc (jvirt o key)])
              (unless (eq-hashtable-contains? stub-procs proc)
                (eq-hashtable-set! found key proc)
                (set-car! ic cls)
                (set-cdr! ic proc))
              proc)))
      (jvirt (or o (npe-at tag pc)) key)))

;; Selection for a call whose resolved method is package-private (JVMS
;; 5.4.5). The vtable is keyed by name and descriptor, which is right when
;; the resolved method is public or protected: any method of that name and
;; descriptor overrides it, so the nearest one wins. A package-private mA is
;; overridden only by a method in its own run-time package (package and
;; defining loader), or by one overriding a method that overrides mA
;; (transitively, through a public or protected one between). So the
;; classes from just below the resolved method's down to the receiver's are
;; walked in that order, keeping every overriding declaration as an anchor
;; for the ones below it; the deepest overrider is selected. Generated code
;; reaches this through jvirt-pp-ic on a miss; reflection and method handles
;; through virtual-target.
(define (own-method-flags c name desc)
  (let loop ([ms (car (jclass-meta c))])
    (cond [(null? ms) #f]
          [(and (string=? name (caar ms)) (string=? desc (cadar ms))) (caddar ms)]
          [else (loop (cdr ms))])))
;; same-runtime-package? (package and defining loader) is reflect.ss's, for nest hosts
(define (package-private-selection rc dc name desc)
  ;; rc the receiver's class, dc the resolved method's; #f when rc isn't
  ;; below dc (dispatch then goes by the vtable)
  (let ([below (let loop ([c rc] [acc '()])
                 (cond [(eq? c dc) acc]
                       [(jclass-super c) (loop (find-class (jclass-super c)) (cons c acc))]
                       [else #f]))])
    (and below
         (let loop ([cs below] [anchors (list (cons dc (or (own-method-flags dc name desc) 0)))] [sel dc])
           (if (null? cs)
               sel
               (let* ([c (car cs)] [f (own-method-flags c name desc)])
                 (if (and f (fxzero? (fxlogand f #x000a))   ; neither private nor static
                          (exists (lambda (a) (or (fxpositive? (fxlogand (cdr a) #x0005))
                                                  (same-runtime-package? c (car a))))
                                  anchors))
                     (loop (cdr cs) (cons (cons c f) anchors) c)
                     (loop (cdr cs) anchors sel))))))))
(define (package-private-select o dc key)
  (let* ([i (let find ([i 0]) (if (char=? (string-ref key i) #\() i (find (fx+ i 1))))]
         [sel (and (jobj? o) (package-private-selection (jobj-cls o) dc (substring key 0 i) (substring key i (string-length key))))])
    (cond [(not sel) (jvirt o key)]
          ;; a selected method with no procedure (abstract, and the runtime
          ;; doesn't override it, as natives.ss does VarHandle's) is
          ;; AbstractMethodError, as for any abstract selection
          [(global-bound? (string->symbol (string-append (jclass-cname sel) "." key)))
           (method-proc (jclass-cname sel) key)]
          [else (no-such-virtual (jobj-cls o) (string->symbol key))])))
;; The selection depends on the receiver's class, the resolved method's and
;; the key alone, so it is kept per receiver class: a call site whose
;; receivers alternate (HashMap.putVal's newNode, with HashMap and
;; LinkedHashMap) walks once per class, not once per change. A stub isn't
;; kept, as the inline caches don't keep one.
(define package-private-selections (make-weak-eq-hashtable))   ; class -> eq table key symbol -> ((dc . proc) ...)
(define (package-private-target o dc key)
  (if (not (jobj? o))
      (jvirt o key)
      (let* ([rc (jobj-cls o)] [k (if (symbol? key) key (string->symbol key))]
             [t (or (eq-hashtable-ref package-private-selections rc #f)
                    (let ([t (make-eq-hashtable)]) (eq-hashtable-set! package-private-selections rc t) t))]
             [hit (assq dc (eq-hashtable-ref t k '()))])
        (if hit
            (cdr hit)
            (let ([proc (package-private-select o dc (if (string? key) key (symbol->string key)))])
              (unless (eq-hashtable-contains? stub-procs proc)
                (eq-hashtable-set! t k (cons (cons dc proc) (eq-hashtable-ref t k '()))))
              proc)))))
;; a reflected or looked-up instance method's target on receiver o: a private
;; one is its own (it overrides nothing and nothing overrides it), a
;; package-private one by the rule above, any other through the vtable
(define (virtual-target o dcname key flags)
  (cond [(fxpositive? (fxlogand flags #x0002)) (nn o) (method-proc dcname key)]
        [(fxzero? (fxlogand flags #x0005)) (package-private-target (nn o) (find-class dcname) key)]
        [else (jvirt o key)]))
;; generated code's call of a resolved package-private method: an inline
;; cache as jvirt-ic's, filled by the selection
(define-syntax jvirt-pp-ic
  (syntax-rules ()
    [(_ ic x key dc tag pc)
     (let ([o x])
       (if (and (jobj? o) (eq? (jobj-cls o) (car ic)))
           (cdr ic)
           (jvirt-pp-miss ic o key dc tag pc)))]))
(define (jvirt-pp-miss ic o key dc tag pc)
  (let ([proc (package-private-target (or o (npe-at tag pc)) (symbol-class dc) key)])
    (when (and (jobj? o) (not (eq-hashtable-contains? stub-procs proc)))
      (set-car! ic (jobj-cls o))
      (set-cdr! ic proc))
    proc))

;; Generated code names classes by symbol: the class is found through an eq
;; table instead of hashing its name on every new and every initialization
;; check.
(define class-by-symbol (make-eq-hashtable))
(define (symbol-class s)
  (or (eq-hashtable-ref class-by-symbol s #f)
      (let ([c (find-class (symbol->string s))]) (eq-hashtable-set! class-by-symbol s c) c)))
(define (jinit-sym s)
  (let ([c (symbol-class s)])
    (unless (eq? (jclass-state c) 'initialized) (jinit-slow c (jclass-cname c)))))
(define (jnew-sym s)
  (let ([c (symbol-class s)]) (make-jobj c (#3%vector-copy (jclass-template c)))))
;; The same from generated code, through a per-site cell that holds the class
;; once it is initialized, which it then stays: the check becomes one car.
;; The trailing tag and pc (codegen's site) ride into the slow path's call,
;; where a stack trace of an initializer's failure reads the pc (reflect.ss)
(define-syntax jinit-ic
  (syntax-rules ()
    [(_ cell s) (unless (car cell) (jinit-cell! cell s))]
    [(_ cell s tag pc) (unless (car cell) (jinit-cell! cell s tag pc))]))
(define (jinit-cell! cell s . site)
  (jinit-sym s)
  (let ([c (symbol-class s)])
    (when (eq? (jclass-state c) 'initialized) (set-car! cell c))))
(define-syntax jnew-ic
  (syntax-rules ()
    [(_ cell s) (jnew-ic cell s #f -1)]
    [(_ cell s tag pc) (let ([c (car cell)])
                         (if c
                             (make-jobj c (#3%vector-copy (jclass-template c)))
                             (begin (jinit-cell! cell s tag pc) (jnew-sym s))))]))
