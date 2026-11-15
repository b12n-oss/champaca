;; Copyright (C) 2026  champaca contributors
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Two commits' startup benchmarks side by side. Reads the results.md files
;; bench.clj writes, saved as <app>-<side>-r<round>.md in one directory, side
;; base or head, and prints a Markdown table per app: each variant's median
;; per round on both sides, and the change from the mean of base's rounds to
;; the mean of head's. HotSpot's rows are the control: the same JVM on both
;; sides, so a change there is the machine, not champaca. bb.edn's
;; bench:compare task loads this file and calls main; .github/workflows/bench.yml
;; puts the output on its run's summary.

(ns bench.compare
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- millis
  "A cell's time in ms (\"3557 ms\", \"50.3 s\"), or nil for any other cell."
  [cell]
  (when-let [[_ n unit] (re-matches #"([\d.]+) (ms|s)" (str/trim cell))]
    (* (parse-double n) (if (= unit "s") 1000 1))))

(defn- shown [ms]
  (if (>= ms 10000) (format "%.1f s" (/ ms 1000.0)) (format "%,d ms" (Math/round (double ms)))))

(defn- results
  "A results.md file as {:rows {variant cells} :order [variant ...] :load l
  :commit c}, cells after the variant's name."
  [file]
  (reduce (fn [acc line]
            (cond
              (str/starts-with? line "- Load average before:")
              (assoc acc :load (first (str/split (str/trim (subs line 22)) #"\s+")))
              (str/starts-with? line "- champaca: ")
              (assoc acc :commit (str/trim (subs line 12)))
              (and (str/starts-with? line "| ")
                   (not (str/starts-with? line "| Variant")))
              (let [[variant & cells] (map str/trim (str/split (subs line 2) #"\s*\|\s*"))]
                (-> acc (assoc-in [:rows variant] (vec cells)) (update :order conj variant)))
              :else acc))
          {:rows {} :order []}
          (str/split-lines (slurp (str file)))))

(defn- rounds
  "{[side round] results} for app, the rounds every side has."
  [dir app]
  (into {} (for [f (fs/glob dir (str app "-*-r*.md"))
                 :let [[_ side r] (re-matches #".*-(base|head)-r(\d+)\.md" (str (fs/file-name f)))]
                 :when side]
             [[side (parse-long r)] (results f)])))

(defn- table [app rs]
  (let [rounds-of (fn [side] (sort (keep (fn [[[s r] _]] (when (= s side) r)) rs)))
        base-rs (rounds-of "base") head-rs (rounds-of "head")
        first-res (val (first (sort-by key rs)))
        ;; a server's table has First page/All pages after the start column
        server? (= 4 (count (val (first (:rows first-res)))))
        cols (if server? [[0 "started"] [2 "all pages"]] [[0 nil]])
        cell (fn [side r variant ci] (some-> (get-in rs [[side r] :rows variant ci]) millis))
        mean (fn [xs] (/ (reduce + xs) (count xs)))]
    (concat
     [(str "### " app)
      ""
      (str "Base " (:commit (get rs ["base" (first base-rs)]))
           ", head " (:commit (get rs ["head" (first head-rs)]))
           "; load average before each run, base "
           (str/join ", " (map #(:load (get rs ["base" %])) base-rs))
           ", head " (str/join ", " (map #(:load (get rs ["head" %])) head-rs)) ".")
      ""
      "| Variant | Base | Head | Change |"
      "|---|---|---|---|"]
     (for [variant (:order first-res)
           [ci label] cols
           :let [b (keep #(cell "base" % variant ci) base-rs)
                 h (keep #(cell "head" % variant ci) head-rs)]
           :when (and (= (count b) (count base-rs)) (= (count h) (count head-rs)) (seq b) (seq h))]
       (let [pct (Math/round (* 100.0 (/ (- (mean h) (mean b)) (mean b))))]
         (format "| %s | %s | %s | %s |"
                 (if label (str variant ", " label) variant)
                 (str/join " / " (map shown b))
                 (str/join " / " (map shown h))
                 (if (zero? pct) "±0%" (format "%+d%%" pct)))))
     [""])))

(defn main [args]
  (let [[dir] args]
    (when-not (and dir (fs/directory? dir))
      (binding [*out* *err*] (println "usage: jolt bench:compare <directory of <app>-<base|head>-r<n>.md>"))
      (System/exit 2))
    (println "Each cell is that round's median, rounds in order; the change compares the means of the rounds.")
    (println "HotSpot's rows are the control: a change there is the machine.")
    (println)
    (doseq [app (distinct (keep #(second (re-matches #"(.+)-(?:base|head)-r\d+\.md" (str (fs/file-name %))))
                                (sort (fs/glob dir "*.md"))))
            :let [rs (rounds dir app)]
            :when (and (some #(= "base" (ffirst %)) rs) (some #(= "head" (ffirst %)) rs))]
      (doseq [l (table app rs)] (println l)))))
