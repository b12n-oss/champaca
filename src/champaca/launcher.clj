;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.launcher
  "The java-style command line: option parsing (jvm-options and its
  handler table), the launcher's refusals, class path expansion and the
  options' effect on the shared state. Nothing here touches the caches or
  loads a class."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classfile :as cf]
            [champaca.state :refer [state]]))

;; The launcher's module options, each the jdk.module.<suffix> property
;; HotSpot makes of it (Arguments::parse_each_vm_init_arg): numbered ones
;; are one property per occurrence, .0 up, per option; the others one
;; property, the last occurrence winning. Taken as --opt value or --opt=value;
;; an empty value is the launcher's refusal, except --add-opens=, which it
;; lets through to ModuleBootstrap.
(def ^:private module-options
  {"--add-modules" ["addmods" :numbered]
   "--enable-native-access" ["enable.native.access" :numbered]
   "--add-opens" ["addopens" :numbered]
   "--add-exports" ["addexports" :numbered]
   "--add-reads" ["addreads" :numbered]
   "--limit-modules" ["limitmods" :single]})

(defn module-properties
  "The [key value] system properties for :module-options, a vector of
  [suffix value] in command-line order, as HotSpot passes them to
  ModuleBootstrap."
  [opts]
  (let [numbered (set (keep (fn [[sfx kind]] (when (= kind :numbered) sfx)) (vals module-options)))]
    (loop [[[sfx v] & more :as os] opts counts {} singles {} out []]
      (cond (empty? os) (into out (map (fn [[sfx v]] [(str "jdk.module." sfx) v]) singles))
            (numbered sfx) (let [n (get counts sfx 0)]
                             (recur more (assoc counts sfx (inc n)) singles (conj out [(str "jdk.module." sfx "." n) v])))
            :else (recur more counts (assoc singles sfx v) out)))))

(defn launcher-error
  "The java launcher's refusal of an option: its text on stderr, status 1."
  [& lines]
  (binding [*out* *err*] (run! println lines))
  (System/exit 1))

