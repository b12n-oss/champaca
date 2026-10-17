# Calling Java

Everything here lives in `champaca.api`, required as `ldk` throughout. Run `(ldk/init!)` first. Each result shown in a comment came from running the line.

## Five functions

```clojure
(ldk/construct "java.lang.StringBuilder" "jolt")            ; new StringBuilder("jolt")
(ldk/invoke "champaca" "substring" 1 4)                     ;=> "ham"
(ldk/invoke-static "java.lang.Math" "max" 3 7)              ;=> 7
(ldk/get-static "java.lang.Integer" "MAX_VALUE")            ;=> 2147483647
(ldk/get-field tokenizer "sval")                            ; an instance field
```

Class names take dots or slashes (`"java.util.ArrayList"` and `"java/util/ArrayList"` both work). The receiver of `invoke` is a Java object, or a jolt string, which acts as a `java.lang.String`. Anything else, a jolt vector for instance, fails with `champaca: not a Java value`.

A longer run, using a `StringBuilder`:

```clojure
(let [sb (ldk/construct "java.lang.StringBuilder" "jolt")]
  (ldk/invoke sb "append" " + java")
  (ldk/invoke sb "reverse")
  (ldk/to-string sb))
;=> "avaj + tloj"
```

`to-string` calls the object's own `toString`. Use it to look at a Java object, because jolt prints one as `#object[:object]`, which tells you nothing.

The field getters take a field name. `get-field` reads instance fields, walking up the superclasses, and `get-static` reads static ones, running the class's static initializer first if it hasn't run:

```clojure
(let [t (ldk/construct "java.io.StreamTokenizer" (ldk/construct "java.io.StringReader" "tepals 12"))]
  [(ldk/invoke t "nextToken") (ldk/get-field t "sval")
   (ldk/invoke t "nextToken") (ldk/get-field t "nval")])
;=> [-3 "tepals" -2 12.0]        (-3 is TT_WORD, -2 is TT_NUMBER)
```

Neither getter checks access, so they'll read a private field too (`(ldk/get-field sb "count")` on a `StringBuilder` holding `"abc"` gives `3`). There are no setters yet.

## Which overload runs

Champaca picks the overload from the values you pass, the way `javac` picks one from the static types at a call site. Each parameter scores how well its argument fits. An exact primitive match fits best, then a widening (an `int` into a `long` or `double`), then boxing, then passing an object as one of its supertypes. The lowest total wins:

```clojure
(ldk/invoke-static "java.lang.Math" "max" 3 7)            ;=> 7            max(int,int)
(ldk/invoke-static "java.lang.Math" "max" 1 5000000000)   ;=> 5000000000   max(long,long): too big for an int
(ldk/invoke-static "java.lang.Math" "max" 3 7.5)          ;=> 7.5          max(double,double)
(ldk/invoke-static "java.lang.String" "valueOf" \x)       ;=> "x"          valueOf(char)
```

A jolt integer is an `int` when it fits in 32 bits and a `long` otherwise. A jolt double is a `double`, and it never narrows to an `int`. So `(ldk/invoke-static "java.lang.Integer" "valueOf" 2.0)` finds nothing that fits, while `(ldk/invoke-static "java.lang.Double" "valueOf" 2)` widens the 2 and returns `2.0`.

When two overloads score the same, the more specific one wins (JLS 15.12.2.5). With `nil` that matters: `String.valueOf(null)` in Java means `valueOf(char[])`, which throws, and so does this:

```clojure
(ldk/invoke-static "java.lang.String" "valueOf" nil)
;; throws ex-info "java.lang.NullPointerException"
```

`ArrayList.remove` is the classic trap, and it comes out the way it does in Java. An integer selects `remove(int)`, the index. Anything else selects `remove(Object)`:

```clojure
(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [w ["a" "b" "c"]] (ldk/invoke xs "add" w))
  [(ldk/invoke xs "remove" 0) (ldk/invoke xs "remove" "c") (ldk/->clj xs)])
;=> ["a" true ["b"]]
```

Only public methods and constructors take part. Bridge and synthetic methods are skipped, and so is a parent's method that a covariant override hides.

### Varargs

