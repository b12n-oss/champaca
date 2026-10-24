# Champaca guides

Champaca runs Java bytecode inside a jolt process, so jolt code can call Java libraries as ordinary procedure calls. These pages cover using it, in roughly the order you'd need them. The project is a spike, and [limitations](limitations.md) lists what isn't there yet.

## Using it

- [getting-started](getting-started.md): what you need installed, the first call, and depending on champaca from your own project.
- [calling-java](calling-java.md): `construct`, `invoke`, `invoke-static` and the field getters, how an overload gets picked, jolt fns as Java interfaces, and what an exception looks like on the jolt side.
- [data-conversion](data-conversion.md): what jolt values turn into on the way in (arrays, collections, boxes) and how `->clj` and `->java` copy data across.
- [jars-and-modules](jars-and-modules.md): a classpath of jars and directories, and classes from JDK modules beyond `java.base`.
- [running-java-programs](running-java-programs.md): running a compiled Java `main` straight from the command line.

## When it's slow or broken

- [performance](performance.md): the startup cache, the call-site cache, and the pinned jolt build.
- [debugging](debugging.md): the `CHAMPACA_*` switches, Java stack traces, and the error kinds `api.clj` raises.

## Background

- [how-it-works](how-it-works.md): from a `.class` file to native code, and how the JDK boots.
- [limitations](limitations.md): the shortcuts a real program will run into.

The top-level [README](../../README.md) has the short version of most of this. The files in `examples/` are tests that `bb test` runs, each asserting what its calls return, so they make a good second reference.
