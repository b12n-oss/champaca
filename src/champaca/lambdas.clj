;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.lambdas
  "Lambda sites and bootstrap constants: LambdaMetafactory calls, dynamic
  constants and the static arguments of compiled bootstraps."
  (:require [champaca.cgtext :refer [method-global num-lit parse-desc str-lit sx sym]]))

;; --- lambdas -----------------------------------------------------------------------
;; LambdaMetafactory call sites are not run through the JDK's bootstrap (that
;; needs MethodHandles and runtime class generation). The translator emits a
;; closure instead and the linker makes one small class per functional
;; interface whose SAM entry calls the closure stored in the object.

(defn- convert
  "Scheme expression converting v from stack type `from` to `to`, where
  each is a descriptor. Boxes, unboxes or widens; refs pass through."
  [from to v]
  (let [prim? #(contains? #{"Z" "B" "C" "S" "I" "J" "F" "D"} %)]
    (cond
      (= from to) v
      (and (prim? from) (prim? to))
      (if (and (#{"F" "D"} to) (not (#{"F" "D"} from))) (sx "inexact" v) v)
      (prim? from) (sx "jbox" (str-lit from) v)
      (prim? to) (sx "junbox" (str-lit to) v)
      :else v)))

(defn- impl-call
  "The call a lambda's body makes to its implementation method, by the method
  handle's kind (5 virtual, 6 static, 7 special, 8 new, 9 interface).
  Invoking a static or constructor reference initializes its class first, as
  the JVM's method handle does (JVMS 5.5), through a site cell like
  invokestatic's and new's. That also loads the class, which translating
  this method did in this process but may not have in the one running it
  from the startup cache. Unlike invokestatic, a reference to this class's
  own method checks too: the lambda can run on another thread while this
  class's <clinit> is still going, and that thread has to wait for it."
  [{:keys [resolve ic!]} kind {:keys [class name desc]} actuals]
  (case (int kind)
    6 (do ((:ensure-loaded resolve) class)
          (let [decl ((:resolve-method resolve) class name desc)]
            (sx "begin" (sx "jinit-ic" (ic!) (str "'" (sym decl)))
                (apply sx (method-global decl name desc) actuals))))
    7 (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) actuals)
    (5 9) (cond
            ;; a private or final method, or a final class's: the one method,
            ;; after the null check dispatch would do, as invokevirtual; javac
            ;; 25 references a private method (this::foo) as kind 5, and the
            ;; vtable keeps an inherited entry under its name
            (when (= kind 5) ((:direct-target resolve) class name desc))
            (let [decl ((:direct-target resolve) class name desc)]
              (apply sx (method-global decl name desc) (sx "nn" (first actuals)) (rest actuals)))
            ;; as invokevirtual of a package-private method (JVMS 5.4.5)
            (when (= kind 5) ((:package-private-decl resolve) class name desc))
            (let [pp ((:package-private-decl resolve) class name desc)]
              (apply sx (sx "package-private-target" (sx "nn" (first actuals)) (sx "symbol-class" (str "'" (sym pp))) (str-lit (str name desc))) actuals))
            :else
            (apply sx (sx "jvirt" (first actuals) (str "'" (sym (str name desc)))) actuals))
    8 (do ((:ensure-loaded resolve) class)
          (sx "let" (sx (sx "o" (sx "jnew-ic" (ic!) (str "'" (sym class)))))
              (apply sx (method-global class name desc) "o" actuals) "o"))))

(defn- lambda-body
  "The call's result as the interface method returns it."
  [sam-ret ret-type call]
  (cond (= sam-ret "V") (sx "begin" call "(void)")
        (= ret-type "V") (sx "begin" call "#f")
        :else (convert ret-type sam-ret call)))

(defn- metafactory-extras
  "[interfaces bridges] of a lambda: the functional interface, then
  altMetafactory's marker interfaces and Serializable (flags 2 and 1), and
  its bridge descriptors (flag 4)."
  [cp bsm iface]
  (let [[flags & more] (map #(let [e (cp %)] (or (:value e) (:name e) (:desc e))) (drop 3 (:args bsm)))
        flags (or flags 0)
        [markers more] (if (pos? (bit-and flags 2)) [(take (first more) (rest more)) (drop (inc (first more)) more)] [[] more])
        bridges (if (pos? (bit-and flags 4)) (take (first more) (rest more)) [])]
    [(cond-> (into [iface] markers) (pos? (bit-and flags 1)) (conj "java/io/Serializable")) bridges]))

(defn lambda-expr
  "A LambdaMetafactory call site as a jlambda of a class of its own per call
  site, as the JVM's: site holds the interface method's name, the interface
  descriptor, the captured values' expressions and the bootstrap entry."
  [{:keys [cls resolve method-key] :as e} pc {:keys [sam-name iface-desc captured bsm]}]
  (let [cp (:cp cls)
        [sam-mt impl-mh _inst-mt] (map cp (:args bsm))
        iface (subs iface-desc 1 (dec (count iface-desc)))
        sam-desc (:desc sam-mt)
        {sam-args :args sam-ret :ret} (parse-desc sam-desc)
        kind (:kind impl-mh)
        {:keys [class desc] :as target} (cp (:ref-idx impl-mh))
        {impl-args :args impl-ret :ret} (parse-desc desc)
        impl-params (if (#{5 7 9} kind) (cons (str "L" class ";") impl-args) impl-args)
        params (map #(str "p" %) (range (count sam-args)))
        cvars (map #(str "c" %) (range (count captured)))
        actuals (concat cvars
                        (map (fn [t p want] (convert t want p))
                             sam-args params (drop (count captured) impl-params)))
        call (impl-call e kind target actuals)
        body (lambda-body sam-ret (if (= kind 8) (str "L" class ";") impl-ret) call)
        [ifaces bridges] (metafactory-extras cp bsm iface)
        lambda-class ((:lambda-class resolve) ifaces (cons (str sam-name sam-desc) (map #(str sam-name %) bridges))
                      (:name cls) (str method-key "@" pc))]
    (sx "let" (apply sx (map sx cvars captured))
        (sx "jlambda" (str-lit lambda-class) (sx "lambda" (apply sx params) body)))))

;; --- bootstrap constants -------------------------------------------------------------
;; A constant-pool entry as a datum the runtime (handles.ss) turns into a Java
;; object: the static arguments of a bootstrap method, and the method handle,
;; method type and dynamic constants an ldc can load. Those three resolve once
;; per constant-pool entry, however many instructions and bootstrap arguments
;; name it (JVMS 5.4.3), so they come wrapped as (entry |Class#index| datum),
;; keyed by an interned symbol.

(defn const-datum [cls i]
  (let [cp (:cp cls)
        e (cp i)
        entry (fn [d] (sx "entry" (sym (str (:name cls) "#" i)) d))]
    (case (:tag e)
      :int (sx "int" (num-lit (:value e)))
      :long (sx "long" (num-lit (:value e)))
      :float (sx "float" (num-lit (:value e)))
      :double (sx "double" (num-lit (:value e)))
      :string (sx "string" (str-lit (:value e)))
      :class (sx "class" (str-lit (:name e)))
      :methodtype (entry (sx "methodtype" (str-lit (:desc e))))
      :methodhandle (let [{:keys [class name desc]} (cp (:ref-idx e))]
                      (entry (sx "mh" (str (:kind e)) (str-lit class) (str-lit name) (str-lit desc))))
      :dynamic (let [bsm (nth (:bootstrap-methods cls) (:bsm-idx e))]
                 (entry (sx "condy" (str-lit (:name e)) (str-lit (:desc e))
                            (const-datum cls (:ref-idx bsm))
                            (apply sx (map #(const-datum cls %) (:args bsm))))))
      (throw (ex-info "bootstrap constant not in spike" {:entry e :class (:name cls)})))))


(defn condy
  "A dynamic constant's bootstrap method name and static argument indexes."
  [cls e]
  (let [cp (:cp cls)
        bsm (nth (:bootstrap-methods cls) (:bsm-idx e))]
    [(:name (cp (:ref-idx (cp (:ref-idx bsm))))) (:args bsm)]))
