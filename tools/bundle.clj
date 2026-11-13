;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt bundle: an app bundle that runs with no JDK installed (ROADMAP 41).
;;
;;   jolt bundle -o OUT -cp CLASSPATH -m MAIN [options]     a Java program
;;   jolt bundle -o OUT --jolt NS --project DIR [-cp CP]    a jolt program that
;;                                                        calls Java through
;;                                                        champaca.api, whose
;;                                                        init! takes CP from
;;                                                        CHAMPACA_CLASSPATH
;; Paths are the checkout's (jolt runs tasks there) or absolute.
;; options:
;;   --name NAME            the launcher's name (default: the main class's or
;;                          namespace's last part, lower-cased)
;;   --add-modules M,N      modules beyond what jdeps finds the classpath needs
;;                          and the two always added (jdk.charsets, and
;;                          jdk.localedata unless --locales none): jdeps can't
;;                          see what a program reaches by name (a charset, a
;;                          locale's data, a ServiceLoader provider)
;;   --locales LIST         keep only these locales' data (en,de: jlink's
;;                          --include-locales), or none for no jdk.localedata
;;                          (27 MB): numbers and dates then format as en does
;;   --signable             build the binaries with jolt build --signable, so
;;                          they can be code-signed afterwards (codesign on
;;                          macOS); it needs a C compiler. macOS refuses to start
;;                          a downloaded unsigned binary: sign and notarize a
;;                          bundle before handing it to anyone
;;   --train ARGS...        run the program once with ARGS at build time, so
;;                          the cache holds the code that run compiled (the
;;                          rest of the command line); without it only the
;;                          JDK's boot is cached
;;
;; OUT holds bin/NAME (the launcher), lib/ (the jolt-built binary), runtime/
;; (a jlink image of the modules needed, with no JVM and no native libraries:
;; champaca reads only its class files and data), app/ (the classpath's jars
;; and directories, copied) and cache/ (champaca's cache, warmed). Moving or
;; copying OUT keeps the cache: its key hashes the runtime image's index, and
;; classpath entries inside the bundle are named relative to it
;; (CHAMPACA_BUNDLE). A cache that can't be written (an installed, read-only
;; OUT) is read, and what a run compiles beyond it isn't kept.
;;
;; Build-time needs: JAVA_HOME a JDK 25 (jdeps, jlink) and jolt (the pinned
;; build, $JOLT or jolt on PATH). A bundle runs on the OS and architecture it
;; was built on.

(ns bundle
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- die
  "Stops the build: -main cleans up what it made and exits 2."
  [& msg]
  (throw (ex-info (str/join " " msg) {::die true})))

(def ^:private value-options
  "The options that take a value, and the key each sets."
  {"-o" :out "-cp" :cp "--class-path" :cp "-m" :main "--jolt" :jolt "--project" :project
   "--name" :name "--add-modules" :add-modules "--locales" :locales})

(defn- parse [args]
  (loop [[a & more :as args] args, opts {}]
    (cond
      (empty? args) opts
      (= a "--train") (assoc opts :train (vec more))
      (= a "--signable") (recur more (assoc opts :signable true))
      (not (value-options a)) (die "unknown argument" a)
      (empty? more) (die a "needs a value")
      :else (recur (rest more) (assoc opts (value-options a) (first more))))))

(defn- java-home []
  (let [h (System/getenv "JAVA_HOME")]
    (when-not (and h (fs/exists? (fs/path h "bin" "jlink")))
      (die "JAVA_HOME must name the JDK 25 to build from (it needs bin/jlink and bin/jdeps)"))
    h))

(defn- tool [n] (str (fs/path (java-home) "bin" n)))

(defn- sh! [& cmd]
  (let [{:keys [exit]} (apply p/shell {:continue true} cmd)]
    (when-not (zero? exit) (die "failed:" (str/join " " cmd)))))

