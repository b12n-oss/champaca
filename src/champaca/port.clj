;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns champaca.port
  "A Java static method written out as a jolt function.

  The method is translated as champaca translates any method, in its plain
  form, and put through the structuring pass (structure.ss). What comes out
  has a procedure for each loop head and a let for each value, over a small
  set of runtime operations. This namespace writes that as Clojure: the
  loop as loop and recur, int arithmetic as the unchecked int operations,
  array access as aget and aset, and each call of a JDK method through a
  rule that names the jolt form standing for it.

  A method is ported only when every operation and every call in it has a
  rule. Anything else is left, with the reason, and stays a method champaca
  runs. A rule can carry a condition (a String read by index holds for
  characters up to U+FFFF); the conditions a port relies on come back with
  it, and champaca.portcheck checks the port against the method under them."
  (:require [champaca.api :as ldk]
            [champaca.bytecode :as bc]
            [champaca.cgtext :refer [parse-desc]]
            [champaca.classfile :as cf]
            [champaca.jit :as jit]
            [champaca.link :as link]
            [champaca.loader :as loader]
            [champaca.port.rules :as rules]
            [champaca.port.tidy :refer [do-form mentions? tidy]]
            [champaca.portcheck :as pc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as s]))

(defn- left
  "Stop porting this method: why is what it has that no rule covers."
  [why]
  (throw (ex-info why {::left why})))

;; --- the structured form, as data -------------------------------------------------
;; A list is a vector, a symbol a string, a string [:str "..."] (port.ss).

(def ^:private helper-loaded (atom false))

(defn- structured
  "[:ok form] or [:left reason] for a method's generated text."
  [text]
  (when-not @helper-loaded
    (s/eval-string (slurp (io/resource "champaca/port.ss")))
    (reset! helper-loaded true))
  (read-string (s/call "port-structured" text)))

(defn- head [x] (when (vector? x) (first x)))
(defn- text-of [x] (when (= :str (head x)) (second x)))
(defn- quoted [x] (when (= "quote" (head x)) (second x)))

(defn- unmark
  "x without the pc marker a call's operator carries: (begin '(jpc . n) x)."
  [x]
  (if (and (= "begin" (head x)) (= 3 (count x)) (quoted (second x)))
    (nth x 2)
    x))

(defn- safepoint? [x]
  (and (= "when" (head x)) (= ["jsafepoint"] (last x))))

