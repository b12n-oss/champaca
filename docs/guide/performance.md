# Performance

Three things decide how fast champaca feels. The startup cache sets how long `init!` takes. The call-site cache sets what each `invoke` costs. And the generated code itself sets how fast Java runs once it's going.

All numbers here were measured on macOS arm64 with the jolt pinned in `deps.edn`.

## The startup cache

Booting the JDK means parsing classes, translating their methods to Scheme and compiling them: 387 classes and 819 methods before `main` can start, two thirds of them for the module system (below). The first run saves the result under `~/.cache/champaca/<key>/`: the compiled code as a Chez `boot.so` (4.9 MB) and the class table as a jolt image, `state.img`. Later runs load both and skip all of that. The runtime's own Scheme (`runtime.ss`, `natives.ss` and the rest) is compiled into the same directory as `runtime.so`, which loads in about 8 ms where compiling the source took about 130 ms on every start. The code is compiled without Chez's inspector information (variable names and source positions), which had made `boot.so` 12.1 MB and its load 40% slower; procedure names stay, and they're all `getCallerClass` needs.

| Program | Cache off | Cold, writing the cache | Warm cache |
|---|---|---|---|
| `Hello` | 1.17 s | 1.53 s | 0.31 s |
| `Streams` | 1.68 s | 2.20 s | 0.47 s |
| `examples/interop.clj` | 1.48 s | 1.82 s | 0.36 s |

