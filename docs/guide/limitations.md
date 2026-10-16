# Limitations

Champaca is a spike. It runs the JDK's own code, but only along the paths its tests and the four checked libraries (commons-lang3, gson, jsoup, Jackson) have exercised. Code outside those paths can hit a missing native or one of the shortcuts below. This page lists the ones known so far, roughly in the order a real program meets them.

## One thread

`Thread.start` returns without running the thread's body, and `isAlive` is `false` right after it. `synchronized`, `wait` and `notify` are no-ops. So code that takes locks on a single thread runs, and code that waits for another thread to do something won't get it. A shutdown hook never runs, since it's a thread too. Executors fail sooner: creating a single-thread executor and submitting a task to it stops on a missing native, `jdk/internal/vm/ContinuationSupport.isSupported0()Z`. VarHandle and `Unsafe` memory-ordering modes all behave alike, which is fine with one thread and will need revisiting along with threads.

## `invokedynamic` and method handles

Lambdas and records work because the translator recognises their bootstrap methods (`LambdaMetafactory`, `ObjectMethods`) and generates the class or method directly, and string concatenation is compiled the same way. Any other bootstrap method fails with `invokedynamic bootstrap not in spike`. `MethodHandle.invoke`, `invokeExact` and the `Lookup.find*` methods for methods aren't there either. VarHandles are: `findVarHandle`, `findStaticVarHandle`, `arrayElementVarHandle` and `byteArrayViewVarHandle` all work, with every access mode, which is what `java.util.concurrent.atomic` and `ConcurrentHashMap` need. Their coordinate combinators and `byteBufferViewVarHandle` are missing.

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
- `sun.misc.Unsafe` runs with `sun.misc.unsafe.memory.access=allow`, because the default warning finds its caller through `StackWalker`, which has no natives yet.
- No `module-info` checks and no export rules: every class sits in one unnamed module.
- Stack traces under `CHAMPACA_BT` name methods by descriptor and have no line numbers.

