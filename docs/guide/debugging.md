# Debugging

## Switches

Each switch is an environment variable, read when the process starts. `STATS` and `PROF` report from `champaca.main`; the rest also apply when jolt code calls Java through `champaca.api`.

| Variable | What it does |
|---|---|
| `CHAMPACA_BT=1` | keeps a shadow stack of Java frames, so an uncaught exception or a missing native prints where it happened |
| `CHAMPACA_STATS=1` | prints how many classes were loaded, methods compiled and cached units loaded, after boot and at exit |
| `CHAMPACA_PROF=1` | prints how long each boot phase took |
| `CHAMPACA_TRACE=1` | logs each method as it compiles and writes all the generated Scheme to `target/jit/trace.ss` |
| `CHAMPACA_CALLER_DEBUG=1` | prints the frames each `Reflection.getCallerClass` call sees |
| `CHAMPACA_CACHE=off` | ignores the startup cache ([performance](performance.md)) |
| `CHAMPACA_CALL_CACHE=off` | chooses the overload again on every call ([performance](performance.md#the-call-site-cache)) |

## Java stack traces

By default an uncaught exception prints just its `toString`, because recording frames costs time on every call. With `CHAMPACA_BT=1` you get the frames, innermost first. For the `Boom` program from [running-java-programs](running-java-programs.md):

```console
$ CHAMPACA_BT=1 jolt -M -m champaca.main classes Boom
3
Exception in thread "main" java.lang.NumberFormatException: For input string: "four"
	at java/lang/Throwable.fillInStackTrace()Ljava/lang/Throwable;
	at java/lang/Throwable.<init>(Ljava/lang/String;)V
	at java/lang/Exception.<init>(Ljava/lang/String;)V
	at java/lang/RuntimeException.<init>(Ljava/lang/String;)V
	at java/lang/IllegalArgumentException.<init>(Ljava/lang/String;)V
	at java/lang/NumberFormatException.<init>(Ljava/lang/String;)V
	at java/lang/NumberFormatException.forInputString(Ljava/lang/String;I)Ljava/lang/NumberFormatException;
	at java/lang/Integer.parseInt(Ljava/lang/String;I)I
	at java/lang/Integer.parseInt(Ljava/lang/String;)I
	at Boom.parse(Ljava/lang/String;)I
	at Boom.total([Ljava/lang/String;)I
	at Boom.main([Ljava/lang/String;)V
```

Frames name the method by its JVM descriptor and carry no line numbers. The JVM hides the frames of the exception's own constructors, and champaca shows them, so the frame you care about sits a few lines further down than you'd expect. The trace stops at 25 frames.

`CHAMPACA_BT` changes the generated code, so it gets its own startup cache. The first run with it on is a cold one.

## Errors on the jolt side

Calls through `champaca.api` raise an `ex-info` of one of three kinds:

| Kind | How to tell | Message |
|---|---|---|
| a Java exception | `(:java/exception (ex-data e))` is the exception object | the exception's `toString` |
| no overload fits, or two fit equally | `(:champaca/error (ex-data e))` is `:lookup` | the method name and every candidate's descriptor |
| a JDK native champaca lacks | `(:champaca/error (ex-data e))` is `:missing-native` | `champaca: missing native <class>.<name><descriptor>` |

The first two are covered in [calling-java](calling-java.md#exceptions).

### A missing native

The JDK declares many of its methods `native` and leaves them to the JVM. Champaca writes the ones the tested paths reach in Scheme, in `resources/champaca/natives.ss` (and `reflect.ss` for reflection), and adds more as programs need them. Reaching one that isn't there looks like this:

```text
champaca: missing native java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;
```

That one was real; this guide turned it up. The fix was one line:

```scheme
(defnative "java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;" (this) #f)
```

`defnative` takes the method's full name and descriptor, then its parameters (`this` first for an instance method), then a Scheme body. In the body Java `null` is `#f`, `int`s and `long`s are exact integers, a `boolean` is 0 or 1, and objects are the runtime's own records. The comment at the top of `runtime.ss` lists the whole mapping. Run with `CHAMPACA_BT=1` to see which Java code made the call, and read the JDK's source for the native's contract: `$JAVA_HOME/lib/src.zip` has it.

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
