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
               (5 9) (cond
                       ;; a private or final method, or a final class's: the
                       ;; one method, after the null check dispatch would do,
                       ;; as invokevirtual; javac 25 references a private
                       ;; method (this::foo) as kind 5, and the vtable keeps
                       ;; an inherited entry under its name
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

(defn- switch-labels
  "The case labels of a typeSwitch or enumSwitch as handles.ss's quoted list,
  or nil when one is a primitive pattern's (JEP 507, a preview in JDK 25: a
  primitive class, a long, float, double or boolean constant), whose match
  depends on the selector's type; the JDK's bootstrap runs for those."
  [cls enum? arg-idxs]
  (let [cp (:cp cls)
        label (fn [i]
                (let [e (cp i)]
                  (case (:tag e)
                    :class (sx "class" (str-lit (:name e)))
                    :string (sx (if enum? "name" "string") (str-lit (:value e)))
                    :int (sx "int" (str (:value e)))
                    (:long :float :double) nil
                    :dynamic (let [[bsm-name [mh & args]] (condy cls e)]
                               (cond
                                 (= bsm-name "primitiveClass") nil
                                 ;; a qualified enum constant: EnumDesc.of(ClassDesc.of(binary name), constant name)
                                 (and (= bsm-name "invoke") (= "of" (:name (cp (:ref-idx (cp mh)))))
                                      (= "java/lang/Enum$EnumDesc" (:class (cp (:ref-idx (cp mh))))))
                                 (let [[_ [_ cname]] (condy cls (cp (first args)))]
                                   (sx "enum" (str-lit (str/replace (:value (cp cname)) "." "/"))
                                       (str-lit (:value (cp (second args))))))
                                 ;; Boolean.TRUE or FALSE, a boolean constant's label
                                 (= bsm-name "getStaticFinal") nil
                                 :else (throw (ex-info (str "switch label not in spike: condy " bsm-name)
                                                       {:class (:name cls)}))))
                    (throw (ex-info (str "switch label not in spike: " (:tag e)) {:class (:name cls)})))))
        labels (map label arg-idxs)]
    (when (every? some? labels)
      (str "'" (apply sx labels)))))

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

