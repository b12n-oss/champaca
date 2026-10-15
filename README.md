<p align="center"><img src="docs/images/logo.svg" alt="Champaca logo: a champaca flower seen from the side, narrow saffron and golden tepals curling outward and drooping from a green cup on a short stem" width="160"></p>

<h1 align="center">Champaca</h1>

> [!WARNING]
> **Spike.** This checks whether [OpenLDK](https://github.com/atgreen/openldk) can be rebuilt in [jolt](https://github.com/jolt-lang/jolt). It runs real JDK code, but only along the paths the tests exercise.

OpenLDK is Anthony Green's Java runtime written in Common Lisp. It translates JVM bytecode into Lisp and lets SBCL compile it to machine code. This project does the same thing on jolt. The translator is written in jolt's Clojure and emits Chez Scheme source, which `jolt.scheme/eval-string` compiles natively, one method at a time on first call.

The classes are the JDK's own. They're read straight out of `$JAVA_HOME/jmods`, `java.base` first and then whichever other module has the class, and the runtime boots them the way a JVM does: it builds the main thread, initializes `String` and `Reference`, then runs `System.initPhase1()`. So `System.out.println` goes through the real `PrintStream`, `StreamEncoder` and UTF-8 encoder, and ends at a native `FileOutputStream.writeBytes`.

The [guides](docs/guide/README.md) cover all of this in more depth: getting started, calling Java, data conversion, jars, running Java programs, performance, debugging, how it works, and what's missing.

## Calling Java from jolt

Java objects live in jolt's own heap as Chez records, so calling into Java is a plain procedure call. There's no second runtime and no wire protocol. Methods are found by name, and the overload is chosen from the argument values, the way `javac` picks one at a call site:

```clojure
(require '[champaca.api :as ldk])

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
- **jolt collections** go where Java wants a `Map`, `Set`, `List`, `Collection`, `Iterable` or plain `Object`. A map becomes a `LinkedHashMap`, a set a `LinkedHashSet`, and a seq an `ArrayList`, keeping jolt's order. A vector becomes an `ArrayList` too, unless the parameter is an array or plain `Object`, where it stays an array as above. The copy is deep, so a nested vector inside a collection is an `ArrayList`, and keywords become their names as strings. `(ldk/->java x)` does the same conversion by hand.
- **Results** come back with Strings as jolt strings and boxed numbers, Booleans and Characters unwrapped. Java arrays and collections stay Java objects, so you can keep calling methods on them. `->clj` copies one into jolt data, deeply: arrays and lists to vectors, sets to sets, maps to maps.

So a jolt map can go straight into a JSON library and come back:

```clojure
(def mapper (ldk/construct "com.fasterxml.jackson.databind.ObjectMapper"))
(def json (ldk/invoke mapper "writeValueAsString" {:name "champaca" :tags ["a" "b"]}))
;=> "{\"name\":\"champaca\",\"tags\":[\"a\",\"b\"]}"
(ldk/->clj (ldk/invoke mapper "readValue" json (ldk/invoke-static "java.lang.Class" "forName" "java.util.Map")))
;=> {"name" "champaca", "tags" ["a" "b"]}
```

`examples/dynamic.clj` (20 calls) and `examples/varargs.clj` (14 calls) each have a Java twin, `test/java/DynamicCheck.java` and `test/java/VarargsCheck.java`, which makes the same calls on the JVM. `bb test` checks the outputs match, and also runs both Java programs on champaca.

Choosing an overload means walking the class's methods and scoring each one, so a call remembers what it chose. The cache key is the class, the method name and the kind of each argument: a Java value's class, an integer's range (int, long or bigger), or the jolt type for the rest. A vector counts by its element kind, whatever its length. A vector mixing kinds isn't cached, and neither is a failed lookup. Once a call has been seen, `invoke` on `StringBuilder.length` takes 1.0 µs instead of 6.3 µs, which is close to the 0.85 µs of a `call` with its descriptor. `ArrayList.add` drops from 9.8 to 1.6 µs and `Math.max` from 4.4 to 1.3 µs. `examples/callsite.clj` runs one call site with arguments that need different overloads, and `CHAMPACA_CALL_CACHE=off` turns the cache off to compare.

The descriptor-based functions (`call-static`, `new-object`, `call`, `implement`) are still there for exact control. A Java exception thrown out of any call surfaces as an `ex-info` whose message is the exception's `toString`, and the object itself sits under `:java/exception`.

## Libraries from jars

The classpath takes directories and jars, so a Java library loads straight from its jar:

```clojure
(ldk/init! {:classpath ["lib/commons-lang3-3.12.0.jar"]})
(ldk/invoke-static "org.apache.commons.lang3.StringUtils" "capitalize" "champaca")  ;=> "Champaca"
```

From the command line it's a colon-separated list, as with `java -cp`: `jolt -M -m champaca.main classes:lib/gson-2.13.2.jar Main`. Four real libraries were checked this way against the JVM, with identical output: commons-lang3 3.12.0 (`StringUtils`), gson 2.13.2 (parsing, `toJson` of a JSON tree, of a plain object and of a `Map`, `fromJson` into a class with no default constructor, pretty printing) jsoup 1.18.1 (parsing HTML, CSS selectors, absolute URLs, `Jsoup.clean` with a safelist) and Jackson 2.20 (`readTree`, `writeValueAsString` of maps and annotated classes, `@JsonProperty` and `@JsonIgnore`, `readValue` into a bean with a `List<Point>` property, indented output). They aren't in `bb test`, because they'd need the jars downloaded. `bb test` packs `test/jar` into `target/lib.jar` instead and runs `test/java/JarCheck.java` and `examples/jars.clj` against it.

Classes outside `java.base` come from the other jmods: `java.sql`, `java.logging`, `jdk.unsupported` and so on. The first run reads each jmod's zip directory in place to build a package-to-module index (876 packages across JDK 25's 69 modules), and saves it as `~/.cache/champaca/jmods/<jdk>/packages.edn`. A class lookup goes straight to its module, and only a module that's actually used gets its zip copied there. `bb test` copies 4 of them, 20 MB.

## Reflection

JDK 25 implements `Method.invoke` and `Constructor.newInstance` either with method handles or with two VM natives, `invoke0` and `newInstance0`. It falls back to the natives while `java.lang.invoke` isn't initialized (it never is here) or when `jdk.reflect.useNativeAccessorOnly=true` (set here too). So the runtime supplies those natives, plus `Class.getDeclaredMethods0`, `getDeclaredFields0`, `getDeclaredConstructors0` and the rest, and builds real `java.lang.reflect` objects through their own constructors. Field access has no native path in JDK 25, so `Field.get`/`set` and the typed variants are overridden.

`Reflection.getCallerClass` walks the Chez continuation. A translated method is a top-level `define` named `Class.name(desc)`, and its blocks and trampoline loop carry that name too, so each frame says which Java method it belongs to. Nothing is recorded on ordinary calls. Under `CHAMPACA_BT` the shadow stack answers instead, because its wrapper hides frame names.

## Startup cache

The first run builds a cache under `~/.cache/champaca/<key>/` and later runs load it. Booting the JDK compiles 362 methods from 201 classes. The cache stores them, along with the class table, as Chez native code (`boot.so`) plus a jolt image (`state.img`). Each later run that compiles more JDK code appends an `extra-<n>.so` generation. Measured on macOS arm64:

| Program | No cache | Warm cache |
|---|---|---|
| Hello | 1.24 s | 0.40 s |
| Streams | 1.81 s | 0.46 s |
| `examples/interop.clj` | 1.38 s | 0.43–0.52 s |

The key covers the JDK (`release`, the jmod's size and mtime), the translator's own source, jolt's runtime version and whether `CHAMPACA_BT` is on (it changes the generated code), so a change to any of them starts a fresh cache instead of reusing a stale one. User classes are never cached. `CHAMPACA_CACHE=off` disables it and `CHAMPACA_CACHE_DIR` moves it. From jolt, call `(ldk/save-cache!)` before exit to keep what the process compiled.

The compiled code leaves out Chez's inspector information, which halves `boot.so` (6.8 MB) and the time to load it. Every generation is still loaded on every run, so one `bb test` from an empty cache (14 generations) brings a warm Hello to 0.60 s. Writing a new cache removes key directories no run has used for a week.

## Run it

You need jolt 0.8.22 or later (`deps.edn` sets that as `:jolt/min-version`) and JDK 25 with `JAVA_HOME` set. The zip inside each jmod is copied, the first time a class is looked up in it, under `~/.cache/champaca/jmods/`.

`deps.edn` also pins an upstream jolt commit under `:champaca/jolt`, because a fix champaca wants hasn't reached a jolt release yet: `FileInputStream.skip` used to read the bytes it skipped, and now it seeks ([jolt#1375](https://github.com/jolt-lang/jolt/pull/1375)). `bb jolt` builds that commit from a jolt checkout (`$JOLT_SRC`, or `~/dev/jolt`, cloned if missing) through a detached worktree, and puts the binary in `~/.cache/champaca/jolt/<sha>/jolt`. `bb test` runs on `$JOLT` if it's set, then on the pinned build if it exists, then on `jolt` from your `PATH`, and prints which one it picked. Everything also works on a released 0.8.22, just a bit slower on the first run.

```sh
bb jolt                                               # build the pinned jolt (about 45 s, once per pin)
bb test                                               # every test/java program vs the JVM, plus the examples/*.clj programs
JOLT=jolt bb test                                     # the same suite on the jolt from your PATH
jolt -M -m champaca.main target/classes Streams   # after bb test has compiled the classes
CHAMPACA_BT=1 ...       # keep a shadow stack, so uncaught exceptions and missing natives print Java frames
CHAMPACA_STATS=1 ...    # print classes loaded and methods compiled
CHAMPACA_PROF=1 ...     # time spent per boot phase (parse, register, codegen, compile, cache)
CHAMPACA_CALLER_DEBUG=1 ...  # print the frames each Reflection.getCallerClass call sees
CHAMPACA_TRACE=1 ...    # log each compiled method, and write the generated Scheme to target/jit/trace.ss
CHAMPACA_CALL_CACHE=off ...  # choose the overload again on every invoke, invoke-static and construct
```

## What's here

| File | Job |
|---|---|
| `src/champaca/classfile.clj` | parses `.class` files: constant pool, fields with `ConstantValue`, methods, `Code`, `BootstrapMethods` |
| `src/champaca/codegen.clj` | turns one method's bytecode into a Scheme `define`, with basic blocks as `letrec` thunks and a typed compile-time stack |
| `src/champaca/main.clj` | loads classes from the classpath (directories and jars) or the jmods, lays out fields, builds vtables (interface defaults included), boots the JDK |
| `src/champaca/api.clj` | the jolt-facing API |
| `resources/champaca/runtime.ss` | objects, arrays, exceptions, `java.lang.String` conversion, `Class` mirrors, lambdas, records |
| `resources/champaca/natives.ss` | JDK native methods (115 named, plus pattern rules for `Unsafe`) and a few overrides |
| `resources/champaca/reflect.ss` | reflection natives, `invoke0`/`newInstance0`, `Field` overrides, `getCallerClass` |
| `resources/champaca/interop.ss` | the conversions and entry points behind `api.clj` |
| `resources/champaca/dynamic.ss` | descriptor-free calls: candidate lookup, overload fit, the call-site cache, auto-wrapping jolt fns |

## Shortcuts that real programs will hit

- **Lambdas and records** skip their bootstrap methods. The translator spots `LambdaMetafactory` and `ObjectMethods` call sites and generates the class or method directly. Any other `invokedynamic` fails.
- **Reflection skips a little metadata.** Methods, fields, constructors, `invoke`, `newInstance` and field get/set work (`test/java/Reflect.java`), and so do annotations with their defaults, generic signatures, and member, local and anonymous classes (`test/java/AnnotationCheck.java`). An annotation is an instance of a generated class, not a `java.lang.reflect.Proxy`, so its `toString`, `equals` and `hashCode` are `Object`'s. Checked-exception lists and record components come back empty, and field access ignores access control.
- **One thread.** `Thread.start0` does nothing, and monitors are no-ops.
- **VarHandles, not MethodHandles.** `findVarHandle`, `findStaticVarHandle`, `arrayElementVarHandle` and `byteArrayViewVarHandle` return handles that every access mode works on (`test/java/VarHandleCheck.java`), so `java.util.concurrent.atomic` and `ConcurrentHashMap` run. Memory-ordering modes all act the same, since there's one thread. The coordinate combinators (`insertCoordinates` and friends), `byteBufferViewVarHandle` and `MethodHandle.invoke` don't exist.
- **No native libraries.** A JDK library load (`zip`, `net`) is a no-op, since its natives are Scheme code here. A Java library's own JNI code can't load.
- **`sun.misc.Unsafe` doesn't warn.** `sun.misc.unsafe.memory.access` is `allow`, because the default `warn` finds its caller with `StackWalker`, which has no natives yet.

## The name

A champaca (*Magnolia champaca*, จำปา in Thai) is a tall tree from South and Southeast Asia, a relative of the magnolias. Its flowers have narrow tepals that run from gold to saffron orange, and their scent is strong enough that the oil goes into perfume.

<p align="center"><img src="docs/images/champaca-1880.jpg" alt="Botanical plate of Michelia champaca: a branch with long green leaves, narrow saffron-orange flowers and a bud, beside a cluster of green fruit" width="240"></p>

*Michelia champaca* (now *Magnolia champaca*), plate 191 of Francisco Manuel
Blanco's *Flora de Filipinas*, Gran edición, Atlas I (about 1880 to 1883).
Public domain, via
[Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Michelia_champaca_Blanco1.191-cropped.jpg).
The logo in `docs/images/logo.svg` was drawn for this project.

## License

GPLv3 with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`.
