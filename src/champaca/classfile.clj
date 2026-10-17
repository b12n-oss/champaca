(ns champaca.classfile
  "Parse a JVM .class file into plain data. Spike scope: the constant pool,
  fields, methods, the Code attribute and BootstrapMethods. Everything else
  is skipped by length."
  (:require [jolt.scheme :as s]))

(s/eval-string
 "(define (ldk-s64 . bs)
    (let loop ([bs bs] [acc 0])
      (if (null? bs)
          (if (>= acc #x8000000000000000) (- acc #x10000000000000000) acc)
          (loop (cdr bs) (+ (* acc 256) (car bs))))))
  (define (ldk-bits->f32 b)
    (let ([v (make-bytevector 4)])
      (bytevector-u32-set! v 0 b (endianness big))
      (bytevector-ieee-single-ref v 0 (endianness big))))
  (define (ldk-bits->f64 b)
    (let ([v (make-bytevector 8)])
      (bytevector-u64-set! v 0 (logand b #xffffffffffffffff) (endianness big))
      (bytevector-ieee-double-ref v 0 (endianness big))))
  ;; a jolt byte array is a record around a bytevector (natives.ss reads it
  ;; the same way)
  (define (ldk-bytevector-of a) (if (bytevector? a) a ((record-accessor (record-rtd a) 0) a)))
  ;; the n bytes at start as a string when they are all ASCII other than NUL,
  ;; which modified UTF-8 encodes as itself; #f otherwise
  (define (ldk-ascii-at bv start n)
    (let ([end (fx+ start n)])
      (let loop ([i start])
        (if (fx= i end)
            (let ([s (make-string n)])
              (do ([j 0 (fx+ j 1)]) ((fx= j n) s)
                (string-set! s j (integer->char (bytevector-u8-ref bv (fx+ start j))))))
            (let ([b (bytevector-u8-ref bv i)])
              (and (fx< 0 b 128) (loop (fx+ i 1))))))))")

;; resolved once: a call through s/call looks the name up every time, about
;; ten times the cost of the call itself
(def ^:private bytevector-of (s/proc "ldk-bytevector-of"))
(def ^:private ascii-at (s/proc "ldk-ascii-at"))

(defn- reader
  "A cursor over a byte array, or over a vector of unsigned bytes (an
  attribute's body, read again in place)."
  [bs]
  (if (vector? bs)
    {:at (fn [i] (nth bs i)) :pos (volatile! 0)}
    {:at (fn [i] (bit-and (aget ^bytes bs i) 0xff)) :pos (volatile! 0) :bv (bytevector-of bs)}))

(declare decode-mutf8 take-bytes)

(defn- utf8-at
  "The constant-pool string of n bytes at the cursor: in Scheme when it is
  plain ASCII, the common case and many times faster than decoding here."
  [r n]
  (let [p (:pos r) start @p]
    (or (when-let [bv (:bv r)]
          (when-let [s (ascii-at bv start n)]
            (vreset! p (+ start n))
            s))
        (decode-mutf8 (take-bytes r n)))))

(defn- u1 [r] (let [p (:pos r) i @p] (vreset! p (inc i)) ((:at r) i)))
(defn- u2 [r] (let [p (:pos r) i @p at (:at r)] (vreset! p (+ i 2)) (+ (* (at i) 256) (at (inc i)))))
(defn- u4 [r] (let [a (u2 r) b (u2 r)] (+ (* a 65536) b)))
(defn- s4 [r] (let [v (u4 r)] (if (>= v 2147483648) (- v 4294967296) v)))
(defn- take-bytes [r n]
  (let [p (:pos r) start @p end (+ start n) at (:at r)]
    (vreset! p end)
    (loop [i start acc (transient [])]
      (if (< i end) (recur (inc i) (conj! acc (at i))) (persistent! acc)))))

(defn- units->string
  "UTF-16 code units to a string, a surrogate pair becoming one character;
  nil when a surrogate stands alone, which a Chez string can't hold."
  [units]
  (let [n (count units)]
    (loop [i 0 out (StringBuilder.)]
      (if (>= i n)
        (str out)
        (let [u (nth units i)
              lo (when (< (inc i) n) (nth units (inc i)))]
          (cond
            (or (< u 0xd800) (> u 0xdfff)) (recur (inc i) (.append out (char u)))
            (and (< u 0xdc00) lo (<= 0xdc00 lo 0xdfff))
            (recur (+ i 2) (.append out (char (+ 0x10000 (* (- u 0xd800) 1024) (- lo 0xdc00)))))
            :else nil))))))

(defn- decode-mutf8
  "Modified UTF-8 to a string. Supplementary characters arrive as surrogate
  pairs and become one character. A constant with a lone surrogate comes
  back as a vector of its UTF-16 code units; codegen/str-lit writes that as
  a Scheme vector and the runtime's jstring takes either."
  [bs]
  (let [n (count bs)
        units (loop [i 0 acc (transient [])]
                (if (>= i n)
                  (persistent! acc)
                  (let [b (nth bs i)]
                    (cond
                      (< b 0x80) (recur (inc i) (conj! acc b))
                      (< b 0xe0) (recur (+ i 2) (conj! acc (+ (* (bit-and b 0x1f) 64)
                                                              (bit-and (nth bs (inc i)) 0x3f))))
                      :else (recur (+ i 3) (conj! acc (+ (* (bit-and b 0x0f) 4096)
                                                         (* (bit-and (nth bs (inc i)) 0x3f) 64)
                                                         (bit-and (nth bs (+ i 2)) 0x3f))))))))]
    (or (units->string units) units)))

(defn- read-cp-entry [r]
  (let [tag (u1 r)]
    (case (int tag)
      1 {:tag :utf8 :value (utf8-at r (u2 r))}
      3 {:tag :int :value (s4 r)}
      4 {:tag :float :value (s/call "ldk-bits->f32" (u4 r))}
      5 {:tag :long :value (apply s/call "ldk-s64" (take-bytes r 8))}
      6 {:tag :double :value (s/call "ldk-bits->f64" (apply s/call "ldk-s64" (take-bytes r 8)))}
      7 {:tag :class :name-idx (u2 r)}
      8 {:tag :string :idx (u2 r)}
      9 {:tag :fieldref :class-idx (u2 r) :nat-idx (u2 r)}
      10 {:tag :methodref :class-idx (u2 r) :nat-idx (u2 r)}
      11 {:tag :imethodref :class-idx (u2 r) :nat-idx (u2 r)}
      12 {:tag :nat :name-idx (u2 r) :desc-idx (u2 r)}
      15 {:tag :methodhandle :kind (u1 r) :ref-idx (u2 r)}
      16 {:tag :methodtype :desc-idx (u2 r)}
      17 {:tag :dynamic :bsm-idx (u2 r) :nat-idx (u2 r)}
      18 {:tag :indy :bsm-idx (u2 r) :nat-idx (u2 r)}
      19 {:tag :module :name-idx (u2 r)}
      20 {:tag :package :name-idx (u2 r)}
      (throw (ex-info "bad constant pool tag" {:tag tag})))))

(defn- read-constant-pool [r]
  (let [n (u2 r)]
    (loop [i 1 cp {}]
      (if (>= i n)
        cp
        (let [e (read-cp-entry r)]
          (recur (if (#{:long :double} (:tag e)) (+ i 2) (inc i)) (assoc cp i e)))))))

(defn- resolve-cp
  "Replace index fields with the strings and maps they point at, so later
  passes never chase indices."
  [cp]
  (let [utf (fn [i] (:value (cp i)))
        cls (fn [i] (utf (:name-idx (cp i))))
        nat (fn [i] (let [e (cp i)] {:name (utf (:name-idx e)) :desc (utf (:desc-idx e))}))]
    (into {}
          (map (fn [[i e]]
                 [i (case (:tag e)
                      :class {:tag :class :name (cls i)}
                      :string {:tag :string :value (utf (:idx e))}
                      (:fieldref :methodref :imethodref)
                      (merge {:tag (:tag e) :class (cls (:class-idx e))} (nat (:nat-idx e)))
                      :nat (assoc (nat i) :tag :nat)
                      :methodtype {:tag :methodtype :desc (utf (:desc-idx e))}
                      (:indy :dynamic) (merge {:tag (:tag e) :bsm-idx (:bsm-idx e)} (nat (:nat-idx e)))
                      e)]))
          cp)))

(defn- read-attributes [r cp]
  (vec (repeatedly (u2 r)
                   (fn [] {:name (:value (cp (u2 r)))
                           :info (take-bytes r (u4 r))}))))

(defn- parse-code [info cp]
  (let [r (reader info)
        max-stack (u2 r)
        max-locals (u2 r)
        code (take-bytes r (u4 r))
        handlers (vec (repeatedly (u2 r)
                                  (fn [] {:start (u2 r) :end (u2 r) :handler (u2 r)
                                          :type (let [i (u2 r)] (when (pos? i) (:name (cp i))))})))]
    {:max-stack max-stack :max-locals max-locals :code code :handlers handlers}))

(defn- parse-bootstrap-methods [info]
  (let [r (reader info)]
    (vec (repeatedly (u2 r)
                     (fn [] {:ref-idx (u2 r) :args (vec (repeatedly (u2 r) #(u2 r)))})))))

(defn- attr-bytes
  "The raw bytes of the attribute named n, for the JDK's own parsers
  (AnnotationParser, the generics parser) to read."
  [attrs n]
  (some #(when (= n (:name %)) (vec (:info %))) attrs))

(defn- signature [attrs cp]
  (some #(when (= "Signature" (:name %))
           (let [[a b] (:info %)] (:value (cp (+ (* a 256) b)))))
        attrs))

(defn- reflection-attrs
  "Signature and annotation attributes, as reflection hands them to Field,
  Method, Constructor and Class."
  [attrs cp]
  (let [sig (signature attrs cp)
        ann (attr-bytes attrs "RuntimeVisibleAnnotations")
        pann (attr-bytes attrs "RuntimeVisibleParameterAnnotations")
        adef (attr-bytes attrs "AnnotationDefault")]
    (cond-> {}
      sig (assoc :signature sig)
      ann (assoc :annotations ann)
      pann (assoc :param-annotations pann)
      adef (assoc :annotation-default adef))))

(defn- u2s [info] (map (fn [[a b]] (+ (* a 256) b)) (partition 2 info)))

(defn- nesting
  "This class's own InnerClasses entry, {:outer :simple-name :flags} (outer and
  simple name nil for local and anonymous classes), and its EnclosingMethod,
  {:class :name :desc} (name and desc nil outside a method)."
  [attrs cp this]
  (let [inner (some #(when (= "InnerClasses" (:name %))
                       (some (fn [[ci oi ni fl]]
                               (when (= this (:name (cp ci)))
                                 {:outer (when (pos? oi) (:name (cp oi)))
                                  :simple-name (when (pos? ni) (:value (cp ni)))
                                  :flags fl}))
                             (partition 4 (rest (u2s (:info %))))))
                    attrs)
        encl (some #(when (= "EnclosingMethod" (:name %))
                      (let [[ci mi] (u2s (:info %)) nat (when (pos? mi) (cp mi))]
                        {:class (:name (cp ci)) :name (:name nat) :desc (:desc nat)}))
                   attrs)]
    (cond-> {} inner (assoc :inner inner) encl (assoc :enclosing-method encl))))

(defn- read-member [r cp]
  (let [flags (u2 r)
        name (:value (cp (u2 r)))
        desc (:value (cp (u2 r)))
        attrs (read-attributes r cp)
        code (some #(when (= "Code" (:name %)) (parse-code (:info %) cp)) attrs)
        const (some #(when (= "ConstantValue" (:name %))
                       (let [[a b] (:info %)] (cp (+ (* a 256) b))))
                    attrs)]
    (cond-> (merge {:flags flags :name name :desc desc} (reflection-attrs attrs cp))
      code (assoc :code code)
      const (assoc :constant const))))

(def ACC-STATIC 0x0008)
(def ACC-INTERFACE 0x0200)
(def ACC-ABSTRACT 0x0400)
(def ACC-NATIVE 0x0100)

(defn static? [m] (pos? (bit-and (:flags m) ACC-STATIC)))
(defn native? [m] (pos? (bit-and (:flags m) ACC-NATIVE)))
(defn interface? [c] (pos? (bit-and (:flags c) ACC-INTERFACE)))

(defn parse
  "Parse the bytes of a class file into a map of :name :super :interfaces
  :fields :methods :cp :bootstrap-methods."
  [^bytes bs]
  (let [r (reader bs)
        magic (u4 r)
        _ (when-not (= magic 0xCAFEBABE) (throw (ex-info "not a class file" {:magic magic})))
        _minor (u2 r)
        _major (u2 r)
        cp (resolve-cp (read-constant-pool r))
        flags (u2 r)
        this (:name (cp (u2 r)))
        super (let [i (u2 r)] (when (pos? i) (:name (cp i))))
        interfaces (vec (repeatedly (u2 r) #(:name (cp (u2 r)))))
        fields (vec (repeatedly (u2 r) #(read-member r cp)))
        methods (vec (repeatedly (u2 r) #(read-member r cp)))
        attrs (read-attributes r cp)]
    {:name this :flags flags :super super :interfaces interfaces
     :fields fields :methods methods :cp cp
     :reflection (merge (reflection-attrs attrs cp) (nesting attrs cp this))
     :bootstrap-methods (some #(when (= "BootstrapMethods" (:name %))
                                 (parse-bootstrap-methods (:info %)))
                              attrs)}))

(defn parse-file [path]
  (parse (java.nio.file.Files/readAllBytes
          (java.nio.file.Paths/get path (into-array String [])))))
