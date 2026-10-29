;; bb test's failed-save row, on a cache of its own: a JDK class outside the
;; boot set loads, the first save-cache! fails (begin-units! made to throw),
;; another class loads and the second save succeeds. Before, that second save
;; wrote Adler32's class map to state.img without the code registering it,
;; and every later run failed with "class not registered" (use.clj).
(require '[champaca.api :as ldk] '[jolt.scheme :as scm])
(ldk/init! {})
(println "adler" (ldk/invoke (ldk/construct "java.util.zip.Adler32") "getValue"))
(scm/eval-string "(define cachesave-begin-units! begin-units!)")
(scm/eval-string "(set! begin-units! (lambda args (error 'begin-units! \"forced\")))")
(def failed? (try (ldk/save-cache!) false (catch Throwable _ true)))
(scm/eval-string "(set! begin-units! cachesave-begin-units!)")
(println "choice" (ldk/invoke (ldk/construct "java.text.ChoiceFormat" "0#none|1#one") "format" 1))
(ldk/save-cache!)
;; with the cache on, a first save that didn't fail proves nothing
(System/exit (if (or failed? (= "off" (System/getenv "CHAMPACA_CACHE"))) 0 3))
