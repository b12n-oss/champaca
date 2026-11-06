;; Java objects for several interfaces at once from jolt, through
;; java.lang.reflect.Proxy, and what happens when a jolt fn that Java called
;; throws: Java's catch and finally blocks see it as a RuntimeException, and
;; it comes back to jolt as itself.
;;   bb example proxy
(ns examples.proxy
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init!)

(defn- thrown
  "The ex-message of what f throws."
  [f]
  (try (f) nil (catch :default e (ex-message e))))

(deftest two-interfaces
  (let [runs (atom 0)
        p (ldk/proxy ["java.lang.Runnable" "java.util.Comparator"]
                     {"run" (fn [] (swap! runs inc))
                      :compare (fn [a b] (- (count a) (count b)))})
        xs (ldk/construct "java.util.ArrayList")]
    (doseq [s ["ccc" "a" "bb"]] (ldk/invoke xs "add" s))
    (ldk/invoke-static "java.util.Collections" "sort" xs p)
    (is (= ["a" "bb" "ccc"] (ldk/->clj xs)))
    (let [t (ldk/construct "java.lang.Thread" p)]
      (ldk/invoke t "start")
      (ldk/invoke t "join"))
    (is (= 1 @runs))
    (is (true? (ldk/invoke-static "java.lang.reflect.Proxy" "isProxyClass" (ldk/invoke p "getClass"))))))

(deftest results-boxed-for-the-return-type
  (is (= 5 (ldk/invoke (ldk/proxy ["java.util.function.LongSupplier"] {"getAsLong" (fn [] 5)}) "getAsLong")))
  (is (= 5 (ldk/invoke (ldk/proxy ["java.util.function.IntSupplier"] {"getAsInt" (fn [] 5)}) "getAsInt")))
  (is (= 2.0 (ldk/invoke (ldk/proxy ["java.util.function.DoubleSupplier"] {"getAsDouble" (fn [] 2)}) "getAsDouble")))
  (is (true? (ldk/invoke (ldk/proxy ["java.util.function.BiPredicate"] {"test" (fn [a b] (= a b))}) "test" "x" "x")))
  (is (= \z (ldk/invoke (ldk/proxy ["java.lang.CharSequence"] {"charAt" (fn [_] \z)}) "charAt" 0)))
  (let [in (ldk/proxy ["java.io.DataInput"] {"readShort" (fn [] 7) "readByte" (fn [] -3) "readFloat" (fn [] 1)})]
    (is (= [7 -3 1.0] [(ldk/invoke in "readShort") (ldk/invoke in "readByte") (ldk/invoke in "readFloat")])))
  ;; arguments by the parameter's type: append(char) gets a char, writeChar(int) an int
  (let [got (atom [])
        out (ldk/proxy ["java.lang.Appendable" "java.io.DataOutput"]
                       {"append" (fn ([c] (swap! got conj c) nil) ([s a b] nil))
                        "writeChar" (fn [c] (swap! got conj c))})]
    (ldk/invoke out "append" \a)
    (ldk/invoke out "writeChar" 98)
    (is (= [\a 98] @got)))
  (is (= "1+q" (ldk/invoke (ldk/proxy ["java.util.function.BiFunction"] {"apply" (fn [a b] (str a "+" b))}) "apply" 1 "q"))))

(deftest nil-is-false-for-a-boolean
  (is (false? (ldk/invoke (ldk/proxy ["java.util.function.Predicate"] {"test" (fn [_] nil)}) "test" 1)))
  (is (true? (ldk/invoke (ldk/proxy ["java.util.function.Predicate"] {"test" (fn [_] :yes)}) "test" 1)))
  ;; the same for a bare fn and for implement
  (let [xs (ldk/construct "java.util.ArrayList")]
    (doseq [n [1 2 3]] (ldk/invoke xs "add" n))
    (ldk/invoke xs "removeIf" (fn [n] (when (= n 2) true)))
    (is (= [1 3] (ldk/->clj xs))))
  (let [p (ldk/implement "java/util/function/Predicate" "test" "(Ljava/lang/Object;)Z" (fn [_] nil))]
    (is (false? (ldk/call p "test" "(Ljava/lang/Object;)Z" 1)))))

(defn- answer [] 42)

