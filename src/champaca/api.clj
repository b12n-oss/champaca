(ns champaca.api
  "Call Java from jolt, in-process. Same surface as jolt-openldk's
  net.b12n.jolt.openldk (init!, call-static, new-object, call, implement),
  but with no SBCL and no wire protocol: Java objects live in jolt's own
  Chez heap and calls are direct procedure calls.

  Class names use slashes (\"java/util/ArrayList\") and every call names
  its JVM descriptor, as in jolt-openldk."
  (:require [clojure.java.io :as io]
            [jolt.scheme :as s]
            [champaca.main :as main]))

(defonce ^:private interop-loaded (atom false))
(defonce ^:private dynamic-loaded (atom false))

(defn- rethrow
  "Turn the runtime's java-exception condition into an ex-info."
  [f]
  (try (f)
       (catch :default e
         (cond
           ;; System.exit from Java ends the process, as on a JVM
           (s/call "java-halt?" e)
           (System/exit (s/call "java-halt-status" e))
           (s/call "ldk-java-exception?" e)
           (throw (ex-info (s/call "ldk-exception-message" e)
                           {:java/exception (s/call "ldk-exception-object" e)}))
           (and @dynamic-loaded (s/call "ldk-api-error?" e))
           (throw (ex-info (s/call "ldk-api-error-message" e) {:champaca/error :lookup}))
           ;; the runtime's own records; jolt wraps real Chez errors itself
           (s/call "ldk-missing-native?" e)
           (throw (ex-info (s/call "ldk-condition-message" e) {:champaca/error :missing-native}))
           :else (throw e)))))

(defn init!
  "Boot the JDK. opts: {:classpath [\"dir\" \"lib.jar\" ...]} for user classes."
  ([] (init! {}))
  ([{:keys [classpath]}]
   (let [status (main/init! (or classpath []))]
     (when-not (zero? status) (throw (ex-info "JDK boot failed" {:status status}))))
   (when-not @interop-loaded
     (s/eval-string (slurp (io/resource "champaca/interop.ss")))
     (reset! interop-loaded true))
   (when-not @dynamic-loaded
     (s/eval-string (slurp (io/resource "champaca/dynamic.ss")))
     (s/call "set-lambda-class-hook!" (fn [iface sam-key] (main/lambda-class [iface] [sam-key])))
     (reset! dynamic-loaded true))
   :ok))

(defn call-static [cls mname desc & args]
  (main/ensure-loaded! cls)
  (rethrow #(apply s/call "ldk-call-static" cls mname desc args)))

(defn new-object [cls desc & args]
  (main/ensure-loaded! cls)
  (rethrow #(apply s/call "ldk-new" cls desc args)))

(defn call [obj mname desc & args]
  (rethrow #(apply s/call "ldk-call" obj mname desc args)))

(defn implement
  "A Java object implementing the single-method interface iface, whose
  method (mname, desc) calls the jolt fn f with converted arguments."
  [iface mname desc f]
  (s/call "ldk-implement" (main/lambda-class [iface] [(str mname desc)]) desc f))

(defn to-string [obj] (rethrow #(s/call "ldk-to-string" obj)))

(defn save-cache!
  "Add whatever JDK code this process compiled since boot to the startup
  cache, so the next process loads it instead of compiling it. Call it
  before exit; it is cheap when nothing new was compiled."
  []
  (main/save-generation!))

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

(defn get-static [cls fname] (rethrow #(s/call "dyn-get-static" cls fname)))
(defn get-field [obj fname] (rethrow #(s/call "dyn-get-field" obj fname)))

(defn ->clj
  "Copy Java data into jolt data, deeply: an array or a List (any Collection)
  becomes a vector, a Set a set, a Map a map, with strings and boxed numbers
  converted. Anything else is returned as is."
  [x]
  (cond
    (s/call "dyn-array?" x)
    (mapv #(->clj (s/call "dyn-aref" x %)) (range (s/call "dyn-alength" x)))
    (s/call "dyn-instance?" x "java/util/Map")
    (let [a (s/call "dyn-map-array" x)]
      (into {} (for [i (range 0 (s/call "dyn-alength" a) 2)]
                 [(->clj (s/call "dyn-aref" a i)) (->clj (s/call "dyn-aref" a (inc i)))])))
    (s/call "dyn-instance?" x "java/util/Set") (set (->clj (s/call "dyn-coll-array" x)))
    (s/call "dyn-instance?" x "java/util/Collection") (->clj (s/call "dyn-coll-array" x))
    :else x))

(defn ->java
  "Copy jolt data into Java collections, deeply: a map becomes a
  LinkedHashMap, a set a LinkedHashSet, a vector or seq an ArrayList, and
  strings, numbers and keywords (as strings) are converted as Objects."
  [x]
  (rethrow #(s/call "dyn->java" (->arg x))))
