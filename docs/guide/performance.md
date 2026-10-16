# Performance

Three things decide how fast champaca feels. The startup cache sets how long `init!` takes. The call-site cache sets what each `invoke` costs. And the generated code itself sets how fast Java runs once it's going.

All numbers here were measured on macOS arm64 with the jolt pinned in `deps.edn`.

## The startup cache

Booting the JDK means parsing classes, translating their methods to Scheme and compiling them: 201 classes and 362 methods before `main` can start. The first run saves the result under `~/.cache/champaca/<key>/`: the compiled code as a Chez `boot.so` (6.8 MB) and the class table as a jolt image, `state.img`. Later runs load both and skip all of that. The runtime's own Scheme (`runtime.ss`, `natives.ss` and the rest) is compiled into the same directory as `runtime.so`, which loads in about 8 ms where compiling the source took about 130 ms on every start. The code is compiled without Chez's inspector information (variable names and source positions), which had made `boot.so` 12.1 MB and its load 40% slower; procedure names stay, and they're all `getCallerClass` needs.

| Program | Cache off | Warm cache | Warm, after a full `bb test` |
|---|---|---|---|
| `Hello` | 1.31 s | 0.33 s | 0.42 s |
| `Streams` | 1.96 s | 0.39 s | 0.48 s |
| `examples/interop.clj` | 1.47 s | 0.37 s | 0.47 s |

Each is the median of five runs, measured after file support went in. The warm column uses a cache that only that program has filled, and the last one a cache that all 29 tests have added to (see Generations). Before the runtime was cached compiled, the warm column read 0.41, 0.47 and 0.45 s.

### What's in it, and what isn't

Only JDK code is cached. Your own classes, from the classpath, compile fresh on every run, so a big application still pays for its own code at startup.

When a run compiles JDK methods the cache didn't have, `champaca.main` appends them as a new generation, `extra-<n>.so`, on the way out. From jolt, `(ldk/save-cache!)` does the same; call it before the process exits. It costs almost nothing when there's nothing new.

### Generations

`boot.so` loads whole on every run, since boot needs all of it. A generation doesn't. It's written one unit at a time, each unit being either one class's registration (its vtable and method stubs) or one compiled method body, and `state.img` records where each unit sits. A later run starts with none of them loaded. When something first needs a class from a generation, its unit loads, its superclass and interfaces first. The first call to one of its methods then loads that method's body rather than compiling it again. `CHAMPACA_STATS=1` counts the units a run loaded.

Before this, every run loaded every generation, and the cost followed the amount of code. A full `bb test` from an empty cache leaves 14 generations, and a warm `Hello` on that cache took 0.58 s instead of 0.37 s. Right after the change it took 0.42 s and loaded 85 units. Most of the 60 ms left is `state.img`, which still holds every class any run has loaded (775 KB against 250 KB, 73 ms to read against 25 ms).

Class registrations turned out to be most of a generation's code: 9.4 MB of the 14.7 MB that `bb test` wrote, with method bodies the rest. So loading only the bodies lazily would have saved 40 ms.

### When it's rebuilt

The directory name is a hash of everything the compiled code depends on: the JDK (its `release` file plus the size and mtime of `java.base.jmod`), the translator's own source, the jolt runtime version, and whether `CHAMPACA_BT` is on. Change any of them and the next run builds a fresh cache in a new directory.

Two switches control it:

```sh
CHAMPACA_CACHE=off jolt -M -m champaca.main classes Main          # don't read or write it
CHAMPACA_CACHE_DIR=/tmp/ldk jolt -M -m champaca.main classes Main  # keep it somewhere else
```

### Upkeep

Old key directories are cleaned up when a new cache is written: any sibling directory no run has loaded for 7 days goes, along with leftovers from an interrupted write. A cache hit marks its directory as used, so another key still in use, such as the `CHAMPACA_BT` one, stays as long as something runs with it every week. Deleting a key directory under `~/.cache/champaca` is always safe, since the next run rebuilds what it needs. Leave `jmods/` and `jolt/` beside them unless you mean it: they hold the module index with its copied zips, and the pinned jolt build.

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

Inside this repo, `deps.edn` names an upstream jolt commit:

```clojure
:champaca/jolt {:git/url "https://github.com/jolt-lang/jolt.git"
                :git/sha "9eb0b9b34aa229ca1403a1fce4472f39a26d2818"}
```

That commit has a fix that isn't in a jolt release yet: `FileInputStream.skip` seeks instead of reading every skipped byte ([jolt#1375](https://github.com/jolt-lang/jolt/pull/1375)). Champaca's module index skips to the end of each of the 69 jmods, which drops from 63 ms to 5 to 8 ms with the fix. On a cold `gson` run that's 6.17 s against 6.30 to 6.47 s.

`bb jolt` builds the pinned commit:

```sh
bb jolt        # about 45 s, once per pin
bb test        # prints which jolt it picked
```

It makes a detached worktree of a jolt checkout (`$JOLT_SRC`, else `~/dev/jolt`, cloned from the URL if neither exists) at `~/.cache/champaca/jolt/<sha>/src`, runs `make build` there and copies the binary next to it. `bb test` uses `$JOLT` when it's set, then the pinned build, then whatever `jolt` is on your `PATH`.

The pin only affects `bb test` in this repo. Champaca works on a released 0.8.22 too, with the first run a little slower. Once a jolt release includes the fix, the pin goes and `:jolt/min-version` moves up to that release.

## The generated code