(defn- cp-entries
  "The class path's entries, canonical; dir/* is the directory's jars, sorted,
  as champaca.main and java read it."
  [cp]
  (when cp
    (vec (for [e (str/split cp (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
               :when (seq e)
               e (if (str/ends-with? e "/*")
                   (let [d (subs e 0 (- (count e) 2))]
                     (when-not (fs/directory? d) (die "classpath entry not found:" e))
                     (sort (map str (filter #(re-find #"(?i)\.jar$" (str %)) (fs/list-dir d)))))
                   [e])]
           (do (when-not (fs/exists? e) (die "classpath entry not found:" e))
               (str (fs/canonicalize e)))))))

(defn- modules
  "The JDK modules the classpath needs, by jdeps, plus extra (comma
  separated); java.base alone when the classpath is empty."
  [entries extra locales]
  (let [found (when (seq entries)
                (let [{:keys [out exit err]}
                      (apply p/shell {:out :string :err :string :continue true}
                             ;; the entries both as the class path and as targets:
                             ;; without the class path jdeps calls JDK types some
                             ;; jars use (Jackson's java.sql.Time) not found and
                             ;; drops their modules
                             (tool "jdeps") "--print-module-deps" "--ignore-missing-deps" "--multi-release" "25"
                             "--class-path" (str/join java.io.File/pathSeparator entries) entries)]
                  (when-not (zero? exit) (die "jdeps failed:" err))
                  ;; jdeps prints its warnings (split packages) on stdout too;
                  ;; the module list is its last line
                  (some-> (->> (str/split-lines out) (remove #(or (str/blank? %) (str/starts-with? % "Warning"))) last)
                          str/trim (str/split #","))))]
    (vec (distinct (remove str/blank? (concat ["java.base" "jdk.charsets"]
                                              (when-not (= locales "none") ["jdk.localedata"])
                                              found (some-> extra (str/split #","))))))))

(defn- jlink! [out mods locales]
  (let [rt (fs/path out "runtime")]
    (apply sh! (tool "jlink") "--add-modules" (str/join "," mods) "--strip-debug" "--no-header-files"
           "--no-man-pages" "--output" (str rt)
           (when (and locales (not= locales "none")) [(str "--include-locales=" locales)]))
    ;; champaca never starts a JVM or loads a JDK native library: the launchers,
    ;; the VM and every shared library go; legal/ stays (the licenses travel
    ;; with the binaries)
    (fs/delete-tree (fs/path rt "bin"))
    (doseq [f (fs/glob rt "lib/**")
            :when (and (fs/regular-file? f)
                       (re-find #"\.(dylib|so|dll)$|/jspawnhelper$|/jexec$" (str f)))]
      (fs/delete f))
    (doseq [d ["lib/server" "lib/client" "lib/minimal"]] (fs/delete-tree (fs/path rt d)))
    (str rt)))

(defn- copy-app!
  "Copies the classpath entries into OUT/app, each under a name of its own,
  and answers their paths relative to OUT."
  [out entries]
  (fs/create-dirs (fs/path out "app"))
  (loop [[e & more] entries, used #{}, rel []]
    (if-not e
      rel
      (let [base (fs/file-name e)
            name (if (used base) (str (count rel) "-" base) base)
            dst (fs/path out "app" name)]
        (if (fs/directory? e) (fs/copy-tree e dst) (fs/copy e dst))
        (recur more (conj used name) (conj rel (str "app/" name)))))))

(defn- jolt-build! [jolt project ns out-bin signable]
  (when-not (or (fs/executable? jolt) (fs/which jolt))
    (die "no jolt at" jolt "(jolt jolt builds the pinned one; $JOLT names another)"))
  (fs/create-dirs (fs/parent out-bin))
  (let [{:keys [exit]} (apply p/shell {:dir (str project) :continue true} jolt "build" "-m" ns "-o" (str out-bin)
                              (when signable ["--signable"]))]
    (when-not (zero? exit) (die "jolt build failed for" ns)))
  ;; jolt build's work directory, beside the binary
  (fs/delete-tree (str out-bin ".build")))

(defn- champaca-project
  "A scratch project depending on this champaca checkout, for building
  champaca.main."
  [repo]
  (let [d (fs/create-temp-dir {:prefix "champaca-bundle-"})]
    (spit (str (fs/path d "deps.edn")) (pr-str {:deps {'champaca/champaca {:local/root (str repo)}}}))
    d))

(defn- launcher [name exec-line]
  (str "#!/bin/sh\n"
       "# " name ": a champaca app bundle (jolt bundle). It needs no JDK: runtime/ holds\n"
       "# the JDK's class files, and cache/ the compiled code, which moves with the\n"
       "# directory.\n"
       "p=$0\n"
       "while [ -L \"$p\" ]; do l=$(readlink \"$p\"); case $l in /*) p=$l ;; *) p=$(dirname \"$p\")/$l ;; esac; done\n"
       "B=$(CDPATH= cd -- \"$(dirname -- \"$p\")/..\" && pwd -P)\n"
       "JAVA_HOME=\"$B/runtime\" CHAMPACA_BUNDLE=\"$B\" CHAMPACA_CACHE_DIR=\"$B/cache\"\n"
       "export JAVA_HOME CHAMPACA_BUNDLE CHAMPACA_CACHE_DIR\n"
       exec-line "\n"))

(defn- du [path]
  (if (fs/exists? path)
    (reduce + (map fs/size (filter fs/regular-file? (cons path (fs/glob path "**")))))
    0))

(defn- mb [n] (format "%.1f MB" (/ n 1048576.0)))

(defn- check-args!
  "Dies on options that can't make a bundle, in the order build! checks them."
  [{:keys [out main project name] jolt-ns :jolt} jolt]
  (when-not out (die "-o OUT is required"))
  (when-not (or main jolt-ns) (die "give -m MAIN (a Java program) or --jolt NS (a jolt program)"))
  (when (and main jolt-ns) (die "-m and --jolt don't go together"))
  (when (and jolt-ns (not project)) (die "--jolt NS needs --project DIR, the jolt project holding NS"))
  (when (and jolt-ns (= "champaca" (or name (str/lower-case (last (str/split jolt-ns #"[.$]"))))))
    (die "--jolt NS's binary would be named champaca, which lib/ keeps for champaca.main; give --name"))
  (when (fs/exists? out) (die out "exists; give a new directory"))
  (when-not (or (fs/executable? jolt) (fs/which jolt))
    (die "no jolt at" jolt "(jolt jolt builds the pinned one; $JOLT names another)")))

(defn- check-out-outside!
  "Dies when -o is inside a class path directory, which would copy itself;
  canonical paths, so a symlink to the directory doesn't slip past."
  [out entries]
  (let [o (str (fs/path (fs/canonicalize (fs/parent (fs/absolutize out))) (fs/file-name out)))]
    (doseq [e entries :when (fs/directory? e)]
      (when (or (= o e) (str/starts-with? o (str e "/")))
        (die "-o" out "is inside the class path directory" e)))))

(defn- launcher-exec
  "The launcher's last line: champaca.main on MAIN, or the jolt program's
  binary with the class path in CHAMPACA_CLASSPATH; rel are the class path
  entries relative to the bundle."
  [main name rel]
  (let [cp-line (str/join ":" (map #(str "$B/" %) rel))]
    (if main
      (str "exec \"$B/lib/champaca\""
           (when (seq rel) (str " -cp \"" cp-line "\""))
           " " main " \"$@\"")
      (str (when (seq rel) (str "CHAMPACA_CLASSPATH=\"" cp-line "\" export CHAMPACA_CLASSPATH\n"))
           "exec \"$B/lib/" name "\" \"$@\""))))

(defn- write-launcher!
  "Writes bin/NAME running exec, and answers its path."
  [out name exec]
  (let [bin (fs/path out "bin" name)]
    (fs/create-dirs (fs/parent bin))
    (spit (str bin) (launcher name exec))
    (fs/set-posix-file-permissions bin "rwxr-xr-x")
    bin))

(defn- warm-cache!
  "The cache: the JDK's boot always, then the program's own run when asked;
  the same environment the launcher sets (the builder's own CHAMPACA_*
  settings stay out: CHAMPACA_BT or CHAMPACA_LINES would warm a cache under
  another key than the users' runs look for)."
  [out rt champaca-bin bin name train]
  (let [env (merge (into {} (remove (fn [[k _]] (str/starts-with? k "CHAMPACA_"))) (System/getenv))
                   {"JAVA_HOME" rt "CHAMPACA_BUNDLE" out "CHAMPACA_CACHE_DIR" (str (fs/path out "cache"))})]
    (println "bundle: warming the cache (the JDK's boot)")
    (let [{:keys [exit]} (p/shell {:env env :out :string :err :string :continue true} (str champaca-bin) "-version")]
      (when-not (zero? exit) (die "the boot failed in the bundle's runtime")))
    (when train
      (println "bundle: training run:" name (str/join " " train))
      (let [{:keys [exit]} (apply p/shell {:continue true :env env} (str bin) train)]
        (println "bundle: training run exited" exit)))))

(defn- print-sizes!
  "What build! wrote and how big each part is."
  [out name train]
  (println (str "bundle: wrote " out "/bin/" name))
  (doseq [[label d] [["runtime" "runtime"] ["lib" "lib"] ["app" "app"] ["cache" "cache"]]]
    (println (format "  %-8s %s" label (mb (du (fs/path out d))))))
  (println (format "  %-8s %s" "total" (mb (du out))))
  (when-not train
    (println "bundle: only the boot is cached; --train ARGS caches what a run of the program compiles")))

(defn- build! [repo jolt made args]
  (let [{:keys [out cp main project name add-modules train signable locales] jolt-ns :jolt :as opts} (parse args)]
    (check-args! opts jolt)
    (let [entries (cp-entries cp)
          _ (check-out-outside! out entries)
          name (or name (str/lower-case (last (str/split (or main jolt-ns) #"[.$]"))))
          mods (modules entries add-modules locales)
          _ (println "bundle: modules" (str/join "," mods))
          _ (fs/create-dirs out)
          _ (vreset! made {:out (str (fs/canonicalize out))})
          out (str (fs/canonicalize out))
          rt (jlink! out mods locales)
          rel (copy-app! out entries)
          champaca-bin (fs/path out "lib" "champaca")
          scratch (champaca-project repo)
          _ (vswap! made assoc :scratch scratch)]
      (println "bundle: building champaca.main with" jolt)
      (jolt-build! jolt scratch "champaca.main" champaca-bin signable)
      (when jolt-ns
        (println "bundle: building" jolt-ns "in" project)
        (jolt-build! jolt (fs/canonicalize project) jolt-ns (fs/path out "lib" name) signable))
      (let [bin (write-launcher! out name (launcher-exec main name rel))]
        (warm-cache! out rt champaca-bin bin name train)
        (print-sizes! out name train)))))

(defn -main [repo jolt & args]
  (let [made (volatile! nil)]
    (try
      (build! repo jolt made args)
      (catch Throwable e
        ;; any failure: the partial OUT goes, so a rerun with the same -o works
        (when-let [o (:out @made)]
          (binding [*out* *err*] (println "bundle: removing the partial" o))
          (fs/delete-tree o))
        (when-not (::die (ex-data e))
          (some-> (:scratch @made) fs/delete-tree)
          (throw e))
        (binding [*out* *err*] (println "bundle:" (ex-message e)))
        ;; System/exit skips the finally below
        (some-> (:scratch @made) fs/delete-tree)
        (System/exit 2))
      (finally
        (some-> (:scratch @made) fs/delete-tree)))))
