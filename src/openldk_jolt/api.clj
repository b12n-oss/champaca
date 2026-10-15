(ns openldk-jolt.api
  "Call Java from jolt, in-process. Same surface as jolt-openldk's
  net.b12n.jolt.openldk (init!, call-static, new-object, call, implement),
  but with no SBCL and no wire protocol: Java objects live in jolt's own
  Chez heap and calls are direct procedure calls.

  Class names use slashes (\"java/util/ArrayList\") and every call names
  its JVM descriptor, as in jolt-openldk."
  (:require [clojure.java.io :as io]
            [jolt.scheme :as s]
            [openldk-jolt.main :as main]))

(defonce ^:private interop-loaded (atom false))
(defonce ^:private dynamic-loaded (atom false))

(defn- rethrow
  "Turn the runtime's java-exception condition into an ex-info."
  [f]
  (try (f)
       (catch :default e
         (cond
           (s/call "ldk-java-exception?" e)
           (throw (ex-info (s/call "ldk-exception-message" e)
                           {:java/exception (s/call "ldk-exception-object" e)}))
           (and @dynamic-loaded (s/call "ldk-api-error?" e))
           (throw (ex-info (s/call "ldk-api-error-message" e) {:openldk/error :lookup}))
           ;; the runtime's own records; jolt wraps real Chez errors itself
           (s/call "ldk-missing-native?" e)
           (throw (ex-info (s/call "ldk-condition-message" e) {:openldk/error :missing-native}))
           :else (throw e)))))

(defn init!
  "Boot the JDK. opts: {:classpath [\"dir\" ...]} for user classes."
  ([] (init! {}))
  ([{:keys [classpath]}]
   (let [status (main/init! (or classpath []))]
     (when-not (zero? status) (throw (ex-info "JDK boot failed" {:status status}))))
   (when-not @interop-loaded
     (s/eval-string (slurp (io/resource "openldk_jolt/interop.ss")))
     (reset! interop-loaded true))
   (when-not @dynamic-loaded
     (s/eval-string (slurp (io/resource "openldk_jolt/dynamic.ss")))
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

(defn to-string [obj] (s/call "ldk-to-string" obj))

(defn save-cache!
  "Add whatever JDK code this process compiled since boot to the startup
  cache, so the next process loads it instead of compiling it. Call it
  before exit; it is cheap when nothing new was compiled."
  []
  (main/save-generation!))

(defn- ->arg
  "jolt vectors go to Scheme as lists, which the runtime turns into a Java
  array when the chosen parameter is an array (or a varargs tail)."
  [x]
  (if (vector? x) (apply s/call "list" (map ->arg x)) x))

;; --- descriptor-free calls ---------------------------------------------------------
;; Class names may use dots or slashes. The overload is chosen from the
;; runtime types of the arguments (see resources/openldk_jolt/dynamic.ss),
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
  "Copy a Java array into a jolt vector, converting elements (strings,
  boxed numbers) and nested arrays. Anything else is returned as is."
  [x]
  (if (s/call "dyn-array?" x)
    (mapv #(->clj (s/call "dyn-aref" x %)) (range (s/call "dyn-alength" x)))
    x))
