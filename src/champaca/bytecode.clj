;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.bytecode
  "Bytecode as data: decoding instructions, basic blocks and control flow,
  and the natural loops array-loop versioning looks for."
  (:require [champaca.cgtext :refer [at]]))

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
    198 [:ifnull :branch] 199 [:ifnonnull :branch] 200 [:goto_w :branch4] 201 [:jsr_w :branch4]}
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

(defn- u1-at [code i] (nth code i))
(defn- s1-at [code i] (let [v (nth code i)] (if (>= v 128) (- v 256) v)))
(defn- s2-at [code i] (s16 (nth code i) (nth code (inc i))))
(defn- u2-at [code i] (+ (* (nth code i) 256) (nth code (inc i))))
(defn- s4-at [code i] (s32 (nth code i) (nth code (+ i 1)) (nth code (+ i 2)) (nth code (+ i 3))))

(defn- switch-base
  "Where a switch's operands start: after the opcode and the padding that
  aligns them to four bytes from the start of the code."
  [pc]
  (+ pc 1 (mod (- 3 (mod pc 4)) 4)))

(defn- tableswitch-operands [code pc]
  (let [p (switch-base pc)
        dflt (+ pc (s4-at code p)) lo (s4-at code (+ p 4)) hi (s4-at code (+ p 8))
        cnt (inc (- hi lo))
        targets (vec (for [k (range cnt)] [(+ lo k) (+ pc (s4-at code (+ p 12 (* 4 k))))]))]
    [[dflt targets] (- (+ p 12 (* 4 cnt)) pc)]))

(defn- lookupswitch-operands [code pc]
  (let [p (switch-base pc)
        dflt (+ pc (s4-at code p)) cnt (s4-at code (+ p 4))
        targets (vec (for [k (range cnt)]
                       [(s4-at code (+ p 8 (* 8 k))) (+ pc (s4-at code (+ p 12 (* 8 k))))]))]
    [[dflt targets] (- (+ p 8 (* 8 cnt)) pc)]))

(defn- wide-operands
  "wide's: the slot index, and iinc's 16-bit increment."
  [code pc]
  (let [[op2] (opcodes (u1-at code (inc pc))) idx (u2-at code (+ pc 2))]
    (if (= op2 :iinc)
      [[idx (s2-at code (+ pc 4))] 6]
      [[idx] 4])))

(def ^:private operand-readers
  ;; operand format -> (fn [code pc] [args len])
  {:none (fn [_ _] [[] 1])
   :u1 (fn [code pc] [[(u1-at code (inc pc))] 2])
   :s1 (fn [code pc] [[(s1-at code (inc pc))] 2])
   :s2 (fn [code pc] [[(s2-at code (inc pc))] 3])
   :u2 (fn [code pc] [[(u2-at code (inc pc))] 3])
   :iinc (fn [code pc] [[(u1-at code (+ pc 1)) (s1-at code (+ pc 2))] 3])
   :branch (fn [code pc] [[(+ pc (s2-at code (inc pc)))] 3])
   :branch4 (fn [code pc] [[(+ pc (s4-at code (inc pc)))] 5])
   :iface (fn [code pc] [[(u2-at code (inc pc))] 5])
   :indy (fn [code pc] [[(u2-at code (inc pc))] 5])
   :multi (fn [code pc] [[(u2-at code (inc pc)) (u1-at code (+ pc 3))] 4])
   :wide wide-operands
   :tableswitch tableswitch-operands
   :lookupswitch lookupswitch-operands})

(defn- operands
  "[args len] of the instruction at pc, whose operand format is fmt; an
  xload_n/xstore_n's [:implicit n] has its slot as its one argument."
  [code pc fmt]
  (if (vector? fmt)
    [[(second fmt)] 1]
    (if-let [read (operand-readers fmt)]
      (read code pc)
      (throw (ex-info "no reader for operand format" {:pc pc :format fmt})))))

