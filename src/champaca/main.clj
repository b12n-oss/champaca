;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.main
  "Class loading, linking and the JIT hook, plus the command-line entry.

  Classes come from the user classpath first, then from the JDK's own
  runtime image. Loading a class registers it with the Scheme runtime and
  defines every method as a stub; the first call to a stub calls back into
  jit!, which translates that one method and redefines the global in place.
  Native methods are bound by name from resources/champaca/natives.ss.
  That mirrors OpenLDK, which also compiles each method on first call."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [bundle-root bytes-fp class-bytes class-source entry-url
                                        jar-entry? jar-manifest jar-path java-home jdk-class-bytes jdk-image
                                        jdk-resource-bytes layer-module module-resource-bytes
                                        module-resource-url note-class-entry! outside-boot-layer? package-dir
                                        package-info package-module plain-resource-path?
                                        refuse-outside-layer! resource-bytes resource-urls user-class-bytes
                                        user-resource-bytes with-manifest-class-paths]]
            [champaca.codegen :as cg]
            [champaca.launcher :refer [module-properties launcher-error jvm-options expand-class-path
                                       source-mode-of apply-launcher-options! program-args print-usage!]]
            [champaca.state :refer [state env trace? timed prof-report scheme! scheme-trace! *reads*
                                    packed? unpack packed= thaw-entry
                                    intern-keys intern-members
                                    packed-entry strip-for-cache]]
            [jolt.image :as img]))

(declare ensure-loaded! ancestry-of-unlinked lambda-class link registration-text delete-tree!)

(declare cache-read-only?)

(defn- parse-entry
  "thaw-entry for one packed class entry. Damage inside an entry of an
  otherwise readable state.img surfaces here, mid-run, where booting cold is
  no longer possible (generations are already half loaded). The cache goes,
  so the next run boots cold, and this run fails loudly."
  [cname e]
  (try (thaw-entry e)
       (catch Exception ex
         (binding [*out* *err*]
           (println "champaca: discarding a damaged startup cache (bad entry for"
                    (str cname "):") (ex-message ex)))
         (when-let [dir (:cache-dir @state)]
           (if (cache-read-only? dir)
             (binding [*out* *err*]
               (println "champaca: the cache can't be written, so it can't be dropped either: rebuild or reinstall it (" (str dir) ")"))
             (delete-tree! dir)))
         (throw ex))))

