# How it works

Champaca follows [OpenLDK](https://github.com/atgreen/openldk), Anthony Green's Java runtime for SBCL: translate JVM bytecode into Lisp source and let the Lisp compiler turn it into machine code. Here the translator is jolt Clojure, the Lisp is Chez Scheme, and `jolt.scheme/eval-string` compiles the generated text natively inside the running jolt process. There's no interpreter and no second runtime. A Java object is a Chez record in jolt's own heap, and a Java method is a Chez procedure.

## From class file to procedure

1. **Find the bytes.** `main.clj` looks for `pkg/Name.class` on the user classpath (directories and jars), then in the JDK module that owns the package ([jars-and-modules](jars-and-modules.md)).
2. **Parse.** `classfile.clj` reads the constant pool, fields (with `ConstantValue`), methods and their `Code`, `BootstrapMethods`, and the attributes reflection needs: signatures, annotations, inner-class entries.
3. **Register.** `main.clj` lays out the instance fields into slots, builds the vtable (interface default methods included) and registers the class with the runtime. Every method gets a stub as its top-level definition, named `Class.name(descriptor)`.
4. **Compile on first call.** The stub's first call translates that one method with `codegen.clj`, hands the text to Chez, and replaces its own definition with the compiled procedure. Later calls go straight to the compiled code. A method that's never called is never translated.

Classes load lazily too: a class is found and parsed the first time something names it, and its static initializer runs on first active use, superclass first, as the JVM specifies.

## The shape of a translated method

Each local becomes a Scheme variable `l0`, `l1`, ... and each operand-stack slot becomes `s0`, `s1`, .... The translator tracks the stack's types at compile time, so it knows what every slot holds. The method's code splits into basic blocks, each a `letrec`-bound thunk, and a jump is a tail call, which Chez compiles to a plain jump. `Hello.main` is a single block:

```scheme
(define |Hello.main([Ljava/lang/String;)V|
  (lambda (l0)
    (let ((s0 0) (s1 0))
      (letrec ((|Hello.main([Ljava/lang/String;)V#B0|
                (lambda ()
                  (begin (jinit "java/lang/System")
                         (set! s0 |java/lang/System:out|)
                         (set! s1 (jstring-lit "Hello, World!"))
                         ((jvirt s0 "println(Ljava/lang/String;)V") s0 s1)
                         (void)))))
        (|Hello.main([Ljava/lang/String;)V#B0|)))))
```

`getstatic` became `jinit` plus a top-level variable, and `invokevirtual` became a vtable lookup (`jvirt`) followed by a call. A static call is a call to the target's top-level name.

Exception handlers need one change to that shape. A Scheme `guard` around a block would also cover every block that block tail-calls into, which is wrong for Java's exception ranges. So a method with an exception table runs as a trampoline instead: each block returns the next block rather than calling it, a small loop calls whatever comes back, and the `guard` around a block covers only that block. A Java `throw` is Scheme's `raise` of the exception object itself, and a handler's `guard` clause checks the raised object's class.

Some instructions get special treatment. `invokedynamic` with `LambdaMetafactory` or `ObjectMethods` (lambdas and records) and string concatenation's `makeConcatWithConstants` are compiled directly, with no bootstrap method run. VarHandle access modes become calls into the runtime's own VarHandle table.

## Values

The comment at the top of `resources/champaca/runtime.ss` has the full mapping. In short: `int`, `short`, `byte`, `char` and `boolean` are fixnums (booleans are 0 and 1, as in bytecode), a `long` is an exact integer wrapped to 64 bits, `float` and `double` are flonums (a float rounds through IEEE single precision), `null` is `#f`, an object is a record holding its class and a slot vector, and an array is a record holding its element descriptor and a Scheme vector. A `java.lang.String` is an ordinary object with the JDK's own fields, a `byte[]` and a coder.

## Natives

The JDK leaves its `native` methods to the JVM. Champaca writes those in Scheme: `natives.ss` for the general ones (`System.arraycopy`, `Unsafe`, file descriptors, time zones and so on) and `reflect.ss` for reflection. A few Java methods are overridden outright where the JDK's own code would need machinery champaca doesn't have, such as runtime bytecode generation for annotation proxies.

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

## Calls from jolt

`champaca.api` sits on top. The descriptor-based functions call a method's procedure directly. `invoke` and friends (`dynamic.ss`) first collect candidate methods from the class's registration data, score them against the arguments, convert the arguments for the winner and call it, with the call-site cache in front of all of that. A jolt fn passed as a single-method interface becomes an instance of a generated class whose one method calls back into the fn.

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
| `resources/champaca/interop.ss` | conversions behind the descriptor-based API |
| `resources/champaca/dynamic.ss` | descriptor-free calls, overload choice, the call-site cache |
