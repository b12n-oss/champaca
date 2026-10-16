# Limitations

Champaca is a spike. It runs the JDK's own code, but only along the paths its tests and the six checked libraries (commons-lang3, gson, jsoup, Jackson, Caffeine, Guava) have exercised. Code outside those paths can hit a missing native or one of the shortcuts below. This page lists the ones known so far, roughly in the order a real program meets them.

## Threads take turns

Threads work, but they don't run in parallel. Each Java thread is a real Chez thread, and one global lock lets only one of them run Java code at a time ([how-it-works](how-it-works.md#threads)). A program that uses threads for correctness (a background worker, a producer and a consumer, an executor) behaves as on a JVM. One that uses them for speed gets none.

Some details follow from that. A thread that loops without calling anything gives the lock up every thousand backward branches once another thread is waiting, so a spin-wait ends, but a long recursive computation with no loop in it can keep the others waiting until it returns. A Java thread blocked in file or console I/O holds the lock while it waits. VarHandle and `Unsafe` memory-ordering modes all behave alike, which is safe for the same reason. Virtual threads need continuations, which aren't here, so `Thread.ofVirtual()` is unsupported. `getState` reports `RUNNABLE` for a thread that is blocked or waiting.

## `invokedynamic` and method handles

Both work, with a few gaps. The bootstraps javac emits are compiled directly: lambdas, records, string concatenation and pattern switches. Any other bootstrap method runs for real, once per call site ([how-it-works](how-it-works.md#method-handles)).

Method handles come from the `Lookup.find*` and `unreflect*` methods, and `invokeExact`, `invoke`, `invokeWithArguments`, `asType`, `bindTo` and eight combinators work on them: `constant`, `identity`, `insertArguments`, `dropArguments`, `filterReturnValue`, `guardWithTest`, `permuteArguments` and `explicitCastArguments`. The others (`filterArguments`, `foldArguments`, `catchException`, the loop builders and so on) still run the JDK's own code, which fails on these handles, usually with an `AbstractMethodError` for `MethodHandle.rebind`. `invokeExact` doesn't check that the call matches the handle's type exactly. It converts the arguments as `invoke` would, so a call the JVM rejects with `WrongMethodTypeException` runs here. `explicitCastArguments` converts the way `asType` does, so it won't narrow a `long` to an `int` as the JVM's does. Lookups don't check access either. `Lookup.defineHiddenClass` throws a `NullPointerException`, and a pattern switch label that javac builds from a dynamic constant other than a primitive class or an enum constant fails to translate.

VarHandles work too: `findVarHandle`, `findStaticVarHandle`, `arrayElementVarHandle` and `byteArrayViewVarHandle`, with every access mode, which is what `java.util.concurrent.atomic` and `ConcurrentHashMap` need. Their coordinate combinators and `byteBufferViewVarHandle` are missing.

## Reflection

Methods, fields, constructors, `Method.invoke`, `newInstance` and field get and set work, and so do annotations with their defaults, generic signatures, and member, local and anonymous classes. Missing: checked-exception lists and record components (both come back empty), type annotations and parameter names. An annotation is an instance of a generated class rather than a `java.lang.reflect.Proxy`, so its `toString`, `equals` and `hashCode` are `Object`'s. Field access through reflection ignores access control.

## Native code

A JDK library load (`zip`, `net` and so on) is a no-op, because those natives are Scheme here. A Java library's own JNI code can't load at all. Natives champaca hasn't written yet fail as `:missing-native` ([debugging](debugging.md#a-missing-native)).

## The calling API

- `init!` takes effect once per process. A second call, with a different `:classpath` or not, does nothing.
- The user classpath is searched before the JDK's modules, the reverse of Java's order.
- There are getters for fields but no setters.
- Every conversion copies. A Java method that changes a collection you passed it changes the copy, and a big collection is copied again on each call ([data-conversion](data-conversion.md#conversions-copy)).
- `->clj` returns map keys as strings, with no option to turn them into keywords.
- A vector passed to a plain `Object` parameter becomes an array, not a `List`.
- A Java object prints as `#object[:object]` in jolt. Use `ldk/to-string`.

## Speed

The startup cache makes boot quick, but generated code hasn't been tuned at all: `test/java/Bench.java` spends about 1.5 s computing what the JVM does in well under 0.1 s ([performance](performance.md#the-generated-code)). The startup cache still slows a little as generations pile up, because `state.img` holds every class any run has cached and is read whole.

## Other gaps

- File I/O covers what the tests reach. Writes, permissions and symlink handling in `java.io` and `java.nio` are thin.
- `MessageDigest` and the rest of `java.security` can't start yet. Loading the security properties opens a `java.nio.file` path, and the native filesystem behind it isn't there (Guava's `Hashing.sha256()` stops at `UnixNativeDispatcher.init`).
- `sun.misc.Unsafe` runs with `sun.misc.unsafe.memory.access=allow`, because the default warning finds its caller through `StackWalker`, which has no natives yet.
- No `module-info` checks and no export rules: every class sits in one unnamed module.
- Stack traces under `CHAMPACA_BT` name methods by descriptor and have no line numbers.

