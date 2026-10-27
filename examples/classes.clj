;; Real Java classes defined from jolt: a superclass, interfaces, fields and
;; methods that are jolt fns, statics too, seen by Java code, reflection and
;; Class.forName as any other class of the application loader.
;;   bb example classes
(ns examples.classes
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(defn- thrown
  "The ex-message of what f throws."
  [f]
  (try (f) nil (catch :default e (ex-message e))))

(defn- class-named [n] (ldk/invoke-static "java.lang.Class" "forName" n))

(defn- member-strings
  "toString of each of the array's members, sorted."
  [arr]
  (sort (map ldk/to-string (ldk/->clj arr))))

(deftest a-real-class
  (let [c (ldk/define-class "demo.classes.Greeter"
            {:implements ["java.util.function.Function"]
             :fields {"greeting" "java.lang.String" "count" "int"}
             :methods {:apply (fn [this who]
                                (ldk/set-field! this "count" (inc (ldk/get-field this "count")))
                                (str (ldk/get-field this "greeting") ", " who " (" (ldk/get-field this "count") ")"))}})
        g (ldk/construct "demo.classes.Greeter")]
    (ldk/set-field! g "greeting" "hi")
    (is (= "hi, bob (1)" (ldk/invoke g "apply" "bob")))
    ;; Java calls it through the interface
    (is (= ["hi, a (2)" "hi, b (3)"]
           (ldk/->clj (ldk/invoke (ldk/invoke (ldk/invoke (ldk/->java ["a" "b"]) "stream") "map" g) "toList"))))
    (is (= "class demo.classes.Greeter" (ldk/to-string c)))
    (is (= "class demo.classes.Greeter" (ldk/to-string (class-named "demo.classes.Greeter"))))
    (is (= "jdk.internal.loader.ClassLoaders$AppClassLoader"
           (ldk/invoke (ldk/invoke (ldk/invoke c "getClassLoader") "getClass") "getName")))
    (is (= "public final" (ldk/invoke-static "java.lang.reflect.Modifier" "toString" (ldk/invoke c "getModifiers"))))
    (is (= ["public native java.lang.Object demo.classes.Greeter.apply(java.lang.Object)"]
           (member-strings (ldk/invoke c "getDeclaredMethods"))))
    (is (= ["public int demo.classes.Greeter.count" "public java.lang.String demo.classes.Greeter.greeting"]
           (member-strings (ldk/invoke c "getFields"))))
    ;; made by reflection, as a framework would
    (let [h (ldk/invoke (ldk/invoke c "getConstructor" []) "newInstance" [])]
      (ldk/set-field! h "greeting" "yo")
      (is (= "yo, x (1)" (ldk/invoke h "apply" "x"))))))

(deftest extending-a-class
  (ldk/define-class "demo.classes.Tens"
    {:extends "java.util.AbstractList" :implements ["java.util.RandomAccess"]
     :methods {:get (fn [_ i] (* 10 i)) :size (fn [_] 3)}})
  (let [l (ldk/construct "demo.classes.Tens")]
    ;; AbstractList's own methods run on the jolt ones
    (is (= "[0, 10, 20]" (ldk/to-string l)))
    (is (true? (ldk/invoke l "contains" 20)))
    (is (= [0 10 20] (ldk/->clj (ldk/construct "java.util.ArrayList" l)))))
  ;; a constructor for each of the superclass's, arguments passed on
  (ldk/define-class "demo.classes.Worker"
    {:extends "java.lang.Thread" :fields ["seen"]
     :methods {:run (fn [this] (ldk/set-field! this "seen" (ldk/invoke this "getName")))}})
  (let [w (ldk/construct "demo.classes.Worker" "w1")]
    (ldk/invoke w "start")
    (ldk/invoke w "join")
    (is (= "w1" (ldk/get-field w "seen"))))
  ;; a long among them, and protected hooks Java calls
  (let [seen (atom [])]
    (ldk/define-class "demo.classes.Pool"
      {:extends "java.util.concurrent.ThreadPoolExecutor"
       :methods {:beforeExecute (fn [this th r] (swap! seen conj :before) (ldk/invoke-super this "beforeExecute" th r))
                 :afterExecute (fn [_ r e] (swap! seen conj :after))}})
    (let [pool (ldk/construct "demo.classes.Pool" 1 1 10 (ldk/get-static "java.util.concurrent.TimeUnit" "SECONDS")
                              (ldk/construct "java.util.concurrent.LinkedBlockingQueue"))
          task (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" (fn [] 41))]
      (is (= 41 (ldk/invoke (ldk/invoke pool "submit" task) "get")))
      (ldk/invoke pool "shutdown")
      (is (true? (ldk/invoke pool "awaitTermination" 5 (ldk/get-static "java.util.concurrent.TimeUnit" "SECONDS"))))
      (is (= [:before :after] @seen))))
  ;; a varargs constructor stays varargs
  (ldk/define-class "demo.classes.Provider" {:extends "java.util.spi.AbstractResourceBundleProvider"})
  (is (true? (ldk/invoke (ldk/invoke (ldk/construct "demo.classes.Provider" "java.class" "java.properties") "getClass")
                         "isInstance" (ldk/construct "demo.classes.Provider"))))
  (is (= [false true] (sort (map #(ldk/invoke % "isVarArgs")
                                 (ldk/->clj (ldk/invoke (ldk/invoke-static "java.lang.Class" "forName" "demo.classes.Provider")
                                                        "getConstructors"))))))
  ;; a method only a superinterface declares: forEach, from Iterable, which
  ;; AbstractList's List extends; apply, from UnaryOperator's Function
  (let [seen (atom [])]
    (ldk/define-class "demo.classes.Each"
      {:extends "java.util.AbstractList" :implements ["java.util.function.UnaryOperator"]
       :methods {:size (fn [_] 2) :get (fn [_ i] i) :forEach (fn [this f] (swap! seen conj :each))
                 :apply (fn [_ x] (str x "?"))}})
    (let [e (ldk/construct "demo.classes.Each")]
      (ldk/invoke e "forEach" (fn [x] nil))
      (is (= [:each] @seen))
      (is (= "a?" (ldk/invoke e "apply" "a"))))))

(deftest calling-the-superclass
  (ldk/define-class "demo.classes.Marked"
    {:extends "java.util.ArrayList"
     :methods {:add (fn [this x] (ldk/invoke-super this "add" (str "<" x ">")))
               ;; a full JVM name wins over its name's fn
               "add(ILjava/lang/Object;)V" (fn [this i x] (ldk/invoke-super this "add" i (str "[" x "]")))
               :toString (fn [this] (str "Marked" (ldk/invoke-super this "toString")))
               ;; protected, called by subList's clear
               :removeRange (fn [this a b] (ldk/invoke-super this "removeRange" a (dec b)))}})
  (let [l (ldk/construct "demo.classes.Marked")]
    (ldk/invoke l "add" 1)
    (ldk/invoke l "add" 0 2)
    (ldk/invoke l "add" 3)
    (is (= "Marked[[2], <1>, <3>]" (ldk/to-string l)))
    (ldk/invoke (ldk/invoke l "subList" 0 2) "clear")
    (is (= "Marked[<1>, <3>]" (ldk/to-string l))))
  ;; a default method of the class's own interface
  (ldk/define-class "demo.classes.Cmp"
    {:implements ["java.util.Comparator"]
     :methods {:compare (fn [_ a b] (compare a b)) :reversed (fn [this] (ldk/invoke-super this "reversed"))}})
  (is (= 1 (ldk/invoke (ldk/invoke (ldk/construct "demo.classes.Cmp") "reversed") "compare" 1 2)))
  ;; an abstract one has no body to call
  (ldk/define-class "demo.classes.NoBody"
    {:extends "java.util.AbstractList" :methods {:size (fn [_] 1) :get (fn [this i] (ldk/invoke-super this "get" i))}})
  (is (= "java.lang.AbstractMethodError: Method java/util/AbstractList.get(I)Ljava/lang/Object; is abstract"
         (thrown #(ldk/invoke (ldk/construct "demo.classes.NoBody") "get" 0))))
  (is (= "invoke-super: java.lang.Object isn't a class define-class made"
         (thrown #(ldk/invoke-super (ldk/construct "java.lang.Object") "toString")))))

(deftest results-checked-against-the-return-type
  (ldk/define-class "demo.classes.Prims"
    {:implements ["java.util.function.IntPredicate" "java.util.function.LongUnaryOperator"
                  "java.util.function.DoubleSupplier" "java.lang.CharSequence"]
     :methods {:test (fn [_ i] (when (odd? i) :yes)) :applyAsLong (fn [_ n] (* n 2)) :getAsDouble (fn [_] 3)
               :length (fn [_] 2) :charAt (fn [_ i] (if (zero? i) \a 98)) :subSequence (fn [_ a b] "b")
               :toString (fn [_] "ab")}})
  (let [p (ldk/construct "demo.classes.Prims")]
    (is (= [true false] [(ldk/invoke p "test" 3) (ldk/invoke p "test" 2)]))
    (is (= 10000000000 (ldk/invoke p "applyAsLong" 5000000000)))
    (is (= 3.0 (ldk/invoke p "getAsDouble")))
    (is (= [\a \b] [(ldk/invoke p "charAt" 0) (ldk/invoke p "charAt" 1)]))
    (is (= "ab" (ldk/to-string (ldk/construct "java.lang.StringBuilder" p)))))
  (ldk/define-class "demo.classes.Results"
    {:implements ["java.util.function.IntSupplier" "java.util.concurrent.Callable"]
     :fields ["v"]
     :methods {:getAsInt (fn [this] (ldk/get-field this "v")) :call (fn [this] (ldk/get-field this "v"))
               "list()Ljava/util/List;" (fn [this] [1 2])
               "ints()[I" (fn [this] [3 4])
               "map()Ljava/util/Map;" (fn [this] {:a 1})
               "wrong()Ljava/util/List;" (fn [this] "x")
               "badInts()[I" (fn [this] [1 "a"])
               "nilInts()[I" (fn [this] [nil])
               "strs()[Ljava/lang/String;" (fn [this] [1 2])
               "grid()[[J" (fn [this] [[1 2] [3]])
               "boxed()Ljava/lang/Double;" (fn [this] 3)
               "small()Ljava/lang/Short;" (fn [this] 70000)
               "notSam()Ljava/lang/Object;" (fn [this] (fn [] 1))
               "sam()Ljava/util/function/IntSupplier;" (fn [this] (fn [] 9))}})
  (let [r (ldk/construct "demo.classes.Results")
        int-of #(do (ldk/set-field! r "v" %) (thrown (fn [] (ldk/invoke r "getAsInt"))))]
    (is (= "java.lang.NullPointerException: demo.classes.Results.getAsInt()I returned nil, not int" (int-of nil)))
    (is (= "java.lang.ClassCastException: demo.classes.Results.getAsInt()I returned a string, not int" (int-of "7")))
    (is (= (str "java.lang.IllegalArgumentException: demo.classes.Results.getAsInt()I returned 5000000000,"
                " out of range for int")
           (int-of 5000000000)))
    (ldk/set-field! r "v" 7)
    (is (= 7 (ldk/invoke r "getAsInt")))
    ;; jolt data goes over as invoke's arguments do
    (is (= "java.util.ArrayList" (ldk/invoke (ldk/invoke (ldk/invoke r "list") "getClass") "getName")))
    (is (= [1 2] (ldk/->clj (ldk/invoke r "list"))))
    (is (= [3 4] (ldk/->clj (ldk/invoke r "ints"))))
    (is (= {"a" 1} (ldk/->clj (ldk/invoke r "map"))))
    (is (= (str "java.lang.ClassCastException: demo.classes.Results.wrong()Ljava/util/List; returned a"
                " java.lang.String, not java.util.List")
           (thrown #(ldk/invoke r "wrong"))))
    ;; an array's elements are checked as results of the component type
    (is (= "java.lang.ClassCastException: demo.classes.Results.badInts()[I returned a string, not int"
           (thrown #(ldk/invoke r "badInts"))))
    (is (= "java.lang.NullPointerException: demo.classes.Results.nilInts()[I returned nil, not int"
           (thrown #(ldk/invoke r "nilInts"))))
    (is (= (str "java.lang.ClassCastException: demo.classes.Results.strs()[Ljava/lang/String; returned a"
                " java.lang.Integer, not java.lang.String")
           (thrown #(ldk/invoke r "strs"))))
    (is (= [[1 2] [3]] (ldk/->clj (ldk/invoke r "grid"))))
    ;; a box takes a number as its primitive does
    (is (= 3.0 (ldk/invoke r "boxed")))
    (is (= (str "java.lang.IllegalArgumentException: demo.classes.Results.small()Ljava/lang/Short; returned 70000,"
                " out of range for short")
           (thrown #(ldk/invoke r "small"))))
    ;; a fn only where an interface with one method is wanted
    (is (= "java.lang.ClassCastException: demo.classes.Results.notSam()Ljava/lang/Object; returned a fn, not java.lang.Object"
           (thrown #(ldk/invoke r "notSam"))))
    (is (= 9 (ldk/invoke (ldk/invoke r "sam") "getAsInt")))))

(defn- answer [_] 42)

(deftest what-a-class-may-hold
  ;; a var as a method's fn, an array field, a name with NUL in it (modified
  ;; UTF-8 writes NUL as two octets)
  (ldk/define-class "demo.classes.Holds"
    {:implements ["java.util.function.IntSupplier"]
     :fields {"xs" "long[]" "a\u0000b" "int"}
     :methods {:getAsInt #'answer}})
  (let [h (ldk/construct "demo.classes.Holds")]
    (is (= 42 (ldk/invoke h "getAsInt")))
    (ldk/set-field! h "xs" [1 5000000000])
    (is (= [1 5000000000] (ldk/->clj (ldk/get-field h "xs"))))
    (is (= ["[J" "a\u0000b"]
           (sort (map #(if (= "xs" (ldk/invoke % "getName")) (ldk/invoke (ldk/invoke % "getType") "getName") (ldk/invoke % "getName"))
                      (ldk/->clj (ldk/invoke (ldk/invoke h "getClass") "getFields"))))))))

(deftest methods-of-its-own
  (ldk/define-class "demo.classes.Own" {:methods {"twice(I)I" (fn [_ n] (* 2 n))}})
  (let [o (ldk/construct "demo.classes.Own")
        m (ldk/invoke (ldk/invoke o "getClass") "getMethod" "twice" [(ldk/get-static "java.lang.Integer" "TYPE")])]
    (is (= 8 (ldk/invoke o "twice" 4)))
    (is (= 10 (ldk/invoke m "invoke" o [5])))))

(deftest what-define-class-refuses
  (is (= "define-class: java.lang.String is final" (thrown #(ldk/define-class "demo.classes.E1" {:extends "java.lang.String"}))))
  (is (= "define-class: java.lang.Runnable is an interface; name it in :implements"
         (thrown #(ldk/define-class "demo.classes.E2" {:extends "java.lang.Runnable"}))))
  (is (= "define-class: java.lang.Object is not an interface"
         (thrown #(ldk/define-class "demo.classes.E3" {:implements ["java.lang.Object"]}))))
  (is (= "define-class: no method nope to implement in java.lang.Object, java.lang.Runnable"
         (thrown #(ldk/define-class "demo.classes.E4" {:implements ["java.lang.Runnable"] :methods {:nope (fn [_])}}))))
  (is (= "define-class: getClass()Ljava/lang/Class; is final in java.lang.Object"
         (thrown #(ldk/define-class "demo.classes.E5" {:methods {"getClass()Ljava/lang/Class;" (fn [_])}}))))
  (is (= "define-class: getClass is final in java.lang.Object"
         (thrown #(ldk/define-class "demo.classes.E5" {:methods {:getClass (fn [_])}}))))
  (is (= "define-class: \"bad(Q)V\" is neither a method name nor name(descriptor)return"
         (thrown #(ldk/define-class "demo.classes.E6" {:methods {"bad(Q)V" (fn [_])}}))))
  (is (= "define-class: :toString is not a fn" (thrown #(ldk/define-class "demo.classes.E7" {:methods {:toString 42}}))))
  (is (= "define-class: java.util.Mine is in a package of the JDK's" (thrown #(ldk/define-class "java.util.Mine" {}))))
  (is (= "define-class: java.lang.Math is final" (thrown #(ldk/define-class "demo.classes.E8" {:extends "java.lang.Math"}))))
  (is (= "java.lang.NoClassDefFoundError: no/Such" (thrown #(ldk/define-class "demo.classes.E9" {:extends "no.Such"}))))
  (is (= "define-class: java.util.ArrayList is already a class" (thrown #(ldk/define-class "java.util.ArrayList" {}))))
  (is (= "define-class: demo..E10 isn't a class name" (thrown #(ldk/define-class "demo..E10" {}))))
  (is (= "define-class: field a is named twice" (thrown #(ldk/define-class "demo.classes.E11" {:fields ["a" "a"]}))))
  (is (= "define-class: \"a.b\" isn't a field name" (thrown #(ldk/define-class "demo.classes.E11" {:fields ["a.b"]}))))
  (is (= "define-class: field n's type Integer isn't a class (a nested class is Outer$Inner)"
         (thrown #(ldk/define-class "demo.classes.E11" {:fields {"n" "Integer"}}))))
  (is (= "define-class: java.lang.Runnable is in :implements twice"
         (thrown #(ldk/define-class "demo.classes.E11" {:implements ["java.lang.Runnable" "java.lang.Runnable"]}))))
  (is (= "define-class: method \"toString\" is named twice"
         (thrown #(ldk/define-class "demo.classes.E11" {:methods {:toString str "toString" str}}))))
  ;; none of those took the name
  (is (= 1 (count (ldk/->clj (ldk/invoke (ldk/define-class "demo.classes.E11" {:fields {"e" "java.util.Map$Entry"} :extends nil})
                                         "getFields"))))))

(deftest names-beyond-the-bmp
  (let [n (str "demo.classes.G" (char 128512))]
    (ldk/define-class n {:fields [(str "f" (char 128512))] :methods {:toString (fn [_] "ok")}})
    (is (= "ok" (ldk/to-string (ldk/construct n))))
    (is (= n (ldk/invoke (ldk/invoke-static "java.lang.Class" "forName" n) "getName")))))

(deftest static-members
  (let [cname "demo.classes.Tally"
        spec (fn [step]
               {:static-fields {"total" "int" "label" "java.lang.String"}
                :static-methods {"add(I)I" (fn [n]
                                             (ldk/set-static! cname "total" (+ (ldk/get-static cname "total") (* step n)))
                                             (ldk/get-static cname "total"))
                                 "fresh()Ldemo/classes/Tally;" (fn [] (ldk/construct cname))
                                 "wrong()I" (fn [] "seven")
                                 "boom()V" (fn [] (throw (ex-info "static boom" {})))}})
        c (ldk/define-class cname (spec 1))
        int-type (ldk/get-static "java.lang.Integer" "TYPE")]
    ;; statics start at Java's defaults
    (is (= 0 (ldk/get-static cname "total")))
    (is (nil? (ldk/get-static cname "label")))
    (is (= 5 (ldk/invoke-static cname "add" 5)))
    (is (= "demo.classes.Tally" (ldk/invoke (ldk/invoke (ldk/invoke-static cname "fresh") "getClass") "getName")))
    ;; Java sees and calls them: reflection and a method handle
    (let [m (ldk/invoke c "getMethod" "add" [int-type])]
      (is (= "public static native" (ldk/invoke-static "java.lang.reflect.Modifier" "toString" (ldk/invoke m "getModifiers"))))
      (is (= 7 (ldk/invoke m "invoke" nil [2]))))
    (is (= "public static" (ldk/invoke-static "java.lang.reflect.Modifier" "toString"
                                              (ldk/invoke (ldk/invoke c "getField" "label") "getModifiers"))))
    (let [mh (ldk/invoke (ldk/invoke-static "java.lang.invoke.MethodHandles" "publicLookup") "findStatic" c "add"
                         (ldk/invoke-static "java.lang.invoke.MethodType" "methodType" int-type int-type))]
      (is (= 10 (ldk/invoke mh "invokeWithArguments" [3]))))
    ;; results are checked, errors come back as themselves
    (is (= "java.lang.ClassCastException: demo.classes.Tally.wrong()I returned a string, not int"
           (thrown #(ldk/invoke-static cname "wrong"))))
    (is (= "static boom" (thrown #(ldk/invoke-static cname "boom"))))
    (is (= "static boom" (ex-message (ex-cause (try (ldk/invoke (ldk/invoke c "getMethod" "boom" []) "invoke" nil [])
                                                    (catch :default e e))))))
    ;; the same shape again swaps the static fns and keeps the values
    (is (identical? c (ldk/define-class cname (spec 100))))
    (is (= 110 (ldk/invoke-static cname "add" 1)))
    (is (= (str "define-class: demo.classes.Tally is already defined with another superclass, interfaces,"
                " fields or method keys; a class is defined once per process")
           (thrown #(ldk/define-class cname (assoc (spec 1) :static-fields ["other"])))))))

(deftest a-field-of-its-own-type
  (ldk/define-class "demo.classes.Link"
    {:fields {"next" "demo.classes.Link"}
     :static-fields {"FIRST" "demo.classes.Link" "ALL" "demo.classes.Link[]"}
     :methods {"self()Ldemo/classes/Link;" (fn [this] this)}})
  (let [a (ldk/construct "demo.classes.Link") b (ldk/construct "demo.classes.Link")]
    (ldk/set-field! a "next" b)
    (ldk/set-static! "demo.classes.Link" "FIRST" a)
    (ldk/set-static! "demo.classes.Link" "ALL" [a b])
    (is (identical? b (ldk/get-field (ldk/get-static "demo.classes.Link" "FIRST") "next")))
    (is (identical? a (ldk/invoke a "self")))
    (is (= 2 (count (ldk/->clj (ldk/get-static "demo.classes.Link" "ALL")))))
    (is (= "field FIRST in demo/classes/Link has type demo.classes.Link, which the value doesn't fit"
           (thrown #(ldk/set-static! "demo.classes.Link" "FIRST" "a string"))))))

(deftest what-statics-refuse
  (is (= "define-class: static method \"add\" needs its full JVM name, name(descriptor)return"
         (thrown #(ldk/define-class "demo.classes.S1" {:static-methods {"add" (fn [n] n)}}))))
  ;; Java can't have a static and an instance method of one signature
  (is (= "define-class: static hashCode()I has an instance method's name and descriptor"
         (thrown #(ldk/define-class "demo.classes.S2" {:static-methods {"hashCode()I" (fn [] 1)}}))))
  (is (= "define-class: static f(I)I has an instance method's name and descriptor"
         (thrown #(ldk/define-class "demo.classes.S3" {:methods {"f(I)I" (fn [_ n] n)} :static-methods {"f(I)I" (fn [n] n)}}))))
  (is (= "define-class: field x is named twice"
         (thrown #(ldk/define-class "demo.classes.S4" {:fields ["x"] :static-fields ["x"]}))))
  (is (= "define-class: field y's type no.Such isn't a class (a nested class is Outer$Inner)"
         (thrown #(ldk/define-class "demo.classes.S5" {:static-fields {"y" "no.Such"}}))))
  ;; none of them took the name
  (is (= "class demo.classes.S1"
         (ldk/to-string (ldk/define-class "demo.classes.S1" {:static-methods {"add(I)I" (fn [n] (inc n))}}))))
  (is (= 2 (ldk/invoke-static "demo.classes.S1" "add" 1))))

(deftest defining-again
  (let [spec (fn [n] {:implements ["java.util.function.IntSupplier"] :fields ["v"] :methods {:getAsInt (fn [_] n)}})
        c (ldk/define-class "demo.classes.Again" (spec 1))
        o (ldk/construct "demo.classes.Again")]
    (is (= 1 (ldk/invoke o "getAsInt")))
    ;; the same shape swaps the fns, for objects made before too
    (is (identical? c (ldk/define-class "demo.classes.Again" (spec 2))))
    (is (= 2 (ldk/invoke o "getAsInt")))
    (is (= (str "define-class: demo.classes.Again is already defined with another superclass, interfaces,"
                " fields or method keys; a class is defined once per process")
           (thrown #(ldk/define-class "demo.classes.Again" (assoc (spec 3) :fields ["v" "w"])))))))

(deftest errors-through-java
  (let [boom (ex-info "boom" {:a 1})]
    (ldk/define-class "demo.classes.Boom"
      {:implements ["java.util.concurrent.Callable"] :methods {:call (fn [_] (throw boom))}})
    ;; back as itself
    (is (identical? boom (try (ldk/invoke (ldk/construct "demo.classes.Boom") "call") (catch :default e e))))
    ;; and Java's catch sees it
    (let [ft (ldk/construct "java.util.concurrent.FutureTask" (ldk/construct "demo.classes.Boom"))]
      (ldk/invoke ft "run")
      (is (identical? boom (ex-cause (try (ldk/invoke ft "get") (catch :default e e)))))))
  ;; a Java exception passes as itself
  (ldk/define-class "demo.classes.Io"
    {:implements ["java.util.concurrent.Callable"]
     :methods {:call (fn [_] (throw (ldk/construct "java.io.IOException" "io")))}})
  (is (= "java.io.IOException: io" (thrown #(ldk/invoke (ldk/construct "demo.classes.Io") "call")))))
