# Calling Java

Everything here lives in `champaca.api`, required as `ldk` throughout. Run `(ldk/init!)` first. Each result shown in a comment came from running the line.

## The core functions

```clojure
(ldk/construct "java.lang.StringBuilder" "jolt")            ; new StringBuilder("jolt")
(ldk/invoke "champaca" "substring" 1 4)                     ;=> "ham"
(ldk/invoke-static "java.lang.Math" "max" 3 7)              ;=> 7
(ldk/get-static "java.lang.Integer" "MAX_VALUE")            ;=> 2147483647
(ldk/get-field tokenizer "sval")                            ; an instance field
(ldk/set-field! tokenizer "sval" "jolt")                    ; and its setter
```

Class names take dots or slashes (`"java.util.ArrayList"` or `"java/util/ArrayList"`). The receiver of `invoke` is a Java object, or a jolt string, which acts as a `java.lang.String`. Anything else, a jolt vector for instance, fails with `champaca: not a Java value`.

A longer run, using a `StringBuilder`:

```clojure
(let [sb (ldk/construct "java.lang.StringBuilder" "jolt")]
  (ldk/invoke sb "append" " + java")
  (ldk/invoke sb "reverse")
  (ldk/to-string sb))
;=> "avaj + tloj"
```

`to-string` calls the object's own `toString`. Use it to look at a Java object, since jolt prints one as an unhelpful `#object[:object]`.

`get-field` reads an instance field by name, walking up the superclasses. `get-static` reads a static one, found the way Java resolves `Sub.FIELD`: the class itself, then its interfaces, then its superclass. As on the JVM, only the declaring class gets initialized, so `(ldk/get-static "java.io.ObjectOutputStream" "STREAM_MAGIC")` gives `-21267` from the `ObjectStreamConstants` interface. A `StreamTokenizer` keeps its current token in public fields:

```clojure
(let [t (ldk/construct "java.io.StreamTokenizer" (ldk/construct "java.io.StringReader" "tepals 12"))]
  [(ldk/invoke t "nextToken") (ldk/get-field t "sval")
   (ldk/invoke t "nextToken") (ldk/get-field t "nval")])
;=> [-3 "tepals" -2 12.0]        (-3 is TT_WORD, -2 is TT_NUMBER)
```

Neither getter checks access, so a private field reads too (`(ldk/get-field sb "count")` on a `StringBuilder` holding `"abc"` gives `3`).

`set-field!` and `set-static!` store into a field, found the same way, and return `nil`. The value converts as an argument of the field's type would ([data-conversion](data-conversion.md)). An integer in range also goes into a `byte`, `short` or `char` field, the way Java lets a constant narrow on assignment, and one stored in a `float` or `double` field widens as in Java, so `16777217` in a `float` reads back as `1.6777216E7`:

```clojure
(let [t (ldk/construct "java.io.StreamTokenizer" (ldk/construct "java.io.StringReader" ""))]
  (ldk/set-field! t "ttype" -3)
  (ldk/set-field! t "sval" "jolt")
  (ldk/to-string t))
;=> "Token[jolt], line 1"
```

They refuse a `final` field (`field MAX_VALUE in java/lang/Integer is final`) and a value the type can't hold (`field nval in java/io/StreamTokenizer has type double, which the value doesn't fit`). Like the getters, they skip access checks.

## Which overload runs

Champaca picks the overload from the values you pass, the way `javac` picks one from the static types at a call site. Each parameter scores how well its argument fits: an exact primitive match best, then a widening (an `int` into a `long` or `double`), then boxing, then passing an object as one of its supertypes. The lowest total wins:

```clojure
(ldk/invoke-static "java.lang.Math" "max" 3 7)            ;=> 7            max(int,int)
(ldk/invoke-static "java.lang.Math" "max" 1 5000000000)   ;=> 5000000000   max(long,long): too big for an int
(ldk/invoke-static "java.lang.Math" "max" 3 7.5)          ;=> 7.5          max(double,double)
(ldk/invoke-static "java.lang.String" "valueOf" \x)       ;=> "x"          valueOf(char)
```

A jolt integer is an `int` when it fits in 32 bits and a `long` otherwise. A jolt double is a `double`, and it never narrows to an `int`. So `(ldk/invoke-static "java.lang.Integer" "valueOf" 2.0)` finds nothing that fits, while `(ldk/invoke-static "java.lang.Double" "valueOf" 2)` widens the 2 and returns `2.0`.

An integer reaches a `byte` or `short` parameter only as a last resort. When no overload fits without narrowing, the choice is made again, this time letting an integer in the type's range fit one, and a vector of them a `byte[]` or `short[]`. Since Java never narrows an argument, anything that resolves the first time resolves as javac would. The second try lets jolt, which has no byte type, call `(ldk/invoke-static "java.lang.Byte" "valueOf" 5)` or `(ldk/invoke md "update" [104 105])`. `StringBuilder.append(65)` still appends `"65"`, and `Byte.valueOf(300)` finds nothing.

When two overloads score the same, the more specific one wins (JLS 15.12.2.5). With `nil` that matters: `String.valueOf(null)` in Java means `valueOf(char[])`, which throws, and so does this:

```clojure
(ldk/invoke-static "java.lang.String" "valueOf" nil)
;; throws ex-info "java.lang.NullPointerException"
```

`ArrayList.remove` is the classic trap, and it comes out as in Java: an integer selects `remove(int)`, the index, and anything else `remove(Object)`:

```clojure
(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [w ["a" "b" "c"]] (ldk/invoke xs "add" w))
  [(ldk/invoke xs "remove" 0) (ldk/invoke xs "remove" "c") (ldk/->clj xs)])
