;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Java threads from jolt. Each call into Java holds the runtime's lock only
;; while it runs, so a Java thread keeps going while jolt does other work.
;;   jolt example threads
(ns examples.threads
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(deftest jolt-fn-as-runnable
  (let [hits (atom 0)
        t (ldk/construct "java.lang.Thread" (fn [] (dotimes [_ 1000] (swap! hits inc))))]
    (ldk/invoke t "start")
    (ldk/invoke t "join")
    (is (= 1000 @hits))
    (is (false? (ldk/invoke t "isAlive")))))

;; a bare fn fits both submit(Runnable) and submit(Callable), so say which
(defn- callable [f] (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" f))

(deftest pool-and-futures
  (let [pool (ldk/invoke-static "java.util.concurrent.Executors" "newFixedThreadPool" 2)
        futures (mapv (fn [n] (ldk/invoke pool "submit" (callable (fn [] (* n n))))) (range 1 6))]
    (is (= [1 4 9 16 25] (mapv #(ldk/invoke % "get") futures)))
    (ldk/invoke pool "shutdown")))

(deftest java-runs-while-jolt-waits
  ;; a Java thread runs while jolt waits in jolt code, never calling Java: if
  ;; jolt kept the lock between calls, this loop would wait forever
  (let [q (ldk/construct "java.util.concurrent.LinkedBlockingQueue")
        done (atom false)
        producer (ldk/construct "java.lang.Thread"
                                (fn [] (dotimes [i 5] (ldk/invoke q "put" i)) (reset! done true)))]
    (ldk/invoke producer "start")
    (loop [waited 0]
      (when (and (not @done) (< waited 30000))
        (Thread/sleep 10)
        (recur (+ waited 10))))
    (is (true? @done))
    (is (= [0 1 2 3 4] (ldk/->clj (ldk/invoke-static "java.util.List" "copyOf" q))))
    (ldk/invoke producer "join")))

(defn- current-name [] (ldk/invoke (ldk/invoke-static "java.lang.Thread" "currentThread") "getName"))

(deftest jolt-threads-get-java-threads
  ;; a thread jolt started (a future) calling Java gets a java.lang.Thread of
  ;; its own, as a native thread attached to a JVM does. The process's first
  ;; thread is main, where test/run_example.clj runs the tests; an nREPL
  ;; evaluation runs on a jolt-<n> thread instead.
  (let [here (current-name)
        there @(future (current-name))]
    (if (System/getProperty "champaca.example.runner")
      (is (= "main" here))
      (is (re-matches #"jolt-\d+" here)))
    (is (re-matches #"jolt-\d+" there))
    (is (not= here there))))
