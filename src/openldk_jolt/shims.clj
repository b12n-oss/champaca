(ns openldk-jolt.shims
  "Codegen-side metadata for the JDK classes the spike fakes by hand. Their
  method bodies live in resources/openldk_jolt/runtime.ss under the same
  global names; this map only says what exists, so field layouts, vtables
  and static field lookups can be computed.

  Real OpenLDK loads these classes from the JDK's jmods. Doing that here is
  the next spike, not this one.")

(defn- exception-class [super]
  {:super super :interfaces [] :fields []
   :methods [{:name "<init>" :desc "()V" :inherit-impl "java/lang/Throwable"}
             {:name "<init>" :desc "(Ljava/lang/String;)V" :inherit-impl "java/lang/Throwable"}]})

(def classes
  (merge
   {"java/lang/Object"
    {:super nil :interfaces [] :fields []
     :methods [{:name "<init>" :desc "()V"}
               {:name "toString" :desc "()Ljava/lang/String;" :virtual true}
               {:name "hashCode" :desc "()I" :virtual true}]}

    "java/lang/Throwable"
    {:super "java/lang/Object" :interfaces []
     :fields [{:name "detailMessage" :desc "Ljava/lang/String;"}]
     :methods [{:name "<init>" :desc "()V"}
               {:name "<init>" :desc "(Ljava/lang/String;)V"}
               {:name "getMessage" :desc "()Ljava/lang/String;" :virtual true}
               {:name "toString" :desc "()Ljava/lang/String;" :virtual true}]}

    "java/io/PrintStream"
    {:super "java/lang/Object" :interfaces [] :fields []
     :methods (vec (for [d ["(Ljava/lang/String;)V" "(Ljava/lang/Object;)V" "(I)V"
                            "(J)V" "(Z)V" "(C)V" "()V"]]
                     {:name "println" :desc d :virtual true}))}

    "java/lang/System"
    {:super "java/lang/Object" :interfaces []
     :fields [{:name "out" :desc "Ljava/io/PrintStream;" :static true}]
     :methods []}}
   (into {}
         (map (fn [[c super]] [c (exception-class super)]))
         [["java/lang/Exception" "java/lang/Throwable"]
          ["java/lang/RuntimeException" "java/lang/Exception"]
          ["java/lang/ArithmeticException" "java/lang/RuntimeException"]
          ["java/lang/NullPointerException" "java/lang/RuntimeException"]
          ["java/lang/ClassCastException" "java/lang/RuntimeException"]
          ["java/lang/NegativeArraySizeException" "java/lang/RuntimeException"]
          ["java/lang/IndexOutOfBoundsException" "java/lang/RuntimeException"]
          ["java/lang/ArrayIndexOutOfBoundsException" "java/lang/IndexOutOfBoundsException"]])))