;=> ["a" true ["b"]]
```

Only public methods and constructors take part. Bridge and synthetic methods are skipped, and so is a parent's method that a covariant override hides.

### Varargs

As in javac, fixed-arity overloads are tried first. Only if none fits does champaca try the varargs methods, packing the trailing arguments into the array:

```clojure
(ldk/invoke-static "java.lang.String" "format" "%s has %d tepals" "champaca" 12)  ;=> "champaca has 12 tepals"
(ldk/invoke-static "java.lang.String" "join" "-" ["a" "b" "c"])                  ;=> "a-b-c"
```

In the second call the vector is the array itself, so nothing gets packed. Passing the elements one by one, `(... "join" "-" "a" "b" "c")`, packs them and gives the same result.

### When nothing fits

A call that no overload fits, or that two fit equally well, throws an `ex-info` with `{:champaca/error :lookup}` and the candidates in the message:

```clojure
(ldk/invoke-static "java.lang.Math" "max" "a" "b")
;; throws ex-info:
;;   no max in java/lang/Math fits 2 argument(s); candidates:
;;     java/lang/Math.max(II)I
;;     java/lang/Math.max(JJ)J
;;     java/lang/Math.max(FF)F
;;     java/lang/Math.max(DD)D
```

Each line names a method by its JVM descriptor: `(II)I` takes two ints and returns an int. A name that doesn't exist at all says `(no method of that name and arity)`.

## jolt fns as Java interfaces

A jolt fn can go where a parameter's type is an interface with one abstract method (`Comparator`, `Predicate`, `Function`, `Runnable` and so on). Java calls back into it, converting arguments as results are:

```clojure
(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [n [5 1 4 2 3]] (ldk/invoke xs "add" n))
  (ldk/invoke xs "removeIf" (fn [n] (even? n)))      ; a Predicate
  (ldk/invoke xs "sort" (fn [a b] (compare a b)))    ; a Comparator
  (ldk/->clj xs))