(defn- array-class-element
  "The class an array descriptor's element names (\"[[Lp/X;\" -> \"p/X\"), or
  nil for an array of primitives."
  [d]
  (let [e (str/replace d #"^\[+" "")]
    (when (str/starts-with? e "L") (subs e 1 (dec (count e))))))

(defn- resolve-type!
  "Loads the class a checkcast or instanceof names, or a call site's
  descriptor (resolve-site!): an array's element class
  (JVMS 5.4.3.1), whose absence is the array's (NoClassDefFoundError names the
  array, as HotSpot's). A class the element needs, such as its superclass,
  stays the one named."
  [resolve c]
  (if (str/starts-with? c "[")
    (when-let [el (array-class-element c)]
      (try ((:ensure-loaded resolve) el)
           (catch Exception e
             (if (= el (missing-class e)) (throw (ex-info (str "class not found: " c) {:class c})) (throw e)))))
    ((:ensure-loaded resolve) c)))

(defn- resolve-site!
  "Resolves the classes a signature-polymorphic call's or an invokedynamic's
  descriptor names, parameters in order then the return type, as HotSpot
  resolves the call site's MethodType when it links the instruction: a
  missing one makes the instruction throw NoClassDefFoundError (an array's
  names the array), whatever the receiver. A plain invoke's descriptor isn't
  resolved, there or here. A bootstrap's static arguments are
  resolve-bsm-args!'s."
  [resolve desc]
  (let [{:keys [args ret]} (parse-desc desc)]
    (doseq [t (conj args ret)]
      (case (first t)
        \L (resolve-type! resolve (subs t 1 (dec (count t))))
        \[ (resolve-type! resolve t)
        nil))))

(defn- resolve-bsm-args!
  "Resolves the classes the static arguments of a bootstrap codegen compiles
  directly name (a lambda's method types and implementation handle, a record's
  getters, a pattern switch's class labels), in order, as HotSpot resolves
  them when it links the invokedynamic, after the call site's type
  (resolve-site!). A Class constant is the class, a MethodType its
  descriptor's classes, a MethodHandle its member's class, the method itself,
  then its descriptor (a field's type; a missing field isn't checked). Dynamic
  constants resolve when they run (handles.ss)."
  [cls resolve arg-idxs]
  (let [cp (:cp cls)]
    (doseq [i arg-idxs :let [e (cp i)]]
      (case (:tag e)
        :class (resolve-type! resolve (:name e))
        :methodtype (resolve-site! resolve (:desc e))
        :methodhandle (let [{:keys [class name desc]} (cp (:ref-idx e))
                            kind (int (:kind e))]
                        (resolve-type! resolve class)
                        ;; the member before its descriptor (JVMS 5.4.3.5): a
                        ;; method that isn't there is NoSuchMethodError, whatever
                        ;; its descriptor names
                        (case kind
                          (5 9) ((:check-virtual resolve) class name desc)
                          (6 7 8) ((:resolve-method resolve) class name desc)
                          nil)
                        ;; kinds 1-4 are field accessors: the descriptor is the
                        ;; field's type, read as a method returning it
                        (resolve-site! resolve (if (<= 1 kind 4) (str "()" desc) desc)))
        nil))))

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

(defn- emit-array-load [e {:keys [op pc]} _]
  (let [hv (hoisted e (- (stack-depth e) 2))]
    (bin! e (fn [a i] (if-let [[v n] hv] (sx "vec-ref" v n i (site pc)) (sx "array-ref" a i (site pc))))
          (array-load-types op "I"))))

(defn- emit-aastore [e {:keys [pc]} _]
  (let [[a i v] (popn! e 3)]
    (stmt! e (sx "array-store!" ((:ic! e)) a i v (site pc)))))

(defn- emit-array-store [e {:keys [op pc]} _]
  (let [hv (hoisted e (- (stack-depth e) 3))
        [a i v] (popn! e 3)
        x (case op :bastore (sx "wrap-byte" v) :castore (sx "ix-and" v "#xffff") :sastore (sx "wrap-short" v) v)]
    (stmt! e (if-let [[vv n] hv] (sx "vec-set!" vv n i x (site pc)) (sx "array-set!" a i x (site pc))))))

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

(defn- emit-monitor [e {:keys [op pc]} _]
  (stmt! e (sx (if (= op :monitorenter) "jmonitor-enter" "jmonitor-exit") (sx "nn-at" (pop! e) (site pc)))))

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
      (apply sx (at pc (callee e pc decl name desc)) (sx "nn-at" recv (site pc)) argv)
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
                 (apply sx (at pc (callee e pc decl name desc)) argv)))]
    (cond (nil? call) nil
          (= ret "V") (stmt! e call)
          :else (push! e call (desc->t ret)))))

;; --- invokedynamic -------------------------------------------------------------------

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
                      ("I" "S" "B" "J") (sx "number->string" v)
                      "F" (sx "java-float->string" v)
                      "D" (sx "jdouble->string" v)
                      "C" (sx "list" v)
                      "Z" (sx "jbool->string" v)
                      v))
                  params argv)]
    (sx "jconcat" (lit recipe) (apply sx "list" conv) (apply sx "list" consts))))

