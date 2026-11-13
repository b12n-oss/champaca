;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.codegen
  "Translate one method's bytecode into Chez Scheme source text.

  Shape of the output: locals are l0..lN and operand-stack slots are s0..sM,
  each store binding a fresh variable in a let. Each basic block is a
  letrec-bound procedure over the live locals and its entry stack, and a jump
  is a tail call, which Chez compiles to a direct jump. A method with an
  exception table groups its blocks into regions, one per list of covering
  handlers. Jumps inside a region are tail calls too. A jump out of a covered
  region returns a thunk to the method's trampoline, so the handler installed
  on entry to the region (jtry) covers the region and nothing past it."
  ;; this namespace's own pop! (a block-state helper) replaces clojure.core's
  (:refer-clojure :exclude [pop!])
  (:require [clojure.string :as str]
            [champaca.bytecode :refer [array-loops backward-ranges blocks decode load-ops store-ops successors]]
            [champaca.cgtext :refer [*inline-site* *method-tag* at jstring-lit-form label method-global num-lit parse-desc site static-global str-lit sx sym wide?]]
            [champaca.intrinsics :refer [direct-form static-form]]
            [champaca.lambdas :refer [condy const-datum lambda-expr]]
            [champaca.linkage :refer [bad-class local-names missing-class missing-method refused-class resolve-bsm-args! resolve-site! resolve-type! wrong-kind]]))

;; --- per-instruction emission ---------------------------------------------------

(defn desc->t
  "Stack type of a field or return descriptor: I J F D or A."
  [d]
  (case (first d) (\B \C \S \Z \I) "I" \J "J" \F "F" \D "D" "A"))

