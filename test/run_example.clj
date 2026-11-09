;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Runs the tests of one examples/*.clj file, then saves the startup cache:
;;   jolt -M test/run_example.clj examples/interop.clj
;; bb test and bb example call it. The example files only define tests, so
;; loading one into an nREPL session (C-c C-k in CIDER) runs nothing and
;; never exits the server; (clojure.test/run-tests) in its namespace does.
;; Exits 0 when at least one test ran and none failed. Tests can tell they run
;; here, on the process's first thread, from the champaca.example.runner
;; system property.
(require '[clojure.test :as t]
         '[champaca.api :as ldk])

(let [file (first *command-line-args*)
      ;; the file's first form is its ns form
      ns-sym (second (read-string (slurp file)))
      _ (System/setProperty "champaca.example.runner" "true")
      _ (load-file file)
      {:keys [test fail error]} (t/run-tests ns-sym)]
  (ldk/save-cache!)
  (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))
