;; Startup benchmarks, ported from upstream OpenLDK's bench/ (Anthony Green):
;; the same Spring Boot and Quarkus applications, each started on HotSpot and
;; through champaca, timed, and their output checked against HotSpot's. See
;; bench/README.md. bb.edn's bench task loads this file and calls main.
;;
;; Environment:
;;   JAVA_HOME   a JDK 25 (required)
;;   RUNS        timed runs per variant (default 10)
;;   COLD_RUNS   timed runs of a cold variant, each with an empty cache (default 3)
;;   SKIP_AOT    set to leave out the JDK AOT cache variants
;;   SKIP_CHAMPACA  set to leave out the champaca variants
;; Needs mvn on PATH (Maven Central reached with the proxy unset).

(ns bench
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def java-home (or (System/getenv "JAVA_HOME") (throw (ex-info "set JAVA_HOME to a JDK 25" {}))))
(def java (str java-home "/bin/java"))
(defn- env-long [k default] (if-let [v (System/getenv k)] (parse-long v) default))
(def runs (env-long "RUNS" 10))
(def cold-runs (env-long "COLD_RUNS" 3))
(def mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))
(def repo (str (fs/canonicalize ".")))

(defn- log [& xs] (binding [*out* *err*] (println (str "==> " (str/join " " xs)))))

;; the environment without proxies: Maven Central refuses some
(def ^:private no-proxy (apply dissoc (into {} (System/getenv)) ["HTTPS_PROXY" "https_proxy" "HTTP_PROXY" "http_proxy"]))

(defn- maven [dir & args]
  (when-not (fs/which "mvn") (throw (ex-info "mvn isn't on PATH" {})))
  (apply p/shell {:dir dir :env (assoc no-proxy "JAVA_HOME" java-home)} "mvn" "-q" "-B" args))

;; --- the applications ------------------------------------------------------------
;; Each: where it lives, how it builds, the command whose output is the
;; reference, and its variants. A variant's :cmd runs in the app's target
;; directory; :champaca marks one run through champaca, :cold one whose every
;; run starts from an empty startup cache.

(def apps
  {"spring-boot"
   {:title "Spring Boot"
    :build (fn [dir t]
             (log "Building the application (Spring AOT included)")
             (maven dir "-Pnative" "package" "-DskipTests")
             (maven dir "dependency:copy-dependencies" "-DincludeScope=runtime" (str "-DoutputDirectory=" t "/lib"))
             (fs/copy (str t "/boot-demo-1.0.jar") (str t "/boot-demo-fat.jar") {:replace-existing true})
             (log "Extracting the jar, as Spring Boot recommends for production")
             (fs/delete-tree (str t "/extracted"))
             (p/shell {:dir t} java "-Djarmode=tools" "-jar" "boot-demo-1.0.jar" "extract" "--destination" "extracted")
             (when-not (System/getenv "SKIP_AOT")
               ;; a cache records the class path as given, so train with the
               ;; command lines the benchmark runs
               (log "Training the JDK AOT caches")
               (p/shell {:dir t :out :string} java "-XX:AOTCacheOutput=extracted/boot.aot" "-jar" "extracted/boot-demo-1.0.jar")
               (p/shell {:dir t :out :string} java "-XX:AOTCacheOutput=extracted/boot-springaot.aot" "-Dspring.aot.enabled=true"
                        "-jar" "extracted/boot-demo-1.0.jar")))
    :reference [java "-jar" "boot-demo-fat.jar"]
    :variants [{:name "HotSpot, fat jar" :cmd [java "-jar" "boot-demo-fat.jar"]}
               {:name "HotSpot, extracted jar" :cmd [java "-jar" "extracted/boot-demo-1.0.jar"]}
               {:name "HotSpot + Spring AOT" :cmd [java "-Dspring.aot.enabled=true" "-jar" "extracted/boot-demo-1.0.jar"]}
               {:name "HotSpot + JDK AOT cache" :aot true
                :cmd [java "-XX:AOTMode=on" "-XX:AOTCache=extracted/boot.aot" "-jar" "extracted/boot-demo-1.0.jar"]}
               {:name "HotSpot + Spring AOT + AOT cache" :aot true
                :cmd [java "-Dspring.aot.enabled=true" "-XX:AOTMode=on" "-XX:AOTCache=extracted/boot-springaot.aot"
                      "-jar" "extracted/boot-demo-1.0.jar"]}
               {:name "champaca, extracted jar, cold" :champaca ["-jar" "extracted/boot-demo-1.0.jar"] :cold true}
               {:name "champaca, extracted jar" :champaca ["-jar" "extracted/boot-demo-1.0.jar"]}
               {:name "champaca, fat jar" :champaca ["-jar" "boot-demo-fat.jar"]}]}

   "quarkus"
   {:title "Quarkus"
    :build (fn [dir t]
             (log "Building the uber-jar")
             (maven dir "package" "-DskipTests" "-Dquarkus.package.jar.type=uber-jar")
             (fs/move (str t "/quarkus-demo-1.0-runner.jar") (str t "/quarkus-uber.jar") {:replace-existing true})
             (log "Building the fast-jar, Quarkus's default packaging")
             (fs/delete-tree (str t "/quarkus-app"))
             (maven dir "package" "-DskipTests")
             (when-not (System/getenv "SKIP_AOT")
               (log "Training the JDK AOT cache")
               (p/shell {:dir t :out :string} java "-XX:AOTCacheOutput=quarkus-app/app.aot" "-jar" "quarkus-app/quarkus-run.jar")))
    :reference [java "-jar" "quarkus-app/quarkus-run.jar"]
    :variants [{:name "HotSpot, fast-jar" :cmd [java "-jar" "quarkus-app/quarkus-run.jar"]}
               {:name "HotSpot, uber-jar" :cmd [java "-jar" "quarkus-uber.jar"]}
               {:name "HotSpot + JDK AOT cache" :aot true
                :cmd [java "-XX:AOTMode=on" "-XX:AOTCache=quarkus-app/app.aot" "-jar" "quarkus-app/quarkus-run.jar"]}
               {:name "champaca, uber-jar, cold" :champaca ["-jar" "quarkus-uber.jar"] :cold true}
               {:name "champaca, uber-jar" :champaca ["-jar" "quarkus-uber.jar"]}
               {:name "champaca, fast-jar" :champaca ["-jar" "quarkus-app/quarkus-run.jar"]}]}})

