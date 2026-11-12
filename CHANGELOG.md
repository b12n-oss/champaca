# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions will
follow [Semantic Versioning](https://semver.org/) once there is a first
release.

Each entry goes under the kind of change it is: **Added** for something that
works now and didn't (grouped by area), **Changed** for behavior a program
may notice, usually champaca coming into line with the JVM, **Fixed** for
bugs, **Performance** for speed and memory, and **Internal** for refactors
with no change in behavior. New entries go first in their group, and each
names the check that covers it.

## [Unreleased]

The first public version. Champaca is still experimental: the
[limitations](docs/guide/limitations.md) page lists what is missing.

### Added

#### Foundations

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

#### Networking

- Multicast works: `DatagramChannel.join`, `MembershipKey.drop`, `block`
  and `unblock`, and `MulticastSocket.joinGroup`/`leaveGroup`, for IPv4 and
  IPv6 groups, source-specific joins included, with the JDK's refusals of
  what macOS can't filter, where every join stopped at an
  `UnsatisfiedLinkError` (`test/java/MulticastCheck.java`, the same as
  HotSpot on a Mac and in a Linux container).
- Sockets speak IPv6. The JDK is told IPv6 is there when it is, as on the
  JVM, so sockets are dual stack, `::1` and other IPv6 literals connect and
  bind, lookups return IPv6 addresses too, and `NetworkInterface` lists
  IPv6 addresses (on a Mac every interface matched the JVM's).
  `test/java/Ipv6Check.java` covers TCP, the NIO channels, UDP, lookups and
  the loopback interface. `-Djava.net.preferIPv4Stack=true` turns it off.

#### Files and processes

- `ProcessHandle` knows processes as the JVM does: start times (so a handle
  to a process that has gone never signals one that reused its pid),
  `children()`, `descendants()` and `allProcesses()`, and `Info`'s command,
  arguments, command line, user, start time and CPU time, where `Info` was
  empty and `children()` stopped at a missing native
  (`test/java/ProcessHandleCheck.java`, the same as HotSpot on macOS and
  Linux).
- `java.util.prefs` works: on macOS through CFPreferences, in the layout
  HotSpot leaves in `~/Library/Preferences`, so each reads the other's
  nodes, and on Linux through `~/.java` with its lock files, where both
  threw `UnsatisfiedLinkError` (`test/java/PrefsCheck.java`).
- `Files.getFileStore` and `FileSystem.getFileStores` work, on macOS and
  Linux, with the JVM's mount entries. They failed with an
  `UnsatisfiedLinkError` (`test/java/FileStoreCheck.java`).
- Setting a file's creation time works on macOS (`BasicFileAttributeView.setTimes`
  through `setattrlist`), where it threw `UnsatisfiedLinkError`, so
  commons-io's `FileUtils.copyFile`, which keeps the source's times, works
  too. commons-io 2.17.0 is the thirteenth library `jolt libs` checks
  (`test/java/FileTimesCheck.java`, `test/libs/Cio.java`).
- `available()` on a pipe, a terminal or a socket's file descriptor counts
  the bytes waiting, as the JVM asks the kernel, where it was 0, and standard
  input's counts what champaca has buffered too
  (`test/java/AvailableCheck.java`).

#### Monitoring and management

- `ManagementFactory.getCompilationMXBean()` answers, where it was null as on a
  JVM without a JIT: `sun.management.compiler` names champaca's JIT, and the
  total compilation time is the time spent translating and compiling methods
  (`test/java/CompilationBeanCheck.java`).
- GC notifications: a listener on a garbage collector bean hears each
  collection as the JDK's `GarbageCollectionNotificationInfo`, with its
  action, cause and `GcInfo`, on a daemon `Notification Thread`, as on
  HotSpot, so GC pause metrics work; `getLastGcInfo` answers too
  (`test/java/GcNotificationCheck.java`).
- `ManagementFactory` lists a memory pool, `Chez heap`, and a garbage
  collector, `Chez`, where both lists were empty: the pool reports Chez's
  heap usage and peak, the collector Chez's collection count and time, and
  the MBean server lists them (`test/java/MemoryBeanCheck.java`).
- `ThreadMXBean` gives `ThreadInfo`, thread dumps and deadlock detection,
  where each stopped at a missing native. A thread's info says what it blocks
  or waits on and who owns that lock, how often it blocked and waited (with
  times once contention monitoring is on), the monitors and ownable
  synchronizers it holds, and its stack, the native it waits in on top as
  HotSpot shows it (`Object.wait0`, `Thread.sleepNanos0`, `Unsafe.park`; so
  does `Thread.getStackTrace` on another thread). `findMonitorDeadlockedThreads`
  and `findDeadlockedThreads` find cycles of threads blocked on monitors and
  parked on `ReentrantLock`s and the like. Taking a lock costs what it did:
  a dump asking for locked monitors or synchronizers finds them by walking
  the heap, as HotSpot finds synchronizers (`test/java/ThreadInfoCheck.java`).
- `Thread.getStackTrace()` on another thread, and `Thread.getAllStackTraces()`,
  give that thread's frames, line numbers included, where they were empty
  (or, for `getStackTrace`, an `UnsatisfiedLinkError`). A thread waiting in
  `sleep`, `wait`, `park`, a contended monitor or blocking I/O reads as on
  the JVM, but for the native frame on top (`test/java/OtherStackCheck.java`).

#### Reflection and classes

- `Class.getDeclaredClasses` and `getClasses` list a class's member classes,
  in the JVM's order, where they gave an empty array
  (`test/java/DeclaredClassesCheck.java`).
- A lambda's class lists the interface method it implements, so
  `getDeclaredMethods` and `getMethod` find it and `Method.invoke` runs it,
  as on the JVM. The list was empty (`test/java/LambdaReflectCheck.java`).
- A JDK class file found as a resource has the JVM's `jrt:` URL, from
  `getResource` and `getResources` through any loader, where it had none.
  Byte Buddy's injection strategy needed it (`test/java/JrtResourceCheck.java`).

#### Method handles and VarHandles

- The `MethodHandles` loop builders work: `loop`, `whileLoop`, `doWhileLoop`,
  both `countedLoop`s and `iteratedLoop`, with the JDK's own checks and
  texts. They failed with an `AbstractMethodError` for `MethodHandle.rebind`
  (`test/java/LoopHandleCheck.java`).
- `MethodHandle.asSpreader` and `asCollector` take a position, so
  `InvocationHandler.invokeDefault`, which spreads at position 1, works.
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

#### Libraries

- Netty 4.1.115, Log4j 2.24.1 and Jackson's XML module 2.18.1 over
  Woodstox join the libraries `jolt libs` checks against the JVM, making
  twenty: an NIO event loop with an echo server and pooled direct buffers,
  plugin-based logging, and XML through StAX (`test/libs/Nty.java`,
  `L4j.java`, `Jxml.java`, the same on macOS and Linux).
- Joda-Time 2.13.0, Guice 7.0.0, Kryo 5.6.2 and commons-compress 1.27.1
  join the libraries `jolt libs` checks against the JVM, making seventeen:
  time zones from a jar's own data, dependency injection, serialization
  through `Unsafe` and Objenesis, and archives (`test/libs/Joda.java`,
  `Guic.java`, `Kry.java`, `Comp.java`, the same on macOS and Linux).
- picocli 4.7.6, the H2 database 2.3.232 and Byte Buddy 1.17.5 join the
  libraries `jolt libs` checks against the JVM, making twelve
  (`test/libs/Pico.java`, `test/libs/H2.java`, `test/libs/Buddy.java`).

### Changed

Behavior that now follows the JVM where champaca did something else, which a
program may notice, and changes to the tasks.

- A foreign call whose stack arguments Chez Scheme 10.4.1 would put in the
  wrong place is made, not refused, when jolt says its Chez places them
  right (`jolt.ffi/stack-args-correct?`, a jolt with Chez patches 0006/0007
  or a Chez with the upstream fix). Asked once, on the first such signature;
  a jolt that answers false, can't tell or is older keeps the refusals.
- `sun.misc.Unsafe` warns as on JDK 25: a library that touches memory through
  it (Guava, Kryo) prints the JDK's deprecation warning on stderr, the same
  text as HotSpot's, where champaca set `sun.misc.unsafe.memory.access` to
  `allow` and nothing could change it. `-Dsun.misc.unsafe.memory.access=allow`
  turns the warning off and `deny` makes the call throw, as on the JVM.
- An annotation is a `java.lang.reflect.Proxy` over the JDK's
  `AnnotationInvocationHandler`, as on the JVM, where champaca generated a
  class of its own for it: `Proxy.isProxyClass` is true for it and a failed
  cast names the proxy class as HotSpot does. Spring Boot's start time is
  unchanged, cold and warm.
- A lambda's class is one per call site, as on the JVM: a hidden class
  named `Caller$$Lambda/0x...` in its caller's package, module and loader,
  its caller's nestmate, where every call site with the same interface
  shared one `champaca.$$Lambda$...` class in the boot loader's unnamed
  module (`test/java/LambdaSiteCheck.java`). Warm starts and memory are
  unchanged on a Quarkus fast-jar.
- A lambda's class name holds `$$Lambda`, as the JVM's do, so a framework
  that tells lambdas by it (Spring's `ClassUtils.isLambdaClass`) does here
  too: `champaca.$$Lambda$...`, was `champaca.Lambda$...`.
- VarHandle combinators screen their filters for checked exceptions as the
  JDK does: a direct handle declaring one is refused with the JDK's
  `IllegalArgumentException`, and a checked exception from any other filter
  becomes `IllegalStateException`, except under `filterCoordinates`
  (`test/java/VarHandleFilterCheck.java`).
- `MethodHandles.Lookup`'s `find*`, `unreflect*` and `findVarHandle` check
  access as the JVM does, with its `IllegalAccessException` texts, where any
  lookup reached any member: a private member of another class, a package
  member from outside, a public lookup on a non-public class and `findSpecial`
  for another class are refused, and a protected method from another package
  takes the lookup class as its receiver (`test/java/LookupAccessCheck.java`).
- `Field.get`, `Field.set` and the typed accessors check access as the JVM
  does when the field wasn't made accessible, with the JDK's
  `IllegalAccessException` text: a private field of another class is refused,
  and a protected one only a subclass may reach, on its own kind of object.
  They used to read and write any field (`test/java/FieldAccessCheck.java`).
- `-jar` applies the main jar's `Add-Opens`, `Add-Exports` and
  `Enable-Native-Access` manifest attributes, as the `java` launcher does,
  where they were ignored: a Quarkus fast-jar logged an
  `IllegalAccessError` from its thread pool at shutdown (`test/jarmain`).
- `native.encoding`, `sun.jnu.encoding` and the console streams' encodings
  come from the locale's codeset (`nl_langinfo`) as the JDK's launcher reads
  them, where they were always UTF-8. With no locale set, Linux gets
  `ANSI_X3.4-1968` and `System.out` writes `?` for what ASCII can't hold, as
  HotSpot does; macOS keeps UTF-8 unless `LANG` or `LC_*` names another
  codeset (`test/encoding`, recorded from HotSpot on both systems).
- Two of the socket layer's platform answers now match HotSpot 25.0.2's: a
  socket's write side isn't shut before close on macOS, and IPv6 options
  apply to an IPv6 socket bound to an IPv4 address on Linux.
- A class of your own in a package named `champaca.something` has its frames
  in stack traces and is the caller `MethodHandles.lookup()`, `Class.forName`
  and the other caller-sensitive methods see. Only champaca's generated
  classes, in the package `champaca` itself, are left out.
- The repo's tasks run on jolt: `jolt test`, `jolt battery`, `jolt linux` and the
  rest, from the same `bb.edn`, so babashka is no longer needed. The pinned jolt
  is built by `tools/build-jolt.sh`, which needs only git and make, and the
  Linux image and CI use it.
- The jolt that `jolt test` and the other tasks build is the v0.8.26 tag,
  the first release with `System/mapLibraryName`, and running the tasks
  under jolt needs 0.8.26 or later; champaca still needs jolt 0.8.24 or
  later.

### Fixed

- A static field that champaca's own runtime reads (`ValueLayout.JAVA_INT`
  for a downcall's captured `errno`) keeps its value on a warm run. The
  module-graph archive's walk over static fields gave one whose class hadn't
  registered yet a label of its own, the class's code was compiled against
  it, and the next run read the field as unset: `captureCallState` threw a
  `NullPointerException` on every warm run from a fresh cache
  (`test/java/CaptureStateCheck.java`, run cold then warm by `jolt test`).
- A class on the class path in a package one of the JDK's modules owns no
  longer replaces the JDK's class or adds one to the package: an old jar
  carrying `javax.xml` classes gets `java.xml`'s, and a class the JDK doesn't
  have there isn't found, as on the JVM. A package outside the boot layer is
  still the class path's (`test/java/ShadowCheck.java`).
- `Thread.getState()` says what a thread waits for, as on the JVM:
  `TIMED_WAITING` while it sleeps, `WAITING` or `TIMED_WAITING` in
  `Object.wait` and `LockSupport.park`, `BLOCKED` while it waits to enter a
  monitor. It said `RUNNABLE` for all of them.
- A local record's `toString` starts with its simple name, `Local[x=1]`,
  where it gave javac's binary suffix, `1Local[x=1]`
  (`test/java/RecordCheck.java`).
- A class, method or field name holding `|` or `\` runs, as the JVM allows
  and Scala and Kotlin emit. Codegen bar-quoted every name as a Scheme
  symbol, and Chez reads no escape between bars, so such a class failed to
  load (`test/java/OddNameCheck.java`).
- `MethodHandles.explicitCastArguments` converts as the JVM's does. It
  converted as `asType` does, so narrowing gave garbage or an
  `ArrayIndexOutOfBoundsException`, a null threw instead of becoming zero,
  and a cast to an interface was checked (`test/java/ExplicitCastCheck.java`).
- `Package.getPackage(String)`, `Package.getPackages()` and the application
  loader's `getDefinedPackage` and `getDefinedPackages` find a package of
  your class path as soon as one of its classes has loaded, as on the JVM,
  where they found it only after something had asked one of its classes for
  its package (`test/java/PackageCheck.java`).
- A class's `Package` is the one its class loader defines, so the
  application loader's `getDefinedPackage`, `getDefinedPackages` and
  `Package.getPackage` find your packages, and a `URLClassLoader`'s class
  reports the package it defined from its jar's manifest
  (`test/java/PackageCheck.java`).

### Performance

- The startup cache archives the module graph, as HotSpot's class data
  sharing does. A cold boot lets the JDK's own `ModuleBootstrap` build its
  `ArchivedModuleGraph` and writes that object graph to the cache; a warm
  boot rebuilds it in under 4 ms, and the JDK then skips the generated
  `SystemModules$default` and the module resolution. That class runs once,
  interpreted, when the archive is built, instead of being compiled: a cold
  `Hello` went from 5.3 s to 4.4 s, a warm one is unchanged.
- A method over 4,000 bytes of bytecode does its array accesses outside
  loops through calls instead of inline checks, which made the JDK's locale
  tables slow to compile: on a cache holding only the boot a first
  `String.format` took 5.2 s instead of 12.0 s.
- `Field.get` and `Field.set` take about a third of the time they did, 0.4 µs
  a call from 1.3 µs on a public instance field.
- A process keeps the parsed class files of its 256 most recently used
  classes rather than all of them, parsing any other again when more of its
  methods compile: Jackson's probe's live heap after `main` went from 292 to
  263 MB at the same speed (`CHAMPACA_PARSED_LIMIT` sets the number).

### Internal

Refactors and licensing work with no change in behavior.

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
- The largest runtime files were split by job, guided by `jolt metrics`:
  `natives.ss` into `management.ss`, `memory.ss`, `threads.ss`,
  `loaders.ss` and `varhandles.ss`, `files.ss` into `zip.ss` and
  `prefs.ss`, `runtime.ss` into `strings.ss`, `modules.ss`, `units.ss` and
  `launch.ss`, `net.ss` into `netif.ss`, and `codegen.clj` into
  `champaca.cgtext`, `bytecode`, `linkage` and `lambdas`. Code moved
  unchanged; the generated Scheme of 37 programs is identical before and
  after.
- The pieces that followed GPL-version-2-only OpenJDK code were written
  again from HotSpot 25's observed behavior: `src/champaca/npe.clj` (the
  helpful NullPointerException messages, from JEP 358), `class-cast-message`,
  the class name checks behind `Class.forName`, and `encode-path` (resource
  URLs). Each matches HotSpot on its check (NpeCheck, CastCheck,
  ClassNameCheck, ResourceCheck). The class name check now refuses a NUL only
  at the end of a name's segment, as HotSpot does, where it refused one
  anywhere.
