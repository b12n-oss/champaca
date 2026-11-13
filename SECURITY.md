# Security

Please don't report a security problem in a public issue. Use GitHub's private vulnerability reporting instead: [report one here](https://github.com/b12n-oss/champaca/security/advisories/new). Only the maintainers see the report, and the discussion stays private until a fix is out.

## What champaca is, and isn't

Champaca runs a Java program inside a jolt process. It isn't a sandbox. A program run through it can do what it could do on HotSpot: read and write files, open sockets, start processes, and call native code through `java.lang.foreign` ([compatibility](docs/guide/compatibility.md)).

It also trusts the class files it runs more than HotSpot does. Champaca doesn't verify bytecode ([limitations](docs/guide/limitations.md)), and its generated code uses unchecked arithmetic on values the bytecode says are ints ([performance](docs/guide/performance.md)). That holds for what a compiler writes. Nothing checks it for a hand-made class file, which can do things HotSpot would refuse with a `VerifyError`, with results that aren't defined. Treat every class and jar you run as code you trust completely, as you would a native library.

So neither of these is a champaca vulnerability: a program doing something harmful because it was written to, and a crafted class file misbehaving when it's run. These are:

- champaca running code from a file it was only meant to read, such as a jar on the class path whose classes the program never loads, or a cache entry that belongs to another program;
- anything in the next section exposing more than it says.

A correctness gap, where champaca lets an ordinary compiled program do what HotSpot would refuse (an access check or a module boundary that doesn't hold), is a bug worth a normal issue.

## What to keep in mind

- **The startup cache.** Champaca keeps compiled code in `~/.cache/champaca`, or wherever `CHAMPACA_CACHE_DIR` points ([debugging](docs/guide/debugging.md)), and later runs load that code and run it ([performance](docs/guide/performance.md)). Anyone who can write to that directory can run code as you. Keep it in a directory only you can write to, and don't point `CHAMPACA_CACHE_DIR` at a shared one. An app bundle's cache lives in the bundle, or in `~/.cache/champaca/bundles` when the bundle's own directory can't be written ([app bundles](docs/guide/app-bundles.md)).
- **The nREPL server.** `jolt nrepl` listens on a free port on 127.0.0.1 and writes the port to `.nrepl-port` ([getting started](docs/guide/getting-started.md)). Anything that can connect to it can run code as you, so it's for your own machine only. Stop it when you're done.
- **Native code.** `System.loadLibrary`, `SymbolLookup.libraryLookup` and the foreign linker load and call native libraries, as on the JVM. A library is trusted with the whole process.

## Supported versions

Champaca hasn't had a release yet. Fixes go to `main`, and that's the version to run.
