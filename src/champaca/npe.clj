(ns champaca.npe
  "The JVM's helpful NullPointerException messages (JEP 358), from the
  failing method's bytecode, ported from HotSpot's ExceptionMessageBuilder
  (src/hotspot/share/interpreter/bytecodeUtils.cpp, JDK 25), quirks
  included, since the point is the same text.

  A simulated operand stack records, for each value, the bci of the
  instruction that pushed it. At the failing bci the null operand's slot
  names its producer, which is printed as a source-like expression: a
  local, a field, a static, a method's return value, an array element.
  Control-flow merges that disagree forget the producer, and then only the
  failed action is printed."
  (:require [champaca.codegen :as cg]
            [clojure.string :as str]))

(def ^:private max-detail 5)
(def ^:private max-entries 1000000)

;; --- types and the simulated stack ------------------------------------------
;; A slot is [bci type], bci nil when merged from different producers.

(defn- desc-type
  "Signature::basic_type."
  [d]
  (case (first d)
    \Z :boolean \B :byte \C :char \S :short \I :int \J :long \F :float
    \D :double \V :void \[ :array
    :object))

(defn- type-size [t] (case t (:long :double) 2 :void 0 1))

(defn- push-raw [st slot] (if (= :void (second slot)) st (update st :s conj slot)))
(defn- push [st slot] (if (= 2 (type-size (second slot))) (push-raw (push-raw st slot) slot) (push-raw st slot)))
(defn- pop-n [st n] (update st :s #(subvec % 0 (- (count %) n))))
(defn- slot-data [st k] (let [s (:s st)] (nth s (- (count s) 1 k))))

(defn- written [st i] (if (> i 63) st (update st :w bit-or (bit-shift-left 1 i))))
(defn- written? [st i] (or (> i 63) (not (zero? (bit-and (:w st) (bit-shift-left 1 i))))))

(defn- merge-slot [[ab at :as a] [bb bt]]
  (if (not= at bt)
    (if (and (#{:object :array} at) (#{:object :array} bt))
      [(when (= ab bb) ab) :object]
      [nil :conflict])
    (if (= ab bb) a [nil at])))

(defn- merge-stack [a b]
  ;; HotSpot asserts the sizes agree; a mismatch is a bug in a stack effect
  (when-not (= (count (:s a)) (count (:s b)))
    (throw (ex-info "simulated stacks of different sizes meet" {:sizes [(count (:s a)) (count (:s b))]})))
  {:s (mapv merge-slot (:s a) (:s b)) :w (bit-or (:w a) (:w b))})

;; --- what each instruction does to the stack ----------------------------------

(def ^:private push-type
  {:nop :void :aconst_null :object
   :iconst_m1 :int :iconst_0 :int :iconst_1 :int :iconst_2 :int :iconst_3 :int :iconst_4 :int :iconst_5 :int
   :lconst_0 :long :lconst_1 :long :fconst_0 :float :fconst_1 :float :fconst_2 :float
   :dconst_0 :double :dconst_1 :double :bipush :int :sipush :int
   :iload :int :lload :long :fload :float :dload :double :aload :object
   :iinc :void :new :object})

(def ^:private array-load-type
  {:iaload :int :laload :long :faload :float :daload :double :aaload :object
   :baload :byte :caload :char :saload :short})

(def ^:private store-size {:istore 1 :lstore 2 :fstore 1 :dstore 2 :astore 1})

(def ^:private pops
  {:iastore 3 :lastore 4 :fastore 3 :dastore 4 :aastore 3 :bastore 3 :castore 3 :sastore 3
   :pop 1 :pop2 2 :monitorenter 1 :monitorexit 1})

(def ^:private arith-type
  (merge (zipmap [:iadd :isub :imul :idiv :irem :iand :ior :ixor] (repeat :int))
         (zipmap [:ladd :lsub :lmul :ldiv :lrem :land :lor :lxor] (repeat :long))
         (zipmap [:fadd :fsub :fmul :fdiv :frem] (repeat :float))
         (zipmap [:dadd :dsub :dmul :ddiv :drem] (repeat :double))))

(def ^:private neg-type {:ineg :int :lneg :long :fneg :float :dneg :double})
(def ^:private shift-type {:ishl :int :ishr :int :iushr :int :lshl :long :lshr :long :lushr :long})

;; [slots popped, type pushed]
(def ^:private conversions
  {:i2l [1 :long] :i2f [1 :float] :i2d [1 :double] :f2i [1 :int] :f2l [1 :long] :f2d [1 :double]
   :i2b [1 :byte] :i2c [1 :char] :i2s [1 :short]
   :l2i [2 :int] :l2f [2 :float] :l2d [2 :double] :d2i [2 :int] :d2l [2 :long] :d2f [2 :float]
   :lcmp [4 :int] :fcmpl [2 :int] :fcmpg [2 :int] :dcmpl [4 :int] :dcmpg [4 :int]})

(def ^:private branch-pops
  {:ifeq 1 :ifne 1 :iflt 1 :ifge 1 :ifgt 1 :ifle 1 :ifnull 1 :ifnonnull 1
   :if_icmpeq 2 :if_icmpne 2 :if_icmplt 2 :if_icmpge 2 :if_icmpgt 2 :if_icmple 2
   :if_acmpeq 2 :if_acmpne 2})

(def ^:private return-pops {:ireturn 1 :lreturn 2 :freturn 1 :dreturn 2 :areturn 1 :return 0 :athrow 1})

(def ^:private invokes #{:invokevirtual :invokespecial :invokestatic :invokeinterface})

(defn- arg-slots [desc] (reduce + (map (comp type-size desc-type) (:args (cg/parse-desc desc)))))

(defn- step
  "ExceptionMessageBuilder::do_instruction's effect: {:stack :ended :dests},
  or nil for an instruction it doesn't know."
  [cp {:keys [op args pc]} st]
  (let [a0 (first args)
        out (fn [st & {:keys [ended dests]}] {:stack st :ended ended :dests dests})]
    (cond
      (contains? push-type op) (out (push st [pc (push-type op)]))
      (#{:ldc :ldc_w :ldc2_w} op)
      (out (if-let [t (case (:tag (cp a0))
                        (:class :string) :object
                        :int :int :long :long :float :float :double :double
                        nil)]
             (push st [pc t])
             st))
      (contains? array-load-type op) (out (push (pop-n st 2) [pc (array-load-type op)]))
      (contains? store-size op) (out (pop-n (written st a0) (store-size op)))
      (contains? pops op) (out (pop-n st (pops op)))
      (= :dup op) (out (push-raw st (slot-data st 0)))
      (= :dup_x1 op) (let [[t1 t2] (map #(slot-data st %) [0 1])]
                       (out (reduce push-raw (pop-n st 2) [t1 t2 t1])))
      (= :dup_x2 op) (let [[t1 t2 t3] (map #(slot-data st %) [0 1 2])]
                       (out (reduce push-raw (pop-n st 3) [t1 t3 t2 t1])))
      (= :dup2 op) (let [st (push-raw st (slot-data st 1))] (out (push-raw st (slot-data st 1))))
      (= :dup2_x1 op) (let [[t1 t2 t3] (map #(slot-data st %) [0 1 2])]
                        (out (reduce push-raw (pop-n st 3) [t2 t1 t3 t2 t1])))
      (= :dup2_x2 op) (let [[t1 t2 t3 t4] (map #(slot-data st %) [0 1 2 3])]
                        (out (reduce push-raw (pop-n st 4) [t2 t1 t4 t3 t2 t1])))
      (= :swap op) (let [[t1 t2] (map #(slot-data st %) [0 1])]
                     (out (push (push (pop-n st 2) t1) t2)))
      (contains? arith-type op) (let [t (arith-type op)] (out (push (pop-n st (* 2 (type-size t))) [pc t])))
      (contains? neg-type op) (let [t (neg-type op)] (out (push (pop-n st (type-size t)) [pc t])))
      (contains? shift-type op) (let [t (shift-type op)] (out (push (pop-n st (inc (type-size t))) [pc t])))
      (contains? conversions op) (let [[n t] (conversions op)] (out (push (pop-n st n) [pc t])))
      (contains? branch-pops op) (out (pop-n st (branch-pops op)) :dests [a0])
      (= :jsr op) (out (push st [pc :address]) :ended true :dests [a0])
      (= :ret op) (out st :ended true)
      (#{:tableswitch :lookupswitch} op)
      (out (pop-n st 1) :dests (cons (first args) (map second (second args))))
      (contains? return-pops op) (out (pop-n st (return-pops op)) :ended true)
      (= :getstatic op) (out (push st [pc (desc-type (:desc (cp a0)))]))
      (= :getfield op) (out (push (pop-n st 1) [pc (desc-type (:desc (cp a0)))]))
      (= :putstatic op) (out (pop-n st (type-size (desc-type (:desc (cp a0))))))
      (= :putfield op) (out (pop-n st (inc (type-size (desc-type (:desc (cp a0)))))))
      (or (invokes op) (= :invokedynamic op))
      (let [desc (:desc (cp a0))
            st (if (#{:invokestatic :invokedynamic} op) st (pop-n st 1))]
        (out (push (pop-n st (arg-slots desc)) [pc (desc-type (:ret (cg/parse-desc desc)))])))
      (#{:newarray :anewarray} op) (out (push (pop-n st 1) [pc :object]))
      (#{:instanceof :arraylength} op) (out (push (pop-n st 1) [pc :int]))
      (= :checkcast op) (out st)
      (= :multianewarray op) (out (push (pop-n st (second args)) [pc :object]))
      (#{:goto :goto_w} op) (out st :ended true :dests [a0])
      :else nil)))

(defn- analyze
  "The ExceptionMessageBuilder constructor: the simulated stack before each
  instruction, as a map pc -> {:s slots :w written-locals}. It stops early
  once the stack at bci is known, as HotSpot's does."
  [cp insns handlers bci]
  (let [stacks (volatile! (reduce (fn [m {:keys [handler]}]
                                    (if (contains? m handler) m (assoc m handler {:s [[handler :object]] :w 0})))
                                  {0 {:s [] :w 0}}
                                  handlers))
        entries (volatile! 0)
        merge! (fn [st target]
                 (let [old (get @stacks target)
                       st (if old (merge-stack st old) (do (vswap! entries + (count (:s st))) st))]
                   (vswap! stacks assoc target st)
                   st))]
    (loop [added true all false]
      (if (or all (not added))
        @stacks
        (let [r (reduce
                 (fn [{:keys [added] :as acc} {:keys [pc len] :as insn}]
                   (let [acc (if-let [st (get @stacks pc)]
                               (if-let [{:keys [stack ended dests]} (step cp insn st)]
                                 (let [targets (concat (when-not ended [(+ pc len)]) dests)
                                       added (or added (some #(not (contains? @stacks %)) targets))]
                                   ;; each merge folds the target's stack into
                                   ;; the one carried to the next target
                                   (reduce merge! stack targets)
                                   (assoc acc :added added))
                                 (assoc acc :all false :added false))
                               (assoc acc :all false))
                         next-pc (+ pc len)]
                     (cond (and (= bci next-pc) (contains? @stacks next-pc)) (reduced (assoc acc :all true))
                           (> @entries max-entries) (reduced (assoc acc :all true :added false))
                           :else acc)))
                 {:added false :all true}
                 insns)]
          (recur (:added r) (:all r)))))))

;; --- printing ------------------------------------------------------------------

(defn- external-type [t]
  (case (first t)
    \Z "boolean" \B "byte" \C "char" \S "short" \I "int" \J "long" \F "float" \D "double" \V "void"
    \[ (str (external-type (subs t 1)) "[]")
    \L (str/replace (subs t 1 (dec (count t))) "/" ".")))

(defn- klass-name [cname]
  (let [n (str/replace cname "/" ".")]
    (case n "java.lang.Object" "Object" "java.lang.String" "String" n)))

;; trim_well_known_class_names_from_signature: java.lang. dropped from a
;; parameter starting java.lang.Object or java.lang.String, so
;; java.lang.StringBuilder loses it too
(defn- trim-params [s]
  (str/replace s #"(^|, )java\.lang\.(Object|String)" (fn [[_ sep n]] (str sep n))))

(defn- method-name [{:keys [class name desc]}]
  (str (klass-name class) "." name "("
       (trim-params (str/join ", " (map external-type (:args (cg/parse-desc desc)))))
       ")"))

(defn- local-var [^StringBuilder sb {:keys [code flags desc]} bci slot param?]
  (if-let [v (some (fn [{:keys [start length index name]}]
                     (when (and (<= start bci) (< bci (+ start length)) (= index slot)) name))
                   (:locals code))]
    (.append sb v)
    (let [static (pos? (bit-and flags 0x0008))]
      (if (and (not static) (zero? slot) param?)
        (.append sb "this")
        (let [found (loop [ts (:args (cg/parse-desc desc)) curr (if static 0 1) i 1]
                      (when-let [t (first ts)]
                        (let [n (type-size (desc-type t))]
                          (if (and (>= slot curr) (< slot (+ curr n))) i (recur (rest ts) (+ curr n) (inc i))))))]
          (.append sb (if (and found param?) (str "<parameter" found ">") (str "<local" slot ">"))))))))

(defn- cause0
  "ExceptionMessageBuilder::print_NPE_cause0: the expression that produced
  the value in slot of the stack before bci."
  [{:keys [stacks insn-at cp method] :as ctx} ^StringBuilder sb bci slot detail inner prefix]
  (when (pos? detail)
    (when-let [st (get stacks bci)]
      (when (< slot (count (:s st)))
        (when-let [src (first (slot-data st slot))]
          (let [{:keys [op args]} (insn-at src)
                a0 (first args)]
            (when (and (= detail max-detail) prefix (not (invokes op)))
              (.append sb prefix))
            (case op
              (:iload :aload) (do (local-var sb method src a0 (not (written? st a0))) true)
              :aconst_null (do (.append sb "null") true)
              (:iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4 :iconst_5)
              (do (.append sb ({:iconst_m1 "-1" :iconst_0 "0" :iconst_1 "1" :iconst_2 "2"
                                :iconst_3 "3" :iconst_4 "4" :iconst_5 "5"} op))
                  true)
              :bipush (do (.append sb (str a0)) true)
              ;; printed unsigned, as HotSpot reads it
              :sipush (do (.append sb (str (bit-and a0 0xffff))) true)
              (:iaload :aaload)
              (do (when-not (cause0 ctx sb src 1 (dec detail) inner nil) (.append sb "<array>"))
                  (.append sb "[")
                  (when-not (cause0 ctx sb src 0 detail true nil) (.append sb "..."))
                  (.append sb "]")
                  true)
              :getstatic (let [{:keys [class name]} (cp a0)]
                           (.append sb (str (klass-name class) "." name))
                           true)
              :getfield (do (when (cause0 ctx sb src 0 (dec detail) inner nil) (.append sb "."))
                            (.append sb (:name (cp a0)))
                            true)
              (:invokevirtual :invokespecial :invokestatic :invokeinterface)
              (do (when (and (= detail max-detail) (not inner))
                    (.append sb " because the return value of \""))
                  (.append sb (method-name (cp a0)))
                  true)
              false)))))))

(def ^:private failed-array-action
  {:iaload "Cannot load from int array" :faload "Cannot load from float array"
   :aaload "Cannot load from object array" :baload "Cannot load from byte/boolean array"
   :caload "Cannot load from char array" :saload "Cannot load from short array"
   :laload "Cannot load from long array" :daload "Cannot load from double array"
   :iastore "Cannot store to int array" :fastore "Cannot store to float array"
   :aastore "Cannot store to object array" :bastore "Cannot store to byte/boolean array"
   :castore "Cannot store to char array" :sastore "Cannot store to short array"
   :lastore "Cannot store to long array" :dastore "Cannot store to double array"
   :arraylength "Cannot read the array length" :athrow "Cannot throw exception"
   :monitorenter "Cannot enter synchronized block" :monitorexit "Cannot exit synchronized block"})

(defn- failed-action [cp {:keys [op args]}]
  (or (failed-array-action op)
      (case op
        :getfield (str "Cannot read field \"" (:name (cp (first args))) "\"")
        :putfield (str "Cannot assign field \"" (:name (cp (first args))) "\"")
        (:invokevirtual :invokespecial :invokeinterface) (str "Cannot invoke \"" (method-name (cp (first args))) "\""))))

(defn- null-slot
  "get_NPE_null_slot: which stack slot held the null, :explicit for a
  constructor call (an NPE the program made itself), or nil."
  [cp {:keys [op args]}]
  (case op
    (:getfield :arraylength :athrow :monitorenter :monitorexit) 0
    (:iaload :faload :aaload :baload :caload :saload :laload :daload) 1
    (:iastore :fastore :aastore :bastore :castore :sastore) 2
    (:lastore :dastore) 3
    :putfield (type-size (desc-type (:desc (cp (first args)))))
    (:invokevirtual :invokespecial :invokeinterface)
    (let [{:keys [name desc]} (cp (first args))]
      (if (= "<init>" name) :explicit (arg-slots desc)))
    nil))

(defn message
  "The helpful message for an NPE at bci of method (a parsed classfile
  method; its :code has :locals when parsed with classfile/*locals*), or
  nil where the JVM gives none."
  [cp method bci]
  (let [{:keys [code handlers]} (:code method)
        insns (cg/decode code)
        by-pc (into {} (map (juxt :pc identity)) insns)]
    (when-let [insn (by-pc bci)]
      (let [slot (null-slot cp insn)]
        (when (integer? slot)
          (let [ctx {:stacks (analyze cp insns handlers bci) :insn-at by-pc :cp cp :method method}
                sb (StringBuilder. ^String (failed-action cp insn))]
            (when (cause0 ctx sb bci slot max-detail false " because \"")
              (.append sb "\" is null"))
            (str sb)))))))
