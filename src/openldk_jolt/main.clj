(ns openldk-jolt.main
  "Class loading, linking and the JIT hook, plus the command-line entry.

  Loading a class registers it with the Scheme runtime and defines every
  method as a stub. The first call to a stub calls back into jit!, which
  translates that one method and redefines the global in place. That mirrors
  OpenLDK, which also compiles each method on first call."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]
            [openldk-jolt.classfile :as cf]
            [openldk-jolt.codegen :as cg]
            [openldk-jolt.shims :as shims]))

(defonce ^:private state (atom {:classpath [] :classes {}}))

(defn- trace? [] (some? (System/getenv "OPENLDK_TRACE")))

(defn- scheme! [text]
  (when (trace?)
    (let [dir (io/file "target" "jit")]
      (.mkdirs dir)
      (spit (io/file dir "trace.ss") (str text "\n\n") :append true)))
  (s/eval-string text))

(declare ensure-loaded!)

(defn- class-info [cname]
  (or (get-in @state [:classes cname])
      (do (ensure-loaded! cname) (get-in @state [:classes cname]))))

;; --- resolution: walk the superclass chain (and interfaces for fields) ------

(defn- resolve-static-field [cname fname]
  (loop [todo [cname]]
    (when-let [c (first todo)]
      (let [info (class-info c)]
        (if (some #(and (:static %) (= fname (:name %))) (:fields info))
          c
          (recur (concat (rest todo) (remove nil? (cons (:super info) (:interfaces info))))))))))

(defn- field-index [cname fname]
  (loop [c cname]
    (if-let [info (class-info c)]
      (if (some #(and (not (:static %)) (= fname (:name %))) (:fields info))
        (get (:layout info) (str c "." fname))
        (recur (:super info)))
      (throw (ex-info "no such field" {:class cname :field fname})))))

(defn- resolve-method [cname mname desc]
  (loop [c cname]
    (if-let [info (and c (class-info c))]
      (if (some #(and (= mname (:name %)) (= desc (:desc %))) (:methods info))
        c
        (recur (:super info)))
      (throw (ex-info "no such method" {:class cname :method mname :desc desc})))))

(def ^:private resolver
  {:ensure-loaded #(ensure-loaded! %)
   :resolve-static-field resolve-static-field
   :field-index field-index
   :resolve-method resolve-method})

;; --- loading -------------------------------------------------------------------

(defn- find-classfile [cname]
  (some (fn [dir] (let [f (io/file dir (str cname ".class"))] (when (.exists f) (str f))))
        (:classpath @state)))

(defn- normalize
  "Turn a parsed classfile or a shim entry into the linker's class map."
  [cname]
  (if-let [shim (shims/classes cname)]
    (assoc shim :name cname :shim? true)
    (if-let [path (find-classfile cname)]
      (let [c (cf/parse-file path)]
        {:name cname :super (:super c) :interfaces (:interfaces c) :parsed c
         :fields (mapv (fn [f] {:name (:name f) :desc (:desc f) :static (cf/static? f)}) (:fields c))
         :methods (mapv (fn [m] (assoc m :virtual (not (or (cf/static? m)
                                                           (str/starts-with? (:name m) "<")))))
                        (:methods c))})
      (throw (ex-info (str "class not found (JDK classes outside the shims are not in the spike): " cname)
                      {:class cname})))))

(defn- link
  "Compute field layout, slot template and vtable from the (already linked)
  superclass."
  [info]
  (let [sup (when (:super info) (class-info (:super info)))
        own (remove :static (:fields info))
        base (count (:template sup []))
        layout (into (or (:layout sup) {})
                     (map-indexed (fn [i f] [(str (:name info) "." (:name f)) (+ base i)]) own))
        template (into (or (:template sup) []) (map #(cg/default-value (:desc %)) own))
        vtable (into (or (:vtable sup) {})
                     (for [m (:methods info)
                           :when (and (:virtual m) (or (:shim? info) (:code m)))]
                       [(str (:name m) (:desc m)) (str (or (:inherit-impl m) (:name info)) "." (:name m) (:desc m))]))]
    (assoc info :layout layout :template template :vtable vtable)))

(defn- registration-text [{:keys [name super interfaces template vtable fields methods shim?]}]
  (str/join
   "\n"
   (concat
    [(cg/sx "register-class!" (cg/str-lit name) (if super (cg/str-lit super) "#f")
            (str "'" (apply cg/sx (map cg/str-lit interfaces)))
            (apply cg/sx "vector" template)
            (str "'" (apply cg/sx (for [[k g] (sort vtable)] (str "(" (cg/str-lit k) " . " (cg/sym g) ")")))))]
    (when-not shim?
      (for [f fields :when (:static f)]
        (cg/sx "define" (cg/static-global name (:name f)) (cg/default-value (:desc f)))))
    (for [m methods :when (:inherit-impl m)]
      (cg/sx "define" (cg/method-global name (:name m) (:desc m))
             (cg/method-global (:inherit-impl m) (:name m) (:desc m))))
    (when-not shim?
      (for [m methods :when (:code m)]
        (let [g (cg/method-global name (:name m) (:desc m))]
          (cg/sx "define" g
                 (cg/sx "lambda" "args"
                        (cg/sx "jit!" (cg/str-lit name) (cg/str-lit (:name m)) (cg/str-lit (:desc m)))
                        (cg/sx "apply" g "args")))))))))

(defn ensure-loaded! [cname]
  (when-not (get-in @state [:classes cname])
    (let [info (normalize cname)]
      (doseq [dep (remove nil? (cons (:super info) (:interfaces info)))]
        (ensure-loaded! dep))
      (let [linked (link info)]
        (swap! state assoc-in [:classes cname] linked)
        (scheme! (registration-text linked)))))
  cname)

;; --- the JIT -------------------------------------------------------------------

(defn jit! [cname mname desc]
  (let [info (class-info cname)
        m (first (filter #(and (= mname (:name %)) (= desc (:desc %))) (:methods info)))]
    (when (trace?) (binding [*out* *err*] (println "jit" (str cname "." mname desc))))
    (scheme! (cg/compile-method (:parsed info) m resolver))))

(defn- boot! []
  (scheme! (slurp (io/resource "openldk_jolt/runtime.ss")))
  (scheme! "(define jit-hook #f) (define (set-jit-hook! f) (set! jit-hook f)) (define (jit! c m d) (jit-hook c m d))")
  (s/call "set-jit-hook!" jit!)
  ;; the runtime raises shimmed exceptions itself (jthrow), so they must be
  ;; registered before any Java code runs, not on first reference
  (run! ensure-loaded! (sort (keys shims/classes))))

(defn run
  "Run cname's main method with classes found under the classpath dirs.
  Returns the exit status."
  [classpath cname args]
  (swap! state assoc :classpath classpath)
  (boot!)
  (ensure-loaded! cname)
  (apply s/call "run-main" cname args))

(defn -main [& argv]
  (let [[cp cname & args] argv]
    (when-not cname
      (binding [*out* *err*] (println "usage: jolt -M -m openldk-jolt.main <classpath-dir[:dir...]> <MainClass> [args...]"))
      (System/exit 2))
    (System/exit (run (str/split cp #":") (str/replace cname "." "/") (vec args)))))