(deftest what-a-method-map-may-hold
  ;; a var, or anything else callable
  (is (= 42 (ldk/invoke (ldk/proxy ["java.util.function.IntSupplier"] {"getAsInt" #'answer}) "getAsInt")))
  (is (= "x" (ldk/invoke (ldk/proxy ["java.util.function.Function"] {"apply" {1 "x"}}) "apply" 1)))
  (is (= "proxy: \"get\" is not a fn" (thrown #(ldk/proxy ["java.util.function.Supplier"] {"get" 42})))))

(deftest results-that-dont-fit-fail-as-on-the-jvm
  ;; the proxy's own cast to the return type's box throws, so a long for an
  ;; int is the ClassCastException a Long gives on the JVM
  (is (= (str "java.lang.ClassCastException: class java.lang.Long cannot be cast to class java.lang.Integer "
              "(java.lang.Long and java.lang.Integer are in module java.base of loader 'bootstrap')")
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.IntSupplier"] {"getAsInt" (fn [] 5000000000)}) "getAsInt"))))
  (is (= (str "java.lang.NullPointerException: Cannot invoke \"java.lang.Integer.intValue()\" because the return value of "
              "\"java.lang.reflect.InvocationHandler.invoke(Object, java.lang.reflect.Method, Object[])\" is null")
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.IntSupplier"] {"getAsInt" (fn [] nil)}) "getAsInt"))))
  (is (= (str "java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Integer "
              "(java.lang.String and java.lang.Integer are in module java.base of loader 'bootstrap')")
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.IntSupplier"] {"getAsInt" (fn [] "x")}) "getAsInt")))))

(deftest methods-without-a-fn
  (let [cmp (ldk/proxy ["java.util.Comparator"] {"compare" (fn [a b] (compare a b))})]
    ;; a default method runs its own body on the proxy
    (is (= 1 (ldk/invoke (ldk/invoke cmp "reversed") "compare" "a" "b")))
    ;; and so do Object's
    (is (re-matches #"jdk\.proxy\d+\.\$Proxy\d+@[0-9a-f]+" (ldk/to-string cmp)))
    (is (= (ldk/invoke-static "java.lang.System" "identityHashCode" cmp) (ldk/invoke cmp "hashCode")))
    (is (true? (ldk/invoke cmp "equals" cmp)))
    (is (false? (ldk/invoke cmp "equals" "x"))))
  ;; a default method taking an int
  (let [sp (ldk/proxy ["java.util.Spliterator"] {"characteristics" (fn [] 16)})]
    (is (true? (ldk/invoke sp "hasCharacteristics" 16)))
    (is (false? (ldk/invoke sp "hasCharacteristics" 1))))
  (is (= "mine" (ldk/to-string (ldk/proxy ["java.lang.Runnable"] {"run" (fn []) "toString" (fn [] "mine")}))))
  (is (= "java.lang.UnsupportedOperationException: get"
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.Supplier"] {}) "get"))))
  (is (= "java.lang.IllegalArgumentException: java.lang.String is not an interface"
         (thrown #(ldk/proxy ["java.lang.String"] {})))))

(deftest a-jolt-error-comes-back-as-itself
  (let [boom (ex-info "boom" {:a 1})
        caught (try (ldk/invoke (ldk/proxy ["java.util.function.Supplier"] {"get" (fn [] (throw boom))}) "get")
                    (catch :default e e))]
    (is (identical? boom caught)))
  ;; through a Java frame that doesn't catch it: Method.invoke wraps it
  (let [boom (ex-info "impl" {:k 2})
        c (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" (fn [] (throw boom)))
        m (ldk/invoke (ldk/invoke-static "java.lang.Class" "forName" "java.util.concurrent.Callable") "getMethod" "call" [])]
    (is (identical? boom (ex-cause (try (ldk/invoke m "invoke" c []) (catch :default e e)))))))

(deftest java-exceptions-from-a-fn
  (is (= "java.lang.IllegalStateException: bad"
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.Supplier"]
                                         {"get" (fn [] (throw (ldk/construct "java.lang.IllegalStateException" "bad")))})
                              "get"))))
  ;; a checked exception the interface doesn't declare, as Proxy does on the JVM
  (is (= "java.lang.reflect.UndeclaredThrowableException"
         (thrown #(ldk/invoke (ldk/proxy ["java.util.function.Supplier"]
                                         {"get" (fn [] (throw (ldk/construct "java.io.IOException" "io")))})
                              "get")))))

(deftest java-catches-a-jolt-error
  ;; FutureTask catches what its Callable throws and hands it back wrapped
  (let [boom (ex-info "boom" {})
        ft (ldk/construct "java.util.concurrent.FutureTask" (fn [] (throw boom)))]
    (ldk/invoke ft "run")
    (let [e (try (ldk/invoke ft "get") nil (catch :default e e))]
      (is (= "java.util.concurrent.ExecutionException: java.lang.RuntimeException: boom" (ex-message e)))
      (is (identical? boom (ex-cause e)))))
  ;; a Java exception from a call the fn made reaches Java as itself
  (let [ft (ldk/construct "java.util.concurrent.FutureTask"
                          (fn [] (ldk/invoke-static "java.lang.Integer" "parseInt" "nope")))]
    (ldk/invoke ft "run")
    (is (= "java.util.concurrent.ExecutionException: java.lang.NumberFormatException: For input string: \"nope\""
           (thrown #(ldk/invoke ft "get"))))))

(deftest a-jolt-error-releases-the-monitor
  ;; synchronizedList's forEach holds the list's monitor while it calls the
  ;; fn; the error must leave through its synchronized block's handler
  (let [xs (ldk/invoke-static "java.util.Collections" "synchronizedList" (ldk/construct "java.util.ArrayList"))]
    (ldk/invoke xs "add" 1)
    (is (= "in the fn" (thrown #(ldk/invoke xs "forEach" (fn [_] (throw (ex-info "in the fn" {})))))))
    (let [t (ldk/construct "java.lang.Thread" (fn [] (ldk/invoke xs "add" 2)))]
      (ldk/invoke t "start")
      (ldk/invoke t "join" 5000)
      (is (false? (ldk/invoke t "isAlive")))
      (is (= 2 (ldk/invoke xs "size"))))))
