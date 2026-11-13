# Champaca guides

Champaca runs Java bytecode inside a jolt process, so jolt code can call Java libraries as ordinary procedure calls. These pages cover using it, in roughly the order you'd need them. The project is a spike, and [limitations](limitations.md) lists what isn't there yet.

## Using it

- [getting-started](getting-started.md): what you need installed, the first call, and depending on champaca from your own project.
- [calling-java](calling-java.md): `construct`, `invoke`, `invoke-static` and the field getters and setters, how an overload gets picked, jolt fns as Java interfaces, proxies, real Java classes defined from jolt, threads, and what an exception looks like on the jolt side.
- [data-conversion](data-conversion.md): what jolt values turn into on the way in (arrays, collections, boxes) and how `->clj` and `->java` copy data across.
- [jars-and-modules](jars-and-modules.md): a classpath of jars and directories, and classes from JDK modules beyond `java.base`.
- [running-java-programs](running-java-programs.md): running a compiled Java `main` straight from the command line, and Spring Boot and Quarkus applications in their packagings.
- [jvm-languages](jvm-languages.md): Clojure, Groovy, Scala, Kawa, ABCL and Kotlin on champaca, their compilers included: the command for each, what's been checked against HotSpot, and how fast they start.
- [checking-a-port](checking-a-port.md): `champaca.portcheck`, which runs a jolt function and the Java method it was written to replace on the same generated inputs and reports where they differ.
- [app-bundles](app-bundles.md): shipping a Java or jolt program as a directory that runs with no JDK installed, its cache warmed at build time and moving with it.

## When it's slow or broken

- [performance](performance.md): the startup cache, the call-site cache, what the generated code costs, and the pinned jolt build.
- [debugging](debugging.md): the `CHAMPACA_*` switches, Java stack traces, and the error kinds `api.clj` raises.

## Background

- [compared-to-babashka](compared-to-babashka.md): how champaca differs from babashka and GraalVM native-image, and when each fits.
- [compatibility](compatibility.md): what behaves as on the JVM, area by area, with the test that checks each.
- [how-it-works](how-it-works.md): from a `.class` file to native code and how the JDK boots, with a table of the source files.
- [limitations](limitations.md): the shortcuts a real program will run into.

## Working on champaca

- [contributing](contributing.md): how a change gets in, from its test against the JVM to the pull request, and the licensing rules for new code.
- [development](development.md): every task and `CHAMPACA_*` switch, the checks to run before a merge, and the metrics report.

The top-level [README](../../README.md) is the short version. The files in `examples/` are tests that `jolt test` runs, each asserting what its calls return, so they make a good second reference.
