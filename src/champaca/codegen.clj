(ns champaca.codegen
  "Translate one method's bytecode into Chez Scheme source text.

  Shape of the output: locals are l0..lN and operand-stack slots are s0..sM,
  all mutable Scheme variables. Each basic block is a letrec-bound thunk and a
  jump is a tail call, which Chez compiles to a direct jump. A method with an
  exception table switches to trampoline mode instead: blocks return the next
  block rather than calling it, so a guard wrapped around a block covers that
  block and nothing it jumps to."
  (:require [clojure.string :as str]))

;; --- text helpers ------------------------------------------------------------

(defn sx [& parts] (str "(" (str/join " " (remove nil? parts)) ")"))

(defn sym
  "A Scheme symbol literal for a JVM-ish name, bar-quoted so / ( ) ; < > [ $
  all survive."
  [s]
  (str "|" s "|"))

(defn str-lit [s]
  (str "\""
       (apply str (map (fn [c]
                         (let [i (int c)]
                           (cond (= c \\) "\\\\"
                                 (= c \") "\\\""
                                 (< i 32) (str "\\x" (Integer/toHexString i) ";")
                                 :else (str c))))
                       s))
       "\""))

(defn num-lit [x]
  (cond
    (integer? x) (str x)
    (Double/isNaN x) "+nan.0"
    (Double/isInfinite x) (if (pos? x) "+inf.0" "-inf.0")
    :else (str x)))

(defn method-global [cls mname desc] (sym (str cls "." mname desc)))
(defn static-global [cls fname] (sym (str cls ":" fname)))

;; --- descriptors -------------------------------------------------------------

(defn- read-type [s i]
  (case (nth s i)
    \L (let [j (str/index-of s ";" i)] [(subs s i (inc j)) (inc j)])
    \[ (let [[t j] (read-type s (inc i))] [(str "[" t) j])
    [(str (nth s i)) (inc i)]))

(defn parse-desc
  "\"(IJLjava/lang/String;)V\" -> {:args [\"I\" \"J\" \"Ljava/lang/String;\"] :ret \"V\"}"
  [desc]
  (loop [i 1 args []]
    (if (= \) (nth desc i))
      {:args args :ret (subs desc (inc i))}
      (let [[t j] (read-type desc i)] (recur j (conj args t))))))

(defn wide? [t] (contains? #{"J" "D"} t))

(defn default-value [t]
  (case t ("F" "D") "0.0" ("I" "J" "S" "B" "C" "Z") "0" "#f"))

;; --- decoding ----------------------------------------------------------------

(def ^:private opcodes
  ;; byte -> [mnemonic operand-format]
  (merge
   (zipmap (range 0 16)
           (map #(vector % :none)
                [:nop :aconst_null :iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3
                 :iconst_4 :iconst_5 :lconst_0 :lconst_1 :fconst_0 :fconst_1 :fconst_2
                 :dconst_0 :dconst_1]))
   {16 [:bipush :s1] 17 [:sipush :s2] 18 [:ldc :u1] 19 [:ldc_w :u2] 20 [:ldc2_w :u2]
    21 [:iload :u1] 22 [:lload :u1] 23 [:fload :u1] 24 [:dload :u1] 25 [:aload :u1]
    54 [:istore :u1] 55 [:lstore :u1] 56 [:fstore :u1] 57 [:dstore :u1] 58 [:astore :u1]
    132 [:iinc :iinc] 167 [:goto :branch] 168 [:jsr :branch] 169 [:ret :u1]
    170 [:tableswitch :tableswitch] 171 [:lookupswitch :lookupswitch]
    178 [:getstatic :u2] 179 [:putstatic :u2] 180 [:getfield :u2] 181 [:putfield :u2]
    182 [:invokevirtual :u2] 183 [:invokespecial :u2] 184 [:invokestatic :u2]
    185 [:invokeinterface :iface] 186 [:invokedynamic :indy]
    187 [:new :u2] 188 [:newarray :u1] 189 [:anewarray :u2] 192 [:checkcast :u2]
    193 [:instanceof :u2] 196 [:wide :wide] 197 [:multianewarray :multi]
    198 [:ifnull :branch] 199 [:ifnonnull :branch] 200 [:goto_w :branch4]}
   ;; xload_n / xstore_n: the slot number is folded into the args
   (into {} (for [[base op] [[26 :iload] [30 :lload] [34 :fload] [38 :dload] [42 :aload]
                             [59 :istore] [63 :lstore] [67 :fstore] [71 :dstore] [75 :astore]]
                  n (range 4)]
              [(+ base n) [op [:implicit n]]]))
   (zipmap (range 46 54) (map #(vector % :none) [:iaload :laload :faload :daload :aaload :baload :caload :saload]))
   (zipmap (range 79 87) (map #(vector % :none) [:iastore :lastore :fastore :dastore :aastore :bastore :castore :sastore]))
   (zipmap (range 87 132)
           (map #(vector % :none)
                [:pop :pop2 :dup :dup_x1 :dup_x2 :dup2 :dup2_x1 :dup2_x2 :swap
                 :iadd :ladd :fadd :dadd :isub :lsub :fsub :dsub :imul :lmul :fmul :dmul
                 :idiv :ldiv :fdiv :ddiv :irem :lrem :frem :drem :ineg :lneg :fneg :dneg
                 :ishl :lshl :ishr :lshr :iushr :lushr :iand :land :ior :lor :ixor :lxor]))
   (zipmap (range 133 153)
           (map #(vector % :none)
                [:i2l :i2f :i2d :l2i :l2f :l2d :f2i :f2l :f2d :d2i :d2l :d2f :i2b :i2c :i2s
                 :lcmp :fcmpl :fcmpg :dcmpl :dcmpg]))
   (zipmap (range 153 167)
           (map #(vector % :branch)
                [:ifeq :ifne :iflt :ifge :ifgt :ifle :if_icmpeq :if_icmpne :if_icmplt
                 :if_icmpge :if_icmpgt :if_icmple :if_acmpeq :if_acmpne]))
   (zipmap (range 172 178) (map #(vector % :none) [:ireturn :lreturn :freturn :dreturn :areturn :return]))
   {190 [:arraylength :none] 191 [:athrow :none] 194 [:monitorenter :none] 195 [:monitorexit :none]}))

(defn- s16 [hi lo] (let [v (+ (* hi 256) lo)] (if (>= v 32768) (- v 65536) v)))
(defn- s32 [a b c d] (let [v (+ (* a 16777216) (* b 65536) (* c 256) d)]
                       (if (>= v 2147483648) (- v 4294967296) v)))

(defn decode
  "Code bytes -> vector of {:pc :op :args :len}."
  [code]
  (let [n (count code)
        b (fn [i] (nth code i))
        sb (fn [i] (let [v (b i)] (if (>= v 128) (- v 256) v)))
        u2 (fn [i] (+ (* (b i) 256) (b (inc i))))
        i32 (fn [i] (s32 (b i) (b (+ i 1)) (b (+ i 2)) (b (+ i 3))))]
    (loop [pc 0 out []]
      (if (>= pc n)
        out
        (let [[op fmt] (or (opcodes (b pc)) (throw (ex-info "unknown opcode" {:pc pc :byte (b pc)})))
              [args len]
              (cond
                (vector? fmt) [[(second fmt)] 1]
                :else
                (case fmt
                  :none [[] 1]
                  :u1 [[(b (inc pc))] 2]
                  :s1 [[(sb (inc pc))] 2]
                  :s2 [[(s16 (b (+ pc 1)) (b (+ pc 2)))] 3]
                  :u2 [[(u2 (inc pc))] 3]
                  :iinc [[(b (+ pc 1)) (sb (+ pc 2))] 3]
                  :branch [[(+ pc (s16 (b (+ pc 1)) (b (+ pc 2))))] 3]
                  :branch4 [[(+ pc (i32 (inc pc)))] 5]
                  :iface [[(u2 (inc pc))] 5]
                  :indy [[(u2 (inc pc))] 5]
                  :multi [[(u2 (inc pc)) (b (+ pc 3))] 4]
                  :wide (let [[op2] (opcodes (b (inc pc))) idx (u2 (+ pc 2))]
                          (if (= op2 :iinc)
                            [[idx (s16 (b (+ pc 4)) (b (+ pc 5)))] 6]
                            [[idx] 4]))
                  :tableswitch
                  (let [p (+ pc 1 (mod (- 3 (mod pc 4)) 4))
                        dflt (+ pc (i32 p)) lo (i32 (+ p 4)) hi (i32 (+ p 8))
                        cnt (inc (- hi lo))
                        targets (vec (for [k (range cnt)] [(+ lo k) (+ pc (i32 (+ p 12 (* 4 k))))]))]
                    [[dflt targets] (- (+ p 12 (* 4 cnt)) pc)])
                  :lookupswitch
                  (let [p (+ pc 1 (mod (- 3 (mod pc 4)) 4))
                        dflt (+ pc (i32 p)) cnt (i32 (+ p 4))
                        targets (vec (for [k (range cnt)]
                                       [(i32 (+ p 8 (* 8 k))) (+ pc (i32 (+ p 12 (* 8 k))))]))]
                    [[dflt targets] (- (+ p 8 (* 8 cnt)) pc)])))]
          (recur (+ pc len) (conj out {:pc pc :op (if (= op :wide) (first (opcodes (b (inc pc)))) op)
                                       :args args :len len})))))))

;; --- control flow ------------------------------------------------------------

(def ^:private branch-ops
  #{:ifeq :ifne :iflt :ifge :ifgt :ifle :if_icmpeq :if_icmpne :if_icmplt :if_icmpge
    :if_icmpgt :if_icmple :if_acmpeq :if_acmpne :ifnull :ifnonnull})
(def ^:private terminal-ops
  #{:goto :goto_w :ireturn :lreturn :freturn :dreturn :areturn :return :athrow
    :tableswitch :lookupswitch})

(defn- successors [{:keys [op args pc len]}]
  (cond
    (branch-ops op) [(first args) (+ pc len)]
    (#{:goto :goto_w} op) [(first args)]
    (#{:tableswitch :lookupswitch} op) (cons (first args) (map second (second args)))
    (terminal-ops op) []
    :else [(+ pc len)]))

(defn- leaders [insns handlers]
  (into (sorted-set 0)
        (concat
         (mapcat (fn [{:keys [op pc len] :as i}]
                   (when (or (branch-ops op) (terminal-ops op))
                     (cons (+ pc len) (successors i))))
                 insns)
         (mapcat (juxt :start :end :handler) handlers))))

(defn- blocks
  "pc -> vector of instructions, split at every leader."
  [insns handlers]
  (let [ls (leaders insns handlers)]
    (->> insns
         (partition-by (let [cur (volatile! -1)]
                         (fn [{:keys [pc]}] (when (ls pc) (vreset! cur pc)) @cur)))
         (map (fn [blk] [(:pc (first blk)) (vec blk)]))
         (into (sorted-map)))))

;; --- per-instruction emission ---------------------------------------------------

(def ^:dynamic *method-tag*
  "Class.name(desc) of the method being translated. Block labels carry it
  so a Chez stack frame names its Java method (see getCallerClass)."
  "")

(defn- label [pc] (sym (str *method-tag* "#B" pc)))

(defn desc->t
  "Stack type of a field or return descriptor: I J F D or A."
  [d]
  (case (first d) (\B \C \S \Z \I) "I" \J "J" \F "F" \D "D" "A"))

(defn- cat2? [t] (contains? #{"J" "D"} t))

(def ^:private newarray-elem {4 "Z" 5 "C" 6 "F" 7 "D" 8 "B" 9 "S" 10 "I" 11 "J"})

;; VarHandle's signature-polymorphic access methods (the native ones in
;; java.lang.invoke.VarHandle); a call site's descriptor is not a real method
(def ^:private varhandle-modes
  #{"get" "set" "getVolatile" "setVolatile" "getOpaque" "setOpaque" "getAcquire" "setRelease"
    "compareAndSet" "compareAndExchange" "compareAndExchangeAcquire" "compareAndExchangeRelease"
    "weakCompareAndSetPlain" "weakCompareAndSet" "weakCompareAndSetAcquire" "weakCompareAndSetRelease"
    "getAndSet" "getAndSetAcquire" "getAndSetRelease" "getAndAdd" "getAndAddAcquire" "getAndAddRelease"
    "getAndBitwiseOr" "getAndBitwiseOrAcquire" "getAndBitwiseOrRelease"
    "getAndBitwiseAnd" "getAndBitwiseAndAcquire" "getAndBitwiseAndRelease"
    "getAndBitwiseXor" "getAndBitwiseXorAcquire" "getAndBitwiseXorRelease"})

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

(defn- lambda-expr
  [{:keys [cls resolve]} sam-name iface-desc captured-types captured bsm]
  (let [cp (:cp cls)
        [sam-mt impl-mh _inst-mt] (map cp (:args bsm))
        iface (subs iface-desc 1 (dec (count iface-desc)))
        sam-desc (:desc sam-mt)
        {sam-args :args sam-ret :ret} (parse-desc sam-desc)
        kind (:kind impl-mh)
        {:keys [class name desc]} (cp (:ref-idx impl-mh))
        {impl-args :args impl-ret :ret} (parse-desc desc)
        ;; kinds: 5 virtual, 6 static, 7 special, 8 new, 9 interface
        impl-params (if (#{5 7 9} kind) (cons (str "L" class ";") impl-args) impl-args)
        params (map #(str "p" %) (range (count sam-args)))
        cvars (map #(str "c" %) (range (count captured)))
        actuals (concat cvars
                        (map (fn [t p want] (convert t want p))
                             sam-args params (drop (count captured) impl-params)))
        call (case (int kind)
               6 (do ((:ensure-loaded resolve) class)
                     (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) actuals))
               7 (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) actuals)
               (5 9) (apply sx (sx "jvirt" (first actuals) (str "'" (sym (str name desc)))) actuals)
               8 (do ((:ensure-loaded resolve) class)
                     (sx "let" (sx (sx "o" (sx "jnew-sym" (str "'" (sym class)))))
                         (apply sx (method-global class name desc) "o" actuals) "o")))
        ret-type (if (= kind 8) (str "L" class ";") impl-ret)
        body (cond (= sam-ret "V") (sx "begin" call "(void)")
                   (= ret-type "V") (sx "begin" call "#f")
                   :else (convert ret-type sam-ret call))
        ;; altMetafactory extras: flags, then optional markers and bridges
        [flags & more] (map #(let [e (cp %)] (or (:value e) (:name e) (:desc e))) (drop 3 (:args bsm)))
        flags (or flags 0)
        [markers more] (if (pos? (bit-and flags 2)) [(take (first more) (rest more)) (drop (inc (first more)) more)] [[] more])
        bridges (if (pos? (bit-and flags 4)) (take (first more) (rest more)) [])
        ifaces (cond-> (into [iface] markers) (pos? (bit-and flags 1)) (conj "java/io/Serializable"))
        lambda-class ((:lambda-class resolve) ifaces (cons (str sam-name sam-desc) (map #(str sam-name %) bridges)))]
    (sx "let" (apply sx (map sx cvars captured))
        (sx "jlambda" (str-lit lambda-class) (sx "lambda" (apply sx params) body)))))

;; --- bootstrap constants -------------------------------------------------------------
;; A constant-pool entry as a datum the runtime (handles.ss) turns into a Java
;; object: the static arguments of a bootstrap method, and the method handle,
;; method type and dynamic constants an ldc can load.

(defn- const-datum [cls i]
  (let [cp (:cp cls)
        e (cp i)]
    (case (:tag e)
      :int (sx "int" (num-lit (:value e)))
      :long (sx "long" (num-lit (:value e)))
      :float (sx "float" (num-lit (:value e)))
      :double (sx "double" (num-lit (:value e)))
      :string (sx "string" (str-lit (:value e)))
      :class (sx "class" (str-lit (:name e)))
      :methodtype (sx "methodtype" (str-lit (:desc e)))
      :methodhandle (let [{:keys [class name desc]} (cp (:ref-idx e))]
                      (sx "mh" (str (:kind e)) (str-lit class) (str-lit name) (str-lit desc)))
      :dynamic (let [bsm (nth (:bootstrap-methods cls) (:bsm-idx e))]
                 (sx "condy" (str-lit (:name e)) (str-lit (:desc e))
                     (const-datum cls (:ref-idx bsm))
                     (apply sx (map #(const-datum cls %) (:args bsm)))))
      (throw (ex-info "bootstrap constant not in spike" {:entry e :class (:name cls)})))))

;; --- pattern switches -----------------------------------------------------------------
;; typeSwitch and enumSwitch labels as a quoted list for the runtime's
;; jswitch-index (handles.ss): Class constants, Strings (enum constant names
;; under enumSwitch), Integers, and two dynamic constants javac makes:
;; ConstantBootstraps.primitiveClass for a primitive pattern, and an EnumDesc
;; for a qualified enum constant (`case Color.RED`) in a switch on another type.

(defn- condy
  "A dynamic constant's bootstrap method name and static argument indexes."
  [cls e]
  (let [cp (:cp cls)
        bsm (nth (:bootstrap-methods cls) (:bsm-idx e))]
    [(:name (cp (:ref-idx (cp (:ref-idx bsm))))) (:args bsm)]))

(defn- switch-labels [cls enum? arg-idxs]
  (let [cp (:cp cls)
        label (fn [i]
                (let [e (cp i)]
                  (case (:tag e)
                    :class (sx "class" (str-lit (:name e)))
                    :string (sx (if enum? "name" "string") (str-lit (:value e)))
                    :int (sx "int" (str (:value e)))
                    :dynamic (let [[bsm-name [mh & args]] (condy cls e)]
                               (cond
                                 (= bsm-name "primitiveClass") (sx "prim" (str-lit (:name e)))
                                 ;; a qualified enum constant: EnumDesc.of(ClassDesc.of(binary name), constant name)
                                 (and (= bsm-name "invoke") (= "of" (:name (cp (:ref-idx (cp mh)))))
                                      (= "java/lang/Enum$EnumDesc" (:class (cp (:ref-idx (cp mh))))))
                                 (let [[_ [_ cname]] (condy cls (cp (first args)))]
                                   (sx "enum" (str-lit (str/replace (:value (cp cname)) "." "/"))
                                       (str-lit (:value (cp (second args))))))
                                 :else (throw (ex-info (str "switch label not in spike: condy " bsm-name)
                                                       {:class (:name cls)}))))
                    (throw (ex-info (str "switch label not in spike: " (:tag e)) {:class (:name cls)})))))]
    (str "'" (apply sx (map label arg-idxs)))))

(defn- render-steps
  "A block's steps and its terminal expression as Scheme. Nested lets when
  mutable? is false: every store or push binds a fresh variable, shadowing
  the old one, so Chez can keep values in registers (an assigned variable is
  boxed even when no closure captures it). set! on method-wide variables
  otherwise, which the exception-handler trampoline needs."
  [steps term mutable?]
  (if mutable?
    (str "(begin "
         (str/join " " (for [[k a b] steps]
                         (case k
                           :bind (sx "set!" a b)
                           :pbind (let [temps (map #(str "t" %) (range (count a)))]
                                    (sx "let" (apply sx (map (fn [t [_ e]] (sx t e)) temps a))
                                        (apply sx "begin" (map (fn [t [v _]] (sx "set!" v t)) temps a))))
                           :do a)))
         " " term ")")
    (reduce (fn [inner [k a b]]
              (case k
                :bind (sx "let" (sx (sx a b)) inner)
                :pbind (sx "let" (apply sx (map (fn [[v e]] (sx v e)) a)) inner)
                :do (sx "begin" a inner)))
            term
            (reverse steps))))

(defn- emit-block
  "Emit one basic block. ctx carries :cls (the classfile map), :resolve (the
  linker callbacks), :tramp? and :next-pc. entry is the compile-time stack at
  block entry: a vector of types (I J F D A), one per operand-stack value.
  Returns {:code text :succ {pc entry-stack}}."
  [ctx blk entry]
  (let [{:keys [cls resolve tramp? next-pc live]} ctx
        cp (:cp cls)
        this-class (:name cls)
        st (volatile! entry)
        ;; steps, in order: [:bind var expr], [:pbind [[var expr] ...]] (one
        ;; parallel binding) or [:do expr]. A method without exception handlers
        ;; renders them as nested lets, so nothing is assigned; one with
        ;; handlers as set! on method-wide variables (render-steps)
        stmts (transient [])
        succ (volatile! {})
        s (fn [i] (str "s" i))
        bind! (fn [v expr] (conj! stmts [:bind v expr]))
        push! (fn [expr t] (bind! (s (count @st)) expr) (vswap! st conj t))
        pop! (fn [] (let [i (dec (count @st))] (vswap! st pop) (s i)))
        top-t (fn [] (peek @st))
        popn! (fn [n] (vec (reverse (repeatedly n pop!))))
        stmt! (fn [x] (conj! stmts [:do x]))
        start-pc (:pc (first blk))
        ;; a backward jump is where a loop can spin, so it is where a thread
        ;; lets another one run (jsafepoint, natives.ss)
        goto (fn [pc]
               (vswap! succ assoc pc @st)
               ;; a jump passes the live locals and the stack as arguments
               (let [j (if tramp?
                         (label pc)
                         (apply sx (label pc) (concat (map #(str "l" %) live) (map s (range (count @st))))))]
                 (if (<= pc start-pc) (sx "begin" "(when (fx> gil-ready 0) (jsafepoint))" j) j)))
        init (fn [c] (when (not= c this-class) (stmt! (sx "jinit-sym" (str "'" (sym c))))))
        bin (fn [f t] (let [b (pop!) a (pop!)] (push! (f a b) t)))
        un (fn [f t] (push! (f (pop!)) t))
        ;; copy the top k values to below the m values under them (dup family)
        shuffle! (fn [k m]
                   (let [n (count @st)
                         base (- n k m)
                         items (subvec @st base)
                         order (concat (range (- (count items) k) (count items)) (range (count items)))
                         slots (map #(s (+ base %)) (range (count items)))]
                     (conj! stmts [:pbind (map-indexed (fn [j src] [(s (+ base j)) (nth slots src)]) order)])
                     (vreset! st (into (subvec @st 0 base) (map items order)))))
        cmp0 {:ifeq "fx=" :ifne "fx<>" :iflt "fx<" :ifge "fx>=" :ifgt "fx>" :ifle "fx<="}
        cmp2 {:if_icmpeq "fx=" :if_icmpne "fx<>" :if_icmplt "fx<" :if_icmpge "fx>="
              :if_icmpgt "fx>" :if_icmple "fx<="}
        terminal (volatile! nil)
        branch (fn [test target fall] (vreset! terminal (sx "if" test (goto target) (goto fall))))]
    (doseq [{:keys [op args pc len]} blk]
      (let [a0 (first args)]
        (case op
          :nop nil
          :aconst_null (push! "#f" "A")
          (:iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4 :iconst_5)
          (push! (str ({:iconst_m1 -1 :iconst_0 0 :iconst_1 1 :iconst_2 2 :iconst_3 3
                        :iconst_4 4 :iconst_5 5} op)) "I")
          (:lconst_0 :lconst_1) (push! (if (= op :lconst_0) "0" "1") "J")
          (:fconst_0 :fconst_1 :fconst_2) (push! ({:fconst_0 "0.0" :fconst_1 "1.0" :fconst_2 "2.0"} op) "F")
          (:dconst_0 :dconst_1) (push! (if (= op :dconst_0) "0.0" "1.0") "D")
          (:bipush :sipush) (push! (str a0) "I")
          (:ldc :ldc_w :ldc2_w)
          (let [e (cp a0)]
            (case (:tag e)
              :int (push! (num-lit (:value e)) "I")
              :long (push! (num-lit (:value e)) "J")
              :float (push! (num-lit (:value e)) "F")
              :double (push! (num-lit (:value e)) "D")
              :string (push! (sx "jstring-lit" (str-lit (:value e))) "A")
              :class (push! (sx "jmirror-of" (str-lit (:name e))) "A")
              (:methodtype :methodhandle :dynamic)
              (let [d (if (= :dynamic (:tag e)) (:desc e) "Ljava/lang/Object;")]
                ;; resolved once per site, keyed by an interned symbol
                (push! (sx "jconst-at" (str "'" (sym (str *method-tag* "@" pc))) (str-lit this-class)
                           (str "'" (const-datum cls a0)) (str-lit d))
                       (desc->t d)))
              (throw (ex-info "ldc constant not in spike" {:entry e}))))
          :iload (push! (str "l" a0) "I")
          :lload (push! (str "l" a0) "J")
          :fload (push! (str "l" a0) "F")
          :dload (push! (str "l" a0) "D")
          :aload (push! (str "l" a0) "A")
          (:istore :lstore :fstore :dstore :astore) (bind! (str "l" a0) (pop!))
          :iinc (bind! (str "l" a0) (sx "wrap-int" (sx "fx+" (str "l" a0) (str (second args)))))
          (:iaload :baload :caload :saload) (bin (fn [a i] (sx "array-ref" a i)) "I")
          :laload (bin (fn [a i] (sx "array-ref" a i)) "J")
          :faload (bin (fn [a i] (sx "array-ref" a i)) "F")
          :daload (bin (fn [a i] (sx "array-ref" a i)) "D")
          :aaload (bin (fn [a i] (sx "array-ref" a i)) "A")
          (:iastore :lastore :fastore :dastore :aastore :bastore :castore :sastore)
          (let [[a i v] (popn! 3)]
            (stmt! (sx "array-set!" a i (case op :bastore (sx "wrap-byte" v) :castore (sx "fxlogand" v "#xffff")
                                       :sastore (sx "wrap-short" v) v))))
          :pop (pop!)
          :pop2 (if (cat2? (top-t)) (pop!) (do (pop!) (pop!)))
          :dup (shuffle! 1 0)
          :dup_x1 (shuffle! 1 1)
          :dup_x2 (shuffle! 1 (if (cat2? (nth @st (- (count @st) 2))) 1 2))
          :dup2 (shuffle! (if (cat2? (top-t)) 1 2) 0)
          :dup2_x1 (shuffle! (if (cat2? (top-t)) 1 2) 1)
          :dup2_x2 (let [k (if (cat2? (top-t)) 1 2)]
                     (shuffle! k (if (cat2? (nth @st (- (count @st) k 1))) 1 2)))
          :swap (let [t1 (top-t) v1 (pop!) t2 (top-t) v2 (pop!)]
                  (conj! stmts [:pbind [[v1 v2] [v2 v1]]])
                  (vswap! st conj t1 t2))
          :iadd (bin #(sx "wrap-int" (sx "fx+" %1 %2)) "I")
          :isub (bin #(sx "wrap-int" (sx "fx-" %1 %2)) "I")
          :imul (bin #(sx "wrap-int" (sx "*" %1 %2)) "I")
          :idiv (bin #(sx "idiv" %1 %2) "I")
          :irem (bin #(sx "irem" %1 %2) "I")
          :ineg (un #(sx "wrap-int" (sx "fx-" "0" %)) "I")
          :ishl (bin #(sx "int-shl" %1 %2) "I")
          :ishr (bin #(sx "int-shr" %1 %2) "I")
          :iushr (bin #(sx "int-ushr" %1 %2) "I")
          :iand (bin #(sx "fxlogand" %1 %2) "I")
          :ior (bin #(sx "fxlogor" %1 %2) "I")
          :ixor (bin #(sx "fxlogxor" %1 %2) "I")
          :ladd (bin #(sx "wrap-long" (sx "+" %1 %2)) "J")
          :lsub (bin #(sx "wrap-long" (sx "-" %1 %2)) "J")
          :lmul (bin #(sx "wrap-long" (sx "*" %1 %2)) "J")
          :ldiv (bin #(sx "ldiv" %1 %2) "J")
          :lrem (bin #(sx "lrem" %1 %2) "J")
          :lneg (un #(sx "wrap-long" (sx "-" %)) "J")
          :lshl (bin #(sx "lshl" %1 %2) "J")
          :lshr (bin #(sx "lshr" %1 %2) "J")
          :lushr (bin #(sx "lushr" %1 %2) "J")
          :land (bin #(sx "logand" %1 %2) "J")
          :lor (bin #(sx "logor" %1 %2) "J")
          :lxor (bin #(sx "logxor" %1 %2) "J")
          :lcmp (bin #(sx "lcmp" %1 %2) "I")
          (:dadd :dsub :dmul :ddiv)
          (bin #(sx ({:dadd "fl+" :dsub "fl-" :dmul "fl*" :ddiv "fl/"} op) %1 %2) "D")
          (:fadd :fsub :fmul :fdiv)
          (bin #(sx "f32" (sx ({:fadd "fl+" :fsub "fl-" :fmul "fl*" :fdiv "fl/"} op) %1 %2)) "F")
          :drem (bin #(sx "jfrem" %1 %2) "D")
          :frem (bin #(sx "f32" (sx "jfrem" %1 %2)) "F")
          :dneg (un #(sx "fl*" "-1.0" %) "D")
          :fneg (un #(sx "fl*" "-1.0" %) "F")
          (:fcmpl :dcmpl) (bin #(sx "jfcmp" %1 %2 "-1") "I")
          (:fcmpg :dcmpg) (bin #(sx "jfcmp" %1 %2 "1") "I")
          :i2l (un identity "J")
          :l2i (un #(sx "wrap-int" %) "I")
          :i2d (un #(sx "fixnum->flonum" %) "D")
          :l2d (un #(sx "inexact" %) "D")
          :i2f (un #(sx "f32" (sx "fixnum->flonum" %)) "F")
          :l2f (un #(sx "f32" (sx "inexact" %)) "F")
          :f2d (un identity "D")
          :d2f (un #(sx "f32" %) "F")
          (:f2i :d2i) (un #(sx "d2i" %) "I")
          (:f2l :d2l) (un #(sx "d2l" %) "J")
          :i2b (un #(sx "wrap-byte" %) "I")
          :i2c (un #(sx "fxlogand" % "#xffff") "I")
          :i2s (un #(sx "wrap-short" %) "I")
          (:ifeq :ifne :iflt :ifge :ifgt :ifle)
          (let [a (pop!)] (branch (sx (cmp0 op) a "0") a0 (+ pc len)))
          (:if_icmpeq :if_icmpne :if_icmplt :if_icmpge :if_icmpgt :if_icmple)
          (let [b (pop!) a (pop!)] (branch (sx (cmp2 op) a b) a0 (+ pc len)))
          (:if_acmpeq :if_acmpne)
          (let [b (pop!) a (pop!) t (sx "eq?" a b)]
            (branch (if (= op :if_acmpeq) t (sx "not" t)) a0 (+ pc len)))
          (:ifnull :ifnonnull)
          (let [a (pop!)] (branch (if (= op :ifnull) (sx "not" a) a) a0 (+ pc len)))
          (:goto :goto_w) (vreset! terminal (goto a0))
          (:tableswitch :lookupswitch)
          (let [k (pop!) [dflt targets] args]
            (vreset! terminal
                     (apply sx "case" k
                            (concat (for [[v t] targets] (sx (sx (str v)) (goto t)))
                                    [(sx "else" (goto dflt))]))))
          (:ireturn :lreturn :freturn :dreturn :areturn) (vreset! terminal (pop!))
          :return (vreset! terminal "(void)")
          :athrow (vreset! terminal (sx "jathrow" (pop!)))
          :monitorenter (stmt! (sx "jmonitor-enter" (pop!)))
          :monitorexit (stmt! (sx "jmonitor-exit" (pop!)))
          :getstatic (let [{:keys [class name desc]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (push! (static-global decl name) (desc->t desc)))
          :putstatic (let [{:keys [class name]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (stmt! (sx "set!" (static-global decl name) (pop!))))
          :getfield (let [{:keys [class name desc]} (cp a0)
                          idx ((:field-index resolve) class name)]
                      (un #(sx "field-ref" % (str idx)) (desc->t desc)))
          :putfield (let [{:keys [class name]} (cp a0)
                          idx ((:field-index resolve) class name)
                          v (pop!) o (pop!)]
                      (stmt! (sx "field-set!" o (str idx) v)))
          (:invokevirtual :invokeinterface :invokespecial :invokestatic)
          (let [{:keys [class name desc]} (cp a0)
                {:keys [args ret]} (parse-desc desc)
                argv (popn! (count args))
                recv (when-not (= op :invokestatic) (pop!))
                call (case op
                       (:invokevirtual :invokeinterface)
                       (cond
                         (and (= class "java/lang/invoke/VarHandle") (varhandle-modes name))
                         ;; signature-polymorphic: the runtime decodes the call site
                         (apply sx "vh-call" recv (str-lit name) argv)
                         (and (= class "java/lang/invoke/MethodHandle") (#{"invokeExact" "invoke"} name))
                         (apply sx "mh-call" recv (str-lit desc) argv)
                         :else
                         (do (when-not (str/starts-with? class "[") ((:ensure-loaded resolve) class))
                             (apply sx (sx "jvirt-ic" ((:ic! ctx)) recv (str "'" (sym (str name desc)))) recv argv)))
                       :invokespecial
                       (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) recv argv)
                       :invokestatic
                       (let [decl ((:resolve-method resolve) class name desc)]
                         (init decl)
                         (apply sx (method-global decl name desc) argv)))]
            (if (= ret "V") (stmt! call) (push! call (desc->t ret))))
          :invokedynamic
          (let [{:keys [bsm-idx name desc]} (cp a0)
                {:keys [args ret]} (parse-desc desc)
                bsm (nth (:bootstrap-methods cls) bsm-idx)
                bsm-ref (cp (:ref-idx (cp (:ref-idx bsm))))
                bsm-name (:name bsm-ref)
                ;; a bootstrap of the same name in another class runs for real
                bsm-name (if (and (#{"typeSwitch" "enumSwitch"} bsm-name)
                                  (not= (:class bsm-ref) "java/lang/runtime/SwitchBootstraps"))
                           (str (:class bsm-ref) "." bsm-name)
                           bsm-name)
                argv (popn! (count args))]
            (case bsm-name
              "makeConcatWithConstants"
              (let [recipe (:value (cp (first (:args bsm))))
                    consts (map #(let [e (cp %)] (str-lit (str (:value e)))) (rest (:args bsm)))
                    conv (map (fn [t v]
                                (case t
                                  ("I" "S" "B" "J") (sx "number->string" v)
                                  "F" (sx "java-float->string" v)
                                  "D" (sx "jdouble->string" v)
                                  "C" (sx "string" (sx "integer->char" v))
                                  "Z" (sx "jbool->string" v)
                                  (sx "jstring-of" v)))
                              args argv)]
                (push! (sx "jconcat" (str-lit recipe) (apply sx "list" conv) (apply sx "list" consts)) "A"))
              "bootstrap" ; java.lang.runtime.ObjectMethods: record toString/equals/hashCode
              (let [[_rec names-idx & getters] (:args bsm)
                    fields (for [g getters :let [{:keys [class name desc]} (cp (:ref-idx (cp g)))]]
                             (sx "list" (str-lit name) (str ((:field-index resolve) class name)) (str-lit desc)))
                    rec-class (:name (cp (first (:args bsm))))
                    simple (last (str/split rec-class #"[/$]"))]
                (push! (apply sx "jrecord-op" (str-lit name) (str-lit simple)
                              (str "(list " (str/join " " fields) ")") argv)
                       (desc->t ret)))
              ("metafactory" "altMetafactory")
              (push! (lambda-expr ctx name ret args argv bsm) "A")
              ("typeSwitch" "enumSwitch") ; java.lang.runtime.SwitchBootstraps: pattern switches
              (push! (sx "jswitch-index" (first argv) (second argv) (switch-labels cls (= bsm-name "enumSwitch") (:args bsm))) "I")
              ;; any other bootstrap runs for real, once per call site (handles.ss)
              (let [call (apply sx "indy-call" (str "'" (sym (str *method-tag* "@" pc))) (str-lit this-class)
                                (str "'" (sx (const-datum cls (:ref-idx bsm))
                                             (apply sx (map #(const-datum cls %) (:args bsm)))))
                                (str-lit name) (str-lit desc) argv)]
                (if (= ret "V") (stmt! call) (push! call (desc->t ret))))))
          :new (let [c (:name (cp a0))]
                 ((:ensure-loaded resolve) c)
                 (init c)
                 (push! (sx "jnew-sym" (str "'" (sym c))) "A"))
          :newarray (let [e (newarray-elem a0)]
                      (un #(sx "jnewarray" (str-lit e) % (if (#{"F" "D"} e) "0.0" "0")) "A"))
          :anewarray (let [c (:name (cp a0))
                           e (if (str/starts-with? c "[") c (str "L" c ";"))]
                       (un #(sx "jnewarray" (str-lit e) % "#f") "A"))
          :multianewarray (let [c (:name (cp a0)) dims (popn! (second args))]
                            (push! (sx "jmultiarray" (str-lit c) (apply sx "list" dims)) "A"))
          :arraylength (un #(sx "array-length" %) "I")
          :checkcast (let [c (:name (cp a0))]
                       (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                       (let [v (s (dec (count @st)))] (bind! v (sx "jcheckcast" v (str-lit c)))))
          :instanceof (let [c (:name (cp a0))]
                        (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                        (un #(sx "if" (sx "jinstance?" % (str-lit c)) "1" "0") "I"))
          (throw (ex-info (str "opcode not in spike: " (name op)) {:op op :pc pc :class this-class})))))
    (let [term (or @terminal (goto next-pc))]
      {:code (render-steps (persistent! stmts) term tramp?)
       :succ @succ})))

(defn- guard-wrap
  "Wrap a block's code in one guard whose clauses follow the exception table
  order, for every handler whose range covers the block."
  [code pc handlers]
  (let [covering (filter #(and (<= (:start %) pc) (< pc (:end %))) handlers)]
    (if (empty? covering)
      code
      (sx "guard"
          (apply sx "e"
                 (concat
                  (for [{:keys [type handler]} covering]
                    (sx (if type (sx "jinstance?" "e" (str-lit type)) (sx "jobj?" "e"))
                        (sx "set!" "s0" "e")
                        (label handler)))
                  ;; no handler here: raise again from this guard. Without
                  ;; this clause guard re-raises in the thrower's dynamic
                  ;; context, re-entering any dynamic-wind on the way, so a
                  ;; synchronized method between them would take its monitor
                  ;; again.
                  [(sx "else" (sx "raise" "e"))]))
          code))))

(declare compile-method*)

(defn compile-method
  "Scheme source for `(define |Cls.name(desc)| (lambda ...))`."
  [cls method resolve]
  (binding [*method-tag* (str (:name cls) "." (:name method) (:desc method))]
    (compile-method* cls method resolve)))

(defn- compile-method*
  [cls method resolve]
  (let [{:keys [max-stack max-locals code handlers]} (:code method)
        insns (decode code)
        blks (blocks insns handlers)
        tramp? (boolean (seq handlers))
        ;; inline-cache cells, one per virtual call site, made once per method
        ics (volatile! 0)
        ic! (fn [] (str "ic" (dec (vswap! ics inc))))
        next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
        {:keys [args]} (parse-desc (:desc method))
        arg-types (if (pos? (bit-and (:flags method) 0x0008)) args (cons "L" args))
        params (loop [ts arg-types slot 0 out []]
                 (if-let [t (first ts)]
                   (recur (rest ts) (+ slot (if (wide? t) 2 1)) (conj out slot))
                   out))
        param-set (set params)
        ;; the locals a jump has to carry: the parameters and every slot the
        ;; method loads or stores
        live (vec (sort (into param-set
                              (for [{:keys [op args]} insns
                                    :when (#{:iload :lload :fload :dload :aload
                                             :istore :lstore :fstore :dstore :astore :iinc} op)]
                                (first args)))))
        ;; worklist over blocks: each is emitted once, at the stack depth its
        ;; first predecessor established (verified bytecode guarantees agreement)
        emitted (loop [work (into [[0 []]] (map (fn [h] [(:handler h) ["A"]]) handlers))
                       done {}]
                  (if-let [[pc dep] (first work)]
                    (if (contains? done pc)
                      (recur (rest work) done)
                      (let [{:keys [code succ]} (emit-block {:cls cls :resolve resolve :tramp? tramp?
                                                             :next-pc (next-pc pc) :live live :ic! ic!}
                                                            (blks pc) dep)]
                        (recur (concat (rest work) succ) (assoc done pc [dep code]))))
                    done))
        bindings (concat (for [i (range max-locals) :when (not (param-set i))] (sx (str "l" i) "0"))
                         (for [i (range max-stack)] (sx (str "s" i) "0")))]
    (let [body* (if tramp?
                  (sx "let" (apply sx bindings)
                   (sx "letrec"
                       (apply sx (for [[pc [_ code]] (sort-by key emitted)]
                                   (sx (label pc) (sx "lambda" "()" (guard-wrap code pc handlers)))))
                         ;; an inline named loop rather than a shared helper,
                         ;; so a frame named for this method stays on the stack
                         ;; while a block runs under guard (getCallerClass)
                         (let [t (sym (str *method-tag* "#T"))]
                           (sx "let" t (sx (sx "r" (sx (label 0))))
                               (sx "if" (sx "procedure?" "r") (sx t (sx "r")) "r")))))
                  ;; each block takes the live locals and its entry stack
                  (sx "letrec"
                      (apply sx (for [[pc [dep code]] (sort-by key emitted)]
                                  (sx (label pc)
                                      (sx "lambda" (apply sx (concat (map #(str "l" %) live)
                                                                     (map #(str "s" %) (range (count dep)))))
                                          code))))
                      (apply sx (label 0) (map #(if (param-set %) (str "l" %) "0") live))))
          ;; ACC_SYNCHRONIZED: the monitor of this, or of the class for a
          ;; static method, held for the call and released however it ends.
          ;; dynamic-wind rather than guard: an exception leaves through a
          ;; handler further out, whose escape runs the exit before that
          ;; handler's clauses, and installing a guard costs about four times
          ;; as much. The blocks are named, so this method's frames stay
          ;; recognizable on the stack (getCallerClass).
          body (if (pos? (bit-and (:flags method) 0x0020))
                 (let [lock (if (pos? (bit-and (:flags method) 0x0008))
                              (sx "jmirror-of" (str-lit (:name cls)))
                              "l0")]
                   (sx "let" (sx (sx "lock" lock))
                       (sx "dynamic-wind"
                           (sx "lambda" "()" (sx "jmonitor-enter" "lock"))
                           (sx "lambda" "()" body*)
                           (sx "lambda" "()" (sx "jmonitor-exit" "lock")))))
                 body*)]
      (sx "define" (method-global (:name cls) (:name method) (:desc method))
          (let [f (sx "lambda" (apply sx (map #(str "l" %) params))
                      ;; CHAMPACA_BT: keep a shadow stack of Java frames for backtraces
                      (if (System/getenv "CHAMPACA_BT")
                        (sx "fluid-let" (sx (sx "jstack" (sx "cons" (str-lit (str (:name cls) "." (:name method) (:desc method))) "jstack")))
                            body)
                        body))]
            (if (pos? @ics)
              (sx "let" (apply sx (for [i (range @ics)] (sx (str "ic" i) "(cons #f #f)"))) f)
              f))))))