`test/java/Bench.java` computes `fib(32)` and a 50-million-step loop with `long` arithmetic. With a warm cache it takes 0.50 s on champaca, about 0.33 s of that startup, against 0.15 s on the JVM, which needs about 0.11 s just to start. It took 1.79 s before the work below, so its computing went from about 1.46 s to 0.17 s.

Four changes did it, each measured on its own first:

- **Int and long wrapping inline.** Every `int` result was wrapped by calling a procedure, and every `long` result by a procedure whose mask was a bignum, so each `long` add became bignum arithmetic. Macros now handle the fixnum case inline (a Chez fixnum always fits a `long`) and leave bignums to the procedures. That alone took `Bench` from 1.79 s to 0.78 s.
- **No assignment** in methods without exception handlers ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)). A hand-written version of the loop ran in 368 ms with `set!`, 692 ms with `set!` on block parameters, and 134 ms with only `let`s.
- **Symbol-keyed dispatch with inline caches** for virtual and interface calls, and symbol-keyed classes for `new` and initialization checks.
- **Inline field and array access**, and `synchronized` methods released through `dynamic-wind`, which costs about 7 ns to set up against 33 ns for a `guard`.

What single operations cost, measured in a loop of two million, before these changes and after:

| Operation | Before | Now | JVM |
|---|---|---|---|
| Empty loop step | 20 ns | 2.2 ns | 1.2 ns |
| Static call | 12.7 ns | 3.1 ns | 0.4 ns |
| Virtual call | 54 ns | 6.0 ns | 0.5 ns |
| Interface call | 83 ns | 11 ns | 0.8 ns |
| Field read and write | 24 ns | 6.2 ns | 1.1 ns |
| Static field read and write | 8.3 ns | 3.2 ns | 1.0 ns |
| Array read and write | 28 ns | 8.0 ns | 1.1 ns |
| `new` | 96 ns | 27 ns | 4.4 ns |
| Calling a method with try/catch | 45 ns | 21 ns | 0.6 ns |
| Calling a `synchronized` method | 111 ns | 66 ns | 9.3 ns |
| `AtomicInteger.incrementAndGet` | 636 ns | 56 ns | 7.2 ns |
| `ReentrantLock` lock and unlock | 1435 ns | 133 ns | 13 ns |
| `StringBuilder.append(char)` | 390 ns | 64 ns | 2.0 ns |

### Exception handlers

Methods with an exception table came later. They used to run every block through a trampoline, with a `guard` around each block and every local assigned with `set!`, so a loop inside a `try` paid for a `guard` on each step. Now only entering a `try` region costs anything ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)), and the handler goes onto Chez's handler stack directly instead of through `guard`, which takes a full continuation and reads the continuation marks: about 17 ns against 36 ns, measured on their own. Measured the same way, before and after, with calling a method that has a try/catch in the table above:

| Operation | Before | Now | JVM |
|---|---|---|---|
| A loop step inside a `try` | 79 ns | 2.4 ns | 0.9 ns |
| The same, with a handler that reads the loop's locals | 73 ns | 3.8 ns | 1.0 ns |
| A `try`/`catch` inside a loop | 44 ns | 25 ns | 0.9 ns |
| A `try`/`finally` inside a loop | 44 ns | 27 ns | 0.9 ns |
| A `synchronized` block | 88 ns | 56 ns | 10.5 ns |
| Throwing and catching an exception | 600 ns | 600 ns | 380 ns |

Reading a 4 MB file a byte at a time through a `BufferedInputStream`, which takes a lock and a `try` for every byte, went from 2.1 s before any of this to 0.79 s, then to 0.44 s, against 37 ms on the JVM.

Most of what's left sits in calls, allocation and monitors. Atomic operations still go through `Unsafe` and VarHandle code that decodes its access mode at run time, and a `synchronized` block still costs 56 ns. About 20 ns of that is its `try` region, so most of the rest goes to the monitor itself.

### What threads cost a single thread

Thread support adds two things to every program, threaded or not: a monitor lookup on each `synchronized` entry and exit, and a check at each backward branch for a thread waiting to run. Measured against the same code without threads, a loop of 200,000 `StringBuffer.append`, `length` and `Hashtable.put` calls went from 377 ms to about 440 ms, a 20-million-step counting loop from 392 ms to about 420 ms, and `Bench` from 1.85 s to 1.86 to 1.90 s. The monitor sits in a weak table keyed by its object, and that lookup is the likeliest home of the 17% on `synchronized` code, though it hasn't been profiled.

### What method handles cost

A method handle call goes through the runtime's handle table rather than straight to the method. In a loop of a million calls to a static `add(int, int)`, a direct call took 12 ns, `invokeExact` 117 ns, and `invoke` with boxed `Object` arguments about 1.35 µs, most of that in boxing through `Integer.valueOf` and unboxing through a virtual call. Linking a call site with a bootstrap of its own happens once, on its first call.

Evaluating `handles.ss` from source added about 20 ms to every start when method handles went in. Since then the warm path loads the runtime compiled (`runtime.so`, above), so that cost is now paid only on a cold start or with the cache off.

### What file access costs

File natives call libc through Chez's foreign interface, so a read or write is one libc call plus copying between a Java `byte[]` (a Scheme vector of fixnums) and native memory, in 4 KB pieces for `java.io`. Writing a 4 MB file with `Files.write` took about 180 ms and reading it back with `Files.readAllBytes` about 145 ms, against 7 ms and 1 ms on the JVM. Reading the same file a byte at a time through a `BufferedInputStream` took 2.1 s against 37 ms: that loop is all generated code, which is the slow part ([The generated code](#the-generated-code)).

`files.ss` is the largest runtime file, and evaluating it took about 40 ms per start before the compiled runtime made that a one-time cost.
