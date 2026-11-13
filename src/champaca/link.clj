;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.link
  "The class table's readers and the linker: a class's table entry thawed
  on first read, resolution of fields and methods through its ancestry, a
  parsed class file made into the linker's class map with its layout and
  vtable, the registration text the Scheme runtime evaluates, and HotSpot's
  checks of a class's definition."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.cachedir :refer [cache-read-only? delete-tree!]]
            [champaca.classfile :as cf]
            [champaca.classpath :refer [bytes-fp class-bytes jdk-class-bytes outside-boot-layer? package-dir
                                        refuse-outside-layer! user-class-bytes]]
            [champaca.cgtext :as cgt]
            [champaca.state :refer [state timed *reads* ensure-loaded!* packed? unpack thaw-entry
                                    intern-keys intern-members]]))

(declare ancestry-of-unlinked)

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

(defn thaw!
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

(defn class-info [cname]
  (refuse-outside-layer! cname)
  (or (thaw! cname)
      (do (ensure-loaded!* cname) (thaw! cname))))

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

(defn methods-of
  "The methods of a class map: its own, or thawed through class-methods."
  [info]
  (when info (or (:methods info) (class-methods (:name info)))))

;; Parsed class files are kept for compiling a class's next method, but a
;; whole run's of them were nearly a quarter of Jackson's probe's heap (66 of 292 MB,
;; the constant pools 48). Only the most recently used parsed-limit of those
;; that can be parsed again from their bytes stay: a JDK or classpath class,
;; not hidden (renamed after parsing) and not generated (no bytes). A dropped
;; one is read again by parsed, as a class restored from a cache is.
;; CHAMPACA_PARSED_LIMIT changes it: 1 makes nearly every compile parse its
;; class again, which is how the re-reading path is tested
(def ^:private parsed-limit
  (or (some-> (System/getenv "CHAMPACA_PARSED_LIMIT") parse-long) 256))
(def ^:private parsed-clock (atom 0))

