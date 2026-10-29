;; Where a champaca process's memory goes. Boots, runs a main class, then
;; measures the live heap after a full collection:
;;
;;   bb heap <classpath> <Main> [types|roots|java] [args...]
;;
;; types  Chez's (object-counts) by type, bytes and count, top 40, with the
;;        heap's allocated and committed sizes and the collector's settings.
;; roots  what each root holds, with symbols and code objects as leaves (a
;;        symbol's value, which compute-composition would follow, reaches
;;        everything from any compiled method): the runtime's string-keyed
;;        tables, the class table, main.clj's state by key, then the symbol
;;        and code names. Each object counts for the first root reaching it.
;; java   the Java object graph from every top-level symbol bound to a Java
;;        object, array or bignum (the static fields) and the intern table:
;;        objects, arrays by element type, bignums in slots.
;;
;; Spike 71 (2026-10-29) wrote these to find the 592 MB of a warm Spring
;; Boot: packed entries as 4-byte-a-char strings, per-class copies of every
;; method key, inspector information. CHAMPACA_CACHE_DIR and the other
;; variables apply as to any run; a warm cache gives a run's own heap, a cold
;; one includes the compiler's leftovers.
(require '[champaca.api :as ldk] '[jolt.scheme :as scm])

(def mb #(quot % 1048576))

(defn- type-name [t] (if (scm/call "record-type-descriptor?" t) (str "record " (scm/call "record-type-name" t)) (pr-str t)))

(defn types []
  (binding [*out* *err*]
    (println "gc settings:" (scm/eval-string "(format \"trip ~a MB, radix ~a, reserve ~a, max gen ~a\" (quotient (collect-trip-bytes) 1048576) (collect-generation-radix) (heap-reserve-ratio) (collect-maximum-generation))"))
    (println "after main:" (scm/eval-string "(format \"allocated ~a MB, committed ~a MB\" (quotient (bytes-allocated) 1048576) (quotient (current-memory-bytes) 1048576))"))
    (println (scm/eval-string "
      (let* ([rows (map (lambda (e)
                          (cons (let ([t (car e)]) (if (record-type-descriptor? t) (format \"record ~a\" (record-type-name t)) (format \"~s\" t)))
                                (fold-left (lambda (acc g) (cons (+ (car acc) (cadr g)) (+ (cdr acc) (cddr g)))) (cons 0 0) (cdr e))))
                        (object-counts))]
             [sorted (list-sort (lambda (a b) (> (cddr a) (cddr b))) rows)]
             [total (fold-left + 0 (map cddr rows))])
        (with-output-to-string
          (lambda ()
            (printf \"heap by type, ~a MB in all:~%\" (quotient total 1048576))
            (let loop ([rs sorted] [n 0])
              (unless (or (null? rs) (= n 40))
                (printf \"  ~8d KB ~10d  ~a~%\" (quotient (cddr (car rs)) 1024) (cadr (car rs)) (car (car rs)))
                (loop (cdr rs) (+ n 1)))))))"))))

(def walker "
  (define heap-seen (make-eq-hashtable))
  (define (heap-tally name x)
    (let ([pairs 0] [strs 0] [chars 0] [vecs 0] [slots 0] [recs 0] [bvs 0] [bvb 0])
      (define (walk x)
        (cond [(pair? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (set! pairs (+ pairs 1)) (walk (car x)) (walk (cdr x)))]
              [(string? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (set! strs (+ strs 1)) (set! chars (+ chars (string-length x))))]
              [(bytevector? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (set! bvs (+ bvs 1)) (set! bvb (+ bvb (bytevector-length x))))]
              [(vector? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (set! vecs (+ vecs 1)) (set! slots (+ slots (vector-length x))) (vector-for-each walk x))]
              [(#%$tlc? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (walk (#%$tlc-keyval x)) (walk (#%$tlc-next x)))]
              [(box? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (walk (unbox x)))]
              [(hashtable? x) (unless (eq-hashtable-ref heap-seen x #f) (eq-hashtable-set! heap-seen x #t) (set! recs (+ recs 1))
                                (call-with-values (lambda () (hashtable-entries x)) (lambda (ks vs) (vector-for-each walk ks) (vector-for-each walk vs))))]
              [(#%$record? x)
               (unless (eq-hashtable-ref heap-seen x #f)
                 (eq-hashtable-set! heap-seen x #t) (set! recs (+ recs 1))
                 (let* ([rtd (#%$record-type-descriptor x)] [n (vector-length (record-type-field-names rtd))])
                   (do ([i 0 (+ i 1)]) ((= i n)) (walk ((record-accessor rtd i) x)))))]
              [else (void)]))
      (walk x)
      (format \"~22a strings ~4d MB (~8d, ~9d chars)  pairs ~4d MB (~8d)  vectors ~4d MB (~7d)  records ~7d  bytevectors ~3d MB\"
              name (quotient (* 4 chars) 1048576) strs chars (quotient (* 16 pairs) 1048576) pairs (quotient (* 8 slots) 1048576) vecs recs (quotient bvb 1048576))))")

(defn roots []
  (scm/eval-string walker)
  (binding [*out* *err*]
    (doseq [t ["interned" "natives" "overrides" "elem-by-text" "interned-units" "frame-methods" "user-classes" "class-loaders"
               "package-modules" "jpackage-table" "hidden-methods" "defined-supers" "call-cache" "const-sites" "indy-sites" "class-by-symbol" "classes"]]
      (println (try (scm/eval-string (str "(heap-tally \"" t "\" " t ")")) (catch Throwable e (str t ": " (ex-message e))))))
    (let [st @@#'champaca.main/state
          total (scm/eval-string "(lambda (x) (fold-left + 0 (map cddr (compute-composition x))))")]
      (println (scm/call "heap-tally" "main.clj state" st))
      (println "  state keys by reachable MB (compute-composition, overlapping):")
      (doseq [[k m v] (sort-by (comp - second) (for [[k v] st] [k (mb (total v)) v]))
              :when (pos? m)]
        (println (format "  %5d MB  %s%s" m k (cond (map? v) (str "  (" (count v) " entries)") (coll? v) (str "  (" (count v) ")") :else "")))))
    (println (scm/eval-string "(let ([n 0] [cs (make-eq-hashtable)] [syms (filter (lambda (s) (not (gensym? s))) (oblist))]) (for-each (lambda (s) (when (and (top-level-bound? s) (procedure? (top-level-value s))) (let ([c (#%$closure-code (top-level-value s))]) (unless (eq-hashtable-ref cs c #f) (eq-hashtable-set! cs c #t) (let ([nm (#%$code-name c)]) (when (string? nm) (set! n (+ n (string-length nm))))))))) syms) (format \"code names ~a MB; ~a interned symbols, names ~a MB (gensyms left out: forcing their names makes them)\" (quotient (* 4 n) 1048576) (length syms) (quotient (fold-left (lambda (acc s) (+ acc (* 4 (string-length (symbol->string s))))) 0 syms) 1048576)))"))))

(defn java []
  (scm/eval-string "(load \"tools/heap-java.ss\")")
  (binding [*out* *err*] (println (scm/eval-string "(java-heap-report)"))))

(let [[cp mc mode & args] *command-line-args*
      mode (or mode "types")]
  (when-not (and cp mc (#{"types" "roots" "java"} mode))
    (binding [*out* *err*] (println "usage: bb heap <classpath> <Main> [types|roots|java] [args...]"))
    (System/exit 2))
  (when (= mode "types") (scm/eval-string "(enable-object-counts #t)"))
  (ldk/init! {:classpath (vec (.split ^String cp ":"))})
  (ldk/invoke-static mc "main" (vec args))
  (scm/eval-string "(collect (collect-maximum-generation))")
  (case mode "types" (types) "roots" (roots) "java" (java))
  (System/exit 0))
