(ns openldk-jolt.codegen
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
                  :wide (throw (ex-info "wide not in spike" {:pc pc}))
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
          (recur (+ pc len) (conj out {:pc pc :op op :args args :len len})))))))

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

(defn- label [pc] (str "B" pc))

(defn- emit-block
  "Emit one basic block. ctx carries :cls (the classfile map), :resolve (the
  linker callbacks), :tramp? and :next-pc. Returns {:code text :succ {pc depth}}."
  [ctx blk depth]
  (let [{:keys [cls resolve tramp? next-pc]} ctx
        cp (:cp cls)
        this-class (:name cls)
        d (volatile! depth)
        stmts (transient [])
        succ (volatile! {})
        s (fn [i] (str "s" i))
        push! (fn [expr] (conj! stmts (sx "set!" (s @d) expr)) (vswap! d inc))
        pop! (fn [] (vswap! d dec) (s @d))
        popn! (fn [n] (vec (reverse (repeatedly n pop!))))
        stmt! (fn [x] (conj! stmts x))
        goto (fn [pc dep] (vswap! succ assoc pc dep) (if tramp? (label pc) (sx (label pc))))
        init (fn [c] (when (not= c this-class) (stmt! (sx "jinit" (str-lit c)))))
        bin (fn [f] (let [b (pop!) a (pop!)] (push! (f a b))))
        un (fn [f] (push! (f (pop!))))
        cmp0 {:ifeq "fx=" :ifne "fx<>" :iflt "fx<" :ifge "fx>=" :ifgt "fx>" :ifle "fx<="}
        cmp2 {:if_icmpeq "fx=" :if_icmpne "fx<>" :if_icmplt "fx<" :if_icmpge "fx>="
              :if_icmpgt "fx>" :if_icmple "fx<="}
        terminal (volatile! nil)]
    (doseq [{:keys [op args pc len]} blk]
      (let [a0 (first args)]
        (case op
          :nop nil
          :aconst_null (push! "#f")
          (:iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4 :iconst_5)
          (push! (str ({:iconst_m1 -1 :iconst_0 0 :iconst_1 1 :iconst_2 2 :iconst_3 3
                        :iconst_4 4 :iconst_5 5} op)))
          (:lconst_0 :lconst_1) (push! (if (= op :lconst_0) "0" "1"))
          (:fconst_0 :fconst_1 :fconst_2 :dconst_0 :dconst_1)
          (push! ({:fconst_0 "0.0" :fconst_1 "1.0" :fconst_2 "2.0" :dconst_0 "0.0" :dconst_1 "1.0"} op))
          (:bipush :sipush) (push! (str a0))
          (:ldc :ldc_w :ldc2_w)
          (let [e (cp a0)]
            (push! (case (:tag e)
                     (:int :long :float :double) (num-lit (:value e))
                     :string (str-lit (:value e))
                     (throw (ex-info "ldc constant not in spike" {:entry e})))))
          (:iload :lload :fload :dload :aload) (push! (str "l" a0))
          (:istore :lstore :fstore :dstore :astore) (stmt! (sx "set!" (str "l" a0) (pop!)))
          :iinc (stmt! (sx "set!" (str "l" a0) (sx "i32" (sx "fx+" (str "l" a0) (str (second args))))))
          (:iaload :laload :faload :daload :aaload :baload :caload :saload)
          (bin (fn [a i] (sx "jaload" a i)))
          (:iastore :lastore :fastore :dastore :aastore :bastore :castore :sastore)
          (let [[a i v] (popn! 3)]
            (stmt! (sx "jastore" a i (case op :bastore (sx "i2b" v) :castore (sx "fxlogand" v "#xffff") v))))
          :pop (pop!)
          :dup (push! (s (dec @d)))
          :dup_x1 (let [v1 (pop!) v2 (pop!)]
                    (stmt! (sx "let" (sx (sx "t1" v1) (sx "t2" v2))
                               (sx "set!" (s @d) "t1") (sx "set!" (s (inc @d)) "t2")
                               (sx "set!" (s (+ @d 2)) "t1")))
                    (vswap! d + 3))
          :swap (let [v1 (pop!) v2 (pop!)]
                  (stmt! (sx "let" (sx (sx "t" v1)) (sx "set!" v1 v2) (sx "set!" v2 "t")))
                  (vswap! d + 2))
          :iadd (bin #(sx "i32" (sx "fx+" %1 %2)))
          :isub (bin #(sx "i32" (sx "fx-" %1 %2)))
          :imul (bin #(sx "i32" (sx "*" %1 %2)))
          :idiv (bin #(sx "idiv" %1 %2))
          :irem (bin #(sx "irem" %1 %2))
          :ineg (un #(sx "i32" (sx "fx-" "0" %)))
          :ishl (bin #(sx "ishl" %1 %2))
          :ishr (bin #(sx "ishr" %1 %2))
          :iushr (bin #(sx "iushr" %1 %2))
          :iand (bin #(sx "fxlogand" %1 %2))
          :ior (bin #(sx "fxlogor" %1 %2))
          :ixor (bin #(sx "fxlogxor" %1 %2))
          :ladd (bin #(sx "i64" (sx "+" %1 %2)))
          :lsub (bin #(sx "i64" (sx "-" %1 %2)))
          :lmul (bin #(sx "i64" (sx "*" %1 %2)))
          :ldiv (bin #(sx "ldiv" %1 %2))
          :lrem (bin #(sx "lrem" %1 %2))
          :lneg (un #(sx "i64" (sx "-" %)))
          :lshl (bin #(sx "lshl" %1 %2))
          :lshr (bin #(sx "lshr" %1 %2))
          :lushr (bin #(sx "lushr" %1 %2))
          :land (bin #(sx "logand" %1 %2))
          :lor (bin #(sx "logor" %1 %2))
          :lxor (bin #(sx "logxor" %1 %2))
          :lcmp (bin #(sx "lcmp" %1 %2))
          (:dadd :dsub :dmul :ddiv)
          (bin #(sx ({:dadd "fl+" :dsub "fl-" :dmul "fl*" :ddiv "fl/"} op) %1 %2))
          (:fadd :fsub :fmul :fdiv)
          (bin #(sx "f32" (sx ({:fadd "fl+" :fsub "fl-" :fmul "fl*" :fdiv "fl/"} op) %1 %2)))
          :i2l nil
          :l2i (un #(sx "i32" %))
          :i2d (un #(sx "fixnum->flonum" %))
          :l2d (un #(sx "inexact" %))
          :i2f (un #(sx "f32" (sx "fixnum->flonum" %)))
          :f2d nil
          :d2f (un #(sx "f32" %))
          :i2b (un #(sx "i2b" %))
          :i2c (un #(sx "fxlogand" % "#xffff"))
          :i2s (un #(sx "i2s" %))
          (:ifeq :ifne :iflt :ifge :ifgt :ifle)
          (let [a (pop!)]
            (vreset! terminal (sx "if" (sx (cmp0 op) a "0") (goto a0 @d) (goto (+ pc len) @d))))
          (:if_icmpeq :if_icmpne :if_icmplt :if_icmpge :if_icmpgt :if_icmple)
          (let [b (pop!) a (pop!)]
            (vreset! terminal (sx "if" (sx (cmp2 op) a b) (goto a0 @d) (goto (+ pc len) @d))))
          (:if_acmpeq :if_acmpne)
          (let [b (pop!) a (pop!) t (sx "eq?" a b)]
            (vreset! terminal (sx "if" (if (= op :if_acmpeq) t (sx "not" t)) (goto a0 @d) (goto (+ pc len) @d))))
          (:ifnull :ifnonnull)
          (let [a (pop!)]
            (vreset! terminal (sx "if" (if (= op :ifnull) (sx "not" a) a) (goto a0 @d) (goto (+ pc len) @d))))
          (:goto :goto_w) (vreset! terminal (goto a0 @d))
          (:tableswitch :lookupswitch)
          (let [k (pop!) [dflt targets] args]
            (vreset! terminal
                     (apply sx "case" k
                            (concat (for [[v t] targets] (sx (sx (str v)) (goto t @d)))
                                    [(sx "else" (goto dflt @d))]))))
          (:ireturn :lreturn :freturn :dreturn :areturn) (vreset! terminal (pop!))
          :return (vreset! terminal "(void)")
          :athrow (vreset! terminal (sx "jathrow" (pop!)))
          (:monitorenter :monitorexit) (pop!) ; spike: single-threaded, monitors are no-ops
          :getstatic (let [{:keys [class name]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (push! (static-global decl name)))
          :putstatic (let [{:keys [class name]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl) (stmt! (sx "set!" (static-global decl name) (pop!))))
          :getfield (let [{:keys [class name]} (cp a0)
                          idx ((:field-index resolve) class name)]
                      (un #(sx "jslot" % (str idx))))
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
                       (do ((:ensure-loaded resolve) class)
                           (apply sx (sx "jvirt" recv (str-lit (str name desc))) recv argv))
                       :invokespecial
                       (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) recv argv)
                       :invokestatic
                       (let [decl ((:resolve-method resolve) class name desc)]
                         (init decl)
                         (apply sx (method-global decl name desc) argv)))]
            (if (= ret "V") (stmt! call) (push! call)))
          :invokedynamic
          (let [{:keys [bsm-idx name desc]} (cp a0)
                {:keys [args]} (parse-desc desc)
                bsm (nth (:bootstrap-methods cls) bsm-idx)
                recipe (:value (cp (first (:args bsm))))
                _ (when-not (and (= name "makeConcatWithConstants") recipe
                                 (not (str/includes? recipe "\u0002")))
                    (throw (ex-info "invokedynamic not in spike" {:name name :desc desc})))
                argv (popn! (count args))
                conv (map (fn [t v]
                            (case t
                              ("I" "S" "B" "J" "F" "D") (sx "number->string" v)
                              "C" (sx "string" (sx "integer->char" v))
                              "Z" (sx "jbool->string" v)
                              (sx "jstring-of" v)))
                          args argv)]
            (push! (apply sx "jconcat" (str-lit recipe) conv)))
          :new (let [c (:name (cp a0))]
                 ((:ensure-loaded resolve) c)
                 (init c)
                 (push! (sx "jnew" (str-lit c))))
          :newarray (un #(sx "jnewarray" (str-lit "prim") % (if (#{6 7} a0) "0.0" "0")))
          :anewarray (un #(sx "jnewarray" (str-lit (:name (cp a0))) % "#f"))
          :arraylength (un #(sx "jarraylength" %))
          :checkcast (let [c (:name (cp a0))]
                       (when-not (str/starts-with? c "[")
                         ((:ensure-loaded resolve) c)
                         (let [v (s (dec @d))] (stmt! (sx "set!" v (sx "jcheckcast" v (str-lit c)))))))
          :instanceof (let [c (:name (cp a0))]
                        ((:ensure-loaded resolve) c)
                        (un #(sx "if" (sx "jinstance?" % (str-lit c)) "1" "0")))
          (throw (ex-info (str "opcode not in spike: " (name op)) {:op op :pc pc})))))
    (let [term (or @terminal (goto next-pc @d))]
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

(defn compile-method
  "Scheme source for `(define |Cls.name(desc)| (lambda ...))`."
  [cls method resolve]
  (let [{:keys [max-stack max-locals code handlers]} (:code method)
        insns (decode code)
        blks (blocks insns handlers)
        tramp? (boolean (seq handlers))
        next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
        ;; worklist over blocks: each is emitted once, at the stack depth its
        ;; first predecessor established (verified bytecode guarantees agreement)
        emitted (loop [work (into [[0 0]] (map (fn [h] [(:handler h) 1]) handlers))
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
    (sx "define" (method-global (:name cls) (:name method) (:desc method))
        (sx "lambda" (apply sx (map #(str "l" %) params))
            (sx "let" (apply sx bindings)
                (sx "letrec"
                    (apply sx (for [[pc code] (sort-by key emitted)]
                                (sx (label pc) (sx "lambda" "()" (if tramp? (guard-wrap code pc handlers) code)))))
                    (if tramp? (sx "trampoline" (label 0)) (sx (label 0)))))))))