(defn- reparseable? [cname info]
  (and (map? info) (:parsed info) (#{:jdk :user} (:origin info))
       (not (str/includes? cname "/0x")) (not (str/starts-with? cname "champaca/"))))

(defn- drop-old-parsed [st]
  (let [ticks (:parsed-ticks st)
        holders (for [[n info] (:classes st) :when (reparseable? n info)] n)
        keep (set (take parsed-limit (sort-by #(- (get ticks % 0)) holders)))
        gone (remove keep holders)]
    (-> (reduce (fn [st n] (update-in st [:classes n] dissoc :parsed)) st gone)
        (update :parsed-ticks #(apply dissoc % gone)))))

(defn note-parsed!
  "Notes a use of cname's parsed class file; every parsed-limit uses, all
  but the parsed-limit most recently used reparseable ones are dropped."
  [cname]
  (let [t (swap! parsed-clock inc)]
    (swap! state assoc-in [:parsed-ticks cname] t)
    (when (zero? (mod t parsed-limit)) (swap! state drop-old-parsed))))

(defn parsed
  "The parsed classfile for cname. A class restored from the boot cache
  carries no bytecode, so it is re-read on the first method compile; a
  classpath class restored from the library cache must read back as the
  bytes its entry was made from, or its new methods wouldn't match the
  ones it has. A directory's class does, from the bytes it kept; a jar
  changed in place under the run is an error."
  [cname]
  (note-parsed! cname)
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

;; --- resolution ----------------------------------------------------------------------

(defn ancestry
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

(defn resolve-static-field [cname fname]
  (or (some (fn [c] (when (some #(and (:static %) (= fname (:name %))) (:fields (class-info c))) c))
            (ancestry cname))
      (throw (ex-info "no such static field" {:class cname :field fname}))))

(defn field-index [cname fname]
  (loop [c cname]
    (if-let [info (and c (class-info c))]
      (if (some #(and (not (:static %)) (= fname (:name %))) (:fields info))
        (get (:layout info) (str c "." fname))
        (recur (:super info)))
      (throw (ex-info "no such field" {:class cname :field fname})))))

(defn resolve-method
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

(defn check-virtual
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

(defn- call-target
  "[declaring class, method] a call of kind op resolves to: invokespecial and
  invokestatic as resolve-method finds it, the others as resolve-virtual;
  [nil nil] or a nil method when nothing does."
  [op cname mname desc]
  (if (#{:special :static} op)
    (let [d (try (resolve-method cname mname desc) (catch Exception _ nil))]
      [d (and d (some #(when (and (= mname (:name %)) (= desc (:desc %))) %) (class-methods d)))])
    (resolve-virtual cname mname desc)))

(defn- kind-mismatch
  "HotSpot's text when method m doesn't fit a call of kind op, or nil."
  [op m]
  (let [static? (and m (cf/static? m))]
    (cond (and m (= op :static) (not static?)) "Expected static method"
          (and static? (= op :interface)) "Expected instance not static method"
          (and static? (#{:virtual :special} op)) "Expecting non-static method")))

(defn check-kind
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
    (let [[decl m] (call-target op cname mname desc)]
      (when-let [what (kind-mismatch op m)]
        (throw (ex-info "incompatible class change" {:class decl :method mname :desc desc :icce what}))))))

(defn direct-target
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

(defn package-private-decl
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

;; --- loading -----------------------------------------------------------------------

(declare class-map linkage-error)

(defn final-sealed?
  "A parsed class that's final and has a PermittedSubclasses attribute,
  HotSpot's ClassFormatError."
  [c]
  (and (pos? (bit-and (:flags c) 0x0010)) (some? (:permitted-subclasses (:reflection c)))))

(defn- normalize-bytes
  "[bytes from-class-path?] for cname: the class path's, else the JDK's for a
  class of the boot layer (the JVM's loaders don't find one outside it,
  outside-boot-layer?), else ::defined when a loader of the program's own
  defines it (the class naming it is that loader's, natives.ss; the time is
  that loader's own code and the define, from the cache or not), else
  normalize's \"class not found\"."
  [cname]
  (let [user (timed :class-bytes #(user-class-bytes cname))]
    [(or user
         (when-not (outside-boot-layer? cname) (timed :class-bytes #(jdk-class-bytes cname)))
         (when (boolean (timed :loader #(s/call "ask-resolving-loader" cname))) ::defined)
         (throw (ex-info (str "class not found: " cname) {:class cname})))
     (boolean user)]))

(defn- class-format-error [cname msg]
  (ex-info (str "class format error in " cname ": " msg) {:class cname :class-format msg}))

(defn- parse-class-bytes
  "cname's bytes parsed. Bytes that aren't a class file are the JVM's
  ClassFormatError for the code that needs the class (codegen, runtime.ss
  find-class): the message ends with HotSpot's text, which find-class reads
  off; a version this release refuses names the class asked for."
  [cname bytes]
  (try (timed :cf-parse #(cf/parse bytes))
       (catch Exception e
         (let [{:keys [class-format named class-version]} (ex-data e)]
           (cond class-format (throw (class-format-error cname (if named (str class-format " " cname) class-format)))
                 class-version (throw (linkage-error cname "java/lang/UnsupportedClassVersionError" (class-version cname)))
                 :else (throw e))))))

(defn- check-class-file!
  "HotSpot's ClassFileParser refusals of a parsed class: a class file found
  under a name that isn't its own (a file system that ignores case, a
  renamed file) is not a second class, an interface's superclass is Object,
  and a final class isn't sealed."
  [cname p user?]
  (when (and user? (not= (:name p) cname))
    (throw (linkage-error cname "java/lang/NoClassDefFoundError" (str cname " (wrong name: " (:name p) ")"))))
  (when (and (pos? (bit-and (:flags p) 0x0200)) (not= "java/lang/Object" (:super p)))
    (throw (class-format-error cname (str "Interfaces must have java.lang.Object as superclass in class file " cname))))
  (when (final-sealed? p)
    (throw (class-format-error cname (str "PermittedSubclasses attribute in final class file " cname)))))

(defn normalize
  "Parse cname's classfile into the linker's class map. A classpath class is
  cached only through the library cache (restore-user-class!), which checks
  it hasn't changed."
  [cname]
  (let [[bytes user?] (normalize-bytes cname)]
    (when-not (= bytes ::defined)
      (let [p (parse-class-bytes cname bytes)]
        (check-class-file! cname p user?)
        (class-map cname p (if user? :user :jdk))))))

(defn class-map
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

(defn overridden?
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
     (into (or (:template sup) []) (map #(cgt/default-value (:desc %)) own))]))

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

(defn link
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

(defn ancestry-of-unlinked
  "ancestry for a class that is not in the registry yet."
  [info]
  (cons (:name info)
        (distinct (mapcat ancestry (remove nil? (cons (:super info) (:interfaces info)))))))

(defn- constant-text [{:keys [tag value]}]
  (case tag
    (:int :long :float :double) (cgt/num-lit value)
    :string (cgt/jstring-lit-form value)))

;; A reflection attribute as a Scheme literal: a Signature string, raw
;; annotation bytes, or #f when the classfile has none.
(defn- refl-lit [v]
  (cond (nil? v) "#f"
        (string? v) (cgt/str-lit v)
        :else (str "#vu8" (apply cgt/sx (map str v)))))

(defn- vtable-delta-text
  "The vtable as changes to the superclass's: printing every inherited entry
  made it a third of the startup cache's source."
  [super vtable]
  (str "'" (apply cgt/sx (let [sup-vt (when super (vtable-of (class-info super)))]
                          (concat
                           (for [[k g] (sort vtable) :when (not= g (get sup-vt k))]
                             (str "(" (cgt/str-lit k) " . " (cgt/sym g) ")"))
                           (for [k (sort (keys sup-vt)) :when (not (contains? vtable k))]
                             (str "(" (cgt/str-lit k) " . #f)")))))))

(defn- method-meta-text
  "A method's reflection meta: (name desc flags signature annotations
  param-annotations annotation-default type-annotations exceptions
  parameters), Exceptions as class names, MethodParameters as (name flags)
  with name #f when absent."
  [m]
  (cgt/sx (cgt/str-lit (:name m)) (cgt/str-lit (:desc m)) (str (:flags m))
         (refl-lit (:signature m)) (refl-lit (:annotations m))
         (refl-lit (:param-annotations m)) (refl-lit (:annotation-default m))
         (refl-lit (:type-annotations m))
         (if-let [exs (:exceptions m)] (apply cgt/sx (map cgt/str-lit exs)) "#f")
         (if-let [ps (:parameters m)]
           (apply cgt/sx (for [[n fl] ps] (cgt/sx (refl-lit n) (str fl))))
           "#f")))

(defn- field-meta-text
  "A field's reflection meta: (name desc flags signature annotations
  type-annotations)."
  [f]
  (cgt/sx (cgt/str-lit (:name f)) (cgt/str-lit (:desc f)) (str (or (:flags f) 0))
         (refl-lit (:signature f)) (refl-lit (:annotations f))
         (refl-lit (:type-annotations f))))

(defn- record-meta-text
  "Record: (name desc signature annotations type-annotations) per component."
  [record]
  (apply cgt/sx (for [rc record]
                 (cgt/sx (cgt/str-lit (:name rc)) (cgt/str-lit (:desc rc))
                        (refl-lit (:signature rc)) (refl-lit (:annotations rc))
                        (refl-lit (:type-annotations rc))))))

(defn- class-meta-text
  "The class's own reflection meta: (signature annotations inner
  enclosing-method record-components type-annotations nest-host
  nest-members permitted-subclasses declared-classes). inner is InnerClasses'
  entry for this
  class, (outer simple-name flags); enclosing-method is (class name desc);
  NestHost is the host's name and NestMembers their names;
  PermittedSubclasses their names, () for a sealed class listing none;
  declared-classes the names of the classes it declares, #f for none."
  [{:keys [inner enclosing-method record nest-members permitted-subclasses declared-classes] :as reflection}]
  (cgt/sx (refl-lit (:signature reflection)) (refl-lit (:annotations reflection))
         (if inner
           (cgt/sx (refl-lit (:outer inner)) (refl-lit (:simple-name inner)) (str (:flags inner)))
           "#f")
         (if enclosing-method
           (cgt/sx (refl-lit (:class enclosing-method)) (refl-lit (:name enclosing-method))
                  (refl-lit (:desc enclosing-method)))
           "#f")
         (if record (record-meta-text record) "#f")
         (refl-lit (:type-annotations reflection))
         (refl-lit (:nest-host reflection))
         (if nest-members (apply cgt/sx (map cgt/str-lit nest-members)) "#f")
         (if permitted-subclasses (apply cgt/sx (map cgt/str-lit permitted-subclasses)) "#f")
         (if declared-classes (apply cgt/sx (map cgt/str-lit declared-classes)) "#f")))

(defn- register-form
  "register-class! for the class: name, super, interfaces, template, the
  vtable delta and the field layout as one pair, flags, then the declared
  members in classfile order for reflection (methods, fields, then the
  class's own meta)."
  [{:keys [name super interfaces template layout fields flags reflection]} vtable methods]
  (cgt/sx "register-class!" (cgt/str-lit name) (if super (cgt/str-lit super) "#f")
         (str "'" (apply cgt/sx (map cgt/str-lit interfaces)))
         (apply cgt/sx "vector" template)
         (cgt/sx "cons" (vtable-delta-text super vtable)
                 (str "'" (apply cgt/sx (for [[k i] (sort layout)] (str "(" (cgt/str-lit k) " . " i ")")))))
         (str flags)
         (str "'" (cgt/sx (apply cgt/sx (map method-meta-text methods))
                         (apply cgt/sx (map field-meta-text fields))
                         (class-meta-text reflection)))))

(defn- conflict-stubs
  "A conflicting default's stub, with the JVM's message: the interfaces in
  declaration order, dotted, without descriptors."
  [name conflicts]
  (for [[k is] (sort conflicts)
        :let [mname (subs k 0 (str/index-of k "("))]]
    (cgt/sx "define-global!" (str "'" (cgt/sym (str "icce:" name "." k)))
           (cgt/sx "icce-stub" (cgt/str-lit (str "Conflicting default methods: "
                                               (str/join " " (map #(str (str/replace % "/" ".") "." mname) is))))))))

(defn- static-globals [name fields]
  (for [f fields :when (:static f)]
    (cgt/sx "define-global!" (str "'" (cgt/static-global name (:name f)))
           (if-let [k (:constant f)] (constant-text k) (cgt/default-value (:desc f))))))

(defn- define-methods-form
  "Every method's definition from one form: a native's from natives.ss, any
  other's from method-stub, which returns a runtime override when natives.ss
  has one, else a closure that JIT-compiles the method on first call."
  [name methods]
  (cgt/sx "define-methods!" (cgt/str-lit name)
         (str "'" (apply cgt/sx (for [m methods
                                     :when (or (:native m) (:code m) (overridden? name m))]
                                 (cgt/sx (cgt/str-lit (:name m)) (cgt/str-lit (:desc m))
                                        (cgt/method-global name (:name m) (:desc m))
                                        (if (:native m) "#t" "#f")))))))

(defn registration-text [{:keys [name fields conflicts origin] :as info}]
  (let [vtable (vtable-of info) methods (methods-of info)]
    (str/join
     "\n"
     (concat
      ;; a classpath class stays in the unnamed module whatever its package
      ;; (runtime.ss user-class!); a registration the library cache keeps
      ;; carries this too
      (when (= origin :user) [(cgt/sx "user-class!" (cgt/str-lit name))])
      [(register-form info vtable methods)]
      (conflict-stubs name conflicts)
      (static-globals name fields)
      [(define-methods-form name methods)]))))

(defn linkage-error
  "The ex-info for a class the JVM refuses to define, error the JVM class's
  slashed name: runtime.ss find-class, define-class! and codegen read it."
  [cname error msg]
  (ex-info (str "linkage error in " cname ": " error "|" msg) {:class cname :linkage error :message msg}))

(defn dotted
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

(defn sealed-refused?
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

(defn- inherited-instance-method?
  "Whether m can override: an instance method, not a constructor, not private."
  [m]
  (and (not (cf/static? m)) (not= "<init>" (:name m)) (zero? (bit-and (:flags m) 0x0002))))

(defn- final-method-seen
  "c's final instance method with m's name and descriptor that class n can
  see (public or protected, or package-private from n's package), or nil."
  [c m n]
  (let [pkg package-dir   ; a hidden class's /0x<n> suffix isn't its package
        sm (some #(when (and (= (:name m) (:name %)) (= (:desc m) (:desc %))) %) (class-methods c))]
    (when (and sm (pos? (bit-and (:flags sm) 0x0010)) (not (cf/static? sm))
               (zero? (bit-and (:flags sm) 0x0002))
               (or (pos? (bit-and (:flags sm) 0x0005)) (= (pkg c) (pkg n))))
      sm)))

(defn- final-override-of
  "The error for class n's method m overriding a final method of the first
  superclass in supers that has one n can see, or nil."
  [m n supers]
  (some (fn [c]
          (when (final-method-seen c m n)
            (linkage-error n "java/lang/IncompatibleClassChangeError"
                           (str "class " (dotted n) " overrides final method " (dotted c) "." (:name m) (:desc m)))))
        supers))

(defn- final-override-error
  "The error for the first instance method of info overriding a final one of
  a superclass it can see (a package-private one only from the same
  package), or nil."
  [info sup]
  (let [n (:name info)
        supers (take-while some? (iterate #(:super (class-info %)) sup))]
    (some #(when (inherited-instance-method? %) (final-override-of % n supers))
          (:methods info))))

(defn check-definition!
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
