;; Java threads from jolt. Each call into Java holds the runtime's lock only
;; while it runs, so a Java thread keeps going while jolt does other work.
;;   jolt -M examples/threads.clj
(require '[champaca.api :as ldk])

(ldk/init!)

;; a jolt fn as the thread's Runnable
(def hits (atom 0))
(def t (ldk/construct "java.lang.Thread" (fn [] (dotimes [_ 1000] (swap! hits inc)))))
(ldk/invoke t "start")
(ldk/invoke t "join")
(println "thread ran:" @hits (ldk/invoke t "isAlive"))

;; a pool, and futures read from jolt
(def pool (ldk/invoke-static "java.util.concurrent.Executors" "newFixedThreadPool" 2))
;; a bare fn fits both submit(Runnable) and submit(Callable), so say which
(defn callable [f] (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" f))
(def futures (mapv (fn [n] (ldk/invoke pool "submit" (callable (fn [] (* n n))))) (range 1 6)))
(println "squares:" (mapv #(ldk/invoke % "get") futures))
(ldk/invoke pool "shutdown")

;; a Java thread runs while jolt waits in jolt code, never calling Java: if
;; jolt kept the lock between calls, this loop would wait forever
(def q (ldk/construct "java.util.concurrent.LinkedBlockingQueue"))
(def done (atom false))
(def producer (ldk/construct "java.lang.Thread"
                             (fn [] (dotimes [i 5] (ldk/invoke q "put" i)) (reset! done true))))
(ldk/invoke producer "start")
(loop [waited 0]
  (when (and (not @done) (< waited 30000))
    (Thread/sleep 10)
    (recur (+ waited 10))))
(println "filled while jolt waited:" @done (ldk/->clj (ldk/invoke-static "java.util.List" "copyOf" q)))
(ldk/invoke producer "join")

;; a thread jolt started (a future) calling Java gets a java.lang.Thread of
;; its own, as a native thread attached to a JVM does
(defn current-name [] (ldk/invoke (ldk/invoke-static "java.lang.Thread" "currentThread") "getName"))
(println "main is" (current-name) "/ future is jolt-N:"
         (boolean (re-matches #"jolt-\d+" @(future (current-name)))))