;; --- running and timing ----------------------------------------------------------

(defn- champaca-cmd [jolt args]
  (into [jolt "-Sdeps" (str "{:deps {champaca/champaca {:local/root \"" repo "\"}}}") "-M" "-m" "champaca.main"] args))

;; the variant's command and environment for one run; a cold run gets an
;; empty cache directory of its own, deleted afterwards by the caller
(defn- run-spec [jolt t v]
  (if-let [args (:champaca v)]
    (let [cache (if (:cold v) (str (fs/create-temp-dir {:prefix "champaca-cold-"})) (str t "/champaca-cache"))]
      {:cmd (champaca-cmd jolt args) :env (assoc no-proxy "JAVA_HOME" java-home "CHAMPACA_CACHE_DIR" cache)
       :temp (when (:cold v) cache)})
    {:cmd (:cmd v) :env no-proxy}))

(defn- run! [t {:keys [cmd env]} & [extra]]
  (let [t0 (System/nanoTime)
        r @(p/process (into (vec extra) cmd) {:dir t :env env :out :string :err :string :in ""})]
    {:ms (/ (- (System/nanoTime) t0) 1e6) :out (:out r) :err (:err r) :exit (:exit r)}))

;; what every variant must print: the application's JSON and round trip
(defn- runner-output [s]
  (let [ls (str/split-lines (or s ""))
        from (drop-while #(not= % "[ {") ls)
        [body tail] (split-with #(not (str/starts-with? % "roundtrip=")) from)]
    (when (seq tail) (str/join "\n" (concat body [(first tail)])))))

;; peak resident set size, in MB, of one more run
(defn- peak-rss [t spec]
  (let [r (if mac?
            (run! t spec ["/usr/bin/time" "-l"])
            (run! t spec ["/usr/bin/time" "-f" "%M"]))
        e (:err r)]
    (if mac?
      (when-let [[_ b] (re-find #"(\d+)\s+maximum resident set size" e)] (quot (parse-long b) (* 1024 1024)))
      (when-let [kb (last (re-seq #"(?m)^\d+$" e))] (quot (parse-long kb) 1024)))))

(defn- with-run [jolt t v f]
  (let [spec (run-spec jolt t v)]
    (try (f spec) (finally (when-let [d (:temp spec)] (fs/delete-tree d))))))

(defn- fmt-ms [ms] (if (< ms 100) (format "%.1f ms" ms) (if (< ms 10000) (format "%.0f ms" ms) (format "%.1f s" (/ ms 1000)))))

(defn- host-lines []
  (let [sh (fn [& cmd] (str/trim (:out (apply p/shell {:out :string :err :string :continue true} cmd))))]
    (if mac?
      [(str "- Host: " (sh "sysctl" "-n" "machdep.cpu.brand_string") ", " (sh "sysctl" "-n" "hw.ncpu") " CPUs, macOS "
            (sh "sw_vers" "-productVersion"))
       (str "- Load average before: " (str/replace (sh "sysctl" "-n" "vm.loadavg") #"[{}]" ""))]
      [(str "- Host: " (second (re-find #"model name\s*:\s*(.*)" (slurp "/proc/cpuinfo"))) ", " (sh "nproc") " CPUs, Linux")
       (str "- Load average before: " (str/join " " (take 3 (str/split (slurp "/proc/loadavg") #" "))))])))

(defn- load-after []
  (if mac?
    (str/trim (str/replace (:out (p/shell {:out :string} "sysctl" "-n" "vm.loadavg")) #"[{}]" ""))
    (str/join " " (take 3 (str/split (slurp "/proc/loadavg") #" ")))))

(defn- bench-app [jolt app-name {:keys [title reference variants]}]
  (let [t (str repo "/bench/" app-name "/target")
        ref-out (runner-output (:out (run! t {:cmd reference :env no-proxy})))
        vs (cond->> variants
             (System/getenv "SKIP_AOT") (remove :aot)
             (System/getenv "SKIP_CHAMPACA") (remove :champaca))
        lines (concat
               [(str "# " title " startup, " runs " runs each (cold champaca variants " cold-runs ")") ""]
               (host-lines)
               [(str "- JDK: " (first (str/split-lines (:err (p/shell {:err :string} java "-version")))))
                (str "- champaca: " (str/trim (:out (p/shell {:out :string :dir repo} "git" "describe" "--always" "--dirty"))))
                (str "- jolt: " jolt) ""
                "| Variant | Median | Min | Max | Peak RSS | Output |" "|---|---|---|---|---|---|"])]
    (when-not ref-out (throw (ex-info (str title ": the reference run printed no application output") {})))
    (doseq [l lines] (println l))
    (let [rows
          (doall
           (for [v vs]
             (let [_ (log "Timing" (:name v))
                   ;; a warm variant's first run fills its cache and is the
                   ;; output check; a cold variant checks its first timed run
                   check (when-not (:cold v) (with-run jolt t v #(run! t %)))
                   timed (vec (for [_ (range (if (:cold v) cold-runs runs))] (with-run jolt t v #(run! t %))))
                   out (runner-output (:out (or check (first timed))))
                   ms (sort (map :ms timed))
                   rss (with-run jolt t v #(peak-rss t %))
                   row (format "| %s | %s | %s | %s | %s | %s |" (:name v)
                               (fmt-ms (nth ms (quot (count ms) 2))) (fmt-ms (first ms)) (fmt-ms (last ms))
                               (if rss (str rss " MB") "n/a")
                               (if (= out ref-out) "same" "DIFFERENT"))]
               (println row)
               (flush)
               row)))
          after (str "Load average after: " (load-after))]
      (println)
      (println after)
      (spit (str t "/results.md") (str (str/join "\n" (concat lines rows ["" after])) "\n")))))

(defn main [jolt args]
  (let [[app-name what] args
        what (or what "all")
        app (get apps app-name)]
    (when-not (and app (#{"build" "bench" "all"} what))
      (binding [*out* *err*] (println (str "usage: bb bench <" (str/join "|" (keys apps)) "> [build|bench|all]")))
      (System/exit 2))
    (let [dir (str repo "/bench/" app-name) t (str dir "/target")]
      (when (#{"build" "all"} what) (fs/create-dirs t) ((:build app) dir t))
      (when (#{"bench" "all"} what) (bench-app jolt app-name app)))))
