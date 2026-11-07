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
| `runtime/` | a `jlink` image of the modules `jdeps` says the class path needs, plus `jdk.charsets` and `jdk.localedata` (see below) and any `--add-modules`, with `bin/`, the VM and every native library removed, since champaca loads none of them; `legal/` stays |
| `app/` | the class path's jars and directories, copied |
| `cache/` | the startup cache, warmed at build time |

The launcher sets `JAVA_HOME` to `runtime/`, `CHAMPACA_CACHE_DIR` to `cache/` and `CHAMPACA_BUNDLE` to the directory, then runs the binary. For a jolt program it also sets `CHAMPACA_CLASSPATH`, which `(ldk/init!)` reads when it's given no `:classpath`, so the program doesn't need to know where its jars went. A jolt program should still call `(ldk/save-cache!)` before it exits, as it would outside a bundle ([performance](performance.md#generations)).

## The training run

The build leaves the builder's own `CHAMPACA_*` settings out of these runs, since `CHAMPACA_BT` or `CHAMPACA_LINES` would warm a cache under a key the launcher's runs never look for. Without `--train`, the build boots the JDK once (through `champaca -version`) and caches that boot. The program's own code, and whatever JDK code it reaches beyond the boot, still compiles on its first real run. `--train ARGS...` runs the program once at build time with those arguments, through the launcher, so the cache holds what that run compiled. It's the same idea as a training run for the JDK's own AOT cache. Everything after `--train` goes to the program, so put it last, and pick arguments that exercise the paths you care about without side effects you don't want at build time.

## Moving it

The bundle keeps its cache wherever it goes. Two things make that work:

- **Inside a bundle, the cache key hashes the runtime image's index** (a CRC-32 of every resource's name, offset and sizes) instead of the file's modification time, which a copy changes. The index doesn't cover the class bytes themselves, which is fine for the image a bundle ships with. Outside a bundle the key keeps the modification time, so a rewritten `lib/modules` still gets a fresh cache.
- **A class path entry inside `CHAMPACA_BUNDLE` is named relative to it** in the library cache, so the cache's entries for `app/x.jar` answer whether the bundle sits in `/opt` or in a home directory.

Measured on an M1 Pro on 2026-11-07 (load average about 5), with the bundle moved after it was built and run with `env -i`, so no `JAVA_HOME` and no JDK on the `PATH`, three runs each:

| Program | Bundle size | Warm run, moved | Compiled in that run |
|---|---|---|---|
| Hello (TreeMap, `String.format`, `java.time`) | 89 MB | 0.49 to 0.52 s | nothing |
| Jackson's probe (`test/libs/Jack.java`), three jars, `java.desktop` and `java.sql` | 128 MB | 0.87 to 0.92 s | nothing, output the same as HotSpot's |
| a jolt program serializing a map with Jackson | 150 MB | 0.76 s | nothing |

About 28 MB of each is `jdk.localedata` and `jdk.charsets`; `--locales none` drops the 27 MB of locale data (Hello with `java.base` alone was 61 MB).

On macOS the first launch of a freshly copied binary costs about 0.4 s more while the system checks it (0.57 s against 0.15 s for a binary that does nothing). That happens once per copy and isn't champaca's time.

## Read-only bundles

An installed bundle often can't write its own directory. When its cache can't be written, champaca keeps a cache for that bundle in the user's home instead, `~/.cache/champaca/bundles/<a hash of the bundle's path>/`. The first run copies the bundle's cache there and runs on the copy, saving what it compiles as any run does, so the next run starts warm with it. The bundle's own files are never written. A copy is made again only when the bundle's cache changes (a new install), which a small `<key>.seed` file beside the copy records; a copy that fails to load is dropped and rebuilt in the user's cache once, not copied again on every run. For Jackson's bundle (a 20 MB cache) that first run took 1.25 s against 1.02 to 1.04 s for the next two, the copy and a fresh binary's first launch together, at a load average of 8, with nothing compiled in any of them. `CHAMPACA_USER_CACHE_DIR` puts these caches elsewhere, and `off` turns them off.

Without a user cache (`off`, or no writable home), champaca reads the bundle's cache and skips the save: whatever a run compiles beyond it isn't kept, so code the training run didn't reach compiles on every run, and the run doesn't fail for it. When such a cache has nothing for this run, or holds something that doesn't load (a damaged `state.img`, a missing `runtime.so`, a cache built for another key), the run goes without a cache, as `CHAMPACA_CACHE=off` gives, and says so once on stderr: `champaca: the startup cache at <dir> can't be written and has none for this run, so this run uses none`. A writable cache drops a damaged copy and builds a new one. Damage found inside a class entry after the boot fails that one run in a writable cache; in one that can't be written at all it fails each run until the bundle is rebuilt or reinstalled.

## Signing

A `jolt build` binary is unsigned, and macOS refuses to start an unsigned binary that was downloaded (the file carries a quarantine attribute). `bb bundle --signable` builds the binaries with `jolt build --signable`, which needs a C compiler, so they can be signed afterwards with `codesign` and notarized before you hand the bundle to anyone. An ad-hoc signature (`codesign -s -`) on such a binary passes `codesign --verify --strict`, and the bundle runs as before; notarizing needs an Apple Developer ID, which the build can't supply. For a bundle you copied yourself, `xattr -d com.apple.quarantine <file>` lifts the mark. Linux has no such check.

## Limits

- A bundle runs on the OS and architecture it was built on, since its compiled code is Chez machine code, so build one on each platform you ship to.
- Its size is mostly the runtime and the binaries. `java.desktop`, which Jackson needs for `java.beans`, takes the runtime from 24 MB to 51 MB.
- The class path is fixed when the bundle is built. A program that loads plugins from elsewhere still can, but their classes cache under their real paths.
- `jdeps` decides the modules, and it can't see what a program reaches by name: a charset, a locale's data, a `ServiceLoader` provider, `jdk.zipfs`, `jdk.crypto.ec`. So `jdk.charsets` (3 MB) and `jdk.localedata` (27 MB) always go in, and a bundle formats `NumberFormat.getInstance(Locale.GERMANY)` and French dates as HotSpot does. `--locales en,de` keeps only those locales' data (jlink's `--include-locales`), and `--locales none` leaves it out, after which numbers and dates format as English. Anything else reached by name goes in `--add-modules`.
