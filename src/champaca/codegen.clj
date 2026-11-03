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
  (:require [clojure.string :as str]))

;; --- text helpers ------------------------------------------------------------

(defn sx [& parts] (str "(" (str/join " " (remove nil? parts)) ")"))

(defn sym
  "A Scheme symbol literal for a JVM-ish name, bar-quoted so / ( ) ; < > [ $
  all survive."
  [s]
  (str "|" s "|"))

(defn str-lit
  "A Scheme string literal, or for a constant holding a lone surrogate (a
  vector of UTF-16 code units, see classfile/decode-mutf8) a vector literal.
  Quote it where it's evaluated; jstring-lit-form does."
  [s]
  (if (vector? s)
    (str "#" (apply sx (map str s)))
    (str "\""
         (apply str (map (fn [c]
                           (let [i (int c)]
                             (cond (= c \\) "\\\\"
                                   (= c \") "\\\""
                                   ;; Chez reads any line ending inside a literal as \n, NEL
                                   ;; and LS included (R6RS 4.2.7); escaped, they stay themselves
                                   (or (< i 32) (= i 0x85) (= i 0x2028)) (str "\\x" (Integer/toHexString i) ";")
                                   :else (str c))))
                         s))
         "\"")))

(defn jstring-lit-form
  "The expression for a String constant: the interned java.lang.String."
  [s]
  (sx "jstring-lit" (if (vector? s) (str "'" (str-lit s)) (str-lit s))))

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

