# openldk-jolt

> [!WARNING]
> **Spike.** This checks whether [OpenLDK](https://github.com/atgreen/openldk) can be rebuilt in [jolt](https://github.com/jolt-lang/jolt). It runs real JDK code, but only along the paths the tests exercise.

OpenLDK is Anthony Green's Java runtime written in Common Lisp. It translates JVM bytecode into Lisp and lets SBCL compile it to machine code. This project does the same thing on jolt. The translator is written in jolt's Clojure and emits Chez Scheme source, which `jolt.scheme/eval-string` compiles natively, one method at a time on first call.

The classes are the JDK's own. They're read straight out of `$JAVA_HOME/jmods/java.base.jmod`, and the runtime boots them the way a JVM does: it builds the main thread, runs `Reference.<clinit>`, then runs `System.initPhase1()`. So `System.out.println` goes through the real `PrintStream`, `StreamEncoder` and UTF-8 encoder, and ends at a native `FileOutputStream.writeBytes`.

## Calling Java from jolt

Java objects live in jolt's own heap as Chez records, so calling into Java is a plain procedure call. There's no second runtime and no wire protocol. The API mirrors [jolt-openldk](https://github.com/jlt-commons/jolt-openldk)'s:

```clojure
(require '[openldk-jolt.api :as ldk])

(ldk/init!)

(ldk/call-static "java/lang/Math" "sqrt" "(D)D" 2)              ;=> 1.4142135623730951

(let [xs (ldk/new-object "java/util/ArrayList" "()V")]
  (doseq [w ["pear" "fig" "apple"]]
    (ldk/call xs "add" "(Ljava/lang/Object;)Z" w))
  ;; a jolt fn as a java.util.Comparator, called back from Collections.sort
  (ldk/call-static "java/util/Collections" "sort" "(Ljava/util/List;Ljava/util/Comparator;)V"
                   xs (ldk/implement "java/util/Comparator" "compare"
                                     "(Ljava/lang/Object;Ljava/lang/Object;)I"
                                     (fn [a b] (compare (count a) (count b)))))
  (ldk/to-string xs))                                            ;=> "[fig, pear, apple]"
```

Strings, `nil` and booleans convert at the boundary using the descriptor. Any other Java object crosses as itself. A Java exception thrown out of a call surfaces as an `ex-info` whose message is the exception's `toString`, and the object itself sits under `:java/exception`. See `examples/interop.clj`.

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
| `resources/openldk_jolt/interop.ss` | the conversions and entry points behind `api.clj` |

## Shortcuts that real programs will hit

- **Lambdas and records** skip their bootstrap methods. The translator spots `LambdaMetafactory` and `ObjectMethods` call sites and generates the class or method directly. Any other `invokedynamic` fails.
- **No reflection.** `Method.invoke` and friends aren't there. `Class.getEnumConstantsShared` is overridden to call `values()` directly, because `EnumMap` needs it.
- **One thread.** `Thread.start0` does nothing, and monitors are no-ops.
- `float`/`double` to string uses Scheme formatting, not `Double.toString`.

## License

GPLv3 with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`.
