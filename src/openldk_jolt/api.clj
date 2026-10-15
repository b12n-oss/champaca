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

(defn- rethrow
  "Turn the runtime's java-exception condition into an ex-info."
  [f]
  (try (f)
       (catch :default e
         (if (s/call "ldk-java-exception?" e)
           (throw (ex-info (s/call "ldk-exception-message" e)
                           {:java/exception (s/call "ldk-exception-object" e)}))
           (throw e)))))

(defn init!
  "Boot the JDK. opts: {:classpath [\"dir\" ...]} for user classes."
  ([] (init! {}))
  ([{:keys [classpath]}]
   (let [status (main/init! (or classpath []))]
     (when-not (zero? status) (throw (ex-info "JDK boot failed" {:status status}))))
   (when-not @interop-loaded
     (s/eval-string (slurp (io/resource "openldk_jolt/interop.ss")))
     (reset! interop-loaded true))
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