The last row timed `jolt -M examples/interop.clj` when that file printed its results. It's a set of tests now, which `bb example interop` runs. Each is the median of five runs, measured right after the version before the last change below on the same machine, so read them for the comparison more than the absolute values. The module system's boot came later and made every column dearer: see [the module system's share](#the-module-systems-share) below. A cold run is the first after the cache key changes, with the JDK's modules already extracted (that happens once per JDK). Without compiling each method once, the cold column read 1.87, 2.65 and 2.28 s, with the other two columns the same. Before the earlier changes, on a busier machine, it read 3.15, 4.79 and 4.29 s.

A cold start used to spend half its time writing the cache, and most of that compiling the class registrations into `boot.so`. Each method stub was its own top-level `define`, several thousand of them, and Chez's cost per top-level form is far above that of a quoted list, so a class's methods are now defined by one form holding a list (`define-methods!`). Each class's vtable also repeated every inherited entry, about a third of the registration text, and it's now registered as changes to its superclass's. Together they took `boot.so` from 6.8 MB to 1.5 MB, which warm starts load whole. A cold start also used to evaluate the runtime's source and then compile it again for the cache; it now compiles `runtime.so` first and loads that. And class files parse about twice as fast: ASCII constant-pool strings are decoded in Scheme, straight from the byte array.

Each JDK method a cold start compiled was also compiled twice: once to run it, then again from its text when `boot.so` was written, about 200 ms for `Hello`. Now the JIT compiles such a method to object code once, runs that code, and keeps the same bytes for the cache, which appends them to `boot.so` as they are. Compiling to bytes and loading them costs a bit more than compiling straight into memory, about 290 µs against 225 µs for a method-sized form, so champaca does it only when the bytes will be written: during boot, in a `champaca.main` run, which saves a generation at exit, and after a jolt program's first `save-cache!`. Before that first call a jolt program records the text instead, which is compiled when `save-cache!` writes it. Methods compiled this way, like everything loaded from the cache, carry no inspector information.

### The module system's share

Booting the module system (`System.initPhase2()` and `initPhase3()`, [how it works](how-it-works.md#booting-the-jdk)) loads 181 more classes before `main`: `ModuleBootstrap`, `ModuleDescriptor` and its builders, the class loaders and `URLClassPath`, and `SystemModules$default`, the class jlink generates with the image's module graph. Measured against the version just before it, medians of five alternating rounds on macOS arm64:

| | Before | With the module system |
|---|---|---|
| Warm `Hello` | 0.38 s | 0.45 s |
| Warm `JdkLib` | 0.44 s | 0.50 s |
| Cold `Hello` (new cache key) | 1.7 s | 6.7 s |
| `boot.so` | 1.7 MB | 4.9 MB |
| The cache key directory | 3.4 MB | 6.9 MB |

A warm start pays for loading the extra code (`boot.so` takes 88 ms to load instead of 35) and reading a bigger class table, then about 15 ms running the boot itself. The JVM pays for the same boot too, but it archives the result (class data sharing): run with `-Xshare:off` it starts 15 ms slower, since it parses classes rather than compiling them. An archived boot layer is the way to get the 70 ms back, and the cold start with it: the generated `SystemModules$default` would then run once, when the archive is built, instead of being compiled into the cache.

Most of the cold start's five seconds is Chez compiling `SystemModules$default`. Its `moduleDescriptors()` method is 21 KB of straight-line bytecode, 2,000 calls building 70 descriptors, and the Scheme for it is 890 KB. As one nest of 12,000 lets it took Chez's expander 4.6 s, since the expander walks the enclosing bindings for every variable it resolves; the translator now closes each `let` as soon as its value is dead ([how it works](how-it-works.md#the-shape-of-a-translated-method)), which cut the expander's share and the whole cold boot from 14 s to 9 s. Its 2,900 inline-cache cells, one `let` variable each, were the expander's other cost (a `let` of 2,500 bindings slows every variable lookup under it tenfold), so a method over 4,000 bytes of bytecode keeps them in one vector: 9 s to 6.7 s, and the method's expansion went from 1.7 s to 0.16 s. What's left of its 2.8 s is the compiler's back end on a form that size; replacing the caches with plain calls, dropping the stack-trace markers, or giving the collector a bigger trip (2.7 s to 2.0 s at a 1.4 GB heap) changed little there, and interpreting a cached expanded form instead would cost every warm start 340 ms.

### What's in it, and what isn't

JDK code is cached, and so are classes from jars and directories on the classpath ([below](#classes-from-jars-and-directories)). A classpath class is used from the cache only while its bytes are the ones it was compiled from, so the program you're working on always runs what you last compiled.

When a run compiles JDK methods the cache didn't have, `champaca.main` appends them as a new generation, `extra-<n>.so`, on the way out. From jolt, `(ldk/save-cache!)` does the same; call it before the process exits. It costs almost nothing when there's nothing new.

### Generations

`boot.so` loads whole on every run, since boot needs all of it. A generation doesn't. It's written one unit at a time, each unit being either one class's registration (its vtable and method stubs) or one compiled method body, and `state.img` records where each unit sits. A later run starts with none of them loaded. When something first needs a class from a generation, its unit loads, its superclass and interfaces first. The first call to one of its methods then loads that method's body rather than compiling it again. `CHAMPACA_STATS=1` counts the units a run loaded.

Before this, every run loaded every generation, and the cost followed the amount of code. A full `bb test` from an empty cache leaves 14 generations, and a warm `Hello` on that cache took 0.58 s instead of 0.37 s. Right after the change it took 0.42 s and loaded 85 units. Most of the 60 ms left was `state.img`, which holds every class any run has loaded (775 KB against 250 KB, 73 ms to read against 25 ms).

`state.img` is now written as one piece of edn text per class. Reading the image builds strings, and an entry is parsed the first time the run touches its class, so boot pays for the classes the program uses rather than for everything the cache has seen. After today's full `bb test` the table holds about 1,450 classes in a 1.6 MB image: decoding the old form took 144 ms, reading the text form takes 35 ms, and a warm `Hello` on that cache went from 0.43 s to 0.34 s (medians of nine interleaved runs). A save keeps the text of every entry the run never parsed, so rewriting the image doesn't re-print the whole table either.

Class registrations turned out to be most of a generation's code: 9.4 MB of the 14.7 MB that `bb test` wrote, with method bodies the rest. So loading only the bodies lazily would have saved 40 ms.

### Classes from jars and directories

A class from a jar goes in its jar's file under `lib/` in the key directory, one file per jar (by its canonical path), and a class from a directory in that directory's file the same way. Its compiled code goes in the same generations as JDK code. The JDK can't change under a cache, since the key covers it, but a jar or a directory can, so each entry records what it was built from. A class's own fingerprint is the CRC-32 and size of its class file. For a jar's class those come from the zip's directory without inflating anything. A directory's class file is read for it, and the class is parsed from those same bytes; when a class loads from other bytes (a load retried after a failure, say), the fingerprint recorded for it is taken from those, so an entry can't pair one version's fingerprint with another's code. A directory's class keeps the bytes it was loaded or restored from for the rest of the run, about what its class file weighs, so a method a restored class's entry didn't have compiles from the version the class was restored as, and stack frames read their line numbers from the version that's running, even when the file has changed or gone since, as the JVM keeps running the class it loaded. Reading a loaded class's bytes this way never changes what the cache records. A jar's class is read from the jar again and checked against its fingerprint, and if the bytes it gets back differ, the run stops with an error naming the class. `test/libcache/edit` checks the directory case in three runs: the second replaces a restored class's file, then reads one of its stack frames and calls a method the cache lacks. A class file written into an earlier classpath directory while the program runs may go unseen: a class the cache restores comes from where the run first found it, where the JVM would load the new file. A class that compiles from its file instead records the fingerprint of the bytes it was parsed from. The entry also records the fingerprint of every other classpath class that its link or its compiled methods read, along with everything those classes inherit from: a field's slot is decided by the superclasses, so a class whose own bytes didn't change still moves its fields when a superclass gains one.

When a run needs a class, it finds it on the classpath as before and uses the cached entry only when its fingerprint and every one it recorded still match. Otherwise the class compiles as if there were no cache, and the next save replaces the entry. A class that depends on one a class loader defined isn't kept, and neither is a method whose code reads one. A lambda's class can't be made again from its name alone, so an entry whose code names one is used only while that class is in the class table. That class takes its interfaces' default methods into its vtable, and it goes in `state.img` like a JDK class, so its name carries the fingerprints of the classpath interfaces it implements: before, a jar's interface that gained a default method between runs left the next run with the earlier class, and calling the new method threw `AbstractMethodError`. `test/libcache/lam` checks it, eight more runs: the interface from jars, then from directories, then from one directory holding v1's interface and then v2's.

Jackson's probe in `test/libs` compiled about 2,170 methods of classpath classes on every run before this. A warm run took 5.22 s and now takes 1.34 s (medians of five interleaved runs; the JVM takes 0.28 s). Of what's left, boot is about 0.23 s and loading some 4,700 cached units about 0.25 s, and the class entries cost something to parse: all 584 of Jackson's take about 150 ms. A classpath class's code is cached with Chez's inspector information, which JDK code goes without, because a stack trace reads its line numbers from it ([how it works](how-it-works.md#stack-traces)): that costs Jackson 3.3 MB of generations (20.1 MB against 16.8) and about 0.1 s a warm run (1.34 s against 1.24). The 19 methods it still compiled were the probe's own, from the directory `target/libs/Jack`, which is cached now too.

Jackson's classes unpacked into a directory (1,103 class files) took 5.49 s a warm run when directories weren't cached and take 1.47 s now (medians of five interleaved runs); reading every class file for its CRC is part of that. Rebuilding one class there and running again compiles just that class and appends a generation of about 22 KB, which stays, like any replaced code, until the key directory goes. `javac` writes the same bytes for an unchanged source (the 223 class files of `test/java`, compiled twice, came out identical), so a full rebuild recompiles only the classes that changed and those that depend on them. A warm `Hello` is unchanged (0.42 s against 0.41). Cached code takes more memory than code compiled in the process: that run peaks at 568 MB against 508 MB when it compiled everything, and the same classes from jars peak at 557 MB, the 11 MB between them including the class files the run holds on to.

A jar's or a directory's file is read the first time a run loads a class from it, so a run pays only for the jars it uses, and a program without any never reads one. A warm `Hello` on a cache holding Jackson's 1 MB of entries reads none of them.

Every program shares the cache, but since the files go by the jar's or directory's path, two programs on different versions of a library, at different paths in `~/.m2`, keep their own entries. A jar rebuilt at one path (a snapshot, an uberjar) replaces its own. A file no run has read for a week is deleted the next time a run writes one, while a replaced entry's compiled code stays in its generation file until the key directory goes. `CHAMPACA_STATS=1` says how many classes came from the library cache and how many methods of classpath classes compiled.

`bb test` checks it with `test/libcache`, one program run fourteen times over jars and directories that change between runs, its output including a jar class's code source, its package and one of its stack frames: `Base` gains a field in `base-v2.jar`, `Sub`'s bytes are the same in both jars, and `lc2.User`, in a jar of its own, writes `Sub`'s field without naming `Base`. The fifth and sixth runs put `Base` in a directory ahead of the jar, and the sixth gets it, with `LibCacheMain` from its own directory, back from the cache. Each run also checks how many classes came from the cache at least: the seventh, back on `base-v1.jar`, gets `Base` from that jar's own file, which the `base-v2.jar` runs left alone. The next three rebuild one path: `base.jar` holds v1, then v2 is copied over it, and its file's v1 entries must not answer for it. The last four do the same with a directory, `base-dir`, as `javac -d` rebuilding into one would, and one of them copies in a `Base` of the same size as v1's with another string in it, so only the CRC tells the two apart.

### When it's rebuilt

The directory name is a hash of everything the compiled code depends on: the JDK (its `release` file plus the size and mtime of `lib/modules`), the translator's own source, the jolt runtime version, and whether `CHAMPACA_BT` is on. Change any of them and the next run builds a fresh cache in a new directory.

Two switches control it:

```sh
CHAMPACA_CACHE=off jolt -M -m champaca.main classes Main          # don't read or write it
CHAMPACA_CACHE_DIR=/tmp/ldk jolt -M -m champaca.main classes Main  # keep it somewhere else
```

### Upkeep

Old key directories are cleaned up when a new cache is written: any sibling directory no run has loaded for 7 days goes, along with leftovers from an interrupted write. A cache hit marks its directory as used, so another key still in use, such as the `CHAMPACA_BT` one, stays as long as something runs with it every week. Besides the age rule, only the 16 most recently used key directories are kept. That matters when the translator itself is being edited, since every edit makes a new key, and a day of that would otherwise leave gigabytes behind. Deleting a key directory under `~/.cache/champaca` is always safe, since the next run rebuilds what it needs. Leave `jolt/` beside them unless you mean it, since it holds the pinned jolt build. A `jmods/` there is left from versions that copied the JDK's jmods and can go.

## The call-site cache

Picking an overload ([calling-java](calling-java.md#which-overload-runs)) walks the class and its supertypes for methods with the right name, then scores each one against the arguments. That's slow to repeat in a loop, so `invoke`, `invoke-static` and `construct` remember the choice.

The key is the class, the method name and the *kind* of each argument: a Java value's class, an integer's range (int, long or bigger), or the jolt type for anything else. A vector counts by the kind its elements share, whatever its length. Those are exactly the things the scoring looks at, so the cached answer is the one a fresh lookup would give. A vector whose elements are of mixed kinds isn't cached, and a lookup that fails is never stored, so it fails the same way every time.

Microseconds per call after warm-up, cache off and on:

| Call | Off | On |
|---|---|---|
| `invoke` `StringBuilder.length` | 6.33 | 1.02 |
| `invoke` `ArrayList.add` | 9.77 | 1.55 |
| `invoke` `ArrayList.size` | 6.86 | 0.85 |
| `invoke-static` `Math.max` | 4.41 | 1.25 |
| `invoke-static` `String.valueOf` | 10.04 | 2.55 |
| `invoke-static` `Arrays.asList` with a vector | 7.46 | 3.14 |
| `construct` `StringBuilder(String)` | 5.71 | 3.05 |
| `call` `length` with its descriptor | 0.85 | 0.85 |

So a cached `invoke` comes close to the descriptor-based `call`. The remaining gap hasn't been profiled. Converting arguments and results is the likely cost, along with turning a jolt string receiver into a Java `String`.

`CHAMPACA_CALL_CACHE=off` turns it off, which is handy for checking whether a surprising result comes from the cache. `examples/callsite.clj` exercises it: one call site gets arguments that need a different overload each time.

The table holds at most 4096 entries and empties itself when full, so a call site that sees endless new argument kinds can't make it grow without bound.

## The pinned jolt

Inside this repo, `deps.edn` names a jolt commit, the v0.8.23 tag:

```clojure
:champaca/jolt {:git/url "https://github.com/jolt-lang/jolt.git"
                :git/sha "a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5"}
```

It's the release's own code, built from source. Built that way it ran a warm `Hello` in 0.32 s (median of seven, interleaved), where one release binary of the same commit took 0.45 s and started jolt itself 50 ms slower. A local build of the commit the pin used before, from upstream main, was as fast as the new one, so the slowdown comes from how that release binary was built, not from jolt's code.

0.8.23 is also the first release with `java.io.RandomAccessFile` ([jolt#1375](https://github.com/jolt-lang/jolt/pull/1375)), which champaca reads the JDK's runtime image with, so it's the minimum.

`bb jolt` builds the pinned commit:

```sh
bb jolt        # about 45 s, once per pin
bb test        # prints which jolt it picked
```

It makes a detached worktree of a jolt checkout (`$JOLT_SRC`, else `~/dev/jolt`, cloned from the URL if neither exists) at `~/.cache/champaca/jolt/<sha>/src`, runs `make build` there and copies the binary next to it. `bb test` uses `$JOLT` when it's set, then the pinned build, then whatever `jolt` is on your `PATH`.

The pin only affects `bb test` and `bb libs` in this repo. Champaca works on any released 0.8.23 or later.

## The generated code

`test/java/Bench.java` computes `fib(32)` and a 50-million-step loop with `long` arithmetic. With a warm cache it takes 0.50 s on champaca, about 0.33 s of that startup, against 0.15 s on the JVM, which needs about 0.11 s just to start. It took 1.79 s before the work below, so its computing went from about 1.46 s to 0.17 s.

Four changes did it, each measured on its own first:

- **Int and long wrapping inline.** Every `int` result was wrapped by calling a procedure, and every `long` result by a procedure whose mask was a bignum, so each `long` add became bignum arithmetic. Macros now handle the fixnum case inline (a Chez fixnum always fits a `long`) and leave bignums to the procedures. That alone took `Bench` from 1.79 s to 0.78 s.
- **No assignment** in methods without exception handlers ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)). A hand-written version of the loop ran in 368 ms with `set!`, 692 ms with `set!` on block parameters, and 134 ms with only `let`s.
- **Symbol-keyed dispatch with inline caches** for virtual and interface calls, and symbol-keyed classes for `new` and initialization checks.
- **Inline field and array access**, and `synchronized` methods released through `dynamic-wind`, which costs about 7 ns to set up against 33 ns for a `guard`.

What single operations cost, measured in a loop of two million, before these changes and now, with the later work below included:

| Operation | Before | Now | JVM |
|---|---|---|---|
| Empty loop step | 20 ns | 2.2 ns | 1.2 ns |
| Static call | 12.7 ns | 2.5 ns | 0.4 ns |
| Virtual call | 54 ns | 3.8 ns | 0.5 ns |
| Interface call | 83 ns | 6.7 ns | 0.8 ns |
| Field read and write | 24 ns | 4.0 ns | 1.1 ns |
| Static field read and write | 8.3 ns | 3.2 ns | 1.0 ns |
| Array read and write | 28 ns | 3.9 ns | 1.1 ns |
| `new` | 96 ns | 12 ns | 4.4 ns |
| Calling a method with try/catch | 45 ns | 21 ns | 0.6 ns |
| Calling a `synchronized` method | 111 ns | 22 ns | 9.3 ns |
| `AtomicInteger.incrementAndGet` | 636 ns | 13 ns | 7.2 ns |
| `ReentrantLock` lock and unlock | 1435 ns | 34 ns | 13 ns |
| `StringBuilder.append(char)` | 390 ns | 36 ns | 2.0 ns |

### Exception handlers

Methods with an exception table came later. They used to run every block through a trampoline, with a `guard` around each block and every local assigned with `set!`, so a loop inside a `try` paid for a `guard` on each step. Now only entering a `try` region costs anything ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)), and the handler goes onto Chez's handler stack directly instead of through `guard`, which takes a full continuation and reads the continuation marks: about 17 ns against 36 ns, measured on their own. Measured the same way, before and after, with calling a method that has a try/catch in the table above:

| Operation | Before | After | JVM |
|---|---|---|---|
| A loop step inside a `try` | 79 ns | 2.4 ns | 0.9 ns |
| The same, with a handler that reads the loop's locals | 73 ns | 3.8 ns | 1.0 ns |
| A `try`/`catch` inside a loop | 44 ns | 25 ns | 0.9 ns |
| A `try`/`finally` inside a loop | 44 ns | 27 ns | 0.9 ns |
| A `synchronized` block | 88 ns | 56 ns | 10.5 ns |
| Throwing and catching an exception | 600 ns | 600 ns | 380 ns |

### Records, monitors and type tests

Three things were left that every program pays for, found by timing each piece of a `synchronized` block and of `HashMap.get` on its own:

- **Record accessors were procedure calls.** Chez inlines a record's accessors and type check into code compiled later only when the record type is nongenerative, so its descriptor is known at compile time. Objects, arrays, classes and monitors are now sealed and nongenerative records, which made every field read, array access and dispatch cheaper. A field set and read on a plain record took 4.6 ns against 2.2 ns on a sealed nongenerative one, the same as on a vector.
- **A monitor lived in a weak table** and its owner came from a thread parameter. An object now keeps its monitor in its own record, and the owner is compared with the GIL's owner ([how-it-works](how-it-works.md#threads)). A static `synchronized` method also looked its class mirror up by name on every call, and now finds it once.
- **`instanceof` and `checkcast` compared class names** and walked the class hierarchy on every test, so `String.equals`, which tests `instanceof String`, took 187 ns. Each site now keeps the last class it saw with its answer, and each initialization check and `new` keeps its class once it's initialized.

| Operation | Before | Now | JVM |
|---|---|---|---|
| `String.equals`, unequal | 188 ns | 38 ns | 4.6 ns |
| `HashMap.get`, `Object` keys | 191 ns | 35 ns | 6.0 ns |
| A `synchronized` block | 60 ns | 37 ns | 10.3 ns |
| `StringBuffer.append(char)` | 112 ns | 62 ns | 12.6 ns |
| Throwing and catching an exception | 600 ns | 340 ns | 380 ns |

Stack traces made a throw dearer again: `fillInStackTrace` walks the continuation and keeps a pair per frame, about what HotSpot pays for its backtrace. Measured with 100,000 of each: constructing a `Throwable` 20 frames deep went from 220 ns to 1.2 us (JVM 550 ns), and throwing and catching across 20 frames from 500 ns to 1.35 us (JVM 1.3 us). The frames stay in the throwable's own field: a weak table under the throwable kept each dead throwable's frames alive until a collection swept its entry, which cost another 1.5 us a throw. Code that never throws pays nothing: the pc markers are constants the optimizer drops, and a `try` entry costs what it did. The cache still compiles JDK code without Chez's inspector information, which the line numbers come from: with it the cache was 6.5 MB against 4.2, and a warm start 0.02 to 0.04 s slower (Hello 0.30 to 0.32 s, JdkLib 0.34 to 0.38 s, medians of 5 alternating rounds), so JDK frames carry no line numbers.

### Arrays and small calls

A loop comparing two 100-byte arrays took 7.0 ns per element. Measured on its own, a plain Chez loop over two vectors takes 2.4 ns per element in safe mode and 0.6 ns unchecked, so most of the 7 ns was checks, and several ran twice: `array-ref` checked the index against the vector and then `vector-ref` checked it again, and `array-length` made a procedure call for its null check. The array macros now check null and bounds once and then use Chez's unchecked vector access (`#3%vector-ref`), which is safe because the index has just been checked against that vector. That brought the loop to 4.4 ns per element, against 0.6 ns on the JVM.

Calls themselves cost little here: a call to a static method is about 1 ns on top of the loop, 3 ns into another class with its initialization check. Code like `StringBuilder.append` pays for many of them, though, so small leaf methods are now inlined and calls that can only reach one method skip dispatch ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)). A static call to a leaf now costs nothing beyond the loop around it.

| Operation | Before | Now | JVM |
|---|---|---|---|
| Comparing two 100-byte arrays | 708 ns | 446 ns | 57 ns |
| `String.equals`, unequal, 3 characters | 38 ns | 27 ns | 4.8 ns |
| `String.length` | 10 ns | 6.7 ns | 1.9 ns |
| `ReentrantLock` lock and unlock | 42 ns | 34 ns | 13 ns |
| `StringBuilder.append(char)` | 42 ns | 36 ns | 2.0 ns |

Reading a 4 MB file a byte at a time through a `BufferedInputStream`, which takes a lock and a `try` for every byte, went from 2.1 s before any of this to 0.79 s, then 0.44 s, then 0.28 s, against 37 ms on the JVM.

What's left is spread thin. Each method call still goes through a top-level name and passes its live locals, only leaves get inlined, and safe-mode checks stay on field access and arithmetic.

### What threads cost a single thread

Thread support adds two things to every program, threaded or not: a monitor lookup on each `synchronized` entry and exit, and a check at each backward branch for a thread waiting to run. Measured against the same code without threads, a loop of 200,000 `StringBuffer.append`, `length` and `Hashtable.put` calls went from 377 ms to about 440 ms, a 20-million-step counting loop from 392 ms to about 420 ms, and `Bench` from 1.85 s to 1.86 to 1.90 s. Those numbers predate the monitor changes above, and the monitor lookup they blamed, unprofiled at the time, has since gone.

### What calling a jolt fn costs Java

Each call Java makes into one of your fns runs inside an exception handler, the one that turns a jolt error into a `RuntimeException` Java can catch ([calling-java](calling-java.md#exceptions)). The handler is only set up, and the jolt side is only asked to convert anything once an error is raised. `Collections.sort` on 200,000 integers with a jolt comparator makes 2,855,207 calls, and took 0.73 to 0.80 s without the handler and 0.82 to 0.88 s with it, alternating runs. That is about 30 ns of the roughly 270 ns each call costs.

### What method handles cost

A method handle call goes through the runtime's handle table rather than straight to the method. In a loop of a million calls to a static `add(int, int)`, a direct call took 12 ns, `invokeExact` 117 ns, and `invoke` with boxed `Object` arguments about 1.35 µs, most of that in boxing through `Integer.valueOf` and unboxing through a virtual call. Linking a call site with a bootstrap of its own happens once, on its first call.

Evaluating `handles.ss` from source added about 20 ms to every start when method handles went in. Since then the warm path loads the runtime compiled (`runtime.so`, above), so that cost is now paid only on a cold start or with the cache off.

### What file access costs

File natives call libc through Chez's foreign interface, so a read or write is one libc call plus copying between a Java `byte[]` (a Scheme vector of fixnums) and native memory, in 4 KB pieces for `java.io`. Writing a 4 MB file with `Files.write` took about 180 ms and reading it back with `Files.readAllBytes` about 145 ms, against 7 ms and 1 ms on the JVM. Reading the same file a byte at a time through a `BufferedInputStream` took 2.1 s against 37 ms: that loop is all generated code, which is the slow part ([The generated code](#the-generated-code)).

`files.ss` is one of the larger runtime files, and evaluating it took about 40 ms per start before the compiled runtime made that a one-time cost.
