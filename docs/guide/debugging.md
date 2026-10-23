# Debugging

## Switches

Each switch is an environment variable, read when the process starts. `STATS` and `PROF` report from `champaca.main`; the rest also apply when jolt code calls Java through `champaca.api`.

| Variable | What it does |
|---|---|
| `CHAMPACA_BT=1` | keeps a shadow stack of Java frames, so an uncaught exception prints where it happened; names each native champaca lacks on stderr as it's hit; says why an NPE got no message |
| `CHAMPACA_STATS=1` | prints how many classes were loaded, methods compiled and cached units loaded, after boot and at exit |
| `CHAMPACA_PROF=1` | prints how long each boot phase took |
| `CHAMPACA_TRACE=1` | logs each method as it compiles and writes all the generated Scheme to `target/jit/trace.ss` |
| `CHAMPACA_CALLER_DEBUG=1` | prints the frames each `Reflection.getCallerClass` call sees |
| `CHAMPACA_NPE_STRICT=1` | throws when an NPE's message can't be built, where a normal run gives a `null` message as HotSpot does; `bb test` and `bb libs` set it |
| `CHAMPACA_CACHE=off` | ignores the startup cache ([performance](performance.md)) |
| `CHAMPACA_CALL_CACHE=off` | chooses the overload again on every call ([performance](performance.md#the-call-site-cache)) |

## Java stack traces

An exception carries its stack trace as on the JVM: `printStackTrace`, `getStackTrace`, `Thread.currentThread().getStackTrace()`, `StackWalker` and the source location `java.util.logging` infers all work, and an uncaught exception prints its trace on stderr the way the JVM's default handler does. For the `Boom` program from [running-java-programs](running-java-programs.md):

```console
$ jolt -M -m champaca.main classes Boom
3
Exception in thread "main" java.lang.NumberFormatException: For input string: "four"
	at java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java)
	at java.base/java.lang.Integer.parseInt(Integer.java)
	at java.base/java.lang.Integer.parseInt(Integer.java)
	at Boom.parse(Boom.java:2)
	at Boom.total(Boom.java:3)
	at Boom.main(Boom.java:6)
```

Your own frames carry their line numbers, which come from the class file's `LineNumberTable` through Chez's record of each frame's pending call ([how-it-works](how-it-works.md#stack-traces)). JDK frames name their file but no line: the startup cache compiles JDK code without that record, because keeping it cost 0.02 to 0.04 s on every warm start ([performance](performance.md#exception-handlers)). Two other differences from the JVM: a native champaca wrote in Scheme has no frame, where the JVM prints `Native Method`, and the stacks of other threads aren't available.

`CHAMPACA_BT=1` is for errors in champaca itself. It keeps a shadow stack of Java frames, so a Chez error inside a native, which has no Java trace, still prints where in the Java program it happened, and it names each missing native on stderr as it's hit. It changes the generated code, so it gets its own startup cache, and the first run with it on is a cold one.

## Errors on the jolt side

Calls through `champaca.api` raise an `ex-info` of one of two kinds:

| Kind | How to tell | Message |
|---|---|---|
| a Java exception | `(:java/exception (ex-data e))` is the exception object | the exception's `toString` |
| no overload fits, or two fit equally | `(:champaca/error (ex-data e))` is `:lookup` | the method name and every candidate's descriptor |

Both are covered in [calling-java](calling-java.md#exceptions). A native champaca lacks is the first kind: it throws `java.lang.UnsatisfiedLinkError` named after the native, as below.

### A missing native

The JDK declares many of its methods `native` and leaves them to the JVM. Champaca writes the ones the tested paths reach in Scheme, in `resources/champaca/natives.ss` (and `reflect.ss` for reflection, `files.ss` for files), and adds more as programs need them. Calling one that isn't there throws `UnsatisfiedLinkError`, which is what the JVM throws for a `native` method whose library was never loaded, so a library that wraps an optional feature in a try/catch keeps going. Uncaught, it ends the program like any other exception:

```text
Exception in thread "main" java.lang.UnsatisfiedLinkError: java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;
```

With `CHAMPACA_BT=1` the name is also printed on stderr the moment the native is hit, whether or not something catches the error, which is the quickest way to list what a new library needs. That example was real; this guide turned it up. The first fix was one line, answering null, which is what HotSpot answers when it has nothing to say (the JVM's full message came later, in `npe.clj`):

```scheme
(defnative "java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;" (this) #f)
```

`defnative` takes the method's full name and descriptor, then its parameters (`this` first for an instance method), then a Scheme body. In the body Java `null` is `#f`, `int`s and `long`s are exact integers, a `boolean` is 0 or 1, and objects are the runtime's own records. The comment at the top of `runtime.ss` lists the whole mapping. Run with `CHAMPACA_BT=1` to see which Java code made the call, and read the JDK's source for the native's contract: `$JAVA_HOME/lib/src.zip` has it.

### A class that won't load

A class the translator can't handle (an opcode it has no case for, an attribute it can't parse, a superclass that isn't there) is a Scheme error when a call first reaches it, reported as `champaca: Scheme error: ...` with the Java frames under `CHAMPACA_BT=1`. Loaded by name instead, through `Class.forName` or a class loader's `loadClass`, it throws `java.lang.NoClassDefFoundError` with that Scheme error's text in its message, the way the JVM throws a `LinkageError` for a class it can't define, so the cause reads off the exception. Only a class that isn't there at all is a `ClassNotFoundException`. A library that probes for an optional class with `Class.forName` inside a `catch (Throwable)` or `catch (LinkageError)` goes on to its fallback; one that catches only `ClassNotFoundException` stops at the error, which names the class to fix.

## Looking at generated code

`CHAMPACA_TRACE=1` prints a `jit` line for each method it compiles and writes everything it fed to Chez, the runtime included, to `target/jit/trace.ss`. `Hello.main` comes out as:

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

(The file has it on one line; it's indented here.) `l0` is local 0, `s0` and `s1` are stack slots, `ic0` remembers that `System` is initialized, `ic1` is the `println` call's inline cache, and `#B0` is the method's only basic block. [how-it-works](how-it-works.md) explains the shape.

A method compiled from the startup cache doesn't go through the translator, so it won't appear. Add `CHAMPACA_CACHE=off` to see everything.

## Timing a boot

```console
$ CHAMPACA_PROF=1 jolt -M -m champaca.main target/classes Hello
champaca prof (boot, cache hit): wall 233.87 ms
  cache-load-code 87.56 ms
  cache-read-state 24.84 ms
  java+runtime (rest) 121.46 ms
Hello, World!
```

On a cache miss the phases are parsing, registering classes, code generation, Chez compilation and writing the cache.