;; what a null check passes to npe-at so the NPE's message can be built from
;; its bytecode: npe-tag, the method's Class.name(desc), which each compiled
;; method (an inlined callee too) binds once in its lambda, and the bci
;; Every call that can end up pending in a frame, and every helper that can
;; throw, carries its bytecode pc as a constant (begin '(jpc . pc) x): Chez
;; drops it, so the code costs the same, but the frame's recorded source
;; keeps it, and a stack trace reads the pc, hence the line, off the frame
;; (reflect.ss java-frames-of). at wraps a call's operator; site is the
;; "tag pc" argument pair of the null-check, array, class-init and cast
;; helpers, its tag reference wrapped the same way.
(def ^:dynamic *inline-site*
  "[pc tag] of the call site a leaf callee is being compiled for. Markers
  in its body then name the site and the callee, (jpc site-pc callee . pc),
  so the walk shows the callee as a frame of its own above the caller's
  line, as HotSpot does for an inlined method."
  nil)
(defn- jpc [pc]
  (if-let [[spc] *inline-site*]
    (str "'(jpc " spc " " (sym *method-tag*) " . " pc ")")
    (str "'(jpc . " pc ")")))
(defn- at [pc op] (sx "begin" (jpc pc) op))
(defn- site [pc] (str (sx "begin" (jpc pc) "npe-tag") " " pc))

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
  [{:keys [cls resolve ic!]} sam-name iface-desc captured-types captured bsm]
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
        ;; invoking a static or constructor reference initializes its class
        ;; first, as the JVM's method handle does (JVMS 5.5), through a site
        ;; cell like invokestatic's and new's. That also loads the class, which
        ;; translating this method did in this process but may not have in the
        ;; one running it from the startup cache. Unlike invokestatic, a
        ;; reference to this class's own method checks too: the lambda can run
        ;; on another thread while this class's <clinit> is still going, and
        ;; that thread has to wait for it.
        call (case (int kind)
               6 (do ((:ensure-loaded resolve) class)
                     (let [decl ((:resolve-method resolve) class name desc)]
                       (sx "begin" (sx "jinit-ic" (ic!) (str "'" (sym decl)))
                           (apply sx (method-global decl name desc) actuals))))
               7 (apply sx (method-global ((:resolve-method resolve) class name desc) name desc) actuals)
               (5 9) (apply sx (sx "jvirt" (first actuals) (str "'" (sym (str name desc)))) actuals)
               8 (do ((:ensure-loaded resolve) class)
                     (sx "let" (sx (sx "o" (sx "jnew-ic" (ic!) (str "'" (sym class)))))
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
;; method type and dynamic constants an ldc can load. Those three resolve once
;; per constant-pool entry, however many instructions and bootstrap arguments
;; name it (JVMS 5.4.3), so they come wrapped as (entry |Class#index| datum),
;; keyed by an interned symbol.

(defn- const-datum [cls i]
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
  (let [slot (fn [v] (if (str/starts-with? v "s") (parse-long (re-find #"\d+" v)) -1))
        seq-form (fn [xs] (if (= 1 (count xs)) (first xs) (apply sx "begin" xs)))
        ;; a scope: [slot binding-text children], the root [-1 nil children]
        close (fn [[_ head children]] (sx "let" head (seq-form (if (seq children) children ["(void)"]))))
        add-child (fn [scopes x] (update scopes (dec (count scopes)) update 2 conj x))]
    (loop [steps steps scopes [[-1 nil []]]]
      (if-let [[k a b] (first steps)]
        (recur (rest steps)
               (case k
                 :bind (conj scopes [(slot a) (sx (sx a b)) []])
                 :pbind (conj scopes [(reduce min (map (comp slot first) a)) (apply sx (map (fn [[v e]] (sx v e)) a)) []])
                 :set (add-child scopes (sx "set!" a b))
                 :do (add-child scopes a)
                 :depth (loop [scopes scopes]
                          (let [[s :as top] (peek scopes)]
                            (if (and (> (count scopes) 1) (>= s a))
                              (recur (add-child (pop scopes) (close top)))
                              scopes)))))
        ;; the terminal expression is the last statement of the innermost
        ;; scope, which reads what the last instruction popped
        (loop [scopes (add-child scopes term)]
          (if (= 1 (count scopes))
            (seq-form (nth (first scopes) 2))
            (recur (add-child (pop scopes) (close (peek scopes))))))))))

(defn- missing-class
  "The class a resolution failure says isn't there, or nil for any other
  failure (main.clj's normalize: \"class not found\" with the name)."
  [e]
  (let [d (ex-data e)]
    (when (and (= (set (keys d)) #{:class}) (str/starts-with? (str (ex-message e)) "class not found"))
      (:class d))))

(defn- bad-class
  "[class message] of a class whose file isn't a class file (main.clj's
  normalize), or nil for any other failure."
  [e]
  (let [d (ex-data e)]
    (when (= (set (keys d)) #{:class :class-format})
      [(:class d) (:class-format d)])))

(defn- missing-method
  "[class name desc] of a method a call names that its class doesn't have
  (main.clj's resolve-method, check-virtual), or nil for any other failure."
  [e]
  (let [d (ex-data e)]
    (when (and (= (set (keys d)) #{:class :method :desc}) (= "no such method" (ex-message e)))
      [(:class d) (:method d) (:desc d)])))

(defn- refused-class
  "[class error-class text] of a class the JVM refuses to define (main.clj
  check-definition!, load-supertypes!), or nil."
  [e]
  (let [d (ex-data e)]
    (when (:linkage d) [(:class d) (:linkage d) (:message d)])))

(defn- wrong-kind
  "[what class name desc] of a call whose instruction doesn't match the
  method it resolves to (main.clj check-kind), or nil."
  [e]
  (let [d (ex-data e)]
    (when (:icce d) [(:icce d) (:class d) (:method d) (:desc d)])))

(defn- local-names
  "The variables a local is passed in: a long kept as halves (split) takes two."
  [split i]
  (if (split i) [(str "l" i "h") (str "l" i "l")] [(str "l" i)]))

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

(defn- emit-block
  "Emit one basic block. ctx carries :cls (the classfile map), :resolve (the
  linker callbacks), :next-pc, :live (the locals a jump passes), :boxed (the
  method-wide locals), :split (the long locals kept as halves) and :jump
  (target pc and argument list -> the jump's code). entry is the compile-time
  stack at block entry: a vector of types (I J F D A), one per operand-stack
  value; inside the block a long kept as halves is j, in two variables sNh
  and sNl, both unsigned 32-bit (runtime.ss lh-*), and is boxed with
  lh-box wherever something other than a long operation takes it.
  Returns {:code text :succ {pc entry-stack}}."
  [ctx blk entry]
  (let [{:keys [cls resolve next-pc live boxed split jump]} ctx
        ;; what a direct call names: the callee inlined into this method when
        ;; it is a small leaf (compile-method), else its top-level definition
        callee (fn [pc decl name desc]
                 (or (binding [*inline-site* [pc *method-tag*]] ((:inline! ctx) decl name desc))
                     (method-global decl name desc)))
        cp (:cp cls)
        this-class (:name cls)
        st (volatile! entry)
        ;; steps, in order: [:bind var expr], [:pbind [[var expr] ...]] (one
        ;; parallel binding), [:set var expr] or [:do expr], rendered as nested
        ;; lets (render-steps)
        stmts (transient [])
        succ (volatile! {})
        s (fn [i] (str "s" i))
        h (fn [i] (str "s" i "h"))
        l (fn [i] (str "s" i "l"))
        ;; literal values on the stack by index: a shift's count, a long's
        ;; halves
        consts (volatile! {})
        bind! (fn [v expr] (conj! stmts [:bind v expr]))
        store! (fn [i expr] (conj! stmts [(if (boxed i) :set :bind) (str "l" i) expr]))
        push! (fn [expr t] (vswap! consts dissoc (count @st)) (bind! (s (count @st)) expr) (vswap! st conj t))
        push-const! (fn [v t] (let [i (count @st)] (push! (num-lit v) t) (vswap! consts assoc i v)))
        ;; a long as halves, both computed from what was there before
        push-halves! (fn [hx lx]
                       (let [i (count @st)]
                         (vswap! consts dissoc i)
                         (conj! stmts [:pbind [[(h i) hx] [(l i) lx]]])
                         (vswap! st conj "j")))
        ;; the popped value as an expression: a long kept as halves is boxed
        pop! (fn [] (let [i (dec (count @st)) t (peek @st)]
                      (vswap! st pop) (vswap! consts dissoc i)
                      (if (= t "j") (sx "lh-box" (h i) (l i)) (s i))))
        ;; a long's halves, split here if it was boxed (lh-split-l reads what
        ;; lh-split-h put in long-buf, so nothing comes between them)
        pop-halves! (fn [] (let [i (dec (count @st)) t (peek @st) c (@consts i)]
                             (vswap! st pop) (vswap! consts dissoc i)
                             (cond (= t "j") [(h i) (l i)]
                                   c [(str (bit-and (bit-shift-right c 32) 0xffffffff)) (str (bit-and c 0xffffffff))]
                                   :else (do (bind! (h i) (sx "lh-split-h" (s i)))
                                             (bind! (l i) (sx "lh-split-l" (s i)))
                                             [(h i) (l i)]))))
        halves? (fn [n insns] (or (some #{"j"} (take-last n @st)) (halves-use? cp split (rest insns))))
        top-t (fn [] (peek @st))
        popn! (fn [n] (vec (reverse (repeatedly n pop!))))
        stmt! (fn [x] (conj! stmts [:do x]))
        start-pc (:pc (first blk))
        ;; a backward jump is where a loop can spin, so it is where a thread
        ;; lets another one run (jsafepoint, natives.ss)
        goto (fn [pc]
               ;; a block takes its stack boxed
               (vswap! succ assoc pc (mapv #(if (= % "j") "J" %) @st))
               ;; a jump passes the live locals and the stack as arguments
               (let [j (jump pc (concat (mapcat #(local-names split %) live)
                                        (map-indexed (fn [i t] (if (= t "j") (sx "lh-box" (h i) (l i)) (s i))) @st)))]
                 (if (<= pc start-pc) (sx "begin" "(when (#3%fx> gil-ready 0) (jsafepoint))" j) j)))
        init (fn [c pc] (when (not= c this-class) (stmt! (sx "jinit-ic" ((:ic! ctx)) (str "'" (sym c)) (site pc)))))
        bin (fn [f t] (let [b (pop!) a (pop!)] (push! (f a b) t)))
        un (fn [f t] (push! (f (pop!)) t))
        ;; copy the top k values to below the m values under them (dup family)
        shuffle! (fn [k m]
                   (let [n (count @st)
                         base (- n k m)
                         items (subvec @st base)
                         order (concat (range (- (count items) k) (count items)) (range (count items)))
                         ;; a slot that keeps its own value (dup's original) isn't
                         ;; rebound, so its let can close while the copy lives
                         moves (remove (fn [[v e]] (= v e))
                                       (apply concat
                                              (map-indexed (fn [j src]
                                                             (let [to (+ base j) from (+ base src)]
                                                               (if (= "j" (nth items src))
                                                                 [[(h to) (h from)] [(l to) (l from)]]
                                                                 [[(s to) (s from)]])))
                                                           order)))]
                     (vswap! consts #(into {} (remove (fn [[k _]] (>= k base))) %))
                     (when (seq moves) (conj! stmts [:pbind moves]))
                     (vreset! st (into (subvec @st 0 base) (map items order)))))
        cmp0 {:ifeq "ix=" :ifne "ix<>" :iflt "ix<" :ifge "ix>=" :ifgt "ix>" :ifle "ix<="}
        cmp2 {:if_icmpeq "ix=" :if_icmpne "ix<>" :if_icmplt "ix<" :if_icmpge "ix>="
              :if_icmpgt "ix>" :if_icmple "ix<="}
        terminal (volatile! nil)
        ;; set when an instruction also translated the one after it
        fused (volatile! false)
        lcmp-branch {:ifeq #(sx "=" %1 %2) :ifne #(sx "not" (sx "=" %1 %2)) :iflt #(sx "<" %1 %2)
                     :ifge #(sx ">=" %1 %2) :ifgt #(sx ">" %1 %2) :ifle #(sx "<=" %1 %2)}
        branch (fn [test target fall] (vreset! terminal (sx "if" test (goto target) (goto fall))))]
    ;; a class an instruction names is resolved when it is translated, but on
    ;; the JVM only when it runs: one that isn't there makes the instruction a
    ;; throw of NoClassDefFoundError (JVMS 5.4.3), and the rest of the block,
    ;; which can't be reached, isn't translated
    (loop [insns blk]
     (when-let [{:keys [op args pc len]} (first insns)]
      (when-not
       (try
      (let [a0 (first args)]
        (case op
          :nop nil
          :aconst_null (push! "#f" "A")
          (:iconst_m1 :iconst_0 :iconst_1 :iconst_2 :iconst_3 :iconst_4 :iconst_5)
          (push-const! ({:iconst_m1 -1 :iconst_0 0 :iconst_1 1 :iconst_2 2 :iconst_3 3
                         :iconst_4 4 :iconst_5 5} op) "I")
          (:lconst_0 :lconst_1) (push-const! (if (= op :lconst_0) 0 1) "J")
          (:fconst_0 :fconst_1 :fconst_2) (push! ({:fconst_0 "0.0" :fconst_1 "1.0" :fconst_2 "2.0"} op) "F")
          (:dconst_0 :dconst_1) (push! (if (= op :dconst_0) "0.0" "1.0") "D")
          (:bipush :sipush) (push-const! a0 "I")
          (:ldc :ldc_w :ldc2_w)
          (let [e (cp a0)]
            (case (:tag e)
              :int (push-const! (:value e) "I")
              :long (push-const! (:value e) "J")
              :float (push! (num-lit (:value e)) "F")
              :double (push! (num-lit (:value e)) "D")
              :string (push! (jstring-lit-form (:value e)) "A")
              ;; resolved when it runs, as the JVM resolves it, through this
              ;; class's defining loader (runtime.ss jldc-class)
              :class (push! (sx "jldc-class" (str-lit (:name e)) (str-lit this-class)) "A")
              (:methodtype :methodhandle :dynamic)
              (let [d (if (= :dynamic (:tag e)) (:desc e) "Ljava/lang/Object;")]
                ;; resolved once per constant-pool entry (const-datum's entry)
                (push! (sx "jconst" (str-lit this-class) (str "'" (const-datum cls a0)) (str-lit d))
                       (desc->t d)))
              (throw (ex-info "ldc constant not in spike" {:entry e}))))
          :iload (push! (str "l" a0) "I")
          :lload (if (split a0) (push-halves! (str "l" a0 "h") (str "l" a0 "l")) (push! (str "l" a0) "J"))
          :fload (push! (str "l" a0) "F")
          :dload (push! (str "l" a0) "D")
          :aload (push! (str "l" a0) "A")
          (:istore :lstore :fstore :dstore :astore)
          (if (and (= op :lstore) (split a0))
            (let [[vh vl] (pop-halves!)]
              (conj! stmts [:pbind [[(str "l" a0 "h") vh] [(str "l" a0 "l") vl]]]))
            (store! a0 (pop!)))
          :iinc (store! a0 (sx "wrap-int" (sx "ix+" (str "l" a0) (str (second args)))))
          (:iaload :baload :caload :saload) (bin (fn [a i] (sx "array-ref" a i (site pc))) "I")
          :laload (bin (fn [a i] (sx "array-ref" a i (site pc))) "J")
          :faload (bin (fn [a i] (sx "array-ref" a i (site pc))) "F")
          :daload (bin (fn [a i] (sx "array-ref" a i (site pc))) "D")
          :aaload (bin (fn [a i] (sx "array-ref" a i (site pc))) "A")
          :aastore
          (let [[a i v] (popn! 3)]
            (stmt! (sx "array-store!" ((:ic! ctx)) a i v (site pc))))
          (:iastore :lastore :fastore :dastore :bastore :castore :sastore)
          (let [[a i v] (popn! 3)]
            (stmt! (sx "array-set!" a i (case op :bastore (sx "wrap-byte" v) :castore (sx "ix-and" v "#xffff")
                                              :sastore (sx "wrap-short" v) v)
                       (site pc))))
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
          :iadd (bin #(sx "wrap-int" (sx "ix+" %1 %2)) "I")
          :isub (bin #(sx "wrap-int" (sx "ix-" %1 %2)) "I")
          :imul (bin #(sx "wrap-int" (sx "ix*" %1 %2)) "I")
          :idiv (bin #(sx "int-div" %1 %2 (site pc)) "I")
          :irem (bin #(sx "int-rem" %1 %2 (site pc)) "I")
          :ineg (un #(sx "wrap-int" (sx "ix-" "0" %)) "I")
          :ishl (bin #(sx "int-shl" %1 %2) "I")
          :ishr (bin #(sx "int-shr" %1 %2) "I")
          :iushr (bin #(sx "int-ushr" %1 %2) "I")
          :iand (bin #(sx "ix-and" %1 %2) "I")
          :ior (bin #(sx "ix-or" %1 %2) "I")
          :ixor (bin #(sx "ix-xor" %1 %2) "I")
          ;; on halves when an operand is in halves or the result goes on
          ;; to a long operation; else runtime.ss's long macros, fixnums
          ;; inline and anything else on 32-bit halves, boxed
          (:ladd :lsub :lmul :land :lor :lxor)
          (if (halves? 2 insns)
            (let [[bh bl] (pop-halves!) [ah al] (pop-halves!) r (count @st)]
              (case op
                ;; the low halves' sum, whose bit 32 is the carry
                :ladd (let [c (str "s" r "c")]
                        (bind! c (sx "lh-add-c" al bl))
                        (push-halves! (sx "lh-add-h" ah bh c) (sx "lh-u32" c)))
                ;; the low halves' difference, negative for a borrow
                :lsub (let [c (str "s" r "c")]
                        (bind! c (sx "lh-sub-c" al bl))
                        (push-halves! (sx "lh-sub-h" ah bh c) (sx "lh-u32" c)))
                :lmul (let [u (str "s" r "u") w (str "s" r "w")]
                        (bind! u (sx "lh-mul-u" al bl))
                        (bind! w (sx "lh-mul-w" al bl u))
                        (push-halves! (sx "lh-mul-h" ah al bh bl u w) (sx "lh-u32" w)))
                :land (push-halves! (sx "lh-and" ah bh) (sx "lh-and" al bl))
                :lor (push-halves! (sx "lh-or" ah bh) (sx "lh-or" al bl))
                :lxor (push-halves! (sx "lh-xor" ah bh) (sx "lh-xor" al bl))))
            (bin #(sx (name op) %1 %2) "J"))
          ;; a literal count folds the shift's tests away
          (:lshl :lshr :lushr)
          (if (or (= "j" (nth @st (- (count @st) 2))) (halves-use? cp split (rest insns)))
            (let [c (@consts (dec (count @st)))
                  cnt (pop!)
                  [ah al] (pop-halves!)
                  r (count @st)
                  n (if c
                      (str (bit-and c 63))
                      (let [v (str "s" r "n")] (bind! v (sx "ix-and" cnt "63")) v))]
              (case op
                :lshl (push-halves! (sx "lh-shl-h" ah al n) (sx "lh-shl-l" al n))
                :lushr (push-halves! (sx "lh-ushr-h" ah n) (sx "lh-ushr-l" ah al n))
                :lshr (let [sg (str "s" r "s")]
                        (bind! sg (sx "lh-s32" ah))
                        (push-halves! (sx "lh-shr-h" sg n) (sx "lh-shr-l" ah al n sg)))))
            (bin #(sx (name op) %1 %2) "J"))
          :ldiv (bin #(sx (at pc "ldiv") %1 %2) "J")
          :lrem (bin #(sx (at pc "lrem") %1 %2) "J")
          :lneg (if (halves? 1 insns)
                  (let [[ah al] (pop-halves!) c (str "s" (count @st) "c")]
                    (bind! c (sx "lh-sub-c" "0" al))
                    (push-halves! (sx "lh-sub-h" "0" ah c) (sx "lh-u32" c)))
                  (un #(sx "lneg" %) "J"))
          ;; lcmp then a branch on its result, the usual shape, is one
          ;; comparison: the branch ends the block, so it's the last instruction
          :lcmp (let [{nop :op nargs :args npc :pc nlen :len} (second insns)]
                  (if (some #{"j"} (take-last 2 @st))
                    (let [[bh bl] (pop-halves!) [ah al] (pop-halves!)
                          eq (sx "lh=" ah al bh bl) lt (sx "lh<" ah al bh bl) gt (sx "lh<" bh bl ah al)]
                      (if-let [test ({:ifeq eq :ifne (sx "not" eq) :iflt lt :ifge (sx "not" lt)
                                      :ifgt gt :ifle (sx "not" gt)} nop)]
                        (do (branch test (first nargs) (+ npc nlen))
                            (vreset! fused true))
                        (push! (sx "lh-cmp" ah al bh bl) "I")))
                    (if-let [test (lcmp-branch nop)]
                      (let [b (pop!) a (pop!)]
                        (branch (test a b) (first nargs) (+ npc nlen))
                        (vreset! fused true))
                      (bin #(sx "lcmp" %1 %2) "I"))))
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
          :i2l (if (halves-use? cp split (rest insns))
                 (let [v (pop!)] (push-halves! (sx "lh-i2l-h" v) (sx "lh-u32" v)))
                 (un identity "J"))
          ;; the low half as a signed int
          :l2i (if (= "j" (top-t))
                 (let [[_ vl] (pop-halves!)] (push! (sx "lh-s32" vl) "I"))
                 (un #(sx "l2i" %) "I"))
          :i2d (un #(sx "fixnum->flonum" %) "D")
          :l2d (un #(sx "inexact" %) "D")
          :i2f (un #(sx "f32" (sx "fixnum->flonum" %)) "F")
          :l2f (un #(sx "f32" (sx "inexact" %)) "F")
          :f2d (un identity "D")
          :d2f (un #(sx "f32" %) "F")
          (:f2i :d2i) (un #(sx "d2i" %) "I")
          (:f2l :d2l) (un #(sx "d2l" %) "J")
          :i2b (un #(sx "wrap-byte" %) "I")
          :i2c (un #(sx "ix-and" % "#xffff") "I")
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
          :athrow (vreset! terminal (sx "jathrow-at" (pop!) (site pc)))
          :monitorenter (stmt! (sx "jmonitor-enter" (sx "nn-at" (pop!) (site pc))))
          :monitorexit (stmt! (sx "jmonitor-exit" (sx "nn-at" (pop!) (site pc))))
          :getstatic (let [{:keys [class name desc]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl pc) (push! (static-global decl name) (desc->t desc)))
          :putstatic (let [{:keys [class name]} (cp a0)
                           decl ((:resolve-static-field resolve) class name)]
                       (init decl pc) (stmt! (sx "set!" (static-global decl name) (pop!))))
          :getfield (let [{:keys [class name desc]} (cp a0)
                          idx ((:field-index resolve) class name)]
                      (un #(sx "field-ref" % (str idx) (site pc)) (desc->t desc)))
          :putfield (let [{:keys [class name]} (cp a0)
                          idx ((:field-index resolve) class name)
                          v (pop!) o (pop!)]
                      (stmt! (sx "field-set!" o (str idx) v (site pc))))
          (:invokevirtual :invokeinterface :invokespecial :invokestatic)
          (let [{:keys [class name desc]} (cp a0)
                {:keys [args ret]} (parse-desc desc)
                argv (popn! (count args))
                recv (when-not (= op :invokestatic) (pop!))
                call (case op
                       (:invokevirtual :invokeinterface)
                       (cond
                         (and (= class "java/lang/invoke/VarHandle") (varhandle-modes name))
                         ;; signature-polymorphic: the runtime adapts the call site's
                         ;; types to the access mode's, as asType would
                         (apply sx (at pc "vh-call") (sx "nn-at" recv (site pc)) (str-lit name) (str-lit desc) argv)
                         (and (= class "java/lang/invoke/MethodHandle") (#{"invokeExact" "invoke"} name))
                         (apply sx (at pc "mh-call") (sx "nn-at" recv (site pc)) (str-lit desc) argv)
                         :else
                         (do (when-not (str/starts-with? class "[") ((:ensure-loaded resolve) class))
                             ;; a method nothing declares is NoSuchMethodError
                             ;; when the call runs, whatever the receiver
                             ((:check-virtual resolve) class name desc)
                             ;; a static one is IncompatibleClassChangeError, as resolution says
                             ((:check-kind resolve) (if (= op :invokeinterface) :interface :virtual) class name desc)
                             (if-let [decl ((:direct-target resolve) class name desc)]
                               ;; nothing can override it: call it directly,
                               ;; after the null check dispatch would do. For
                               ;; invokeinterface that is a private interface
                               ;; method, which javac calls this way
                               (apply sx (at pc (callee pc decl name desc))
                                      (sx "nn-at" recv (site pc)) argv)
                               (apply sx (at pc (sx "jvirt-ic" ((:ic! ctx)) recv (str "'" (sym (str name desc))) (site pc))) recv argv))))
                       :invokespecial
                       (let [decl ((:resolve-method resolve) class name desc)
                             _ ((:check-kind resolve) :special class name desc)]
                         ;; Object's constructor does nothing
                         (when-not (and (= decl "java/lang/Object") (= name "<init>"))
                           (apply sx (at pc (callee pc decl name desc)) recv argv)))
                       :invokestatic
                       (let [decl ((:resolve-method resolve) class name desc)
                             _ ((:check-kind resolve) :static class name desc)]
                         (init decl pc)
                         (apply sx (at pc (callee pc decl name desc)) argv)))]
            (cond (nil? call) nil
                  (= ret "V") (stmt! call)
                  :else (push! call (desc->t ret))))
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
                    ;; a code-unit vector (a lone surrogate) is quoted, being evaluated
                    lit #(if (vector? %) (str "'" (str-lit %)) (str-lit (str %)))
                    consts (map #(lit (:value (cp %))) (rest (:args bsm)))
                    conv (map (fn [t v]
                                (case t
                                  ("I" "S" "B" "J") (sx "number->string" v)
                                  "F" (sx "java-float->string" v)
                                  "D" (sx "jdouble->string" v)
                                  ;; one code unit, which may be half a surrogate pair
                                  "C" (sx "list" v)
                                  "Z" (sx "jbool->string" v)
                                  ;; the reference itself: jconcat takes its String or toString
                                  v))
                              args argv)]
                (push! (sx "jconcat" (lit recipe) (apply sx "list" conv) (apply sx "list" consts)) "A"))
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
              (let [call (apply sx (at pc "indy-call") (str "'" (sym (str *method-tag* "@" pc))) (str-lit this-class)
                                (str "'" (sx (const-datum cls (:ref-idx bsm))
                                             (apply sx (map #(const-datum cls %) (:args bsm)))))
                                (str-lit name) (str-lit desc) argv)]
                (if (= ret "V") (stmt! call) (push! call (desc->t ret))))))
          :new (let [c (:name (cp a0))]
                 ((:ensure-loaded resolve) c)
                 (push! (sx "jnew-ic" ((:ic! ctx)) (str "'" (sym c)) (site pc)) "A"))
          ;; the element descriptor is interned once per method (runtime.ss)
          :newarray (let [e (newarray-elem a0) el ((:ic! ctx) (sx "intern-elem" (str-lit e)))]
                      (un #(sx (at pc "jnewarray-interned") el % (if (#{"F" "D"} e) "0.0" "0")) "A"))
          ;; the element class resolves here, as the JVM's anewarray does: making
          ;; the array alone wouldn't load it
          :anewarray (let [c (:name (cp a0))
                           _ (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                           e (if (str/starts-with? c "[") c (str "L" c ";"))
                           el ((:ic! ctx) (sx "intern-elem" (str-lit e)))]
                       (un #(sx (at pc "jnewarray-interned") el % "#f") "A"))
          :multianewarray (let [c (:name (cp a0)) dims (popn! (second args))]
                            (push! (sx (at pc "jmultiarray") (str-lit c) (apply sx "list" dims)) "A"))
          :arraylength (un #(sx "array-length" % (site pc)) "I")
          :checkcast (let [c (:name (cp a0))]
                       (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                       (let [v (s (dec (count @st)))] (bind! v (sx "jcheckcast-ic" ((:ic! ctx)) v (str-lit c) (site pc)))))
          :instanceof (let [c (:name (cp a0))]
                        (when-not (str/starts-with? c "[") ((:ensure-loaded resolve) c))
                        (let [ic ((:ic! ctx))] (un #(sx "if" (sx "jinstance-ic?" ic % (str-lit c)) "1" "0") "I")))
          (throw (ex-info (str "opcode not in spike: " (name op)) {:op op :pc pc :class this-class})))
        ;; the stack depth after the instruction, which lets render-steps
        ;; close the lets of the slots it popped. Not after the block's last
        ;; instruction, whose terminal expression (a branch test, a return
        ;; value) reads what it popped.
        (when (nil? @terminal) (conj! stmts [:depth (count @st)])))
        false
        (catch Exception e
          (let [c (missing-class e)
                k (wrong-kind e)
                r (refused-class e)
                m (or (missing-method e) k)
                bad (or (bad-class e) r)
                ncdfe (cond k (apply sx "jthrow-icce" (map str-lit k))
                            r (apply sx "jthrow-linkage" (map str-lit r))
                            m (apply sx "jthrow-no-method" (map str-lit m))
                            bad (apply sx "jthrow-class-format" (map str-lit bad))
                            :else (sx "jthrow-missing" (str-lit c)))]
            (when-not (or c m bad) (throw e))
            (case (when-not m op)
              ;; on null, neither resolves its class, as HotSpot doesn't: a
              ;; cast passes and instanceof is false; only an object throws
              :checkcast (let [v (s (dec (count @st)))]
                           (bind! v (sx "if" v ncdfe v))
                           (conj! stmts [:depth (count @st)])
                           false)
              :instanceof (do (un #(sx "if" % ncdfe "0") "I")
                              (conj! stmts [:depth (count @st)])
                              false)
              (do (vreset! terminal ncdfe) true)))))
       (recur (if @fused (drop 2 insns) (rest insns))))))
    (let [term (or @terminal (goto next-pc))]
      {:code (render-steps (persistent! stmts) term)
       :succ @succ})))

(defn- entry-label [pc] (sym (str *method-tag* "#E" pc)))

(defn- covering
  "Indices of the handlers whose range covers pc, in exception table order."
  [handlers pc]
  (vec (keep-indexed (fn [i {:keys [start end]}] (when (and (<= start pc) (< pc end)) i)) handlers)))

(def ^:private load-ops #{:iload :lload :fload :dload :aload :iinc})
(def ^:private store-ops #{:istore :lstore :fstore :dstore :astore :iinc})

(defn- boxed-locals
  "The locals some handler reads on entry that the code it covers assigns.
  A handler sees a local's value at the throw, which only a method-wide
  variable holds: every other local keeps the value it had when the guarded
  region was entered, so the guard passes that on."
  [blks handlers cover]
  (let [use-def (into {} (for [[pc blk] blks]
                           [pc (reduce (fn [[u d] {:keys [op args]}]
                                         (let [i (first args)]
                                           [(if (and (load-ops op) (not (d i))) (conj u i) u)
                                            (if (store-ops op) (conj d i) d)]))
                                       [#{} #{}] blk)]))
        ;; live-in = use + (normal successors' live-in - def) + every covering
        ;; handler's live-in, which a throw before the def reaches
        live-in (loop [li (zipmap (keys blks) (repeat #{}))]
                  (let [li' (into {} (for [[pc [u d]] use-def]
                                       [pc (-> u
                                               (into (remove d) (mapcat li (successors (peek (blks pc)))))
                                               (into (mapcat #(li (:handler (nth handlers %))) (cover pc))))]))]
                    (if (= li li') li (recur li'))))]
    (set (for [[i {:keys [handler]}] (map-indexed vector handlers)
               :let [assigned (into #{} (comp (filter (fn [[pc _]] (some #{i} (cover pc))))
                                              (mapcat (fn [[_ [_ d]]] d)))
                                    use-def)]
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

(defn compile-method
  "Scheme source for `(set-top-level-value! '|Cls.name(desc)| (lambda ...))`. A small leaf
  method it calls directly (invokestatic, invokespecial, or invokevirtual
  that nothing can override) is compiled into it as a let-bound procedure,
  which Chez calls directly or inlines."
  [cls method resolve]
  (let [tag (str (:name cls) "." (:name method) (:desc method))
        ;; inline-cache cells, one per site that needs one, made once per
        ;; method and shared with the methods inlined into it; given an
        ;; expression, a variable holding its value, computed once the same way
        ics (volatile! [])
        ;; a giant method keeps its cells in one vector: Chez's expander scans
        ;; a let's bindings linearly for every variable it resolves, and
        ;; SystemModules' 21 KB module-descriptor builder has 2900 sites, which
        ;; cost it a second of compile time as variables. One more load per
        ;; site is nothing in code that runs once.
        vector-ics? (> (count (:code (:code method))) ic-vector-limit)
        ic! (fn ic!
              ([] (ic! "(cons #f #f)"))
              ([init] (let [i (dec (count (vswap! ics conj init)))]
                        (if vector-ics? (str "(vector-ref ics " i ")") (str "ic" i)))))
        inlined (volatile! {})          ; callee tag -> label, or false
        defs (volatile! [])             ; [label lambda-text], in order
        bt? (System/getenv "CHAMPACA_BT")
        inline! (fn [decl name desc]
                  (when-not bt?
                    ;; one copy per call site (*inline-site*), since the
                    ;; copy's markers name the site for stack traces
                    (let [ctag (str decl "." name desc)
                          key [ctag (first *inline-site*)]]
                      (if (contains? @inlined key)
                        (get @inlined key)
                        (let [{c :cls m :method} ((:inline-candidate resolve) (:name cls) decl name desc)
                              lbl (when (and m (leaf? c m))
                                    ;; a callee that fails to translate (a
                                    ;; class missing at run time, say) stays
                                    ;; a plain call, so it fails only if called
                                    (when-let [text (try (binding [*method-tag* ctag]
                                                           (compile-method* c m resolve ic! (constantly nil)))
                                                         (catch Exception _ nil))]
                                      (let [l (sym (str tag "#I:" ctag "@" (first *inline-site*)))]
                                        (vswap! defs conj [l text])
                                        l)))]
                          (vswap! inlined assoc key (or lbl false))
                          lbl)))))
        f (binding [*method-tag* tag] (compile-method* cls method resolve ic! inline!))
        f (if (seq @defs) (sx "let" (apply sx (map (fn [[l t]] (sx l t)) @defs)) f) f)]
    ;; set-top-level-value!, not define: a top-level define compiled to a
    ;; cached unit carries the expander's record of the binding ($sc-put-cte,
    ;; a syntax object with its ribcage), 15% of a unit's bytes and a fifth
    ;; of its load time (spike 72); the value cell is the same. The let names
    ;; the procedure as define did (a code name is what stack traces and
    ;; getCallerClass read; a bare lambda has none).
    (let [g (method-global (:name cls) (:name method) (:desc method))
          body (cond (empty? @ics) f
                     vector-ics? (sx "let" (sx (sx "ics" (apply sx "vector" @ics))) f)
                     :else (sx "let" (apply sx (map-indexed (fn [i init] (sx (str "ic" i) init)) @ics)) f))]
      (sx "set-top-level-value!" (str "'" g) (sx "let" (sx (sx g body)) g)))))

(defn- compile-method*
  "The method as a lambda. ic! names a fresh inline-cache cell, inline! the
  inlined procedure for a direct callee or nil."
  [cls method resolve ic! inline!]
  (let [{:keys [code handlers]} (:code method)
        insns (decode code)
        blks (blocks insns handlers)
        next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
        {:keys [args]} (parse-desc (:desc method))
        arg-types (if (pos? (bit-and (:flags method) 0x0008)) args (cons "L" args))
        params (loop [ts arg-types slot 0 out []]
                 (if-let [t (first ts)]
                   (recur (rest ts) (+ slot (if (wide? t) 2 1)) (conj out slot))
                   out))
        param-set (set params)
        cover (into {} (map (fn [pc] [pc (covering handlers pc)]) (keys blks)))
        boxed (if (seq handlers) (boxed-locals blks handlers cover) #{})
        ;; the long locals kept as halves (emit-block): loaded and stored as
        ;; longs only, not method-wide, and given a long operation's result
        ;; somewhere, which is what would otherwise box
        param-types (into {} (map vector params arg-types))
        made-by #{:ladd :lsub :lmul :land :lor :lxor :lshl :lshr :lushr :lneg :lconst_0 :lconst_1 :ldc2_w :i2l}
        split (let [uses (group-by #(first (:args %)) (filter #(or (load-ops (:op %)) (store-ops (:op %))) insns))
                    made (set (for [[p i] (map vector insns (rest insns))
                                    :when (and (= :lstore (:op i)) (made-by (:op p)))]
                                (first (:args i))))]
                (set (for [[i us] uses
                           :when (and (made i) (not (boxed i))
                                      (every? #(#{:lload :lstore} (:op %)) us)
                                      (or (not (param-set i)) (= "J" (param-types i))))]
                       i)))
        ;; the locals a jump has to carry: the parameters and every slot the
        ;; method loads or stores, less the boxed ones, which stay method-wide
        live (vec (sort (remove boxed
                                (into param-set
                                      (for [{:keys [op args]} insns
                                            :when (or (load-ops op) (store-ops op))]
                                        (first args))))))
        ;; covered blocks entered from outside their region, each needing a
        ;; guarded entry (entry-label)
        entered (volatile! (sorted-set))
        enter (fn [pc] (if (seq (cover pc))
                         (do (vswap! entered conj pc) (entry-label pc))
                         (label pc)))
        ;; a jump inside a region is a tail call. One out of an uncovered
        ;; block is a tail call too, into the target's guarded entry when it
        ;; has one; one out of a covered region has to leave its guard first,
        ;; so it returns a thunk to the trampoline
        jump (fn [from]
               (fn [pc args]
                 (cond (= (cover from) (cover pc)) (apply sx (label pc) args)
                       (empty? (cover from)) (apply sx (enter pc) args)
                       :else (sx "lambda" "()" (apply sx (enter pc) args)))))
        ;; worklist over blocks: each is emitted once, at the stack depth its
        ;; first predecessor established (verified bytecode guarantees agreement)
        emitted (loop [work (into [[0 []]] (map (fn [h] [(:handler h) ["A"]]) handlers))
                       done {}]
                  (if-let [[pc dep] (first work)]
                    (if (contains? done pc)
                      (recur (rest work) done)
                      (let [{:keys [code succ]} (emit-block {:cls cls :resolve resolve :next-pc (next-pc pc)
                                                             :live live :boxed boxed :split split :jump (jump pc) :ic! ic! :inline! inline!}
                                                            (blks pc) dep)]
                        (recur (concat (rest work) succ) (assoc done pc [dep code]))))
                    done))
        args-of (fn [dep] (concat (mapcat #(local-names split %) live) (map #(str "s" %) (range (count dep)))))
        ;; each block takes the live locals and its entry stack
        block-defs (for [[pc [dep code]] (sort-by key emitted)]
                     (sx (label pc) (sx "lambda" (apply sx (args-of dep)) code)))
        ;; the first block, called with the parameters, a long one kept as
        ;; halves split first
        start (let [split-params (filter #(and (param-set %) (split %)) live)
                    call (apply sx (enter 0) (mapcat #(cond (split %) (if (param-set %) (local-names split %) ["0" "0"])
                                                            (param-set %) [(str "l" %)]
                                                            :else ["0"])
                                                     live))]
                (if (seq split-params)
                  (sx "let*" (apply sx (mapcat (fn [i] [(sx (str "l" i "h") (sx "lh-split-h" (str "l" i)))
                                                        (sx (str "l" i "l") (sx "lh-split-l" (str "l" i)))])
                                               split-params))
                      call)
                  call))
        ;; a guarded entry: one handler for the region (jtry), whose clauses
        ;; follow the exception table order and pass the handler the exception
        ;; and the locals as the region got them. Building one can enter a
        ;; handler's region, so this runs until no new entry turns up.
        entry-defs (loop [out [] done #{}]
                     (if-let [pc (first (remove done @entered))]
                       (let [as (args-of (first (emitted pc)))
                             clauses (doall
                                      (for [i (cover pc)
                                            :let [{:keys [type handler]} (nth handlers i)]]
                                        (sx (if type (sx "jinstance?" "e" (str-lit type)) (sx "jobj?" "e"))
                                            (apply sx (enter handler) (concat (mapcat #(local-names split %) live) ["e"])))))]
                         (recur (conj out (sx (entry-label pc)
                                              (sx "lambda" (apply sx as)
                                                  (sx "jtry" "e" (apply sx clauses)
                                                      (apply sx (label pc) as)))))
                                (conj done pc)))
                       out))]
    (let [body* (if (seq handlers)
                  (sx "let" (apply sx (for [i (sort boxed) :when (not (param-set i))] (sx (str "l" i) "0")))
                      (sx "letrec" (apply sx (concat block-defs entry-defs))
                          ;; an inline named loop rather than a shared helper,
                          ;; so a frame named for this method stays on the stack
                          ;; while a block runs under its region's handler (getCallerClass)
                          (let [t (sym (str *method-tag* "#T"))]
                            (sx "let" t (sx (sx "r" start))
                                (sx "if" (sx "procedure?" "r") (sx t (sx "r")) "r")))))
                  (sx "letrec" (apply sx block-defs) start))
          ;; ACC_SYNCHRONIZED: the monitor of this, or of the class for a
          ;; static method, held for the call and released however it ends.
          ;; dynamic-wind rather than guard: an exception leaves through a
          ;; handler further out, whose escape runs the exit before that
          ;; handler's clauses, and installing a guard costs about four times
          ;; as much. jframe below keeps a frame named after the method.
          sync? (pos? (bit-and (:flags method) 0x0020))
          bt? (System/getenv "CHAMPACA_BT")
          body (if sync?
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
                           (sx "lambda" "()" body*)
                           (sx "lambda" "()" (sx "jmonitor-exit" "lock")))))
                 body*)
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
      (sx "lambda" (apply sx (map #(str "l" %) params))
          ;; npe-tag, a constant Chez folds into each site that uses it,
          ;; inside the lambda so the method stays a plain lambda to Chez
          (sx "let" (sx (sx "npe-tag" (str "'" (sym *method-tag*))))
              body)))))
