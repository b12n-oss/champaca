;; Copyright (C) 2026  champaca contributors
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0

(ns champaca.npe
  "Helpful NullPointerException messages (JEP 358): what the failing
  instruction couldn't do, then which expression gave it the null, as in
  Cannot invoke \"String.length()\" because \"<local1>\" is null.

  Written from the text of JEP 358 and from the messages HotSpot was
  observed to give for probe programs, not from HotSpot's sources.

  The analysis keeps, for each operand stack word, the pc of the
  instruction that pushed it, and for each local slot whether it was
  stored to. It sweeps the code in pc order, merging states where paths
  join, and stops in the first sweep that reaches the failing
  instruction. What a backward jump carries arrives in the next sweep, so
  a store later in a loop body isn't seen by the loop's test, its body or
  the code after it, as in HotSpot's messages."
  (:require [champaca.codegen :as cg]
            [clojure.string :as str]))

(def ^:private max-depth
  "How many steps back a description goes. An array index starts again."
  5)

(def ^:private acc-static 0x0008)

(defn- words
  "Stack words a value of descriptor type t takes."
  [t]
  (if (#{"J" "D"} t) 2 1))

;; --- names -------------------------------------------------------------------

(defn- class-text
  "An internal class name as messages print it: dotted, with String and
  Object short. An array class keeps its descriptor form."
  [internal]
  (let [dotted (str/replace internal "/" ".")]
    (case dotted
      "java.lang.String" "String"
      "java.lang.Object" "Object"
      dotted)))

(def ^:private primitive-names
  {\B "byte" \C "char" \D "double" \F "float" \I "int" \J "long" \S "short"
   \Z "boolean" \V "void"})

(defn- type-text [t]
  (let [c (first t)]
    (cond
      (= c \L) (class-text (subs t 1 (dec (count t))))
      (= c \[) (str (type-text (subs t 1)) "[]")
      :else (primitive-names c))))

(defn- method-text
  "Class.name(parameter types) of a method reference."
  [{:keys [class name desc]}]
  (str (class-text class) "." name
       "(" (str/join ", " (map type-text (:args (cg/parse-desc desc)))) ")"))

;; --- stack effects -----------------------------------------------------------

(defn- all [ops effect] (zipmap ops (repeat effect)))

(def ^:private fixed-effects
  "op -> [words popped, words pushed] for ops whose effect doesn't depend
  on a constant pool entry."
  (merge
   (all [:nop :goto :goto_w :ret :iinc :return] [0 0])
   (all [:aconst_null :iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4
         :iconst_5 :fconst_0 :fconst_1 :fconst_2 :bipush :sipush :ldc :ldc_w
         :iload :fload :aload :new]
        [0 1])
   (all [:lconst_0 :lconst_1 :dconst_0 :dconst_1 :ldc2_w :lload :dload] [0 2])
   (all [:pop :ifeq :ifne :iflt :ifge :ifgt :ifle :ifnull :ifnonnull :tableswitch
         :lookupswitch :ireturn :freturn :areturn :athrow :monitorenter :monitorexit]
        [1 0])
   (all [:pop2 :if_icmpeq :if_icmpne :if_icmplt :if_icmpge :if_icmpgt :if_icmple
         :if_acmpeq :if_acmpne :lreturn :dreturn]
        [2 0])
   (all [:ineg :fneg :i2f :f2i :i2b :i2c :i2s :newarray :anewarray :arraylength
         :instanceof]
        [1 1])
   (all [:i2l :i2d :f2l :f2d] [1 2])
   (all [:iaload :faload :aaload :baload :caload :saload :iadd :fadd :isub :fsub
         :imul :fmul :idiv :fdiv :irem :frem :ishl :ishr :iushr :iand :ior :ixor
         :fcmpl :fcmpg :l2i :l2f :d2i :d2f]
        [2 1])
   (all [:laload :daload :lneg :dneg :l2d :d2l] [2 2])
   (all [:lshl :lshr :lushr] [3 2])
   (all [:iastore :fastore :aastore :bastore :castore :sastore] [3 0])
   (all [:lastore :dastore] [4 0])
   (all [:ladd :dadd :lsub :dsub :lmul :dmul :ldiv :ddiv :lrem :drem :land :lor :lxor]
        [4 2])
   (all [:lcmp :dcmpl :dcmpg] [4 1])))

(def ^:private shuffles
  "op -> [words taken from the top, the order they go back in]."
  {:dup [1 [0 0]]
   :dup_x1 [2 [1 0 1]]
   :dup_x2 [3 [2 0 1 2]]
   :dup2 [2 [0 1 0 1]]
   :dup2_x1 [3 [1 2 0 1 2]]
   :dup2_x2 [4 [2 3 0 1 2 3]]
   :swap [2 [1 0]]})

(def ^:private store-words
  {:istore 1 :fstore 1 :astore 1 :lstore 2 :dstore 2})

(def ^:private invoke-ops #{:invokevirtual :invokespecial :invokestatic :invokeinterface})

(defn- drop-words [stack n]
  (subvec stack 0 (max 0 (- (count stack) n))))

(defn- shuffle-top
  "Takes n words off the top and puts them back in order (indexes into the
  taken words, bottom first). Missing words read as unknown."
  [stack [n order]]
  (let [have (count stack)
        taken (vec (concat (repeat (max 0 (- n have)) :unknown)
                           (subvec stack (max 0 (- have n)))))]
    (into (drop-words stack n) (map taken order))))

(defn- invoke-words
  "[words popped, words pushed] of a call through a method reference."
  [op {:keys [desc]}]
  (let [{:keys [args ret]} (cg/parse-desc desc)
        arg-words (reduce + 0 (map words args))]
    [(if (= op :invokestatic) arg-words (inc arg-words))
     (if (= ret "V") 0 (words ret))]))

(defn- member-effect [cp op args]
  (let [entry (cp (first args))]
    (case op
      :getstatic [0 (words (:desc entry))]
      :putstatic [(words (:desc entry)) 0]
      :getfield [1 (words (:desc entry))]
      :putfield [(inc (words (:desc entry))) 0]
      :invokedynamic (let [{:keys [args ret]} (cg/parse-desc (:desc entry))]
                       [(reduce + 0 (map words args)) (if (= ret "V") 0 (words ret))])
      :multianewarray [(second args) 1]
      :jsr [0 1]
      (if (invoke-ops op)
        (invoke-words op entry)
        (throw (ex-info "no stack effect for op" {:op op}))))))

(defn- effect
  "The state after an instruction. Pushed words carry its pc; checkcast
  keeps its operand's source, so a cast is invisible to a description."
  [cp {:keys [op args pc]} {:keys [stack written] :as st}]
  (cond
    (shuffles op) (assoc st :stack (shuffle-top stack (shuffles op)))
    (store-words op) {:stack (drop-words stack (store-words op))
                      :written (conj written (first args))}
    (= op :checkcast) st
    :else (let [[popped pushed] (or (fixed-effects op) (member-effect cp op args))]
            (assoc st :stack (into (drop-words stack popped) (repeat pushed pc))))))

(def ^:private branch-ops
  #{:ifeq :ifne :iflt :ifge :ifgt :ifle :if_icmpeq :if_icmpne :if_icmplt :if_icmpge
    :if_icmpgt :if_icmple :if_acmpeq :if_acmpne :ifnull :ifnonnull})

(def ^:private terminal-ops
  #{:ireturn :lreturn :freturn :dreturn :areturn :return :athrow :ret})

(defn- transfer
  "[successor-pc state] pairs for an instruction run in state st."
  [cp {:keys [op args pc len] :as insn} st]
  (let [after (effect cp insn st)
        next-pc (+ pc len)]
    (cond
      (branch-ops op) [[(first args) after] [next-pc after]]
      (#{:goto :goto_w} op) [[(first args) after]]
      ;; a subroutine returns to the instruction after its jsr
      (= op :jsr) [[(first args) after] [next-pc st]]
      (#{:tableswitch :lookupswitch} op)
      (let [[default targets] args]
        (map #(vector % after) (cons default (map second targets))))
      (terminal-ops op) []
      :else [[next-pc after]])))

;; --- the sweeps --------------------------------------------------------------

(defn- merge-state
  "Two states meeting at one instruction: a word pushed by different
  instructions becomes unknown, and a slot stored on either path counts as
  stored."
  [a b]
  (if (nil? a)
    b
    {:stack (if (= (count (:stack a)) (count (:stack b)))
              (mapv (fn [x y] (if (= x y) x :unknown)) (:stack a) (:stack b))
              ;; depths differ only in code that doesn't verify, or around a
              ;; jsr subroutine: keep the first stack rather than fail
              (:stack a))
     :written (into (:written a) (:written b))}))

;; A handler starts with the caught exception alone on the stack and no
;; slot counted as stored, whatever its try range stored.
(def ^:private handler-state {:stack [:catch] :written #{}})

(defn- analyze
  "Sweeps the code until one reaches the instruction at position target.
  Answers {:states pos->state :at state} with the in-state of every
  instruction processed so far, or nil if no sweep reaches it."
  [cp insns pos-of handlers target]
  (let [n (count insns)
        states (volatile! {0 {:stack [] :written #{}}})
        changed (volatile! false)
        flow! (fn [pc st]
                (when-let [pos (pos-of pc)]
                  (let [old (get @states pos)
                        new (merge-state old st)]
                    (when (not= old new)
                      (vswap! states assoc pos new)
                      (vreset! changed true)))))
        sweep (fn []
                (loop [pos 0]
                  (when (< pos n)
                    (if-let [st (get @states pos)]
                      (if (= pos target)
                        st
                        (let [{:keys [pc] :as insn} (nth insns pos)]
                          (doseq [{:keys [start end handler]} handlers
                                  :when (and (<= start pc) (< pc end))]
                            (flow! handler handler-state))
                          (doseq [[succ st'] (transfer cp insn st)]
                            (flow! succ st'))
                          (recur (inc pos))))
                      (recur (inc pos))))))]
    (loop []
      (vreset! changed false)
      (if-let [at (sweep)]
        {:states @states :at at}
        (when @changed (recur))))))

;; --- descriptions ------------------------------------------------------------

(defn- operand
  "The source of the word depth below the top of the stack before the
  instruction at pc."
  [{:keys [states pos-of]} pc depth]
  (let [stack (:stack (get states (pos-of pc)))
        i (- (count stack) 1 depth)]
    (if (and stack (>= i 0)) (nth stack i) :unknown)))

(defn- local-name
  "this, the local variable table's name, <parameterN> for a parameter
  slot not stored to on the way to the failing instruction, else <localN>."
  [{:keys [static? locals params written]} slot pc]
  (or (when (and (not static?) (zero? slot)) "this")
      (some (fn [{:keys [start length index name]}]
              (when (and (= index slot) (<= start pc) (< pc (+ start length))) name))
            locals)
      (if-let [k (and (not (written slot)) (params slot))]
        (str "<parameter" k ">")
        (str "<local" slot ">"))))

(def ^:private load-ops #{:iload :lload :fload :dload :aload})

(def ^:private int-consts
  {:iconst_m1 "-1" :iconst_0 "0" :iconst_1 "1" :iconst_2 "2" :iconst_3 "3"
   :iconst_4 "4" :iconst_5 "5"})

(defn- describe
  "The source text of the value pushed by the instruction at src, depth
  steps from the start, or nil when it has none. seen guards against a
  source that leads back to itself."
  [{:keys [cp insn-at] :as ctx} src depth seen]
  (when (and (number? src) (<= depth max-depth) (not (seen src)))
    (let [{:keys [op args pc]} (insn-at src)
          seen (conj seen src)]
      (cond
        (load-ops op) (local-name ctx (first args) pc)
        (= op :aconst_null) "null"
        (int-consts op) (int-consts op)
        (#{:bipush :sipush} op) (str (first args))
        (= op :getstatic) (let [{:keys [class name]} (cp (first args))]
                            (str (class-text class) "." name))
        (= op :getfield) (let [obj (describe ctx (operand ctx pc 0) (inc depth) seen)
                               field (:name (cp (first args)))]
                           (if obj (str obj "." field) field))
        (#{:aaload :iaload} op)
        (str (or (describe ctx (operand ctx pc 1) (inc depth) seen) "<array>")
             "[" (or (describe ctx (operand ctx pc 0) 1 seen) "...") "]")
        (invoke-ops op) (method-text (cp (first args)))
        :else nil))))

;; --- the message -------------------------------------------------------------

(def ^:private array-kinds
  {:iaload "int" :laload "long" :faload "float" :daload "double" :aaload "object"
   :baload "byte/boolean" :caload "char" :saload "short"
   :iastore "int" :lastore "long" :fastore "float" :dastore "double" :aastore "object"
   :bastore "byte/boolean" :castore "char" :sastore "short"})

(defn- action
  "The first part of the message, and how many stack words lie above the
  null reference; nil for an instruction that can't meet a null."
  [cp {:keys [op args]}]
  (cond
    (#{:iaload :laload :faload :daload :aaload :baload :caload :saload} op)
    [(str "Cannot load from " (array-kinds op) " array") 1]
    (#{:iastore :fastore :aastore :bastore :castore :sastore} op)
    [(str "Cannot store to " (array-kinds op) " array") 2]
    (#{:lastore :dastore} op) [(str "Cannot store to " (array-kinds op) " array") 3]
    (= op :arraylength) ["Cannot read the array length" 0]
    (= op :athrow) ["Cannot throw exception" 0]
    (= op :monitorenter) ["Cannot enter synchronized block" 0]
    (= op :monitorexit) ["Cannot exit synchronized block" 0]
    (= op :getfield) [(str "Cannot read field \"" (:name (cp (first args))) "\"") 0]
    (= op :putfield) (let [{:keys [name desc]} (cp (first args))]
                       [(str "Cannot assign field \"" name "\"") (words desc)])
    (#{:invokevirtual :invokespecial :invokeinterface} op)
    (let [entry (cp (first args))]
      [(str "Cannot invoke \"" (method-text entry) "\"")
       (reduce + 0 (map words (:args (cg/parse-desc (:desc entry)))))])
    :else nil))

(defn- param-slots
  "slot -> parameter number, counting from 1."
  [static? desc]
  (loop [slot (if static? 0 1) k 1 args (:args (cg/parse-desc desc)) m {}]
    (if (empty? args)
      m
      (recur (+ slot (words (first args))) (inc k) (rest args) (assoc m slot k)))))

(defn- reason
  "The part after \"because\", or nil when the null's source can't be
  described."
  [ctx src]
  (let [{:keys [op args]} (when (number? src) ((:insn-at ctx) src))]
    (if (invoke-ops op)
      (str "the return value of \"" (method-text ((:cp ctx) (first args))) "\" is null")
      (when-let [text (describe ctx src 1 #{})]
        (str "\"" text "\" is null")))))

(defn message
  "The helpful message for an NPE at bci of method, or nil where the JVM
  gives none."
  [cp method bci]
  (let [{:keys [code handlers locals]} (:code method)
        insns (cg/decode code)
        pos-of (into {} (map-indexed (fn [i insn] [(:pc insn) i]) insns))
        target (pos-of bci)
        [first-part depth] (when target (action cp (nth insns target)))]
    (when first-part
      (let [static? (pos? (bit-and (:flags method) acc-static))
            {:keys [states at]} (analyze cp insns pos-of handlers target)
            ctx {:cp cp
                 :insn-at (fn [pc] (nth insns (pos-of pc)))
                 :pos-of pos-of
                 :states states
                 :static? static?
                 :locals locals
                 :params (param-slots static? (:desc method))
                 :written (or (:written at) #{})}
            src (if at
                  (let [stack (:stack at)
                        i (- (count stack) 1 depth)]
                    (if (>= i 0) (nth stack i) :unknown))
                  :unknown)
            why (reason ctx src)]
        (if why
          (str first-part " because " why)
          first-part)))))
