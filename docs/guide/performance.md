# Performance

Three things decide how fast champaca feels. The startup cache sets how long `init!` takes. The call-site cache sets what each `invoke` costs. And the generated code itself sets how fast Java runs once it's going.

All numbers here were measured on macOS arm64 with the jolt pinned in `deps.edn`.

## The startup cache

Booting the JDK means parsing classes, translating their methods to Scheme and compiling them: 387 classes and 819 methods before `main` can start, two thirds of them for the module system (below). The first run saves the result under `~/.cache/champaca/<key>/`: the compiled code as a Chez `boot.so` (4.9 MB) and the class table as a jolt image, `state.img`. Later runs load both and skip all of that. The runtime's own Scheme (`runtime.ss`, `natives.ss` and the rest) is compiled into the same directory as `runtime.so`, which loads in about 8 ms where compiling the source took about 130 ms on every start. The code is compiled without Chez's inspector information (variable names and source positions), which had made `boot.so` 12.1 MB and its load 40% slower; procedure names stay, which is all `getCallerClass` and a stack frame's method name need (it's also why a JDK frame has no line number).

Today, on an M1 Pro on 2026-10-28 (medians of five runs, load average 5 to 7 from other work):

| Program | Cache off | Cold, writing the cache | Warm cache | Warm, after a full `bb test` |
|---|---|---|---|---|
| `Hello` | 5.9 s | 6.5 s | 0.39 s | 0.46 s |
| `Streams` | 6.6 s | 7.0 s | 0.49 s | 0.52 s |

Before the module system booted, the same table read:

| Program | Cache off | Cold, writing the cache | Warm cache |
|---|---|---|---|
| `Hello` | 1.17 s | 1.53 s | 0.31 s |
| `Streams` | 1.68 s | 2.20 s | 0.47 s |
| `examples/interop.clj` | 1.48 s | 1.82 s | 0.36 s |

