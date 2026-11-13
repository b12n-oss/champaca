;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.


(ns champaca.main
  "The command-line entry: the java-like launcher, which reads the options,
  boots the JDK through champaca.boot and runs the main class."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.boot :refer [init!]]
            [champaca.classpath :refer [jar-manifest]]
            [champaca.launcher :refer [launcher-error jvm-options expand-class-path
                                       source-mode-of apply-launcher-options! program-args print-usage!]]
            [champaca.libcache :refer [save-generation! saves-generation!]]
            [champaca.state :refer [state env prof-report]]))

(defn- stats []
  (let [{:keys [classes lazy jitted jitted-user units-loaded restored]} @state]
    (binding [*out* *err*]
      (println (str "champaca: " (- (count classes) (count lazy)) " classes loaded ("
                    (or restored 0) " from the library cache), " jitted " methods compiled ("
                    (or jitted-user 0) " of classpath classes), " (or units-loaded 0) " cached units loaded"))
      ;; classes loaders of the program's own defined, by what the cache did with them
      (let [{:keys [defined-new defined-changed defined-uncacheable cached-methods-dropped]} @state
            n (fn [k] (or k 0))]
        (when (some pos? (map n [defined-new defined-changed defined-uncacheable cached-methods-dropped]))
          (println (str "champaca: loader-defined classes not from the cache: " (n defined-new) " new, "
                        (n defined-changed) " changed, " (n defined-uncacheable) " of them uncacheable; "
                        (n cached-methods-dropped) " classes' cached methods dropped")))))))

(defn- on-signal-shutdown!
  "Registered with jolt, which runs it when SIGINT, SIGTERM or SIGHUP ends the
  process (and at any exit): Java's shutdown hooks run as HotSpot runs them
  on those signals, and the startup cache is saved as a normal end saves it,
  so a server stopped with a signal (a Spring Boot app under kill) has run
  its hooks and comes back warm. At a normal end Java has already run them
  and this does nothing."
  []
  (s/call "gil-enter!")
  (try (when (true? (s/call "java-signal-shutdown!")) (save-generation!))
       (finally (s/call "gil-leave!"))))

(defn- install-exit-saves!
  "Save the startup cache however the process ends: on a signal (jolt's
  shutdown hook) and on System.exit from another thread, which ends the
  process there, past run's own save."
  []
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable on-signal-shutdown!))
  (s/call "set-off-main-exit-hook!" (fn [] (save-generation!))))

(defn- launch!
  "Print the version when asked, then run the program as the command line
  named it (a module, a jar or a class): its exit status. Only a version
  printed and nothing to run (-version) ends without running."
  [cname args]
  (let [v (:version @state)
        vs (if v (s/call "java-print-version" (boolean (:err? v))) 0)]
    (cond (not (and cname (zero? vs))) vs
          (:launch-module @state) (apply s/call "run-module-main" cname args)
          (:launch-jar @state) (apply s/call "run-jar-main" (:launch-jar @state) args)
          :else (apply s/call "run-main" cname args))))

(defn run
  "Run cname's main method with classes found under the classpath dirs,
  after booting the JDK. Returns the exit status. With :version in the
  state (-showversion and friends) the JDK's VersionProps prints the
  version first; a nil cname runs nothing after it (-version)."
  [classpath cname args]
  (saves-generation!) ; save-generation! runs at exit below
  (let [t0 (System/nanoTime)
        status (init! classpath)
        _ (when (env "CHAMPACA_PROF") (prof-report "boot" (- (System/nanoTime) t0)))]
    (when (env "CHAMPACA_STATS") (binding [*out* *err*] (print "after boot: ")) (stats))
    (if (zero? status)
      (let [_ (install-exit-saves!)
            status (launch! cname args)]
        (when (env "CHAMPACA_PROF") (prof-report "run" (- (System/nanoTime) t0)))
        (when (env "CHAMPACA_STATS") (stats))
        (save-generation!)
        status)
      status)))

(defn- run-jar!
  "As java -jar: the manifest's Main-Class, the jar alone as the class path
  given (its Class-Path entries follow it, as for any jar). Any file,
  whatever its name, as java -jar takes it; one that isn't a zip is the
  launcher's \"invalid or corrupt\"."
  [jar args]
  (let [file? (.isFile (io/file jar))
        m (when file? (try (or (jar-manifest jar) {}) (catch Exception _ ::corrupt)))
        main (when (map? m) (get-in m [nil "main-class"]))]
    (when-not main
      (binding [*out* *err*]
        (println (cond (not file?) (str "Error: Unable to access jarfile " jar)
                       (= m ::corrupt) (str "Error: Invalid or corrupt jarfile " jar)
                       :else (str "no main manifest attribute, in " jar))))
      (System/exit 1))
    ;; :launch-jar: the JDK's LauncherHelper reads the manifest itself in its
    ;; jar mode, so Add-Opens, Add-Exports and Enable-Native-Access apply
    (swap! state assoc :java-command (str/join " " (cons jar args)) :launch-jar jar)
    (System/exit (run [jar] (str/replace (str/trim main) "." "/") (vec args)))))

(defn- run-main-class!
  "The main class on the class path cp. sun.java.command, as the launcher
  has it: the main class as given (the source launcher with its module),
  then the arguments."
  [cp cname args source-mode?]
  (swap! state assoc :java-command (str/join " " (cons (if source-mode? (str "jdk.compiler/" (str/replace cname "/" ".")) cname) args)))
  (System/exit (run (if (= "" cp) [] (vec (expand-class-path cp))) (str/replace cname "." "/") (vec args))))

(defn -main [& all-argv]
  (let [{:keys [classpath argv source version module] :as opts} (jvm-options all-argv)
        consumed (take (- (count all-argv) (count argv)) all-argv)
        source-mode? (source-mode-of argv source)
        _ (apply-launcher-options! opts consumed source-mode?)
        [cp cname & args] (program-args argv classpath source source-mode?)]
    ;; -version and --version print and exit, whatever follows, as the
    ;; launcher, which needs no main class for them
    (when (:exit? version)
      (System/exit (run (if classpath (vec (expand-class-path classpath)) []) nil [])))
    (when module
      ;; java -m: the main class is the module's, and the class path -cp's
      ;; alone ("" without one, as java.class.path then is)
      (swap! state assoc :java-command (str/join " " (cons module argv)) :launch-module module)
      (System/exit (run (if classpath (vec (expand-class-path classpath)) []) module (vec argv))))
    (when (and source (= "-jar" (first argv)))
      (launcher-error "Error: Option -jar is not allowed with --source"))
    (when (and (= cp "-jar") (not cname))
      (binding [*out* *err*] (println "Error: -jar requires jar file specification"))
      (System/exit 1))
    (when-not cname
      ;; -showversion without a program prints the version, then the usage
      (when version (run [] nil []))
      (print-usage!)
      (System/exit 2))
    (if (= cp "-jar")
      (run-jar! cname args)
      (run-main-class! cp cname args source-mode?))))
