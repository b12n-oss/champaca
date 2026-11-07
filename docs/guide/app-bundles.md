# App bundles

Champaca never starts a JVM. It reads the JDK's class files and a few data files from `$JAVA_HOME`, and jolt runs everything else as native code. So an app can ship with a slice of the JDK's files beside it, and the person running it doesn't need a JDK at all. `bb bundle` builds that slice, a native binary of champaca, and a warmed startup cache into one directory you can copy anywhere.

## Building one

A Java program, given its class path and main class:

```sh
bb bundle -o out/jack -cp target/classes:lib/jackson-databind.jar:lib/jackson-core.jar:lib/jackson-annotations.jar \
          -m Jack --train
```

A jolt program that calls Java through `champaca.api`, given its namespace and its project:

```sh
bb bundle -o out/demo --jolt demo.core --project ../demo -cp lib/jackson-databind.jar:... --name demo --train x y
```

Relative paths are the champaca checkout's, since bb runs its tasks there. Building needs `JAVA_HOME` set to a JDK 25 (for `jdeps` and `jlink`) and jolt (the pinned build, `$JOLT`, or `jolt` on your `PATH`).

What lands in the directory:

| Path | What it is |
|---|---|
| `bin/NAME` | the launcher, a shell script; call it from anywhere, through a symlink too |
| `lib/` | `champaca`, champaca's own `jolt build` binary, and for `--jolt` the program's binary |
| `runtime/` | a `jlink` image of the modules `jdeps` says the class path needs (plus `--add-modules`), with `bin/`, the VM and every native library removed, since champaca loads none of them; `legal/` stays |
| `app/` | the class path's jars and directories, copied |
| `cache/` | the startup cache, warmed at build time |

The launcher sets `JAVA_HOME` to `runtime/`, `CHAMPACA_CACHE_DIR` to `cache/` and `CHAMPACA_BUNDLE` to the directory, then runs the binary. For a jolt program it also sets `CHAMPACA_CLASSPATH`, which `(ldk/init!)` reads when it's given no `:classpath`, so the program doesn't need to know where its jars went. A jolt program should still call `(ldk/save-cache!)` before it exits, as it would outside a bundle ([performance](performance.md#generations)).

## The training run

Without `--train`, the build boots the JDK once (through `champaca -version`) and caches that boot. The program's own code, and whatever JDK code it reaches beyond the boot, still compiles on its first real run. `--train ARGS...` runs the program once at build time with those arguments, through the launcher, so the cache holds what that run compiled. It's the same idea as a training run for the JDK's own AOT cache. Everything after `--train` goes to the program, so put it last, and pick arguments that exercise the paths you care about without side effects you don't want at build time.

## Moving it

The bundle keeps its cache wherever it goes. Two things make that work:

- **The cache key hashes the runtime image's index** (a CRC-32 of every resource's name, offset and sizes), not the file's modification time, which a copy changes. Hashing the 1.5 MB index of a full JDK takes about 2 ms.
- **A class path entry inside `CHAMPACA_BUNDLE` is named relative to it** in the library cache, so the cache's entries for `app/x.jar` answer whether the bundle sits in `/opt` or in a home directory.

Measured on an M1 Pro on 2026-11-07, with the bundle moved after it was built and run with `env -i`, so no `JAVA_HOME` and no JDK on the `PATH`:

| Program | Bundle size | Warm run, moved | Compiled in that run |
|---|---|---|---|
| Hello (TreeMap, `String.format`, `java.time`), `java.base` only | 61 MB | 0.44 s | nothing |
| Jackson's probe (`test/libs/Jack.java`), three jars, `java.desktop` and `java.sql` | 100 MB | 0.82 to 0.85 s | nothing, 449 classes from the library cache |
| a jolt program serializing a map with Jackson | 121 MB | 0.71 to 0.73 s | nothing |

On macOS the first launch of a freshly copied binary costs about 0.4 s more while the system checks it (0.57 s against 0.15 s for a binary that does nothing). That happens once per copy and isn't champaca's time.

## Read-only bundles

An installed bundle often can't write its own directory. Champaca reads a cache it can't write and skips the save: whatever a run compiles beyond the cache isn't kept, and the run doesn't fail for it. Code the training run didn't reach then compiles on every run, so train the paths that matter. A cache root that can't be written and holds no cache for this key (a bundle built by another jolt, say) means a run without a cache, as `CHAMPACA_CACHE=off` gives.

## Signing

A `jolt build` binary is unsigned, and macOS refuses to start an unsigned binary that was downloaded (the file carries a quarantine attribute). `bb bundle --signable` builds the binaries with `jolt build --signable`, which needs a C compiler, so they can be signed afterwards with `codesign` and notarized before you hand the bundle to anyone. An ad-hoc signature (`codesign -s -`) on such a binary passes `codesign --verify --strict`, and the bundle runs as before; notarizing needs an Apple Developer ID, which the build can't supply. For a bundle you copied yourself, `xattr -d com.apple.quarantine <file>` lifts the mark. Linux has no such check.

## Limits

- A bundle runs on the OS and architecture it was built on, since its compiled code is Chez machine code, so build one on each platform you ship to.
- Its size is mostly the runtime and the binaries. `java.desktop`, which Jackson needs for `java.beans`, takes the runtime from 24 MB to 51 MB.
- The class path is fixed when the bundle is built. A program that loads plugins from elsewhere still can, but their classes cache under their real paths.
- `jdeps` decides the modules. A program that reaches a module only by reflection or `ServiceLoader` needs it named in `--add-modules`.
