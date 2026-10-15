# Performance

Three things decide how fast champaca feels. The startup cache sets how long `init!` takes. The call-site cache sets what each `invoke` costs. And the generated code itself sets how fast Java runs once it's going; that one hasn't been worked on yet.

All numbers here were measured on macOS arm64 with the jolt pinned in `deps.edn`.

## The startup cache

Booting the JDK means parsing classes, translating their methods to Scheme and compiling them: 201 classes and 362 methods before `main` can start. The first run saves the result under `~/.cache/champaca/<key>/`: the compiled code as a Chez `boot.so` and the class table as a jolt image, `state.img`. Later runs load both and skip all of that.

| Program | Cache off | Warm cache |
|---|---|---|
| `Hello` | 1.21 s | 0.42 s |
| `Streams` | 1.75 s | 0.51 s |
| `examples/interop.clj` | 1.33 s | 0.47 s |

Each is three runs, which agreed to within 0.02 s, and the warm column uses a cache with no extra generations (see Upkeep).

### What's in it, and what isn't

Only JDK code is cached. Your own classes, from the classpath, compile fresh on every run, so a big application still pays for its own code at startup.

When a run compiles JDK methods the cache didn't have, `champaca.main` appends them as a new generation, `extra-<n>.so`, on the way out. From jolt, `(ldk/save-cache!)` does the same; call it before the process exits. It costs almost nothing when there's nothing new.

### When it's rebuilt

The directory name is a hash of everything the compiled code depends on: the JDK (its `release` file plus the size and mtime of `java.base.jmod`), the translator's own source, the jolt runtime version, and whether `CHAMPACA_BT` is on. Change any of them and the next run builds a fresh cache in a new directory.

Two switches control it:

```sh
CHAMPACA_CACHE=off jolt -M -m champaca.main classes Main          # don't read or write it
CHAMPACA_CACHE_DIR=/tmp/ldk jolt -M -m champaca.main classes Main  # keep it somewhere else
```

### Upkeep

Nothing cleans up after it yet. Old directories from earlier keys stay on disk, and generations are never merged. Each `extra-<n>.so` adds to load time, and it adds up faster than you'd guess: one `bb test` from an empty cache leaves 14 generations, and after it a warm `Hello` takes 0.77 to 0.79 s instead of 0.42 s. Deleting a key directory under `~/.cache/champaca` is always safe, since the next run rebuilds what it needs. Leave `jmods/` and `jolt/` beside them unless you mean it: they hold the module index with its copied zips, and the pinned jolt build.

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

Every Java local and stack slot becomes a mutable Scheme variable, and every call looks up a top-level symbol. Neither has been profiled or tuned, and it shows. `test/java/Bench.java` computes `fib(32)` and a 50-million-step loop: it takes 2.34 s on champaca, of which about 0.84 s was startup on a cache carrying 19 generations, against 0.15 s on the JVM, which needs about 0.11 s just to start. That leaves roughly 1.5 s of computing against well under 0.1 s. Code that spends its time inside Java, rather than crossing between jolt and Java, will feel this more than anything else on the page.
