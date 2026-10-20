# Limitations

Champaca is a spike. It runs the JDK's own code, but only along the paths its tests and the eight checked libraries (commons-lang3, gson, jsoup, Jackson, Caffeine, Guava, SLF4J, SnakeYAML) have exercised. Code outside those paths can hit a missing native or one of the shortcuts below. This page lists the ones known so far, roughly in the order a real program meets them.

## Threads take turns

Threads work, but they don't run in parallel. Each Java thread is a real Chez thread, and one global lock lets only one of them run Java code at a time ([how-it-works](how-it-works.md#threads)). A program that uses threads for correctness (a background worker, a producer and a consumer, an executor) behaves as on a JVM. One that uses them for speed gets none.

Some details follow from that. A thread that loops without calling anything gives the lock up every thousand backward branches once another thread is waiting, so a spin-wait ends, but a long recursive computation with no loop in it can keep the others waiting until it returns. A Java thread blocked in file or console I/O holds the lock while it waits. VarHandle and `Unsafe` memory-ordering modes all behave alike, which is safe for the same reason. Virtual threads need continuations, which aren't here, so `Thread.ofVirtual()` is unsupported. `getState` reports `RUNNABLE` for a thread that is blocked or waiting.

## `invokedynamic` and method handles

Both work, with a few gaps. The bootstraps javac emits are compiled directly: lambdas, records, string concatenation and pattern switches. Any other bootstrap method runs for real, once per call site ([how-it-works](how-it-works.md#method-handles)).

Method handles come from the `Lookup.find*` and `unreflect*` methods, and `invokeExact`, `invoke`, `invokeWithArguments`, `asType`, `bindTo`, `asSpreader`, `asCollector` and the `MethodHandles` combinators work on them: `zero`, `empty`, `constant`, `identity`, `insertArguments`, `dropArguments`, `filterArguments`, `filterReturnValue`, `foldArguments`, `collectArguments`, `guardWithTest`, `permuteArguments`, `explicitCastArguments`, `catchException`, `throwException`, `tryFinally`, the array element handles and the three invokers, with the JVM's conversions and exception texts (`test/java/MethodHandleCombinatorCheck.java`). The loop builders (`countedLoop`, `whileLoop`, `doWhileLoop`, `iteratedLoop`, `loop`) still run the JDK's own code, which fails on these handles, usually with an `AbstractMethodError` for `MethodHandle.rebind`. `invokeExact` doesn't check that the call matches the handle's type exactly. It converts the arguments as `invoke` would, so a call the JVM rejects with `WrongMethodTypeException` runs here (`exactInvoker` does check). `asType` refuses an impossible conversion when the handle is built, with the JVM's `WrongMethodTypeException` text. `explicitCastArguments` converts the way `asType` does, so it won't narrow a `long` to an `int` as the JVM's does. Lookups don't check access either. `Lookup.defineHiddenClass` throws a `NullPointerException`, and a pattern switch label that javac builds from a dynamic constant other than a primitive class or an enum constant fails to translate.

VarHandles work too: `findVarHandle`, `findStaticVarHandle`, `unreflectVarHandle`, `arrayElementVarHandle` and `byteArrayViewVarHandle`, which is what `java.util.concurrent.atomic` and `ConcurrentHashMap` need. Each supports the access modes the JVM's would, and the rest throw `UnsupportedOperationException`. So a `final` field is read-only, a `float` has no bitwise updates, and a `byte[]` view takes only plain `get` and `set`, as on JDK 25. A call site's types are converted as `asType` would, which means widening, boxing and unboxing but never narrowing (an `int` passed for a `short` throws `WrongMethodTypeException`), and a handle from `withInvokeExactBehavior` rejects a call whose types differ with `WrongMethodTypeException`. The six coordinate combinators work (`filterValue`, `filterCoordinates`, `insertCoordinates`, `permuteCoordinates`, `collectCoordinates`, `dropCoordinates`), with the JVM's construction-time checks and messages; a derived handle supports the modes its target does. Their filters aren't screened for checked exceptions: the JVM refuses a direct handle whose method declares one and wraps any other handle so a checked exception becomes `IllegalStateException`, and champaca does neither. `byteBufferViewVarHandle` is missing.

## Reflection

Methods, fields, constructors, `Method.invoke`, `newInstance` and field get and set work, and so do annotations with their defaults, generic signatures, and member, local and anonymous classes. Record components, parameter names (real ones when the class was compiled with `javac -parameters`, else `arg0`, `arg1` as on the JVM), checked-exception lists and type annotations match the JVM's too, which is what Jackson needs to bind records. An annotation is an instance of a generated class rather than a `java.lang.reflect.Proxy`, so `Proxy.isProxyClass` says false for it, but its `toString`, `equals` and `hashCode` are the JDK's own. Field access through reflection ignores access control. `Class.getPackage` gives one `Package` per name. A classpath package carries the version attributes of the manifest of the jar its first loaded class came from, as the JVM's class loaders define it, so a package split over two jars reads the same as on the JVM. Some differences remain. A JDK package's `isSealed` says false where the JVM says true, because the JVM's JDK packages belong to named modules and every module here is unnamed. Sealing isn't enforced, so a sealed package split over two jars loads instead of throwing `SecurityException`. A jar whose manifest the JDK would refuse gets a warning on stderr and its classes load without version information, where the JVM fails to load them. A lambda's class is `champaca/Lambda$...`, shared by every call site with the same interface, so its package is `champaca` rather than its caller's. And `Package.getPackage(String)`, `Package.getPackages()` and `ClassLoader.getDefinedPackage` still find nothing.

## Native code

A JDK library load (`zip`, `net` and so on) is a no-op, because those natives are Scheme here. A Java library's own JNI code can't load at all. Natives champaca hasn't written yet fail as `:missing-native` ([debugging](debugging.md#a-missing-native)).

## The calling API

- `init!` takes effect once per process. A second call, with a different `:classpath` or not, does nothing.
- Until `save-cache!` runs, each JDK method the process compiles keeps its generated source in memory (a few KB per method) so a save can write it. A long-lived program should call `save-cache!` once its working set is warm, which also empties that list.
- The user classpath is searched before the JDK's modules, the reverse of Java's order.
- There are getters for fields but no setters.
- Every conversion copies. A Java method that changes a collection you passed it changes the copy, and a big collection is copied again on each call ([data-conversion](data-conversion.md#conversions-copy)).
- `->clj` returns map keys as strings, with no option to turn them into keywords.
- A vector passed to a plain `Object` parameter becomes an array, not a `List`.
- A Java object prints as `#object[:object]` in jolt. Use `ldk/to-string`.
- A Java string can hold a lone surrogate (half of a UTF-16 pair) and a jolt string can't, so one comes back to jolt with U+FFFD in its place. Inside Java nothing is lost: string constants, concatenation and `switch` keep the code units exactly.

## Speed

The startup cache makes boot quick, and plain computation now runs within a few times of the JVM: `test/java/Bench.java` spends about 0.17 s computing what the JVM does in about 0.04 s. Calls, allocation, `try` blocks and synchronization cost several to many times more than on the JVM ([performance](performance.md#the-generated-code) has the numbers). The startup cache still slows a little as generations pile up (a cache filled by the whole test suite costs a warm `Hello` a few tens of milliseconds), though `state.img` entries now parse only when a run touches their class.

## Other gaps

- `java.nio.file` needs macOS, or Linux with glibc 2.33 or newer, on arm64 or x86_64. The file natives read `struct stat`, `struct dirent`, `struct flock` and `struct statvfs` through a table of offsets measured on each of those systems, and call `stat`, which glibc only exports itself since 2.33 (RHEL 8 and Debian 11 are older). On anything else, a BSD, musl or an older glibc, `UnixNativeDispatcher.init` throws `UnsupportedOperationException` instead of guessing. Only Ubuntu 26.04 (glibc 2.43) has been tested. `java.io` streams and `RandomAccessFile` don't depend on those layouts and work anywhere libc does. On Linux the birth time comes from `statx`, as the JVM's does.
- Linux's container support is off, as with `-XX:-UseContainerSupport`: inside a container champaca reports the host's memory and processor count, where the JVM reports the container's limits.
- No selectors: `Selector.open()` stops at a missing native, `KQueue`'s on macOS and `EPoll`'s on Linux, so non-blocking channels don't work. File watching works on macOS, where the JDK's `WatchService` polls in plain Java, but not on Linux, where it needs inotify natives champaca doesn't have yet.
- `Files.getFileStore` and the other mount queries have no natives yet.
- `available()` on a pipe or a terminal is 0, where the JVM asks the kernel how much is waiting.
- `Unsafe.copyMemory` between native memory and a `float[]` or `double[]` fails, since those arrays hold Scheme flonums rather than bytes.
- Resources from the JDK's own modules can be read as streams but have no URL: `getResource` answers `file:` and `jar:` URLs for the classpath only.
- `sun.misc.Unsafe` runs with `sun.misc.unsafe.memory.access=allow`, because the default warning finds its caller through `StackWalker`, which has no natives yet.
- No `module-info` checks and no export rules: every class sits in one unnamed module.
- Stack traces under `CHAMPACA_BT` name methods by descriptor and have no line numbers.
- A `NullPointerException` raised in champaca's own Scheme code, which stands in for some of the JDK's Java (the method handle layer, for one), has a `null` message where the JVM's might describe the failing bytecode. One the program's or the JDK's bytecode raises carries the JVM's message, such as `Cannot invoke "String.length()" because "<local1>" is null` (`test/java/NpeCheck.java`).