;=> [1 3 5]
```

The fn's return value converts as an argument does, so return an integer for a `Comparator`. A method returning `boolean`, such as a `Predicate`'s, takes jolt's truth, where `nil` is false.

Because a fn fits any single-method interface, a method overloaded on two of them is ambiguous. `ExecutorService.submit` takes a `Runnable` or a `Callable`, so `(ldk/invoke pool "submit" (fn [] 42))` fails with both as candidates. `implement` says which one you mean:

```clojure
(ldk/invoke pool "submit" (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" (fn [] 42)))
```

## Proxies

`proxy` makes one object for several interfaces, or for an interface with more than one method, out of a map from method name to fn. The object is a `java.lang.reflect.Proxy`, built by the JDK's own `ProxyGenerator` (`examples/proxy.clj`):

```clojure
(def xs (ldk/construct "java.util.ArrayList" ["ccc" "a" "bb"]))
(def p (ldk/proxy ["java.lang.Runnable" "java.util.Comparator"]
                  {"run" (fn [] (println "ran"))
                   :compare (fn [a b] (- (count a) (count b)))}))
(ldk/invoke-static "java.util.Collections" "sort" xs p)   ; sorts by length
(ldk/invoke (ldk/construct "java.lang.Thread" p) "start")  ; prints "ran"
```

Names can be strings or keywords. Overloads of one name share its fn, so give it one arity per overload. A map value can be anything callable, a var for instance; anything else is refused when the proxy is made.

Arguments arrive converted for their parameter types as `invoke` results are. The fn's result goes back boxed for the method's return type, so a `long` method can return `5` and a `double` method `2`, and a `boolean` method takes jolt's truth. A result that doesn't fit goes back as it is, and the proxy's own cast fails as it would on the JVM: `5000000000` from an `int` method is a `ClassCastException`, and `nil` from an `int` method a `NullPointerException`.

A method without a fn runs its own body when it has one. Default methods work that way, so `(ldk/invoke p "reversed")` gives a working reversed `Comparator`, and so do `equals`, `hashCode` and `toString`, which behave as `Object`'s unless the map names them. A method with no body throws `UnsupportedOperationException` with the method's name, as Clojure's `proxy` does. A checked exception the method doesn't declare reaches the caller as `UndeclaredThrowableException`, the JDK's own rule for proxies.

The proxy belongs to the application class loader, so it can implement your classpath's interfaces as well as the JDK's.

### C calling a jolt fn

`java.lang.foreign` works from jolt, upcalls included, so you can hand a jolt fn to a C function that takes a function pointer. Wrap the fn as a functional interface, take a method handle on its method, and turn that into a stub with `Linker.upcallStub` (`examples/upcalls.clj`):

```clojure
(def op (ldk/proxy ["java.util.function.IntUnaryOperator"] {"applyAsInt" (fn [x] (* 2 x))}))
(def int-t (ldk/get-static "java.lang.Integer" "TYPE"))
(def mh (-> (ldk/invoke-static "java.lang.invoke.MethodHandles" "publicLookup")
            (ldk/invoke "findVirtual" (ldk/invoke-static "java.lang.Class" "forName" "java.util.function.IntUnaryOperator")
                        "applyAsInt" (ldk/invoke-static "java.lang.invoke.MethodType" "methodType" int-t int-t))
            (ldk/invoke "bindTo" op)))
(def int-layout (ldk/get-static "java.lang.foreign.ValueLayout" "JAVA_INT"))
(def stub (ldk/invoke (ldk/invoke-static "java.lang.foreign.Linker" "nativeLinker") "upcallStub"
                      mh (ldk/invoke-static "java.lang.foreign.FunctionDescriptor" "of" int-layout [int-layout])
                      (ldk/invoke-static "java.lang.foreign.Arena" "global") []))
```

`stub` is a `MemorySegment` whose address C can call. Use `publicLookup` here: `MethodHandles.lookup()` needs a Java caller and throws `IllegalCallerException` when jolt calls it, as it does for a JNI caller on HotSpot. The fn can call Java again, and it can run on a thread C started, which gets a `java.lang.Thread` of its own. Since jolt can't pass `--enable-native-access`, Java prints the restricted-method warning for code in the unnamed module.

## Classes defined from jolt

`define-class` makes a Java class out of jolt fns. It can extend a class, implement any number of interfaces and have fields, and Java code, reflection and `Class.forName` see an ordinary class of the application loader (`examples/classes.clj`):

```clojure
(ldk/define-class "demo.Greeter"
  {:implements ["java.util.function.Function"]
   :fields {"greeting" "java.lang.String" "count" "int"}
   :methods {:apply (fn [this who]
                      (ldk/set-field! this "count" (inc (ldk/get-field this "count")))
                      (str (ldk/get-field this "greeting") ", " who))}})
;=> the Class, demo.Greeter

(def g (ldk/construct "demo.Greeter"))
(ldk/set-field! g "greeting" "hi")
(ldk/invoke g "apply" "bob")                        ;=> "hi, bob"
(ldk/invoke-static "java.lang.Class" "forName" "demo.Greeter")   ; the same class
```

`:extends` defaults to `java.lang.Object`. The class gets a public constructor for each public or protected constructor of its superclass, passing its arguments on, so `(ldk/construct "demo.Worker" "w1")` works for a class extending `Thread`. `:fields` is a vector of names, each an `Object`, or a map from name to type, written as Java source writes it: `"int"`, `"java.lang.String"`, `"long[]"`. The fields are public, so `get-field`, `set-field!` and Java reflection all reach them.

Each fn gets the object first, then the method's arguments converted as `invoke` results are. A key in `:methods` is one of two things:

- A method name, as a string or keyword. Its fn implements every method of that name the class inherits and can override: abstract, default or concrete, public or protected, and not final. Give the fn one arity per overload.
- A full JVM name such as `"twice(I)I"`. Its fn implements that one method, and it wins over its name's fn. It can be a method the class inherits or a new one of its own, which Java reaches through reflection.

`:constructors` gives the class constructors of its own instead, as `gen-class` does. It maps a vector of parameter types to the parameter types of a superclass constructor, which may be public or protected.

`:init` gets a constructor's arguments and returns the superclass constructor's arguments as a vector, checked against its parameter types. `:post-init` then gets the object and the same arguments. Give either fn one arity per constructor; two constructors with the same number of parameters reach the same arity, so the fn tells them apart by the arguments' values. A constructor whose superclass constructor takes no arguments needs no `:init`. `:init` or `:post-init` without `:constructors` is refused, since it would never run:

```clojure
(ldk/define-class "demo.Named"
  {:extends "java.lang.Thread"
   :fields {"n" "int"}
   :constructors {["java.lang.String" "int"] ["java.lang.String"]}
   :init (fn [who n] [(str "worker-" who)])
   :post-init (fn [this who n] (ldk/set-field! this "n" n))
   :methods {:run (fn [this] (println (ldk/get-field this "n")))}})
(ldk/invoke (ldk/construct "demo.Named" "a" 3) "getName")   ;=> "worker-a"
```

`:static-fields` and `:static-methods` give the class public static members. Static fields are written like `:fields`, and they start as Java's defaults. Each `:static-methods` key is a full JVM name, and its fn gets the arguments alone, with no object:

```clojure
(ldk/define-class "demo.Tally"
  {:static-fields {"total" "int"}
   :static-methods {"add(I)I" (fn [n]
                                (ldk/set-static! "demo.Tally" "total" (+ n (ldk/get-static "demo.Tally" "total")))
                                (ldk/get-static "demo.Tally" "total"))}})
(ldk/invoke-static "demo.Tally" "add" 5)            ;=> 5
```

`:static-init` is a fn of no arguments that runs as the class's static initializer, the place to set the static fields. Defining the class doesn't run it. It runs once, when the class is first initialized by a constructor, a static field or method, or `Class.forName`, as for any Java class:

```clojure
(ldk/define-class "demo.Config"
  {:static-fields {"PORT" "int"}
   :static-init (fn [] (ldk/set-static! "demo.Config" "PORT" 8080))})
(ldk/get-static "demo.Config" "PORT")              ;=> 8080
```

An error thrown by the initializer fails the class as the JVM fails one. The first use throws `ExceptionInInitializerError`, whose cause is the error, and every later use throws `NoClassDefFoundError: Could not initialize class`. A Java `Error` passes through as itself.

Java code reaches the statics through reflection or a method handle, and their results are checked as an instance method's are. A field's type can be the class being defined, as in a linked node's `next` or a static `INSTANCE`.

Defining the class refuses, with the reason, any of these:

- a name with nothing to override, a final method, a final superclass
- an interface given as `:extends`, a class given in `:implements`
- a field named twice or with a type that isn't a class (a nested class is written `java.util.Map$Entry`)
- a method key given twice (`:apply` and `"apply"`)
- a superclass constructor that doesn't exist or is private, a constructor that passes arguments on without an `:init`, an empty `:constructors`
- a static method key without its descriptor, and a static method with the name and descriptor of an instance method, the class's own or inherited

A refused definition leaves the name free. The JVM's own checks run after these. A sealed superclass or interface that doesn't permit the class, such as `java.lang.constant.ConstantDesc`, throws HotSpot's `IncompatibleClassChangeError`, which arrives as any Java exception does, under `:java/exception`.

The fn's result is checked against the method's return type, as Clojure's casts check one. A `boolean` method takes jolt's truth. For any other primitive, `nil` is a `NullPointerException`, a value of the wrong kind (a string for an `int`) a `ClassCastException`, and a number out of the type's range an `IllegalArgumentException`. A `double` method can return `3`, though, and a `char` method an integer in range.

For a reference type the result converts as an argument to `invoke` would, so a vector can be a `List` and a map a `Map`. A vector for an array type is checked element by element by the same rules, which makes `[1 "a"]` for an `int[]` a `ClassCastException`. A number for a box type such as `Double` is boxed as that type, and a fn is taken only for an interface with one abstract method. Anything that still isn't an instance of the return type is a `ClassCastException` that names the method.

`invoke-super` calls the superclass's method, as `super.name(args)` does in Java, and picks the overload the same way `invoke` does. It reaches protected methods and the defaults of the class's own interfaces:

```clojure
(ldk/define-class "demo.Loud"
  {:extends "java.util.ArrayList"
   :methods {:add (fn [this x] (ldk/invoke-super this "add" (str x "!")))
             :toString (fn [this] (str "Loud" (ldk/invoke-super this "toString")))}})
```

A method you leave out keeps what the class inherits, and an abstract one left out throws `AbstractMethodError` when called, as a class compiled without it would on the JVM. The class is final, so `invoke-super` always means the class it was defined with.

`:annotations` puts annotations on the class, as a map from an annotation type to a map of its elements. `:field-annotations`, `:method-annotations` and `:constructor-annotations` do the same for members, keyed by a field name, a key of `:methods` or `:static-methods` (a method name annotates every method it implements), and a constructor's vector of parameter types. Reflection and libraries that read annotations see them as they see a compiled class's, so Jackson binds this class as it would a Java one:

```clojure
(def ja "com.fasterxml.jackson.annotation.")
(ldk/define-class "demo.Person"
  {:fields {"name" "java.lang.String" "age" "int"}
   :annotations {(str ja "JsonPropertyOrder") {:value ["age" "name"]}}
   :field-annotations {"name" {(str ja "JsonProperty") {:value "full_name"}}}
   :methods {"getSecret()Ljava/lang/String;" (fn [_] "hidden")}
   :method-annotations {"getSecret()Ljava/lang/String;" {(str ja "JsonIgnore") {}}}})
;; with name "Ada" and age 36 set, an ObjectMapper writes {"age":36,"full_name":"Ada"}
```

An element's value is checked against its type, as javac checks one:

- a primitive takes a number in its range (a `char` a char too, a `boolean` only `true` or `false`)
- a `String` takes a string, and a `Class` a type name such as `"int"`, `"void"` or `"java.util.List[]"`, or a `Class`
- an enum takes a constant's name as a keyword or string, and a nested annotation a map of its elements
- an array takes a vector, or a single value as an array of one

An element left out takes its default. Refused are an element the type doesn't have, a missing element with no default, a type that isn't an annotation, one whose retention isn't `RUNTIME` (reflection would never see it) and one whose `@Target` leaves out what it's on. So is a member key that names nothing the class has, or a method name whose methods all have full JVM names of their own in `:methods`, since each takes only its own key's annotations. As javac does, champaca refuses a `String` past 65535 bytes in a class file's UTF-8, and a `float` that would round to infinity or to zero.

Errors cross as they do for any fn Java calls ([Exceptions](#exceptions)).

A class is defined once per process. Evaluating the same `define-class` again swaps in the new fns and keeps the static fields' values, which suits the REPL, and objects made before see the new fns too. "The same" means the same superclass, interfaces, fields in the same order, method keys, static initializer, constructors and annotations, statics included. Any other change is refused, so change the name or restart the process ([limitations](limitations.md#classes-defined-from-jolt)).

## Threads

A Java thread can run a jolt fn, and it keeps running between your calls into Java, while your own jolt code does other work:

```clojure
(def t (ldk/construct "java.lang.Thread" (fn [] (println "on" (ldk/invoke (ldk/invoke-static "java.lang.Thread" "currentThread") "getName")))))
(ldk/invoke t "start")
(ldk/invoke t "join")
;; on Thread-0 (the number depends on the threads made before it)
```

Only one thread runs Java code at a time ([limitations](limitations.md#threads-take-turns)), while jolt code runs alongside it. A fn that Java calls on its thread runs on that thread too, so treat what it touches as code shared between threads. A thread jolt started, such as a `future`, can call Java as well, and gets a `java.lang.Thread` named `jolt-<n>` the first time it does.

As on a JVM, a non-daemon thread still running keeps the process alive when your program ends, and an executor's threads are non-daemon until you call `shutdown`. `examples/threads.clj` has a pool and a producer thread.

## Results

Strings come back as jolt strings, with any lone surrogate turned into U+FFFD ([limitations](limitations.md#the-calling-api)). Boxed numbers, Booleans and Characters come back unboxed, and `void` comes back as `nil`. Everything else stays a Java object: arrays, collections, your own classes. You can keep calling methods on those, or copy them into jolt data with `->clj` ([data-conversion](data-conversion.md)).

```clojure
(ldk/invoke "champaca" "length")                      ;=> 8
(ldk/invoke "champaca" "charAt" 0)                    ;=> \c
(ldk/invoke-static "java.lang.Long" "valueOf" 7)      ;=> 7
(ldk/invoke "a,b,c" "split" ",")                      ; a String[], still Java
(ldk/->clj (ldk/invoke "a,b,c" "split" ","))          ;=> ["a" "b" "c"]
```

## Serialization

`ObjectOutputStream` and `ObjectInputStream` work from jolt as any other classes do, on your own classes and the JDK's (`examples/serialization.clj`). A stream read from jolt resolves the class names it holds through the application class loader, the one your classpath answers to, as the JVM does when the reader is one of the application's classes. A stream read by a class that a loader of your own defined resolves through that loader ([how-it-works](how-it-works.md#natives)).

## Exceptions

A Java exception that escapes a call turns into an `ex-info`. Its message is the exception's `toString`, and the exception object itself sits under `:java/exception` in the ex-data, so you can keep asking it things:

```clojure
(try
  (ldk/invoke-static "java.lang.Integer" "parseInt" "nope")
  (catch :default e
    [(ex-message e)
     (ldk/invoke (:java/exception (ex-data e)) "getMessage")]))
;=> ["java.lang.NumberFormatException: For input string: \"nope\""
;    "For input string: \"nope\""]
```

A fn of yours that Java calls (an interface implementation, a `proxy` method, a thread's `Runnable`) can throw too. A Java exception it throws, or one a Java call inside it threw, leaves as itself. Any other error leaves as a `java.lang.RuntimeException` with the error's message. Java code sees it as it would see Clojure's `ex-info` on the JVM: its `catch` blocks catch it, its `finally` blocks run, and a `synchronized` block gives its lock back.

When that exception comes back out to your code, you get your own error again, the identical object with its ex-data. If Java wrapped it first, as `FutureTask.get` does in an `ExecutionException`, the wrapper's `ex-info` has your error as its `ex-cause`:

```clojure
(let [ft (ldk/construct "java.util.concurrent.FutureTask" (fn [] (throw (ex-info "boom" {:a 1}))))]
  (ldk/invoke ft "run")
  (try (ldk/invoke ft "get")
       (catch :default e [(ex-message e) (ex-data (ex-cause e))])))
;=> ["java.util.concurrent.ExecutionException: java.lang.RuntimeException: boom" {:a 1}]
```

Champaca's own errors are `:lookup` under `:champaca/error`, with the reason in the message: a call nothing fits (above), a setter given a final field, a `define-class` that refuses a definition. `proxy` and `->clj` refuse bad input with an `ex-info` of their own. A receiver that isn't a Java value at all, such as a vector given to `invoke`, is a plain `RuntimeException` (`champaca: not a Java value [1]`).

A JDK native method champaca doesn't provide yet throws `java.lang.UnsatisfiedLinkError`, which arrives as a Java exception like any other ([debugging](debugging.md) covers it).

## Checked against Java

`examples/dynamic.clj` and `examples/varargs.clj` each have a Java twin, `test/java/DynamicCheck.java` and `test/java/VarargsCheck.java`, making the same calls on the JVM: 26 and 14 of them. The examples are tests, asserting the values the twins print on the JVM, and `jolt test` runs them along with both Java programs on champaca. `dynamic.clj` also checks things its twin doesn't: surrogate pairs, a missing native and the out-of-range integers a `byte` parameter refuses.

## With descriptors

The older functions take the JVM descriptor and skip overload resolution: `call-static`, `new-object`, `call` and `implement`.

```clojure
(ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 42)   ;=> "101010"
```

Use them for exact control, such as reaching an overload the scoring wouldn't pick. With the call-site cache ([performance](performance.md#the-call-site-cache)), speed alone is rarely a reason. `examples/interop.clj` uses nothing else.

An `int`, `short`, `byte`, `char` or `long` parameter takes a jolt integer in that type's range, or a character as its code. As `Method.invoke` would, it refuses anything else (`1.5`, `nil`, `2147483648` for an `int`) with `java.lang.IllegalArgumentException: 1.5 is not a Java int`. An `implement` fn whose method returns one of those types is held to the same rule, and a result that breaks it throws `java.lang.ClassCastException` inside the Java code that called the fn.