The last row timed `jolt -M examples/interop.clj` when that file printed its results. It's a set of tests now, which `bb example interop` runs. Each is the median of five runs, measured right after the version before the last change below on the same machine, so read them for the comparison more than the absolute values. The module system's boot came later and made every column dearer: see [the module system's share](#the-module-systems-share) below. A cold run is the first after the cache key changes. Without compiling each method once, the cold column read 1.87, 2.65 and 2.28 s, with the other two columns the same. Before the earlier changes, on a busier machine, it read 3.15, 4.79 and 4.29 s.

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

When a run compiles JDK methods the cache didn't have, `champaca.main` appends them as a new generation, `extra-<n>.so`, on the way out. That includes a warm start's own boot when it loads classes the cached boot didn't, which a `-D` option can make it do: `-Dstdout.encoding=ISO-8859-1` gives `System.out` an encoder no default boot loads, and its class goes into that run's generation like any other (`bb test` runs that sequence on a cache of its own, `EncoderCheck` being the program that uses the encoder). From jolt, `(ldk/save-cache!)` does the same; call it before the process exits. It costs almost nothing when there's nothing new. A cache directory that can't be written (an installed bundle's) is read, and the save is skipped, so a run there keeps nothing it compiled and doesn't fail for it. A save that fails otherwise (a full disk, say) throws and loses what it would have written, and the classes it held load and compile their methods again in the next run that needs them, a subclass saved later included: `state.img` only lists a class whose registration is in `boot.so` or in a generation, since a later run takes any class it lists for registered (`test/cachesave` runs a failed save, then a good one holding a subclass of a class the first held, then runs using both).

### Generations

`boot.so` loads whole on every run, since boot needs all of it. A generation doesn't. It's written one unit at a time, each unit being either one class's registration (its vtable and method stubs) or one compiled method body, and `state.img` records where each unit sits. A later run starts with none of them loaded. When something first needs a class from a generation, its unit loads, its superclass and interfaces first. The first call to one of its methods then loads that method's body rather than compiling it again. `CHAMPACA_STATS=1` counts the units a run loaded.

Before this, every run loaded every generation, and the cost followed the amount of code. A full `bb test` from an empty cache leaves 14 generations, and a warm `Hello` on that cache took 0.58 s instead of 0.37 s. Right after the change it took 0.42 s and loaded 85 units. Most of the 60 ms left was `state.img`, which holds every class any run has loaded (775 KB against 250 KB, 73 ms to read against 25 ms).

`state.img` is now written as one piece of edn text per class. Reading the image builds strings, and an entry is parsed the first time the run touches its class, so boot pays for the classes the program uses rather than for everything the cache has seen. After a full `bb test` on 2026-10-17 the table holds about 1,450 classes in a 1.6 MB image: decoding the old form took 144 ms, reading the text form takes 35 ms, and a warm `Hello` on that cache went from 0.43 s to 0.34 s (medians of nine interleaved runs). A save keeps the text of every entry the run never parsed, so rewriting the image doesn't re-print the whole table either. Since spikes 73 and 74 an entry is three pieces, the class map, its vtable and its methods, and a thaw parses only the first: a vtable is read when a subclass links or a registration is rebuilt, the methods when a call to the class is compiled, a subclass links or the class is reflected on, none of which a class restored from the cache needs on a warm run, and together they are three quarters of an entry's text.

Class registrations turned out to be most of a generation's code: 9.4 MB of the 14.7 MB that `bb test` wrote, with method bodies the rest. So loading only the bodies lazily would have saved 40 ms.

### Classes from jars and directories

A class from a jar goes in its jar's file under `lib/` in the key directory, one file per jar (by its canonical path), and a class from a directory in that directory's file the same way. Its compiled code goes in the same generations as JDK code. The JDK can't change under a cache, since the key covers it, but a jar or a directory can, so each entry records what it was built from. A class's own fingerprint is the CRC-32 and size of its class file. For a jar's class those come from the zip's directory without inflating anything. A directory's class file is read for it, and the class is parsed from those same bytes; when a class loads from other bytes (a load retried after a failure, say), the fingerprint recorded for it is taken from those, so an entry can't pair one version's fingerprint with another's code. A directory's class keeps the bytes it was loaded or restored from for the rest of the run, about what its class file weighs, so a method a restored class's entry didn't have compiles from the version the class was restored as, and stack frames read their line numbers from the version that's running, even when the file has changed or gone since, as the JVM keeps running the class it loaded. Reading a loaded class's bytes this way never changes what the cache records. A jar's class is read from the jar again and checked against its fingerprint, and if the bytes it gets back differ, the run stops with an error naming the class. `test/libcache/edit` checks the directory case in three runs: the second replaces a restored class's file, then reads one of its stack frames and calls a method the cache lacks. A class file written into an earlier classpath directory while the program runs may go unseen: a class the cache restores comes from where the run first found it, where the JVM would load the new file. A class that compiles from its file instead records the fingerprint of the bytes it was parsed from. The entry also records the fingerprint of every other classpath class that its link or its compiled methods read, along with everything those classes inherit from: a field's slot is decided by the superclasses, so a class whose own bytes didn't change still moves its fields when a superclass gains one.

When a run needs a class, it finds it on the classpath as before and uses the cached entry only when its fingerprint and every one it recorded still match. Otherwise the class compiles as if there were no cache, and the next save replaces the entry. A classpath class that depends on one a class loader defined isn't kept, and neither is a method whose code reads one, since on the JVM the application loader couldn't see it. Nor is a method holding an instruction that throws because a class isn't there, a class file doesn't parse or a method is missing, so the run after the file is added or fixed sees it (`test/libcache/fix` checks a class file cut short, then whole). A class a loader of the program's own defines (`ClassLoader.defineClass` from a loader that isn't one of the JDK's: Spring Boot's fat-jar launcher, Quarkus's fast-jar RunnerClassLoader, a plugin's `URLClassLoader`) is kept the same way, by the fingerprint of the bytes the loader handed over, in one file per class path, since the bytes have no path of their own. The next time a loader defines a class of that name from the same bytes, the entry is restored instead of parsed. Its dependencies are checked in two steps, because the loader decides what the names mean: the classes it inherits from when it's defined, and the classes its cached methods read when the first of them is called, which is when translating it would have loaded them, each asked of the defining loader; a dependency whose bytes changed makes the cached methods compile again (`test/libcache/def`). A hidden class, one defined without a name, or one a JDK loader defines isn't kept. Checking a class's dependencies through its loader means the loader is asked for every class the cached methods read, at the first call, where the JVM would ask for each when the code that names it first runs, so a loader that logs or counts its loads sees them earlier and together. Frameworks that define their classes this way are the fat jar's launcher and the fast-jar's RunnerClassLoader ([running Java programs](running-java-programs.md#frameworks)). A lambda's class can't be made again from its name alone, so an entry whose code names one is used only while that class is in the class table. That class takes its interfaces' default methods into its vtable, and it goes in `state.img` like a JDK class, so its name carries the fingerprints of the classpath and loader-defined interfaces it implements: before, a jar's interface that gained a default method between runs left the next run with the earlier class, and calling the new method threw `AbstractMethodError`. `test/libcache/lam` checks it, eight more runs: the interface from jars, then from directories, then from one directory holding v1's interface and then v2's.

Jackson's probe in `test/libs` compiled about 2,170 methods of classpath classes on every run before this. A warm run took 5.22 s and now takes 1.34 s (medians of five interleaved runs; the JVM takes 0.28 s). Of what's left, boot is about 0.23 s and loading some 4,700 cached units about 0.25 s, and the class entries cost something to parse: all 584 of Jackson's take about 150 ms. A class from a classpath directory is cached with Chez's inspector information, which JDK code goes without and a jar's class by default too, because a stack trace reads its line numbers from it ([how it works](how-it-works.md#stack-traces)) and it costs about as much memory as the code itself ([memory](#memory)); with `CHAMPACA_LINES=all`, which gives jar classes lines too, it costs Jackson 3.3 MB of generations (20.1 MB against 16.8) and about 0.1 s a warm run (1.34 s against 1.24). The 19 methods it still compiled were the probe's own, from the directory `target/libs/Jack`, which is cached now too.

Jackson's classes unpacked into a directory (1,103 class files) took 5.49 s a warm run when directories weren't cached and take 1.47 s now (medians of five interleaved runs); reading every class file for its CRC is part of that. Rebuilding one class there and running again compiles just that class and appends a generation of about 22 KB, which stays, like any replaced code, until the key directory goes. `javac` writes the same bytes for an unchanged source (the 223 class files of `test/java`, compiled twice, came out identical), so a full rebuild recompiles only the classes that changed and those that depend on them. A warm `Hello` is unchanged (0.42 s against 0.41). Cached code takes more memory than code compiled in the process: that run peaks at 568 MB against 508 MB when it compiled everything, and the same classes from jars peak at 557 MB, the 11 MB between them including the class files the run holds on to.

A jar's or a directory's file is read the first time a run loads a class from it, so a run pays only for the jars it uses, and a program without any never reads one. A warm `Hello` on a cache holding Jackson's 1 MB of entries reads none of them.

Every program shares the cache, but since the files go by the jar's or directory's path, two programs on different versions of a library, at different paths in `~/.m2`, keep their own entries. The classes a program's own loaders define go by the program's class path, so two fat jars at different paths keep theirs apart too. A class restored that way is still checked against a sealed superclass or interface, since the same bytes can come from another loader whose unnamed module the sealed type doesn't share (`test/libcache/def`, runs 13 to 15). A jar rebuilt at one path (a snapshot, an uberjar) replaces its own. A file no run has read for a week is deleted the next time a run writes one, while a replaced entry's compiled code stays in its generation file until the key directory goes. `CHAMPACA_STATS=1` says how many classes came from the library cache and how many methods of classpath classes compiled.

`bb test` checks it with `test/libcache`, one program run fourteen times over jars and directories that change between runs, its output including a jar class's code source, its package and one of its stack frames: `Base` gains a field in `base-v2.jar`, `Sub`'s bytes are the same in both jars, and `lc2.User`, in a jar of its own, writes `Sub`'s field without naming `Base`. The fifth and sixth runs put `Base` in a directory ahead of the jar, and the sixth gets it, with `LibCacheMain` from its own directory, back from the cache. `test/libcache/def` does the same for classes a loader of the program's own defines: a loader reading class files from a directory off the class path defines `A`, `B` and the interface `F` that a lambda in `A` implements, and a `B` with a field added before the one `A` reads makes `A`'s cached methods compile again while `A` itself comes back from the cache. Each run also checks how many classes came from the cache at least: the seventh, back on `base-v1.jar`, gets `Base` from that jar's own file, which the `base-v2.jar` runs left alone. The next three rebuild one path: `base.jar` holds v1, then v2 is copied over it, and its file's v1 entries must not answer for it. The last four do the same with a directory, `base-dir`, as `javac -d` rebuilding into one would, and one of them copies in a `Base` of the same size as v1's with another string in it, so only the CRC tells the two apart.

### When it's rebuilt

The directory name is a hash of everything the compiled code depends on: the JDK (its `release` file plus the size of `lib/modules` and a CRC-32 of the image's index, which names every resource with its offset and sizes, so a runtime copied with its cache keeps the key, as an [app bundle](app-bundles.md) needs), the translator's own source, the jolt runtime version, and whether `CHAMPACA_BT` is on. Change any of them and the next run builds a fresh cache in a new directory.

Two switches control it:

```sh
CHAMPACA_CACHE=off jolt -M -m champaca.main classes Main          # don't read or write it
CHAMPACA_CACHE_DIR=/tmp/ldk jolt -M -m champaca.main classes Main  # keep it somewhere else
```

### Upkeep

Old key directories are cleaned up when a new cache is written: any sibling directory no run has loaded for 7 days goes, along with leftovers from an interrupted write. A cache hit marks its directory as used, so another key still in use, such as the `CHAMPACA_BT` one, stays as long as something runs with it every week. Besides the age rule, only the 16 most recently used key directories are kept. That matters when the translator itself is being edited, since every edit makes a new key, and a day of that would otherwise leave gigabytes behind. Deleting a key directory under `~/.cache/champaca` is always safe, since the next run rebuilds what it needs. Leave `jolt/` beside them unless you mean it, since it holds the pinned jolt build. A `jmods/` there is left from versions that copied the JDK's jmods and can go.

### Memory

A warm process is mostly champaca's own data, not the program's. Chez's `(object-counts)` after a full collection, for the Spring Boot benchmark's extracted jar warm (`bench/`), before the work of 2026-10-29: a 592 MB live heap in a 1.2 GB peak RSS, of which strings 318 MB, pairs 103, compiled code 64, vectors 38, with the Java objects themselves under 15 MB (85k objects, 22k byte arrays). The strings were the class entries from `state.img` and the library files kept as Chez strings at four bytes a character (122 MB for 3.3 MB of files), the thawed class maps' strings with every method key and descriptor read apart per class (87 MB), the 159k interned symbols' names (46 MB) and compiled code's literals; the pairs were the inspector information of the classpath classes' code (4.3 million of them, with 73k return-point records and their live-variable masks, about 100 MB in all).

Three changes took the same run to a 352 MB live heap, 816 MB committed and 881 MB peak RSS. Class entries are kept as UTF-8 bytes until thawed and thawing interns their strings (so does registering a class's reflection metadata), and Chez's inspector information is compiled in only for classes from classpath directories, where the developer's own build lives, and for the classes defined while a source file runs; a jar's or another loader-defined class names its file in a stack trace but no line, as JDK frames do, and an inlined leaf of it that throws from a check shows its caller's frame ([limitations](limitations.md#other-gaps)), unless `CHAMPACA_LINES=all` (its own cache key, since the cached code differs; `off` keeps it out for directory classes too). The generation files shrank from 107 to 51 MB with it. A warm `Hello` peaks at 240 MB instead of 340. What's left is the compiled code (64 MB and its relocation tables), the symbols naming every method and vtable slot (46 MB of names), the thawed class maps and the collector's copying space: committed memory runs about twice the live heap, and `heap-reserve-ratio` makes no difference to it. The two intern tables (thawed entries' strings, registered classes' metadata strings) only grow: a program that defines many classes with distinct names or descriptors keeps one copy of each for the process. `bb heap <classpath> <Main> [types|roots|java]` (`tools/heap.clj`) takes these measurements for any program: the live heap by object type after a full collection, what each of the runtime's tables and `main.clj`'s state holds (with symbols and code as leaves, since a symbol's value reaches everything), and the Java object graph from the static fields.

Spike 76 looked at what's left with Chez's object backreferences, which name what holds each object. On a warm start the extracted jar's live heap was 312 MB by then, after the lazy class entries (spikes 73, 74). A fifth of it was strings, and most of those were names. Chez gives every top-level variable in a mutable environment, jolt's included, a label gensym on its first definition or reference, and the variable's value lives in that gensym, whose unique name is a fresh string of `*top*:` and the whole name. With one global per method, static field and conflict stub, that was 65,000 gensyms and 20 MB of names nothing else shared. A symbol whose `*top*` property is the symbol itself is its own label, the way every symbol of Chez's scheme environment is, so champaca labels its globals that way before anything refers to them: `define-global!` for the definitions, and a walk over every generated form before it's expanded, so a cached unit and the process loading it agree on the label (a symbol that already has one keeps it; one already bound, a Scheme or jolt procedure, is left alone). The warm start's live heap went from 312 to 285 MB, peak RSS from 762 to 714 MB, and the start from 2.58 to 2.47 s (four alternating runs each). What remains: compiled code with its relocation tables, about 75 MB, plus 10 MB of bignums that are the collector's live-slot masks for return points in frames wider than a fixnum, all of it the generated code itself; the plain symbols' names, 18 MB; and the library cache's packed entries, 20 MB, kept for the run because a dependency that turns out changed has to drop its entry from the jar's file.

## The call-site cache

Picking an overload ([calling-java](calling-java.md#which-overload-runs)) walks the class and its supertypes for methods with the right name, then scores each one against the arguments. That's slow to repeat in a loop, so `invoke`, `invoke-static` and `construct` remember the choice.

The key is the class, the method name and the *kind* of each argument: a Java value's class, an integer's range (byte, short, int, long or bigger: a `byte` or `short` parameter takes only an integer in its range), or the jolt type for anything else. A vector counts by the kind its elements share, whatever its length, and a vector of integers by the widest range among them, since that is what decides which array it fits. Those are exactly the things the scoring looks at, so the cached answer is the one a fresh lookup would give. Any other vector whose elements are of mixed kinds isn't cached, and a lookup that fails is never stored, so it fails the same way every time.

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

Inside this repo, `deps.edn` names a jolt commit, the v0.8.24 tag:

```clojure
:champaca/jolt {:git/url "https://github.com/jolt-lang/jolt.git"
                :git/sha "a9b24d4c9dd57e3615d68975eb457e7b16554da9"}
```

It's the release's own code, built from source. Built that way it ran a warm `Hello` in 0.42 s (median of eight, interleaved, on a loaded machine), where the installed release binary of the same version took 0.59 s and started jolt itself 70 ms slower. The same held for 0.8.23 (0.32 s against 0.45 s), and a local build of v0.8.23 runs a warm `Hello` as fast as one of v0.8.24, so the gap comes from how the release binaries are built, not from jolt's code.

0.8.24 is the minimum because it's the first release where `String(byte[], offset, length, charset)` copies only the slice ([jolt#1413](https://github.com/jolt-lang/jolt/pull/1413)). Champaca reads the runtime image's string table that way, and on an older jolt each read cost the whole table. It also has `java.io.RandomAccessFile` ([jolt#1375](https://github.com/jolt-lang/jolt/pull/1375), since 0.8.23) and a shutdown watcher that no longer ends the process when a profiling signal arrives ([jolt#1462](https://github.com/jolt-lang/jolt/pull/1462)). Its Chez Scheme patches 0006 and 0007 fix foreign calls with arguments on the stack ([jolt#1502](https://github.com/jolt-lang/jolt/pull/1502)), but `make build` only uses them when it builds its own Chez. A system Chez, such as Homebrew's, is used as it is, so champaca keeps refusing the signatures an unpatched Chez gets wrong (see the foreign function notes in [limitations](limitations.md)).

`bb jolt` builds the pinned commit:

```sh
bb jolt        # about 45 s, once per pin
bb test        # prints which jolt it picked
```

It makes a detached worktree of a jolt checkout (`$JOLT_SRC`, else `~/dev/jolt`, cloned from the URL if neither exists) at `~/.cache/champaca/jolt/<sha8>/src` (`<sha8>` being the commit's first eight characters), runs `make build` there and copies the binary next to it. `bb test` uses `$JOLT` when it's set, then the pinned build, then whatever `jolt` is on your `PATH`.

The pin only affects this repo's bb tasks that run jolt (`test`, `libs`, `example`, `nrepl`, `bench`). Champaca works on any released 0.8.24 or later.

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

### Int arithmetic

An `int` is a fixnum in `int`'s range by construction, but generated code did its arithmetic with Chez's checked `fx+`, `fx<` and the rest, which test every operand for a fixnum each time, and that was about a third of an `int[]` sum's time. Since spike 86 codegen emits macros from `runtime.ss` (`ix+`, `ix<`, `ix-and`, `int-div` and the rest) that expand to the unchecked forms (`#3%fx+`). `imul` is `fx*/wraparound` instead of generic `*`, since the low 32 bits are all that's kept, and `idiv` and `irem` test for zero inline instead of calling a procedure. Under `CHAMPACA_BT` the macros keep their checks, and every native champaca provides with an `int`, `boolean`, `byte`, `char` or `short` result is checked to return a fixnum in range, so a run with it set catches a value that isn't an `int` where the unchecked operations would compute garbage. A million elements, with a warm cache:

| Kernel | Before spike 86 | Now | JVM |
|---|---|---|---|
| summing an `int[]` | 3.5 ms | 2.2 ms | 0.32 ms |
| filling an `int[]` with `i * 3` | 3.8 ms | 2.2 ms | 0.16 ms |
| a for-each loop xoring an `int[]` | 2.9 ms | 2.2 ms | 0.32 ms |
| a `31 * h + b` hash over a `byte[]` | 4.4 ms | 3.5 ms | 0.96 ms |

Values from jolt are held to the same rule where they enter: an `int` parameter or an `implement` result refuses `1.5` or `nil` ([calling-java](calling-java.md#with-descriptors)).

### Long arithmetic

A `long` is an exact integer. Up to 60 bits that's a Chez fixnum and costs what an `int` does, but past 60 bits it's a bignum, and hashing, random numbers and `BigInteger` live there. Generic `*` on two such longs made a bignum of up to 128 bits, and cutting it back to 64 took three more bignum operations, so `x = x * 3 + 1` ran about 110 times slower than on the JVM. Since spike 84, generated code uses macros from `runtime.ss` (`ladd`, `lmul`, `lshl` and the rest): two fixnums stay inline, and anything else goes to a procedure that does the 64-bit operation on 32-bit halves in fixnum arithmetic and makes one bignum, the result. An `lcmp` followed by a branch on its result is one comparison, which never allocates.

That still left a bignum for each result past 60 bits. Since spike 85, a long that long operations make and use stays two fixnum variables inside a method, its high and low 32 bits, and becomes one value only where something else takes it: a call, a field or array store, a return, or a jump with it on the stack. An operation works on halves when an operand already is in halves or when its result goes straight on to another long operation. Otherwise it's the boxed macro from before, so a sum of small longs costs what it did. A long local that's only ever loaded and stored as a long, and gets a long operation's result somewhere, stays as halves from block to block too, unless an exception handler reads it. Kernels of a million steps, with a warm cache:

| Kernel | Before spike 84 | Spike 84 | Now | JVM |
|---|---|---|---|---|
| `x = x * 3 + 1` | 83 ms | 23 ms | 2.5 ms | 0.7 ms |
| FNV-1a over 1 MB | 60 ms | 27 ms | 4.0 ms | 1.3 ms |
| xorshift64 | 160 ms | 60 ms | 4.4 ms | 1.9 ms |
| MurmurHash3's `fmix64` | 333 ms | 102 ms | 7.4 ms | 0.8 ms |
| `Random.nextLong` | 407 ms | 108 ms | 45 ms | 16 ms |
| a long multiply, divide and remainder | 72 ms | 21 ms | 23 ms | 1.0 ms |
| comparing longs near the range's ends | 92 ms | 19 ms | 3.6 ms | 0.3 ms |
| a sum that stays under 60 bits | 2.2 ms | 2.0 ms | 2.1 ms | 0.3 ms |
| 100,000 `BigInteger` multiply and `mod` | 980 ms | 360 ms | 245 ms | 20 ms |

The multiply, divide and remainder row got a little slower, because the product is made in halves and then boxed for `ldiv`, which still works on whole values. `Random` and `BigInteger` keep their longs in fields and arrays, so they box at every store. `test/java/LongOpsCheck.java` checks every operation against the JVM's results on values each side of the fixnum edge, the halves' edges and the range's ends. `test/java/LongFuzzCheck.java`, generated by `test/fuzz/LongFuzzGen.java`, runs 60 random methods on longs (arithmetic, shifts, comparisons, conditional expressions, `x++`, arrays, fields, calls, a `try` whose handler reads the locals) and has to print what HotSpot prints.

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

Even checked once, an access still tested for null, read the array's vector out of its record and compared the index with its length, and the loop's test read the length again, every step. Since spike 87 a loop is versioned, as upstream OpenLDK does it: in a method without exception handlers, an innermost loop that does something to an array held in a local the loop never assigns is entered through a check, made once, that each such local holds an array. If they all do, a copy of the loop runs with each array's vector and length taken before the first step, so an access compares its index with that length and nothing else, and `a.length` is a variable. A null or anything else runs the loop as it was, so an exception comes where it would have. Bounds checks stay: dropping them saved 0.1 of 1.7 ms in a hand-written copy and would need a proof about the index. A million elements, with a warm cache, after spike 86's [int arithmetic](#int-arithmetic):

| Kernel | Before | Versioned | JVM |
|---|---|---|---|
| summing an `int[]` | 2.27 ms | 1.72 ms | 0.32 ms |
| summing a `long[]` | 2.69 ms | 2.01 ms | 0.32 ms |
| a `double[]` dot product | 5.31 ms | 3.64 ms | 0.95 ms |
| a for-each loop over an `int[]` | 2.22 ms | 1.44 ms | 0.32 ms |
| filling an `int[]` | 2.22 ms | 1.70 ms | 0.16 ms |
| summing to a bound `n` passed in | 2.06 ms | 1.72 ms | 0.32 ms |

The copy doubles the loop's code. A loop that reads a local the copy doesn't use as an array (`this` in `this.data[i]`, say) still gets one, which is then dropped, and its entry is one more tail call. Recursion inside such a method reaches about 25% less deep before `StackOverflowError`, still far past the JVM's default. `test/java/ArrayLoopCheck.java` checks the cases where the copy and the original could differ against HotSpot: nulls with and without steps, an index past the end after side effects, a second array that is null only when read, arrays the loop reassigns, jagged grids, `break` and `continue`, stores of every width, stack traces from inside the copy, and arrays moved by `dup_x1` and `swap`, which only hand-made bytecode does. Upstream's `ArrayLoopProbe`, `InlineLeafProbe` and `PrimitiveOptimizationProbe` run as they are.

Calls themselves cost little here: a call to a static method is about 1 ns on top of the loop, 3 ns into another class with its initialization check. Code like `StringBuilder.append` pays for many of them, though, so small leaf methods are now inlined and calls that can only reach one method skip dispatch ([how-it-works](how-it-works.md#the-shape-of-a-translated-method)). A static call to a leaf now costs nothing beyond the loop around it.

| Operation | Before | Now | JVM |
|---|---|---|---|
| Comparing two 100-byte arrays | 708 ns | 446 ns | 57 ns |
| `String.equals`, unequal, 3 characters | 38 ns | 27 ns | 4.8 ns |
| `String.length` | 10 ns | 6.7 ns | 1.9 ns |
| `ReentrantLock` lock and unlock | 42 ns | 34 ns | 13 ns |
| `StringBuilder.append(char)` | 42 ns | 36 ns | 2.0 ns |

Reading a 4 MB file a byte at a time through a `BufferedInputStream`, which takes a lock and a `try` for every byte, went from 2.1 s before any of this to 0.79 s, then 0.44 s, then 0.28 s, against 37 ms on the JVM.

What's left is spread thin. Each method call still goes through a top-level name and passes its live locals, only leaves get inlined, and safe-mode checks stay on field access and on `float` and `double` arithmetic (`fl+`).

### What threads cost a single thread

Thread support adds two things to every program, threaded or not: a monitor lookup on each `synchronized` entry and exit, and a check at each backward branch for a thread waiting to run. Measured against the same code without threads, a loop of 200,000 `StringBuffer.append`, `length` and `Hashtable.put` calls went from 377 ms to about 440 ms, a 20-million-step counting loop from 392 ms to about 420 ms, and `Bench` from 1.85 s to 1.86 to 1.90 s. Those numbers predate the monitor changes above, and the monitor lookup they blamed, unprofiled at the time, has since gone.

### What calling a jolt fn costs Java

Each call Java makes into one of your fns runs inside an exception handler, the one that turns a jolt error into a `RuntimeException` Java can catch ([calling-java](calling-java.md#exceptions)). The handler is only set up, and the jolt side is only asked to convert anything once an error is raised. `Collections.sort` on 200,000 integers with a jolt comparator makes 2,855,207 calls, and took 0.73 to 0.80 s without the handler and 0.82 to 0.88 s with it, alternating runs. That is about 30 ns of the roughly 270 ns each call costs.

A method of a class `define-class` made costs about the same as a fn. The same sort through a `Comparator` class took 1.0 s in each of three runs, alternating with a plain fn comparator at 1.03 to 1.09 s, on a machine with a load average near 7, so both numbers are higher than the ones above.

### What method handles cost

A method handle call goes through the runtime's handle table rather than straight to the method. In a loop of a million calls to a static `add(int, int)` (2026-11-06, a loaded machine), `invokeExact` took 59 ns and `invoke` with boxed `Object` arguments about 0.75 µs, most of that in boxing through `Integer.valueOf` and unboxing through a virtual call. A direct call to so small a method is compiled into the loop and costs about 1 ns. `invokeExact` converts nothing, since its type must be the handle's, which took it from 90 ns to 59. Linking a call site with a bootstrap of its own happens once, on its first call.

Evaluating `handles.ss` from source added about 20 ms to every start when method handles went in. Since then the warm path loads the runtime compiled (`runtime.so`, above), so that cost is now paid only on a cold start or with the cache off.

### What file access costs

File natives call libc through Chez's foreign interface, so a read or write is one libc call plus copying between a Java `byte[]` (a Scheme vector of fixnums) and native memory, in 4 KB pieces for `java.io`. Writing a 4 MB file with `Files.write` took about 180 ms and reading it back with `Files.readAllBytes` about 145 ms, against 7 ms and 1 ms on the JVM. Reading the same file a byte at a time through a `BufferedInputStream` took 2.1 s against 37 ms: that loop is all generated code, which is the slow part ([The generated code](#the-generated-code)).

`files.ss` is one of the larger runtime files, and evaluating it took about 40 ms per start before the compiled runtime made that a one-time cost.

### A framework loader's own work

With its classes cached, the Spring Boot fat jar still started 3 s behind the extracted jar: the launcher's own code, reading nested jars and defining the classes, where HotSpot spends 80 ms. A sampling profiler (`bb prof`, [debugging](debugging.md#sampling-a-run)) put the time in five places, and a warm fat-jar start went from 6.7 to 5.5 s on the same cache, with these fixes:

- **The `Unaligned` accessors.** Every `ByteBuffer` read and write on a `byte[]` goes through `Unsafe.getIntUnaligned` and the rest, which HotSpot intrinsifies and which the JDK's Java fallback does as a native call per byte plus the assembly, under a `ScopedMemoryAccess` wrapper with a `try`/`finally` of its own. Both layers are overridden (`natives.ss`): a `byte[]`'s bytes are read from its vector directly, in either byte order, native memory through one `foreign-ref`. A loop of `getInt` and `getShort` on a heap buffer went from over 500 ns an iteration to 69.
- **Copies between arrays and native memory.** Every byte a file, socket or zlib stream moves crosses as a `foreign-ref` or `foreign-set!`, and at jolt's optimize level those are procedure calls with argument checks: the unchecked `#3%` forms are 17 times faster (29 ns to 1.7 ns a byte, measured). `native->vector!` and `vector->native!` in `runtime.ss` are what the copies use now; the vector accesses stay checked. `Unsafe.copyMemory` between two arrays copies in one pass, backwards when the ranges overlap (it built a temporary vector first).
- **String concatenation.** `jconcat` built every result as a list of UTF-16 code units from a recipe it re-parsed on each call. The recipe is parsed once per call site, and when every piece is Latin-1 (a `String` with coder 0, a literal below U+0100, which is nearly always) the result's bytes are copied straight from the pieces; anything else takes the code-unit path as before. Spring's launcher concatenates a URL per class.
- **`Reference.refersTo`.** The native looked the referent's slot up by class and field name on every call, and the loader's soft-reference caches call it a lot; the slot is looked up once.
- **Cached units.** A method's unit was compiled from `(define |name| (lambda ...))`, and a top-level `define` compiled to a port carries the expander's record of the binding (`$sc-put-cte`, a syntax object with its ribcage): 15% of a unit's bytes and about a fifth of its load time. It is `(set-top-level-value! '|name| (let ([|name| ...]) |name|))` now, the `let` keeping the code name stack traces read. The 18,000 units a warm Spring start loads took 785 ms, now 683.

What remained, measured on the same run: thawing the 2,584 class entries the launcher's loader defines (795 ms of parsing 20 MB of edn text, half of it vtables that mostly repeat the superclass's), loading the units (683 ms, Chez's own fasl reader at about 80 MB/s), the defining hook's rest (210 ms), and about 1.5 s in the loader's Java code itself, which is spread thin: inflating, `String.equals` and `indexOf`, hash codes, Spring's `ZipString.compare`. A Chez fasl blob per entry would read seven times faster than the edn text, but jolt's keywords come back from it as different objects, so the entry format stays edn. Spike 73 then split each entry in two, the map and its vtable, and a thaw leaves the vtable packed until something reads it, which a restored class never does: thawing fell to 485 ms and the warm fat-jar start from 5.45 to 4.88 s (three alternating runs each, same cache). Spike 74 did the same for the methods list, a quarter of the text, read when a call to the class is compiled, a subclass links or the class is reflected on: thawing fell to about 250 ms and the start by another half second (main 5.0 to 5.3 s against 4.5 to 4.6, under a load of 5). What remains of an entry is its fields, layout, dependencies and cached-method index, all read at restore time.

Spike 75 profiled what was left of the loader's own Java at a 1 ms interval: `String.hashCode`, `equals` and `indexOf`, the three HotSpot intrinsifies, were 0.2 s of a 4.5 s start as the JDK's Java loops. They are overridden with the same loops in Scheme over the arrays' vectors (`ArraysSupport.vectorizedHashCode` for every basic type the JDK hashes there, `StringLatin1.equals` and `indexOfChar`): a micro-benchmark on a 70-character string has `equals` 740 to 350 ns and `indexOf` 500 to 230 ns, the JVM at 9 and 12. The rest of that profile is the launcher's own logic spread thin (its `JarUrlConnection.connect` under a lock, zip header reads, URL parsing) and two fixed costs: `BigDecimal`'s class initialization, 0.1 s of `BigInteger` squaring whose 64-bit products are bignums here (145 ms, then 46 ms after spike 84's [long arithmetic](#long-arithmetic), and 24 ms after spike 85, against 57 ms for spike 84's code in the same runs), and inflating, where every byte crosses between a vector and native memory. `StringOpsCheck` pins the three against the JVM.

`bb test` checks the accessors with `UnalignedCheck`: every alignment and both byte orders on heap and direct buffers, writes through the same paths, the views, and a closed arena's buffer throwing as on the JVM.