(defn- record-op-call
  "java.lang.runtime.ObjectMethods' bootstrap: a record's toString, equals or
  hashCode."
  [e bsm name argv]
  (let [cp (:cp e)
        [_rec _names-idx & getters] (:args bsm)
        fields (for [g getters :let [{:keys [class name desc]} (cp (:ref-idx (cp g)))]]
                 (sx "list" (str-lit name) (str ((:field-index (:resolve e)) class name)) (str-lit desc)))
        rec-class (:name (cp (first (:args bsm))))
        simple (last (str/split rec-class #"[/$]"))]
    (apply sx "jrecord-op" (str-lit name) (str-lit simple)
           (str "(list " (str/join " " fields) ")") argv)))

(defn- indy-call
  "A bootstrap that runs for real, once per call site (handles.ss)."
  [e pc bsm name desc argv]
  (let [cls (:cls e)]
    (apply sx (at pc "indy-call") (str "'" (sym (str *method-tag* "@" pc))) (str-lit (:this-class e))
           (str "'" (sx (const-datum cls (:ref-idx bsm))
                        (apply sx (map #(const-datum cls %) (:args bsm)))))
           (str-lit name) (str-lit desc) argv)))

(defn- bootstrap-name
  "The bootstrap's method name; a typeSwitch or enumSwitch in a class other
  than SwitchBootstraps is named with its class, so it runs for real."
  [cp bsm]
  (let [bsm-ref (cp (:ref-idx (cp (:ref-idx bsm))))
        n (:name bsm-ref)]
    (if (and (#{"typeSwitch" "enumSwitch"} n)
             (not= (:class bsm-ref) "java/lang/runtime/SwitchBootstraps"))
      (str (:class bsm-ref) "." n)
      n)))

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
      (do (compiled!) (push! e (lambda-expr e name ret params argv bsm) "A"))
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
    (un! e #(sx "array-length" % (site pc)) "I")))

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
   (zipmap [:tableswitch :lookupswitch] (repeat emit-switch))
   (zipmap [:ireturn :lreturn :freturn :dreturn :areturn :return :athrow] (repeat emit-end))
   (zipmap [:monitorenter :monitorexit] (repeat emit-monitor))
   (zipmap [:getstatic :putstatic :getfield :putfield] (repeat emit-field))
   (zipmap [:invokevirtual :invokeinterface :invokespecial :invokestatic] (repeat emit-invoke))
   {:nop (fn [_ _ _] nil)
    :iinc emit-iinc :aastore emit-aastore
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

(def ^:private array-ops
  #{:iaload :laload :faload :daload :aaload :baload :caload :saload
    :iastore :lastore :fastore :dastore :bastore :castore :sastore :arraylength})

(defn- array-loops
  "header pc -> {:body #{pc} :locals #{slot}}: the innermost natural loops of
  blks that do something to an array and aload a local they never store."
  [blks]
  (let [succs (into {} (for [[pc blk] blks] [pc (filter blks (successors (peek blk)))]))]
    (when (and (some (fn [[_ blk]] (some #(array-ops (:op %)) blk)) blks)
               (some (fn [[pc ss]] (some #(<= % pc) ss)) succs))
      (let [;; reachable blocks in reverse postorder, iteratively
            rpo (loop [stack [[0 (succs 0)]] seen #{0} out ()]
                  (if-let [[pc ss] (peek stack)]
                    (let [ss (drop-while seen ss)]
                      (if-let [n (first ss)]
                        (recur (conj (pop stack) [pc (rest ss)] [n (succs n)]) (conj seen n) out)
                        (recur (pop stack) seen (conj out pc))))
                    out))
            reach (set rpo)
            preds (reduce (fn [m [p ss]] (if (reach p) (reduce #(update %1 %2 (fnil conj []) p) m ss) m)) {} succs)
            meet (fn [sets] (reduce (fn [a b] (into #{} (filter b) a)) sets))
            dom (loop [dom (into {0 #{0}} (map (fn [pc] [pc reach]) (rest rpo)))]
                  (let [d (reduce (fn [d pc] (assoc d pc (conj (meet (map d (preds pc))) pc))) dom (rest rpo))]
                    (if (= d dom) dom (recur d))))
            ;; a back edge p -> h, h dominating p, and the blocks that reach
            ;; p without passing h
            bodies (reduce (fn [m [p h]]
                             (update m h (fn [b]
                                           (loop [b (conj (or b #{h}) p) work [p]]
                                             (if-let [x (peek work)]
                                               (let [new (remove b (when (not= x h) (preds x)))]
                                                 (recur (into b new) (into (pop work) new)))
                                               b)))))
                           {}
                           (for [p rpo h (succs p) :when ((dom p) h)] [p h]))]
        (into {}
              (for [[h body] bodies
                    :when (not-any? #(and (not= % h) (bodies %)) body)
                    :let [insns (mapcat blks body)
                          stored (set (keep #(when (store-ops (:op %)) (first (:args %))) insns))
                          locals (set (keep #(when (= :aload (:op %)) (first (:args %))) insns))
                          locals (into #{} (remove stored) locals)]
                    :when (and (seq locals) (some #(array-ops (:op %)) insns))]
                [h {:body body :locals locals}]))))))

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
     :handlers handlers :blks blks :cover cover :boxed boxed
     :params params :param-set param-set
     :next-pc (into {} (map vector (keys blks) (concat (rest (keys blks)) [nil])))
     :split (split-locals insns boxed param-set (zipmap params arg-types))
     :live (live-locals insns param-set boxed)
     ;; array loops to version (handler-free methods of a size whose
     ;; blocks' dominators are cheap to find)
     :loops (or (when (and (empty? handlers) (<= (count code) ic-vector-limit))
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
  [{:keys [cls resolve next-pc live boxed split ic! inline!]} pc jump]
  {:cls cls :resolve resolve :next-pc (next-pc pc)
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

(defn- block-defs [plan emitted]
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

(defn- version-defs [{:keys [loops] :as plan} emitted]
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
        bt? (System/getenv "CHAMPACA_BT")
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