(defn- cat2? [t] (contains? #{"J" "D" "j"} t))

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

;; --- pattern switches -----------------------------------------------------------------
;; typeSwitch and enumSwitch labels as a quoted list for the runtime's
;; jswitch-index (handles.ss): Class constants, Strings (enum constant names
;; under enumSwitch), Integers, and two dynamic constants javac makes:
;; ConstantBootstraps.primitiveClass for a primitive pattern, and an EnumDesc
;; for a qualified enum constant (`case Color.RED`) in a switch on another type.

(defn- condy-label
  "A switch label given as a dynamic constant: an enum constant qualified by
  its class (EnumDesc.of(ClassDesc.of(binary name), constant name)), or nil
  for a primitive pattern's (a primitive class, Boolean.TRUE or FALSE)."
  [cls e]
  (let [cp (:cp cls)
        [bsm-name [mh & args]] (condy cls e)
        target (fn [] (cp (:ref-idx (cp mh))))]
    (cond
      (= bsm-name "primitiveClass") nil
      (and (= bsm-name "invoke") (= "of" (:name (target))) (= "java/lang/Enum$EnumDesc" (:class (target))))
      (let [[_ [_ cname]] (condy cls (cp (first args)))]
        (sx "enum" (str-lit (str/replace (:value (cp cname)) "." "/"))
            (str-lit (:value (cp (second args))))))
      (= bsm-name "getStaticFinal") nil
      :else (throw (ex-info (str "switch label not in spike: condy " bsm-name) {:class (:name cls)})))))

(defn- switch-label
  "One case label, from its constant pool entry i, or nil for a primitive
  pattern's."
  [cls enum? i]
  (let [e ((:cp cls) i)]
    (case (:tag e)
      :class (sx "class" (str-lit (:name e)))
      :string (sx (if enum? "name" "string") (str-lit (:value e)))
      :int (sx "int" (str (:value e)))
      (:long :float :double) nil
      :dynamic (condy-label cls e)
      (throw (ex-info (str "switch label not in spike: " (:tag e)) {:class (:name cls)})))))

(defn- switch-labels
  "The case labels of a typeSwitch or enumSwitch as handles.ss's quoted list,
  or nil when one is a primitive pattern's (JEP 507, a preview in JDK 25: a
  primitive class, a long, float, double or boolean constant), whose match
  depends on the selector's type; the JDK's bootstrap runs for those."
  [cls enum? arg-idxs]
  (let [labels (map #(switch-label cls enum? %) arg-idxs)]
    (when (every? some? labels)
      (str "'" (apply sx labels)))))

;; render-steps' scopes: [slot binding-text children] each, innermost
;; last, the root [-1 nil children]

(defn- step-slot
  "The stack slot a variable named sN binds, or -1 for any other (a local)."
  [v]
  (if (str/starts-with? v "s") (parse-long (re-find #"\d+" v)) -1))

(defn- begin-form
  "xs as one statement: the one form, or a begin of them."
  [xs]
  (if (= 1 (count xs)) (first xs) (apply sx "begin" xs)))

(defn- close-scope
  "A scope as its let, (void) for a body when it has no children."
  [[_ head children]]
  (sx "let" head (begin-form (if (seq children) children ["(void)"]))))

(defn- add-to-scope
  "scopes with x as the innermost scope's last child."
  [scopes x]
  (update scopes (dec (count scopes)) update 2 conj x))

(defn- close-dead-scopes
  "scopes with each innermost scope binding a stack slot at or above depth
  closed into its parent, down to the first slot still live."
  [scopes depth]
  (let [[s :as top] (peek scopes)]
    (if (and (> (count scopes) 1) (>= s depth))
      (recur (add-to-scope (pop scopes) (close-scope top)) depth)
      scopes)))

(defn- step-scopes
  "The scopes after steps, from the root alone."
  [steps]
  (reduce (fn [scopes [k a b]]
            (case k
              :bind (conj scopes [(step-slot a) (sx (sx a b)) []])
              :pbind (conj scopes [(reduce min (map (comp step-slot first) a)) (apply sx (map (fn [[v e]] (sx v e)) a)) []])
              :set (add-to-scope scopes (sx "set!" a b))
              :do (add-to-scope scopes a)
              :depth (close-dead-scopes scopes a)))
          [[-1 nil []]] steps))

(defn- close-scopes
  "Every scope closed into its parent, innermost first, the root's children
  as one statement."
  [scopes]
  (if (= 1 (count scopes))
    (begin-form (nth (first scopes) 2))
    (recur (add-to-scope (pop scopes) (close-scope (peek scopes))))))

(defn- render-steps
  "A block's steps and its terminal expression as nested lets: every store or
  push binds a fresh variable, shadowing the old one, so Chez can keep values
  in registers (an assigned variable is boxed even when no closure captures
  it). A :set step assigns one of the few method-wide locals an exception
  handler has to see (compile-method*).

  A binding's let closes as soon as its value is dead, and what follows is a
  sibling statement in the enclosing let's begin rather than more nesting.
  A :depth step (emit-block, after each instruction) says how deep the stack
  is: a bound stack slot at or above it has been popped, so its let closes,
  innermost first, down to the first slot still live. A local (a set!-free
  store) stays open for the rest of the block, which reads it. Chez's expander
  walks the enclosing bindings for each variable it resolves, which made a
  straight-line method of thousands of pushes quadratic to compile:
  SystemModules' 21 KB module-descriptor builder took 4.6 s as one nest."
  [steps term]
  (close-scopes (add-to-scope (step-scopes steps) term)))

(def ^:private long-ops #{:ladd :lsub :lmul :land :lor :lxor :lshl :lshr :lushr :lneg :lcmp :l2i})

(defn- stack-effect
  "[values popped, values pushed] of an instruction, or nil for one
  halves-use? doesn't follow."
  [cp {:keys [op args]}]
  (case op
    (:aconst_null :iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4 :iconst_5
     :lconst_0 :lconst_1 :fconst_0 :fconst_1 :fconst_2 :dconst_0 :dconst_1 :bipush :sipush
     :ldc :ldc_w :ldc2_w :iload :lload :fload :dload :aload :getstatic :new) [0 1]
    (:nop :iinc) [0 0]
    (:istore :lstore :fstore :dstore :astore :pop :putstatic) [1 0]
    :putfield [2 0]
    (:iastore :lastore :fastore :dastore :aastore :bastore :castore :sastore) [3 0]
    (:getfield :i2l :l2i :i2d :l2d :i2f :l2f :f2d :d2f :f2i :d2i :f2l :d2l :i2b :i2c :i2s
     :ineg :lneg :fneg :dneg :arraylength :checkcast :instanceof :newarray :anewarray) [1 1]
    (:iadd :isub :imul :idiv :irem :ishl :ishr :iushr :iand :ior :ixor
     :ladd :lsub :lmul :ldiv :lrem :lshl :lshr :lushr :land :lor :lxor
     :fadd :fsub :fmul :fdiv :frem :dadd :dsub :dmul :ddiv :drem
     :lcmp :fcmpl :fcmpg :dcmpl :dcmpg
     :iaload :laload :faload :daload :aaload :baload :caload :saload) [2 1]
    (:invokevirtual :invokeinterface :invokespecial :invokestatic)
    (let [{as :args ret :ret} (parse-desc (:desc (cp (first args))))]
      [(+ (count as) (if (= op :invokestatic) 0 1)) (if (= ret "V") 0 1)])
    nil))

(defn- halves-use?
  "Whether the value an instruction leaves on top of the stack goes next to
  something that takes a long as halves: a long operation (as the value a
  shift shifts, not its count), or a store to a local kept as halves. insns
  are the block's instructions after it."
  [cp split insns]
  (loop [insns (take 32 insns) pos 0]
    (if-let [i (first insns)]
      (if-let [[pops pushes] (stack-effect cp i)]
        (if (> pops pos)
          (let [{:keys [op args]} i]
            (boolean (or (and (long-ops op) (not (and (#{:lshl :lshr :lushr} op) (= pos 0))))
                         (and (= op :lstore) (split (first args))))))
          (recur (rest insns) (+ (- pos pops) pushes)))
        false)
      false)))

;; --- one basic block ----------------------------------------------------------
;; emit-block translates a block one instruction at a time. Its state, e, is
;; the ctx it was given plus:
;;   :st       the compile-time stack, a vector of types (I J F D A, j for a
;;             long kept as halves)
;;   :stmts    the steps so far, in order: [:bind var expr], [:pbind [[var
;;             expr] ...]] (one parallel binding), [:set var expr], [:do expr]
;;             or [:depth d], rendered as nested lets (render-steps)
;;   :succ     {pc entry-stack} of the blocks it jumps to
;;   :consts   literal values on the stack by index: a shift's count, a
;;             long's halves
;;   :origins  in a fast copy, the stack values an aload of a hoisted local
;;             pushed, by index
;;   :used     the hoisted locals an array operation used
;;   :terminal the block's last expression once an instruction ends it
;;   :fused    set when an instruction also translated the one after it

(defn- sv [i] (str "s" i))
(defn- sv-h [i] (str "s" i "h"))
(defn- sv-l [i] (str "s" i "l"))

(defn- block-state [ctx blk entry]
  (let [cls (:cls ctx)]
    (assoc ctx
           :cp (:cp cls) :this-class (:name cls) :start-pc (:pc (first blk))
           :st (volatile! entry) :stmts (volatile! (transient [])) :succ (volatile! {})
           :consts (volatile! {}) :origins (volatile! {}) :used (volatile! #{})
           :terminal (volatile! nil) :fused (volatile! false))))

(defn- step! [e step] (vswap! (:stmts e) conj! step))
(defn- stack-depth [e] (count @(:st e)))
(defn- top-t [e] (peek @(:st e)))
(defn- bind! [e v expr] (step! e [:bind v expr]))
(defn- stmt! [e x] (step! e [:do x]))
(defn- store! [e i expr] (step! e [(if ((:boxed e) i) :set :bind) (str "l" i) expr]))
(defn- terminate! [e x] (vreset! (:terminal e) x))

(defn- forget!
  "Drop what is known about stack index i: its literal value, its origin."
  [e i]
  (vswap! (:consts e) dissoc i)
  (vswap! (:origins e) dissoc i))

(defn- push! [e expr t]
  (let [i (stack-depth e)]
    (forget! e i)
    (bind! e (sv i) expr)
    (vswap! (:st e) conj t)))

(defn- push-const! [e v t]
  (let [i (stack-depth e)]
    (push! e (num-lit v) t)
    (vswap! (:consts e) assoc i v)))

(defn- push-halves!
  "Push a long as halves, both computed from what was there before."
  [e hx lx]
  (let [i (stack-depth e)]
    (forget! e i)
    (step! e [:pbind [[(sv-h i) hx] [(sv-l i) lx]]])
    (vswap! (:st e) conj "j")))

(defn- pop!
  "The popped value as an expression: a long kept as halves is boxed."
  [e]
  (let [i (dec (stack-depth e)) t (top-t e)]
    (vswap! (:st e) pop)
    (forget! e i)
    (if (= t "j") (sx "lh-box" (sv-h i) (sv-l i)) (sv i))))

(defn- pop-halves!
  "A long's halves, split here if it was boxed (lh-split-l reads what
  lh-split-h put in long-buf, so nothing comes between them)."
  [e]
  (let [i (dec (stack-depth e)) t (top-t e) c (@(:consts e) i)]
    (vswap! (:st e) pop)
    (forget! e i)
    (cond (= t "j") [(sv-h i) (sv-l i)]
          c [(str (bit-and (bit-shift-right c 32) 0xffffffff)) (str (bit-and c 0xffffffff))]
          :else (do (bind! e (sv-h i) (sx "lh-split-h" (sv i)))
                    (bind! e (sv-l i) (sx "lh-split-l" (sv i)))
                    [(sv-h i) (sv-l i)]))))

(defn- popn! [e n] (vec (reverse (repeatedly n #(pop! e)))))
(defn- bin! [e f t] (let [b (pop! e) a (pop! e)] (push! e (f a b) t)))
(defn- un! [e f t] (push! e (f (pop! e)) t))

(defn- halves?
  "Whether a long operation on the top n values works on halves: one of
  them is in halves, or the result goes on to a long operation."
  [e n insns]
  (boolean (or (some #{"j"} (take-last n @(:st e)))
               (halves-use? (:cp e) (:split e) (rest insns)))))

(defn- hoisted
  "[vector length] of the array at stack index i when it's a hoisted local's,
  which counts as a use."
  [e i]
  (when-let [k (@(:origins e) i)]
    (vswap! (:used e) conj k)
    ((:hoist e) k)))

(defn- jump-to
  "The code of a jump to pc. A block takes its stack boxed, and a jump passes
  the live locals and the stack as arguments. A backward jump is where a loop
  can spin, so it is where a thread lets another one run (jsafepoint,
  natives.ss)."
  [e pc]
  (let [st @(:st e)]
    (vswap! (:succ e) assoc pc (mapv #(if (= % "j") "J" %) st))
    (let [j ((:jump e) pc (concat (mapcat #(local-names (:split e) %) (:live e))
                                  (map-indexed (fn [i t] (if (= t "j") (sx "lh-box" (sv-h i) (sv-l i)) (sv i))) st)))]
      (if (<= pc (:start-pc e)) (sx "begin" "(when (#3%fx> gil-ready 0) (jsafepoint))" j) j))))

(defn- branch! [e test target fall]
  (terminate! e (sx "if" test (jump-to e target) (jump-to e fall))))

(defn- init-class! [e c pc]
  (when (not= c (:this-class e))
    (stmt! e (sx "jinit-ic" ((:ic! e)) (str "'" (sym c)) (site pc)))))

(def ^:dynamic *plain*
  "True to translate a method as written: no callee compiled into it, no
  second copy of its array loops, every call a call (none done in line at
  the call site), and no CHAMPACA_BT wrapper around its body. The port tool
  reads this form (jit.clj method-text); what runs is never translated this
  way."
  false)

(defn- callee
  "What a direct call names: the callee inlined into this method when it is a
  small leaf (compile-method), else its top-level definition."
  [e pc decl name desc]
  (or (binding [*inline-site* [pc *method-tag*]] ((:inline! e) decl name desc))
      (method-global decl name desc)))

(defn- shuffle!
  "Copy the top k values to below the m values under them (dup family). A
  slot that keeps its own value (dup's original) isn't rebound, so its let
  can close while the copy lives."
  [e k m]
  (let [st @(:st e)
        base (- (count st) k m)
        items (subvec st base)
        order (concat (range (- (count items) k) (count items)) (range (count items)))
        moves (remove (fn [[v x]] (= v x))
                      (apply concat
                             (map-indexed (fn [j src]
                                            (let [to (+ base j) from (+ base src)]
                                              (if (= "j" (nth items src))
                                                [[(sv-h to) (sv-h from)] [(sv-l to) (sv-l from)]]
                                                [[(sv to) (sv from)]])))
                                          order)))
        below (fn [m] (into {} (remove (fn [[i _]] (>= i base))) m))]
    (vswap! (:consts e) below)
    (vswap! (:origins e) below)
    (when (seq moves) (step! e [:pbind moves]))
    (vreset! (:st e) (into (subvec st 0 base) (map items order)))))

;; --- constants, locals, arrays, the stack --------------------------------------

(def ^:private const-values
  {:iconst_m1 [-1 "I"] :iconst_0 [0 "I"] :iconst_1 [1 "I"] :iconst_2 [2 "I"]
   :iconst_3 [3 "I"] :iconst_4 [4 "I"] :iconst_5 [5 "I"]
   :lconst_0 [0 "J"] :lconst_1 [1 "J"]})

(def ^:private float-consts
  {:fconst_0 ["0.0" "F"] :fconst_1 ["1.0" "F"] :fconst_2 ["2.0" "F"]
   :dconst_0 ["0.0" "D"] :dconst_1 ["1.0" "D"]})

(defn- emit-const [e {:keys [op args]} _]
  (cond (= op :aconst_null) (push! e "#f" "A")
        (#{:bipush :sipush} op) (push-const! e (first args) "I")
        (const-values op) (let [[v t] (const-values op)] (push-const! e v t))
        :else (let [[v t] (float-consts op)] (push! e v t))))

(defn- emit-ldc [e {:keys [args]} _]
  (let [a0 (first args) cls (:cls e) this-class (:this-class e) c ((:cp e) a0)]
    (case (:tag c)
      :int (push-const! e (:value c) "I")
      :long (push-const! e (:value c) "J")
      :float (push! e (num-lit (:value c)) "F")
      :double (push! e (num-lit (:value c)) "D")
      :string (push! e (jstring-lit-form (:value c)) "A")
      ;; resolved when it runs, as the JVM resolves it, through this class's
      ;; defining loader (runtime.ss jldc-class)
      :class (push! e (sx "jldc-class" (str-lit (:name c)) (str-lit this-class)) "A")
      (:methodtype :methodhandle :dynamic)
      (let [d (if (= :dynamic (:tag c)) (:desc c) "Ljava/lang/Object;")]
        ;; resolved once per constant-pool entry (const-datum's entry)
        (push! e (sx "jconst" (str-lit this-class) (str "'" (const-datum cls a0)) (str-lit d))
               (desc->t d)))
      (throw (ex-info "ldc constant not in spike" {:entry c})))))

(def ^:private load-types {:iload "I" :fload "F" :dload "D"})

(defn- emit-load [e {:keys [op args]} _]
  (let [a0 (first args)]
    (cond (load-types op) (push! e (str "l" a0) (load-types op))
          (= op :aload) (do (push! e (str "l" a0) "A")
                            (when (get (:hoist e) a0)
                              (vswap! (:origins e) assoc (dec (stack-depth e)) a0)))
          ((:split e) a0) (push-halves! e (str "l" a0 "h") (str "l" a0 "l"))
          :else (push! e (str "l" a0) "J"))))

(defn- emit-store [e {:keys [op args]} _]
  (let [a0 (first args)]
    (if (and (= op :lstore) ((:split e) a0))
      (let [[vh vl] (pop-halves! e)]
        (step! e [:pbind [[(str "l" a0 "h") vh] [(str "l" a0 "l") vl]]]))
      (store! e a0 (pop! e)))))

(defn- emit-iinc [e {:keys [args]} _]
  (let [a0 (first args)]
    (store! e a0 (sx "wrap-int" (sx "ix+" (str "l" a0) (str (second args)))))))

(def ^:private array-load-types {:laload "J" :faload "F" :daload "D" :aaload "A"})

(defn- array-op
  "The name of the array access at pc in e's method: runtime.ss's inline
  macro, or in a method over ic-vector-limit, outside its loops, the
  procedure of the same name with big-."
  [e pc op]
  (if (and (:big? e) (not (some (fn [[lo hi]] (<= lo pc hi)) (:loop-ranges e))))
    (str "big-" op)
    op))

(defn- emit-array-load [e {:keys [op pc]} _]
  (let [hv (hoisted e (- (stack-depth e) 2))]
    (bin! e (fn [a i] (cond hv (let [[v n] hv] (sx "vec-ref" v n i (site pc)))
                            ;; the port reads a char[]'s element as a char
                            (and *plain* (= :caload op)) (sx "char-ref" a i (site pc))
                            :else (sx (array-op e pc "array-ref") a i (site pc))))
          (array-load-types op "I"))))

(defn- emit-aastore [e {:keys [pc]} _]
  (let [[a i v] (popn! e 3)]
    (stmt! e (sx (array-op e pc "array-store!") ((:ic! e)) a i v (site pc)))))

(defn- emit-array-store [e {:keys [op pc]} _]
  (let [hv (hoisted e (- (stack-depth e) 3))
        [a i v] (popn! e 3)
        x (case op :bastore (sx "wrap-byte" v) :castore (sx "ix-and" v "#xffff") :sastore (sx "wrap-short" v) v)]
    (stmt! e (cond hv (let [[vv n] hv] (sx "vec-set!" vv n i x (site pc)))
                   (and *plain* (= :castore op)) (sx "char-set!" a i x (site pc))
                   :else (sx (array-op e pc "array-set!") a i x (site pc))))))

(defn- below-top-cat2?
  "Whether the value n below the top of the stack is a long or double."
  [e n]
  (let [st @(:st e)] (cat2? (nth st (- (count st) n 1)))))

(defn- emit-stack-op [e {:keys [op]} _]
  (let [k2 (if (cat2? (top-t e)) 1 2)]
    (case op
      :pop (pop! e)
      :pop2 (if (= k2 1) (pop! e) (do (pop! e) (pop! e)))
      :dup (shuffle! e 1 0)
      :dup_x1 (shuffle! e 1 1)
      :dup_x2 (shuffle! e 1 (if (below-top-cat2? e 1) 1 2))
      :dup2 (shuffle! e k2 0)
      :dup2_x1 (shuffle! e k2 1)
      :dup2_x2 (shuffle! e k2 (if (below-top-cat2? e k2) 1 2))
      :swap (let [t1 (top-t e) v1 (pop! e) t2 (top-t e) v2 (pop! e)]
              (step! e [:pbind [[v1 v2] [v2 v1]]])
              (vswap! (:st e) conj t1 t2)))))

;; --- arithmetic and conversions ------------------------------------------------

(def ^:private simple-ops
  "Instructions that pop n values and push f of them as type t: [n t f], f
  taking the instruction's pc first."
  (let [fl {:dadd "fl+" :dsub "fl-" :dmul "fl*" :ddiv "fl/"
            :fadd "fl+" :fsub "fl-" :fmul "fl*" :fdiv "fl/"}
        dbl (fn [op] [2 "D" (fn [_ a b] (sx (fl op) a b))])
        flt (fn [op] [2 "F" (fn [_ a b] (sx "f32" (sx (fl op) a b)))])
        int2 (fn [f] [2 "I" (fn [_ a b] (sx f a b))])
        wrapped (fn [f] [2 "I" (fn [_ a b] (sx "wrap-int" (sx f a b)))])
        un (fn [t f] [1 t (fn [_ a] (f a))])]
    {:iadd (wrapped "ix+") :isub (wrapped "ix-") :imul (wrapped "ix*")
     :idiv [2 "I" (fn [pc a b] (sx "int-div" a b (site pc)))]
     :irem [2 "I" (fn [pc a b] (sx "int-rem" a b (site pc)))]
     :ineg (un "I" #(sx "wrap-int" (sx "ix-" "0" %)))
     :ishl (int2 "int-shl") :ishr (int2 "int-shr") :iushr (int2 "int-ushr")
     :iand (int2 "ix-and") :ior (int2 "ix-or") :ixor (int2 "ix-xor")
     :ldiv [2 "J" (fn [pc a b] (sx (at pc "ldiv") a b))]
     :lrem [2 "J" (fn [pc a b] (sx (at pc "lrem") a b))]
     :dadd (dbl :dadd) :dsub (dbl :dsub) :dmul (dbl :dmul) :ddiv (dbl :ddiv)
     :fadd (flt :fadd) :fsub (flt :fsub) :fmul (flt :fmul) :fdiv (flt :fdiv)
     :drem [2 "D" (fn [_ a b] (sx "jfrem" a b))]
     :frem [2 "F" (fn [_ a b] (sx "f32" (sx "jfrem" a b)))]
     :dneg (un "D" #(sx "fl*" "-1.0" %))
     :fneg (un "F" #(sx "fl*" "-1.0" %))
     :fcmpl [2 "I" (fn [_ a b] (sx "jfcmp" a b "-1"))]
     :dcmpl [2 "I" (fn [_ a b] (sx "jfcmp" a b "-1"))]
     :fcmpg [2 "I" (fn [_ a b] (sx "jfcmp" a b "1"))]
     :dcmpg [2 "I" (fn [_ a b] (sx "jfcmp" a b "1"))]
     :i2d (un "D" #(sx "fixnum->flonum" %))
     :l2d (un "D" #(sx "inexact" %))
     :i2f (un "F" #(sx "f32" (sx "fixnum->flonum" %)))
     :l2f (un "F" #(sx "f32" (sx "inexact" %)))
     :f2d (un "D" identity)
     :d2f (un "F" #(sx "f32" %))
     :f2i (un "I" #(sx "d2i" %)) :d2i (un "I" #(sx "d2i" %))
     :f2l (un "J" #(sx "d2l" %)) :d2l (un "J" #(sx "d2l" %))
     :i2b (un "I" #(sx "wrap-byte" %))
     :i2c (un "I" #(sx "ix-and" % "#xffff"))
     :i2s (un "I" #(sx "wrap-short" %))}))

(defn- emit-simple [e {:keys [op pc]} _]
  (let [[n t f] (simple-ops op)]
    (if (= n 2)
      (bin! e #(f pc %1 %2) t)
      (un! e #(f pc %) t))))

(defn- long-halves-op
  "A long operation on the two longs on top as halves: the low halves' sum,
  whose bit 32 is the carry, the difference, negative for a borrow, and so
  on."
  [e op]
  (let [[bh bl] (pop-halves! e) [ah al] (pop-halves! e) r (stack-depth e)]
    (case op
      :ladd (let [c (str "s" r "c")]
              (bind! e c (sx "lh-add-c" al bl))
              (push-halves! e (sx "lh-add-h" ah bh c) (sx "lh-u32" c)))
      :lsub (let [c (str "s" r "c")]
              (bind! e c (sx "lh-sub-c" al bl))
              (push-halves! e (sx "lh-sub-h" ah bh c) (sx "lh-u32" c)))
      :lmul (let [u (str "s" r "u") w (str "s" r "w")]
              (bind! e u (sx "lh-mul-u" al bl))
              (bind! e w (sx "lh-mul-w" al bl u))
              (push-halves! e (sx "lh-mul-h" ah al bh bl u w) (sx "lh-u32" w)))
      :land (push-halves! e (sx "lh-and" ah bh) (sx "lh-and" al bl))
      :lor (push-halves! e (sx "lh-or" ah bh) (sx "lh-or" al bl))
      :lxor (push-halves! e (sx "lh-xor" ah bh) (sx "lh-xor" al bl)))))

(defn- emit-long-op
  "On halves when an operand is in halves or the result goes on to a long
  operation; else runtime.ss's long macros, fixnums inline and anything else
  on 32-bit halves, boxed."
  [e {:keys [op]} insns]
  (if (halves? e 2 insns)
    (long-halves-op e op)
    (bin! e #(sx (name op) %1 %2) "J")))

(defn- emit-long-shift
  "A literal count folds the shift's tests away."
  [e {:keys [op]} insns]
  (if (or (= "j" (nth @(:st e) (- (stack-depth e) 2))) (halves-use? (:cp e) (:split e) (rest insns)))
    (let [c (@(:consts e) (dec (stack-depth e)))
          cnt (pop! e)
          [ah al] (pop-halves! e)
          r (stack-depth e)
          n (if c
              (str (bit-and c 63))
              (let [v (str "s" r "n")] (bind! e v (sx "ix-and" cnt "63")) v))]
      (case op
        :lshl (push-halves! e (sx "lh-shl-h" ah al n) (sx "lh-shl-l" al n))
        :lushr (push-halves! e (sx "lh-ushr-h" ah n) (sx "lh-ushr-l" ah al n))
        :lshr (let [sg (str "s" r "s")]
                (bind! e sg (sx "lh-s32" ah))
                (push-halves! e (sx "lh-shr-h" sg n) (sx "lh-shr-l" ah al n sg)))))
    (bin! e #(sx (name op) %1 %2) "J")))

(defn- emit-lneg [e _ insns]
  (if (halves? e 1 insns)
    (let [[ah al] (pop-halves! e) c (str "s" (stack-depth e) "c")]
      (bind! e c (sx "lh-sub-c" "0" al))
      (push-halves! e (sx "lh-sub-h" "0" ah c) (sx "lh-u32" c)))
    (un! e #(sx "lneg" %) "J")))

(def ^:private lcmp-branch
  {:ifeq #(sx "=" %1 %2) :ifne #(sx "not" (sx "=" %1 %2)) :iflt #(sx "<" %1 %2)
   :ifge #(sx ">=" %1 %2) :ifgt #(sx ">" %1 %2) :ifle #(sx "<=" %1 %2)})

(defn- lcmp-halves-test
  "The test a branch on lcmp's result makes, for halves a and b."
  [op [ah al] [bh bl]]
  (let [eq (sx "lh=" ah al bh bl) lt (sx "lh<" ah al bh bl) gt (sx "lh<" bh bl ah al)]
    ({:ifeq eq :ifne (sx "not" eq) :iflt lt :ifge (sx "not" lt) :ifgt gt :ifle (sx "not" gt)} op)))

(defn- emit-lcmp
  "lcmp then a branch on its result, the usual shape, is one comparison: the
  branch ends the block, so it's the last instruction."
  [e _ insns]
  (let [{nop :op nargs :args npc :pc nlen :len} (second insns)
        fuse! (fn [test] (branch! e test (first nargs) (+ npc nlen)) (vreset! (:fused e) true))]
    (if (some #{"j"} (take-last 2 @(:st e)))
      (let [b (pop-halves! e) a (pop-halves! e)]
        (if-let [test (lcmp-halves-test nop a b)]
          (fuse! test)
          (push! e (sx "lh-cmp" (first a) (second a) (first b) (second b)) "I")))
      (if-let [test (lcmp-branch nop)]
        (let [b (pop! e) a (pop! e)] (fuse! (test a b)))
        (bin! e #(sx "lcmp" %1 %2) "I")))))

(defn- emit-i2l [e _ insns]
  (if (halves-use? (:cp e) (:split e) (rest insns))
    (let [v (pop! e)] (push-halves! e (sx "lh-i2l-h" v) (sx "lh-u32" v)))
    (un! e identity "J")))

(defn- emit-l2i
  "The low half as a signed int."
  [e _ _]
  (if (= "j" (top-t e))
    (let [[_ vl] (pop-halves! e)] (push! e (sx "lh-s32" vl) "I"))
    (un! e #(sx "l2i" %) "I")))

;; --- control flow ----------------------------------------------------------------

(def ^:private cmp0 {:ifeq "ix=" :ifne "ix<>" :iflt "ix<" :ifge "ix>=" :ifgt "ix>" :ifle "ix<="})
(def ^:private cmp2 {:if_icmpeq "ix=" :if_icmpne "ix<>" :if_icmplt "ix<" :if_icmpge "ix>="
                     :if_icmpgt "ix>" :if_icmple "ix<="})

(defn- emit-branch [e {:keys [op args pc len]} _]
  (let [target (first args) fall (+ pc len)]
    (cond
      (cmp0 op) (let [a (pop! e)] (branch! e (sx (cmp0 op) a "0") target fall))
      (cmp2 op) (let [b (pop! e) a (pop! e)] (branch! e (sx (cmp2 op) a b) target fall))
      (#{:if_acmpeq :if_acmpne} op)
      (let [b (pop! e) a (pop! e) t (sx "eq?" a b)]
        (branch! e (if (= op :if_acmpeq) t (sx "not" t)) target fall))
      :else (let [a (pop! e)] (branch! e (if (= op :ifnull) (sx "not" a) a) target fall)))))

(defn- emit-goto [e {:keys [args]} _] (terminate! e (jump-to e (first args))))

(defn- emit-jsr
  "jsr and jsr_w: the returnAddress is the pc of the next instruction, a
  fixnum, pushed before the jump into the subroutine."
  [e {:keys [args pc len]} _]
  (push! e (str (+ pc len)) "A")
  (terminate! e (jump-to e (first args))))

(defn- emit-ret
  "ret: a dispatch on the local's returnAddress over the pcs decode found it
  can hold (with-ret-targets). Any other value is an error rather than a jump
  to one of them, so a target set that missed a path shows."
  [e {:keys [args targets pc]} _]
  (let [v (str "l" (first args))]
    (terminate! e (apply sx "case" v
                         (concat (map (fn [t] (sx (str "(" t ")") (jump-to e t))) targets)
                                 [(sx "else" (sx "error" "'ret" (str-lit (str "no known return address at pc " pc ", got ~s")) v))])))))

(defn- emit-switch [e {:keys [args]} _]
  (let [k (pop! e) [dflt targets] args]
    (terminate! e (apply sx "case" k
                         (concat (for [[v t] targets] (sx (sx (str v)) (jump-to e t)))
                                 [(sx "else" (jump-to e dflt))])))))

(defn- emit-end [e {:keys [op pc]} _]
  (case op
    :return (terminate! e "(void)")
    :athrow (terminate! e (sx "jathrow-at" (pop! e) (site pc)))
    (terminate! e (pop! e))))

;; monitorenter's call carries its pc like any call (at): a thread blocked
;; entering a contended monitor waits inside it, and another thread reading
;; its stack (Thread.getStackTrace) reads the line off that frame
(defn- emit-monitor [e {:keys [op pc]} _]
  (stmt! e (sx (if (= op :monitorenter) (at pc "jmonitor-enter") "jmonitor-exit") (sx "nn-at" (pop! e) (site pc)))))

;; --- fields ------------------------------------------------------------------------

(defn- emit-field [e {:keys [op args pc]} _]
  (let [{:keys [class name desc]} ((:cp e) (first args))
        resolve (:resolve e)]
    (case op
      :getstatic (let [decl ((:resolve-static-field resolve) class name)]
                   (init-class! e decl pc)
                   (push! e (static-global decl name) (desc->t desc)))
      :putstatic (let [decl ((:resolve-static-field resolve) class name)]
                   (init-class! e decl pc)
                   (stmt! e (sx "set!" (static-global decl name) (pop! e))))
      :getfield (let [idx ((:field-index resolve) class name)]
                  (un! e #(sx "field-ref" % (str idx) (site pc)) (desc->t desc)))
      :putfield (let [idx ((:field-index resolve) class name)
                      v (pop! e) o (pop! e)]
                  (stmt! e (sx "field-set!" o (str idx) v (site pc)))))))

;; --- calls ---------------------------------------------------------------------------

(defn- dispatch-call
  "A virtual or interface call that dispatches on the receiver. A method
  nothing declares is NoSuchMethodError when the call runs, whatever the
  receiver; a static one is IncompatibleClassChangeError, as resolution
  says. One nothing can override is called directly, after the null check
  dispatch would do (for invokeinterface that is a private interface method,
  which javac calls this way). A package-private method is overridden only
  from its run-time package (JVMS 5.4.5), which the vtable's keys can't say."
  [e op pc {:keys [class name desc]} recv argv]
  (let [resolve (:resolve e)
        key #(str "'" (sym (str name desc)))]
    (when-not (str/starts-with? class "[") ((:ensure-loaded resolve) class))
    ((:check-virtual resolve) class name desc)
    ((:check-kind resolve) (if (= op :invokeinterface) :interface :virtual) class name desc)
    (if-let [decl ((:direct-target resolve) class name desc)]
      (or (and (not *plain*)
               (direct-form (str decl "." name desc) (sx "nn-at" recv (site pc)) argv (at pc (method-global decl name desc))))
          (apply sx (at pc (callee e pc decl name desc)) (sx "nn-at" recv (site pc)) argv))
      (if-let [pp (and (= op :invokevirtual) ((:package-private-decl resolve) class name desc))]
        (apply sx (at pc (sx "jvirt-pp-ic" ((:ic! e)) recv (key) (str "'" (sym pp)) (site pc))) recv argv)
        (apply sx (at pc (sx "jvirt-ic" ((:ic! e)) recv (key) (site pc))) recv argv)))))

(defn- virtual-call
  "invokevirtual or invokeinterface. A VarHandle access mode and
  MethodHandle.invoke/invokeExact are signature-polymorphic: the runtime
  adapts the call site's types to the access mode's, as asType would."
  [e op pc {:keys [class name desc] :as ref} recv argv]
  (let [resolve (:resolve e)]
    (cond
      (and (= class "java/lang/invoke/VarHandle") (varhandle-modes name))
      (do (resolve-site! resolve desc)
          (apply sx (at pc "vh-call") (sx "nn-at" recv (site pc)) (str-lit name) (str-lit desc) argv))
      (and (= class "java/lang/invoke/MethodHandle") (#{"invokeExact" "invoke"} name))
      (do (resolve-site! resolve desc)
          (apply sx (at pc (if (= name "invokeExact") "mh-call-exact" "mh-call"))
                 (sx "nn-at" recv (site pc)) (str-lit desc) argv))
      :else (dispatch-call e op pc ref recv argv))))

(defn- emit-invoke [e {:keys [op args pc]} _]
  (let [{:keys [class name desc] :as ref} ((:cp e) (first args))
        resolve (:resolve e)
        {params :args ret :ret} (parse-desc desc)
        argv (popn! e (count params))
        recv (when-not (= op :invokestatic) (pop! e))
        call (case op
               (:invokevirtual :invokeinterface) (virtual-call e op pc ref recv argv)
               :invokespecial
               (let [decl ((:resolve-method resolve) class name desc)]
                 ((:check-kind resolve) :special class name desc)
                 ;; Object's constructor does nothing
                 (when-not (and (= decl "java/lang/Object") (= name "<init>"))
                   (apply sx (at pc (callee e pc decl name desc)) recv argv)))
               :invokestatic
               (let [decl ((:resolve-method resolve) class name desc)]
                 ((:check-kind resolve) :static class name desc)
                 (init-class! e decl pc)
                 (or (and (not *plain*)
                          (static-form (str decl "." name desc) argv (at pc (method-global decl name desc)) (:ic! e) (site pc)))
                     (apply sx (at pc (callee e pc decl name desc)) argv))))]
    (cond (nil? call) nil
          (= ret "V") (stmt! e call)
          :else (push! e call (desc->t ret)))))

;; --- invokedynamic -------------------------------------------------------------------

(defn- recipe-parts
  "A makeConcatWithConstants recipe as the text of its parts: each run of
  literal characters as a string literal, \u0001 (the next argument) as 1
  and \u0002 (the next constant) as 2."
  [recipe]
  (->> (partition-by #(contains? #{\u0001 \u0002} %) recipe)
       (mapcat (fn [run]
                 (if (contains? #{\u0001 \u0002} (first run))
                   (map #(if (= % \u0001) "1" "2") run)
                   [(str-lit (apply str run))])))))

(defn- concat-call
  "makeConcatWithConstants: jconcat over the recipe, the arguments as text
  and the constants. A code-unit vector (a lone surrogate) is quoted, being
  evaluated; a char is one code unit, which may be half a surrogate pair; a
  reference goes as itself, since jconcat takes its String or toString."
  [e bsm params argv]
  (let [cp (:cp e)
        lit #(if (vector? %) (str "'" (str-lit %)) (str-lit (str %)))
        recipe (:value (cp (first (:args bsm))))
        consts (map #(lit (:value (cp %))) (rest (:args bsm)))
        conv (map (fn [t v]
                    (case t
                      ;; an int or a long as the number: jconcat writes its digits
                      ;; (plain keeps number->string, which tells the port tool the
                      ;; argument is a number and not a reference)
                      ("I" "S" "B" "J") (if *plain* (sx "number->string" v) v)
                      "F" (sx "java-float->string" v)
                      "D" (sx "jdouble->string" v)
                      "C" (sx "list" v)
                      "Z" (sx "jbool->string" v)
                      v))
                  params argv)]
    (if (vector? recipe)
      (sx "jconcat" (lit recipe) (apply sx "list" conv) (apply sx "list" consts))
      ;; the recipe parsed here, once: literals, 1 for the next argument, 2
      ;; for the next constant (strings.ss jconcat-parts)
      (sx "jconcat-parts" (str "'" (apply sx (recipe-parts recipe))) (apply sx "list" conv) (apply sx "list" consts)))))

(defn- record-op-call
  "java.lang.runtime.ObjectMethods' bootstrap: a record's toString, equals or
  hashCode."
  [e bsm name argv]
  (let [cp (:cp e)
        [_rec _names-idx & getters] (:args bsm)
        fields (for [g getters :let [{:keys [class name desc]} (cp (:ref-idx (cp g)))]]
                 (sx "list" (str-lit name) (str ((:field-index (:resolve e)) class name)) (str-lit desc)))]
    (apply sx "jrecord-op" (str-lit name)
           (str "(list " (str/join " " fields) ")") argv)))

(defn- indy-call
  "A bootstrap that runs for real, once per call site (handles.ss)."
  [e pc bsm name desc argv]
  (let [cls (:cls e)]
    (apply sx (at pc "indy-call") (str "'" (sym (str *method-tag* "@" pc))) (str-lit (:this-class e))
           (str "'" (sx (const-datum cls (:ref-idx bsm))
                        (apply sx (map #(const-datum cls %) (:args bsm)))))
           (str-lit name) (str-lit desc) argv)))

(def ^:private compiled-bootstraps
  "The JDK bootstraps codegen compiles instead of running, by method name and
  the class that declares it."
  {"makeConcatWithConstants" "java/lang/invoke/StringConcatFactory"
   "bootstrap" "java/lang/runtime/ObjectMethods"
   "metafactory" "java/lang/invoke/LambdaMetafactory"
   "altMetafactory" "java/lang/invoke/LambdaMetafactory"
   "typeSwitch" "java/lang/runtime/SwitchBootstraps"
   "enumSwitch" "java/lang/runtime/SwitchBootstraps"})

(defn- bootstrap-name
  "The bootstrap's method name when it's one of the JDK's that codegen
  compiles (compiled-bootstraps), else its class and name, so it runs for
  real: Groovy's IndyInterface.bootstrap is no record's ObjectMethods."
  [cp bsm]
  (let [bsm-ref (cp (:ref-idx (cp (:ref-idx bsm))))
        n (:name bsm-ref)]
    (if (= (:class bsm-ref) (compiled-bootstraps n))
      n
      (str (:class bsm-ref) "." n))))

(defn- emit-indy [e {:keys [args pc]} _]
  (let [cls (:cls e) resolve (:resolve e)
        {:keys [bsm-idx name desc]} ((:cp e) (first args))
        _ (resolve-site! resolve desc)
        {params :args ret :ret} (parse-desc desc)
        bsm (nth (:bootstrap-methods cls) bsm-idx)
        bsm-name (bootstrap-name (:cp e) bsm)
        argv (popn! e (count params))
        compiled! #(resolve-bsm-args! cls resolve (:args bsm))]
    (case bsm-name
      "makeConcatWithConstants" (do (compiled!) (push! e (concat-call e bsm params argv) "A"))
      "bootstrap" (do (compiled!) (push! e (record-op-call e bsm name argv) (desc->t ret)))
      ("metafactory" "altMetafactory")
      (do (compiled!) (push! e (lambda-expr e pc {:sam-name name :iface-desc ret :captured argv :bsm bsm}) "A"))
      ;; java.lang.runtime.SwitchBootstraps: pattern switches on a reference
      ;; (an object or an array) whose labels handles.ss matches; a primitive
      ;; selector or label runs for real
      ("typeSwitch" "enumSwitch")
      (if-let [labels (when (#{\L \[} (first (first params)))
                        (switch-labels cls (= bsm-name "enumSwitch") (:args bsm)))]
        (do (compiled!) (push! e (sx "jswitch-index" (first argv) (second argv) labels) "I"))
        (push! e (indy-call e pc bsm name desc argv) "I"))
      (let [call (indy-call e pc bsm name desc argv)]
        (if (= ret "V") (stmt! e call) (push! e call (desc->t ret)))))))

;; --- objects and arrays ----------------------------------------------------------------

(defn- emit-new [e {:keys [args pc]} _]
  (let [c (:name ((:cp e) (first args)))]
    ((:ensure-loaded (:resolve e)) c)
    (push! e (sx "jnew-ic" ((:ic! e)) (str "'" (sym c)) (site pc)) "A")))

(defn- emit-newarray
  "The element descriptor is interned once per method (runtime.ss)."
  [e {:keys [args pc]} _]
  (let [elem (newarray-elem (first args)) el ((:ic! e) (sx "intern-elem" (str-lit elem)))]
    (un! e #(sx (at pc "jnewarray-interned") el % (if (#{"F" "D"} elem) "0.0" "0")) "A")))

(defn- emit-anewarray
  "The element class resolves here, as the JVM's anewarray does: making the
  array alone wouldn't load it."
  [e {:keys [args pc]} _]
  (let [c (:name ((:cp e) (first args)))
        arr? (str/starts-with? c "[")
        _ (when-not arr? ((:ensure-loaded (:resolve e)) c))
        el ((:ic! e) (sx "intern-elem" (str-lit (if arr? c (str "L" c ";")))))]
    (un! e #(sx (at pc "jnewarray-interned") el % "#f") "A")))

(defn- emit-multianewarray [e {:keys [args pc]} _]
  (let [c (:name ((:cp e) (first args))) dims (popn! e (second args))]
    (push! e (sx (at pc "jmultiarray") (str-lit c) (apply sx "list" dims)) "A")))

(defn- emit-arraylength [e {:keys [pc]} _]
  (if-let [[_ n] (hoisted e (dec (stack-depth e)))]
    (do (pop! e) (push! e n "I"))
    (un! e #(sx (array-op e pc "array-length") % (site pc)) "I")))

(defn- emit-checkcast [e {:keys [args pc]} _]
  (let [c (:name ((:cp e) (first args)))]
    (resolve-type! (:resolve e) c)
    (let [v (sv (dec (stack-depth e)))]
      (bind! e v (sx "jcheckcast-ic" ((:ic! e)) v (str-lit c) (site pc))))))

(defn- emit-instanceof [e {:keys [args]} _]
  (let [c (:name ((:cp e) (first args)))]
    (resolve-type! (:resolve e) c)
    (let [ic ((:ic! e))] (un! e #(sx "if" (sx "jinstance-ic?" ic % (str-lit c)) "1" "0") "I"))))

;; --- the dispatch ------------------------------------------------------------------------

(def ^:private emitters
  "Each instruction's translation: (f e insn insns), insns being the block's
  instructions from this one on."
  (merge
   (zipmap (concat [:aconst_null :bipush :sipush] (keys const-values) (keys float-consts))
           (repeat emit-const))
   (zipmap [:ldc :ldc_w :ldc2_w] (repeat emit-ldc))
   (zipmap [:iload :lload :fload :dload :aload] (repeat emit-load))
   (zipmap [:istore :lstore :fstore :dstore :astore] (repeat emit-store))
   (zipmap [:iaload :baload :caload :saload :laload :faload :daload :aaload] (repeat emit-array-load))
   (zipmap [:iastore :lastore :fastore :dastore :bastore :castore :sastore] (repeat emit-array-store))
   (zipmap [:pop :pop2 :dup :dup_x1 :dup_x2 :dup2 :dup2_x1 :dup2_x2 :swap] (repeat emit-stack-op))
   (zipmap (keys simple-ops) (repeat emit-simple))
   (zipmap [:ladd :lsub :lmul :land :lor :lxor] (repeat emit-long-op))
   (zipmap [:lshl :lshr :lushr] (repeat emit-long-shift))
   (zipmap (concat (keys cmp0) (keys cmp2) [:if_acmpeq :if_acmpne :ifnull :ifnonnull]) (repeat emit-branch))
   (zipmap [:goto :goto_w] (repeat emit-goto))
   (zipmap [:jsr :jsr_w] (repeat emit-jsr))
   (zipmap [:tableswitch :lookupswitch] (repeat emit-switch))
   (zipmap [:ireturn :lreturn :freturn :dreturn :areturn :return :athrow] (repeat emit-end))
   (zipmap [:monitorenter :monitorexit] (repeat emit-monitor))
   (zipmap [:getstatic :putstatic :getfield :putfield] (repeat emit-field))
   (zipmap [:invokevirtual :invokeinterface :invokespecial :invokestatic] (repeat emit-invoke))
   {:nop (fn [_ _ _] nil)
    :iinc emit-iinc :aastore emit-aastore :ret emit-ret
    :lneg emit-lneg :lcmp emit-lcmp :i2l emit-i2l :l2i emit-l2i
    :invokedynamic emit-indy :new emit-new :newarray emit-newarray
    :anewarray emit-anewarray :multianewarray emit-multianewarray
    :arraylength emit-arraylength :checkcast emit-checkcast :instanceof emit-instanceof}))

(defn- emit-insn!
  "Translate one instruction, then note the stack depth after it, which lets
  render-steps close the lets of the slots it popped. Not after the block's
  last instruction, whose terminal expression (a branch test, a return
  value) reads what it popped."
  [e {:keys [op pc] :as insn} insns]
  (if-let [f (emitters op)]
    (f e insn insns)
    (throw (ex-info (str "opcode not in spike: " (name op)) {:op op :pc pc :class (:this-class e)})))
  (when (nil? @(:terminal e)) (step! e [:depth (stack-depth e)])))

(defn- failure-throw
  "The throw an instruction becomes when translating it failed to resolve
  something, or nil when the failure isn't one of those."
  [ex]
  (let [c (missing-class ex)
        k (wrong-kind ex)
        r (refused-class ex)
        m (or (missing-method ex) k)
        bad (or (bad-class ex) r)]
    (when (or c m bad)
      [(cond k (apply sx "jthrow-icce" (map str-lit k))
             r (apply sx "jthrow-linkage" (map str-lit r))
             m (apply sx "jthrow-no-method" (map str-lit m))
             bad (apply sx "jthrow-class-format" (map str-lit bad))
             :else (sx "jthrow-missing" (str-lit c)))
       m])))

(defn- emit-failure!
  "A class an instruction names is resolved when it is translated, but on the
  JVM only when it runs: one that isn't there makes the instruction a throw
  of NoClassDefFoundError (JVMS 5.4.3), and the rest of the block, which
  can't be reached, isn't translated. On null, neither checkcast nor
  instanceof resolves its class, as HotSpot doesn't: a cast passes and
  instanceof is false; only an object throws. Returns true when the block
  ends here."
  [e op ex]
  (let [[thrown method?] (or (failure-throw ex) (throw ex))]
    (case (when-not method? op)
      :checkcast (let [v (sv (dec (stack-depth e)))]
                   (bind! e v (sx "if" v thrown v))
                   (step! e [:depth (stack-depth e)])
                   false)
      :instanceof (do (un! e #(sx "if" % thrown "0") "I")
                      (step! e [:depth (stack-depth e)])
                      false)
      (do (terminate! e thrown) true))))

(defn- emit-block
  "Emit one basic block. ctx carries :cls (the classfile map), :resolve (the
  linker callbacks), :next-pc, :live (the locals a jump passes), :boxed (the
  method-wide locals), :split (the long locals kept as halves) and :jump
  (target pc and argument list -> the jump's code). entry is the compile-time
  stack at block entry: a vector of types (I J F D A), one per operand-stack
  value; inside the block a long kept as halves is j, in two variables sNh
  and sNl, both unsigned 32-bit (runtime.ss lh-*), and is boxed with
  lh-box wherever something other than a long operation takes it. In a
  loop's fast copy (compile-method*), :hoist maps an array local the loop
  never stores to the names of its vector and length, taken once before the
  loop: an array operation on what an aload of it pushed uses those.
  Returns {:code text :succ {pc entry-stack} :used hoisted-locals-used}."
  [ctx blk entry]
  (let [e (block-state ctx blk entry)]
    (loop [insns blk]
      (when-let [insn (first insns)]
        (when-not (try (emit-insn! e insn insns)
                       false
                       (catch Exception ex (emit-failure! e (:op insn) ex)))
          (recur (if @(:fused e) (drop 2 insns) (rest insns))))))
    ;; the fall-through jump first: it adds to :succ
    (let [term (or @(:terminal e) (jump-to e (:next-pc e)))]
      {:code (render-steps (persistent! @(:stmts e)) term)
       :succ @(:succ e)
       :used @(:used e)})))

(defn- entry-label [pc] (sym (str *method-tag* "#E" pc)))

(defn- covering
  "Indices of the handlers whose range covers pc, in exception table order."
  [handlers pc]
  (vec (keep-indexed (fn [i {:keys [start end]}] (when (and (<= start pc) (< pc end)) i)) handlers)))


(defn- block-use-def
  "[used defined] for one block: the locals it reads before assigning them
  (a ret's too), and the locals it assigns."
  [blk]
  (reduce (fn [[u d] {:keys [op args]}]
            (let [i (first args)]
              [(if (and (or (load-ops op) (= op :ret)) (not (d i))) (conj u i) u)
               (if (store-ops op) (conj d i) d)]))
          [#{} #{}] blk))

(defn- live-in-locals
  "Each block's live-in locals: what it uses, plus its normal successors'
  live-in less what it defines, plus every covering handler's live-in,
  which a throw before the def reaches; iterated to a fixed point."
  [blks handlers cover use-def]
  (loop [li (zipmap (keys blks) (repeat #{}))]
    (let [li' (into {} (for [[pc [u d]] use-def]
                         [pc (-> u
                                 (into (remove d) (mapcat li (successors (peek (blks pc)))))
                                 (into (mapcat #(li (:handler (nth handlers %))) (cover pc))))]))]
      (if (= li li') li (recur li')))))

(defn- assigned-under
  "The locals the blocks handler i covers assign."
  [i cover use-def]
  (into #{} (comp (filter (fn [[pc _]] (some #{i} (cover pc))))
                  (mapcat (fn [[_ [_ d]]] d)))
        use-def))

(defn- boxed-locals
  "The locals some handler reads on entry that the code it covers assigns.
  A handler sees a local's value at the throw, which only a method-wide
  variable holds: every other local keeps the value it had when the guarded
  region was entered, so the guard passes that on."
  [blks handlers cover]
  (let [use-def (into {} (for [[pc blk] blks] [pc (block-use-def blk)]))
        live-in (live-in-locals blks handlers cover use-def)]
    (set (for [[i {:keys [handler]}] (map-indexed vector handlers)
               :let [assigned (assigned-under i cover use-def)]
               l (live-in handler)
               :when (assigned l)]
           l))))

(declare compile-method*)

(def leaf-limit
  "Bytecode bytes in the largest method inlined into its callers."
  40)

(def ic-vector-limit
  "Bytecode bytes above which a method's inline-cache cells live in one vector
  rather than one variable each (compile-method)."
  4000)

(defn- leaf?
  "Whether method is small enough to inline: no calls but Object's empty
  constructor, no backward branches, no exception handlers, not synchronized
  or native. A leaf never shows on the stack while other code runs, so
  inlining it hides nothing from getCallerClass."
  [cls method]
  (let [{:keys [code handlers]} (:code method)
        cp (:cp cls)]
    (and code (empty? handlers) (<= (count code) leaf-limit)
         (zero? (bit-and (:flags method) 0x0120))
         (every? (fn [{:keys [op args pc] :as i}]
                   (and (not (#{:invokevirtual :invokeinterface :invokestatic :invokedynamic
                                :monitorenter :monitorexit :jsr :ret} op))
                        (or (not= op :invokespecial)
                            (let [{:keys [class name]} (cp (first args))]
                              (and (= class "java/lang/Object") (= name "<init>"))))
                        (every? #(> % pc) (successors i))))
                 (decode code)))))

(defn- ic-allocator
  "ic! for one method: a new inline-cache cell given the expression that
  makes it (a fresh pair by default), answering a variable holding its
  value, computed once; the cells collect in ics, in one vector when
  vector-ics?."
  [ics vector-ics?]
  (fn ic!
    ([] (ic! "(cons #f #f)"))
    ([init] (let [i (dec (count (vswap! ics conj init)))]
              (if vector-ics? (str "(vector-ref ics " i ")") (str "ic" i))))))

(defn- inline-label
  "The label of a copy of decl.name desc (ctag) translated for the call site
  *inline-site* of the method tag, its definition added to defs, or nil when
  the callee isn't a leaf. A callee that fails to translate (a class missing
  at run time, say) stays a plain call, so it fails only if called. The
  caller's side comes as a map: its class map cls, resolve, ic!, tag, defs."
  [{:keys [cls resolve ic! tag defs]} decl name desc ctag]
  (let [{c :cls m :method} ((:inline-candidate resolve) (:name cls) decl name desc)]
    (when (and m (leaf? c m))
      (when-let [text (try (binding [*method-tag* ctag]
                             (compile-method* c m resolve ic! (constantly nil)))
                           (catch Exception _ nil))]
        (let [l (sym (str tag "#I:" ctag "@" (first *inline-site*)))]
          (vswap! defs conj [l text])
          l)))))

(defn- inliner
  "inline! for the method tag: a callee's label, or nil to call it plainly;
  one copy per call site (*inline-site*), since the copy's markers name the
  site for stack traces, and none under CHAMPACA_BT."
  [cls resolve ic! tag defs]
  (let [inlined (volatile! {})          ; [callee tag, site] -> label, or false
        bt? (System/getenv "CHAMPACA_BT")
        caller {:cls cls :resolve resolve :ic! ic! :tag tag :defs defs}]
    (fn [decl name desc]
      (when-not (or bt? *plain*)
        (let [ctag (str decl "." name desc)
              key [ctag (first *inline-site*)]]
          (if (contains? @inlined key)
            (get @inlined key)
            (let [lbl (inline-label caller decl name desc ctag)]
              (vswap! inlined assoc key (or lbl false))
              lbl)))))))

(defn- with-ic-cells
  "f inside a let making the inline-cache cells ics, one vector of them
  when vector-ics?."
  [ics vector-ics? f]
  (cond (empty? ics) f
        vector-ics? (sx "let" (sx (sx "ics" (apply sx "vector" ics))) f)
        :else (sx "let" (apply sx (map-indexed (fn [i init] (sx (str "ic" i) init)) ics)) f)))

(defn compile-method
  "Scheme source for `(set-top-level-value! '|Cls.name(desc)| (lambda ...))`. A small leaf
  method it calls directly (invokestatic, invokespecial, or invokevirtual
  that nothing can override) is compiled into it as a let-bound procedure,
  which Chez calls directly or inlines."
  [cls method resolve]
  (let [tag (str (:name cls) "." (:name method) (:desc method))
        ;; inline-cache cells, one per site that needs one, made once per
        ;; method and shared with the methods inlined into it
        ics (volatile! [])
        ;; a giant method keeps its cells in one vector: Chez's expander scans
        ;; a let's bindings linearly for every variable it resolves, and
        ;; SystemModules' 21 KB module-descriptor builder has 2900 sites, which
        ;; cost it a second of compile time as variables. One more load per
        ;; site is nothing in code that runs once.
        vector-ics? (> (count (:code (:code method))) ic-vector-limit)
        ic! (ic-allocator ics vector-ics?)
        defs (volatile! [])             ; [label lambda-text], in order
        inline! (inliner cls resolve ic! tag defs)
        f (binding [*method-tag* tag] (compile-method* cls method resolve ic! inline!))
        f (if (seq @defs) (sx "let" (apply sx (map (fn [[l t]] (sx l t)) @defs)) f) f)]
    ;; set-top-level-value!, not define: a top-level define compiled to a
    ;; cached unit carries the expander's record of the binding ($sc-put-cte,
    ;; a syntax object with its ribcage), 15% of a unit's bytes and a fifth
    ;; of its load time (spike 72); the value cell is the same. The let names
    ;; the procedure as define did (a code name is what stack traces and
    ;; getCallerClass read; a bare lambda has none).
    (let [g (method-global (:name cls) (:name method) (:desc method))
          body (with-ic-cells @ics vector-ics? f)]
      (sx "set-top-level-value!" (str "'" g) (sx "let" (sx (sx g body)) g)))))

;; --- array loops ------------------------------------------------------------------
;; An array access checks for null, takes the array's vector and checks the
;; index against its length, and a loop's test reads the length again, every
;; step. In an innermost loop that reads an array local it never stores, the
;; vector and length can't change, so compile-method* versions the loop: a
;; jump into it from outside goes through #V<header>, which checks once that
;; each such local holds an array, takes its vector and length, and runs a
;; copy of the loop's blocks (#F<pc>) in which an access checks its index
;; alone (runtime.ss vec-ref); a null or anything else runs the loop as it
;; was, so an exception comes where it would have.

(defn- param-slots
  "The local slot of each parameter, a long or double taking two."
  [arg-types]
  (loop [ts arg-types slot 0 out []]
    (if-let [t (first ts)]
      (recur (rest ts) (+ slot (if (wide? t) 2 1)) (conj out slot))
      out)))

(def ^:private long-makers
  "The operations whose long result, stored straight away, marks a local as
  worth keeping in halves."
  #{:ladd :lsub :lmul :land :lor :lxor :lshl :lshr :lushr :lneg :lconst_0 :lconst_1 :ldc2_w :i2l})

(defn- split-locals
  "The long locals kept as halves (emit-block): loaded and stored as longs
  only, not method-wide, and given a long operation's result somewhere,
  which is what would otherwise box."
  [insns boxed param-set param-types]
  (let [uses (group-by #(first (:args %)) (filter #(or (load-ops (:op %)) (store-ops (:op %))) insns))
        made (set (for [[p i] (map vector insns (rest insns))
                        :when (and (= :lstore (:op i)) (long-makers (:op p)))]
                    (first (:args i))))]
    (set (for [[i us] uses
               :when (and (made i) (not (boxed i))
                          (every? #(#{:lload :lstore} (:op %)) us)
                          (or (not (param-set i)) (= "J" (param-types i))))]
           i))))

(defn- live-locals
  "The locals a jump has to carry: the parameters and every slot the method
  loads or stores, less the boxed ones, which stay method-wide."
  [insns param-set boxed]
  (vec (sort (remove boxed
                     (into param-set
                           (for [{:keys [op args]} insns
                                 :when (or (load-ops op) (store-ops op))]
                             (first args)))))))

(defn- method-plan
  "What translating the method needs to know before any block is emitted:
  its blocks, the handler regions covering each, the locals boxed, split
  and carried, the array loops to version. :entered collects the covered
  blocks a jump enters from outside their region."
  [cls method resolve ic! inline!]
  (let [{:keys [code handlers]} (:code method)
        insns (decode code)
        blks (blocks insns handlers)
        {:keys [args]} (parse-desc (:desc method))
        arg-types (if (pos? (bit-and (:flags method) 0x0008)) args (cons "L" args))
        params (param-slots arg-types)
        param-set (set params)
        cover (into {} (map (fn [pc] [pc (covering handlers pc)]) (keys blks)))
        boxed (if (seq handlers) (boxed-locals blks handlers cover) #{})]
    {:cls cls :resolve resolve :ic! ic! :inline! inline!
     :method-key (str (:name method) (:desc method))
     ;; a giant method's array accesses are calls (runtime.ss big-array-ref),
     ;; but not inside a loop: every backward jump's [target jump] range
     ;; keeps the inline form, so a lexer's scanning loop in a giant method
     ;; runs as fast as before (a loop took 117 ms through calls, 50 inline)
     :big? (> (count code) ic-vector-limit)
     :loop-ranges (when (> (count code) ic-vector-limit) (backward-ranges insns))
     :handlers handlers :blks blks :cover cover :boxed boxed
     :params params :param-set param-set
     :next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
     :split (split-locals insns boxed param-set (zipmap params arg-types))
     :live (live-locals insns param-set boxed)
     ;; array loops to version (handler-free methods of a size whose
     ;; blocks' dominators are cheap to find)
     :loops (or (when (and (not *plain*) (empty? handlers) (<= (count code) ic-vector-limit))
                  (array-loops blks))
                {})
     :entered (volatile! (sorted-set))}))

(defn- version-label [pc] (sym (str *method-tag* "#V" pc)))
(defn- fast-label [pc] (sym (str *method-tag* "#F" pc)))

(defn- loop-target
  "A jump into a versioned loop's header from outside the loop goes through
  its #V."
  [{:keys [loops]} from pc]
  (if (and (loops pc) (not ((:body (loops pc)) from))) (version-label pc) (label pc)))

(defn- enter-label
  "A covered block entered from outside its region goes through its guarded
  entry (entry-label), noted in :entered so one is built."
  [{:keys [cover entered]} pc]
  (if (seq (cover pc))
    (do (vswap! entered conj pc) (entry-label pc))
    (label pc)))

(defn- block-jump
  "A jump inside a region is a tail call. One out of an uncovered block is a
  tail call too, into the target's guarded entry when it has one; one out of
  a covered region has to leave its guard first, so it returns a thunk to the
  trampoline."
  [{:keys [cover] :as plan} from]
  (fn [pc args]
    (cond (= (cover from) (cover pc)) (apply sx (loop-target plan from pc) args)
          (empty? (cover from)) (apply sx (enter-label plan pc) args)
          :else (sx "lambda" "()" (apply sx (enter-label plan pc) args)))))

(defn- block-ctx
  "emit-block's context for the block at pc, jumping through jump."
  [{:keys [cls resolve next-pc live boxed split ic! inline! big? loop-ranges]} pc jump]
  {:cls cls :resolve resolve :next-pc (next-pc pc) :big? big? :loop-ranges loop-ranges
   :live live :boxed boxed :split split :jump jump :ic! ic! :inline! inline!})

(defn- emit-blocks
  "Worklist over blocks: each is emitted once, at the stack depth its first
  predecessor established (verified bytecode guarantees agreement). A map of
  pc to [depth code]."
  [{:keys [blks handlers] :as plan}]
  (loop [work (into [[0 []]] (map (fn [h] [(:handler h) ["A"]]) handlers))
         done {}]
    (if-let [[pc dep] (first work)]
      (if (contains? done pc)
        (recur (rest work) done)
        (let [{:keys [code succ]} (emit-block (block-ctx plan pc (block-jump plan pc)) (blks pc) dep)]
          (recur (concat (rest work) succ) (assoc done pc [dep code]))))
      done)))

(defn- block-args
  "A block's parameters: the live locals, then its entry stack."
  [{:keys [split live]} dep]
  (concat (mapcat #(local-names split %) live) (map #(str "s" %) (range (count dep)))))

(defn- block-defs
  "Each emitted block as a letrec binding: a lambda over its block-args."
  [plan emitted]
  (for [[pc [dep code]] (sort-by key emitted)]
    (sx (label pc) (sx "lambda" (apply sx (block-args plan dep)) code))))

(defn- version-def
  "A versioned loop's #V: its blocks copied with the hoisted locals' vectors
  and lengths, entered when every local the copy used holds an array, else
  the loop as it was. The copy's jumps stay in the copy or leave it as the
  loop's would."
  [{:keys [blks] :as plan} emitted h {:keys [body locals]}]
  (let [hoist (into {} (map (fn [k] [k [(str "l" k "v") (str "l" k "n")]]) locals))
        fast (for [pc (sort body) :when (emitted pc)
                   :let [dep (first (emitted pc))
                         jump (fn [to args] (apply sx (if (body to) (fast-label to) (loop-target plan pc to)) args))]]
               [pc dep (emit-block (assoc (block-ctx plan pc jump) :hoist hoist) (blks pc) dep)])
        ks (sort (distinct (mapcat (comp :used #(nth % 2)) fast)))
        as (block-args plan (first (emitted h)))]
    (sx (version-label h)
        (sx "lambda" (apply sx as)
            (if (empty? ks)
              (apply sx (label h) as)
              (sx "if" (apply sx "and" (map #(sx "jarr?" (str "l" %)) ks))
                  (sx "let*" (apply sx (mapcat (fn [k] (let [[v n] (hoist k)]
                                                         [(sx v (sx "jarr-vec" (str "l" k)))
                                                          (sx n (sx "#3%vector-length" v))]))
                                               ks))
                      (sx "letrec" (apply sx (for [[pc dep {:keys [code]}] fast]
                                               (sx (fast-label pc) (sx "lambda" (apply sx (block-args plan dep)) code))))
                          (apply sx (fast-label h) as)))
                  (apply sx (label h) as)))))))

(defn- version-defs
  "The #V binding of each versioned loop whose header was emitted, in pc
  order. Lazy: its copies are emitted when blocks-body realizes it."
  [{:keys [loops] :as plan} emitted]
  (for [[h lp] (sort-by key loops) :when (emitted h)]
    (version-def plan emitted h lp)))

(defn- start-call
  "The first block, called with the parameters, a long one kept as halves
  split first."
  [{:keys [live split param-set loops] :as plan}]
  (let [split-params (filter #(and (param-set %) (split %)) live)
        call (apply sx (if (loops 0) (version-label 0) (enter-label plan 0))
                    (mapcat #(cond (split %) (if (param-set %) (local-names split %) ["0" "0"])
                                   (param-set %) [(str "l" %)]
                                   :else ["0"])
                            live))]
    (if (seq split-params)
      (sx "let*" (apply sx (mapcat (fn [i] [(sx (str "l" i "h") (sx "lh-split-h" (str "l" i)))
                                            (sx (str "l" i "l") (sx "lh-split-l" (str "l" i)))])
                                   split-params))
          call)
      call)))

(defn- entry-defs
  "Each guarded entry: one handler for the region (jtry), whose clauses
  follow the exception table order and pass the handler the exception and
  the locals as the region got them. Building one can enter a handler's
  region, so this runs until no new entry turns up."
  [{:keys [entered cover handlers split live] :as plan} emitted]
  (loop [out [] done #{}]
    (if-let [pc (first (remove done @entered))]
      (let [as (block-args plan (first (emitted pc)))
            clauses (doall
                     (for [i (cover pc)
                           :let [{:keys [type handler]} (nth handlers i)]]
                       (sx (if type (sx "jinstance?" "e" (str-lit type)) (sx "jobj?" "e"))
                           (apply sx (enter-label plan handler) (concat (mapcat #(local-names split %) live) ["e"])))))]
        (recur (conj out (sx (entry-label pc)
                             (sx "lambda" (apply sx as)
                                 (sx "jtry" "e" (apply sx clauses)
                                     (apply sx (label pc) as)))))
               (conj done pc)))
      out)))

(defn- blocks-body
  "The method's blocks around the call of the first. With handlers the
  boxed locals are method-wide and a block's result may be a thunk to call
  outside its region's guard."
  [{:keys [handlers boxed param-set]} block-fns entries versions start]
  (if (seq handlers)
    (sx "let" (apply sx (for [i (sort boxed) :when (not (param-set i))] (sx (str "l" i) "0")))
        (sx "letrec" (apply sx (concat block-fns entries))
            ;; an inline named loop rather than a shared helper,
            ;; so a frame named for this method stays on the stack
            ;; while a block runs under its region's handler (getCallerClass)
            (let [t (sym (str *method-tag* "#T"))]
              (sx "let" t (sx (sx "r" start))
                  (sx "if" (sx "procedure?" "r") (sx t (sx "r")) "r")))))
    (sx "letrec" (apply sx (concat block-fns versions)) start)))

(defn- synchronized-body
  "ACC_SYNCHRONIZED: the monitor of this, or of the class for a static
  method, held for the call and released however it ends. dynamic-wind
  rather than guard: an exception leaves through a handler further out,
  whose escape runs the exit before that handler's clauses, and installing a
  guard costs about four times as much. jframe in compile-method* keeps a
  frame named after the method."
  [cls method ic! body]
  (let [lock (if (pos? (bit-and (:flags method) 0x0008))
               ;; the class mirror, found once: a cell like the
               ;; inline caches, since cached code loads before
               ;; any class exists
               (let [c (ic!)]
                 (sx "or" (sx "car" c)
                     (sx "let" (sx (sx "m" (sx "jmirror-of" (str-lit (:name cls)))))
                         (sx "set-car!" c "m") "m")))
               "l0")]
    (sx "let" (sx (sx "lock" lock))
        (sx "dynamic-wind"
            (sx "lambda" "()" (sx "jmonitor-enter" "lock"))
            (sx "lambda" "()" body)
            (sx "lambda" "()" (sx "jmonitor-exit" "lock"))))))

(defn- compile-method*
  "The method as a lambda. ic! names a fresh inline-cache cell, inline! the
  inlined procedure for a direct callee or nil."
  [cls method resolve ic! inline!]
  ;; the order matters: ic! cells are numbered as they're asked for, and
  ;; the guarded entries are known only once the blocks and the start are
  ;; emitted. version-defs is lazy and emits its copies inside blocks-body.
  (let [plan (method-plan cls method resolve ic! inline!)
        emitted (emit-blocks plan)
        block-fns (block-defs plan emitted)
        versions (version-defs plan emitted)
        start (start-call plan)
        entries (entry-defs plan emitted)
        body* (blocks-body plan block-fns entries versions start)
        sync? (pos? (bit-and (:flags method) 0x0020))
        bt? (and (not *plain*) (System/getenv "CHAMPACA_BT"))   ; the port's form has no wrapper
        body (if sync? (synchronized-body cls method ic! body*) body*)
        ;; CHAMPACA_BT: keep a shadow stack of Java frames for backtraces
        body (if bt?
               (sx "fluid-let" (sx (sx "jstack" (sx "cons" (str-lit (str (:name cls) "." (:name method) (:desc method))) "jstack")))
                   body)
               body)
        ;; a synchronized method's dynamic-wind and BT's fluid-let would be
        ;; tail calls out of the method's own lambda, leaving it no frame of
        ;; its own; jframe, an identity the optimizer can't see through,
        ;; makes the one call a non-tail call, and the stack walk then finds
        ;; the method's frame with the pending pc of the unnamed thunk inside
        body (if (or sync? bt?) (sx "jframe" body) body)]
    (sx "lambda" (apply sx (map #(str "l" %) (:params plan)))
        ;; npe-tag, a constant Chez folds into each site that uses it,
        ;; inside the lambda so the method stays a plain lambda to Chez
        (sx "let" (sx (sx "npe-tag" (str "'" (sym *method-tag*))))
            body))))