Fixed-arity overloads are tried first. Only if none fits does champaca try the varargs methods, packing the trailing arguments into the array, which is javac's order too:

```clojure
(ldk/invoke-static "java.lang.String" "format" "%s has %d tepals" "champaca" 12)  ;=> "champaca has 12 tepals"
(ldk/invoke-static "java.lang.String" "join" "-" ["a" "b" "c"])                  ;=> "a-b-c"
```

In the second call the vector is the array itself, so nothing gets packed. Passing the elements one by one, `(... "join" "-" "a" "b" "c")`, gives the same result through packing.

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

Where a parameter's type is an interface with one abstract method (`Comparator`, `Predicate`, `Function`, `Runnable` and so on), a jolt fn can go in its place. Java calls back into the fn, with arguments converted the same way results are:

```clojure
(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [n [5 1 4 2 3]] (ldk/invoke xs "add" n))
  (ldk/invoke xs "removeIf" (fn [n] (even? n)))      ; a Predicate
  (ldk/invoke xs "sort" (fn [a b] (compare a b)))    ; a Comparator
  (ldk/->clj xs))
;=> [1 3 5]
```

The fn's return value goes back through the same conversion as an argument, so return a boolean for a `Predicate` and an integer for a `Comparator`.

A fn fits any single-method interface, so a method overloaded on two of them is ambiguous. `ExecutorService.submit` takes a `Runnable` or a `Callable`, and `(ldk/invoke pool "submit" (fn [] 42))` fails with both as candidates. `implement` says which one you mean:

```clojure
(ldk/invoke pool "submit" (ldk/implement "java/util/concurrent/Callable" "call" "()Ljava/lang/Object;" (fn [] 42)))
```

## Threads

A Java thread can run a jolt fn, and it keeps running between your calls into Java, while your own jolt code does other work:

```clojure
(def t (ldk/construct "java.lang.Thread" (fn [] (println "on" (ldk/invoke (ldk/invoke-static "java.lang.Thread" "currentThread") "getName")))))
(ldk/invoke t "start")
(ldk/invoke t "join")
;; on Thread-0
```

Only one thread runs Java code at a time ([limitations](limitations.md#threads-take-turns)), but jolt code runs alongside it. A fn that Java calls on its thread runs on that thread too, so treat what it touches the way you would any code shared between threads. A thread jolt started, such as a `future`, can call Java as well, and gets a `java.lang.Thread` named `jolt-<n>` the first time it does. As on a JVM, a non-daemon thread still running keeps the process alive when your program ends, and an executor's threads are non-daemon until you call `shutdown`. `examples/threads.clj` has a pool and a producer thread.

## Results

Strings come back as jolt strings, with any lone surrogate turned into U+FFFD ([limitations](limitations.md#the-calling-api)). Boxed numbers, Booleans and Characters come back unboxed, and `void` comes back as `nil`. Everything else stays a Java object: arrays, collections, your own classes. You can keep calling methods on those, or copy them into jolt data with `->clj` ([data-conversion](data-conversion.md)).

```clojure
(ldk/invoke "champaca" "length")                      ;=> 8
(ldk/invoke "champaca" "charAt" 0)                    ;=> \c
(ldk/invoke-static "java.lang.Long" "valueOf" 7)      ;=> 7
(ldk/invoke "a,b,c" "split" ",")                      ; a String[], still Java
(ldk/->clj (ldk/invoke "a,b,c" "split" ","))          ;=> ["a" "b" "c"]
```

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

There are two other kinds, told apart by `:champaca/error`: `:lookup` for a call nothing fits (above), and `:missing-native` when Java code reaches a JDK native method champaca doesn't provide yet. [debugging](debugging.md) covers the second.

## With descriptors

The older functions take the JVM descriptor explicitly and skip overload resolution entirely: `call-static`, `new-object`, `call` and `implement`.

```clojure
(ldk/call-static "java/lang/Integer" "toBinaryString" "(I)Ljava/lang/String;" 42)   ;=> "101010"
```

They're there for exact control, such as reaching an overload the scoring wouldn't pick. Since the call-site cache arrived ([performance](performance.md#the-call-site-cache)), speed alone is rarely a reason to use them. `examples/interop.clj` uses nothing else.