(defn- thaw!
  "The class table entry for cname as a map, or nil. A cache hit leaves
  every entry packed as UTF-8 edn bytes; the first read parses it and puts
  the map back, so a run only pays for the classes it touches. The swap
  keeps a map that is already there, so a racing thaw or a concurrent
  assoc-in on the same entry is never overwritten."
  [cname]
  (when-let [r *reads*] (vswap! r conj cname))
  (let [e (get-in @state [:classes cname])]
    (if (packed? e)
      (let [u (parse-entry cname e)]
        (-> (swap! state update-in [:classes cname] #(if (packed? %) u %))
            (get-in [:classes cname])))
      e)))

(defn- class-info [cname]
  (refuse-outside-layer! cname)
  (or (thaw! cname)
      (do (ensure-loaded! cname) (thaw! cname))))

(defn- class-vtable
  "cname's vtable, thawed on this first read when its entry kept it packed
  (thaw-entry) and put back in the table; a map already holding one answers
  it. The swap keeps a vtable a racing read put there first."
  [cname]
  (let [info (class-info cname)]
    (or (:vtable info)
        (when-let [p (:vtable-packed info)]
          (let [vt (intern-keys (edn/read-string (unpack p)) true)]
            (-> (swap! state update-in [:classes cname]
                       ;; the entry is there (class-info thawed it); a vtable a racing read stored stays
                       #(cond (nil? %) % (:vtable %) % :else (-> % (assoc :vtable vt) (dissoc :vtable-packed))))
                (get-in [:classes cname :vtable])))))))

(defn- vtable-of
  "The vtable of a class map: its own, or thawed through class-vtable."
  [info]
  (when info (or (:vtable info) (class-vtable (:name info)))))

(defn- class-methods
  "cname's methods, thawed on this first read when its entry kept them
  packed (thaw-entry), as class-vtable thaws a vtable."
  [cname]
  (let [info (class-info cname)]
    (or (:methods info)
        (when-let [p (:methods-packed info)]
          (let [ms (intern-members (edn/read-string (unpack p)))]
            (-> (swap! state update-in [:classes cname]
                       #(cond (nil? %) % (:methods %) % :else (-> % (assoc :methods ms) (dissoc :methods-packed))))
                (get-in [:classes cname :methods])))))))

(defn- methods-of
  "The methods of a class map: its own, or thawed through class-methods."
  [info]
  (when info (or (:methods info) (class-methods (:name info)))))

(defn- cache-root []
  (io/file (or (env "CHAMPACA_CACHE_DIR") (str (env "HOME") "/.cache/champaca"))))

(defn- library-file
  "The library cache's file for the classpath entry (a jar or a directory)
  at canonical path jar: lib/<hash>.img in the cache directory, holding the
  entries of the classes that entry supplied. An entry per file means a
  program reads only what its own entries cached, and two programs on
  different versions of a library (different paths) keep their own
  entries."
  [jar]
  ;; 64 bits from two string hashes, so two jars sharing a name (each would
  ;; overwrite the other's file every run) take thousands of times more jars
  ;; than 32 would; a clash still reads as {} (read-library-image)
  (let [h #(format "%08x" (bit-and (hash %) 0xffffffff))]
    (io/file (:cache-dir @state) "lib" (str (h jar) (h (str/reverse jar)) ".img"))))

(defn- read-library-image
  "The entries in jar's library file, {} when there is none, or when it
  belongs to another jar whose path hashes the same. Reading touches it, so
  pruning (prune-library!) keeps the files programs use. A damaged file is
  dropped (its classes compile again)."
  [jar]
  (let [f (library-file jar)]
    (or (when (.exists f)
          (try (let [m (timed :cache-read-library #(img/read-image (str f)))]
                 (when-not (and (map? m) (map? (:entries m)))
                   (throw (ex-info "not a champaca library image" {})))
                 (when (= jar (:jar m))
                   (.setLastModified f (System/currentTimeMillis))
                   (:entries m)))
               (catch Exception e
                 (binding [*out* *err*]
                   (println "champaca: discarding a damaged library cache file:" (str f) (ex-message e)))
                 (.delete f)
                 nil)))
        {})))

(defn- library-cache
  "The library cache's entries for the jar at canonical path jar, classpath
  classes by name as edn text, read the first time a run loads one of its
  classes."
  [jar]
  (or (get-in @state [:user-cache jar])
      (let [m (read-library-image jar)]
        (get-in (swap! state update-in [:user-cache jar] #(or % m)) [:user-cache jar]))))

(defn- cache-origin
  "Whether a class's code may be cached: :jdk, :user for a classpath class
  the library cache keeps (a jar's class whose dependencies can be checked,
  :src-fp and :src-jar set by library-cache-keys), else nil."
  [info]
  (case (:origin info)
    :jdk :jdk
    ;; a class a loader of the program's own defined is kept like a jar's
    ;; (define-class!), under the class path's file
    (:user :defined) (when (:src-fp info) :user)
    nil))

(defn- record!
  "Keep code for the startup cache when origin, a cache-origin, says it may
  be: {:text t} to compile when the cache is written, or {:fasl bv}, object
  code already compiled (compile-unit). unit says what it defines: [cname]
  a class's registration, [cname key] one method body."
  [origin unit code]
  (when-let [rec (:recording @state)]
    (when origin
      ;; the jar whose library file the save rewrites
      (when (= origin :user)
        (swap! state (fn [st] (update st :library-dirty (fnil conj #{}) (get-in st [:classes (first unit) :src-jar])))))
      (vswap! rec conj (assoc code :unit unit)))))

(defn- compile-to-cache?
  "Whether a JDK method should compile straight to object code kept for the
  cache (compile-unit): during boot, whose cache write always follows, or
  when this run saves a generation at exit (run does). Otherwise it is
  recorded as text, cheaper to compile, and compiled again only if
  save-cache! is called after all."
  [origin]
  (and origin (:recording @state)
       (or (nil? (:boot-status @state)) (:saves-generation @state))))

(defn- write-units!
  "Write units to path as one object file: text compiled here, fasl appended
  as it is. Returns each unit's start offset, then the file's length. With
  batch?, consecutive texts compile in one call (Chez pays per call), and
  their offsets are the run's start, so nothing can load them one by one."
  [path units batch?]
  (s/call "begin-units!" path)
  (let [runs (if batch? (partition-by #(boolean (:fasl %)) units) (map vector units))
        starts (try
                 (reduce (fn [acc run]
                           (if (:fasl (first run))
                             (into acc (map #(s/call "add-fasl!" (:fasl %)) run))
                             (let [start (s/call "add-unit!" (str/join "\n" (map :text run)) (boolean (some :inspect run)))]
                               (into acc (repeat (count run) start)))))
                         [] runs)
                 (catch Exception e
                   ;; close the file; the caller's temp name keeps it out of the cache
                   (s/call "end-units!")
                   (throw e)))]
    (conj starts (s/call "end-units!"))))

(defn- load-unit!
  "Run one unit of a cached generation: [file offset length]. False when the
  generation file is gone (pruned under a live process, cleared by hand), so
  callers fall back to compiling and the next save re-caches the unit."
  [[file off len]]
  (let [f (io/file (:cache-dir @state) file)]
    (if (.exists f)
      (do (swap! state update :units-loaded (fnil inc 0))
          (timed :cache-load-unit #(s/call "load-unit" (str f) off len))
          true)
      false)))

;; --- resolution ----------------------------------------------------------------------

(defn- ancestry
  "cname, its superclasses, then every superinterface reachable from any of
  them, each once, in that order."
  [cname]
  (let [supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (loop [todo (mapcat #(:interfaces (class-info %)) supers) seen (set supers) out (vec supers)]
      (if-let [i (first todo)]
        (if (seen i)
          (recur (rest todo) seen out)
          (recur (concat (rest todo) (:interfaces (class-info i))) (conj seen i) (conj out i)))
        out))))

(defn- iface-methods
  "[interface method] for every instance method the interfaces ifaces
  declare, private ones aside, grouped by name and descriptor."
  [ifaces]
  (group-by (fn [[_ m]] (str (:name m) (:desc m)))
            (for [i (distinct ifaces)
                  m (class-methods i)
                  :when (and (:virtual m) (zero? (bit-and (:flags m) 0x0002)))]
              [i m])))

(defn- specific-defaults
  "Of cands, the [interface method] pairs declaring one method, the
  maximally specific declarations with a body (JVMS 5.4.3.3): those no other
  declaring interface extends. One is the default interface dispatch picks,
  two or more conflict (the call throws IncompatibleClassChangeError), and
  none leaves the method abstract."
  [cands]
  (let [maximal (if (next cands)
                  (let [extends? (fn [j i] (some #{i} (rest (ancestry j))))]
                    (remove (fn [[i _]] (some (fn [[j _]] (and (not= i j) (extends? j i))) cands)) cands))
                  cands)]
    (filter (fn [[_ m]] (:code m)) maximal)))

(defn- resolve-static-field [cname fname]
  (or (some (fn [c] (when (some #(and (:static %) (= fname (:name %))) (:fields (class-info c))) c))
            (ancestry cname))
      (throw (ex-info "no such static field" {:class cname :field fname}))))

(defn- field-index [cname fname]
  (loop [c cname]
    (if-let [info (and c (class-info c))]
      (if (some #(and (not (:static %)) (= fname (:name %))) (:fields info))
        (get (:layout info) (str c "." fname))
        (recur (:super info)))
      (throw (ex-info "no such field" {:class cname :field fname})))))

(defn- resolve-method
  "The class or interface that implements cname.mname(desc) for a direct
  (static or special) call: the superclass chain first, then interfaces,
  where only a method with a body counts."
  [cname mname desc]
  (let [has? (fn [c body?] (some #(and (= mname (:name %)) (= desc (:desc %))
                                       (or (not body?) (:code %) (:native %)))
                                 (class-methods c)))
        supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (or (some #(when (has? % false) %) supers)
        ;; a conflict resolves arbitrarily (JVMS 5.4.3.3); dispatch still throws
        (ffirst (specific-defaults (get (iface-methods (filter #(cf/interface? (class-info %)) (ancestry cname)))
                                       (str mname desc))))
        (some #(when (has? % true) %) (ancestry cname))
        (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(defn- resolve-virtual
  "[declaring-class method] that an invokevirtual or invokeinterface of
  cname.mname(desc) resolves to (JVMS 5.4.3.3, 5.4.3.4), or nil: cname and
  its superclasses first, any method (for an interface, the interface itself
  and then Object); then the superinterfaces, where only a method neither
  static nor private counts."
  [cname mname desc]
  (let [decl-of (fn [c] (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (class-methods c)))
        supers (take-while some? (iterate #(:super (class-info %)) cname))]
    (or (some (fn [c] (when-let [m (decl-of c)] [c m])) supers)
        (some (fn [c] (when-let [m (decl-of c)]
                        (when (and (not (cf/static? m)) (zero? (bit-and (:flags m) 0x0002))) [c m])))
              (remove (set supers) (ancestry cname))))))

(defn- check-virtual
  "Throws resolve-method's \"no such method\" when nothing in cname's
  ancestry declares mname(desc), as a JVM's resolution of an invokevirtual
  or invokeinterface does when the call runs (JVMS 5.4.3.3, 5.4.3.4), before
  it looks at the receiver. An interface's ancestry ends in Object, so its
  calls resolve to Object's methods too. A signature-polymorphic method of
  MethodHandle or VarHandle (native, varargs) takes any descriptor."
  [cname mname desc]
  (let [polymorphic? (and (#{"java/lang/invoke/MethodHandle" "java/lang/invoke/VarHandle"} cname)
                          (some #(and (= mname (:name %)) (:native %) (pos? (bit-and (:flags %) 0x0080)))
                                (class-methods cname)))]
    (when-not (or (str/starts-with? cname "[")
                  (resolve-virtual cname mname desc)
                  polymorphic?)
      (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(defn- check-kind
  "Throws when the method a call resolves to doesn't match the instruction
  in static-ness, as a JVM's linking of the call does when it runs (JVMS
  5.4.3.3, 5.4.3.4, 6.5), with HotSpot's text: an invokevirtual or
  invokespecial of a static method, an invokeinterface of one, an
  invokestatic of an instance method. javac never emits these; a class
  compiled against an older version of a library can. op is :virtual,
  :interface, :special or :static; the method is found as check-virtual and
  resolve-method find it."
  [op cname mname desc]
  (when-not (or (str/starts-with? cname "[") (= mname "<init>") (= mname "<clinit>"))
    (let [decl-of (fn [c] (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (class-methods c)))
          [decl m] (if (#{:special :static} op)
                     (let [d (try (resolve-method cname mname desc) (catch Exception _ nil))] [d (and d (decl-of d))])
                     (resolve-virtual cname mname desc))
          static? (and m (cf/static? m))
          what (cond (and m (= op :static) (not static?)) "Expected static method"
                     (and static? (= op :interface)) "Expected instance not static method"
                     (and static? (#{:virtual :special} op)) "Expecting non-static method")]
      (when what
        (throw (ex-info "incompatible class change" {:class decl :method mname :desc desc :icce what}))))))

(declare parsed overridden?)

(defn- inline-candidate
  "The parsed class and method decl.mname(desc), for codegen to inline into a
  method of caller-cname, or nil. Only a method in the caller's own class or
  in the JDK qualifies: a compiled method is cached with its own class, and a
  copy of another user class's code would outlive a change to that class.
  The JDK can't change under a cache, which is keyed by it. A method the
  runtime overrides never qualifies."
  [caller-cname decl mname desc]
  (let [info (class-info decl)]
    ;; only a class already parsed in this run: reading one again from the
    ;; image costs a warm start more than the inlining saves; decided first,
    ;; so a restored class's methods aren't thawed for an inlining that
    ;; can't happen
    (when (or (= decl caller-cname) (and (= :jdk (:origin info)) (:parsed info)))
      (let [im (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (methods-of info)))]
        ;; the class map answers most of it without reading the class file again
        (when (and im (:code im) (<= (or (:code-len im) 0) cg/leaf-limit)
                   (not (overridden? decl im)))
          (when-let [p (if (= decl caller-cname) (parsed decl) (:parsed info))]
            {:cls p :method (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))}))))))

(defn- direct-target
  "The class whose cname.mname(desc) an invokevirtual or invokeinterface
  always reaches, or nil: the method it resolves to is private or final, or
  its class is final, so no override can be picked at run time."
  [cname mname desc]
  (when-not (str/starts-with? cname "[")
    ;; a method only an interface declares, without a body, doesn't resolve
    ;; here; dispatch handles it
    (when-let [decl (try (resolve-method cname mname desc) (catch Exception _ nil))]
     (let [m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (class-methods decl)))]
      (when (and m (not (cf/static? m))
                 (or (pos? (bit-and (:flags m) 0x0012))
                     (pos? (bit-and (:flags (class-info cname)) 0x0010))))
        decl)))))

(defn- package-private-decl
  "The class declaring the method cname.mname(desc) an invokevirtual resolves
  to, when that method is package-private, or nil. Selection for it has to
  follow JVMS 5.4.5's run-time package rule (runtime.ss jvirt-pp-ic), where
  any other resolved method is overridden by the nearest same-named one, as
  the vtable has it."
  [cname mname desc]
  (when-not (str/starts-with? cname "[")
    (when-let [decl (try (resolve-method cname mname desc) (catch Exception _ nil))]
      (let [m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (class-methods decl)))]
        (when (and m (not (cf/static? m)) (zero? (bit-and (:flags m) 0x0007))
                   (not (cf/interface? (class-info decl))))
          decl)))))

(def ^:private resolver
  {:ensure-loaded #(ensure-loaded! %)
   :package-private-decl package-private-decl
   :inline-candidate inline-candidate
   :direct-target direct-target
   :resolve-static-field resolve-static-field
   :field-index field-index
   :resolve-method resolve-method
   :check-virtual check-virtual
   :check-kind check-kind
   :lambda-class #(lambda-class %1 %2)})

;; --- loading -----------------------------------------------------------------------

(declare class-map linkage-error)

(defn- final-sealed?
  "A parsed class that's final and has a PermittedSubclasses attribute,
  HotSpot's ClassFormatError."
  [c]
  (and (pos? (bit-and (:flags c) 0x0010)) (some? (:permitted-subclasses (:reflection c)))))

(defn- normalize
  "Parse cname's classfile into the linker's class map."
  [cname]
  (let [user (timed :class-bytes #(user-class-bytes cname))
        ;; the JDK's bytes only for a class of the boot layer: the JVM's
        ;; loaders don't find one outside it (outside-boot-layer?)
        bytes (or user
                  (when-not (outside-boot-layer? cname) (timed :class-bytes #(jdk-class-bytes cname)))
                  ;; a loader of the program's own may define it, when the
                  ;; class naming it is that loader's (natives.ss); nil then.
                  ;; The time is the loader's own code running (reading its
                  ;; jars) and the define, restored from the cache or not
                  (when (boolean (timed :loader #(s/call "ask-resolving-loader" cname))) ::defined)
                  (throw (ex-info (str "class not found: " cname) {:class cname})))]
    (when-not (= bytes ::defined)
      ;; a classpath class is cached only through the library cache
      ;; (restore-user-class!), which checks it hasn't changed
      ;; bytes that aren't a class file are the JVM's ClassFormatError for
      ;; the code that needs the class (codegen, runtime.ss find-class): the
      ;; message ends with HotSpot's text, which find-class reads off
      (let [p (try (timed :cf-parse #(cf/parse bytes))
                   (catch Exception e
                     (let [{:keys [class-format named class-version]} (ex-data e)]
                       (cond class-format
                             (let [msg (if named (str class-format " " cname) class-format)]
                               (throw (ex-info (str "class format error in " cname ": " msg)
                                               {:class cname :class-format msg})))
                             ;; a version this release refuses names the class asked for
                             class-version
                             (throw (linkage-error cname "java/lang/UnsupportedClassVersionError" (class-version cname)))
                             :else (throw e)))))]
        ;; a class file found under a name that isn't its own (a file system
        ;; that ignores case, a renamed file) is HotSpot's ClassFileParser
        ;; refusal, not a second class
        (when (and user (not= (:name p) cname))
          (throw (linkage-error cname "java/lang/NoClassDefFoundError" (str cname " (wrong name: " (:name p) ")"))))
        ;; HotSpot's format check: an interface's superclass is Object
        (when (and (pos? (bit-and (:flags p) 0x0200)) (not= "java/lang/Object" (:super p)))
          (let [msg (str "Interfaces must have java.lang.Object as superclass in class file " cname)]
            (throw (ex-info (str "class format error in " cname ": " msg) {:class cname :class-format msg}))))
        ;; and a final class isn't sealed
        (when (final-sealed? p)
          (let [msg (str "PermittedSubclasses attribute in final class file " cname)]
            (throw (ex-info (str "class format error in " cname ": " msg) {:class cname :class-format msg}))))
        (class-map cname p (if user :user :jdk))))))

(defn- class-map
  "The linker's class map for cname from its parsed classfile c, of origin
  :jdk, :user (the classpath) or :defined (bytes a class loader handed over
  at run time, define-class!)."
  [cname c origin]
  (cond->
  {:name cname :super (:super c) :interfaces (:interfaces c) :parsed c :flags (:flags c)
   :origin origin
   :reflection (:reflection c)
   :fields (mapv (fn [f] (merge {:name (:name f) :desc (:desc f) :flags (:flags f)
                                 :static (cf/static? f) :constant (:constant f)}
                                (select-keys f [:signature :annotations :type-annotations])))
                 (:fields c))
   :methods (mapv (fn [m] (assoc m
                                 :native (cf/native? m)
                                 ;; kept in the startup cache, which drops
                                 ;; the bytecode: inlining checks it first
                                 :code-len (some-> (:code m) :code count)
                                 :virtual (not (or (cf/static? m) (str/starts-with? (:name m) "<")))))
                  (:methods c))}
    ;; a preview class file is never cached, nor anything depending on it
    ;; (user-deps): a restore skips the parse, so a run without
    ;; --enable-preview would take it
    (:preview c) (assoc :preview true)))

(defn- overridden?
  "Whether the runtime overrides cname's method m. An abstract method with an
  override (VarHandle.withInvokeExactBehavior, natives.ss) gets a vtable entry
  and a stub like a method with code."
  [cname m]
  (boolean (s/call "native-override" (str cname "." (:name m) (:desc m)))))

(defn- field-layout
  "[layout template] for info's instance fields after its superclass sup's:
  each own field's slot follows the inherited ones, its default value
  appended to the slot template."
  [info sup]
  (let [own (remove :static (:fields info))
        base (count (:template sup []))]
    [(into (or (:layout sup) {})
           (map-indexed (fn [i f] [(str (:name info) "." (:name f)) (+ base i)]) own))
     (into (or (:template sup) []) (map #(cg/default-value (:desc %)) own))]))

(defn- inherited-vtable
  "The superclass's entries for methods some class declares; the defaults it
  picked are picked again by link, since this class may implement a more
  specific interface."
  [sup]
  (into {} (remove (fn [[_ g]] (or (str/starts-with? g "icce:")
                                   (cf/interface? (class-info (subs g 0 (str/index-of g "."))))))
                   (vtable-of sup))))

(defn- own-vtable-methods
  "[private? key global] for each virtual method info declares with code, a
  native or a runtime override."
  [info]
  (for [m (:methods info)
        :when (and (:virtual m) (or (:code m) (:native m) (overridden? (:name info) m)))]
    [(pos? (bit-and (:flags m) 0x0002)) (str (:name m) (:desc m)) (str (:name info) "." (:name m) (:desc m))]))

(defn- interface-ancestors
  "The interfaces among info's ancestors, info itself left out."
  [info]
  (filter #(cf/interface? (class-info %)) (rest (ancestry-of-unlinked info))))

(defn- picked-defaults
  "[key defaults] for each interface method key vtable lacks that has
  maximally specific defaults."
  [info vtable]
  (for [[k cands] (iface-methods (interface-ancestors info))
        :when (not (contains? vtable k))
        :let [ds (specific-defaults cands)]
        :when (seq ds)]
    [k ds]))

(defn- default-entries
  "The vtable entries for picked defaults: two or more maximally specific
  defaults conflict, and the entry is a stub that throws, defined by cname's
  registration."
  [cname picked]
  (for [[k ds] picked]
    [k (if (next ds) (str "icce:" cname "." k) (str (ffirst ds) "." k))]))

(defn- default-conflicts
  "key -> the interfaces whose defaults conflict, for registration's stubs."
  [picked]
  (into {} (for [[k ds] picked :when (next ds)] [k (mapv first ds)])))

(defn- interface-privates
  "[key global] for the private methods of info's interface ancestors: they
  aren't inherited and pick no default, but javac calls them with
  invokeinterface on the receiver, which dispatches through the vtable."
  [info]
  (for [i (interface-ancestors info)
        m (class-methods i)
        :when (and (:virtual m) (:code m) (pos? (bit-and (:flags m) 0x0002)))]
    [(str (:name m) (:desc m)) (str i "." (:name m) (:desc m))]))

(defn- add-absent
  "vtable with each [key global] of entries added under a key it lacks, the
  first entry for a key winning."
  [vtable entries]
  (reduce (fn [vt [k g]] (if (contains? vt k) vt (assoc vt k g))) vtable entries))

(defn- link
  "Compute field layout, slot template and vtable from the (already linked)
  superclass, then fill vtable gaps with interface default methods."
  [info]
  (let [sup (when (:super info) (class-info (:super info)))
        [layout template] (field-layout info sup)
        sup-vt (inherited-vtable sup)
        own-methods (own-vtable-methods info)
        ;; a private method overrides nothing: it neither replaces an
        ;; inherited entry nor keeps a default from being picked (JVMS
        ;; 5.4.6), so it goes in last, under a key nothing else has
        own-privates (for [[p? k g] own-methods :when p?] [k g])
        vtable (into sup-vt (for [[p? k g] own-methods :when (not p?)] [k g]))
        picked (picked-defaults info vtable)
        defaults (default-entries (:name info) picked)
        conflicts (default-conflicts picked)
        vtable (into vtable defaults)
        vtable (add-absent vtable (concat own-privates (interface-privates info)))]
    (assoc info :layout layout :template template :vtable vtable :conflicts conflicts)))

(defn- ancestry-of-unlinked
  "ancestry for a class that is not in the registry yet."
  [info]
  (cons (:name info)
        (distinct (mapcat ancestry (remove nil? (cons (:super info) (:interfaces info)))))))

(defn- constant-text [{:keys [tag value]}]
  (case tag
    (:int :long :float :double) (cg/num-lit value)
    :string (cg/jstring-lit-form value)))

;; A reflection attribute as a Scheme literal: a Signature string, raw
;; annotation bytes, or #f when the classfile has none.
(defn- refl-lit [v]
  (cond (nil? v) "#f"
        (string? v) (cg/str-lit v)
        :else (str "#vu8" (apply cg/sx (map str v)))))

(defn- vtable-delta-text
  "The vtable as changes to the superclass's: printing every inherited entry
  made it a third of the startup cache's source."
  [super vtable]
  (str "'" (apply cg/sx (let [sup-vt (when super (vtable-of (class-info super)))]
                          (concat
                           (for [[k g] (sort vtable) :when (not= g (get sup-vt k))]
                             (str "(" (cg/str-lit k) " . " (cg/sym g) ")"))
                           (for [k (sort (keys sup-vt)) :when (not (contains? vtable k))]
                             (str "(" (cg/str-lit k) " . #f)")))))))

(defn- method-meta-text
  "A method's reflection meta: (name desc flags signature annotations
  param-annotations annotation-default type-annotations exceptions
  parameters), Exceptions as class names, MethodParameters as (name flags)
  with name #f when absent."
  [m]
  (cg/sx (cg/str-lit (:name m)) (cg/str-lit (:desc m)) (str (:flags m))
         (refl-lit (:signature m)) (refl-lit (:annotations m))
         (refl-lit (:param-annotations m)) (refl-lit (:annotation-default m))
         (refl-lit (:type-annotations m))
         (if-let [exs (:exceptions m)] (apply cg/sx (map cg/str-lit exs)) "#f")
         (if-let [ps (:parameters m)]
           (apply cg/sx (for [[n fl] ps] (cg/sx (refl-lit n) (str fl))))
           "#f")))

(defn- field-meta-text
  "A field's reflection meta: (name desc flags signature annotations
  type-annotations)."
  [f]
  (cg/sx (cg/str-lit (:name f)) (cg/str-lit (:desc f)) (str (or (:flags f) 0))
         (refl-lit (:signature f)) (refl-lit (:annotations f))
         (refl-lit (:type-annotations f))))

(defn- record-meta-text
  "Record: (name desc signature annotations type-annotations) per component."
  [record]
  (apply cg/sx (for [rc record]
                 (cg/sx (cg/str-lit (:name rc)) (cg/str-lit (:desc rc))
                        (refl-lit (:signature rc)) (refl-lit (:annotations rc))
                        (refl-lit (:type-annotations rc))))))

(defn- class-meta-text
  "The class's own reflection meta: (signature annotations inner
  enclosing-method record-components type-annotations nest-host
  nest-members permitted-subclasses). inner is InnerClasses' entry for this
  class, (outer simple-name flags); enclosing-method is (class name desc);
  NestHost is the host's name and NestMembers their names;
  PermittedSubclasses their names, () for a sealed class listing none."
  [{:keys [inner enclosing-method record nest-members permitted-subclasses] :as reflection}]
  (cg/sx (refl-lit (:signature reflection)) (refl-lit (:annotations reflection))
         (if inner
           (cg/sx (refl-lit (:outer inner)) (refl-lit (:simple-name inner)) (str (:flags inner)))
           "#f")
         (if enclosing-method
           (cg/sx (refl-lit (:class enclosing-method)) (refl-lit (:name enclosing-method))
                  (refl-lit (:desc enclosing-method)))
           "#f")
         (if record (record-meta-text record) "#f")
         (refl-lit (:type-annotations reflection))
         (refl-lit (:nest-host reflection))
         (if nest-members (apply cg/sx (map cg/str-lit nest-members)) "#f")
         (if permitted-subclasses (apply cg/sx (map cg/str-lit permitted-subclasses)) "#f")))

(defn- register-form
  "register-class! for the class: name, super, interfaces, template, vtable
  delta, layout, flags, then the declared members in classfile order for
  reflection (methods, fields, then the class's own meta)."
  [{:keys [name super interfaces template layout fields flags reflection]} vtable methods]
  (cg/sx "register-class!" (cg/str-lit name) (if super (cg/str-lit super) "#f")
         (str "'" (apply cg/sx (map cg/str-lit interfaces)))
         (apply cg/sx "vector" template)
         (vtable-delta-text super vtable)
         (str "'" (apply cg/sx (for [[k i] (sort layout)] (str "(" (cg/str-lit k) " . " i ")"))))
         (str flags)
         (str "'" (cg/sx (apply cg/sx (map method-meta-text methods))
                         (apply cg/sx (map field-meta-text fields))
                         (class-meta-text reflection)))))

(defn- conflict-stubs
  "A conflicting default's stub, with the JVM's message: the interfaces in
  declaration order, dotted, without descriptors."
  [name conflicts]
  (for [[k is] (sort conflicts)
        :let [mname (subs k 0 (str/index-of k "("))]]
    (cg/sx "define-global!" (str "'" (cg/sym (str "icce:" name "." k)))
           (cg/sx "icce-stub" (cg/str-lit (str "Conflicting default methods: "
                                               (str/join " " (map #(str (str/replace % "/" ".") "." mname) is))))))))

(defn- static-globals [name fields]
  (for [f fields :when (:static f)]
    (cg/sx "define-global!" (str "'" (cg/static-global name (:name f)))
           (if-let [k (:constant f)] (constant-text k) (cg/default-value (:desc f))))))

(defn- define-methods-form
  "Every method's definition from one form: a native's from natives.ss, any
  other's from method-stub, which returns a runtime override when natives.ss
  has one, else a closure that JIT-compiles the method on first call."
  [name methods]
  (cg/sx "define-methods!" (cg/str-lit name)
         (str "'" (apply cg/sx (for [m methods
                                     :when (or (:native m) (:code m) (overridden? name m))]
                                 (cg/sx (cg/str-lit (:name m)) (cg/str-lit (:desc m))
                                        (cg/method-global name (:name m) (:desc m))
                                        (if (:native m) "#t" "#f")))))))

(defn- registration-text [{:keys [name fields conflicts origin] :as info}]
  (let [vtable (vtable-of info) methods (methods-of info)]
    (str/join
     "\n"
     (concat
      ;; a classpath class stays in the unnamed module whatever its package
      ;; (runtime.ss user-class!); a registration the library cache keeps
      ;; carries this too
      (when (= origin :user) [(cg/sx "user-class!" (cg/str-lit name))])
      [(register-form info vtable methods)]
      (conflict-stubs name conflicts)
      (static-globals name fields)
      [(define-methods-form name methods)]))))

(defn- class-origin
  "The :origin of n's class table entry (:jdk, :user, :defined, whatever it
  holds), :jdk for an entry still packed, nil when the table has none."
  [n]
  (let [e (get-in @state [:classes n])] (if (packed? e) :jdk (:origin e))))

(defn- dep-value
  "What user-deps records for the class n: its fingerprint, :present (a
  synthetic class), :layer (a JDK class outside java.base), nil for a JDK
  class that needs nothing, or ::unchecked for one that can't be checked
  later."
  [n defined?]
  (case (if (:preview (get-in @state [:classes n])) :preview (class-origin n))
    ;; a class outside java.base is :layer, there only when the boot layer
    ;; holds its module (--limit-modules may leave it out of a later run,
    ;; outside-boot-layer?)
    :jdk (cond (str/starts-with? n "champaca/") :present
               (not= "java.base" (layer-module n)) :layer
               :else nil)
    :user (or (:fp (class-source n)) ::unchecked)
    :defined (or (when defined? (get-in @state [:classes n :src-fp])) ::unchecked)
    ::unchecked))

(defn- user-deps
  "The dependencies of a library cache entry whose link or compile read the
  classes in names: each classpath class among them and among everything
  they inherit from (a class's layout and vtable come from its ancestors),
  with its fingerprint (class-source). JDK classes need none, since the
  cache key covers the JDK, but a synthetic one (champaca/, a lambda's)
  has no bytes to make it again from, so it is :present, there when the
  entry is used. nil when one can't be checked later (one not in the class
  table, or one a loader defined, unless defined? says the entry is a
  loader-defined class's own, whose dependencies on others are checked by
  their fingerprints when its methods are first called: deps-still-hold!,
  or a preview class file, never cached itself, so that a run without
  --enable-preview reaches it and refuses it), which keeps the entry out of
  the cache."
  [names & [defined?]]
  (when (every? class-origin names)
    (reduce (fn [acc n]
              (let [v (dep-value n defined?)]
                (cond (= v ::unchecked) (reduced nil)
                      (nil? v) acc
                      :else (assoc acc n v))))
            {} (distinct (mapcat ancestry names)))))

(defn- uncheckable-dep
  "The first of names, or of their ancestry, that user-deps can't record a
  fingerprint for (why an entry isn't cached), or nil."
  [names defined?]
  (let [e (fn [n] (get-in @state [:classes n]))]
    (or (first (remove e names))
        (first (filter (fn [n] (let [x (e n)]
                                 (and (not (packed? x))
                                      (case (:origin x)
                                        :jdk false
                                        :user (not (:fp (class-source n)))
                                        :defined (not (and defined? (:src-fp x)))
                                        true))))
                       (distinct (mapcat ancestry names)))))))

(defn- library-cache-keys
  "What a classpath class's entry carries for the library cache once
  linked, its link having read the classes in reads: :src-fp, its own
  fingerprint, :src-jar, the jar whose library file holds it, and :deps
  (user-deps). nil when it can't be cached."
  [cname reads]
  (let [src (class-source cname)]
    (when-let [fp (:fp src)]
      (when-let [deps (user-deps (conj reads cname))]
        {:src-fp fp :src-jar (:jar src) :deps (dissoc deps cname)}))))

(defn- thaw-library-entry
  "A library cache entry read back, or nil. An entry that doesn't read back
  goes alone, unlike state.img's (parse-entry): the class compiles, and the
  next save rewrites it."
  [cname packed]
  (try (thaw-entry packed)
       (catch Exception e
         (binding [*out* *err*]
           (println (str "champaca: dropping the library cache's unreadable entry for " cname ":") (ex-message e)))
         nil)))

(defn- entry-deps-hold?
  "Whether a cached class's sources are as they were: its own fingerprint,
  and each dependency present, in the boot layer, or the classpath's copy
  unchanged and not some other class of that name this run already has (a
  loader's, the JDK's)."
  [src info]
  (and (= (:fp src) (:src-fp info))
       (every? (fn [[d fp]]
                 (let [e (get-in @state [:classes d])]
                   (case fp
                     :present (some? e)
                     :layer (not (outside-boot-layer? d))
                     (and (= fp (:fp (class-source d)))
                          (or (nil? e) (= :user (:origin e)))))))
               (:deps info))))

(defn- register-restored!
  "Register a class restored from the library cache. A directory's class
  keeps the bytes it was restored as (held by class-source) for the run: a
  method it lacks (parsed) and its stack frames (frame-class) read them,
  since the file may have changed or gone by then."
  [cname src info]
  (note-class-entry! cname (:entry src))
  (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
    (ensure-loaded! dep))
  (swap! state update :restored (fnil inc 0))
  (when-not (get-in @state [:classes cname])
    (swap! state assoc-in [:classes cname] info)
    (or (load-unit! (:cached-class info))
        (let [text (registration-text info)]
          (record! :user [cname] {:text text})
          (scheme! text :interpret)))))

(defn- drop-stale-entry!
  "A stale library cache entry dropped, noted for the save's merge."
  [cname src packed]
  (swap! state #(-> %
                    (update-in [:user-cache (:jar src)] dissoc cname)
                    (assoc-in [:library-dropped (:jar src) cname] packed)
                    (update :library-dirty (fnil conj #{}) (:jar src)))))

(defn- restore-user-class!
  "Register classpath class cname from the library cache, when an earlier
  run cached it and neither it nor any class it depends on has changed
  since: true then. Else nil, the stale entry is dropped, and the class is
  parsed and compiled as usual, which caches it again at the next save."
  [cname]
  (when-let [src (class-source cname)]
    (when-let [packed (when (:fp src) (get (library-cache (:jar src)) cname))]
      (let [info (thaw-library-entry cname packed)]
        (if (and info (entry-deps-hold? src info))
          (do (register-restored! cname src info)
              true)
          (do (drop-stale-entry! cname src packed)
              nil))))))

;; The classes whose superclass and interfaces are being loaded, on this
;; thread: one among its own supertypes is the JVM's ClassCircularityError,
;; where loading it again recursed until the heap ran out
(def ^:private ^:dynamic *loading-supertypes* #{})

(defn- linkage-error
  "The ex-info for a class the JVM refuses to define, error the JVM class's
  slashed name: runtime.ss find-class, define-class! and codegen read it."
  [cname error msg]
  (ex-info (str "linkage error in " cname ": " error "|" msg) {:class cname :linkage error :message msg}))

(defn- dotted
  "A class's name as Java prints it; a hidden class keeps the slash before
  its /0x<n> suffix, as Class.getName does."
  [n]
  (let [[_ base sfx] (re-matches #"(.*?)(/0x[0-9a-f]+)?" n)]
    (str (str/replace base "/" ".") sfx)))

(defn- check-sealed!
  "A sealed supertype must permit the class being defined: the same module,
  the same package unless the class is public, and listed in its
  PermittedSubclasses (JVMS 5.3.5), with HotSpot's texts (runtime.ss
  sealed-refusal answers one, or #f). A JDK class isn't checked: the image's
  classes were checked when it was built."
  [info sup]
  (when-not (= :jdk (:origin info))
    (when-let [msg (s/call "sealed-refusal" (:name info) (pos? (bit-and (:flags info) 0x0001)) (= :defined (:origin info)) sup)]
      (throw (linkage-error (:name info) "java/lang/IncompatibleClassChangeError" msg)))))

(defn- sealed-refused?
  "Whether a sealed supertype of info refuses it (check-sealed!), for a class
  restored from the library cache: the bytes are the same, but the loaders
  they're defined in may not be."
  [info]
  (try (doseq [s (remove nil? (cons (:super info) (:interfaces info)))] (check-sealed! info s))
       false
       (catch Exception e
         (if (:linkage (ex-data e)) true (throw e)))))

(defn- check-superclass!
  "A class's superclass may be neither an interface nor final (an
  interface's is Object, unchecked here)."
  [info sup sup-info]
  (let [n (:name info)]
    (when (and sup-info (not (cf/interface? info)))
      (when (cf/interface? sup-info)
        (throw (linkage-error n "java/lang/IncompatibleClassChangeError" (str "class " (dotted n) " has interface " (dotted sup) " as super class"))))
      (when (pos? (bit-and (:flags sup-info) 0x0010))
        (throw (linkage-error n "java/lang/IncompatibleClassChangeError" (str "class " (dotted n) " cannot inherit from final class " (dotted sup))))))))

(defn- check-implemented! [info]
  (let [n (:name info)]
    (doseq [i (:interfaces info)]
      (when-not (cf/interface? (class-info i))
        (throw (linkage-error n "java/lang/IncompatibleClassChangeError"
                              (str "class " (dotted n) " can not implement " (dotted i) ", because it is not an interface ("
                                   (s/call "class-place-text" i) ")")))))))

(defn- final-override-error
  "The error for the first instance method of info overriding a final one of
  a superclass it can see (a package-private one only from the same
  package), or nil."
  [info sup]
  (let [n (:name info)
        pkg package-dir]   ; a hidden class's /0x<n> suffix isn't its package
    (some (fn [m]
            (when (and (not (cf/static? m)) (not= "<init>" (:name m)) (zero? (bit-and (:flags m) 0x0002)))
              (some (fn [c]
                      (let [sm (some #(when (and (= (:name m) (:name %)) (= (:desc m) (:desc %))) %) (class-methods c))]
                        (when (and sm (pos? (bit-and (:flags sm) 0x0010)) (not (cf/static? sm))
                                   (zero? (bit-and (:flags sm) 0x0002))
                                   (or (pos? (bit-and (:flags sm) 0x0005)) (= (pkg c) (pkg n))))
                          (linkage-error n "java/lang/IncompatibleClassChangeError"
                                         (str "class " (dotted n) " overrides final method " (dotted c) "." (:name m) (:desc m))))))
                    (take-while some? (iterate #(:super (class-info %)) sup)))))
          (:methods info))))

(defn- check-definition!
  "HotSpot's checks of a class being defined, once its superclass and
  interfaces are loaded (JVMS 5.3.5, 5.4.5), with its texts: a final
  superclass, an interface as the superclass, a class among the interfaces,
  a sealed supertype that doesn't permit it, an instance method overriding a
  final one it can see. Throws linkage-error."
  [info]
  (let [sup (:super info) sup-info (when sup (class-info sup))]
    (check-superclass! info sup sup-info)
    (check-implemented! info)
    ;; after the interfaces are checked for being interfaces, as HotSpot
    ;; does that while parsing
    (doseq [s (remove nil? (cons sup (:interfaces info)))] (check-sealed! info s))
    (when-let [e (when sup (final-override-error info sup))]
      (throw e))))

(defn- load-supertypes!
  "ensure-loaded! of info's superclass and interfaces, then check-definition!;
  one already being loaded on this thread is ClassCircularityError."
  [info]
  (let [n (:name info)]
    (when (contains? *loading-supertypes* n)
      (throw (linkage-error n "java/lang/ClassCircularityError" (dotted n))))
    (binding [*loading-supertypes* (conj *loading-supertypes* n)]
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep)))
    (check-definition! info)))

(defn- register-lazy-class!
  "A class registered by an earlier run: its class table entry came from the
  cache, and its registration is one unit of a cached generation. A
  dependency that isn't there leaves cname lazy, not half-registered."
  [cname]
  (let [info (thaw! cname)]
    (swap! state update :lazy disj cname)
    (try
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (catch Exception e
        (swap! state update :lazy conj cname)
        (throw e)))
    (or (load-unit! (:cached-class info))
        (let [text (registration-text info)]
          (record! (cache-origin info) [cname] {:text text})
          (scheme! text :interpret)))))

(defn- load-new-class!
  "A class not in the table: restored from the library cache, else parsed,
  its supertypes loaded, linked and registered. A classpath class's link and
  registration record what they read."
  [cname]
  (when-not (restore-user-class! cname)
    (when-let [info (timed :parse #(normalize cname))]
      (load-supertypes! info)
      (when-not (get-in @state [:classes cname])
        (let [reads (when (= :user (:origin info)) (volatile! #{}))
              linked (binding [*reads* reads] (timed :link #(link info)))]
          (swap! state assoc-in [:classes cname] linked)
          (let [text (binding [*reads* reads] (timed :register-text #(registration-text linked)))
                linked (if-let [ks (when reads (library-cache-keys cname @reads))]
                         (get-in (swap! state update-in [:classes cname] merge ks) [:classes cname])
                         linked)]
            (record! (cache-origin linked) [cname] {:text text})
            (timed :register-eval #(scheme! text :interpret))))))))

(defn ensure-loaded! [cname]
  (refuse-outside-layer! cname)
  (cond
    (contains? (:lazy @state) cname) (register-lazy-class! cname)
    (not (get-in @state [:classes cname])) (load-new-class! cname))
  cname)

(defn lambda-class
  "The synthetic class implementing ifaces for lambdas whose abstract
  method keys (\"name(desc)\", the SAM plus any bridges) are sam-keys.
  Slot 0 holds the closure; every SAM key maps to champaca/Lambda.invoke,
  which calls it. Its vtable takes the interfaces' default methods, so its
  name carries the fingerprints of every classpath interface among them
  and their superinterfaces (user-deps), a loader-defined interface's too: an
  interface that changes between runs gets a class of its own instead of an
  earlier run's cached one. One whose interfaces can't be checked isn't
  cached."
  [ifaces sam-keys]
  (run! ensure-loaded! ifaces)
  (let [deps (user-deps ifaces true)
        n (str "champaca/Lambda$" (str/replace (first ifaces) "/" "$") "$"
               (Math/abs (hash (if (empty? deps) [ifaces sam-keys] [ifaces sam-keys deps]))))]
    (if (get-in @state [:classes n])
      (ensure-loaded! n) ; an earlier run's, maybe not registered in this one yet
      (let [info (cond-> {:name n :super "java/lang/Object" :interfaces (vec ifaces) :flags 0x1011 :origin :jdk
                          :fields [{:name "fn" :desc "Ljava/lang/Object;" :flags 0x12 :static false}] :methods []}
                   (nil? deps) (assoc :no-cache true))
            linked (reduce #(assoc-in %1 [:vtable %2] "champaca/Lambda.invoke") (link info) sam-keys)]
        (swap! state assoc-in [:classes n] linked)
        (let [text (registration-text linked)]
          ;; a later run trusts what state.img holds, so only a name that
          ;; pins the interfaces goes there (strip-for-cache skips :no-cache)
          (record! (when deps :jdk) [n] {:text text})
          (scheme! text :interpret))))
    ;; the code being compiled names n (user-deps)
    (when-let [r *reads*] (vswap! r conj n))
    n))

(defn- rename-class
  "The parsed class c under a new name: its own, every constant-pool class
  entry and member reference naming it, and the exception-table types
  parse-code resolved to names. A hidden class keeps the name its bytes carry
  inside, and two hidden classes from the same bytes would otherwise share the
  methods and statics compiled under it. Descriptors keep the old name, and so
  does an array type naming the class ([Lold;), which the JVM resolves to the
  original class too (DefineCheck's hidden self-references row)."
  [c new]
  (let [old (:name c)
        fix-name (fn [s] (if (= s old) new s))
        fix (fn [e] (cond (= :class (:tag e)) (update e :name fix-name)
                          (contains? e :class) (update e :class fix-name)
                          :else e))]
    (-> c
        (assoc :name new)
        (update :cp #(into {} (map (fn [[i e]] [i (fix e)])) %))
        (update :methods (fn [ms] (mapv (fn [m] (cond-> m (:code m) (update-in [:code :handlers] (fn [hs] (mapv #(update % :type fix-name) hs)))))
                                        ms))))))

(def ^:private hidden-counter (atom 0x1000))

(declare defined-jar restore-defined-class! dep-matches?)

(defn- parse-defined
  "The class file bs parsed, or {:bad text} when it isn't one, or {:version
  text} for an unsupported version. A wrong magic number's text ends with
  the name the caller gave, as HotSpot's, or <Unknown>."
  [bs given]
  (try (cf/parse bs)
       (catch Exception e
         (if-let [f (:class-version (ex-data e))]
           {:version (f (or given "<Unknown>"))}
           {:bad (let [m (or (ex-message e) (str (class e)))]
                   (if (:named (ex-data e)) (str m " " (or given "<Unknown>")) m))}))))

(defn- definition-refusal
  "define-class!'s answer when the parsed class c can't be defined, else nil.
  HotSpot checks the name as it reads it, before the interface and sealed
  checks."
  [c given hidden?]
  (cond
    (:bad c) (str "bad " (:bad c))
    (:version c) (str "link java/lang/UnsupportedClassVersionError|" (:version c))
    (and given (not= given (:name c))) (str "wrong " (:name c))
    (and (pos? (bit-and (:flags c) 0x0200)) (not= "java/lang/Object" (:super c)))
    (str "bad Interfaces must have java.lang.Object as superclass in class file " (or given (:name c)))
    (final-sealed? c) (str "bad PermittedSubclasses attribute in final class file " (or given (:name c)))
    (and (not hidden?) (get-in @state [:classes (:name c)])) (str "dup " (:name c))))

(defn- defined-deps!
  "A defined class n's library cache dependencies from what its link and
  registration read: without checkable ones it isn't cached, and without a
  fingerprint nothing depending on it is. Answers its class map."
  [n reads fp]
  (let [deps (when fp (user-deps (conj @reads n) true))]
    (when fp
      (when-not deps (swap! state update :defined-uncacheable (fnil inc 0)))
      (when (and (not deps) (trace?))
        (binding [*out* *err*] (println "define" n "not cached: it depends on" (uncheckable-dep (conj @reads n) true)))))
    (get-in (swap! state update-in [:classes n]
                   #(if deps (assoc % :deps (dissoc deps n)) (dissoc % :src-fp :src-jar)))
            [:classes n])))

(defn- define-parsed-class!
  "Link and register the parsed class c (renamed first when hidden), as
  ensure-loaded! does for a classpath class: \"ok <name>\", or HotSpot's
  definition checks' error as \"link <class>|<text>\"."
  [c hidden? bs fp dj]
  (try
    (let [c (if hidden? (rename-class c (str (:name c) "/0x" (Long/toHexString (swap! hidden-counter inc)))) c)
          n (:name c)
          info (class-map n c :defined)]
      ;; bytes class-source held for a classpath class of this name won't be parsed
      (swap! state update :dir-bytes dissoc n)
      ;; HotSpot's checks of the definition (load-supertypes!)
      (load-supertypes! info)
      ;; the link and the registration record what they read, which the
      ;; entry depends on; its own fingerprint goes in first: user-deps reads
      ;; it for the class itself, and for a class depending on it
      (let [reads (when fp (volatile! #{}))
            linked (cond-> (binding [*reads* reads] (link info)) fp (assoc :src-fp fp :src-jar dj))]
        ;; the defined bytes are kept for NPE messages and frames, once the class is in
        (swap! state #(-> % (assoc-in [:classes n] linked) (assoc-in [:defined-bytes n] bs)))
        (let [text (binding [*reads* reads] (registration-text linked))
              linked (defined-deps! n reads fp)]
          (record! (cache-origin linked) [n] {:text text})
          (scheme! text :interpret)))
      (str "ok " n))
    (catch Exception e
      (if-let [err (:linkage (ex-data e))]
        (str "link " err "|" (:message (ex-data e)))
        (throw e)))))

(defn- define-class!
  "ClassLoader.defineClass, Lookup.defineClass and defineHiddenClass, Proxy
  and Unsafe.defineClass0 (natives.ss define-class!): parse, link and
  register a class from the bytes a loader handed over, which cross from
  Scheme as a Latin-1 string since a byte array doesn't. given is the name
  the caller expects, slashed, or nil. Answers \"ok <name>\", \"bad <message>\"
  (not a class file), \"wrong <name in the bytes>\" or \"dup <name>\" (one
  class table here, so a second definition of a name is a duplicate whatever
  its loader), and the Scheme side throws the JVM's error for each. A hidden
  class is named <name>/0x<n>, as HotSpot names one, and renamed inside.
  With cacheable? (a named, non-hidden class from a loader of the program's
  own), the class is kept in the library cache like a jar's, keyed by its
  bytes' fingerprint under the class path's file (defined-jar), and an
  earlier run's entry for the same bytes is restored instead of parsing
  (restore-defined-class!)."
  [given text hidden? cacheable?]
  (let [bs (.getBytes ^String text "ISO-8859-1")
        fp (when (and cacheable? given) (timed :fingerprint #(bytes-fp bs)))
        dj (when fp (defined-jar))]
    (or (when (and fp (not (get-in @state [:classes given])))
          (restore-defined-class! given bs fp dj))
        (let [c (parse-defined bs given)]
          (or (definition-refusal c given hidden?)
              (define-parsed-class! c hidden? bs fp dj))))))

(defn- defined-jar
  "The library cache's key for the classes a program's own loaders define:
  one file per class path, the bytes having no path of their own. A
  source-file run has its own (\"source:\"), since its defined classes
  compile with line numbers (lines?) and other runs' don't."
  []
  (or (:defined-jar @state)
      (let [j (str (if (:source-mode @state) "source:" "defined:") (str/join ":" (map jar-path (:classpath @state))))]
        (swap! state assoc :defined-jar j)
        j)))

(defn- restore-defined-class!
  "Register the class a loader is defining, n from bytes bs with fingerprint
  fp, from the library cache's file dj: when an earlier run cached a class of
  that name from the same bytes and the classes it inherits from are what
  they were, \"ok <name>\"; else nil, the stale entry dropped so the save
  rewrites it, and the class parses as usual. What its methods read is
  checked when one is first called (deps-still-hold!). A class a sealed
  supertype refuses where it's defined now isn't restored, and its entry
  stays: the definition then parses and fails as the JVM's."
  [n bs fp dj]
  (if-let [packed (get (library-cache dj) n)]
    (let [refused (volatile! false)
          info (try (timed :thaw #(thaw-entry packed))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "champaca: dropping the library cache's unreadable entry for " n ":") (ex-message e)))
                      nil))]
      (if (and info
               (or (= fp (:src-fp info))
                   (do (swap! state update :defined-changed (fnil inc 0))
                       (when (trace?) (binding [*out* *err*] (println "define" n "changed since its entry")))
                       false))
               (do (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))] (ensure-loaded! dep)) true)
               (not (and (sealed-refused? info) (vreset! refused true)))
               (every? (fn [d] (or (dep-matches? d (get (:deps info) d))
                                   (do (swap! state update :defined-changed (fnil inc 0))
                                       (when (trace?) (binding [*out* *err*] (println "define" n "not restored:" d "changed")))
                                       false)))
                       (filter (:deps info) (rest (ancestry-of-unlinked info)))))
        (do (swap! state #(-> %
                              (assoc-in [:classes n] info)
                              (assoc-in [:defined-bytes n] bs)
                              (update :dir-bytes dissoc n)
                              (update :restored (fnil inc 0))))
            (or (load-unit! (:cached-class info))
                (let [text (registration-text info)]
                  (record! :user [n] {:text text})
                  (scheme! text :interpret)))
            (str "ok " n))
        (when-not @refused
          (swap! state #(-> %
                            (update-in [:user-cache dj] dissoc n)
                            (assoc-in [:library-dropped dj n] packed)
                            (update :library-dirty (fnil conj #{}) dj)))
          nil)))
    (do (swap! state update :defined-new (fnil inc 0)) nil)))

;; --- the JIT -----------------------------------------------------------------------

(defn- parsed
  "The parsed classfile for cname. A class restored from the boot cache
  carries no bytecode, so it is re-read on the first method compile; a
  classpath class restored from the library cache must read back as the
  bytes its entry was made from, or its new methods wouldn't match the
  ones it has. A directory's class does, from the bytes it kept; a jar
  changed in place under the run is an error."
  [cname]
  (or (:parsed (thaw! cname))
      (let [bs (class-bytes cname)
            fp (get-in @state [:classes cname :src-fp])
            _ (when (and fp (not bs))
                (throw (ex-info (str cname " is no longer on the classpath") {:class cname})))
            _ (when (and fp (not= fp (bytes-fp bs)))
                (throw (ex-info (str cname " changed on the classpath while this program ran") {:class cname})))
            p (timed :parse #(cf/parse bs))]
        (swap! state assoc-in [:classes cname :parsed] p)
        p)))

(defn- lines?
  "Whether cname's code compiles with Chez's inspector information, which is
  what gives its frames line numbers (how-it-works.md). By default a class
  from a classpath directory, a developer's own build, does; a jar's or a
  loader-defined class names its file without a line, as a JDK frame does,
  because the information costs about as much memory as the code itself (a
  warm Spring Boot: 100 of 460 MB live). A source-file run's defined
  classes do too: javac's source launcher defines the program's own classes
  (their cache file is apart, defined-jar). CHAMPACA_LINES=all gives every
  class but the JDK's lines, CHAMPACA_LINES=off none. Every compile path
  asks, cached or not, so a frame's line doesn't depend on the cache."
  [cname info]
  (case (some-> (env "CHAMPACA_LINES") str/lower-case)
    "all" (contains? #{:user :defined} (:origin info))
    "off" false
    (case (:origin info)
      :user (boolean (when-let [src (class-source cname)] (not (jar-entry? (:entry src)))))
      :defined (boolean (:source-mode @state))
      false)))

(defn- compile-method! [cname mname desc]
  (let [p (parsed cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods p)))
        info (assoc (class-info cname) :parsed p)]
    (swap! state update :jitted inc)
    (when (= :user (:origin info)) (swap! state update :jitted-user (fnil inc 0)))
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (let [reads (when (:src-fp info) (volatile! #{}))
          text (binding [*reads* reads] (timed :codegen #(cg/compile-method (:parsed info) m resolver)))
          ;; a library cache entry's dependencies grow with each method
          ;; compiled; a method reading one that can't be checked isn't kept
          origin (if reads
                   (if-let [deps (user-deps (conj @reads cname) (= :defined (:origin info)))]
                     ;; a fingerprint already recorded stays: were a dependency
                     ;; to differ from it, the next run's check must fail
                     (do (swap! state update-in [:classes cname :deps] #(merge-with (fn [old _] old) % (dissoc deps cname)))
                         :user)
                     (do (when (trace?)
                           (binding [*out* *err*]
                             (println "jit" (str cname "." mname desc) "not cached: it reads" (uncheckable-dep (conj @reads cname) (= :defined (:origin info))))))
                         nil))
                   (cache-origin info))
          unit [cname (str mname desc)]
          ;; an instruction naming a class that isn't there throws when it
          ;; runs (codegen): such a method is never cached, so a cache can't
          ;; carry a throw for a class a later run has, and CHAMPACA_BT says so
          missing (seq (map second (re-seq (re-pattern "\\(jthrow-missing \"([^\"]+)\"") text)))
          ;; and for a class whose file isn't a class file (ClassFormatError)
          bad-format (seq (map second (re-seq (re-pattern "\\(jthrow-class-format \"([^\"]+)\"") text)))
          ;; the same for a method its class doesn't have (NoSuchMethodError)
          no-method (seq (map (fn [[_ c n d]] (str c "." n d))
                              (re-seq (re-pattern "\\(jthrow-no-method \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"") text)))
          report (fn [names why what]
                   (when (and names (System/getenv "CHAMPACA_BT"))
                     (binding [*out* *err*]
                       (println (str "champaca: " cname "." mname desc " names " (str/join ", " (distinct names))
                                     ", " why "; that code throws " what)))))
          ;; and for a class the JVM refuses to define (check-definition!)
          refused (seq (map second (re-seq (re-pattern "\\(jthrow-linkage \"([^\"]+)\"") text)))
          ;; and for a call of the wrong kind (IncompatibleClassChangeError)
          icce (seq (map (fn [[_ c n d]] (str c "." n d))
                         (re-seq (re-pattern "\\(jthrow-icce \"[^\"]+\" \"([^\"]+)\" \"([^\"]+)\" \"([^\"]+)\"") text)))
          _ (report missing "not found" "NoClassDefFoundError")
          _ (report no-method "not found" "NoSuchMethodError")
          _ (report bad-format "not a class file" "ClassFormatError")
          _ (report icce "of the other kind" "IncompatibleClassChangeError")
          _ (report refused "refused by its definition's checks" "the JVM's linkage error")
          missing (or missing no-method bad-format icce refused)]
      (cond
        missing (timed :chez-compile #(scheme! text :compile (lines? cname info)))
        ;; compiled once: the same object code runs now and goes in the cache
        (compile-to-cache? origin)
        (let [bv (timed :chez-compile #(do (when (trace?) (scheme-trace! text))
                                           ;; a classpath class's frames keep their line numbers
                                           (s/call "compile-unit" text (lines? cname info))))]
          (record! origin unit {:fasl bv}))
        ;; the text is kept (cheaply) so save-cache! can compile it into a
        ;; generation; without that a jolt program re-jits these every run
        :else
        (do (timed :chez-compile #(scheme! text :compile (lines? cname info)))
            (record! origin unit {:text text :inspect (lines? cname info)}))))))

(defn saves-generation!
  "Tell the JIT this process will save a generation, so JDK methods compile
  once to object code (api save-cache! calls it on its first save)."
  []
  (swap! state assoc :saves-generation true))

(defn- dep-matches?
  "Whether a loader-defined class's dependency d is what its entry recorded:
  fp its fingerprint, or :present for a synthetic class. One not loaded yet
  is asked of the resolving loader, as translating the method would have
  (runtime.ss jit! and define-class! bind it); one it doesn't have is a
  mismatch."
  [d fp]
  (let [e (when-not (= fp :layer)
            (or (get-in @state [:classes d])
                (do (try (ensure-loaded! d) (catch Exception _ nil))
                    (get-in @state [:classes d]))))]
    (cond (= fp :layer) (not (outside-boot-layer? d)) ; a JDK class outside java.base
          (nil? e) false
          (= fp :present) true   ; a lambda class in state.img, thawed or not
          (packed? e) false      ; a JDK class has that name now
          :else (= fp (or (:src-fp e) (:fp (class-source d)))))))

(defn- deps-still-hold!
  "For a loader-defined class restored with cached methods: whether every
  class those methods read is what the entry recorded, checked once, at the
  first call of one of them, which is when translating it would have loaded
  them. If not, the cached methods are dropped (they compile again, and the
  save rewrites the entry) and the entry's dependencies start over from the
  class's ancestry; an ancestry that can't be checked any more makes the
  class uncacheable and drops its entry."
  [cname info]
  (or (not= :defined (:origin info))
      (contains? (:deps-held @state) cname)
      (if (every? (fn [[d fp]] (or (dep-matches? d fp)
                                   (do (when (trace?) (binding [*out* *err*] (println "cached methods of" cname "dropped:" d "changed")))
                                       false)))
                  (:deps info))
        (do (swap! state update :deps-held (fnil conj #{}) cname) true)
        (let [deps (user-deps [cname] true)
              dj (:src-jar info)]
          (swap! state update :cached-methods-dropped (fnil inc 0))
          (swap! state (fn [st]
                         (-> st
                             (update-in [:classes cname] #(if deps
                                                           (-> % (dissoc :cached-methods) (assoc :deps (dissoc deps cname)))
                                                           (dissoc % :cached-methods :src-fp :src-jar :deps)))
                             (update :library-dirty (fnil conj #{}) dj)
                             (cond-> (not deps) (assoc-in [:library-dropped dj cname] (get-in st [:user-cache dj cname]))))))
          false))))

(defn jit! [cname mname desc]
  (let [info (thaw! cname)
        unit (get (:cached-methods info) (str mname desc))
        ;; a restored class's dependencies are checked before any method of
        ;; it runs, cached or not: compiling an uncached one first would
        ;; record a changed dependency's new fingerprint, and the cached
        ;; ones, compiled against the old, would then pass the check
        held? (or (empty? (:cached-methods info)) (deps-still-hold! cname info))]
    (when-not (and unit held? (load-unit! unit)) ; else compiled by an earlier run
      (compile-method! cname mname desc))))

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

;; --- boot cache ---------------------------------------------------------------------
;; Modeled on Clojure's RT.load: one compiled unit per input, used only when
;; it is newer than its source, and a miss quietly takes the slow path. The
;; unit here is the whole JDK boot. Clojure's key is a timestamp; this key
;; also covers the translator itself, because a cache built by a different
;; translator is stale even when the JDK is not (Clojure's AOT
;; version-mismatch trap).

(def ^:private runtime-files
  "The Scheme runtime, in load order."
  ["champaca/runtime.ss" "champaca/natives.ss" "champaca/reflect.ss" "champaca/handles.ss" "champaca/files.ss"
   "champaca/net.ss" "champaca/spawn.ss" "champaca/foreign.ss"])

;; The text of a helpful NPE message, from the failing method's bytecode
;; (champaca.npe, loaded on the first message so other runs don't pay for
;; it). The class is parsed again with its local variable names, which a
;; normal parse skips, so the work happens only when a message is asked
;; for; a site always gives the same text, so it is kept.
(defn- npe-message** [site]
  (let [at (str/last-index-of site "@")
        tag (subs site 0 at)
        bci (parse-long (subs site (inc at)))
        paren (str/index-of tag "(")
        dot (str/last-index-of tag "." paren)
        cname (subs tag 0 dot)
        mname (subs tag (inc dot) paren)
        desc (subs tag paren)
        bytes (or (class-bytes cname) (throw (ex-info (str cname " is no longer on the classpath") {:class cname})))
        p (binding [cf/*locals* true] (cf/parse bytes))
        m (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (:methods p))]
    (when (:code m)
      ((requiring-resolve 'champaca.npe/message) (:cp p) m bci))))

;; Whatever goes wrong gives no message, as HotSpot gives none for bytecode
;; its analysis can't follow: getMessage must not throw. CHAMPACA_BT says
;; why on stderr, and CHAMPACA_NPE_STRICT (set by jolt test) rethrows, so a
;; bug in the port fails the suite instead of passing as a missing message.
(defn- npe-message* [site]
  (try (npe-message** site)
       (catch Exception e
         (when (env "CHAMPACA_NPE_STRICT") (throw e))
         (when (env "CHAMPACA_BT")
           (binding [*out* *err*] (println "champaca: no NPE message for" site (ex-message e))))
         nil)))

(defn- npe-message [site]
  (let [cache (:npe-messages @state)]
    (if (contains? cache site)
      (get cache site)
      (let [msg (npe-message* site)]
        (swap! state assoc-in [:npe-messages site] msg)
        msg))))

;; --- stack traces ---------------------------------------------------------------
;; A frame's source file, line and module for StackTraceElement. The file and
;; the LineNumberTable come from the class file, re-read with
;; classfile/*lines* the first time a class is asked (a normal parse skips
;; the table), the module from the image's package index for a JDK class.

;; jdk.internal.vm.annotation.Hidden marks methods the JVM leaves out of
;; stack traces (Thread.runWith, lambda forms). It has no elements, but the
;; method's other annotations may, so their values are skipped by the
;; element_value grammar (JVMS 4.7.16.1).
(declare ann-skip)
(defn- ann-u2 [b i] (+ (* 256 (bit-and (nth b i) 0xff)) (bit-and (nth b (inc i)) 0xff)))
(defn- ann-skip-annotation [b i]
  (let [n (ann-u2 b (+ i 2))]
    (loop [j (+ i 4) k 0] (if (= k n) j (recur (ann-skip b (+ j 2)) (inc k))))))
(defn- ann-skip [b i]
  (case (char (bit-and (nth b i) 0xff))
    (\B \C \D \F \I \J \S \Z \s \c) (+ i 3)
    \e (+ i 5)
    \@ (ann-skip-annotation b (inc i))
    \[ (let [n (ann-u2 b (inc i))] (loop [j (+ i 3) k 0] (if (= k n) j (recur (ann-skip b j) (inc k)))))))
(defn- hidden-method? [cp m]
  (when-let [b (:annotations m)]
    (try (let [n (ann-u2 b 0)]
           (loop [i 2 k 0]
             (cond (= k n) false
                   (= "Ljdk/internal/vm/annotation/Hidden;" (:value (cp (ann-u2 b i)))) true
                   :else (recur (ann-skip-annotation b i) (inc k)))))
         (catch Exception _ false))))

(defn- frame-class [cname]
  (let [cache (:frame-classes @state)]
    (if (contains? cache cname)
      (get cache cname)
      (let [info (try (when-let [bytes (class-bytes cname)]
                        (let [p (binding [cf/*lines* true] (cf/parse bytes))
                              slash (str/last-index-of cname "/")]
                          {:source (:source p)
                           :module (when (= :jdk (:origin (class-info cname)))
                                     (package-module (if slash (subs cname 0 slash) "")))
                           :lines (into {} (for [m (:methods p) :when (:code m)]
                                             [(str (:name m) (:desc m)) (:lines (:code m))]))
                           :hidden (set (for [m (:methods p) :when (hidden-method? (:cp p) m)]
                                          (str (:name m) (:desc m))))}))
                      (catch Exception e
                        (when (env "CHAMPACA_BT")
                          (binding [*out* *err*] (println "champaca: no source lines for" cname (ex-message e))))
                        nil))]
        (swap! state assoc-in [:frame-classes cname] info)
        info))))

(defn- frame-info
  "[file line module hidden] for cname.mname desc at bytecode pc, with \"\"
  and -1 where unknown (pc -1 is a frame whose pc wasn't recorded), hidden
  1 for a method the JVM leaves out of traces: one marked @Hidden, or any
  method of a hidden class (ClassFileParser marks them all), whose name is
  Name/0x<n> here (no identifier starts with a digit)."
  [cname mname desc pc]
  (let [{:keys [source module lines hidden]} (frame-class cname)
        table (get lines (str mname desc))
        line (if (and (seq table) (>= pc 0))
               (or (some (fn [[start l]] (when (<= start pc) l)) (rseq table)) -1)
               -1)]
    [(or source "") line (or module "")
     (if (or (contains? hidden (str mname desc)) (str/includes? cname "/0x")) 1 0)]))

(def ^:private translator-sources
  ["champaca/codegen.clj" "champaca/main.clj" "champaca/classfile.clj" "champaca/jimage.clj"
   "champaca/state.clj" "champaca/launcher.clj" "champaca/classpath.clj"])

(defmacro ^:private built-sources-hash
  "The translator sources' hash where this namespace is compiled: what a
  `jolt build` binary, which carries no source of a namespace compiled into
  it, keys its cache with."
  []
  ;; a source missing where the binary is built would leave the key blind to
  ;; it: fail the build instead
  (hash (apply str (map #(slurp (or (io/resource %) (throw (ex-info (str "built-sources-hash: no " %) {}))))
                        translator-sources))))

(defn- translator-fingerprint []
  (let [rt (apply str (map #(slurp (io/resource %)) runtime-files))]
    ;; a built binary carries the runtime files and not the translator's
    ;; sources, which io/resource could still find in a checkout beside it
    ;; (a deps.edn in the current directory): the hash taken at build time.
    ;; Elsewhere the sources themselves, since jolt caches a compiled
    ;; namespace by its own text, so main.clj's macro alone could miss a
    ;; codegen change
    (if (jolt.host/embedded-resource? "champaca/runtime.ss")
      (hash [rt (built-sources-hash)])
      (hash (apply str rt (map #(slurp (io/resource %)) translator-sources))))))

(defn- image-index-crc
  "CRC-32 of the runtime image's index: it changes with any resource added,
  removed, moved or resized, which a JDK build of another version or a
  different jlink selection does."
  []
  (let [idx ^bytes (:index (jdk-image)) c (java.util.zip.CRC32.)]
    (.update c idx 0 (count idx))
    (.getValue c)))

(defn- writable-root?
  "Whether the cache root can be written: it does, or the nearest directory
  above it that exists does (mkdirs makes the rest)."
  [^java.io.File f]
  (loop [f (.getAbsoluteFile f)]
    (cond (nil? f) false
          (.exists f) (.canWrite f)
          :else (recur (.getParentFile f)))))

(defn- cache-read-only?
  "Whether the cache key directory d can't be written: its root can't, or d
  is there and can't."
  [^java.io.File d]
  (not (and (writable-root? (.getParentFile (.getAbsoluteFile d))) (or (not (.exists d)) (.canWrite d)))))

(defn- cache-unusable!
  "This run goes without a cache: d can't be written and has none, or none
  that loads. Said once, on stderr. Answers nil."
  [d why]
  (when-not (:cache-unusable @state)
    (swap! state assoc :cache-unusable true)
    (when-not (:cache-unusable-said @state)
     (binding [*out* *err*]
      (println (str "champaca: the startup cache at " (.getParent (io/file d)) " can't be written and " why
                    ", so this run uses none")))))
  nil)

(declare delete-tree! prune-cache-root!)

(defn- user-cache-root
  "Where a bundle whose own cache can't be written keeps a writable one:
  CHAMPACA_USER_CACHE_DIR (off for none), else ~/.cache/champaca/bundles, one
  directory per bundle path below it."
  []
  (let [v (env "CHAMPACA_USER_CACHE_DIR")]
    (cond (= v "off") nil
          (seq v) (io/file v)
          (seq (env "HOME")) (io/file (env "HOME") ".cache" "champaca" "bundles")
          :else nil)))

(defn- seed-id
  "What a bundle's key directory d holds, as far as a user copy of it needs to
  know: state.img's CRC-32 and size, boot.so's size and the generations' names
  (all written only by a build or a save), nil when d has no cache."
  [^java.io.File d]
  (let [img (io/file d "state.img") so (io/file d "boot.so")]
    (when (and (.isFile img) (.isFile so))
      (let [c (java.util.zip.CRC32.)
            bs (with-open [in (io/input-stream img)] (.readAllBytes in))]
        (.update c bs 0 (count bs))
        (str (.getValue c) ":" (count bs) ":" (.length so) ":"
             (str/join "," (sort (filter #(str/starts-with? % "extra-") (map #(.getName ^java.io.File %) (.listFiles d))))))))))

(defn- copy-tree! [^java.io.File from ^java.io.File to]
  (doseq [^java.io.File f (file-seq from)
          :let [rel (subs (.getPath f) (count (.getPath from)))
                t (io/file (str (.getPath to) rel))]]
    (if (.isDirectory f) (.mkdirs t) (io/copy f t))))

(defn- copy-to-user-cache!
  "u, the user cache's copy of the bundle's key directory d: copied into a
  temp dir and renamed into place when missing (with a seed id), the
  winner's when another process's rename won. nil, said on stderr, when the
  copy fails."
  [d sid root u]
  (let [tmp (io/file root (str (.getName u) ".tmp-" (System/nanoTime)))]
    (try
      (when (and sid (not (.isDirectory u)))
        (.mkdirs root)
        (copy-tree! d tmp)
        (if (.renameTo tmp u)
          ;; a new version of the bundle's cache: older copies
          ;; go by the usual rules (a week unused, 16 at most)
          (prune-cache-root! u)
          (when-not (.isDirectory u)
            (throw (ex-info "rename failed" {})))))
      u
      (catch Exception e
        (binding [*out* *err*]
          (println "champaca: couldn't copy the bundle's cache to" (str root) (str "(" (ex-message e) ")")))
        nil)
      (finally (when (.exists tmp) (delete-tree! tmp))))))

(defn- user-copy
  "For a bundle's key directory d that can't be written, a writable copy under
  the user's cache for this bundle (user-cache-root, then a hash of the
  bundle's path), which runs then read and write as any cache: <key>-<hash of
  d's seed-id>, so each version of the bundle's cache gets a directory of its
  own and none is ever deleted under a process using it. A missing one is
  copied into a temp dir and renamed into place; when another process's
  rename won, that one is used and the temp dropped. Asked once per process.
  nil when there's no user cache, or it can't be written (said once), and the
  bundle's own cache is then read alone."
  [^java.io.File d]
  (if (contains? @state :user-copy)
    (:user-copy @state)
    (let [r (when-let [base (user-cache-root)]
              (let [h #(format "%08x" (bit-and (hash %) 0xffffffff))
                    root (io/file (.getAbsoluteFile base) (str (h @bundle-root) (h (str/reverse @bundle-root))))
                    ;; a seed it can't read (an install's permissions, an I/O
                    ;; error) has no id: no copy, and the run goes on as the
                    ;; bundle's own cache allows
                    sid (try (seed-id d) (catch Exception _ nil))
                    u (io/file root (str (.getName d) "-" (if sid (h sid) "0")))]
                (if-not (writable-root? root)
                  (do (binding [*out* *err*]
                        (println (str "champaca: the bundle's cache can't be written, nor its user cache at " root
                                      "; this run keeps nothing it compiles")))
                      ;; said: cache-unusable! needn't say it again
                      (swap! state assoc :cache-unusable-said true)
                      nil)
                  (copy-to-user-cache! d sid root u))))]
      (swap! state assoc :user-copy r)
      r)))

(defn- cache-dir []
  (when-not (= "off" (env "CHAMPACA_CACHE"))
    (let [modules (io/file (java-home) "lib" "modules")
          release (io/file (java-home) "release")
          ;; CHAMPACA_BT changes the generated code (shadow-stack wrappers that
          ;; hide frame names from getCallerClass), so it gets its own cache;
          ;; CHAMPACA_LINES decides which code carries inspector information
          ;; outside a bundle the image's mtime, as before: any rewrite of the
          ;; file, a same-size change to one class included, gives a fresh
          ;; cache. In a bundle (CHAMPACA_BUNDLE) its index by CRC-32 (every
          ;; resource's name, offset and sizes, not their bytes), since a copy
          ;; of the bundle has new mtimes and must keep its cache; the image
          ;; there is the one the bundle shipped with
          key (hash [(when (.exists release) (slurp release)) (.length modules)
                     (if @bundle-root (image-index-crc) (.lastModified modules))
                     (translator-fingerprint) (img/runtime-version) (some? (env "CHAMPACA_BT")) (some-> (env "CHAMPACA_LINES") str/lower-case)])
          d (io/file (cache-root) (Long/toHexString (bit-and key 0xffffffff)))
          ;; a bundle whose own cache can't be written runs on a copy in the
          ;; user's (user-copy)
          d (or (when (and @bundle-root (cache-read-only? d)) (user-copy d)) d)]
      ;; a cache that can't be written (an installed bundle's) is read when
      ;; it's there and loads; with none for this key, or one that failed to
      ;; load (cache-unusable!), the run has no cache rather than failing the
      ;; boot that would write one
      (cond (:cache-unusable @state) nil
            (or (.isDirectory d) (not (cache-read-only? d))) d
            :else (cache-unusable! d "has none for this run")))))

(defn- library-entries
  "jar's library file's entries: classpath classes by name as edn text,
  kept apart from the class table because the cache serves every program.
  It starts from the file as it is now, since another process may have
  saved since this one read it, less the entries this run found stale
  (unless they were replaced meanwhile); every class of the jar this run
  loaded is written again, with what it compiled."
  [jar classes]
  (into (reduce-kv (fn [m k seen] (if (packed= seen (get m k)) (dissoc m k) m))
                   (read-library-image jar) (get-in @state [:library-dropped jar]))
        (for [[k info] classes
              :when (and (map? info) (= :user (cache-origin info)) (= jar (:src-jar info)))]
          [k (packed-entry info)])))

(defn- dump-classes!
  "state.img: each class entry as edn text, plus the set of classes whose
  registration sits in a generation, so a load knows what is lazy without
  parsing anything. A JDK class goes in only when its registration is cached
  somewhere: boot.so (:boot-classes, the names of the class table as this
  process read it, or of a cold boot's registrations) or a generation
  (:cached-class). A
  later run takes any other entry for registered and fails with \"class
  not registered\": one whose save failed, so that a later save in the
  process found its map and not its code, or one linked and not yet
  recorded. Left out, it loads again in the run that needs it."
  [img]
  (let [{:keys [classes lazy boot-classes]} @state
        classes (into {} (filter (fn [[k v]] (or (packed? v) (contains? boot-classes k) (:cached-class v)))) classes)]
    (img/dump! (str img) {:lazy (into (set lazy)
                                      (for [[k v] classes :when (and (map? v) (= :jdk (:origin v)) (:cached-class v))] k))
                          :classes (strip-for-cache classes)})))

(defn- delete-tree! [f] (doseq [x (reverse (file-seq f))] (.delete x)))

;; A key directory is left behind whenever its key changes (a translator edit,
;; a JDK update), so writing a new one removes siblings no run has loaded for a
;; week: a hit touches its directory. Other live keys (CHAMPACA_BT, a second
;; JDK) stay as long as something uses them. jolt/ never matches, nor does
;; jmods/, where older versions kept copies of the JDK's jmods.
;; The age rule alone is not enough on a machine where the translator itself
;; is edited: every edit is a new key, none a week old, and a day of work
;; leaves gigabytes. So the newest few keys survive and the rest go, however
;; young. Half-written .tmp- directories are collected by age only.
(def ^:private stale-ms (* 7 24 60 60 1000))
(def ^:private keep-keys 16)

(defn- prune-cache-root! [keep]
  (let [cutoff (- (System/currentTimeMillis) stale-ms)
        sibs (filter #(and (.isDirectory %)
                           ;; a key, a user copy's <key>-<seed hash>, their temp dirs
                           (re-matches #"[0-9a-f]{1,8}(-[0-9a-f]{1,8})?(\.tmp-\d+)?" (.getName %))
                           (not= (.getName %) (.getName keep)))
                     (.listFiles (.getParentFile keep)))
        keys-newest-first (sort-by (juxt #(- (.lastModified %)) #(.getName %))
                                   (remove #(str/includes? (.getName %) ".tmp-") sibs))
        over (set (drop (dec keep-keys) keys-newest-first))]
    (doseq [d sibs
            :when (or (< (.lastModified d) cutoff) (over d))]
      (delete-tree! d))))

(defn- write-cache!
  "Compile the recorded boot forms to boot.so and save the class table to
  state.img, in a temp dir renamed into place so readers never see half."
  [dir forms]
  ;; boot! may have started the temp dir, with runtime.so already in it
  (let [pre (:cache-tmp @state)
        tmp (if (and pre (.exists (io/file pre "runtime.so"))) pre (io/file (str dir ".tmp-" (System/nanoTime))))]
    (swap! state dissoc :cache-tmp)
    (.mkdirs tmp)
    ;; registrations compile here; method bodies were compiled when they ran
    (timed :cache-write-boot #(write-units! (str (io/file tmp "boot.so")) forms true))
    ;; the classes whose registration boot.so now holds (dump-classes!)
    (swap! state assoc :boot-classes (set (keep (fn [{[c k] :unit}] (when-not k c)) forms)))
    ;; the runtime itself, so a warm start loads it compiled rather than
    ;; compiling its source again (about 130 ms)
    (when-not (.exists (io/file tmp "runtime.so"))
        (let [src (io/file tmp "runtime.ss")]
          (spit src (str/join "\n" (map #(slurp (io/resource %)) runtime-files)))
          (timed :cache-write-runtime #(s/call "quiet-compile-file" (str src) (str (io/file tmp "runtime.so"))))
          (.delete src)))
    (timed :cache-write-state #(dump-classes! (io/file tmp "state.img")))
    ;; a directory missing a file (a crash mid-write, a load that failed) would
    ;; block the rename forever, so it goes first
    (when (and (.exists dir) (not-every? #(.exists (io/file dir %)) ["boot.so" "state.img" "runtime.so"]))
      (delete-tree! dir))
    (if (.renameTo tmp dir)
      (prune-cache-root! dir)
      ;; another run won the race; ours is redundant
      (delete-tree! tmp))))

(defn- load-cache!
  "Restore a cached boot. Later generations stay on disk until something
  reaches them: their classes are listed in :lazy, and the class table says
  where each class's registration and each method body sits. Returns true on
  a hit."
  [dir]
  (let [so (io/file dir "boot.so") img-file (io/file dir "state.img")]
    (when (and dir (.exists so) (.exists img-file))
      (.setLastModified dir (System/currentTimeMillis))   ; keeps it from being pruned
      (try
        (let [{:keys [classes lazy]} (timed :cache-read-state #(img/read-image (str img-file)))]
          (when-not (and (map? classes) (set? lazy))
            (throw (ex-info "not a champaca state image" {})))
          ;; the names in the class table as read: its entries are what
          ;; boot.so registers, and the lazy ones (dump-classes!). The names
          ;; alone: the map itself would pin every packed entry for the run
          (swap! state assoc :classes classes :lazy lazy :boot-classes (set (keys classes)))
          (timed :cache-load-code #(s/call "load" (str so)))
          true)
        ;; a damaged cache would fail every run the same way: drop it, boot
        ;; cold, and the cold boot writes a new one
        (catch Exception e
          (swap! state assoc :classes {} :lazy #{} :boot-classes #{})
          (if (cache-read-only? dir)
            (cache-unusable! dir (str "its copy doesn't load (" (ex-message e) ")"))
            (do (binding [*out* *err*] (println "champaca: discarding a damaged startup cache:" (ex-message e)))
                (delete-tree! dir)))
          nil)))))

(defn- prune-library!
  "Drop library files no run has read for a week (a jar gone, a version no
  longer used), and temp files a crash left."
  [dir]
  ;; Racy by design: another process may rename a fresh file over one
  ;; between the age check and the delete, and that costs its jar one
  ;; recompile, never a stale entry.
  (let [cutoff (- (System/currentTimeMillis) stale-ms)]
    (doseq [f (.listFiles (io/file dir "lib"))
            :when (< (.lastModified f) cutoff)]
      (.delete f))))

(defn save-generation!
  "Append what this run compiled for JDK classes after boot as one more
  extra-N.so, compiled unit by unit, and rewrite state.img (written beside,
  then renamed) with where each unit sits. A save that fails drops its
  units rather than keeping them: retrying would fail the same way at every
  later save, and the code already ran, so only cache coverage is lost."
  []
  (let [dir (:cache-dir @state)
        ;; a cache that can't be written (an installed bundle's) is read
        ;; only: this run's code isn't kept, and the run doesn't fail for it
        dir (when (and dir (not (:cache-unusable @state)) (not (cache-read-only? dir))) dir)
        units (some-> (:recording @state) deref)]
    (when (and dir (.isDirectory dir) (seq units))
      (vreset! (:recording @state) [])
      (let [stamp (System/currentTimeMillis)
            file (str "extra-" stamp ".so")
            so-tmp (io/file dir (str file ".tmp"))
            img-tmp (io/file dir (str "state.img.tmp-" stamp))
            offsets (write-units! (str so-tmp) units false)
            starts (pop offsets)
            ends (subvec offsets 1)]
        (.renameTo so-tmp (io/file dir file))
        (doseq [[{[cname k] :unit} start end] (map vector units starts ends)]
          (swap! state assoc-in (if k [:classes cname :cached-methods k] [:classes cname :cached-class])
                 [file start (- end start)]))
        (dump-classes! img-tmp)
        (.renameTo img-tmp (io/file dir "state.img"))))
    ;; after the units above have their places
    (when (and dir (.isDirectory dir) (seq (:library-dirty @state)))
      (doseq [jar (remove nil? (:library-dirty @state))
              :let [f (library-file jar)
                    tmp (io/file (.getParentFile f) (str (.getName f) ".tmp-" (System/nanoTime)))]]
        ;; one jar's failure leaves it dirty and the others still go
        (try
          (.mkdirs (.getParentFile f))
          (img/dump! (str tmp) {:jar jar :entries (library-entries jar (:classes @state))})
          (if (.renameTo tmp f)
            (swap! state #(-> % (update :library-dirty disj jar) (update :library-dropped dissoc jar)))
            (.delete tmp))
          (catch Exception e
            (.delete tmp)
            (binding [*out* *err*]
              (println "champaca: couldn't save the library cache for" jar (str "(" (ex-message e) ")"))))))
      (swap! state update :library-dirty (fn [s] (set (remove nil? s))))
      (prune-library! dir))))

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

(defn- classpath-class-answer
  "The class file <cname>.class on the class path, found as the JDK's
  URLClassPath finds it (a directory through the file system, so a//b is
  a/b; a jar by its exact entry), for the application loader's names it
  can't load (natives.ss misnamed-classpath-class): false when there's no
  such file, else \"found\" when parse? is false, else what parsing it as
  cname gives, as normalize would: \"ok <its class name>\", \"format <text>\"
  or \"version <text>\" (HotSpot's ClassFormatError and
  UnsupportedClassVersionError texts), \"error <text>\" for anything else,
  so no Clojure exception crosses the native."
  [cname parse?]
  (if-let [bs (user-resource-bytes (str cname ".class"))]
    (if-not parse?
      "found"
      (try (str "ok " (:name (cf/parse bs)))
           (catch Exception e
             (let [{:keys [class-format named class-version]} (ex-data e)]
               (cond class-format (str "format " (if named (str class-format " " cname) class-format))
                     class-version (str "version " (class-version cname))
                     :else (str "error " (ex-message e)))))))
    false))

(defn- constant-value
  "ConstantPool natives (annotations): constant i of cname, its value or a
  class's name. A lone surrogate's code units (classfile/decode-mutf8) go
  over as a Scheme vector, which jstring takes."
  [cname i]
  (let [e (get (:cp (parsed cname)) i)
        v (or (:value e) (:name e)
              (throw (ex-info "no such constant" {:class cname :index i})))]
    (if (vector? v) (apply s/call "vector" v) v)))

(defn- install-hooks!
  "The runtime's hooks into this namespace, after the launcher's state."
  []
  (s/call "set-define-class-hook!" (fn [& args] (timed :define #(apply define-class! args))))
  ;; annotation instances: a synthetic class implementing the annotation
  ;; interface, its element keys "name()desc" joined by |
  (s/call "set-annotation-class-hook!" (fn [iface keys]
                                         (lambda-class [iface] (if (= "" keys) [] (str/split keys #"\|")))))
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
     (unless (eqv? 0 ((method-proc \"java/lang/System\" \"initPhase2(ZZ)I\") 0 0))
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
      (let [v (:version @state)
            vs (if v (s/call "java-print-version" (boolean (:err? v))) 0)
            status (cond (not (and cname (zero? vs))) vs
                         (:launch-module @state) (apply s/call "run-module-main" cname args)
                         :else (apply s/call "run-main" cname args))]
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
    (swap! state assoc :java-command (str/join " " (cons jar args)))
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
