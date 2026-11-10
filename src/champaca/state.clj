;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.state
  "Shared state and helpers the other champaca namespaces build on: the
  class table and its counters, environment lookups, phase timing, Scheme
  evaluation, and the codec that packs class entries for the caches."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jolt.scheme :as s]))

(defonce state (atom {:classpath [] :classes {} :jitted 0}))

;; ensure-loaded! is in champaca.loader, which links classes with this
;; namespace's readers and which they call back to load a class missing from
;; the table; loader installs it when it loads, boot! checks it is there.
(defonce ensure-loaded-fn (atom nil))

(defn ensure-loaded!*
  "Load cname through champaca.loader's ensure-loaded!, which loader installs
  when it loads; a clear error when it hasn't (champaca.link used alone)."
  [cname]
  (if-let [f (deref ensure-loaded-fn)]
    (f cname)
    (throw (ex-info "champaca.state/ensure-loaded-fn is unset: require champaca.loader, which installs ensure-loaded!" {:class cname}))))

(defn env [k] (System/getenv k))
(defn trace? [] (some? (env "CHAMPACA_TRACE")))

;; CHAMPACA_PROF: exclusive (self) time per phase. A nested phase's time is
;; charged to it and taken out of its parent.
(def prof-stack (volatile! []))

(defn timed [k f]
  (if-not (env "CHAMPACA_PROF")
    (f)
    (let [t0 (System/nanoTime)]
      (vswap! prof-stack conj 0)
      (try (f)
           (finally
             (let [total (- (System/nanoTime) t0)
                   child (peek @prof-stack)]
               (vswap! prof-stack pop)
               (when (seq @prof-stack)
                 (vswap! prof-stack #(conj (pop %) (+ (peek %) total))))
               (swap! state update-in [:prof k] (fnil + 0) (- total child))))))))

(defn prof-report [label wall-ns]
  (let [p (:prof @state) ms #(/ (Math/round (/ % 1e4)) 100.0)
        accounted (reduce + (vals p))]
    (binding [*out* *err*]
      (println (str "champaca prof (" label ", cache " (name (or (:cache @state) :off)) "): wall " (ms wall-ns) " ms"))
      (doseq [[k v] (sort-by (comp - val) p)] (println (str "  " (name k) " " (ms v) " ms")))
      (println (str "  java+runtime (rest) " (ms (- wall-ns accounted)) " ms")))))

(defn scheme-trace!
  "Append text to target/jit/trace.ss (CHAMPACA_TRACE)."
  [text]
  (let [dir (io/file "target" "jit")]
    (.mkdirs dir)
    (spit (io/file dir "trace.ss") (str text "\n\n") :append true)))

(defn scheme!
  "Evaluate Scheme text: compiled by default, or :interpret for one-shot
  plumbing like class registration."
  ([text] (scheme! text :compile))
  ([text mode]
   (when (trace?) (scheme-trace! text))
   (if (= mode :interpret)
     (s/call "interpret-string" text)
     (s/eval-string text)))
  ;; a method's text, with inspector information only when lines says
  ;; (runtime.ss eval-method-text; the runtime's global setting is #t)
  ([text _mode lines]
   (when (trace?) (scheme-trace! text))
   (s/call "eval-method-text" text (boolean lines))))

;; The classes whose table entries a classpath class's link or method compile
;; reads, a volatile set while one runs: what its library cache entry depends
;; on (user-deps).
(def ^:dynamic *reads* nil)

(defn- pack
  "A class entry's edn text as UTF-8 bytes, the form the caches keep it in
  and the class table holds it in until thawed: a Chez string takes four
  bytes a character, and the JDK's entries alone run to 19 million
  characters, 78 MB as strings against 19 as bytes."
  [^String text]
  (.getBytes text "UTF-8"))
(defn packed? [x] (or (bytes? x) (string? x) (and (vector? x) (bytes? (first x)))))   ; a string: an entry made this run
;; An entry's text; a multi-part entry (packed-entry) answers its first part
(defn unpack ^String [x] (cond (vector? x) (unpack (first x)) (bytes? x) (String. ^bytes x "UTF-8") (nil? x) nil :else x))
(defn packed=
  "Whether two packed entries hold the same text (= on byte arrays is
  identity), every part of a multi-part entry."
  [a b]
  (and (some? a) (some? b)
       (= (if (vector? a) (mapv unpack a) [(unpack a)]) (if (vector? b) (mapv unpack b) [(unpack b)]))))

(def ^:private interned-strings (atom {}))
(defn- intern-string
  "One copy of each string the thawed entries hold: a method's key recurs in
  the vtable of every class that inherits it, and a descriptor in every
  method with that signature; read apart, each is its own string of four
  bytes a character."
  [^String s]
  (or (get @interned-strings s)
      (do (swap! interned-strings assoc s s) s)))
(defn intern-keys
  "m with its string keys interned (and its string values when values? says)."
  [m values?]
  (if (map? m)
    (persistent! (reduce-kv (fn [acc k v] (assoc! acc (if (string? k) (intern-string k) k) (if (and values? (string? v)) (intern-string v) v)))
                            (transient {}) m))
    m))
(defn intern-members [ms]
  (mapv #(cond-> % (string? (:name %)) (update :name intern-string) (string? (:desc %)) (update :desc intern-string)) ms))
(defn thaw-entry
  "A packed entry as its class map, the strings in the slots that hold them
  interned: the vtable's keys and values, the methods' and fields' names and
  descriptors, the interfaces, the layout's, dependencies' and cached
  methods' keys. Those slots, not a walk of the whole map: a postwalk with a
  lookup per string took 2.7 s over 7,400 entries where this takes 0.4."
  [e]
  (let [m (edn/read-string (unpack e))]
    (cond-> m
      ;; a multi-part entry keeps its vtable and its methods packed until
      ;; something reads them (class-vtable, class-methods): three quarters
      ;; of an entry's text, and a restored class's vtable is read only when
      ;; a subclass links or its registration is rebuilt, its methods when
      ;; a call to it is compiled, a subclass links or it is reflected on
      (and (vector? e) (second e)) (assoc :vtable-packed (second e))
      (and (vector? e) (nth e 2 nil)) (assoc :methods-packed (nth e 2))
      (:vtable m) (update :vtable intern-keys true)
      (:methods m) (update :methods intern-members)
      (:fields m) (update :fields intern-members)
      (:interfaces m) (update :interfaces #(mapv intern-string %))
      (:layout m) (update :layout intern-keys false)
      (:deps m) (update :deps intern-keys false)
      (:cached-methods m) (update :cached-methods intern-keys false))))

(defn packed-entry
  "A class map as edn text in UTF-8 bytes (pack), without bytecode: methods
  keep :code as a flag. Three parts, [map vtable methods], when the map has
  a vtable or methods (every linked class's): a thaw reads the map and
  keeps the other parts' bytes for class-vtable and class-methods, so a
  warm run never parses the vtables and methods of the classes it only
  restores, three quarters of the entries' text (spikes 73, 74). A part
  never thawed is written back as it was."
  [info]
  (let [vt (if (:vtable info) (pack (pr-str (:vtable info))) (:vtable-packed info))   ; live over kept bytes
        ms (if (contains? info :methods)
             (pack (pr-str (mapv #(cond-> % (:code %) (assoc :code true)) (:methods info))))
             (:methods-packed info))
        core (pack (pr-str (dissoc info :parsed :vtable :vtable-packed :methods :methods-packed)))]
    (if (or vt ms) [core vt ms] core)))

(defn strip-for-cache
  "The linker's class map as packed edn per class, JDK classes only, without
  bytecode. An entry this run never thawed is packed already and is kept as
  it is."
  [classes]
  (into {}
        (for [[k info] classes
              :let [s (if (packed? info)
                        info
                        ;; not a classpath class, not one a loader defined, not a
                        ;; lambda class over interfaces no later run can check
                        (when (and (= :jdk (:origin info)) (not (:no-cache info)))
                          (packed-entry info)))]
              :when s]
          [k s])))
