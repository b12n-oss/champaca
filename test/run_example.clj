;; Runs the tests of one examples/*.clj file, then saves the startup cache:
;;   jolt -M test/run_example.clj examples/interop.clj
;; bb test and bb example call it. The example files only define tests, so
;; loading one into an nREPL session (C-c C-k in CIDER) runs nothing and
;; never exits the server; (clojure.test/run-tests) in its namespace does.
;; Exits 0 when at least one test ran and none failed.
(require '[clojure.test :as t]
         '[champaca.api :as ldk])

(let [file (first *command-line-args*)
      ;; the file's first form is its ns form
      ns-sym (second (read-string (slurp file)))
      _ (load-file file)
      {:keys [test fail error]} (t/run-tests ns-sym)]
  (ldk/save-cache!)
  (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))
