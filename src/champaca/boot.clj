;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.


(ns champaca.boot
  "The boot: the runtime loaded (natives bound by name from
  resources/champaca/natives.ss), the launcher's state passed to it, its
  hooks installed, and the JDK booted as the JVM boots it before main, from
  the startup cache when it holds this boot."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [entry-url jdk-resource-bytes module-resource-bytes module-resource-url
                                        package-info plain-resource-path? resource-bytes resource-urls
                                        with-manifest-class-paths]]
            [champaca.cachedir :refer [cache-dir cache-read-only? cache-unusable! delete-tree! runtime-files]]
            [champaca.jit :refer [classpath-class-answer constant-value frame-info jit! npe-message]]
            [champaca.launcher :refer [module-properties]]
            [champaca.libcache :refer [load-cache! write-cache!]]
            [champaca.loader :refer [define-class! ensure-loaded!]]
            [champaca.state :refer [state env timed scheme! ensure-loaded-fn]]))

(defn- check-lines-env!
  "CHAMPACA_LINES takes all or off (lines?); anything else is a typo, said
  on stderr."
  []
  (when-let [v (env "CHAMPACA_LINES")]
    (when-not (#{"all" "off"} (str/lower-case v))
      (binding [*out* *err*]
        (println (str "champaca: CHAMPACA_LINES=" v " isn't all or off; using the default, lines for classes from class directories"))))))

(defn- load-cached-runtime!
  "Load the cache's runtime.so, true when it loaded. The cache key covers the
  runtime's source, so a cached runtime.so is always this source compiled. A
  damaged one is discarded with the cache, or over a read-only cache makes
  it unusable."
  [dir so ro]
  (and so (.exists so)
       (try (timed :cache-load-runtime #(s/call "load" (str so))) true
            (catch Exception e
              (if ro
                (cache-unusable! dir (str "its runtime doesn't load (" (ex-message e) ")"))
                (do (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
                    (delete-tree! dir)))
              false))))

(defn- compile-runtime!
  "A cold start writes runtime.so into the cache anyway, so compile it first
  and load it, rather than evaluating the source and compiling it again for
  the cache (write-cache! moves this file into place). It goes inside the
  temp dir write-cache! will fill and rename, so a run that dies first
  leaves it where prune-cache-root! looks."
  [dir]
  (let [tmp (io/file (str dir ".tmp-" (System/nanoTime) (rand-int 1000000)))
        f (io/file tmp "runtime.so")
        src (io/file tmp "runtime.ss")]
    (.mkdirs tmp)
    (spit src (str/join "\n" (map #(slurp (io/resource %)) runtime-files)))
    (timed :runtime-compile
           #(s/eval-string (str "(parameterize ([current-output-port (open-output-string)]"
                                " [generate-inspector-information #f])"
                                " (compile-file " (pr-str (str src)) " " (pr-str (str f)) "))")))
    (.delete src)
    (timed :runtime-load #(s/call "load" (str f)))
    (swap! state assoc :cache-tmp tmp)))

(defn- load-runtime!
  "The runtime: the cache's runtime.so, else compiled into a cache that can
  be written, else its source evaluated."
  []
  (let [dir (cache-dir)
        so (when dir (io/file dir "runtime.so"))
        ro (and dir (cache-read-only? dir))]
    (when-not (load-cached-runtime! dir so ro)
      (when (and ro (not (.exists so)))
        (cache-unusable! dir "its runtime is missing"))
      (if (and dir (not ro))
        (compile-runtime! dir)
        (doseq [f runtime-files] (scheme! (slurp (io/resource f))))))))

(defn- pass-launcher-state!
  "What the launcher read reaches the runtime: the class path, the command,
  the properties, assertion directives, runtime arguments and preview."
  []
  (s/call "set-classpath!" (str/join ":" (:classpath-given @state)))
  (when-let [c (:java-command @state)] (s/call "set-java-command!" c))
  ;; the module options reach ModuleBootstrap as the launcher passes them
  ;; (module-properties); --add-modules or --limit-modules makes it resolve
  ;; the boot layer instead of reading the image's pre-generated graph
  (when-let [ps (seq (concat (:command-line-properties @state)
                             (module-properties (:module-options @state))))]
    (s/call "set-command-line-properties!" (str/join "\u0000" (mapcat identity ps))))
  (when-let [ds (seq (:assertion-directives @state))]
    (s/call "set-assertion-directives!" (str/join "\u0000" ds)))
  (when-let [as (seq (:runtime-arguments @state))]
    (s/call "set-runtime-arguments!" (str/join "\u0000" as)))
  (when @cf/preview-enabled (s/call "set-preview!" true)))

(defn- install-hooks!
  "The runtime's hooks into this namespace, after the launcher's state."
  []
  (s/call "set-define-class-hook!" (fn [& args] (timed :define #(apply define-class! args))))
  (s/call "set-resource-hook!" (fn [path] (or (resource-bytes path) false))
          ;; URLs as one string, one per line
          (fn [path] (str/join "\n" (resource-urls path))))
  ;; java -p's named modules, each from its own jar or directory
  (s/call "set-module-resource-hook!" (fn [mn path] (or (module-resource-bytes mn path) false)))
  (s/call "set-module-resource-url-hook!" (fn [mn path] (or (module-resource-url mn path) false)))
  (s/call "set-jpackage-hook!" package-info)
  ;; the JDK's resources alone, for the platform loader
  (s/call "set-jdk-resource-hook!" (fn [path] (or (when (plain-resource-path? path) (jdk-resource-bytes path)) false)))
  (s/call "set-classpath-class-hook!" classpath-class-answer)
  ;; a classpath class's code source: its entry's URL, or false
  (s/call "set-code-source-hook!" (fn [cname] (if-let [e (get-in @state [:class-entries cname])] (entry-url e) false)))
  (s/call "set-cp-hook!" constant-value)
  ;; helpful NPE messages: site "pkg/Cls.name(desc)@bci" -> text, or false
  (s/call "set-npe-hook!" (fn [site] (or (npe-message site) false)))
  ;; stack traces: a frame's file, line and module as a Scheme vector
  (s/call "set-frame-hook!" (fn [cname mname desc pc]
                              (let [[f l m h] (frame-info cname mname desc pc)] (s/call "vector" f l m h)))))

(def ^:private boot-phases
  "The JVM's boot before main, as Scheme text run guarded: its status."
  "(run-guarded (lambda ()
     ;; String.<clinit> sets COMPACT_STRINGS; before it runs,
     ;; hashCode takes the UTF-16 path on Latin-1 strings, and
     ;; initPhase1 would key the property map by those hashes
     (jinit \"java/lang/String\")
     (jinit \"java/lang/System\")
     (boot-threads!)
     ;; the JVM initializes Reference before initPhase1; its <clinit>
     ;; installs SharedSecrets.javaLangRefAccess, which initPhase1 uses
     (jinit \"java/lang/ref/Reference\")
     ;; AccessibleObject.<clinit> installs langReflectAccess, which
     ;; ReflectionFactory needs (OpenLDK %initialize-system-phase1)
     (jinit \"java/lang/reflect/AccessibleObject\")
     ((method-proc \"java/lang/System\" \"initPhase1()V\"))
     ;; initPhase2 builds the boot layer from the image's
     ;; pre-generated module graph (SystemModules$default, no
     ;; resolution, unless --add-modules asks for one) and
     ;; initPhase3 makes the app class loader the system and
     ;; context loader, as the JVM does before main; a failing
     ;; phase 2 has printed its cause already, as HotSpot's: to
     ;; stdout, the exception and its cause without a trace,
     ;; and the VM ends with status 1, as a halt does
     ;; (under CHAMPACA_BT too: the exception line names a
     ;; champaca fault, a missing native say, and BT must not
     ;; change what the program prints)
     ;; a cold boot archives the module graph there (archive.ss)
     (unless (eqv? 0 (module-graph-phase2 (lambda () ((method-proc \"java/lang/System\" \"initPhase2(ZZ)I\") 0 0))
                                          module-graph-dumping))
       (raise (make-java-halt 1)))
     ;; from here the boot layer decides which JDK classes
     ;; the built-in loaders see (builtin-loader-sees?)
     (boot-layer-complete!)
     ((method-proc \"java/lang/System\" \"initPhase3()V\"))))")

(defn- finish-recording!
  "After the boot: a cold boot's code goes to boot.so, and from here on only
  what later code compiles is recorded. A warm boot keeps what it recorded
  for this run's generation: it compiles only classes the cached boot never
  loaded (a -Dstdout.encoding brings the boot a new encoder), and dropping
  their registrations left their linked maps in state.img with no code
  behind them, so a later run took them for registered and failed with
  \"class not registered\". Nothing is written over a cache that can't be
  written and didn't load."
  [dir hit? status]
  (when-let [rec (:recording @state)]
    (when-not hit?
      (when (and (zero? status) (not (:cache-unusable @state)))
        (timed :cache-write #(write-cache! dir @rec)))
      (vreset! rec []))))

(defn- run-boot!
  "The boot from the startup cache, or recorded for it on a miss: its
  status."
  []
  (let [dir (cache-dir)
        hit? (load-cache! dir)]
    ;; a miss over a cache that can't be written (a file of it gone): no
    ;; cold write there
    (when (and dir (not hit?) (cache-read-only? dir))
      (cache-unusable! dir "holds no boot for this run"))
    ;; nothing is recorded for a cache this run doesn't use (it can't be
    ;; written and didn't load): its save would write into it anyway when
    ;; only the root is read-only
    (when (and dir (not (:cache-unusable @state)))
      (swap! state assoc :recording (volatile! []) :cache-dir dir))
    ;; the archived module graph: read on a hit, made on a recorded miss
    (when hit? (s/call "module-graph-read!" (str (io/file dir "module-graph.img"))))
    (s/call "module-graph-dumping!" (boolean (and dir (not hit?) (not (:cache-unusable @state)))))
    (run! ensure-loaded! ["java/lang/Object" "java/lang/String" "java/lang/Class"])
    (let [status (s/eval-string boot-phases)]
      (finish-recording! dir hit? status)
      (swap! state assoc :cache (cond hit? :hit (and dir (not (:cache-unusable @state))) :miss :else :off))
      status)))

(defn- boot!
  "Load the runtime, then run System.initPhase1 the way the JVM does before
  main. Uses the boot cache when it is valid and writes it when it is not.
  Returns 0 on success."
  []
  (when-not @ensure-loaded-fn
    (throw (ex-info "champaca.state/ensure-loaded-fn is unset: champaca.loader installs ensure-loaded! when it loads, and class-info can't load a class without it" {})))
  (check-lines-env!)
  (load-runtime!)
  (s/call "set-hooks!" jit! (fn [n] (ensure-loaded! n) nil))
  (pass-launcher-state!)
  (install-hooks!)
  (run-boot!))

(defn init!
  "Boot the JDK once (System.initPhase1 and what precedes it) with the given
  user classpath dirs. Returns 0 on success; a second call is a no-op.
  opts: {:preview true} turns on this release's preview features, as
  --enable-preview does, before the boot (never after: the JDK reads it once);
  :add-modules [\"jdk.incubator.vector\" ...] (or one comma-separated string)
  adds root modules to the boot layer, as --add-modules does."
  ([classpath] (init! classpath {}))
  ([classpath {:keys [preview add-modules]}]
   (or (:boot-status @state)
       (do (when preview (reset! cf/preview-enabled true))
           ;; a string is one module list, as the option's value
           (when (seq add-modules)
             (swap! state update :module-options (fnil conj [])
                    ["addmods" (if (string? add-modules) add-modules (str/join "," add-modules))]))
           (swap! state assoc :classpath (with-manifest-class-paths (vec classpath))
                  ;; java.class.path is what was given, as the JVM's launcher has it
                  :classpath-given (vec classpath))
           (let [status (boot!)] (swap! state assoc :boot-status status) status)))))
