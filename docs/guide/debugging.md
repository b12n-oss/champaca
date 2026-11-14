# Debugging

## Switches

Each switch is an environment variable read at process start. `STATS` and `PROF` report from `champaca.main`; the rest also apply when jolt code calls Java through `champaca.api`.

| Variable | What it does |
|---|---|
| `CHAMPACA_BT=1` | keeps a shadow stack of Java frames, so an uncaught exception prints where it happened; names each native champaca lacks on stderr as it's hit, and each class or method a translated method names that isn't there; says why an NPE got no message |
| `CHAMPACA_STATS=1` | prints how many classes were loaded (and how many of them came from the library cache), methods compiled (and how many of classpath classes) and cached units loaded, after boot and at exit; when a loader of the program's own defined classes, a second line says how many weren't restored from the cache and why (new, changed, or depending on a class the cache can't check) and how many classes had their cached methods dropped for a changed dependency |
| `CHAMPACA_PROF=1` | prints how long each phase took, after boot and again when `main` returns |
| `CHAMPACA_TRACE=1` | logs each method as it compiles and writes all the generated Scheme to `target/jit/trace.ss` |
| `CHAMPACA_STRUCTURED=1` | rewrites each method's generated code into a form meant for reading before compiling it, see [structured method code](#structured-method-code); `CHAMPACA_STRUCTURED_TRACE=<file>` appends each rewritten method to that file |
| `CHAMPACA_CALLER_DEBUG=1` | prints the frames each `Reflection.getCallerClass` call sees |
| `CHAMPACA_NPE_STRICT=1` | throws when an NPE's message can't be built, where a normal run gives a `null` message as HotSpot does; `jolt test` and `jolt libs` set it |
| `CHAMPACA_CACHE=off` | ignores the startup cache ([performance](performance.md)) |
| `CHAMPACA_CACHE_DIR=<dir>` | puts the startup cache there instead of `~/.cache/champaca` ([performance](performance.md)) |
| `CHAMPACA_BUNDLE=<dir>` | marks a run as an app bundle's: the cache key hashes the runtime image's index instead of its mtime, and class path entries inside the directory are named relative to it, so a moved bundle keeps its cache; the bundle's launcher sets it ([app-bundles](app-bundles.md)) |
| `CHAMPACA_USER_CACHE_DIR=<dir>` | where a bundle whose own cache can't be written keeps a writable copy of it, one directory per bundle path and one copy per version of its cache (default `~/.cache/champaca/bundles`; `off` for none: the bundle's cache is then only read) ([app-bundles](app-bundles.md#read-only-bundles)) |
| `CHAMPACA_CLASSPATH=<entries>` | the class path `init!` uses when it's given no `:classpath`, separated as a class path is; a `--jolt` bundle's launcher sets it ([app-bundles](app-bundles.md)) |
| `CHAMPACA_LINES=all` | keeps line numbers for the frames of classes from jars and of classes a loader of the program's own defines, not just for classes from a class directory, at about the memory their code takes ([performance](performance.md#memory)); `off` drops them for your directory classes too. Part of the cache key |
| `CHAMPACA_PARSED_LIMIT=<n>` | how many parsed class files the process keeps for compiling more of their methods, the most recently used (default 256); a dropped one is parsed again from its bytes when needed, so 1 exercises that path on nearly every compile ([performance](performance.md#memory)) |
| `CHAMPACA_CALL_CACHE=off` | chooses the overload again on every call ([performance](performance.md#the-call-site-cache)) |

## Java stack traces

An exception carries its stack trace as on the JVM. `printStackTrace`, `getStackTrace`, `Thread.currentThread().getStackTrace()`, `StackWalker` and the source location `java.util.logging` infers all work, and an uncaught exception prints its trace on stderr as the JVM's default handler does. For the `Boom` program from [running-java-programs](running-java-programs.md):

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

Your own frames carry their line numbers when their classes come from a directory on the class path, which is how a build you're working on arrives. The lines come from the class file's `LineNumberTable` through Chez's record of each frame's pending call ([how-it-works](how-it-works.md#stack-traces)).

JDK frames name their file but no line, because the startup cache compiles JDK code without that record: keeping it cost 0.02 to 0.04 s on every warm start ([performance](performance.md#exception-handlers)). By default the same goes for classes from jars, and for classes a loader of the program's own reads from a jar and defines, since the record costs about as much memory as the code. A warm Spring Boot held 100 MB of it in a 460 MB heap. `CHAMPACA_LINES=all` keeps it for them as well ([performance](performance.md#memory)).

A class generated at run time keeps its lines, meaning one defined with no code source location, as `ClassLoader.defineClass(name, b, off, len)` gives. A Clojure REPL defines what it evaluates that way, so `Execution error ... (REPL:1)` reads as on HotSpot (`test/java/GeneratedLinesCheck.java`). Clojure defines such a class for every function of a library it loads from source, so those keep their lines too. Five source-only libraries (core.logic, core.match, test.check, data.json, tools.reader) took a warm run's peak RSS from 974 to 1046 MB and its cache from 68 to 76 MB, at the same speed. `CHAMPACA_LINES=off` gives that back.

Without the lines a frame can go missing too. When a method of a class without them calls nothing and throws from one of champaca's own checks (a division by zero, an array index, a cast, an array's size) while inlined into its caller, Chez has folded the inlined code into the caller's, so the trace shows the caller's frame where the JVM shows the method's own. `test/libcache/StackCheck-lines-off.expected` has the shape, and `CHAMPACA_LINES=all` restores the frame along with the lines.

Two other differences from the JVM: a native champaca wrote in Scheme has no frame, where the JVM prints `Native Method`, and another thread's stack starts at the Java method that blocked, without the native under it ([limitations](limitations.md#native-code)).

`CHAMPACA_BT=1` is for errors in champaca itself. It keeps a shadow stack of Java frames, so a Chez error inside a native, which has no Java trace, still prints where in the Java program it happened, and it names each missing native on stderr as it's hit. It changes the generated code, so it gets its own startup cache, and the first run with it on is a cold one.

## Errors on the jolt side

Calls through `champaca.api` mostly raise an `ex-info` of one of two kinds:

| Kind | How to tell | Message |
|---|---|---|
| a Java exception | `(:java/exception (ex-data e))` is the exception object | the exception's `toString` |
| no overload fits, two fit equally, or champaca refuses the call (a final field, a bad class definition) | `(:champaca/error (ex-data e))` is `:lookup` | the method name and every candidate's descriptor, or the reason |

Both are covered in [calling-java](calling-java.md#exceptions), with the few errors outside them (`proxy`'s and `->clj`'s own, and a receiver that isn't a Java value). A native champaca lacks is the first kind: it throws `java.lang.UnsatisfiedLinkError` named after the native, as below.

### A missing native

The JDK declares many of its methods `native` and leaves them to the JVM. Champaca writes the ones the tested paths reach in Scheme, in `resources/champaca/natives.ss` (and `reflect.ss` for reflection, `files.ss` for files, `net.ss` for sockets, `spawn.ss` for processes), and adds more as programs need them. Calling one that isn't there throws `UnsatisfiedLinkError`, as the JVM does for a `native` method whose library was never loaded, so a library that wraps an optional feature in a try/catch keeps going. Uncaught, it ends the program like any other exception. Before champaca had helpful `NullPointerException` messages, printing one ended like this:

```text
Exception in thread "main" java.lang.UnsatisfiedLinkError: java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;
```

With `CHAMPACA_BT=1` the name is also printed on stderr the moment the native is hit, whether or not something catches the error. That's the quickest way to list what a new library needs. The first fix was one line answering null, which is what HotSpot answers when it has nothing to say (the JVM's full message came later, in `npe.clj`):

```scheme
(defnative "java/lang/NullPointerException.getExtendedNPEMessage()Ljava/lang/String;" (this) #f)
```

`defnative` takes the method's full name and descriptor, then its parameters (`this` first for an instance method), then a Scheme body. In the body Java `null` is `#f`, `int`s and `long`s are exact integers, a `boolean` is 0 or 1, and objects are the runtime's own records; the comment at the top of `runtime.ss` lists the whole mapping. Run with `CHAMPACA_BT=1` to see which Java code made the call. For the native's contract, read the JDK's source in `$JAVA_HOME/lib/src.zip`.

### A class that won't load

A class that isn't there at all, named by an instruction (`new`, a field or method reference, `ldc`, a cast of an object, a lambda's interface) or as a superclass, is a `NoClassDefFoundError` from that instruction when it runs, with the JVM's message and `ClassNotFoundException` cause. So code behind a check such as Spring's `ClassUtils.isPresent` runs as it should. The class is looked for when the method is first translated, except by an `ldc` of a class, which looks when it runs ([limitations](limitations.md#other-gaps)). A cast or `instanceof` of `null` doesn't look for the class, as HotSpot doesn't (`test/java/LinkCheck.java`).

A call to a method its class doesn't have is likewise a `NoSuchMethodError` with HotSpot's message when it runs. That holds for static, `invokespecial`, virtual and interface calls, even when the receiver's own class has the method or the receiver is `null` (`test/java/NoMethodCheck.java`). A call whose instruction doesn't match the method's kind is an `IncompatibleClassChangeError` with HotSpot's text the same way (`test/java/IcceCheck.java`): an `invokevirtual`, `invokespecial` or `invokeinterface` of a static method, or an `invokestatic` of an instance one. javac never emits those, but a class compiled against an older library can.

A class the JVM refuses to define throws HotSpot's error and text wherever it's needed, every time, whether from the class path, from a loader's bytes or at an instruction (`test/java/ClassDefCheck.java`, `test/java/SealedCheck.java`). The JVM refuses a final or interface superclass, a class among its interfaces, an override of a final method, a class among its own supertypes, an interface whose superclass isn't `Object`, a subclass a sealed class or interface doesn't permit, and a final class with a `PermittedSubclasses` attribute. `CHAMPACA_BT=1` names each such class and method as the method holding it is translated.

A class file that doesn't parse (cut short, a wrong magic number, a constant it can't read) is a `java.lang.ClassFormatError` wherever the class is needed, as on the JVM, with HotSpot's message for the first two. `CHAMPACA_BT=1` names each method holding an instruction that needs one as it's translated (`test/java/ClassFormatCheck.java`).

A class the translator can't handle (an opcode it has no case for) is a Scheme error when a call first reaches it, reported as `champaca: Scheme error: ...` with the Java frames under `CHAMPACA_BT=1`. Loaded by name instead, through `Class.forName` or a class loader's `loadClass`, it throws `java.lang.NoClassDefFoundError` with that Scheme error's text in its message, as the JVM throws a `LinkageError` for a class it can't define, so the cause reads off the exception. Only a class that isn't there at all is a `ClassNotFoundException`.

`Class.forName` refuses a malformed name before the JVM looks, with the message the JDK's `Class.c` gives: a name with a slash, or an array descriptor that isn't one or has more than 255 dimensions. Then, as HotSpot's `JVM_FindClassFromCaller` does, it refuses a name over 65535 bytes of modified UTF-8, with a message that abridges the name as HotSpot's does (`test/java/ClassNameCheck.java`).

A class file found under a name that isn't its own is HotSpot's `NoClassDefFoundError` naming both, `a/b/C (wrong name: a/b/D)`, and never a second class. That happens with a file renamed in a class path directory, a name in another case on a file system that ignores case, or `loadClass("a..b.C")`, whose path finds `a/b/C`'s file. A slashed name the application loader finds a class file for is the JDK's `IllegalName`.

A library that probes for an optional class with `Class.forName` inside a `catch (Throwable)` or `catch (LinkageError)` goes on to its fallback. One that catches only `ClassNotFoundException` stops at the error, which names the class to fix.

## Looking at generated code

`CHAMPACA_TRACE=1` prints a `jit` line per compiled method and writes everything fed to Chez, the runtime included, to `target/jit/trace.ss`. `Hello.main` comes out as:

```scheme
(set-top-level-value! '|Hello.main([Ljava/lang/String;)V|
  (let ((|Hello.main([Ljava/lang/String;)V|
         (let ((ic0 (cons #f #f)) (ic1 (cons #f #f)))
    (lambda (l0)
      (let ((npe-tag '|Hello.main([Ljava/lang/String;)V|))
        (letrec ((|Hello.main([Ljava/lang/String;)V#B0|
                  (lambda (l0)
                    (begin (jinit-ic ic0 '|java/lang/System| (begin '(jpc . 0) npe-tag) 0)
                           (let ((s0 |java/lang/System:out|))
                             (let ((s1 (jstring-lit "Hello, World!")))
                               ((begin '(jpc . 5)
                                       (jvirt-ic ic1 s0 '|println(Ljava/lang/String;)V| (begin '(jpc . 5) npe-tag) 5))
                                s0 s1)))
                           (void)))))
          (|Hello.main([Ljava/lang/String;)V#B0| l0))))))
```

(The file has it on one line; it's indented here.) `l0` is local 0, `s0` and `s1` are stack slots, `ic0` remembers that `System` is initialized, `ic1` is the `println` call's inline cache, and `#B0` is the method's only basic block. `npe-tag` names the method for a helpful `NullPointerException` message, which gets it with the bytecode index (0 and 5 here), and each `(begin '(jpc . 5) ...)` marks the bytecode pc a stack trace reads its line number from. [how-it-works](how-it-works.md) explains the shape.

A method from the startup cache skips the translator and won't appear, so add `CHAMPACA_CACHE=off` to see everything.

## Structured method code

The generated code has one procedure for each basic block and one `let` for each stack slot, which is easy to produce and hard to read. `CHAMPACA_STRUCTURED=1` runs a pass over each method's form before Chez compiles it (`resources/champaca/structure.ss`). A block that one place calls moves to that place, and a `let` that only gives a local or a constant another name goes away. Every runtime operation stays, in the same order, so the method does what the generated code does.

Take Euclid's algorithm:

```java
static int gcd(int a, int b) {
    while (b != 0) { int t = a % b; a = b; b = t; }
    return a;
}
```

The generator writes three blocks and eight stack-slot bindings for it (647 characters):

```scheme
(letrec ((|Gcd.gcd(II)I#B0| (lambda (l0 l1 l2)
           (let ((s0 l1))
             (if (ix= s0 0) (|Gcd.gcd(II)I#B15| l0 l1 l2) (|Gcd.gcd(II)I#B4| l0 l1 l2)))))
         (|Gcd.gcd(II)I#B4| (lambda (l0 l1 l2)
           (let ((s0 l0)) (let ((s1 l1))
             (let ((s0 (int-rem s0 s1 (begin '(jpc . 6) npe-tag) 6)))
               (let ((l2 s0)) (let ((s0 l1)) (let ((l0 s0)) (let ((s0 l2)) (let ((l1 s0))
                 (begin (when (#3%fx> gil-ready 0) (jsafepoint))
                        (|Gcd.gcd(II)I#B0| l0 l1 l2)))))))))))
         (|Gcd.gcd(II)I#B15| (lambda (l0 l1 l2) (let ((s0 l0)) s0))))
  (|Gcd.gcd(II)I#B0| l0 l1 0))
```

With the pass on, the loop head is the one procedure left, and the body reads as the Java does (418 characters, indented here):

```scheme
(letrec ([|Gcd.gcd(II)I#B0|
          (lambda (l0 l1 l2)
            (if (ix= l1 0)
                l0
                (let ([l2 (int-rem l0 l1 (begin '(jpc . 6) npe-tag) 6)])
                  (begin (when (#3%fx> gil-ready 0) (jsafepoint))
                         (|Gcd.gcd(II)I#B0| l1 l2 l2)))))])
  (|Gcd.gcd(II)I#B0| l0 l1 0))
```

`CHAMPACA_STRUCTURED_TRACE=<file>` appends each rewritten method to a file, pretty-printed. Use it with `CHAMPACA_CACHE=off`, as for the trace above.

The pass leaves a method as generated when it can't follow it: one with an exception handler (`jtry`), a `synchronized` method, a `switch` compiled to `case`, an assigned local, or a block label that appears twice (an inlined callee used at two sites). The trace file names each of those with its reason, and `CHAMPACA_STATS=1` prints the counts. On a cold `Hello` (jolt 0.8.27, 2026-11-14) 972 of the 1,081 methods compiled were rewritten and 3,310 of their 4,189 blocks moved to their one caller. Start times didn't change: 4.8 s cold and 0.55 s warm with the pass off and on.

One rewrite that looks safe is wrong. `(let ((s0 (f x))) s0)` could be `(f x)`, but with line numbers kept Chez leaves the `let` in place, so the call to `f` is not a tail call and its frame is there for a stack trace and for `Reflection.getCallerClass`. Dropping the `let` lost that frame and four test programs failed, so the pass keeps it.

The startup cache keeps structured and generated code apart (`CHAMPACA_STRUCTURED` is part of its key). `jolt test` runs six programs with the pass on, and `CHAMPACA_STRUCTURED=1 jolt test` runs everything with it; that passed 278 of 278 on 2026-11-14. A change to the code generator can break the pass for a program outside those six, so CI runs the whole suite with it every night on Linux x86_64 and macOS (the battery's `structured` mode). It works with `CHAMPACA_BT=1` too: the pass follows that switch's wrapper around each method body, a `fluid-let` of the shadow stack.

## Timing a run

```console
$ CHAMPACA_PROF=1 jolt -M -m champaca.main target/classes Hello
champaca prof (boot, cache hit): wall 215.67 ms
  cache-load-code 90.1 ms
  cache-read-state 29.28 ms
  cache-load-runtime 18.41 ms
  cache-load-unit 0.25 ms
  java+runtime (rest) 77.64 ms
Hello, World!
champaca prof (run, cache hit): wall 228.66 ms
  cache-load-code 90.1 ms
  cache-read-state 29.28 ms
  cache-load-runtime 18.41 ms
  cache-load-unit 2.4 ms
  codegen 0.95 ms
  chez-compile 0.61 ms
  class-bytes 0.28 ms
  link 0.2 ms
  cf-parse 0.12 ms
  register-text 0.09 ms
  register-eval 0.08 ms
  parse 0.02 ms
  java+runtime (rest) 86.12 ms
```

The second report covers the whole run up to `main`'s return, boot included. `cache-read-library` shows up there when the run read the library cache ([performance](performance.md#classes-from-jars-and-directories)).

On a cache miss the phases are parsing, registering classes, code generation, Chez compilation and writing the cache.

## Sampling a run

`CHAMPACA_PROF` says how long champaca's own phases took. For where a program's time goes, `jolt prof` runs a main class with a sampling profiler on its thread and prints the hot Java frames:

```console
$ PROF_ROWS=4 jolt prof target/classes Streams
...
main returned after 109 ms
31 samples (2 with no Java frame)
-- self: the innermost Java frame
       2   6.5% java/lang/Class$Atomic.<clinit>
       2   6.5% Streams.main
       1   3.2% java/util/AbstractCollection.toString
       1   3.2% java/util/EnumMap.getKeyUniverse
-- inclusive: every Java frame on the stack
      29  93.5% Streams.main
      11  35.5% java/util/stream/IntStream.rangeClosed
      10  32.3% java/util/stream/StreamSupport.intStream
       9  29.0% java/util/stream/StreamOpFlag.set
-- innermost code: what ran under it, outermost last
       5  16.1% enable-interrupts < dynamic-wind < call-with-bytevector-and-offset < go2
```

Every 2 ms of wall time (`PROF_US` sets the interval) a profiler thread sends `SIGPROF` to the thread running `main`. Its handler walks the stack as `fillInStackTrace` does and counts the innermost Java frame (self), every frame on the stack once (inclusive), and the innermost Scheme code names under the handler (`PROF_DEPTH` of them), which say what a native or the runtime was doing under the Java frame on top. A native has no frame of its own, so its time counts for the Java method that called it. `PROF_ROWS` is the rows per table.

The samples arrive only when the main method is entered through champaca's jolt API, since champaca.main's own run path doesn't deliver them. So for a jar, give the class path and the `Main-Class` by hand. Profile a warm cache, or the compiler's work is most of what you see. Spike 72 wrote the profiler to find where a Spring Boot fat jar's start went ([performance](performance.md#a-framework-loaders-own-work)).

A sample is taken when the thread next checks for interrupts, so time spent with interrupts off is counted at the place they come back on. A stack walk, `call/1cc` and a `dynamic-wind`'s winders all run that way. When most samples sit on one `enable-interrupts < dynamic-wind < ...` row under a single frame, the time went into whatever ran just before that point, not into the row itself. To find it, time the pieces from Scheme. After `init!`, `jolt.scheme/eval-string` reaches the runtime's own procedures, so a native and its helpers can each be timed in a loop:

```clojure
(require '[champaca.api :as ldk] '[jolt.scheme :as s])
(ldk/init!)
(s/eval-string
 "(let* ([f (hashtable-ref natives \"java/lang/Class.forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;\" #f)]
         [name (jstring-lit \"java.util.ArrayList\")] [n 200000] [t0 (real-time)])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (f name 1 (app-loader) #f))
    (/ (* 1000000.0 (- (real-time) t0)) n))")   ; ns a call
```

That is how the reflective calls and `Class.forName` were taken apart in November 2026. For `Constructor.newInstance` the profiler put nearly every sample on `enable-interrupts < dynamic-wind < invoke-wrapped`, while timing the pieces found the lookups by name before it.

The signal goes to one thread with `pthread_kill`, not from an interval timer, because a process-directed signal can land on jolt's shutdown watcher. On macOS that watcher's `sigwait` then answered as if a signal of its own had come and ended the process with status 128 ([jolt #1461](https://github.com/jolt-lang/jolt/issues/1461), fixed by jolt PR #1462).
