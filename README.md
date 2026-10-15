# openldk-jolt

> [!WARNING]
> **Spike.** This checks whether [OpenLDK](https://github.com/atgreen/openldk) can be rebuilt in [jolt](https://github.com/jolt-lang/jolt). It runs real JDK code, but only along the paths the tests exercise.

OpenLDK is Anthony Green's Java runtime written in Common Lisp. It translates JVM bytecode into Lisp and lets SBCL compile it to machine code. This project does the same thing on jolt. The translator is written in jolt's Clojure and emits Chez Scheme source, which `jolt.scheme/eval-string` compiles natively, one method at a time on first call.

The classes are the JDK's own. They're read straight out of `$JAVA_HOME/jmods/java.base.jmod`, and the runtime boots them the way a JVM does: it builds the main thread, runs `Reference.<clinit>`, then runs `System.initPhase1()`. So `System.out.println` goes through the real `PrintStream`, `StreamEncoder` and UTF-8 encoder, and ends at a native `FileOutputStream.writeBytes`.

## Calling Java from jolt

Java objects live in jolt's own heap as Chez records, so calling into Java is a plain procedure call. There's no second runtime and no wire protocol. Methods are found by name, and the overload is chosen from the argument values, the way `javac` picks one at a call site:

```clojure
(require '[openldk-jolt.api :as ldk])

(ldk/init!)

(ldk/invoke-static "java.lang.Math" "max" 3 7.5)                 ;=> 7.5

(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [w ["pear" "fig" "apple" "kiwi"]] (ldk/invoke xs "add" w))
  (ldk/invoke xs "sort" (fn [a b] (compare (count a) (count b))))  ; a jolt fn as a Comparator
  (ldk/invoke xs "remove" 0)                                     ;=> "fig"   (remove(int))
  (ldk/invoke xs "get" 0)                                        ;=> "pear"
  (ldk/to-string xs))                                            ;=> "[pear, kiwi, apple]"

(ldk/->clj (ldk/invoke "a,b,c" "split" ","))                     ;=> ["a" "b" "c"]
```

- **`construct`, `invoke`, `invoke-static`, `get-static`, `get-field`.** Class names take dots or slashes, and a jolt string can be the target of `invoke`.
- **Overloads** go to the best fit: exact primitive, then widening, then boxing, then a supertype. Ties go to the most specific method (JLS 15.12.2.5), so `String.valueOf(nil)` picks `valueOf(char[])` and throws an NPE, just like Java. Only public members count. A call that fits nothing, or two methods equally, throws an `ex-info` listing the candidates.
- **A jolt fn** passed where Java wants a single-method interface (`Comparator`, `Predicate`, `Function`, ...) becomes an implementation of it.
- **Varargs** work the way javac does them: fixed-arity overloads first, then varargs methods with the trailing arguments packed into the array. So `(ldk/invoke-static "java.lang.String" "format" "%s: %.2f" "x" 2.5)` and `(ldk/invoke-static "java.util.List" "of" 1 2 3)` just work.
- **A jolt vector** passed for an array parameter becomes a Java array of that element type. Where the parameter is only `Object`, the contents pick the type: `int[]`, `long[]`, `double[]`, `String[]`, nested arrays, or `Object[]`.
- **Results** come back with Strings as jolt strings and boxed numbers, Booleans and Characters unwrapped. Java arrays stay Java arrays, and `->clj` copies one into a vector.
- **Not handled:** jolt maps, sets and seqs aren't converted to `java.util` collections.

`examples/dynamic.clj` (20 calls) and `examples/varargs.clj` (14 calls) each have a Java twin, `test/java/DynamicCheck.java` and `test/java/VarargsCheck.java`, which makes the same calls on the JVM. `bb test` checks the outputs match, and also runs both Java programs on openldk-jolt.

The descriptor-based functions (`call-static`, `new-object`, `call`, `implement`) are still there for exact control. A Java exception thrown out of any call surfaces as an `ex-info` whose message is the exception's `toString`, and the object itself sits under `:java/exception`.

## Reflection

JDK 25 implements `Method.invoke` and `Constructor.newInstance` either with method handles or with two VM natives, `invoke0` and `newInstance0`. It falls back to the natives while `java.lang.invoke` isn't initialized (it never is here) or when `jdk.reflect.useNativeAccessorOnly=true` (set here too). So the runtime supplies those natives, plus `Class.getDeclaredMethods0`, `getDeclaredFields0`, `getDeclaredConstructors0` and the rest, and builds real `java.lang.reflect` objects through their own constructors. Field access has no native path in JDK 25, so `Field.get`/`set` and the typed variants are overridden.

`Reflection.getCallerClass` walks the Chez continuation. A translated method is a top-level `define` named `Class.name(desc)`, and its blocks and trampoline loop carry that name too, so each frame says which Java method it belongs to. Nothing is recorded on ordinary calls. Under `OPENLDK_BT` the shadow stack answers instead, because its wrapper hides frame names.

## Startup cache

The first run builds a cache under `~/.cache/openldk-jolt/<key>/` and later runs load it. Booting the JDK compiles about 340 methods. The cache stores them, along with the class table, as Chez native code (`boot.so`) plus a jolt image (`state.img`). Each later run that compiles more JDK code appends an `extra-<n>.so` generation. Measured on macOS arm64:

| Program | No cache | Warm cache |
|---|---|---|
| Hello | 0.95 s | 0.29 s |
| Streams | 1.45–1.61 s | 0.35 s |
| `examples/interop.clj` | 1.08 s | 0.31 s |

The key covers the JDK (`release`, the jmod's size and mtime), the translator's own source, and jolt's runtime version, so a change to any of them starts a fresh cache instead of reusing a stale one. User classes are never cached. `OPENLDK_CACHE=off` disables it and `OPENLDK_CACHE_DIR` moves it. From jolt, call `(ldk/save-cache!)` before exit to keep what the process compiled.

Old cache directories aren't cleaned up, and generations aren't merged, so each extra file adds a little load time.

## Run it

You need jolt (0.8.21 or later) and JDK 25 with `JAVA_HOME` set. The first run copies the zip inside `java.base.jmod` to `target/jdk/`.

```sh
bb test                                               # every test/java program vs the JVM, plus examples/interop.clj
jolt -M -m openldk-jolt.main target/classes Streams   # after bb test has compiled the classes
OPENLDK_BT=1 ...       # keep a shadow stack, so uncaught exceptions and missing natives print Java frames
OPENLDK_STATS=1 ...    # print classes loaded and methods compiled
OPENLDK_PROF=1 ...     # time spent per boot phase (parse, register, codegen, compile, cache)
OPENLDK_CALLER_DEBUG=1 ...  # print the frames each Reflection.getCallerClass call sees
OPENLDK_TRACE=1 ...    # log each compiled method, and write the generated Scheme to target/jit/trace.ss
```

## What's here

| File | Job |
|---|---|
| `src/openldk_jolt/classfile.clj` | parses `.class` files: constant pool, fields with `ConstantValue`, methods, `Code`, `BootstrapMethods` |
| `src/openldk_jolt/codegen.clj` | turns one method's bytecode into a Scheme `define`, with basic blocks as `letrec` thunks and a typed compile-time stack |
| `src/openldk_jolt/main.clj` | loads classes from the classpath or the jmod, lays out fields, builds vtables (interface defaults included), boots the JDK |
| `src/openldk_jolt/api.clj` | the jolt-facing API |
| `resources/openldk_jolt/runtime.ss` | objects, arrays, exceptions, `java.lang.String` conversion, `Class` mirrors, lambdas, records |
| `resources/openldk_jolt/natives.ss` | JDK native methods (83 named, plus pattern rules for `Unsafe`) |
| `resources/openldk_jolt/reflect.ss` | reflection natives, `invoke0`/`newInstance0`, `Field` overrides, `getCallerClass` |
| `resources/openldk_jolt/interop.ss` | the conversions and entry points behind `api.clj` |
| `resources/openldk_jolt/dynamic.ss` | descriptor-free calls: candidate lookup, overload fit, auto-wrapping jolt fns |

## Shortcuts that real programs will hit

- **Lambdas and records** skip their bootstrap methods. The translator spots `LambdaMetafactory` and `ObjectMethods` call sites and generates the class or method directly. Any other `invokedynamic` fails.
- **Reflection skips some metadata.** Methods, fields, constructors, `invoke`, `newInstance` and field get/set work (`test/java/Reflect.java`). Annotations, generic signatures, inner-class info and checked-exception lists all come back empty. Field access ignores access control.
- **One thread.** `Thread.start0` does nothing, and monitors are no-ops.
- `float`/`double` to string uses Scheme formatting, not `Double.toString`.

## License

GPLv3 with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`.
