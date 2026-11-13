;; Copyright (C) 2026 champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026 Anthony Green
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0

;; jolt metrics:gate's check of a jolt-metrics EDN report against
;; ci/metrics-allow.edn:
;;
;;   jolt ci/metrics-gate.clj <report.edn> <ci/metrics-allow.edn>
;;
;; It fails (exit 1) on
;;
;; - a critical finding under one of :critical-free's path prefixes, or a file
;;   there jolt-metrics couldn't read. Those have no allowlist: split the code.
;; - an unused require, unused private fn or unreferenced public fn not in the
;;   allowlist, and an allowlist entry nothing matches any more, so the list
;;   stays true.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn- gated? [prefixes path]
  (boolean (some #(str/starts-with? path %) prefixes)))

(defn- violation-text [f]
  (->> (:violations f)
       (filter #(= :critical (:band %)))
       (map #(str (name (:rule %)) " " (:value %) " (limit " (:limit %) ")"))
       (str/join ", ")))

(defn- critical-lines [report prefixes]
  (concat
   (for [f (:queue report)
         :when (and (= :critical (:severity f)) (gated? prefixes (:path f)))]
     (str "critical: " (:path f) ":" (:line f) " " (:name f) ": " (violation-text f)))
   (for [e (:errors report)
         :when (gated? prefixes (:path e))]
     (str "unreadable: " (:path e) ": " (:error e)))))

(def ^:private cleanup-kinds [:unused-requires :unused-private :unused-public])

(defn- entry-id
  "What identifies a cleanup finding or an allowlist entry: its file and the
  lib or fn it names."
  [kind e]
  [(:path e) (if (= kind :unused-requires) (:lib e) (:name e))])

(defn- cleanup-lines [report allow]
  (mapcat
   (fn [kind]
     (let [found (into {} (map (fn [e] [(entry-id kind e) e]) (get-in report [:cleanup kind])))
           allowed (set (map #(entry-id kind %) (get allow kind)))]
       (concat
        (for [[id e] (sort-by key found) :when (not (allowed id))]
          (str (name kind) ": " (str/join " " id)
               (when (seq (:refers e)) (str " (refers " (str/join ", " (:refers e)) ")"))
               " is unused: remove it, or list it in ci/metrics-allow.edn with why it stays"))
        (for [id (sort allowed) :when (not (contains? found id))]
          (str (name kind) ": " (str/join " " id)
               " is in ci/metrics-allow.edn but no longer reported: remove the entry")))))
   cleanup-kinds))

(let [[report-path allow-path] *command-line-args*
      report (edn/read-string (slurp report-path))
      allow (edn/read-string (slurp allow-path))
      prefixes (:critical-free allow)
      lines (concat (critical-lines report prefixes) (cleanup-lines report allow))]
  (doseq [l lines] (println l))
  (if (seq lines)
    (do (println (str "metrics gate: " (count lines) " to fix")) (System/exit 1))
    (println (str "metrics gate: no critical finding under " (str/join ", " prefixes)
                  ", no unused code outside the allowlist"))))
