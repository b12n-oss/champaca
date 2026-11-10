;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; A C function calling back into jolt: an upcall stub over a method handle
;; bound to a jolt fn, called by C from the calling thread and from a thread
;; C starts, while a Java thread runs beside it. The fn calls Java itself, so
;; the global lock is entered and left inside the upcall.
;;   jolt example upcalls
(ns examples.upcalls
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]
            [jolt.scheme :as scheme]))

(ldk/init!)

(def ^:private lib
  (str "target/" (ldk/invoke-static "java.lang.System" "mapLibraryName" "ffitest")))

;; jolt test builds the library; jolt example upcalls may run before it has
(defn- ensure-lib! []
  (when-not (ldk/invoke (ldk/construct "java.io.File" lib) "exists")
    (let [pb (ldk/construct "java.lang.ProcessBuilder"
                            ["cc" "-shared" "-fPIC" "-O1" "-pthread" "-o" lib "test/native/ffitest.c"])]
      (ldk/invoke pb "inheritIO")
      (is (= 0 (ldk/invoke (ldk/invoke pb "start") "waitFor"))))))

(defn- layout [n] (ldk/get-static "java.lang.foreign.ValueLayout" n))

(defn- descriptor [ret & args]
  (ldk/invoke-static "java.lang.foreign.FunctionDescriptor" "of" ret (vec args)))

(defn- downcall [name desc]
  (let [arena (ldk/invoke-static "java.lang.foreign.Arena" "global")
        path (ldk/invoke-static "java.nio.file.Path" "of" lib [])
        sym (ldk/invoke (ldk/invoke-static "java.lang.foreign.SymbolLookup" "libraryLookup" path arena)
                        "findOrThrow" name)]
    (ldk/invoke (ldk/invoke-static "java.lang.foreign.Linker" "nativeLinker") "downcallHandle" sym desc [])))

(defn- int-fn-stub
  "A C function pointer for int f(int) that calls the jolt fn f."
  [f]
  (let [op (ldk/proxy ["java.util.function.IntUnaryOperator"] {"applyAsInt" f})
        int-t (ldk/get-static "java.lang.Integer" "TYPE")
        mt (ldk/invoke-static "java.lang.invoke.MethodType" "methodType" int-t int-t)
        ;; publicLookup: lookup() refuses a caller with no Java frame, as for
        ;; a JNI caller on HotSpot
        mh (ldk/invoke (ldk/invoke (ldk/invoke-static "java.lang.invoke.MethodHandles" "publicLookup")
                                   "findVirtual" (ldk/invoke-static "java.lang.Class" "forName" "java.util.function.IntUnaryOperator")
                                   "applyAsInt" mt)
                       "bindTo" op)]
    (ldk/invoke (ldk/invoke-static "java.lang.foreign.Linker" "nativeLinker") "upcallStub"
                mh (descriptor (layout "JAVA_INT") (layout "JAVA_INT"))
                (ldk/invoke-static "java.lang.foreign.Arena" "global") [])))

(defn- calls-java
  "x plus the length of x's decimal digits, through two Java calls."
  [x]
  (+ x (ldk/invoke (ldk/construct "java.lang.StringBuilder" (ldk/invoke-static "java.lang.Integer" "toString" x)) "length")))

(defn- current [] (ldk/invoke-static "java.lang.Thread" "currentThread"))

(deftest c-calls-a-jolt-fn
  (ensure-lib!)
  (let [stub (int-fn-stub calls-java)
        apply-int (downcall "ffi_apply_int" (descriptor (layout "JAVA_INT") (layout "ADDRESS") (layout "JAVA_INT")))]
    (is (= 43 (ldk/invoke apply-int "invokeWithArguments" [stub 41])))))

;; Each side checks which Thread Java thinks is running, around its Java
;; calls: if the upcall gave the lock up early, the two would run at once and
;; one of them would see the other's Thread
(deftest a-c-thread-calls-a-jolt-fn-beside-a-java-thread
  (ensure-lib!)
  (let [seen (atom #{})
        stub (int-fn-stub (fn [x]
                            (let [t (current) r (calls-java x)
                                  ;; the upcall holds the lock until it returns to C,
                                  ;; though the Java calls in between entered and left it
                                  held (boolean (scheme/eval-string "(eq? gil-owner (self))"))]
                              (swap! seen conj [(ldk/invoke t "isDaemon") (identical? t (current)) held])
                              r)))
        in-thread (downcall "ffi_apply_in_thread"
                            (descriptor (layout "JAVA_INT") (layout "ADDRESS") (layout "JAVA_INT") (layout "JAVA_INT")))
        sb (ldk/construct "java.lang.StringBuilder")
        wrong (atom 0)
        started (ldk/construct "java.util.concurrent.CountDownLatch" 1)
        busy (ldk/construct "java.lang.Thread"
                            (ldk/proxy ["java.lang.Runnable"]
                                       {"run" (fn []
                                                (let [me (current)]
                                                  (ldk/invoke started "countDown")
                                                  (dotimes [_ 20000]
                                                    (ldk/invoke sb "append" "x")
                                                    (when-not (identical? me (current)) (swap! wrong inc)))))}))]
    (ldk/invoke busy "start")
    (ldk/invoke started "await")
    ;; f(1) .. f(200) on one thread C starts: the sum of x plus its digit count
    (is (= (reduce + (map calls-java (range 1 201)))
           (ldk/invoke in-thread "invokeWithArguments" [stub 1 200])))
    (ldk/invoke busy "join")
    (is (= 20000 (ldk/invoke sb "length")))
    (is (= 0 @wrong))
    ;; one Thread for the C thread, a daemon, always the current one in its
    ;; upcalls, which hold the lock throughout
    (is (= #{[true true true]} @seen))))
