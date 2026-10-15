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
               (5 9) (apply sx (sx "jvirt" (first actuals) (str-lit (str name desc))) actuals)
               8 (do ((:ensure-loaded resolve) class)
                     (sx "let" (sx (sx "o" (sx "jnew" (str-lit class))))
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

(defn- emit-block
  "Emit one basic block. ctx carries :cls (the classfile map), :resolve (the
  linker callbacks), :tramp? and :next-pc. entry is the compile-time stack at
  block entry: a vector of types (I J F D A), one per operand-stack value.
  Returns {:code text :succ {pc entry-stack}}."
  [ctx blk entry]
  (let [{:keys [cls resolve tramp? next-pc]} ctx
        cp (:cp cls)
        this-class (:name cls)
        st (volatile! entry)
        stmts (transient [])
        succ (volatile! {})
        s (fn [i] (str "s" i))
        push! (fn [expr t] (conj! stmts (sx "set!" (s (count @st)) expr)) (vswap! st conj t))
        pop! (fn [] (let [i (dec (count @st))] (vswap! st pop) (s i)))
        top-t (fn [] (peek @st))
        popn! (fn [n] (vec (reverse (repeatedly n pop!))))
        stmt! (fn [x] (conj! stmts x))
        goto (fn [pc] (vswap! succ assoc pc @st) (if tramp? (label pc) (sx (label pc))))
        init (fn [c] (when (not= c this-class) (stmt! (sx "jinit" (str-lit c)))))
        bin (fn [f t] (let [b (pop!) a (pop!)] (push! (f a b) t)))
        un (fn [f t] (push! (f (pop!)) t))
        ;; copy the top k values to below the m values under them (dup family)
        shuffle! (fn [k m]
                   (let [n (count @st)
                         base (- n k m)
                         items (subvec @st base)
                         order (concat (range (- (count items) k) (count items)) (range (count items)))
                         temps (map #(str "t" %) (range (count items)))]
                     (stmt! (sx "let" (apply sx (map #(sx %1 (s (+ base %2))) temps (range)))
                                (apply sx "begin" (map-indexed (fn [j src] (sx "set!" (s (+ base j)) (nth temps src))) order))))
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
              (throw (ex-info "ldc constant not in spike" {:entry e}))))
          :iload (push! (str "l" a0) "I")
          :lload (push! (str "l" a0) "J")
          :fload (push! (str "l" a0) "F")
          :dload (push! (str "l" a0) "D")
          :aload (push! (str "l" a0) "A")
          (:istore :lstore :fstore :dstore :astore) (stmt! (sx "set!" (str "l" a0) (pop!)))
          :iinc (stmt! (sx "set!" (str "l" a0) (sx "i32" (sx "fx+" (str "l" a0) (str (second args))))))
          (:iaload :baload :caload :saload) (bin (fn [a i] (sx "jaload" a i)) "I")
          :laload (bin (fn [a i] (sx "jaload" a i)) "J")
          :faload (bin (fn [a i] (sx "jaload" a i)) "F")
          :daload (bin (fn [a i] (sx "jaload" a i)) "D")
          :aaload (bin (fn [a i] (sx "jaload" a i)) "A")
          (:iastore :lastore :fastore :dastore :aastore :bastore :castore :sastore)
          (let [[a i v] (popn! 3)]
            (stmt! (sx "jastore" a i (case op :bastore (sx "i2b" v) :castore (sx "fxlogand" v "#xffff")
                                       :sastore (sx "i2s" v) v))))
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
                  (stmt! (sx "let" (sx (sx "t" v1)) (sx "set!" v1 v2) (sx "set!" v2 "t")))
                  (vswap! st conj t1 t2))
          :iadd (bin #(sx "i32" (sx "fx+" %1 %2)) "I")
          :isub (bin #(sx "i32" (sx "fx-" %1 %2)) "I")
          :imul (bin #(sx "i32" (sx "*" %1 %2)) "I")
          :idiv (bin #(sx "idiv" %1 %2) "I")
          :irem (bin #(sx "irem" %1 %2) "I")
          :ineg (un #(sx "i32" (sx "fx-" "0" %)) "I")
          :ishl (bin #(sx "ishl" %1 %2) "I")
          :ishr (bin #(sx "ishr" %1 %2) "I")
          :iushr (bin #(sx "iushr" %1 %2) "I")
          :iand (bin #(sx "fxlogand" %1 %2) "I")
          :ior (bin #(sx "fxlogor" %1 %2) "I")
          :ixor (bin #(sx "fxlogxor" %1 %2) "I")
          :ladd (bin #(sx "i64" (sx "+" %1 %2)) "J")
          :lsub (bin #(sx "i64" (sx "-" %1 %2)) "J")
          :lmul (bin #(sx "i64" (sx "*" %1 %2)) "J")
          :ldiv (bin #(sx "ldiv" %1 %2) "J")
          :lrem (bin #(sx "lrem" %1 %2) "J")
          :lneg (un #(sx "i64" (sx "-" %)) "J")
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
          :l2i (un #(sx "i32" %) "I")
          :i2d (un #(sx "fixnum->flonum" %) "D")
          :l2d (un #(sx "inexact" %) "D")
          :i2f (un #(sx "f32" (sx "fixnum->flonum" %)) "F")
          :l2f (un #(sx "f32" (sx "inexact" %)) "F")
          :f2d (un identity "D")
          :d2f (un #(sx "f32" %) "F")
          (:f2i :d2i) (un #(sx "d2i" %) "I")
          (:f2l :d2l) (un #(sx "d2l" %) "J")
          :i2b (un #(sx "i2b" %) "I")
          :i2c (un #(sx "fxlogand" % "#xffff") "I")
          :i2s (un #(sx "i2s" %) "I")
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
          (:monitorenter :monitorexit) (pop!) ; spike: single-threaded, monitors are no-ops
          :getstatic (let [{:keys [class name desc]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (push! (static-global decl name) (desc->t desc)))
          :putstatic (let [{:keys [class name]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (stmt! (sx "set!" (static-global decl name) (pop!))))
          :getfield (let [{:keys [class name desc]} (cp a0)
                          idx ((:field-index resolve) class name)]
                      (un #(sx "jslot" % (str idx)) (desc->t desc)))
          :putfield (let [{:keys [class name]} (cp a0)
                          idx ((:field-index resolve) class name)
                          v (pop!) o (pop!)]
                      (stmt! (sx "jslot-set!" o (str idx) v)))
          (:invokevirtual :invokeinterface :invokespecial :invokestatic)
          (let [{:keys [class name desc]} (cp a0)
                {:keys [args ret]} (parse-desc desc)
                argv (popn! (count args))
                recv (when-not (= op :invokestatic) (pop!))
                call (case op
                       (:invokevirtual :invokeinterface)
                       (if (and (= class "java/lang/invoke/VarHandle") (varhandle-modes name))
                         ;; signature-polymorphic: the runtime decodes the call site
                         (apply sx "vh-call" recv (str-lit name) argv)
                         (do (when-not (str/starts-with? class "[") ((:ensure-loaded resolve) class))
                             (apply sx (sx "jvirt" recv (str-lit (str name desc))) recv argv)))
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
                bsm-name (:name (cp (:ref-idx (cp (:ref-idx bsm)))))
                argv (popn! (count args))]
            (case bsm-name
              "makeConcatWithConstants"
              (let [recipe (:value (cp (first (:args bsm))))
                    consts (map #(let [e (cp %)] (str-lit (str (:value e)))) (rest (:args bsm)))
                    conv (map (fn [t v]
                                (case t
                                  ("I" "S" "B" "J") (sx "number->string" v)
                                  "F" (sx "jfloat->string" v)
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
              (throw (ex-info (str "invokedynamic bootstrap not in spike: " bsm-name)
                              {:bsm bsm-name :name name :class this-class}))))
          :new (let [c (:name (cp a0))]
                 ((:ensure-loaded resolve) c)
                 (init c)
                 (push! (sx "jnew" (str-lit c)) "A"))
          :newarray (let [e (newarray-elem a0)]
                      (un #(sx "jnewarray" (str-lit e) % (if (#{"F" "D"} e) "0.0" "0")) "A"))
          :anewarray (let [c (:name (cp a0))
                           e (if (str/starts-with? c "[") c (str "L" c ";"))]
                       (un #(sx "jnewarray" (str-lit e) % "#f") "A"))
          :multianewarray (let [c (:name (cp a0)) dims (popn! (second args))]
                            (push! (sx "jmultiarray" (str-lit c) (apply sx "list" dims)) "A"))
          :arraylength (un #(sx "jarraylength" %) "I")
          :checkcast (let [c (:name (cp a0))]
                       (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                       (let [v (s (dec (count @st)))] (stmt! (sx "set!" v (sx "jcheckcast" v (str-lit c))))))
          :instanceof (let [c (:name (cp a0))]
                        (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                        (un #(sx "if" (sx "jinstance?" % (str-lit c)) "1" "0") "I"))
          (throw (ex-info (str "opcode not in spike: " (name op)) {:op op :pc pc :class this-class})))))
    (let [term (or @terminal (goto next-pc))]
      {:code (str "(begin " (str/join " " (persistent! stmts)) " " term ")")
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
                 (for [{:keys [type handler]} covering]
                   (sx (if type (sx "jinstance?" "e" (str-lit type)) (sx "jobj?" "e"))
                       (sx "set!" "s0" "e")
                       (label handler))))
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
        next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
        ;; worklist over blocks: each is emitted once, at the stack depth its
        ;; first predecessor established (verified bytecode guarantees agreement)
        emitted (loop [work (into [[0 []]] (map (fn [h] [(:handler h) ["A"]]) handlers))
                       done {}]
                  (if-let [[pc dep] (first work)]
                    (if (contains? done pc)
                      (recur (rest work) done)
                      (let [{:keys [code succ]} (emit-block {:cls cls :resolve resolve :tramp? tramp?
                                                             :next-pc (next-pc pc)}
                                                            (blks pc) dep)]
                        (recur (concat (rest work) succ) (assoc done pc code))))
                    done))
        {:keys [args]} (parse-desc (:desc method))
        arg-types (if (pos? (bit-and (:flags method) 0x0008)) args (cons "L" args))
        params (loop [ts arg-types slot 0 out []]
                 (if-let [t (first ts)]
                   (recur (rest ts) (+ slot (if (wide? t) 2 1)) (conj out slot))
                   out))
        param-set (set params)
        bindings (concat (for [i (range max-locals) :when (not (param-set i))] (sx (str "l" i) "0"))
                         (for [i (range max-stack)] (sx (str "s" i) "0")))]
    (let [body (sx "let" (apply sx bindings)
                   (sx "letrec"
                       (apply sx (for [[pc code] (sort-by key emitted)]
                                   (sx (label pc) (sx "lambda" "()" (if tramp? (guard-wrap code pc handlers) code)))))
                       (if tramp?
                         ;; an inline named loop rather than a shared helper,
                         ;; so a frame named for this method stays on the stack
                         ;; while a block runs under guard (getCallerClass)
                         (let [t (sym (str *method-tag* "#T"))]
                           (sx "let" t (sx (sx "r" (sx (label 0))))
                               (sx "if" (sx "procedure?" "r") (sx t (sx "r")) "r")))
                         (sx (label 0)))))]
      (sx "define" (method-global (:name cls) (:name method) (:desc method))
          (sx "lambda" (apply sx (map #(str "l" %) params))
              ;; CHAMPACA_BT: keep a shadow stack of Java frames for backtraces
              (if (System/getenv "CHAMPACA_BT")
                (sx "fluid-let" (sx (sx "jstack" (sx "cons" (str-lit (str (:name cls) "." (:name method) (:desc method))) "jstack")))
                    body)
                body))))))
