# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions will
follow [Semantic Versioning](https://semver.org/) once there is a first
release.

## [Unreleased]

The first public version. Champaca is still experimental: the
[limitations](docs/guide/limitations.md) page lists what is missing.

### Added

- **A Java runtime inside jolt.** Each method's bytecode becomes Chez Scheme
  source the first time it's called, and jolt compiles that to native code.
  Java objects are Chez records in jolt's heap. The JDK's own classes come
  from `$JAVA_HOME/lib/modules` (JDK 25), and the JDK boots as it does on a
  JVM, module system included.
- **The jolt API** (`champaca.api`): `invoke`, `invoke-static`, `construct`,
  fields, overloads picked from the arguments as `javac` picks them, jolt fns
  as single-method interfaces, `->clj` and the data conversions, `proxy`,
  and `define-class` for real Java classes (constructors, statics, static
  initializers, annotations) whose methods are jolt fns.
- **A `java`-like launcher** (`champaca.main`): `-cp` with wildcards, `-jar`,
  `-D`, `-ea`/`-da`, `-p`/`-m` and the module options, source files, preview
  features, `-version`, and the JDK's rules for finding `main`.
- **JDK areas checked against HotSpot**: reflection, annotations and records,
  method handles, VarHandles and invokedynamic, threads and monitors, stack
  traces, class loaders and classes defined at run time, serialization,
  files, TCP and UDP sockets, selectors, child processes, memory segments,
  downcalls and upcalls through `java.lang.foreign`, helpful
  NullPointerException messages, and HotSpot's error texts for linkage,
  class format and cast failures.
- **Class files from before Java 6**: `jsr`, `jsr_w` and `ret`, which javac
  1.4 and earlier compiled every `finally` into, so old jars such as
  commons-lang 2.4 run (its `SerializationUtils` failed with "opcode not in
  spike: jsr").
- **Libraries and frameworks**: commons-lang3, commons-lang 2.4, gson,
  jsoup, Jackson, Caffeine, Guava, SLF4J and SnakeYAML match HotSpot's output (`jolt libs`),
  and Spring Boot 4.1.2 and Quarkus 3.40.1 applications start in every
  packaging (`jolt bench`).
- **Caches**: a startup cache for the JDK's boot and compiled methods, and a
  library cache for classes from jars, directories and class loaders, each
  keyed by the bytes it was built from. The startup cache is also keyed by
  the jolt version, so a jolt upgrade never runs code compiled against the
  old one.
- **App bundles**: `jolt bundle` builds a directory that runs a program with
  no JDK installed.
- **Tasks**: `jolt test` (every test program against HotSpot's recorded
  output, plus the examples), `jolt battery`, `jolt linux`, `jolt libs`,
  `jolt bench`, `jolt prof`, `jolt heap`, `jolt metrics`, `jolt nrepl`, and the docs
  site tasks, and `jolt warm`.
- Guides under `docs/guide/`, `CONTRIBUTING.md`, and SPDX license headers
  on the sources, task files and scripts.
- **`jolt warm`**, a training run for the startup cache. It compiles the JDK
  paths programs reach early, so a first `String.format`, `SecureRandom` or
  time zone no longer costs seconds. A program using all three went from
  16.0 s to 0.93 s on a fresh cache.

### Changed

- `Thread.getStackTrace()` on another thread, and `Thread.getAllStackTraces()`,
  give that thread's frames, line numbers included, where they were empty
  (or, for `getStackTrace`, an `UnsatisfiedLinkError`). A thread waiting in
  `sleep`, `wait`, `park`, a contended monitor or blocking I/O reads as on
  the JVM, but for the native frame on top (`test/java/OtherStackCheck.java`).
- `Thread.getState()` says what a thread waits for, as on the JVM:
  `TIMED_WAITING` while it sleeps, `WAITING` or `TIMED_WAITING` in
  `Object.wait` and `LockSupport.park`, `BLOCKED` while it waits to enter a
  monitor. It said `RUNNABLE` for all of them.
- A class, method or field name holding `|` or `\` runs, as the JVM allows
  and Scala and Kotlin emit. Codegen bar-quoted every name as a Scheme
  symbol, and Chez reads no escape between bars, so such a class failed to
  load (`test/java/OddNameCheck.java`).
- The `MethodHandles` loop builders work: `loop`, `whileLoop`, `doWhileLoop`,
  both `countedLoop`s and `iteratedLoop`, with the JDK's own checks and
  texts. They failed with an `AbstractMethodError` for `MethodHandle.rebind`
  (`test/java/LoopHandleCheck.java`).
- `MethodHandle.asSpreader` and `asCollector` take a position, so
  `InvocationHandler.invokeDefault`, which spreads at position 1, works.
- `MethodHandles.explicitCastArguments` converts as the JVM's does. It
  converted as `asType` does, so narrowing gave garbage or an
  `ArrayIndexOutOfBoundsException`, a null threw instead of becoming zero,
  and a cast to an interface was checked (`test/java/ExplicitCastCheck.java`).
- `Lookup.bind`, `findVirtual` on `MethodHandle`'s `invoke` and `invokeExact`
  or on a `VarHandle` access mode, and `MethodHandles.varHandleInvoker` and
  `varHandleExactInvoker` work, with the JDK's texts. They failed with an
  `IllegalAccessException`, a `NoSuchMethodException`, an `InternalError`
  or a `NoClassDefFoundError`. A method lookup that finds nothing names the
  method by its type, `nope()int`, as HotSpot does
  (`test/java/InvokerCheck.java`).
- `MethodHandles.byteBufferViewVarHandle` works, every view type and access
  mode, over heap, direct, sliced and read-only buffers, with the JDK's own
  bounds, alignment and read-only checks. Its handles threw
  `UnsupportedOperationException` (`test/java/ByteBufferViewCheck.java`).
- The startup cache archives the module graph, as HotSpot's class data
  sharing does. A cold boot lets the JDK's own `ModuleBootstrap` build its
  `ArchivedModuleGraph` and writes that object graph to the cache; a warm
  boot rebuilds it in under 4 ms, and the JDK then skips the generated
  `SystemModules$default` and the module resolution. That class runs once,
  interpreted, when the archive is built, instead of being compiled: a cold
  `Hello` went from 5.3 s to 4.4 s, a warm one is unchanged.
- Sockets speak IPv6. The JDK is told IPv6 is there when it is, as on the
  JVM, so sockets are dual stack, `::1` and other IPv6 literals connect and
  bind, lookups return IPv6 addresses too, and `NetworkInterface` lists
  IPv6 addresses (on a Mac every interface matched the JVM's).
  `test/java/Ipv6Check.java` covers TCP, the NIO channels, UDP, lookups and
  the loopback interface. `-Djava.net.preferIPv4Stack=true` turns it off.
- A method over 4,000 bytes of bytecode does its array accesses outside
  loops through calls instead of inline checks, which made the JDK's locale
  tables slow to compile: on a cache holding only the boot a first
  `String.format` took 5.2 s instead of 12.0 s.
- The repo's tasks run on jolt: `jolt test`, `jolt battery`, `jolt linux` and the
  rest, from the same `bb.edn`, so babashka is no longer needed. The pinned jolt
  is built by `tools/build-jolt.sh`, which needs only git and make, and the
  Linux image and CI use it.
- The jolt that `jolt test` and the other tasks build is the v0.8.26 tag,
  the first release with `System/mapLibraryName`, and running the tasks
  under jolt needs 0.8.26 or later; champaca still needs jolt 0.8.24 or
  later.
- The largest functions were split into named steps, guided by
  jolt-metrics (`jolt metrics`): codegen's `emit-block`, `compile-method`,
  `decode` and `render-steps`, the launcher's option parsing, the boot,
  `define-class`, the class loading and cache paths, the FFI arrangement
  and upcall stubs, and a few dozen smaller ones. Each split was checked
  against the old code on the same inputs and gives identical results.
- `src/champaca/main.clj`, 3275 lines, is now ten namespaces by job: the
  shared state, the launcher, the class path, the cache directory, linking,
  the library and startup caches, the loader, the JIT, the boot, and `main`,
  which keeps only the command line (112 lines). Each step moved code
  unchanged.
- The pieces that followed GPL-version-2-only OpenJDK code were written
  again from HotSpot 25's observed behavior: `src/champaca/npe.clj` (the
  helpful NullPointerException messages, from JEP 358), `class-cast-message`,
  the class name checks behind `Class.forName`, and `encode-path` (resource
  URLs). Each matches HotSpot on its check (NpeCheck, CastCheck,
  ClassNameCheck, ResourceCheck). The class name check now refuses a NUL only
  at the end of a name's segment, as HotSpot does, where it refused one
  anywhere.
