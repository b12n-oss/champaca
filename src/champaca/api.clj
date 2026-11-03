(ns champaca.api
  "Call Java from jolt, in-process. Same surface as jolt-openldk's
  net.b12n.jolt.openldk (init!, call-static, new-object, call, implement),
  but with no SBCL and no wire protocol: Java objects live in jolt's own
  Chez heap and calls are direct procedure calls.

  Class names use slashes (\"java/util/ArrayList\") and every call names
  its JVM descriptor, as in jolt-openldk."
  (:refer-clojure :exclude [proxy])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [champaca.main :as main]))

(defonce ^:private interop-loaded (atom false))
(defonce ^:private dynamic-loaded (atom false))
(defonce ^:private gil-released (atom false))

(defn- with-gil
  "Run f holding the runtime's global lock, which one thread at a time holds
  while it runs Java code (natives.ss). Between calls jolt code runs without
  it, so Java threads started from here keep going. Re-entrant: a jolt fn
  that Java calls back can call Java again."
  [f]
  (s/call "gil-enter!")
  (try (f) (finally (s/call "gil-leave!"))))

(defn- rethrow*
  [f]
  (try (f)
       (catch :default e
         (cond
           ;; System.exit from Java ends the process, as on a JVM
           (s/call "java-halt?" e)
           (System/exit (s/call "java-halt-status" e))
           (s/call "ldk-java-exception?" e)
           (let [x (s/call "ldk-exception-object" e)]
             ;; a jolt error that left a jolt fn through Java comes back as itself
             (if-let [j (s/call "ldk-jolt-error-of" x)]
               (throw j)
               (let [m (s/call "ldk-exception-message" e) d {:java/exception x}
                     c (s/call "ldk-jolt-cause" x)]
                 (throw (if c (ex-info m d c) (ex-info m d))))))
           (and @dynamic-loaded (s/call "ldk-api-error?" e))
           (throw (ex-info (s/call "ldk-api-error-message" e) {:champaca/error :lookup}))
           :else (throw e)))))