(defn- assertion-directive
  "-ea, -da and their long forms as natives.ss set-assertion-directives!
  reads them: kind (u for the default, c a class, p a package, \"\" the
  unnamed one), + or -, the name slashed. nil for any other option."
  [a]
  (let [[_ flag arg] (or (re-matches #"-(ea|da|enableassertions|disableassertions)(?::(.*))?" a) [])
        on (if (#{"ea" "enableassertions"} flag) "+" "-")]
    (when flag
      (cond (nil? arg) (str "u" on)
            (str/ends-with? arg "...") (str "p" on (str/replace (subs arg 0 (- (count arg) 3)) "." "/"))
            :else (str "c" on (str/replace arg "." "/"))))))

(defn- size-bytes
  "-Xss's size as bytes: digits with an optional k, m, g or t suffix."
  [s]
  (let [[_ n unit] (re-matches #"([0-9]+)([kKmMgGtT]?)" s)]
    (* (parse-long n) (case (str/lower-case unit) "k" 1024 "m" 1048576 "g" 1073741824 "t" 1099511627776 1))))

;; HotSpot's Arguments::is_internal_module_property: jdk.module. then one of
;; these, alone or followed by a dot (jdk.module.addmods.0, not
;; jdk.module.addmodsx); the launcher's module options set them
(def ^:private reserved-module-suffixes
  ["addexports" "addreads" "addopens" "patch" "addmods" "limitmods" "path"
   "upgrade.path" "enable.native.access" "illegal.native.access"])

(defn- reserved-module-property? [k]
  (and (str/starts-with? k "jdk.module.")
       (let [rest-k (subs k 11)]
         (boolean (some (fn [x] (or (= rest-k x) (str/starts-with? rest-k (str x "."))))
                        reserved-module-suffixes)))))

(defn- ignored-option?
  "An option HotSpot takes that tunes, traces or checks only the VM, which
  champaca accepts with a warning: a heap size, -Xshare:, -XX:, -verbose
  (alone or with any kind, as HotSpot takes any), -Xlog (alone or with a
  selection), the execution modes (-Xint, -Xmixed, -Xcomp, -Xbatch), -Xrs,
  -Xverify:, -Xcheck:jni and -Xnoclassgc."
  [a]
  (boolean (or (re-matches #"-X(mx|ms|mn)[0-9]+[kKmMgGtT]?" a)
               (re-matches #"-XX:.+" a) (re-matches #"-Xshare:.+" a)
               (re-matches #"-verbose(:.*)?" a) (re-matches #"-Xlog(:.*)?" a)
               (re-matches #"-Xverify:.*" a)
               (#{"-Xint" "-Xmixed" "-Xcomp" "-Xbatch" "-Xrs" "-Xcheck:jni" "-Xnoclassgc"} a))))

(defn- option-value
  "An option's separate value as HotSpot's launcher takes it: nil when
  there is none or it starts with \"-\", or when it's empty and empty?
  is false."
  [v empty?]
  (when (and v (not (str/starts-with? v "-")) (or empty? (not= "" v))) v))

(defn- vm-error
  "The launcher's refusal of an option the VM itself judges: lines, then
  HotSpot's two closing lines, status 1."
  [& lines]
  (apply launcher-error (concat lines ["Error: Could not create the Java Virtual Machine."
                                       "Error: A fatal exception has occurred. Program will exit."])))

(defn- separate-value
  "The value after the option starting argv, or the launcher's \"Error: <option>
  requires <what>\". As the launcher, a separate value can't start with
  \"-\" (it's the next option), and only empty? lets it be empty."
  [argv empty? what]
  (or (option-value (second argv) empty?)
      (launcher-error (str "Error: " (first argv) " requires " what))))

(defn- joined-value
  "The value after an option's =, or \"Error: <option>= requires <what>\"
  when it's empty."
  [a what]
  (let [i (str/index-of a "=") v (subs a (inc i))]
    (if (= "" v) (launcher-error (str "Error: " (subs a 0 (inc i)) " requires " what)) v)))

(defn- add-module-option [opts suffix v]
  (update opts :module-options conj [suffix v]))

;; HotSpot refuses the extension and endorsed directories of Java 8
(def ^:private removed-dir-properties
  {"java.endorsed.dirs" (str " is not supported. Endorsed standards and standalone APIs\n"
                             "in modular form will be supported via the concept of upgradeable modules.")
   "java.ext.dirs" " is not supported.  Use -classpath instead."})

(defn- define-option
  "-Dkey=value, -Dkey an empty value, a bare -D nothing. A non-empty
  removed directory property is refused, the first one given, as HotSpot
  parses it: the text on stdout, the launcher's lines on stderr. One of the module system's own
  properties (reserved-module-property?) is dropped and noted."
  [opts argv]
  (let [a (first argv) kv (subs a 2) i (str/index-of kv "=")
        k (if i (subs kv 0 i) kv)
        v (if i (subs kv (inc i)) "")]
    (when-let [why (and (not= "" v) (removed-dir-properties k))]
      (println (str a why))
      (flush)
      (vm-error))
    [(cond (= "" k) opts
           (reserved-module-property? k) (assoc opts :module-props-ignored true)
           :else (update opts :props conj [k v]))
     (rest argv)]))

(defn- joined-module-option?
  "--add-modules=M and the other module options joined to their value."
  [a]
  (when-let [i (str/index-of a "=")] (module-options (subs a 0 i))))

(defn- joined-module-option
  "A module option joined to its value; only --add-opens= may be empty."
  [opts argv]
  (let [a (first argv) i (str/index-of a "=") o (subs a 0 i)
        v (if (= "--add-opens" o) (subs a (inc i)) (joined-value a "modules to be specified"))]
    [(add-module-option opts (first (module-options o)) v) (rest argv)]))

(defn- version-option
  "As java.c: a -- form sends the version to stdout from then on, and
  -version or --version ends the parsing there, whatever follows it (a bad
  option, -showversion) left unread."
  [opts argv]
  (let [a (first argv)
        opts (update opts :version (fn [v] {:err? (and (get v :err? true) (not (str/starts-with? a "--")))}))]
    (if (#{"-version" "--version"} a)
      (reduced (assoc opts :argv [] :version (assoc (:version opts) :exit? true)))
      [opts (rest argv)])))

(defn- stack-option
  "-Xss with HotSpot's checks: 0 is the default, 208k the least, 1g the most."
  [opts argv]
  (let [a (first argv)
        n (when (re-matches #"[0-9]+[kKmMgGtT]?" (subs a 4)) (size-bytes (subs a 4)))]
    (cond (nil? n) (vm-error (str "Invalid thread stack size: " a))
          (> n 1073741824) (vm-error (str "Invalid thread stack size: " a) "The specified size exceeds the maximum representable size.")
          (zero? n) [(dissoc opts :stack) (rest argv)]
          (< n (* 208 1024)) (vm-error "" "The Java thread stack size specified is too small. Specify at least 208k")
          :else [(assoc opts :stack n) (rest argv)])))

(defn- ignore-option [opts argv]
  (binding [*out* *err*] (println (str "champaca: ignoring option " (first argv) "; it has no counterpart here")))
  [opts (rest argv)])

(defn- option-flag
  "A handler for an option taking no separate value: (f opts option)."
  [f]
  (fn [opts argv] [(f opts (first argv)) (rest argv)]))

(defn- prefix [p] (fn [a] (str/starts-with? a p)))

(def ^:private option-handlers
  "[test handler] for each option jvm-options knows, tried in order on the
  option. A handler takes the options so far and the argv starting at the
  option, and answers [options argv-after] to go on or a reduced options
  map to stop (with :argv set)."
  [[(prefix "-D") define-option]
   [#{"-cp" "-classpath" "--class-path"}
    (fn [opts argv] [(assoc opts :classpath (separate-value argv true "class path specification")) (nnext argv)])]
   [(prefix "--class-path=") (option-flag (fn [opts a] (assoc opts :classpath (subs a 13))))]
   ;; the module path, as the launcher passes it: jdk.module.path
   [#{"-p" "--module-path"}
    (fn [opts argv] [(add-module-option opts "path" (separate-value argv false "module path specification")) (nnext argv)])]
   [(prefix "--module-path=")
    (option-flag (fn [opts a] (add-module-option opts "path" (joined-value a "modules to be specified"))))]
   ;; -m module[/class] ends the options, as -jar does: what follows is
   ;; the program's; jdk.module.main makes the module a root
   [#{"-m" "--module"}
    (fn [opts argv] (reduced (assoc opts :module (separate-value argv false "module name") :argv (nnext argv))))]
   [(prefix "--module=")
    (fn [opts argv] (reduced (assoc opts :module (joined-value (first argv) "module name") :argv (rest argv))))]
   [assertion-directive (option-flag (fn [opts a] (update opts :assertions conj (assertion-directive a))))]
   [#{"-esa" "-enablesystemassertions"} (option-flag (fn [opts _] (update opts :assertions conj "s+")))]
   [#{"-dsa" "-disablesystemassertions"} (option-flag (fn [opts _] (update opts :assertions conj "s-")))]
   ;; -Xdiag only makes the launcher's own errors longer
   [#{"-server" "-client" "-Xdiag"} (option-flag (fn [opts _] opts))]
   [#{"--enable-preview"} (option-flag (fn [opts _] (assoc opts :preview true)))]
   [#{"--source"} (fn [opts argv] [(assoc opts :source (separate-value argv false "source version")) (nnext argv)])]
   [(prefix "--source=") (option-flag (fn [opts a] (assoc opts :source (subs a 9))))]
   [module-options
    (fn [opts argv]
      [(add-module-option opts (first (module-options (first argv))) (separate-value argv false "modules to be specified"))
       (nnext argv)])]
   [joined-module-option? joined-module-option]
   ;; only the joined form: the launcher doesn't know this one, and
   ;; ModuleBootstrap judges the value, an empty one too
   [(prefix "--illegal-native-access=")
    (option-flag (fn [opts a] (add-module-option opts "illegal.native.access" (subs a 24))))]
   [#{"-version" "--version" "-showversion" "--show-version"} version-option]
   [(prefix "-Xss") stack-option]
   [ignored-option? ignore-option]])

(defn- option-step
  "One step of jvm-options: the end of the options (the main class, -jar or
  nothing left), a known option's handler, or the launcher's Unrecognized
  option."
  [opts argv]
  (let [a (first argv)]
    (if (or (nil? a) (= a "-jar") (not (str/starts-with? a "-")))
      (reduced (assoc opts :argv argv))
      (if-let [handle (some (fn [[test handle]] (when (test a) handle)) option-handlers)]
        (handle opts argv)
        (vm-error (str "Unrecognized option: " a))))))

(defn jvm-options
  "The options before the main class or -jar, as the java launcher reads
  them, as {:props [[k v]...] :classpath s-or-nil :assertions [...] :argv rest}:
  -Dkey=value pairs (-Dkey is an empty value, a bare -D nothing); -cp,
  -classpath and --class-path (the last one wins); -ea/-da with a class, a
  package... or nothing, and -esa/-dsa, in order (assertion-directive);
  -server and -client, which the launcher accepts and ignores;
  --enable-preview, as :preview (this release's preview class files load,
  PreviewFeatures says so); --source N or --source=N, as :source (the
  source launcher compiles at that release, source-file mode); the module
  options (module-options: --add-modules, --enable-native-access,
  --add-opens, --add-exports, --add-reads, --limit-modules), each value
  as given and --illegal-native-access=V, as [suffix value] entries of
  :module-options in order; -version and --version (print the version and
  exit, the options after them unread) and -showversion and --show-version
  (print it, then run main), as :version {:err? :exit?}, on stderr unless a
  -- form came first; -Xss, the
  thread stack size, as :stack in bytes
  (bound-stack!). A non-empty -Djava.endorsed.dirs or -Djava.ext.dirs is
  HotSpot's refusal. A -D naming one of the module system's own properties
  (reserved-module-property?) is dropped and :module-props-ignored set, as
  HotSpot ignores it with a warning. A heap size (-Xmx,
  -Xms, -Xmn), -Xshare:, any -XX: flag, -verbose[:...], -Xlog[:...] and the
  execution-mode and diagnostic -X options (ignored-option?) are ignored
  with a warning, since start scripts pass them and they tune or trace only
  the VM; any other option is the launcher's Unrecognized option. Each
  option is one of option-handlers."
  [argv]
  (loop [argv argv opts {:props [] :classpath nil :assertions [] :module-options []}]
    (let [r (option-step opts argv)]
      (if (reduced? r)
        @r
        (let [[opts argv] r] (recur argv opts))))))

(defn- runtime-arguments
  "The VM options HotSpot reports (VM.getRuntimeArguments) for the options
  jvm-options consumed: each as given and in place, except the class path
  ones, which become java.class.path alone, and -server/-client, which the
  launcher drops; --source N is -Djdk.internal.javac.source=N, the property
  the launcher passes; a module option's separate value is joined to it
  (--add-opens M is --add-opens=M); the launcher's own -version,
  --version, -showversion, --show-version and -Xdiag aren't listed;
  source-file mode ends with --add-modules=ALL-DEFAULT."
  [consumed source-mode?]
  (concat (loop [[a & more :as as] consumed out []]
            (cond (empty? as) out
                  (module-options a) (recur (rest more) (conj out (str a "=" (first more))))
                  (#{"-cp" "-classpath" "--class-path" "--source"} a)
                  (recur (rest more) (case a
                                       "--source" (conj out (str "-Djdk.internal.javac.source=" (first more)))
                                       out))
                  (str/starts-with? a "--source=") (recur more (conj out (str "-Djdk.internal.javac.source=" (subs a 9))))
                  (or (str/starts-with? a "--class-path=")
                      (#{"-server" "-client" "-version" "--version" "-showversion" "--show-version" "-Xdiag"} a))
                  (recur more out)
                  :else (recur more (conj out a))))
          (when source-mode? ["--add-modules=ALL-DEFAULT"])))

(defn- source-file?
  "Whether the launcher runs a as a source file: a name ending .java that is
  a regular file (a missing one is a main class that isn't found)."
  [a]
  (and (string? a) (str/ends-with? a ".java") (.isFile (io/file a))))

(defn- expand-wildcard
  "A dir/* (or bare *) entry as its directory's .jar and .JAR files, sorted,
  or [e] itself when there are none."
  [e]
  (let [dir (case e "*" "." "/*" "/" (subs e 0 (- (count e) 2)))
        jars (for [n (some-> (io/file dir) .list seq sort)
                   :when (or (str/ends-with? n ".jar") (str/ends-with? n ".JAR"))]
               (case e "*" n "/*" (str "/" n) (str dir "/" n)))]
    (or (seq jars) [e])))

(defn expand-class-path
  "A class path string as a list of entries, a dir/* (or a bare *) standing
  for the names ending .jar or .JAR in that directory, as the java launcher
  expands it. One that finds none, or names no directory, stays as written,
  as java leaves it (so a directory really named * still works). Sorted by
  name: the launcher takes the directory's own order, which its
  documentation leaves unspecified."
  [cp]
  (mapcat (fn [e] (if (or (= e "*") (str/ends-with? e "/*")) (expand-wildcard e) [e]))
          (str/split cp #":")))

;; jolt bounds a thread's stack at 80% of the heap ceiling by default (gigabytes)
;; and throws StackOverflowError past it, found at a collection; a Java program
;; recursing without end reached it after 13 s and 7.6 GB, where the JVM's ends
;; at a megabyte. The launcher bounds it as java does: 1 MB, the JVM's default
;; thread stack, or -Xss's size. A recursion of one-argument calls ends near
;; the JVM's depth (52,000 frames to its 42,000 through a try/finally a frame),
;; and a cold boot's own recursions fit (Chez compiling SystemModules nests
;; 12,000 deep; a cold Spring Boot start passed). A deeper limit costs more than
;; memory: unwinding through a try region per frame is quadratic in the depth
;; (Chez's $do-wind measures both winder lists on each escape). The bound is
;; checked at collections, so a stack of small frames passes it by up to a
;; nursery's worth before it's found. JOLT_STACK_LIMIT, when set, decides
;; instead; the jolt API leaves the process alone, the program around it being
;; jolt's.
(def ^:private default-stack-limit (* 1024 1024))
(defn- bound-stack! [bytes]
  (s/eval-string (str "(when (and (top-level-bound? 'jolt-stack-limit-defaulted?) (top-level-bound? 'jolt-stack-limit-set!)"
                      "           (top-level-value 'jolt-stack-limit-defaulted?))"
                      "  ((top-level-value 'jolt-stack-limit-set!) " bytes "))")))

(def ^:private source-launcher "com/sun/tools/javac/launcher/SourceLauncher")

(defn source-mode-of
  "Source-file mode, as java's: --source, or a first argument that is a
  .java file; the file and its arguments go to javac's SourceLauncher, which
  compiles it in memory and runs its main."
  [argv source]
  (boolean (and (seq argv) (not= "-jar" (first argv)) (or source (source-file? (first argv))))))

(defn apply-launcher-options!
  "The launcher's options take effect before the program runs: the stack
  bound, the properties, assertion directives and runtime arguments, preview
  and the module options, in state."
  [{:keys [props assertions stack preview source module-options version module-props-ignored module]} consumed source-mode?]
  (let [module-options (cond-> module-options
                         module (conj ["main" (first (str/split module #"/"))]))
        props (cond-> props source (conj ["jdk.internal.javac.source" source]))]
    (when (and stack (System/getenv "JOLT_STACK_LIMIT"))
      (binding [*out* *err*] (println "champaca: ignoring -Xss; JOLT_STACK_LIMIT sets the stack bound")))
    (bound-stack! (or stack default-stack-limit))
    (swap! state assoc :command-line-properties props :assertion-directives assertions
           :runtime-arguments (vec (runtime-arguments consumed source-mode?)) :source-mode source-mode?)
    (when preview (reset! cf/preview-enabled true))
    (when module-props-ignored
      (binding [*out* *err*]
        (println "OpenJDK 64-Bit Server VM warning: Ignoring system property options whose names match the '-Djdk.module.*'. names that are reserved for internal use.")))
    (swap! state assoc :module-options module-options :version version)))

(defn program-args
  "[class-path main-class & args] from what follows the options. With -cp the
  main class comes first; without, champaca's own form, the class path first
  (-jar ignores -cp, as java does). In source-file mode the class path is
  -cp's or, as the launcher has it, \".\" alone, or nothing with --source. A
  lone .java name that isn't a file is java's main class (\"Could not find or
  load main class\"); followed by a main class it stays champaca's class
  path, which may name a d.java directory."
  [argv classpath source source-mode?]
  (cond source-mode? (list* (or classpath (if source "" ".")) source-launcher argv)
        (and classpath (not= "-jar" (first argv))) (cons classpath argv)
        (and (= 1 (count argv)) (str/ends-with? (first argv) ".java")) (cons "." argv)
        :else argv))

(defn print-usage! []
  (binding [*out* *err*]
    (println "usage: jolt -M -m champaca.main [options] <classpath[:entry...] (dirs, jars, dir/*)> <MainClass> [args...]")
    (println "   or: jolt -M -m champaca.main [options] -cp <classpath> <MainClass> [args...]")
    (println "   or: jolt -M -m champaca.main [options] -jar <jarfile> [args...]")
    (println "   or: jolt -M -m champaca.main [options] [--source <release>] <file.java> [args...]")
    (println "   or: jolt -M -m champaca.main [options] -p <module path> -m <module>[/<mainclass>] [args...]")
    (println "options: -Dkey=value, -ea[:<class>|:<package>...], -da[...], -esa, -dsa, --enable-preview, --source <release>,")
    (println "         --add-modules, --limit-modules, --enable-native-access <module>[,...], --add-opens, --add-exports <module>/<package>=<module>[,...],")
    (println "         --add-reads <module>=<module>[,...], --illegal-native-access=allow|warn|deny, -version, -showversion, --version, --show-version;")
    (println "         -server, -client, -Xdiag accepted; -Xss<size> bounds the stack (1m by default); -Xmx, -Xms, -Xmn, -Xshare:, -XX:, -verbose, -Xlog,")
    (println "         -Xint, -Xmixed, -Xcomp, -Xbatch, -Xrs, -Xverify:, -Xcheck:jni, -Xnoclassgc ignored with a warning")))