(defn- decode-insns
  "Code bytes -> vector of {:pc :op :args :len}."
  [code]
  (let [n (count code)]
    (loop [pc 0 out []]
      (if (>= pc n)
        out
        (let [[op fmt] (or (opcodes (u1-at code pc)) (throw (ex-info "unknown opcode" {:pc pc :byte (u1-at code pc)})))
              [args len] (operands code pc fmt)
              op (if (= op :wide) (first (opcodes (u1-at code (inc pc)))) op)]
          (recur (+ pc len) (conj out {:pc pc :op op :args args :len len})))))))

;; --- control flow ------------------------------------------------------------

(def ^:private branch-ops
  #{:ifeq :ifne :iflt :ifge :ifgt :ifle :if_icmpeq :if_icmpne :if_icmplt :if_icmpge
    :if_icmpgt :if_icmple :if_acmpeq :if_acmpne :ifnull :ifnonnull})
(def ^:private terminal-ops
  #{:goto :goto_w :ireturn :lreturn :freturn :dreturn :areturn :return :athrow
    :tableswitch :lookupswitch :jsr :jsr_w :ret})

(defn successors
  "The pcs control can go to next. A jsr goes to its subroutine, and the
  subroutine's ret to the instructions after the jsrs that reach it (decode's
  :targets), never straight to the jsr's next instruction."
  [{:keys [op args pc len targets]}]
  (cond
    (branch-ops op) [(first args) (+ pc len)]
    (#{:goto :goto_w :jsr :jsr_w} op) [(first args)]
    (#{:tableswitch :lookupswitch} op) (cons (first args) (map second (second args)))
    (= op :ret) (vec targets)
    (terminal-ops op) []
    :else [(+ pc len)]))

(defn- subroutine-rets
  "The pcs of the rets a subroutine starting at pc can return with: those
  reached from it, a jsr inside it counted as coming back to its next
  instruction, so a nested subroutine's own rets aren't its."
  [at pc]
  (loop [todo [pc] seen #{} rets #{}]
    (if-let [p (peek todo)]
      (let [todo (pop todo)
            {:keys [op len] :as i} (at p)]
        (cond (or (seen p) (nil? i)) (recur todo seen rets)
              (= op :ret) (recur todo (conj seen p) (conj rets p))
              (#{:jsr :jsr_w} op) (recur (conj todo (+ p len)) (conj seen p) rets)
              :else (recur (into todo (successors i)) (conj seen p) rets)))
      rets)))

(defn with-ret-targets
  "insns with each ret's :targets, the sorted pcs right after the jsrs whose
  subroutine returns with it (the pcs a returnAddress in its local can
  hold). A ret no subroutine's walk reaches (one in a handler that covers a
  subroutine's code) can return after any jsr; its dispatch is on the value,
  so a wider set is only more code. A method with no jsr is returned as it
  is."
  [insns]
  (let [jsrs (filter (comp #{:jsr :jsr_w} :op) insns)]
    (if (empty? jsrs)
      insns
      (let [at (into {} (map (juxt :pc identity)) insns)
            after (fn [js] (into (sorted-set) (map #(+ (:pc %) (:len %))) js))
            targets (apply merge-with into
                           (for [[sub js] (group-by (comp first :args) jsrs)
                                 r (subroutine-rets at sub)]
                             {r (after js)}))
            any (after jsrs)]
        (mapv (fn [i] (if (= (:op i) :ret) (assoc i :targets (vec (get targets (:pc i) any))) i)) insns)))))

(defn decode
  "Code bytes -> vector of {:pc :op :args :len}, each ret with its :targets
  (with-ret-targets)."
  [code]
  (with-ret-targets (decode-insns code)))

(defn backward-ranges
  "[target pc] of every jump to an earlier or the same instruction: a loop's
  body lies inside one of them."
  [insns]
  (vec (for [{:keys [pc] :as i} insns, t (successors i) :when (<= t pc)] [t pc])))

(defn- leaders [insns handlers]
  (into (sorted-set 0)
        (concat
         (mapcat (fn [{:keys [op pc len] :as i}]
                   (when (or (branch-ops op) (terminal-ops op))
                     (cons (+ pc len) (successors i))))
                 insns)
         (mapcat (juxt :start :end :handler) handlers))))

(defn blocks
  "pc -> vector of instructions, split at every leader."
  [insns handlers]
  (let [ls (leaders insns handlers)]
    (->> insns
         (partition-by (let [cur (volatile! -1)]
                         (fn [{:keys [pc]}] (when (ls pc) (vreset! cur pc)) @cur)))
         (map (fn [blk] [(:pc (first blk)) (vec blk)]))
         (into (sorted-map)))))


(def load-ops #{:iload :lload :fload :dload :aload :iinc})
(def store-ops #{:istore :lstore :fstore :dstore :astore :iinc})

;; natural loops, innermost first, for array-loop versioning (codegen)
(def ^:private array-ops
  #{:iaload :laload :faload :daload :aaload :baload :caload :saload
    :iastore :lastore :fastore :dastore :bastore :castore :sastore :arraylength})

(defn- block-succs
  "pc -> the blocks a block can go to next."
  [blks]
  (into {} (for [[pc blk] blks] [pc (filter blks (successors (peek blk)))])))

(defn- reverse-postorder
  "The blocks reachable from pc 0 in reverse postorder, found iteratively."
  [succs]
  (loop [stack [[0 (succs 0)]] seen #{0} out ()]
    (if-let [[pc ss] (peek stack)]
      (let [ss (drop-while seen ss)]
        (if-let [n (first ss)]
          (recur (conj (pop stack) [pc (rest ss)] [n (succs n)]) (conj seen n) out)
          (recur (pop stack) seen (conj out pc))))
      out)))

(defn- predecessors
  "pc -> the reachable blocks that can go to it."
  [succs reach]
  (reduce (fn [m [p ss]] (if (reach p) (reduce #(update %1 %2 (fnil conj []) p) m ss) m)) {} succs))

(defn- dominator-sets
  "pc -> the set of blocks dominating it, by iterating to a fixed point in
  reverse postorder; an unreachable block has no entry."
  [rpo preds]
  (let [reach (set rpo)
        meet (fn [sets] (reduce (fn [a b] (into #{} (filter b) a)) sets))]
    (loop [dom (into {0 #{0}} (map (fn [pc] [pc reach]) (rest rpo)))]
      (let [d (reduce (fn [d pc] (assoc d pc (conj (meet (map d (preds pc))) pc))) dom (rest rpo))]
        (if (= d dom) dom (recur d))))))

(defn- natural-loop
  "Body b of header h (nil for h's first back edge) grown by the back edge
  p -> h: the blocks that reach p without passing h."
  [preds b p h]
  (loop [b (conj (or b #{h}) p) work [p]]
    (if-let [x (peek work)]
      (let [new (remove b (when (not= x h) (preds x)))]
        (recur (into b new) (into (pop work) new)))
      b)))

(defn- loop-bodies
  "header pc -> the union of the natural loops of its back edges p -> h,
  h dominating p."
  [rpo succs preds dom]
  (reduce (fn [m [p h]] (update m h #(natural-loop preds % p h)))
          {}
          (for [p rpo h (succs p) :when ((dom p) h)] [p h])))

(defn- versionable-loops
  "The bodies holding no other loop's header that do something to an array,
  each with the locals it aloads and never stores; a body with no such
  local is dropped."
  [blks bodies]
  (into {}
        (for [[h body] bodies
              :when (not-any? #(and (not= % h) (bodies %)) body)
              :let [insns (mapcat blks body)
                    stored (set (keep #(when (store-ops (:op %)) (first (:args %))) insns))
                    locals (set (keep #(when (= :aload (:op %)) (first (:args %))) insns))
                    locals (into #{} (remove stored) locals)]
              :when (and (seq locals) (some #(array-ops (:op %)) insns))]
          [h {:body body :locals locals}])))

(defn array-loops
  "header pc -> {:body #{pc} :locals #{slot}}: the innermost natural loops of
  blks that do something to an array and aload a local they never store."
  [blks]
  (let [succs (block-succs blks)]
    (when (and (some (fn [[_ blk]] (some #(array-ops (:op %)) blk)) blks)
               (some (fn [[pc ss]] (some #(<= % pc) ss)) succs))
      (let [rpo (reverse-postorder succs)
            preds (predecessors succs (set rpo))
            dom (dominator-sets rpo preds)]
        (versionable-loops blks (loop-bodies rpo succs preds dom))))))
