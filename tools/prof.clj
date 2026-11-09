;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Where a champaca process's time goes. Boots, runs a main class with the
;; profiler of tools/prof.ss sampling the thread, then prints three tables
;; to stderr: the innermost Java frame of each sample (self), every Java
;; frame on the stack (inclusive), and the innermost Scheme code names, which
;; show what a native or the runtime was doing under a Java frame:
;;
;;   bb prof <classpath> <Main> [args...]
;;
;; PROF_US sets the interval in microseconds (default 2000), PROF_ROWS the
;; rows per table (30), PROF_DEPTH the code names per chain (4). The main
;; method is entered through champaca.api, where the samples arrive;
;; champaca.main's own run path (jolt -M -m champaca.main) doesn't deliver
;; them, so a jar's Main-Class is given by hand. The frames are the ones
;; stack traces show, so a native has none: its time counts for the Java
;; method that called it, with the Scheme code names telling which native.
;; Spike 72 (2026-10-30) wrote this to find where a warm Spring Boot fat-jar
;; start spent its 3 extra seconds. CHAMPACA_CACHE_DIR and the other
;; variables apply as to any run; profile a warm cache, or the compiler's
;; work dominates.
(require '[champaca.api :as ldk] '[jolt.scheme :as scm])

(let [[cp mc & args] *command-line-args*
      env #(or (System/getenv %1) %2)]
  (when-not (and cp mc)
    (binding [*out* *err*] (println "usage: bb prof <classpath> <Main> [args...]"))
    (System/exit 2))
  (ldk/init! {:classpath (vec (.split ^String cp ":"))})
  (scm/eval-string "(load \"tools/prof.ss\")")   ; bb prof runs from the repo root
  (scm/eval-string (str "(prof-start! " (env "PROF_US" "2000") " " (env "PROF_DEPTH" "4") ")"))
  (let [t0 (System/nanoTime)
        ;; the report covers whatever main did, a throw included
        status (try (ldk/invoke-static mc "main" (vec args)) 0
                    (catch Throwable t (binding [*out* *err*] (println (str "main threw: " (ex-message t)))) 1))]
    (scm/eval-string "(prof-stop!)")
    (binding [*out* *err*]
      (println (str "main " (if (zero? status) "returned" "threw") " after " (quot (- (System/nanoTime) t0) 1000000) " ms"))
      (println (scm/eval-string (str "(prof-report " (env "PROF_ROWS" "30") ")"))))
    (System/exit status)))
