# Security

Please don't report a security problem in a public issue. Use GitHub's private vulnerability reporting instead: [report one here](https://github.com/b12n-oss/champaca/security/advisories/new). Only the maintainers see the report, and the discussion stays private until a fix is out.

## What champaca is, and isn't

Champaca runs a Java program inside a jolt process. It isn't a sandbox. A program run through it can do what it could do on HotSpot: read and write files, open sockets, start processes, and call native code through `java.lang.foreign` ([compatibility](docs/guide/compatibility.md)). Run only programs and libraries you would run on a JVM.

So a program doing something harmful because it was written to isn't a champaca vulnerability. These are:

- champaca letting a program do something HotSpot would refuse it, such as a module boundary, an access check or a bounds check that doesn't hold;
- a class file, jar or bundle that champaca only reads (to load, cache or inspect it) making champaca itself misbehave in a way that crosses one of the boundaries below;
- anything below exposing more than it should.

## What to keep in mind

- **The startup cache.** Champaca keeps compiled code in `~/.cache/champaca`, or wherever `CHAMPACA_CACHE_DIR` points ([debugging](docs/guide/debugging.md)), and later runs load that code and run it ([performance](docs/guide/performance.md)). Anyone who can write to that directory can run code as you. Keep it in a directory only you can write to, and don't point `CHAMPACA_CACHE_DIR` at a shared one. An app bundle's cache lives in the bundle, or in `~/.cache/champaca/bundles` when the bundle's own directory can't be written ([app bundles](docs/guide/app-bundles.md)).
- **The nREPL server.** `jolt nrepl` listens on a free port on 127.0.0.1 and writes the port to `.nrepl-port` ([getting started](docs/guide/getting-started.md)). Anything that can connect to it can run code as you, so it's for your own machine only. Stop it when you're done.
- **Native code.** `System.loadLibrary`, `SymbolLookup.libraryLookup` and the foreign linker load and call native libraries, as on the JVM. A library is trusted with the whole process.

## Supported versions

Champaca hasn't had a release yet. Fixes go to `main`, and that's the version to run.