(defn- jdk-class?
  "A class the port never writes out: calls into it go through rules."
  [cname]
  (boolean (re-find #"^(java|javax|jdk|sun)/" cname)))

(defn- init-of
  "The class an initialization check names, or nil when x isn't one."
  [x]
  (when (= "jinit-ic" (head x)) (str (quoted (nth x 2)))))

(defn- drop-init?
  "Whether the initialization check x goes: every one does. A call into a
  JDK class goes through a rule to a jolt form, which has no class to
  initialize. A call into another class of the program becomes a call of
  that method's function, and there the port holds only if the class's
  initializer does nothing a caller can see, which is noted as a condition
  when the initializer calls anything."
  [ctx x]
  (when-let [cname (init-of x)]
    (when (and (not (jdk-class? cname)) ((:effects? ctx) cname))
      (vswap! (:holds ctx) conj :initializers-without-effects))
    true))

;; --- values -----------------------------------------------------------------------

(declare value)

(defn- hold! [ctx rule]
  (when-let [c (:holds rule)] (vswap! (:holds ctx) conj c)))

(defn- apply-rule [ctx rule what args]
  (when-not rule (left (str "no rule for " what)))
  (hold! ctx rule)
  ((:form rule) (mapv #(value ctx %) args)))

(def ^:private element-classes
  "Reference arrays the port makes: the element's class as jolt names it."
  {"Ljava/lang/String;" 'String "Ljava/lang/Object;" 'Object})

(defn- new-array [ctx [ic len]]
  (let [elem (text-of (second (get (:ics ctx) ic)))
        maker (rules/array-makers elem)
        of (element-classes elem)]
    (when-not (or maker of) (left (str "no rule for an array of " elem)))
    (vreset! (:sized ctx) true)            ; a size from an argument, perhaps
    (if maker
      (list maker (value ctx len))
      (list 'make-array of (value ctx len)))))

(defn- sibling-call
  "A call of another static method the port writes out, of the class being
  ported or of another class of the program: the function that method
  becomes. Its boolean and char parameters and result are
  Clojure's there, and 1 or 0 and a code here. The call is noted, since this
  port stands only if that one does."
  [ctx {:keys [name desc]} f args]
  (let [{types :args ret :ret} (parse-desc desc)
        arg (fn [t a] (let [v (value ctx a)]
                        (case t "Z" (list 'not= 0 v) "C" (list 'char v) v)))
        call (apply list name (map arg types args))]
    (vswap! (:calls ctx) conj f)
    (case ret "Z" (list 'if call 1 0) "C" (list 'int call) call)))

(defn- call-value
  "A call: its operator is a method's global, a virtual lookup, or the
  array allocator."
  [ctx [f & args]]
  (let [f (unmark f)]
    (cond
      (= "jnewarray-interned" f) (new-array ctx args)
      (and (string? f) ((:statics ctx) f)) (sibling-call ctx ((:statics ctx) f) f args)
      (string? f) (apply-rule ctx (rules/direct-rules f) f args)
      (= "jvirt-ic" (head f)) (let [k (quoted (nth f 3))] (apply-rule ctx (rules/virtual-rules k) k args))
      :else (left "a call no rule covers"))))

(defn- concat-piece
  "One argument of a string concatenation, as str takes it: a char is its
  code here, a number prints as itself, and a reference goes through
  String/valueOf, which gives \"null\" for null as Java does."
  [ctx x]
  (case (head x)
    "list" (list 'char (value ctx (second x)))
    "number->string" (value ctx (second x))
    (list 'String/valueOf (value ctx x))))

(defn- concat-value
  "makeConcatWithConstants: the recipe's literal runs and its arguments. The
  recipe arrives parsed: literals, and 1 where the next argument goes."
  [ctx [_ parts [_ & args] [_ & consts]]]
  (when (seq consts) (left "a concatenation with bootstrap constants"))
  (loop [parts (quoted parts) args args out []]
    (if-let [part (first parts)]
      (cond (text-of part) (recur (rest parts) args (conj out (text-of part)))
            (= 1 part) (recur (rest parts) (rest args) (conj out (concat-piece ctx (first args))))
            :else (left "a concatenation recipe that isn't plain text"))
      (apply list 'str out))))

(defn- wrapped
  "(wrap-int x): an int operation that wraps, or a fold to 32 bits."
  [ctx x]
  (if-let [op (rules/wrapped-ops (head x))]
    (list op (value ctx (nth x 1)) (value ctx (nth x 2)))
    (list 'unchecked-int (value ctx x))))

(defn- operation
  "A runtime operation that isn't a call."
  [ctx [h a b c :as x]]
  (case h
    "wrap-int" (wrapped ctx a)
    "nn-at" (value ctx a)
    "int-rem" (list 'rem (value ctx a) (value ctx b))
    "int-div" (list 'unchecked-int (list 'quot (value ctx a) (value ctx b)))
    "array-length" (list 'alength (value ctx a))
    ;; jolt's aget answers nil for a nil array and its aset throws another
    ;; class, where Java throws NullPointerException: non-null keeps Java's
    "array-ref" (list 'aget (list 'non-null (value ctx a)) (value ctx b))
    "array-set!" (list 'aset (list 'non-null (value ctx a)) (value ctx b) (value ctx c))
    ;; a store into a reference array: (array-store! cell array index value)
    "array-store!" (list 'aset (list 'non-null (value ctx b)) (value ctx c) (value ctx (nth x 4)))
    "wrap-byte" (list 'unchecked-byte (value ctx a))
    "wrap-short" (list 'unchecked-short (value ctx a))
    "char-ref" (list 'int (list 'aget (list 'non-null (value ctx a)) (value ctx b)))
    "char-set!" (list 'aset (list 'non-null (value ctx a)) (value ctx b) (list 'char (value ctx c)))
    "jstring-lit" (or (text-of a) (left "a string constant that isn't plain text"))
    "jcheckcast-ic" (value ctx b)
    "jconcat-parts" (concat-value ctx x)
    "void" nil
    (if-let [op (rules/int-ops h)]
      (list op (value ctx a) (value ctx b))
      (left (str "no rule for " h)))))

(defn- field-read
  "A read of a static field: the name of the def that holds its constant.
  The field is noted, since the def must come before the function."
  [ctx global]
  (let [{:keys [name] :as field} ((:field ctx) global)]
    (when (:effects? field) (vswap! (:holds ctx) conj :initializers-without-effects))
    (vswap! (:fields ctx) assoc name (:init field))
    name))

(defn- value
  "The Clojure form for a structured expression used for its value."
  [ctx x]
  (cond
    ;; a local is l3, s0, l2_1; a name holding ( or : is a global, which here
    ;; can only be a static field read (a method's global is a call's
    ;; operator, handled before this)
    (and (string? x) (re-find #"[(:]" x)) (field-read ctx x)
    (string? x) (symbol x)
    (number? x) x
    (false? x) nil
    (text-of x) (text-of x)
    (vector? (head x)) (call-value ctx x)
    (vector? x) (operation ctx x)
    :else (left "a constant no rule covers")))

(defn- test-form [ctx t]
  (cond
    (string? t) (list 'some? (symbol t))
    (= "not" (head t)) (list 'nil? (value ctx (second t)))
    (rules/compare-ops (head t)) (list (rules/compare-ops (head t)) (value ctx (nth t 1)) (value ctx (nth t 2)))
    :else (left (str "no rule for the test " (head t)))))

;; --- control ----------------------------------------------------------------------

(declare expr)

(defn- returned
  "v as the method returns it: a boolean method's 1 and 0 are true and false,
  a char method's code a char."
  [ctx v]
  (case (:ret ctx)
    "Z" (cond (= 1 v) true (= 0 v) false :else (list 'not= 0 v))
    "C" (list 'char v)
    v))

(defn- statement
  "A form whose value nothing uses: (let [x E] nil), which is how a call
  with an unused result comes out, is E."
  [form]
  (if (and (seq? form) (= 'let (first form)) (= 2 (count (second form))) (nil? (nth form 2)) (= 3 (count form)))
    (second (second form))
    form))

(defn- body
  "Forms in sequence, the last in tail position when tail? is."
  [ctx forms tail?]
  (let [forms (vec (remove #(or (safepoint? %) (drop-init? ctx %)) forms))]
    (concat (map #(statement (expr ctx % false)) (butlast forms))
            [(expr ctx (last forms) tail?)])))

(defn- constructor-rule [ctx cname desc]
  (or (get-in rules/constructor-rules [cname desc]) (left (str "no rule for new " cname)))
  (when (= "(I)V" desc) (vreset! (:sized ctx) true))   ; a capacity
  (get-in rules/constructor-rules [cname desc]))

(declare constructor-call)

(defn- bound-before
  "found with binds, symbol and form in turn, ahead of its own."
  [binds found]
  (update found :binds #(vec (concat binds %))))

(defn- through-new
  "constructor-call's answer through (let ([n (jnew-ic ...)]) (begin <init
  call on n> x)): an object made on the way to v's arguments, bound to n
  before what x binds."
  [ctx v n init forms]
  (let [inner (first forms)]
    (when (and (= "begin" (head inner)) (= 3 (count inner)))
      (when-let [made (constructor-call ctx n (nth inner 1))]
        (when-let [found (constructor-call ctx v (nth inner 2))]
          (let [make (constructor-rule ctx (quoted (nth init 2)) (:desc made))]
            (bound-before (concat (:binds made) [(symbol n) (make (mapv #(value ctx %) (:args made)))])
                          found)))))))

(defn- through-let
  "constructor-call's answer through a let around one form."
  [ctx v [_ bindings & forms]]
  (let [[n init] (first bindings)]
    (cond
      (not= 1 (count forms)) nil
      (and (= 1 (count bindings)) (= "jnew-ic" (head init))) (through-new ctx v n init forms)
      :else (when-let [found (constructor-call ctx v (first forms))]
              (bound-before (mapcat (fn [[n i]] [(symbol n) (value ctx i)]) bindings) found)))))

(defn- constructor-call
  "The <init> call on v in x, with what is bound on the way to its
  arguments: {:binds [symbol form ...] :desc :args}, the bindings already
  Clojure, or nil when x isn't one. On the way may be plain lets and other
  objects made with their own constructor calls, as javac writes
  new A(new B(...).m())."
  [ctx v x]
  (cond
    (= "let" (head x)) (through-let ctx v x)
    (vector? (head x))
    (let [f (unmark (first x))]
      (when (and (string? f) (str/includes? f ".<init>(") (= v (second x)))
        {:binds [] :desc (subs f (str/index-of f "(")) :args (drop 2 x)}))))

(defn- new-form
  "(let ([v (jnew-ic ...)]) (begin <init call> rest...)): the allocation and
  its constructor call as one constructor form bound to v."
  [ctx v cname rest-forms tail?]
  (let [call (or (constructor-call ctx v (first rest-forms)) (left (str "no rule for new " cname)))
        make (constructor-rule ctx cname (:desc call))]
    (list 'let
          (vec (concat (:binds call) [(symbol v) (make (mapv #(value ctx %) (:args call)))]))
          (do-form (body ctx (rest rest-forms) tail?)))))

(defn- sequential-bindings
  "Scheme's let and a block's parameters bind all at once; Clojure's let and
  loop bind one after another. pairs of [symbol form], meant all at once, as
  a binding vector that means the same in order: when a form uses a name
  bound before it in the list (a swap), every value is taken first under a
  name of its own."
  [pairs]
  (let [clash? (some (fn [[i [_ form]]] (some #(mentions? (first %) form) (take i pairs)))
                     (map-indexed vector pairs))]
    (if clash?
      (let [held (map (fn [[n _]] (symbol (str n "_in"))) pairs)]
        (vec (concat (mapcat (fn [h [_ form]] [h form]) held pairs)
                     (mapcat (fn [h [n _]] [n h]) held pairs))))
      (vec (mapcat identity pairs)))))

(defn- let-form [ctx [_ bindings & forms] tail?]
  (let [[v init] (first bindings)]
    (if (and (= 1 (count bindings)) (= "jnew-ic" (head init)))
      (let [inner (first forms)]
        (when-not (and (= 1 (count forms)) (= "begin" (head inner))) (left "an allocation used before its constructor"))
        (new-form ctx v (quoted (nth init 2)) (rest inner) tail?))
      (list 'let
            (sequential-bindings (map (fn [[n i]] [(symbol n) (value ctx i)]) bindings))
            (do-form (body ctx forms tail?))))))

(defn- block-name [label] (symbol (str "b" (subs label (+ 2 (str/last-index-of label "#B"))))))

(defn- jump
  "A call of a block: recur inside the loop it heads, the loop itself from
  outside, a call for a block kept as a fn."
  [ctx [label & args]]
  (let [args (map #(value ctx %) args)
        {:keys [params code]} (get (:blocks ctx) label)]
    (cond
      (not= label (:loop ctx)) (apply list (block-name label) args)
      (:in-loop ctx) (do (vreset! (:sized ctx) true) (apply list 'recur args))   ; a loop
      :else (let [pairs (map vector (map symbol params) args)
                  clash? (not= (vec (mapcat identity pairs)) (sequential-bindings pairs))]
              ;; a loop's first values are bound in order too: when one uses a
              ;; parameter's name bound before it, they are taken in a let first
              (if clash?
                (let [held (map (fn [[n _]] (symbol (str n "_in"))) pairs)]
                  (list 'let (vec (interleave held (map second pairs)))
                        (list 'loop (vec (interleave (map first pairs) held))
                              (do-form (body (assoc ctx :in-loop true) code true)))))
                (list 'loop (vec (mapcat identity pairs))
                      (do-form (body (assoc ctx :in-loop true) code true))))))))

(defn- calls
  "How many times x calls the block label."
  [label x]
  (cond (not (vector? x)) 0
        (= "quote" (head x)) 0
        ;; a form's head is a name; a list of bindings has none, and its
        ;; first element counts like the rest
        :else (+ (if (= label (head x)) 1 0)
                 (reduce + (map #(calls label %) (if (string? (first x)) (rest x) x))))))

(defn- inline-block
  "x with each call of label replaced by the block's body under its
  parameters."
  [label {:keys [params code] :as block} x]
  (cond (not (vector? x)) x
        (= "quote" (head x)) x
        (= label (head x)) (let [pairs (remove (fn [[p a]] (= p a)) (map vector params (rest x)))]
                             (if (seq pairs) (into ["let" (vec pairs)] code) (into ["begin"] code)))
        :else (mapv #(inline-block label block %) x)))

(defn- reaches?
  "Whether block from can get to block to through the calls among blocks."
  [blocks from to]
  (loop [todo [from] seen #{}]
    (if-let [l (first todo)]
      (let [called (filter #(pos? (calls % (into ["begin"] (:code (blocks l))))) (keys blocks))]
        (cond (some #(= to %) called) true
              (seen l) (recur (rest todo) seen)
              :else (recur (concat (rest todo) called) (conj seen l))))
      false)))

(defn- join-blocks
  "blocks with every block that isn't a loop's head copied into its callers,
  and the letrec's own forms likewise: a join point or an exit becomes part
  of each path to it. A block that can't get back to itself is one of those.
  Among blocks that call each other, the one the letrec's forms enter stays
  as the head and the others are copied into it."
  [blocks forms]
  (let [entered? (fn [l] (pos? (calls l (into ["begin"] forms))))
        self-call? (fn [l] (pos? (calls l (into ["begin"] (:code (blocks l))))))
        pick (or (first (remove #(reaches? blocks % %) (keys blocks)))
                 (first (remove #(or (self-call? %) (entered? %)) (keys blocks))))]
    (if pick
      (let [b (blocks pick)
            sub (fn [x] (inline-block pick b x))]
        (recur (into {} (map (fn [[l o]] [l (update o :code #(mapv sub %))]) (dissoc blocks pick)))
               (mapv sub forms)))
      [blocks forms])))

(defn- letrec-form [ctx [_ bindings & forms] tail?]
  (let [blocks (into {} (map (fn [[label [_ params & code]]] [label {:params params :code (vec code)}]) bindings))
        [blocks forms] (join-blocks blocks (vec forms))
        ctx (assoc ctx :blocks (merge (:blocks ctx) blocks))]
    (case (count blocks)
      0 (do-form (body ctx forms tail?))
      1 (do-form (body (assoc ctx :loop (key (first blocks)) :in-loop false) forms tail?))
      (do
        (vreset! (:sized ctx) true)        ; blocks that call each other: loops
        (list 'letfn
            (vec (for [[label {:keys [params code]}] blocks]
                   (list (block-name label) (mapv symbol params) (do-form (body ctx code true)))))
            (do-form (body ctx forms tail?)))))))

(defn- expr
  "The Clojure form for a structured expression; tail? when its value is the
  method's."
  [ctx x tail?]
  (let [h (head x)]
    (cond
      (= "if" h) (list 'if (test-form ctx (nth x 1)) (expr ctx (nth x 2) tail?) (expr ctx (nth x 3) tail?))
      (= "let" h) (let-form ctx x tail?)
      (= "begin" h) (do-form (body ctx (rest x) tail?))
      (= "letrec" h) (letrec-form ctx x tail?)
      (= "jathrow-at" h) (list 'throw (value ctx (second x)))
      (= "jinit-ic" h) (left (str "the initialization of " (quoted (nth x 2))))
      (contains? (:blocks ctx) h) (jump ctx x)
      tail? (returned ctx (value ctx x))
      :else (value ctx x))))

;; --- a method ---------------------------------------------------------------------

(defn- method-parts
  "The pieces of a structured method form: its inline-cache cells, its
  parameters and its body."
  [form]
  (let [init (second (first (second form)))                 ; (let ([M init]) M)
        [ics lambda] (if (= "let" (head init)) [(into {} (second init)) (nth init 2)] [{} init])
        [_ params [_ _ & code]] lambda]                      ; (lambda ps (let ([npe-tag ..]) body))
    {:ics ics :params params :code code}))

(defn- param-form
  "A parameter as the defn names it, with the hint its type gives. The hint
  is part of the symbol's text, which is how it prints."
  [p t]
  (symbol (str (case t "[I" "^ints " "Ljava/lang/String;" "^String " "Ljava/util/List;" "^java.util.List " "") p)))

(defn- entry-bindings
  "A char parameter arrives as a char and a boolean as true or false; the
  body works on the code and on 1 or 0."
  [params types]
  (vec (mapcat (fn [p t]
                 (case t
                   "C" [(symbol p) (list 'int (symbol p))]
                   "Z" [(symbol p) (list 'if (symbol p) 1 0)]
                   nil))
               params types)))

(def preamble
  "What the functions of a port need defined before them. within-bmp! is
  the test a function makes of its string parameters when it holds only for
  strings within the BMP: it throws, so a string the port wasn't checked on
  can't give an answer Java wouldn't. non-null is Java's null check on an
  array that is read or written, and string-of is new String(char[], int,
  int) with Java's checks."
  (str "(defn within-bmp!\n"
       "  \"Throws when s has a character above U+FFFF. Java indexes a string by\n"
       "  UTF-16 code unit and jolt by character, and a function ported from\n"
       "  code that reads a string by index agrees with it only without those.\"\n"
       "  [^String s]\n"
       "  (when s\n"
       "    (let [n (.length s)]\n"
       "      (loop [i 0]\n"
       "        (when (< i n)\n"
       "          (when (> (int (.charAt s i)) 0xFFFF)\n"
       "            (throw (ex-info \"ported from Java code that reads strings by index: holds for characters up to U+FFFF\"\n"
       "                            {:champaca.port/outside :strings-within-the-bmp :index i})))\n"
       "          (recur (inc i)))))))\n"
       "\n"
       "(defn non-null\n"
       "  \"x, or Java's exception when it is null. An array read or written goes\n"
       "  through this: jolt's aget answers nil for a nil array.\"\n"
       "  [x]\n"
       "  (if (nil? x) (throw (NullPointerException.)) x))\n"
       "\n"
       "(defn string-of\n"
       "  \"The string of n chars of a from off, as Java's new String(char[], int,\n"
       "  int): jolt's own takes a nil array and a range outside it.\"\n"
       "  [a off n]\n"
       "  (let [a (non-null a)]\n"
       "    (when (or (neg? off) (neg? n) (> off (- (alength a) n)))\n"
       "      (throw (StringIndexOutOfBoundsException.\n"
       "              (str \"offset \" off \", count \" n \", length \" (alength a)))))\n"
       "    (String. a off n)))"))

(defn- guards
  "The tests a function makes on entry for the conditions it holds under:
  each String parameter within the BMP, when it reads strings by index."
  [holds params types]
  (when (holds :strings-within-the-bmp)
    (for [[p t] (map vector params types) :when (= "Ljava/lang/String;" t)]
      (list 'within-bmp! (symbol p)))))

(defn- kebab [s]
  (str/lower-case (str/replace s #"([a-z0-9])([A-Z])" "$1-$2")))

(defn- defn-form [fname desc {:keys [statics effects? field]} form]
  (let [{:keys [ics params code]} (method-parts form)
        {:keys [args ret]} (parse-desc desc)
        holds (volatile! (sorted-set))
        calls (volatile! (sorted-set))
        sized (volatile! false)
        fields (volatile! (sorted-map))
        ctx {:ics ics :blocks {} :ret ret :holds holds :calls calls :statics statics :sized sized
             :effects? effects? :field field :fields fields}
        code (do-form (body ctx code true))
        entry (entry-bindings params args)]
    {:form (apply list 'defn fname (mapv param-form params args)
                  (concat (guards @holds params args)
                          [(tidy (if (seq entry) (list 'let entry code) code))]))
     :holds @holds :calls @calls :sized @sized :fields @fields}))

(def ^:private type-words
  {"I" "int" "J" "long" "Z" "boolean" "C" "char" "S" "short" "B" "byte" "D" "double" "F" "float"})

(defn- type-word
  "A parameter type as a word for a function's name: string, int, chars."
  [t]
  (cond (str/starts-with? t "[") (str (type-word (subs t 1)) "s")
        (str/starts-with? t "L") (str/lower-case (subs t (inc (str/last-index-of t "/")) (dec (count t))))
        :else (type-words t)))

(defn- free-name
  "The function's symbol for a name: the name itself, or with a star after
  it when clojure.core or a special form already has it. A port named min
  or reverse would otherwise take that name from the code written here,
  which uses clojure.core's."
  [n]
  (let [sym (symbol n)]
    (if (or (special-symbol? sym) (ns-resolve 'clojure.core sym))
      (symbol (str n "*"))
      sym)))

(defn- statics
  "The static methods of cname that have code, by global name: {:name the
  function's symbol, :cname :mname :desc}. A method name used once is the
  function's name in kebab case; an overloaded one has its parameter types
  after it, since a Clojure function can't be overloaded by type. With
  prefix?, for a class other than the one being ported, the class's simple
  name comes first (array-utils-is-empty-chars)."
  [cname prefix?]
  (let [ms (filter #(and (cf/static? %) (:code %) (not= "<clinit>" (:name %))) (:methods (link/parsed cname)))
        overloaded (set (keep (fn [[n k]] (when (> k 1) n)) (frequencies (map :name ms))))
        simple (kebab (subs cname (inc (or (str/last-index-of cname "/") -1))))]
    (into {} (for [{:keys [name desc]} ms
                   :let [base (if (overloaded name)
                                (str/join "-" (cons (kebab name) (map type-word (:args (parse-desc desc)))))
                                (kebab name))]]
               [(str cname "." name desc)
                {:cname cname :mname name :desc desc
                 :name (if prefix? (symbol (str simple "-" base)) (free-name base))}]))))

(def ^:private calling-ops
  #{:invokestatic :invokevirtual :invokespecial :invokeinterface :invokedynamic :new :athrow})

(defn- initializer-calls?
  "Whether a class's static initializer calls a method, makes an object or
  throws. One that only fills its own fields with constants and arrays does
  nothing a caller can see, short of failing, and a class whose initializer
  fails can't be ported from at all (field-constant runs it)."
  [cls]
  (boolean (when-let [clinit (some #(when (= "<clinit>" (:name %)) %) (:methods cls))]
             (some #(calling-ops (:op %)) (bc/decode (:code (:code clinit)))))))

(def ^:private no-constant ::no-constant)

(defn- constant-form
  "The Clojure form for the value v of a static final field of type desc,
  or no-constant: a number, a string, null, or an empty array, which
  nothing can change. A boolean is 1 or 0 and a char its code, as
  everywhere in a port's body."
  [desc v]
  (cond
    (#{"I" "S" "B" "J"} desc) v
    (= "Z" desc) (if v 1 0)
    (= "C" desc) (int v)
    (nil? v) nil
    (= "Ljava/lang/String;" desc) (if (string? v) v no-constant)
    (and (str/starts-with? desc "[")
         (zero? (ldk/invoke-static "java.lang.reflect.Array" "getLength" v)))
    (let [elem (subs desc 1)]
      (cond (rules/array-makers elem) (list (rules/array-makers elem) 0)
            (element-classes elem) (list 'make-array (element-classes elem) 0)
            :else no-constant))
    :else no-constant))

(defn- field-constant
  "A static field read as a def: {:name :init :effects?}, or the method is
  left. The field must be final and declared by a class of the program, and
  its value, read here once the class has initialized, one constant-form
  can write. The def's name is the class's simple name and the field's, the
  field's in capitals so that no function's name can be the same."
  [parsed global]
  (let [[c f] (str/split global #":" 2)
        why (fn [w] (left (str "a read of the static field " global ": " w)))
        field (when-not (jdk-class? c) (some #(when (= f (:name %)) %) (:fields (parsed c))))]
    (cond
      (jdk-class? c) (why "a JDK class's field has no rule")
      (nil? field) (why "the class named doesn't declare it")
      (zero? (bit-and (:flags field) 0x0010)) (why "it isn't final")
      :else
      (let [v (try (ldk/get-static (str/replace c "/" ".") f)
                   (catch Throwable e (why (str "its class didn't initialize (" (.getName (class e)) ")"))))
            init (constant-form (:desc field) v)
            simple (kebab (subs c (inc (or (str/last-index-of c "/") -1))))]
        (when (= no-constant init) (why "its value isn't a number, a string, null or an empty array"))
        {:name (symbol (str simple "-" (str/upper-case (str/replace f "_" "-"))))
         :init init
         :effects? (initializer-calls? (parsed c))}))))

(defn- class-of-global
  "The class in a method's global name, a/b/C.name(desc)."
  [method]
  (let [paren (str/index-of method "(")]
    (when paren
      (when-let [dot (str/last-index-of method "." paren)]
        (subs method 0 dot)))))

(defn- program
  "What a port of cname looks methods up in: :statics, from a method's
  global name to its entry, for cname's own static methods and, loaded as
  they are met, those of the program's other classes; :initializer?,
  whether a class has a static initializer."
  [cname]
  (let [tables (atom {cname (statics cname false)})
        parsed (fn [c]
                 (try (loader/ensure-loaded! c) (link/parsed c)
                      (catch clojure.lang.ExceptionInfo _ nil)))
        table (fn [c]
                (or (@tables c)
                    (let [t (if (and (not (jdk-class? c)) (parsed c)) (statics c true) {})]
                      (swap! tables assoc c t)
                      t)))]
    {:statics (fn [method] (when-let [c (class-of-global method)] ((table c) method)))
     :effects? (memoize (fn [c] (initializer-calls? (parsed c))))
     :field (memoize (fn [global] (field-constant parsed global)))}))

;; --- writing it out ----------------------------------------------------------------

(def ^:private width 96)

(defn- layout
  "form as text, broken over lines where it doesn't fit: a form that fits
  stays on one line; otherwise its head and first argument stay and the rest
  go below, indented."
  [form indent]
  (let [flat (pr-str form)]
    (cond
      (<= (+ indent (count flat)) width) flat
      ;; a binding vector: a name and its value to a line
      (and (vector? form) (even? (count form)) (every? symbol? (take-nth 2 form)))
      (str "[" (str/join (str "\n" (apply str (repeat (inc indent) \space)))
                         (map (fn [[n v]] (str n " " (layout v (+ indent 2 (count (str n))))))
                              (partition 2 form)))
           "]")
      (or (not (seq? form)) (< (count form) 3)) flat
      :else
      (let [[h a & more] form
            pad (apply str (repeat (+ indent 2) \space))]
        (str "(" h " " (layout a (+ indent 2 (count (str h))))
             (apply str (map #(str "\n" pad (layout % (+ indent 2))) more))
             ")")))))

(def ^:private spec-by-type
  "A parameter type's spec. An array, a list or an iterator is null now and
  then, as a String is: Java throws on a null one, and the port must too."
  {"I" :int "J" :long "Z" :boolean "C" :char
   "S" {:type :int :range [-32768 32767]}
   "B" {:type :int :range [-128 127]}
   ;; an Object is tried with strings and null
   "Ljava/lang/Object;" {:type :string :nil? true}
   "[I" {:type :ints :nil? true}
   "[C" {:type :chars :nil? true}
   "Ljava/util/List;" {:type :strings :of {:nil? true} :nil? true}
   "Ljava/util/Collection;" {:type :strings :of {:nil? true} :nil? true}
   "[Ljava/lang/Object;" {:type :objects :nil? true}
   "[Ljava/lang/String;" {:type :string-array :nil? true}
   "Ljava/util/Iterator;" {:type :iterator :nil? true}})

(defn- arg-specs
  "champaca.portcheck's argument specs for a descriptor's parameters under
  the conditions a port holds for, or nil when a parameter has none. small?
  keeps an int or long within [-64, 64]: in a method that loops or
  allocates by size it may be the loop's count or the buffer's size, and
  the largest int would run the loop two thousand million times or ask for
  that many bytes. Any other method gets the full range, the limits and
  their neighbours included. Array elements always do."
  [desc holds small?]
  (let [string {:type :string :chars (if (holds :strings-within-the-bmp) :bmp :any) :nil? true}
        spec (fn [t]
               (cond (= "Ljava/lang/String;" t) string
                     (and small? (#{"I" "J"} t)) {:type (spec-by-type t) :range [-64 64]}
                     (and small? (#{"S" "B"} t)) {:type :int :range [-64 64]}
                     :else (spec-by-type t)))
        specs (map spec (:args (parse-desc desc)))]
    (when (every? some? specs) (vec specs))))

(defn- port-one
  "One static method, given the program's lookups: {:method :name :source
  :holds :calls :args}, or {:method :left reason}."
  [program method]
  (let [{:keys [cname mname desc name]} ((:statics program) method)]
    (try
      (when (re-find #"\[Z" desc)
        (left "a boolean[] in its signature: its elements aren't typed yet"))
      (let [[status shape] (structured (jit/method-text cname mname desc))]
        (when (= :left status) (left (str "the structuring pass left it (" shape ")")))
        (let [{:keys [form holds calls sized fields]} (defn-form name desc program shape)
              ;; in Java a call of the method runs its class's initializer first
              holds (cond-> holds ((:effects? program) cname) (conj :initializers-without-effects))]
          {:method method :name name :source (layout form 0) :holds holds :calls calls
           :desc desc :sized sized :fields fields}))
      (catch clojure.lang.ExceptionInfo e
        (if-let [why (::left (ex-data e))]
          {:method method :left why}
          (throw e))))))

(defn- reached-from
  "Every method the port of method calls, directly or through another."
  [by-method method]
  (loop [todo (seq (:calls (by-method method))) seen #{}]
    (if-let [m (first todo)]
      (if (seen m)
        (recur (rest todo) seen)
        (recur (concat (rest todo) (:calls (by-method m))) (conj seen m)))
      seen)))

(defn- settle
  "results with every port that calls a method that was left marked left
  too, until none is: a port stands only if everything it calls does. Then
  each port gets what follows from all it reaches: their conditions become
  its own, and its check keeps ints small if it or any of them loops or
  allocates by size (:small-ints)."
  [results]
  (let [by-method (into {} (map (juxt :method identity) results))
        broken (fn [r] (first (filter #(:left (by-method %)) (:calls r))))
        next-results (mapv (fn [r]
                             (if-let [m (and (not (:left r)) (broken r))]
                               (cond-> {:method (:method r) :left (str "calls " m ", which is left")}
                                 (:from r) (assoc :from (:from r)))
                               r))
                           results)]
    (if (= next-results results)
      (mapv (fn [r]
              (if (:left r)
                r
                (let [reached (reached-from by-method (:method r))
                      holds (into (:holds r) (mapcat #(:holds (by-method %)) reached))
                      small? (boolean (some #(:sized (by-method %)) (cons (:method r) reached)))]
                  (-> (dissoc r :desc :sized)
                      (assoc :holds holds
                             :small-ints small?
                             :args (arg-specs (:desc r) holds small?))))))
            results)
      (recur next-results))))

(defn- with-called
  "results and, after them, the port of each method of another class that
  they call, and of those those call, each marked :from its class."
  [program results]
  (loop [results results seen (set (map :method results))]
    (let [more (distinct (remove seen (mapcat :calls results)))]
      (if (empty? more)
        results
        (recur (into results (map #(assoc (port-one program %) :from (class-of-global %)) more))
               (into seen more))))))

(defn port-class
  "Each static method of cname that has code, in the class file's order, as
  a jolt function: {:method :name :source :holds :calls :args :small-ints},
  :args being champaca.portcheck's specs for it, :small-ints whether those
  keep int and long parameters within [-64, 64], and :calls the static
  methods it calls; or {:method :left reason} when something in it has no
  rule or it calls a method that was left. After them come the static
  methods of the program's other classes that those call, each with :from
  its class: a port needs the functions of all it calls."
  [cname]
  (let [cname (str/replace cname "." "/")
        _ (loader/ensure-loaded! cname)
        program (program cname)
        order (for [m (:methods (link/parsed cname))
                    :let [k (str cname "." (:name m) (:desc m))]
                    :when ((:statics program) k)]
                k)]
    (settle (with-called program (mapv #(port-one program %) order)))))

(defn port-method
  "port-class's answer for one method of cname."
  [cname mname desc]
  (let [cname (str/replace cname "." "/")
        method (str cname "." mname desc)]
    (first (filter #(= method (:method %)) (port-class cname)))))

(defn definitions
  "The defs the functions of results read, one for each static final field
  a port uses, as text: they come before the functions."
  [results]
  (for [[name init] (into (sorted-map) (mapcat :fields (remove :left results)))]
    (str "(def " name " " (pr-str init) ")")))

(defn- define!
  "Every port's function, defined in the namespace champaca.ported: the
  names declared first, since one may call another that comes later.
  Answers the vars by method."
  [ported]
  (binding [*ns* (create-ns 'champaca.ported)]
    (clojure.core/refer-clojure)
    (load-string preamble)
    (run! load-string (definitions ported))
    (load-string (str "(declare " (str/join " " (map :name ported)) ")"))
    (into {} (map (fn [p] [(:method p) (load-string (:source p))]) ported))))

(defn- check-one
  "A port's function checked against its method: the report, or nil when a
  parameter type has no input generator."
  [{:keys [method args name]} f runs]
  (when args
    (let [paren (str/index-of method "(")
          dot (str/last-index-of method "." paren)]
      (pc/check {:name (str name) :class (str/replace (subs method 0 dot) "/" ".")
                 :method (subs method (inc dot) paren) :desc (subs method paren)
                 :args args :f @f :runs runs}))))

(defn check-ports
  "results (port-class's) with each port checked against its method by
  champaca.portcheck on runs generated inputs: a :report is added to every
  port whose parameters have input generators. A port whose report doesn't
  agree must not be used."
  [results runs]
  (let [ported (remove :left results)
        fns (define! ported)]
    (mapv (fn [r]
            (if (:left r)
              r
              (if-let [report (check-one r (fns (:method r)) runs)] (assoc r :report report) r)))
          results)))
