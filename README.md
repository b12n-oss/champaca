<p align="center"><img src="docs/images/logo.svg" alt="Champaca logo: a champaca flower seen from the side, narrow saffron and golden tepals curling outward and drooping from a green cup on a short stem" width="160"></p>

<h1 align="center">Champaca</h1>

> [!WARNING]
> **Spike.** This checks whether [OpenLDK](https://github.com/atgreen/openldk) can be rebuilt in [jolt](https://github.com/jolt-lang/jolt). It runs real JDK code, but only along the paths the tests exercise.

OpenLDK is Anthony Green's Java runtime written in Common Lisp. It translates JVM bytecode into Lisp and lets SBCL compile it to machine code. This project does the same thing on jolt. The translator is written in jolt's Clojure and emits Chez Scheme source, which `jolt.scheme/eval-string` compiles natively, one method at a time on first call.

The classes are the JDK's own. They're read straight out of `$JAVA_HOME/lib/modules`, the runtime image the JVM itself loads them from, and the runtime boots them the way a JVM does: it builds the main thread, initializes `String` and `Reference`, then runs `System.initPhase1()`, `initPhase2()` and `initPhase3()`, which build the boot layer of modules and install the system class loader. So `System.out.println` goes through the real `PrintStream`, `StreamEncoder` and UTF-8 encoder, and ends at a native `FileOutputStream.writeBytes`.

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

- **`construct`, `invoke`, `invoke-static`, `get-static`, `get-field`, `set-static!`, `set-field!`.** Class names take dots or slashes, and a jolt string can be the target of `invoke`. The setters convert the value to the field's type and refuse a `final` field.
- **Overloads** go to the best fit: exact primitive, then widening, then boxing, then a supertype. Ties go to the most specific method (JLS 15.12.2.5), so `String.valueOf(nil)` picks `valueOf(char[])` and throws an NPE, just like Java. Only public members count. A call that fits nothing, or two methods equally, throws an `ex-info` listing the candidates.
- **A jolt fn** passed where Java wants a single-method interface (`Comparator`, `Predicate`, `Function`, ...) becomes an implementation of it. `(ldk/proxy ifaces {"name" fn ...})` makes a `java.lang.reflect.Proxy` for several interfaces, or for an interface with several methods, with results boxed for each method's return type and default methods left to their own bodies. `(ldk/define-class name {:extends ... :implements [...] :fields ... :methods {...}})` makes a real class, with constructors of its own, static fields, methods and initializer, and annotations that Jackson and reflection read, which Java code, reflection and `Class.forName` see ([calling-java](docs/guide/calling-java.md#classes-defined-from-jolt)). An error thrown in such a fn reaches Java as a `RuntimeException`, so Java's `catch` and `finally` blocks see it, and it comes back to jolt as itself ([calling-java](docs/guide/calling-java.md#proxies)).
- **Varargs** work the way javac does them: fixed-arity overloads first, then varargs methods with the trailing arguments packed into the array. So `(ldk/invoke-static "java.lang.String" "format" "%s: %.2f" "x" 2.5)` and `(ldk/invoke-static "java.util.List" "of" 1 2 3)` just work.
- **A jolt vector** passed for an array parameter becomes a Java array of that element type. Where the parameter is only `Object`, the contents pick the type: `int[]`, `long[]`, `double[]`, `String[]`, nested arrays, or `Object[]`.
- **jolt collections** go where Java wants a `Map`, `Set`, `List`, `Collection`, `Iterable` or plain `Object`. A map becomes a `LinkedHashMap`, a set a `LinkedHashSet`, and a seq an `ArrayList`, keeping jolt's order. A vector becomes an `ArrayList` too, unless the parameter is an array or plain `Object`, where it stays an array as above. The copy is deep, so a nested vector inside a collection is an `ArrayList`, and keywords become their names as strings. `(ldk/->java x)` does the same conversion by hand.
- **Results** come back with Strings as jolt strings and boxed numbers, Booleans and Characters unwrapped. Java arrays and collections stay Java objects, so you can keep calling methods on them. `->clj` copies one into jolt data, deeply: arrays and lists to vectors, sets to sets, maps to maps, with `{:key-fn keyword}` for keyword keys.

So a jolt map can go straight into a JSON library and come back:

```clojure
(def mapper (ldk/construct "com.fasterxml.jackson.databind.ObjectMapper"))
(def json (ldk/invoke mapper "writeValueAsString" {:name "champaca" :tags ["a" "b"]}))
;=> "{\"name\":\"champaca\",\"tags\":[\"a\",\"b\"]}"
(ldk/->clj (ldk/invoke mapper "readValue" json (ldk/invoke-static "java.lang.Class" "forName" "java.util.Map")))
;=> {"name" "champaca", "tags" ["a" "b"]}
```

`examples/dynamic.clj` and `examples/varargs.clj` each have a Java twin, `test/java/DynamicCheck.java` and `test/java/VarargsCheck.java`, which makes the same calls on the JVM: 20 and 14 of them. `dynamic.clj` also checks surrogate pairs and a missing native, which its twin doesn't. The examples are tests, and the values they assert are the ones the twins print on the JVM. `bb test` runs them and also runs both Java programs on champaca.

Choosing an overload means walking the class's methods and scoring each one, so a call remembers what it chose. The cache key is the class, the method name and the kind of each argument: a Java value's class, an integer's range (int, long or bigger), or the jolt type for the rest. A vector counts by its element kind, whatever its length. A vector mixing kinds isn't cached, and neither is a failed lookup. Once a call has been seen, `invoke` on `StringBuilder.length` takes 1.0 µs instead of 6.3 µs, which is close to the 0.85 µs of a `call` with its descriptor. `ArrayList.add` drops from 9.8 to 1.6 µs and `Math.max` from 4.4 to 1.3 µs. `examples/callsite.clj` runs one call site with arguments that need different overloads, and `CHAMPACA_CALL_CACHE=off` turns the cache off to compare.

The descriptor-based functions (`call-static`, `new-object`, `call`, `implement`) are still there for exact control. A Java exception thrown out of any call surfaces as an `ex-info` whose message is the exception's `toString`, and the object itself sits under `:java/exception`.

## Libraries from jars

The classpath takes directories and jars, so a Java library loads straight from its jar:

```clojure
(ldk/init! {:classpath ["lib/commons-lang3-3.12.0.jar"]})
(ldk/invoke-static "org.apache.commons.lang3.StringUtils" "capitalize" "champaca")  ;=> "Champaca"
```

From the command line it's a colon-separated list, as with `java -cp`: `jolt -M -m champaca.main classes:lib/gson-2.13.2.jar Main`. `jolt -M -m champaca.main -jar app.jar` runs a jar's `Main-Class`, and a jar's manifest `Class-Path` is followed either way, as on the JVM ([running Java programs](docs/guide/running-java-programs.md)). A class loader of a program's own, such as a `URLClassLoader` over a plugin jar, finds resources the JDK's way, parent first, and a classpath class reports its jar or directory as its code source (`test/java/ResourceCheck.java`). Eight real libraries were checked this way against the JVM, with identical output: commons-lang3 3.12.0 (`StringUtils`), gson 2.13.2 (parsing, `toJson` of a JSON tree, of a plain object and of a `Map`, `fromJson` into a class with no default constructor, pretty printing) jsoup 1.18.1 (parsing HTML, CSS selectors, absolute URLs, `Jsoup.clean` with a safelist), Jackson 2.20 (`readTree`, `writeValueAsString` of maps and annotated classes, `@JsonProperty` and `@JsonIgnore`, `readValue` into a bean with a `List<Point>` property, indented output), Caffeine 3.2.2 (size, weight and time-based eviction against a fake clock, refresh, loading and async caches, stats) Guava 33.4.8 (CRC32C, CRC32, Adler-32, Murmur3, FarmHash, SHA-256, SHA-512 and MD5 hashing, a Bloom filter, immutable multimaps, `Joiner` and `Splitter`) SLF4J 2.0.16 with `slf4j-simple` (logging through the provider `ServiceLoader` finds in its jar) and SnakeYAML 2.3 (loading and dumping maps, beans with generic `List` and `Map` properties, public fields and enums, anchors and merge keys, multi-document streams, block style, timestamps, and its error messages with their line and column marks). Caffeine and Guava both build objects through method handles. `bb libs` reruns those eight checks (the probes are in `test/libs`), running each on the JVM and through champaca and comparing the outputs, and `bb libs:fetch` first downloads whichever jars `~/.m2` lacks from Maven Central, keeping each only if its SHA-1 matches the one pinned in `test/libs/libs.edn`. `bb libs` checks those hashes too, and fails when a library is skipped unless given `--allow-skip`. They aren't part of `bb test`, which needs no network. `bb test` packs `test/jar` into `target/lib.jar` instead and runs `test/java/JarCheck.java` and `examples/jars.clj` against it.

Classes outside `java.base` come from the same image: `java.sql`, `java.logging`, `jdk.unsupported` and so on. The image indexes every entry by name with a perfect hash and lists which module holds each package, so a lookup reads only the class it wants, in place, and nothing is copied or indexed first. Only the index sits in memory, about 1.5 MB for JDK 25.

## Reflection

JDK 25 implements `Method.invoke` and `Constructor.newInstance` either with method handles or with two VM natives, `invoke0` and `newInstance0`. It falls back to the natives while `java.lang.invoke` isn't initialized (it never is here) or when `jdk.reflect.useNativeAccessorOnly=true` (set here too). So the runtime supplies those natives, plus `Class.getDeclaredMethods0`, `getDeclaredFields0`, `getDeclaredConstructors0` and the rest, and builds real `java.lang.reflect` objects through their own constructors. Field access has no native path in JDK 25, so `Field.get`/`set` and the typed variants are overridden.

`Reflection.getCallerClass` and stack traces walk the Chez continuation. A translated method is a top-level `define` named `Class.name(desc)`, and its blocks and trampoline loop carry that name too, so each frame says which Java method it belongs to, and nothing is recorded on ordinary calls. `fillInStackTrace` keeps a pair per frame, the code object and the return offset, about as cheaply as HotSpot keeps its backtrace, and the elements are built when a trace is asked for. The line number comes from Chez's inspector information: every return point records the call it's waiting on, and the generated code wraps each call's operator as `(begin '(jpc . pc) op)`, a constant Chez drops, so the bytecode pc, and from the class file's `LineNumberTable` the line, is read off the frame.

## Startup cache

The first run builds a cache under `~/.cache/champaca/<key>/` and later runs load it. Booting the JDK compiles 819 methods from 387 classes, two thirds of them for the module system (293 from 206 before it booted). The cache stores them, along with the class table, as Chez native code (`boot.so`) plus a jolt image (`state.img`), and keeps the runtime's own Scheme compiled beside them (`runtime.so`). Each later run that compiles more JDK code appends an `extra-<n>.so` generation, and a run loads only the parts of those generations it reaches. Measured on macOS arm64, median of five runs:

| Program | No cache | Warm cache | Warm, after a full `bb test` |
|---|---|---|---|
| Hello | 1.18 s | 0.32 s | 0.33 s |
| Streams | 1.72 s | 0.43 s | 0.44 s |
| `examples/interop.clj` | 1.50 s | 0.37 s | 0.39 s |

The `examples/interop.clj` row timed `jolt -M examples/interop.clj` when that file was a program printing its results. The examples are `clojure.test` namespaces now, and `bb example interop` runs one.

The key covers the JDK (`release`, the runtime image's size and mtime), the translator's own source, jolt's runtime version and whether `CHAMPACA_BT` is on (it changes the generated code), so a change to any of them starts a fresh cache instead of reusing a stale one. Classes from jars and directories are cached too, in a file per jar or directory under `lib/`, each entry checked against its class file's CRC and those of the classes it depends on before it's used. That takes a warm run of Jackson's probe from 5.2 s to 1.3 s, and the same classes unpacked into a directory from 5.5 s to 1.5 s ([performance](docs/guide/performance.md#classes-from-jars-and-directories)). `CHAMPACA_CACHE=off` disables it and `CHAMPACA_CACHE_DIR` moves it. From jolt, call `(ldk/save-cache!)` before exit to keep what the process compiled.

The compiled code leaves out Chez's inspector information, which halves `boot.so` and the time to load it (it's 4.9 MB now, and was 1.7 MB before the module system booted, with each class's method stubs in one form and its vtable stored as changes to its superclass's). `boot.so` loads whole, but a generation is stored one class registration or method body at a time, and each piece loads the first time something needs it. The class table in `state.img` works the same way: each class rides as edn text and parses the first time a run touches it, so a cache grown by many programs costs a warm run little. Before those changes a full `bb test` (14 generations) slowed a warm Hello to 0.58 s. A cold start compiles each JDK method once and caches that same object code, rather than compiling it again to write the cache. Writing a new cache removes key directories no run has used for a week, and keeps at most the 16 most recently used.

## Run it

You need jolt 0.8.23 or later (`deps.edn` sets that as `:jolt/min-version`) and JDK 25 with `JAVA_HOME` set. Any build works, Temurin's included, since every JDK has a runtime image. Champaca used to read the jmods, which Temurin's builds of 25 don't ship; a `~/.cache/champaca/jmods/` left from then can be deleted.

`deps.edn` also pins a jolt commit under `:champaca/jolt`, the v0.8.23 tag. That is the release's own code, built from source, because a local build of it ran a warm Hello in 0.32 s where one release binary of the same commit took 0.45 s. 0.8.23 is also the first release where `FileInputStream.skip` seeks instead of reading the bytes it skips ([jolt#1375](https://github.com/jolt-lang/jolt/pull/1375)), which is why it is the minimum. `bb jolt` builds that commit from a jolt checkout (`$JOLT_SRC`, or `~/dev/jolt`, cloned if missing) through a detached worktree, and puts the binary in `~/.cache/champaca/jolt/<sha>/jolt`. `bb test` runs on `$JOLT` if it's set, then on the pinned build if it exists, then on `jolt` from your `PATH`, and prints which one it picked. Everything also works on a released 0.8.23, just slower.

```sh
bb jolt                                               # build the pinned jolt (about 45 s, once per pin)
bb test                                               # every test/java and test/aaa program vs the JVM, plus the examples/*.clj tests
JOLT=jolt bb test                                     # the same suite on the jolt from your PATH
bb libs:fetch && bb libs                              # the eight real libraries vs the JVM (jars into ~/.m2)
bb linux                                              # bb test and bb libs on Linux, in Docker (bb linux amd64 for x86_64)
bb examples                                           # list the examples/*.clj test files
bb example interop                                    # run one file's tests (builds target/lib.jar first if it needs to)
bb nrepl                                              # an nREPL server for your editor, on a free port written to .nrepl-port
bb site:serve                                         # build the docs site and serve it on the first free port from 3000 (bb site:serve 4000 refuses a busy port); needs a docs-engine checkout
bb site:build                                         # build the docs site into _site/ (bb site:clean deletes it)
jolt -M -m champaca.main target/classes Streams   # after bb test has compiled the classes
CHAMPACA_BT=1 ...       # keep a shadow stack, so an error inside champaca itself prints Java frames, and name each missing native as it's hit
                        # (and say why an NPE got no message)
CHAMPACA_STATS=1 ...    # print classes loaded (and how many from the library cache), methods compiled, cached units loaded
CHAMPACA_PROF=1 ...     # time spent per phase (parse, register, codegen, compile, cache), after boot and when main returns
CHAMPACA_CALLER_DEBUG=1 ...  # print the frames each Reflection.getCallerClass call sees
CHAMPACA_NPE_STRICT=1 ...    # throw when an NPE message can't be built, instead of a null message (bb test sets it)
CHAMPACA_TRACE=1 ...    # log each compiled method, and write the generated Scheme to target/jit/trace.ss
CHAMPACA_CALL_CACHE=off ...  # choose the overload again on every invoke, invoke-static and construct
```

## What's here

| File | Job |
|---|---|
| `src/champaca/classfile.clj` | parses `.class` files: constant pool, fields with `ConstantValue`, methods, `Code`, `BootstrapMethods` |
| `src/champaca/codegen.clj` | turns one method's bytecode into a Scheme `define`, with basic blocks as `letrec` thunks and a typed compile-time stack |
| `src/champaca/main.clj` | loads classes from the classpath (directories and jars) or the JDK's runtime image, lays out fields, builds vtables (interface defaults included), boots the JDK |
| `src/champaca/api.clj` | the jolt-facing API |
| `src/champaca/jimage.clj` | reads the JDK's runtime image, `lib/modules` |
| `src/champaca/npe.clj` | the JVM's helpful `NullPointerException` messages, from the failing bytecode (a port of HotSpot's) |
| `resources/champaca/runtime.ss` | objects, arrays, exceptions, `java.lang.String` conversion, `Class` mirrors, lambdas, records |
| `resources/champaca/natives.ss` | JDK native methods (149 named, plus pattern rules for `Unsafe`), resources, a few overrides |
| `resources/champaca/reflect.ss` | reflection natives, `invoke0`/`newInstance0`, `Field` overrides, `getCallerClass` and the frame walk behind stack traces |
| `resources/champaca/handles.ss` | method handles, call sites for bootstraps run for real, pattern-switch matching |
| `resources/champaca/files.ss` | file, channel and zlib natives (128) through libc, `UnsafeConstants` |
| `resources/champaca/spawn.ss` | child processes: `forkAndExec` through `posix_spawn`, the reaper's `waitpid`, `destroy0` |
| `resources/champaca/net.ss` | socket, datagram and selector natives (105) through libc: `sun.nio.ch.Net`, the socket and datagram dispatchers, `DatagramChannelImpl`, `Inet4AddressImpl`, `NetworkInterface`, `KQueue`, `EPoll`, the selector's wakeup pipe |
| `resources/champaca/interop.ss` | the conversions and entry points behind `api.clj` |
| `resources/champaca/dynamic.ss` | descriptor-free calls: candidate lookup, overload fit, the call-site cache, auto-wrapping jolt fns |

## Shortcuts that real programs will hit

- **javac's bootstraps are compiled, not run.** The translator spots `LambdaMetafactory`, `ObjectMethods`, `makeConcatWithConstants` and the pattern-switch bootstraps and generates the code directly. Any other `invokedynamic` runs its bootstrap method for real, once per call site (`test/java/IndyCheck.expected`, from a class `test/gen/IndyCheckGen.java` writes).
- **Reflection matches the JVM, short of a few gaps.** Methods, fields, constructors, `invoke`, `newInstance` and field get/set work (`test/java/Reflect.java`), and they check the receiver and each argument or value as the JVM does, widening a primitive but never narrowing it, with the JDK's messages (`test/java/ReflectArgCheck.java`, `test/java/FieldArgCheck.java`). So do annotations with their defaults, generic signatures, and member, local and anonymous classes (`test/java/AnnotationCheck.java`). So do record components, parameter names, checked-exception lists and type annotations (`test/java/RecordCheck.java`), and Jackson binds records. An annotation is an instance of a generated class, not a `java.lang.reflect.Proxy`, though its `toString`, `equals` and `hashCode` are the JDK's. Field access ignores access control.
- **Threads take turns.** Each Java thread is a real thread, and `synchronized`, `wait`/`notify`, `sleep`, `interrupt`, executors and `CompletableFuture` all work (`test/java/ThreadCheck.java`), but one global lock lets only one of them run Java code at a time. So threads give correct results and no speedup. Virtual threads aren't supported.
- **Method handles cover the combinator surface.** The `Lookup.find*` and `unreflect*` methods, `invokeExact`, `invoke`, `invokeWithArguments`, `asType`, `bindTo`, the spreaders and collectors, the array element handles, the invokers and the `MethodHandles` combinators from `zero` to `tryFinally` work with the JVM's conversions and exception texts (`test/java/MethodHandleCheck.java`, `test/java/MethodHandleCombinatorCheck.java`). The loop builders (`countedLoop` and friends) don't, `invokeExact` doesn't insist on an exact type match, and lookups skip access checks.
- **VarHandles work for fields and arrays.** `findVarHandle`, `findStaticVarHandle`, `unreflectVarHandle`, `arrayElementVarHandle` and `byteArrayViewVarHandle` return handles that support the same access modes as the JVM's, throwing `UnsupportedOperationException` for the rest (`test/java/VarHandleCheck.java`, `test/java/VarHandleModes.java`), so `java.util.concurrent.atomic` and `ConcurrentHashMap` run. Memory-ordering modes all act the same, which is safe while one thread runs Java at a time. The six coordinate combinators (`insertCoordinates` and friends) work too (`test/java/VarHandleCombinatorCheck.java`); `byteBufferViewVarHandle` doesn't exist.
- **Files go through libc, on macOS and Linux.** `java.io`, `java.nio.file`, `FileChannel` (locks and memory maps included) and `java.util.zip` call libc and zlib through Chez's foreign interface, on real file descriptors (`test/java/FilesCheck.java`). `struct stat` and its relatives are read through a table of offsets measured on each system: macOS (arm64 and x86_64) and Linux with glibc 2.33 or newer on arm64 and x86_64 (`ci/linux/layout.c` prints Linux's). Elsewhere, a BSD, musl, or a glibc older than 2.33 (which doesn't export `stat` itself), `java.nio.file` stops with an `UnsupportedOperationException` at its first use, while `java.io` streams still work. `ci/linux` has a Docker image that runs the suite and the libraries on Linux (`bb linux`), and `.github/workflows/linux.yml` runs the same on GitHub's x86_64 runners when started by hand.
- **Stack traces are the JVM's.** `getStackTrace`, `printStackTrace`, `Thread.currentThread().getStackTrace()`, `StackWalker` and the source location `java.util.logging` infers give the same frames as the JVM, with file names and line numbers for your own classes (`test/java/StackCheck.java`), and an uncaught exception prints its trace the way the JVM's default handler does. A JDK frame names its file but no line, because the startup cache keeps no inspector information for JDK code (with it, a warm start took 0.04 s longer and the cache grew by half). A native champaca wrote in Scheme has no frame, where the JVM prints `Native Method`.
- **The module system boots.** `ModuleLayer.boot()` holds the modules jlink resolved into the runtime image, so `ServiceLoader` sees the JDK's own providers: locale data (`1.234,50 €`, `jeudi 22 octobre`), the extended charsets (Shift_JIS, windows-1252), `ToolProvider`. A JDK class reports its named module, and reflection into a package it doesn't export fails as on the JVM. The application class loader is the system and context class loader, your classes report it and sit in its unnamed module, and a JDK class reports the loader its module is defined to (`test/java/ModuleCheck.java`). It costs a warm start about 0.07 s and a cold one five seconds ([performance](docs/guide/performance.md#the-startup-cache)).
- **Classes can be defined at run time.** `ClassLoader.defineClass` (bytes, or a `ByteBuffer` heap or direct), `Lookup.defineClass` and `defineHiddenClass` (with class data), and `Class.forName` through a loader of your own that defines on demand all work, so `java.lang.reflect.Proxy` does too: a proxy class is generated by the JDK's own `ProxyGenerator` and defined through the same native (`test/java/DefineCheck.java`). A defined class reports its defining loader and, for a hidden one, a name of the JVM's shape, `Name/0x<n>`. A class a loader defines may name others only that loader has, defining them from its `findClass` as ABCL's loader does: each is asked of it through `loadClass`, as the JVM resolves a reference through the referring class's loader, though here when the method naming it is first translated ([limitations](docs/guide/limitations.md#other-gaps)) (`test/java/LinkCheck.java`). One class table remains, so a name can be defined once across all loaders.
- **Object serialization works.** `ObjectOutputStream` and `ObjectInputStream` round-trip your classes and the JDK's, and the bytes are the JVM's: the same class descriptors, field order and computed `serialVersionUID`s. `writeObject`/`readObject`, `writeReplace`/`readResolve`, `Externalizable`, enums, records, arrays, shared references and serializable proxies work, a non-serializable superclass's no-arg constructor runs on deserialization as the specification asks, and a stream resolves class names through the first class loader on the stack that isn't a built-in one, as the JVM does, or through the application loader when jolt itself is the reader (`test/java/SerialCheck.java`, `examples/serialization.clj`).
- **TCP sockets work, over IPv4.** `Socket`, `ServerSocket`, blocking `SocketChannel`s, `HttpURLConnection` and `InetAddress` lookups call libc the way the file natives do, with timeouts, half-close, the socket options and the JDK's exception texts (`test/java/SocketCheck.java`). The JDK is told IPv6 is unavailable: a host is looked up for its IPv4 addresses only (an IPv6-only one is unknown), and connecting to a literal IPv6 address such as `::1` fails with `Protocol family unavailable`. UDP works too: `DatagramSocket` and `DatagramChannel`, connected or not, blocking, with a timeout or under a selector, with the JVM's truncation, port-unreachable and close behavior (`test/java/DatagramCheck.java`). `NetworkInterface` lists the interfaces that have an IPv4 address. The check covers the loopback interface, since the rest differ by machine, and on a Mac and in a Linux container every interface's flags, MTU, hardware address and address prefixes were compared by hand with the JVM's and matched. And `isReachable` tries TCP port 7 as the JDK does without root. Joining multicast groups is not there yet.
- **Child processes work.** `ProcessBuilder` and `Runtime.exec` start programs through `posix_spawn`, with pipes, files, discarded or inherited streams, a changed environment and working directory, and the JDK's PATH search, `/bin/sh` fallback for a script without `#!` and launch error texts. `waitFor` (with a timeout), `destroy`, `destroyForcibly`, `onExit` and `startPipeline` behave as on the JVM, and so do exit codes, a signal's included (`test/java/ProcessCheck.java`). A child gets SIGPIPE's default disposition and none of the parent's other descriptors, as a JVM's does.
- **Selectors work, and so does `java.net.http.HttpClient`.** `Selector` runs on the JDK's own `KQueue` selector on macOS and its `EPoll` one on Linux, so non-blocking `SocketChannel`s and `ServerSocketChannel`s, `Pipe`s, `wakeup`, interrupts and closing a selector from another thread behave as on the JVM, and `HttpClient`'s HTTP/1.1 requests, sent and sent asynchronously, go through its selector thread (`test/java/SelectorCheck.java`). HTTPS works through it too, with HTTP/2 negotiated, checked by hand against a public site on 2026-10-25 since the tests stay offline ([limitations](docs/guide/limitations.md#native-code)). A `select` that waits gives the GIL up.
- **What the VM reports matches.** `Thread.getAllStackTraces` and `enumerate` list the live threads. `ThreadMXBean` measures each thread's CPU time. Nests come from the class file's `NestHost` and `NestMembers` attributes, and an array class has its element's access. `Package.getPackage` answers for the JDK's loaded packages only. The default locale, `java.io.tmpdir` and `sun.java.command` are what the JVM gives on the same machine (`test/java/VmCheck.java`).
- **No native libraries.** A JDK library load (`zip`, `net`, `management`) is a no-op, since its natives are Scheme code (or libc calls) here. A Java library's own JNI code can't load, and a JDK native champaca hasn't written yet throws `UnsatisfiedLinkError`, as the JVM does for an unloaded one, so a program can catch it (`test/java/NativesCheck.java`).
- **`sun.misc.Unsafe` doesn't warn.** `sun.misc.unsafe.memory.access` is `allow`, so Caffeine and Guava, which still use it, run without the JDK's warning on stderr.

## The name

A champaca (*Magnolia champaca*, จำปา in Thai) is a tall tree from South and Southeast Asia, a relative of the magnolias. Its flowers have narrow tepals that run from gold to saffron orange, and their scent is strong enough that the oil goes into perfume.

<p align="center"><img src="docs/images/champaca-1880.jpg" alt="Botanical plate of Michelia champaca: a branch with long green leaves, narrow saffron-orange flowers and a bud, beside a cluster of green fruit" width="240"></p>

*Michelia champaca* (now *Magnolia champaca*), plate 191 of Francisco Manuel
Blanco's *Flora de Filipinas*, Gran edición, Atlas I (about 1880 to 1883).
Public domain, via
[Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Michelia_champaca_Blanco1.191-cropped.jpg).
The logo in `docs/images/logo.svg` was drawn for this project.

## License

GPLv3 with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`. The test programs in `test/aaa` are OpenLDK's own, under the same license (`test/aaa/README.md`).
