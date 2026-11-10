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
- **Libraries and frameworks**: commons-lang3, gson, jsoup, Jackson,
  Caffeine, Guava, SLF4J and SnakeYAML match HotSpot's output (`jolt libs`),
  and Spring Boot 4.1.2 and Quarkus 3.40.1 applications start in every
  packaging (`jolt bench`).
- **Caches**: a startup cache for the JDK's boot and compiled methods, and a
  library cache for classes from jars, directories and class loaders, each
  keyed by the bytes it was built from.
- **App bundles**: `jolt bundle` builds a directory that runs a program with
  no JDK installed.
- **Tasks**: `jolt test` (every test program against HotSpot's recorded
  output, plus the examples), `jolt battery`, `jolt linux`, `jolt libs`,
  `jolt bench`, `jolt prof`, `jolt heap`, `jolt metrics`, `jolt nrepl`, and the docs
  site tasks.
- Guides under `docs/guide/`, `CONTRIBUTING.md`, and SPDX license headers
  on the sources, task files and scripts.

### Changed

- The repo's tasks run on jolt: `jolt test`, `jolt battery`, `jolt linux` and the
  rest, from the same `bb.edn`, so babashka is no longer needed. The pinned jolt
  is built by `tools/build-jolt.sh`, which needs only git and make, and the
  Linux image and CI use it.
- The jolt that `jolt test` and the other tasks build is jolt's main at
  3db8a534, the first commit with `System/mapLibraryName`, until a release
  carries it; champaca still needs jolt 0.8.24 or later.
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
