;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; A class's static methods written out as jolt functions (champaca.port),
;; each checked against the method it came from (champaca.portcheck):
;;
;;   jolt port <classpath> <Class>
;;
;; Prints each function that could be ported, with the conditions it holds
;; for and the check's result, then the methods left and why, then a count.
;; A function whose check disagrees is reported and counted as failed: it
;; must not be used. PORT_RUNS sets the inputs per check (300).
(require '[champaca.api :as ldk]
         '[champaca.port :as port]
         '[champaca.portcheck :as pc]
         '[clojure.string :as str])

(let [[cp cname] *command-line-args*
      runs (parse-long (or (System/getenv "PORT_RUNS") "300"))]
  (when-not (and cp cname)
    (binding [*out* *err*] (println "usage: jolt port <classpath> <Class>"))
    (System/exit 2))
  (ldk/init! {:classpath (vec (.split ^String cp ":"))})
  (let [results (port/check-ports (vec (port/port-class cname)) runs)
        ported (remove :left results)
        reports (mapv :report ported)
        failed (count (filter #(and % (not (pc/agrees? %))) reports))
        unchecked (count (remove some? reports))]
    (doseq [[p r] (map vector ported reports)]
      (println (str ";; " (:method p)))
      (when (seq (:holds p)) (println (str ";; holds for: " (str/join ", " (map name (:holds p))))))
      (println (str ";; " (cond (nil? r) "not checked: a parameter type has no input generator"
                                :else (str (pc/report-line r)
                                           (when (some :range (:args p)) ", int and long parameters within -64 to 64")))))
      (println (:source p))
      (println))
    (doseq [l (filter :left results)]
      (println (str ";; left: " (:method l) ": " (:left l))))
    (println (str ";; " (count results) " static methods: " (count ported) " ported ("
                  (- (count ported) failed unchecked) " checked and agree, " failed " disagree, "
                  unchecked " not checked), " (count (filter :left results)) " left"))
    (println (str ";; " (count (filter #(and (:report %) (some :range (:args %))) ported))
                  " of the checks kept int and long parameters within -64 to 64 (the method loops or"
                  " allocates by size); the others used the full range"))
    (ldk/save-cache!)
    (System/exit (if (zero? failed) 0 1))))