(defn- rethrow
  "Run f, which calls Java, holding the lock, and turn the runtime's
  java-exception condition into an ex-info (its message is the exception's
  toString, more Java)."
  [f]
  (with-gil #(rethrow* f)))

(defn- java-error-of
  "The Java exception to raise for c, a condition escaping a jolt fn that
  Java called (interop.ss call-from-java): a Java exception, thrown by the
  fn or by a call it made, as itself, else a RuntimeException standing for
  the jolt error, which comes back as itself if it reaches jolt again.
  Raising c again lets jolt's catch unwrap what jolt's throw wrapped."
  [c]
  (try (s/call "raise" c)
       (catch :default e
         (cond (s/call "ldk-java-object?" e) e
               (:java/exception (ex-data e)) (:java/exception (ex-data e))
               :else (s/call "ldk-jolt-error->java" e (or (ex-message e) (str e)))))))

(declare ->arg)

(defn init!
  "Boot the JDK. opts: {:classpath [\"dir\" \"lib.jar\" ...]} for user
  classes, :preview true to load class files compiled with --enable-preview
  (and turn on the JDK's preview features), as java --enable-preview does.
  Only the first call boots, so a later call's options are ignored."
  ([] (init! {}))
  ([{:keys [classpath preview]}]
   (let [status (main/init! (or classpath []) {:preview preview})]
     (when-not (zero? status) (throw (ex-info "JDK boot failed" {:status status}))))
   (when-not @interop-loaded
     (s/eval-string (slurp (io/resource "champaca/interop.ss")))
     (s/call "set-jolt-error-hook!" java-error-of)
     (reset! interop-loaded true))
   (when-not @dynamic-loaded
     (s/eval-string (slurp (io/resource "champaca/dynamic.ss")))
     (s/call "set-lambda-class-hook!" (fn [iface sam-key] (main/lambda-class [iface] [sam-key])))
     (s/call "set-jolt-data-hook!" ->arg)
     (reset! dynamic-loaded true))
   ;; boot ran holding the lock; from here on each call takes it
   (when (compare-and-set! gil-released false true)
     (s/call "gil-leave!"))
   :ok))

(defn call-static [cls mname desc & args]
  (rethrow #(do (main/ensure-loaded! cls)
                (apply s/call "ldk-call-static" cls mname desc args))))

(defn new-object [cls desc & args]
  (rethrow #(do (main/ensure-loaded! cls)
                (apply s/call "ldk-new" cls desc args))))

(defn call [obj mname desc & args]
  (rethrow #(apply s/call "ldk-call" obj mname desc args)))

(defn implement
  "A Java object implementing the single-method interface iface, whose
  method (mname, desc) calls the jolt fn f with converted arguments."
  [iface mname desc f]
  (with-gil #(s/call "ldk-implement" (main/lambda-class [iface] [(str mname desc)]) desc f)))

(defn to-string [obj] (rethrow #(s/call "ldk-to-string" obj)))

(defn save-cache!
  "Add whatever JDK code this process compiled since boot to the startup
  cache, so the next process loads it instead of compiling it. Call it
  before exit; it is cheap when nothing new was compiled. The first call
  also tells the JIT this process saves, so later compiles go straight to
  object code instead of being compiled again at the next call."
  []
  (with-gil #(do (main/saves-generation!) (main/save-generation!))))

(defn- ->arg
  "jolt vectors go to Scheme as lists, which the runtime turns into a Java
  array when the chosen parameter is an array (or a varargs tail), or an
  ArrayList for a List, Collection or Iterable. Maps, sets and other seqs are
  tagged, to become a LinkedHashMap, LinkedHashSet or ArrayList, and keywords
  become strings (their name, with any namespace)."
  [x]
  (cond (vector? x) (apply s/call "list" (map ->arg x))
        (map? x) (s/call "make-jolt-map" (apply s/call "list" (map (fn [[k v]] (s/call "cons" (->arg k) (->arg v))) x)))
        (set? x) (s/call "make-jolt-set" (apply s/call "list" (map ->arg x)))
        (seq? x) (s/call "make-jolt-list" (apply s/call "list" (map ->arg x)))
        (keyword? x) (subs (str x) 1)
        :else x))

;; --- descriptor-free calls ---------------------------------------------------------
;; Class names may use dots or slashes. The overload is chosen from the
;; runtime types of the arguments (see resources/champaca/dynamic.ss),
;; a jolt fn passed for a single-method interface is wrapped as one, and
;; boxed results come back as jolt numbers, booleans and chars.

(defn construct
  "Construct cls with the constructor that fits args."
  [cls & args]
  (rethrow #(apply s/call "dyn-new" cls (map ->arg args))))

(defn invoke
  "Call the instance method mname on obj (a Java object, or a jolt string
  as java.lang.String) with the overload that fits args."
  [obj mname & args]
  (rethrow #(apply s/call "dyn-invoke" obj mname (map ->arg args))))

(defn invoke-static
  "Call the static method mname of cls with the overload that fits args."
  [cls mname & args]
  (rethrow #(apply s/call "dyn-invoke-static" cls mname (map ->arg args))))

(defn get-static
  "The static field fname of cls, or of the superinterface or superclass
  that declares it. Initializes the declaring class first."
  [cls fname]
  (rethrow #(s/call "dyn-get-static" cls fname)))
(defn get-field
  "The instance field fname of obj, from obj's class up."
  [obj fname]
  (rethrow #(s/call "dyn-get-field" obj fname)))

(defn set-static!
  "Store v in the static field fname of cls (found as get-static finds it),
  converted as an argument of the field's type would be. Refuses a final
  field and a value that doesn't fit. Returns nil."
  [cls fname v]
  (rethrow #(s/call "dyn-set-static!" cls fname (->arg v)))
  nil)
(defn set-field!
  "Store v in the instance field fname of obj, as set-static! does."
  [obj fname v]
  (rethrow #(s/call "dyn-set-field!" obj fname (->arg v)))
  nil)

(declare ->clj*)

(defn ->clj
  "Copy Java data into jolt data, deeply: an array or a List (any Collection)
  becomes a vector, a Set a set, a Map a map, with strings and boxed numbers
  converted. Anything else is returned as is. Options: :key-fn, applied to
  every map key that is a string, e.g. keyword; two keys it maps to the
  same key throw."
  ([x] (->clj x nil))
  ([x {:keys [key-fn]}]
   (with-gil #(->clj* x (or key-fn identity)))))

(defn- ->clj* [x key-fn]
  (cond
    (s/call "dyn-array?" x)
    (mapv #(->clj* (s/call "dyn-aref" x %) key-fn) (range (s/call "dyn-alength" x)))
    (s/call "dyn-instance?" x "java/util/Map")
    (let [a (s/call "dyn-map-array" x)]
      ;; a key-fn can map two keys to one; that throws rather than drop one
      (reduce (fn [m i]
                (let [k (->clj* (s/call "dyn-aref" a i) key-fn)
                      k' (if (string? k) (key-fn k) k)]
                  (when (contains? m k')
                    (throw (ex-info (str "->clj: :key-fn maps two keys to " (pr-str k')) {:key k'})))
                  (assoc m k' (->clj* (s/call "dyn-aref" a (inc i)) key-fn))))
              {} (range 0 (s/call "dyn-alength" a) 2)))
    (s/call "dyn-instance?" x "java/util/Set") (set (->clj* (s/call "dyn-coll-array" x) key-fn))
    (s/call "dyn-instance?" x "java/util/Collection") (->clj* (s/call "dyn-coll-array" x) key-fn)
    :else x))

(defn ->java
  "Copy jolt data into Java collections, deeply: a map becomes a
  LinkedHashMap, a set a LinkedHashSet, a vector or seq an ArrayList, and
  strings, numbers and keywords (as strings) are converted as Objects."
  [x]
  (rethrow #(s/call "dyn->java" (->arg x))))

(defn proxy
  "A java.lang.reflect.Proxy implementing the interfaces ifaces (class
  names, dots or slashes), whose methods call the jolt fns in methods, a
  map from method name (a string or keyword) to fn. Overloads share their
  name's fn. Arguments arrive converted as invoke's results are, and the
  fn's result goes back boxed for the method's return type (a boolean
  method takes jolt's truth). A method with
  no fn runs its own body when it has one (a default method, Object's
  equals, hashCode and toString), else throws
  UnsupportedOperationException."
  [ifaces methods]
  (let [fs (into {} (map (fn [[k f]]
                          [(if (keyword? k) (name k) k)
                           (cond (fn? f) f
                                 ;; a var, a keyword or a map: Scheme calls only procedures
                                 (ifn? f) (fn [& args] (apply f args))
                                 :else (throw (ex-info (str "proxy: " (pr-str k) " is not a fn") {:method k})))]))
                 methods)]
    (rethrow #(s/call "dyn-proxy" (apply s/call "list" ifaces) (fn [n] (get fs n))))))

;; --- classes defined from jolt -------------------------------------------------------

(defonce ^:private jolt-classes (atom {}))   ; dotted name -> {:shape :cells :scells :icell :ccell :pcell :class}

(def ^:private prim-descs
  {"boolean" "Z" "byte" "B" "char" "C" "short" "S" "int" "I" "long" "J" "float" "F" "double" "D"})

(defn- type-desc
  "The descriptor of a type as Java source names it: int, java.lang.String,
  String[] with its package, java/util/List."
  [t]
  (cond (str/ends-with? t "[]") (str "[" (type-desc (subs t 0 (- (count t) 2))))
        (prim-descs t) (prim-descs t)
        :else (str "L" (str/replace t "." "/") ";")))

;; --- annotations for define-class ------------------------------------------------
;; Checked here against the annotation type through reflection, as javac
;; checks them, and handed to class-file-bytes (dynamic.ss) as element_values.

(defn- class-exists? [slashed] (with-gil #(boolean (s/call "class-exists?" slashed))))

(defn- annotation-type
  "The Class of the annotation type t (dots or slashes), refusing a name
  that isn't a class, a class that isn't an annotation type, one without
  RUNTIME retention (reflection would never see it) and one whose @Target
  leaves out kind, an ElementType name."
  [who t kind]
  (let [t (str/replace (if (keyword? t) (name t) (str t)) "/" ".")]
    (when-not (class-exists? (str/replace t "." "/"))
      (throw (ex-info (str who ": " t " isn't a class (a nested class is Outer$Inner)") {:annotation t})))
    (let [c (invoke-static "java.lang.Class" "forName" t)
          who (str who ": @" t)
          meta-ann (fn [n] (invoke c "getAnnotation" (invoke-static "java.lang.Class" "forName" n)))]
      (when-not (invoke c "isAnnotation")
        (throw (ex-info (str who " isn't an annotation type") {:annotation t})))
      (let [r (meta-ann "java.lang.annotation.Retention")
            policy (if r (invoke (invoke r "value") "name") "CLASS")]
        (when-not (= policy "RUNTIME")
          (throw (ex-info (str who " has " policy " retention, so reflection would never see it") {:annotation t}))))
      (when-let [tg (meta-ann "java.lang.annotation.Target")]
        (let [kinds (set (map #(invoke % "name") (->clj (invoke tg "value"))))]
          (when-not (or (kinds kind) (and (= kind "TYPE") (kinds "TYPE_USE")))
            (throw (ex-info (str who " isn't for a " (str/lower-case kind) ", only " (str/join ", " (sort kinds)))
                            {:annotation t})))))
      c)))

(declare annotation-of)

(defn- annotation-value
  "v as an element of type rt (a Class) takes it, as the element_value
  class-file-bytes encodes: [tag & payload]. A single value for an array
  element is an array of one, as in Java source."
  [who rt v]
  (let [rname (invoke rt "getName")
        bad (fn [what] (throw (ex-info (str who " is " (pr-str v) ", not " what) {:value v})))
        whole (fn [tag lo hi what]
                (cond (and (integer? v) (<= lo v hi)) [tag v]
                      (integer? v) (throw (ex-info (str who " is " v ", out of range for " what) {:value v}))
                      :else (bad (str "an integer (" what ")"))))]
    (cond
      (invoke rt "isArray")
      (let [ct (invoke rt "getComponentType")]
        (into ["["] (map-indexed (fn [i x] (annotation-value (str who "[" i "]") ct x)) (if (sequential? v) v [v]))))
      (= rname "boolean") (if (boolean? v) ["Z" (if v 1 0)] (bad "a boolean"))
      (= rname "byte") (whole "B" -128 127 "byte")
      (= rname "short") (whole "S" -32768 32767 "short")
      (= rname "int") (whole "I" -2147483648 2147483647 "int")
      (= rname "long") (whole "J" -9223372036854775808 9223372036854775807 "long")
      (= rname "char") (if (char? v) ["C" (int v)] (whole "C" 0 65535 "char"))
      (= rname "float")
      ;; as javac: a value that rounds to infinity is too large, one that
      ;; rounds to zero too small (the bounds are half an ulp past the
      ;; largest and smallest floats)
      (let [d (when (number? v) (double v))]
        (cond (nil? d) (bad "a float")
              (and (not (infinite? d)) (>= (abs d) 3.4028235677973366E38))
              (throw (ex-info (str who " is " v ", out of range for float") {:value v}))
              (and (not (zero? d)) (<= (abs d) 7.006492321624085E-46))
              (throw (ex-info (str who " is " v ", too small for float") {:value v}))
              :else ["F" d]))
      (= rname "double") (if (number? v) ["D" (double v)] (bad "a double"))
      (= rname "java.lang.String")
      (cond (not (string? v)) (bad "a string")
            ;; a class file's strings are at most 65535 bytes of modified UTF-8
            (> (reduce + (map #(let [c (int %)] (cond (<= 1 c 0x7f) 1 (<= c 0x7ff) 2 (<= c 0xffff) 3 :else 6)) v)) 65535)
            (throw (ex-info (str who " is a string of more than 65535 bytes in a class file's UTF-8") {:value (subs v 0 20)}))
            :else ["s" v])
      (= rname "java.lang.Class")
      (cond (with-gil #(s/call "dyn-instance?" v "java/lang/Class")) ["c" (invoke v "descriptorString")]
            (string? v) (let [d (if (= v "void") "V" (type-desc v))
                              e (str/replace d #"^\[+" "")]
                          (when (and (str/starts-with? e "L") (not (class-exists? (subs e 1 (dec (count e))))))
                            (throw (ex-info (str who " is " (pr-str v) ", which isn't a class") {:value v})))
                          ["c" d])
            :else (bad "a type name or a Class"))
      (invoke rt "isEnum")
      (let [n (if (keyword? v) (name v) v)
            names (mapv #(invoke % "name") (->clj (invoke rt "getEnumConstants")))]
        (if (and (string? n) (some #{n} names))
          ["e" (invoke rt "descriptorString") n]
          (bad (str "a constant of " rname " (" (str/join ", " names) ")"))))
      (invoke rt "isAnnotation")
      (if (map? v) ["@" (annotation-of who rt v)] (bad (str "a map of " rname "'s elements")))
      :else (bad rname))))

(defn- annotation-of
  "The annotation of type c (a Class) with the elements in m, as
  [desc [[element value] ...]] in the order c declares them. Refuses an
  element c doesn't have and leaving out one that has no default."
  [who c m]
  (let [given (reduce (fn [g [k v]]
                        (let [n (if (keyword? k) (name k) k)]
                          (when-not (string? n)
                            (throw (ex-info (str who ": " (pr-str k) " isn't an element name") {:element k})))
                          (when (contains? g n) (throw (ex-info (str who ": element " n " is given twice") {:element n})))
                          (assoc g n v)))
                      {} m)
        elems (->> (->clj (invoke c "getDeclaredMethods"))
                   (remove #(or (invoke % "isSynthetic") (pos? (bit-and (invoke % "getModifiers") 8))))
                   (mapv (fn [e] [(invoke e "getName") (invoke e "getReturnType") (some? (invoke e "getDefaultValue"))])))]
    (doseq [n (keys given)]
      (when-not (some #(= n (first %)) elems)
        (throw (ex-info (str who " has no element " n) {:element n}))))
    [(invoke c "descriptorString")
     (vec (keep (fn [[n rt default?]]
                  (cond (contains? given n) [n (annotation-value (str who ": element " n) rt (given n))]
                        default? nil
                        :else (throw (ex-info (str who " needs element " n ", which has no default") {:element n}))))
                elems))]))

(defn- annotations-for
  "m, a map from annotation type to a map of its elements (nil for none), as
  a vector of annotations sorted by type, for a target of kind (an
  ElementType name) that where names."
  [where kind m]
  (when-not (or (nil? m) (map? m))
    (throw (ex-info (str "define-class: the annotations " where " are " (pr-str m) ", not a map of type to elements") {})))
  (let [who (str "define-class: " where)
        anns (map (fn [[t elems]]
                    (when-not (or (nil? elems) (map? elems))
                      (throw (ex-info (str who ": @" t "'s elements are " (pr-str elems) ", not a map") {:annotation t})))
                    (let [c (annotation-type who t kind)]
                      (annotation-of (str who ": @" (invoke c "getName")) c elems)))
                  m)]
    (doseq [[d n] (frequencies (map first anns))]
      (when (> n 1)
        (throw (ex-info (str who ": @" (str/replace (subs d 1 (dec (count d))) "/" ".") " is given twice") {:annotation d}))))
    (vec (sort-by first anns))))

(defn- scm-annotation [[desc elems]]
  (letfn [(value [[tag & xs :as v]]
            (case tag
              "@" (s/call "list" tag (scm-annotation (first xs)))
              "[" (apply s/call "list" tag (map value xs))
              (apply s/call "list" v)))]
    (apply s/call "list" desc (map (fn [[n v]] (s/call "cons" n (value v))) elems))))

(defn- as-fn
  "f as a Scheme procedure: a var, a keyword or a map is wrapped, since
  Scheme calls only procedures; anything else isn't callable."
  [who k f]
  (cond (fn? f) f
        (ifn? f) (fn [& args] (apply f args))
        :else (throw (ex-info (str who ": " (pr-str k) " is not a fn") {:method k}))))

(defn- method-key [k]
  (let [k (if (keyword? k) (name k) k)]
    (when (and (str/includes? k "(")
               (not (re-matches #"[^()\[;/.<>]+\((\[*([BCDFIJSZ]|L[^;]+;))*\)(V|\[*([BCDFIJSZ]|L[^;]+;))" k)))
      (throw (ex-info (str "define-class: " (pr-str k) " is neither a method name nor name(descriptor)return") {:method k})))
    k))

(defn define-class
  "Define cname (a binary name, dots or slashes) as a real Java class, final,
  extending :extends (default java.lang.Object) and implementing the
  interfaces in :implements, and answer its Class. Java code, reflection and
  Class.forName see an ordinary class of the application loader.
  :fields names its public fields: a vector of names (each an Object) or a
  map from name to type (\"int\", \"java.lang.String\", \"long[]\").
  :methods maps a method name (string or keyword) to a fn, which implements
  every method of that name the class inherits and can override (abstract,
  default or concrete, public or protected, not final), or a full JVM name
  such as \"greet(Ljava/lang/String;)Ljava/lang/String;\" to a fn for that one
  method, which wins over its name's and may be a new method of the class's
  own. Each fn gets the object, then the method's arguments converted as
  invoke's results are; its result is checked against the return type.
  :static-fields and :static-methods are the same for public static fields
  and methods, every :static-methods key a full JVM name, and a static
  method's fn gets the arguments alone. :static-init is a fn of no
  arguments (nil or false for none) run once, as the class's static initializer, when Java first
  initializes the class (Class.forName, a constructor, a static member). The
  class has a public constructor for each public or protected one of its
  superclass, passing the arguments on, unless :constructors gives its own,
  as gen-class's does: a map from a vector of parameter types to the vector
  of a superclass constructor's. :init gets a constructor's arguments and
  returns the superclass constructor's as a vector; :post-init then gets the
  object and the arguments. Defining the same name again with
  the same superclass, interfaces, fields and method keys swaps in the new
  fns; anything else is refused, since a class is defined once per process."
  [cname {:keys [extends implements fields methods static-fields static-methods static-init
                 constructors init post-init annotations field-annotations method-annotations
                 constructor-annotations]}]
  (let [extends (or extends "java.lang.Object")
        dotted (str/replace cname "/" ".")
        slashed #(str/replace % "." "/")
        field-descs (fn [fs] (mapv (fn [[n t]] [(name n) (type-desc t)])
                                   (if (map? fs) fs (map vector fs (repeat "java.lang.Object")))))
        fields (field-descs fields)
        sfields (field-descs static-fields)
        fn-map (fn [ms static?]
                 (reduce (fn [m [k f]]
                           (let [mk (method-key k)]
                             (when (and static? (not (str/includes? mk "(")))
                               (throw (ex-info (str "define-class: static method " (pr-str mk)
                                                    " needs its full JVM name, name(descriptor)return")
                                               {:method k})))
                             (when (contains? m mk)
                               (throw (ex-info (str "define-class: method " (pr-str mk) " is named twice") {:method k})))
                             (assoc m mk (as-fn "define-class" k f))))
                         {} ms))
        fns (fn-map methods false)
        sfns (fn-map static-methods true)
        sinit (when static-init (as-fn "define-class" :static-init static-init))
        ctor-desc (fn [ts] (str "(" (apply str (map type-desc ts)) ")V"))
        ctors (when (some? constructors)
                (into (sorted-map) (map (fn [[own sup]] [(ctor-desc own) (ctor-desc sup)])) constructors))
        _ (when (and (nil? constructors) (or init post-init))
            (throw (ex-info "define-class: :init and :post-init are for :constructors, and there are none" {:class cname})))
        cinit (when init (as-fn "define-class" :init init))
        post (when post-init (as-fn "define-class" :post-init post-init))
        ;; {target [annotation ...]}, a target named once whatever its spelling
        member-anns (fn [what m norm known? kind where]
                      (when-not (or (nil? m) (map? m))
                        (throw (ex-info (str "define-class: " what " is " (pr-str m) ", not a map") {})))
                      (reduce (fn [acc [k anns]]
                                (let [t (norm k)]
                                  (when-not (known? t)
                                    (throw (ex-info (str "define-class: " what " names " (pr-str k) ", " (where :missing))
                                                    {:target k})))
                                  (when (contains? acc t)
                                    (throw (ex-info (str "define-class: " what " names " (pr-str t) " twice") {:target k})))
                                  (assoc acc t (annotations-for (str "on " (where t)) kind anns))))
                              (sorted-map) m))
        field-names (set (map first (concat fields sfields)))
        anns [(annotations-for "on the class" "TYPE" annotations)
              (member-anns ":field-annotations" field-annotations #(when (or (string? %) (keyword? %)) (name %))
                           field-names "FIELD"
                           #(if (= % :missing) "which isn't in :fields or :static-fields" (str "field " %)))
              (member-anns ":method-annotations" method-annotations method-key #(or (contains? fns %) (contains? sfns %))
                           "METHOD" #(if (= % :missing) "which isn't a key of :methods or :static-methods" (str "method " %)))
              (member-anns ":constructor-annotations" constructor-annotations
                           #(when (sequential? %) (ctor-desc %)) some? "CONSTRUCTOR"
                           #(if (= % :missing) "which isn't a vector of parameter types" (str "constructor " %)))]
        shape [(slashed extends) (mapv slashed implements) fields (sort (keys fns)) sfields (sort (keys sfns))
               ;; printed, so a NaN element compares equal to itself
               (some? sinit) ctors (some? cinit) (some? post) (pr-str anns)]
        scm-list (fn [kvs] (apply s/call "list" (map (fn [[k v]] (s/call "cons" k v)) kvs)))]
    (rethrow
     #(if-let [{old :shape cells :cells scells :scells icell :icell ccell :ccell pcell :pcell c :class}
               (get @jolt-classes dotted)]
        (if (= old shape)
          (do (doseq [[k f] fns] (s/call "set-box!" (get cells k) f))
              (doseq [[k f] sfns] (s/call "set-box!" (get scells k) f))
              (when sinit (s/call "set-box!" icell sinit))
              (when cinit (s/call "set-box!" ccell cinit))
              (when post (s/call "set-box!" pcell post))
              c)
          (throw (ex-info (str "define-class: " dotted " is already defined with another superclass, interfaces,"
                               " fields, method keys, static initializer, constructors or annotations; a class is defined once per process")
                          {:class dotted})))
        (let [boxes (fn [m] (into {} (map (fn [[k f]] [k (s/call "box" f)])) m))
              cells (boxes fns)
              scells (boxes sfns)
              icell (when sinit (s/call "box" sinit))
              ccell (when cinit (s/call "box" cinit))
              pcell (when post (s/call "box" post))
              c (s/call "dyn-define-class" cname extends (apply s/call "list" implements)
                        (scm-list fields) (scm-list cells) (scm-list sfields) (scm-list scells) (or icell false)
                        (if ctors (scm-list ctors) false) (or ccell false) (or pcell false)
                        (let [[canns fanns manns cnanns] anns
                              alist (fn [m] (scm-list (map (fn [[k v]] [k (apply s/call "list" (map scm-annotation v))]) m)))]
                          (s/call "list" (apply s/call "list" (map scm-annotation canns))
                                  (alist fanns) (alist manns) (alist cnanns))))]
          (swap! jolt-classes assoc dotted {:shape shape :cells cells :scells scells :icell icell
                                            :ccell ccell :pcell pcell :class c})
          c)))))

(defn invoke-super
  "From a fn of a class define-class made: call its superclass's method
  mname (public or protected) that fits args on obj, as super.mname(args)
  does in Java. A default method of one of the class's own interfaces is
  found when the superclass has no such method."
  [obj mname & args]
  (rethrow #(apply s/call "dyn-invoke-super" obj mname (map ->arg args))))
