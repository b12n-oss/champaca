# How it works

Champaca follows [OpenLDK](https://github.com/atgreen/openldk), Anthony Green's Java runtime for SBCL: translate JVM bytecode into Lisp source and let the Lisp compiler turn it into machine code. Here the translator is jolt Clojure, the Lisp is Chez Scheme, and `jolt.scheme/eval-string` compiles the generated text natively inside the running jolt process. There's no interpreter and no second runtime. A Java object is a Chez record in jolt's own heap, and a Java method is a Chez procedure.

## From class file to procedure

1. **Find the bytes.** `main.clj` looks for `pkg/Name.class` on the user classpath (directories and jars), then in the JDK module that owns the package ([jars-and-modules](jars-and-modules.md)).
2. **Parse.** `classfile.clj` reads the constant pool, fields (with `ConstantValue`), methods and their `Code`, `BootstrapMethods`, and the attributes reflection needs: signatures, annotations, inner-class entries.
3. **Register.** `main.clj` lays out the instance fields into slots, builds the vtable and registers the class with the runtime. Every method gets a stub as its top-level definition, named `Class.name(descriptor)`. A method no class in the chain declares gets an interface's default the way the JVM picks one: of the superinterfaces that declare it, the most specific, so `B`'s default wins over `A`'s when `B` extends `A`, whatever order a class lists them in.
4. **Compile on first call.** The stub's first call translates that one method with `codegen.clj`, hands the text to Chez, and replaces its own definition with the compiled procedure. Later calls go straight to the compiled code. A method that's never called is never translated.

Classes load lazily too: a class is found and parsed the first time something names it, and its static initializer runs on first active use, superclass first, as the JVM specifies.

## The shape of a translated method

Each local becomes a Scheme variable `l0`, `l1`, ... and each operand-stack slot becomes `s0`, `s1`, .... The translator tracks the stack's types at compile time, so it knows what every slot holds. The method's code splits into basic blocks, each a `letrec`-bound procedure that takes the method's live locals and whatever the stack holds on entry. A jump is a tail call passing the current values, which Chez compiles to a plain jump. Inside a block nothing is assigned: a store or a push binds a fresh variable in a `let`, shadowing the old one. That matters because Chez keeps an assigned variable in a heap box, even when no closure captures it, while a `let`-bound one can live in a register. `Hello.main` is a single block:

```scheme
(define |Hello.main([Ljava/lang/String;)V|
  (let ((ic0 (cons #f #f)) (ic1 (cons #f #f)))
    (lambda (l0)
      (letrec ((|Hello.main([Ljava/lang/String;)V#B0|
                (lambda (l0)
                  (begin (jinit-ic ic0 '|java/lang/System|)
                         (let ((s0 |java/lang/System:out|))
                           (let ((s1 (jstring-lit "Hello, World!")))
                             (begin ((jvirt-ic ic1 s0 '|println(Ljava/lang/String;)V|) s0 s1)
                                    (void))))))))
        (|Hello.main([Ljava/lang/String;)V#B0| l0)))))
```

`getstatic` became an initialization check plus a top-level variable. The check goes through a cell of its own, `ic0`, which holds the class once it's initialized, so after the first time it costs one `car`. `invokevirtual` became a call through `jvirt-ic`, an inline cache: `ic1` remembers the receiver class this call site saw last and the procedure it found for it, so a repeat call costs one `eq?` test, and a new class looks the method up in that class's vtable, keyed by interned symbols. A static call is a call to the target's top-level name. `new`, `instanceof` and `checkcast` get cells too: `new` keeps the initialized class, and the two type tests keep the last class they saw with its answer, since the full test compares class names up the hierarchy. A store into a reference array (`aastore`) gets one as well. It checks the value against the array's element type as the JVM does, throwing `ArrayStoreException`. The cell remembers the element type it last stored into and up to two kinds of value that passed (an object's class, or an array value's element type), so a repeat costs a few `eq?` tests. That works across arrays because each element descriptor is interned: every `String[]` shares one descriptor string, and a method interns the ones it allocates with once, when it's defined. An `Object[]` takes any value, so its cell skips the value test.

Some calls skip dispatch. An `invokevirtual` of a private or final method, or on a final class like `String`, can only reach one method, so it becomes a direct call after a null check. So does an `invokeinterface` of a private interface method, which is how javac calls one from a default method, and which no class can override. And a small leaf method called directly (no calls of its own but `Object`'s empty constructor, no loops, no exception handlers, at most 40 bytes of bytecode) is compiled into its caller as a `let`-bound procedure, which Chez calls without a lookup or inlines. Only a callee in the caller's own class or in the JDK qualifies, because compiled code is cached per class and a copy of another class's code would go stale when that class changes. Which JDK methods get inlined also depends on which classes the run had already parsed. Every choice gives the same results, just at different speeds.

Exception handlers change the shape a little. A handler can't just wrap a block, because a Scheme handler around a block also covers every block that block tail-calls into, which is wrong for Java's exception ranges. So the blocks of a method with an exception table fall into regions, one for each list of handlers that covers them, and jumps inside a region are tail calls as above. Entering a region from outside goes through an entry procedure that pushes one handler for the whole region onto Chez's handler stack (`jtry` in `runtime.ss`). A jump that leaves a region returns a thunk rather than calling its target, and a small trampoline loop calls whatever comes back, so the region's handler is gone before the target runs. A loop inside a `try` therefore runs as fast as one outside it, and only entering the `try` costs anything.

A handler gets the exception and each local's value from when its region was entered. That value is stale only for a local the region assigns and the handler then reads, which a liveness pass over the blocks finds. Those few locals stay method-wide variables assigned with `set!`, and everything else stays in `let`s. A Java `throw` is Scheme's `raise` of the exception object itself, and the handler checks the raised object's class against the exception table's entries in order. When none matches, it passes the exception on to the next handler from where it was raised, so nothing is unwound until some handler takes it.

Some instructions get special treatment. `invokedynamic` with `LambdaMetafactory` or `ObjectMethods` (lambdas and records), string concatenation's `makeConcatWithConstants`, and the `typeSwitch` and `enumSwitch` behind pattern switches are compiled directly, with no bootstrap method run. VarHandle access modes become calls into the runtime's own VarHandle table, and `invokeExact` and `invoke` on a method handle into its handle table ([Method handles](#method-handles)).

## Values

The comment at the top of `resources/champaca/runtime.ss` has the full mapping. In short: `int`, `short`, `byte`, `char` and `boolean` are fixnums (booleans are 0 and 1, as in bytecode), a `long` is an exact integer wrapped to 64 bits, `float` and `double` are flonums (a float rounds through IEEE single precision), `null` is `#f`, an object is a record holding its class, a slot vector and its monitor once something locks it, and an array is a record holding its element descriptor and a Scheme vector. A `java.lang.String` is an ordinary object with the JDK's own fields, a `byte[]` and a coder.

## Natives

The JDK leaves its `native` methods to the JVM. Champaca writes those in Scheme: `natives.ss` for the general ones (`System.arraycopy`, `Unsafe`, time zones and so on), `reflect.ss` for reflection, and `files.ss` for files. A few Java methods are overridden outright where the JDK's own code would need machinery champaca doesn't have, such as runtime bytecode generation for annotation proxies.

The file natives call libc through Chez's foreign interface (`foreign-procedure`), on real file descriptors, the way the JDK's C code does. The JDK hands its NIO natives paths as addresses of C strings it has copied into native memory with `Unsafe`, so `Unsafe`'s memory calls work on real `malloc`'d memory, and a path's address goes to libc unchanged. The console is the exception: `System.in`, `System.out` and `System.err` keep going through Chez's own ports, which jolt shares, so Java's and jolt's output stay in order. A channel writing to file descriptor 1 or 2 calls libc directly, after flushing those ports. `java.util.zip` calls zlib the same way.

Resources aren't native, but the JDK finds its own in the runtime image, which champaca doesn't read. So `getResourceAsStream`, `getResource` and `getResources` are overridden to ask the loader, which looks in the JDK's jmods first and then the classpath, as it does for classes. A classpath resource gets a `file:` or `jar:` URL, which the JDK's own URL handlers then open.

## Booting the JDK

`init!` runs the JDK's own startup code in the order HotSpot does, because the JDK depends on that order:

1. Load the runtime, natives and reflection support.
2. Make sure `Object`, `String` and `Class` are registered.
3. Run `String.<clinit>` first. It sets `COMPACT_STRINGS`, and string hashes computed before that take the UTF-16 path, so the system property map would be keyed by the wrong hashes.
4. Run `System.<clinit>`, then build the main thread and its thread group.
5. Run `Reference.<clinit>` and `AccessibleObject.<clinit>`, which install the shared-secrets hooks `initPhase1` reaches for.
6. Run `System.initPhase1()`, which sets up properties, `System.out` and `System.err`.
7. Give every `Class` one unnamed `Module`.

After that, `System.out.println` goes through the JDK's own `PrintStream`, `StreamEncoder` and UTF-8 encoder down to a native `FileOutputStream.writeBytes`. The startup cache ([performance](performance.md)) saves the result of all this, so a warm start loads it rather than repeating it.

## Threads

`Thread.start0` forks a Chez thread, which runs the thread's `run()` and then does what HotSpot does on the way out: an uncaught exception goes to `dispatchUncaughtException`, then `Thread.exit()`, then the thread is marked terminated and its monitor notified, which is what `join` waits on.

One global lock (the GIL, in `natives.ss`) lets one thread at a time run Java code, so the class table, the JIT and the runtime's own hashtables need no locks of their own. A thread gives it up whenever it blocks: entering a monitor another thread holds, `wait`, `sleep`, `LockSupport.park`, or a class another thread is still initializing. Generated code also checks at every backward branch whether some thread is waiting for the lock, and after a thousand such checks the running thread lets it go first. A monitor has an owner, a count and a wait set. An object keeps it in a field of its own record, which costs no memory since Chez rounds a record up to the same 32 bytes either way, and an array's sits in a weak table keyed by the array. Only the thread holding the GIL runs Java code, so a monitor's owner is compared against the GIL's owner. The values the runtime keeps in globals for the running thread (the current `java.lang.Thread`, the `CHAMPACA_BT` shadow stack) are saved and restored as the lock changes hands.

When `main` returns, `champaca.main` waits for the other non-daemon threads and then calls `Shutdown.shutdown()`, which runs the shutdown hooks, as the `java` launcher does.

## Method handles

The JDK builds a method handle from a `MemberName` the VM resolves and a `LambdaForm` it compiles to a hidden class, and neither exists here. So `handles.ss` replaces the layer above them. A method handle is a bare `MethodHandle` object, allocated without running a constructor, whose `type` field holds a real `MethodType`. A weak table maps it to a Scheme procedure and the descriptor that procedure takes. The `Lookup.find*` and `unreflect*` methods, `asType`, `bindTo`, `invokeWithArguments` and the common `MethodHandles` combinators are overridden to build and combine those. Everything else in `java.lang.invoke`, `MethodType` included, is the JDK's own code.

A call to `invokeExact` or `invoke` carries the call site's descriptor, and the runtime converts each argument and the result between that descriptor and the handle's: boxing, unboxing and primitive widening. When the two match, which is the usual case, nothing is converted.

A pattern switch compiles to a call that walks its case labels in order (`jswitch-index`), since a label is only a class, a string, an integer or an enum constant. An `invokedynamic` with any other bootstrap runs that bootstrap on its first call: a `Lookup` on the calling class, the call's name and `MethodType`, then the static arguments, which codegen writes out as data. The runtime keeps the `CallSite` it returns for that call site. A `ConstantCallSite`'s target is kept directly, and any other kind is asked for its current target on each call, so `MutableCallSite.setTarget` takes effect. An `ldc` of a method handle, a method type or a dynamic constant resolves the same way, once per site.

## Calls from jolt

`champaca.api` sits on top. Each call takes the lock for as long as it runs Java and releases it on return, so Java threads keep running while jolt code does something else. The descriptor-based functions call a method's procedure directly. `invoke` and friends (`dynamic.ss`) first collect candidate methods from the class's registration data, score them against the arguments, convert the arguments for the winner and call it, with the call-site cache in front of all of that. A jolt fn passed as a single-method interface becomes an instance of a generated class whose one method calls back into the fn.

## Files

| File | Job |
|---|---|
| `src/champaca/classfile.clj` | parses `.class` files |
| `src/champaca/codegen.clj` | one method's bytecode to a Scheme `define` |
| `src/champaca/main.clj` | class loading, layout, vtables, boot, the startup cache, `champaca.main` |
| `src/champaca/api.clj` | the jolt-facing API |
| `resources/champaca/runtime.ss` | objects, arrays, exceptions, strings, `Class` mirrors, lambdas, records |
| `resources/champaca/natives.ss` | JDK native methods and a few overrides |
| `resources/champaca/reflect.ss` | reflection natives and `getCallerClass` |
| `resources/champaca/handles.ss` | method handles, bootstraps run for real, pattern switches |
| `resources/champaca/files.ss` | file, channel and zlib natives through libc |
| `resources/champaca/interop.ss` | conversions behind the descriptor-based API |
| `resources/champaca/dynamic.ss` | descriptor-free calls, overload choice, the call-site cache |
