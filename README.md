<p align="center"><img src="docs/images/logo.svg" alt="Champaca logo: a champaca flower seen from the side, narrow saffron and golden tepals curling outward and drooping from a green cup on a short stem" width="160"></p>

<h1 align="center">Champaca</h1>

> [!WARNING]
> **Experimental.** Champaca runs real Java programs and libraries with the same output as HotSpot, but it isn't a complete JVM. A program can still reach a JDK native that hasn't been written yet (an `UnsatisfiedLinkError`, named on stderr with `CHAMPACA_BT=1`), JNI libraries aren't there, and a warm start takes a few times HotSpot's time and memory. The [limitations](docs/guide/limitations.md) page lists the gaps, and [debugging](docs/guide/debugging.md) shows how to read one.

Champaca is a Java runtime inside [jolt](https://github.com/jolt-lang/jolt). It rebuilds [OpenLDK](https://github.com/atgreen/openldk), Anthony Green's Java runtime for Common Lisp, on jolt: a translator written in jolt's Clojure turns each method's bytecode into Chez Scheme source the first time the method is called, and `jolt.scheme/eval-string` compiles that to native code. Java objects are Chez records in jolt's own heap, so jolt code calls a Java library as a plain procedure call, with no second runtime in the process.

The classes are the JDK's own, read from `$JAVA_HOME/lib/modules`, and the JDK boots as it does on a JVM, module system included. So `System.out.println` goes through the real `PrintStream` and UTF-8 encoder before it reaches a `FileOutputStream.writeBytes` that champaca implements over libc.

## Calling Java from jolt

```clojure
(require '[champaca.api :as ldk])

(ldk/init!)

(ldk/invoke-static "java.lang.Math" "max" 3 7.5)                 ;=> 7.5

(let [xs (ldk/construct "java.util.ArrayList")]
  (doseq [w ["pear" "fig" "apple" "kiwi"]] (ldk/invoke xs "add" w))
  (ldk/invoke xs "sort" (fn [a b] (compare (count a) (count b))))  ; a jolt fn as a Comparator
  (ldk/invoke xs "remove" 0)                                     ;=> "fig"   (remove(int))
  (ldk/invoke xs "get" 0)                                        ;=> "pear"
  (ldk/to-string xs))                                            ;=> "[pear, kiwi, apple]"

(ldk/->clj (ldk/invoke "a,b,c" "split" ","))                     ;=> ["a" "b" "c"]
```

Methods are found by name, and the overload is picked from the arguments the way `javac` picks one. A jolt fn goes wherever Java wants a single-method interface, jolt collections become Java ones on the way in, and results come back as jolt values where they can. [calling-java](docs/guide/calling-java.md) and [data-conversion](docs/guide/data-conversion.md) have the details, including proxies and real Java classes defined from jolt.

## What it runs

- **Thirteen common libraries** (commons-lang3, commons-lang 2.4, commons-io, gson, jsoup, Jackson, Caffeine, Guava, SLF4J, SnakeYAML, picocli, the H2 database and Byte Buddy) give the same output as on HotSpot, which `jolt libs` checks ([jars-and-modules](docs/guide/jars-and-modules.md)).
- **Spring Boot 4.1.2 and Quarkus 3.40.1 applications** start in every packaging upstream OpenLDK's benchmarks time ([running-java-programs](docs/guide/running-java-programs.md), [bench](bench/README.md)).
- **Compiled Java programs** run from the command line as with `java`: `-cp`, `-jar`, `-D` options, `-p` and `-m` for modules, source files, and the JDK's own rules for finding `main` ([running-java-programs](docs/guide/running-java-programs.md)).
- **Many JDK areas**, from reflection, method handles and threads to files, sockets, child processes, serialization and native calls through `java.lang.foreign`, each checked against HotSpot ([compatibility](docs/guide/compatibility.md)).
- **A startup cache** keeps what a run compiled, so a warm `Hello` takes 0.39 s from exec to exit on an M1 Pro, where the first run took 4.3 s ([performance](docs/guide/performance.md#the-startup-cache)).
- **App bundles**: `jolt bundle` makes a directory that runs a program on a machine with no JDK ([app-bundles](docs/guide/app-bundles.md)).

## Getting started

You need jolt 0.8.24 or later and JDK 25, with `JAVA_HOME` set.

```sh
jolt jolt                                         # build the jolt commit deps.edn pins, once
jolt test                                         # every test program against the JVM, plus the examples
jolt -M -m champaca.main target/classes Hello   # run a class jolt test compiled
```

[getting-started](docs/guide/getting-started.md) covers depending on champaca from your own project and using it from an editor. [development](docs/guide/development.md) lists every task and switch.

## Guides

- [getting-started](docs/guide/getting-started.md): installing, the first call, using it from your own project.
- [calling-java](docs/guide/calling-java.md) and [data-conversion](docs/guide/data-conversion.md): the jolt API, overloads, proxies, classes defined from jolt, and what values turn into.
- [jars-and-modules](docs/guide/jars-and-modules.md) and [running-java-programs](docs/guide/running-java-programs.md): class paths, JDK modules, the `java`-like launcher, frameworks.
- [app-bundles](docs/guide/app-bundles.md): shipping a program without a JDK.
- [performance](docs/guide/performance.md) and [debugging](docs/guide/debugging.md): the caches, what generated code costs, the `CHAMPACA_*` switches.
- [compatibility](docs/guide/compatibility.md), [limitations](docs/guide/limitations.md) and [how-it-works](docs/guide/how-it-works.md): what matches the JVM, what doesn't, and why.
- [contributing](docs/guide/contributing.md) and [development](docs/guide/development.md): how a change gets in, the tasks, the checks before a merge. Changes written with an LLM's help are welcome on the same terms ([AI_POLICY.md](AI_POLICY.md)).

## The name

A champaca (*Magnolia champaca*, จำปา in Thai) is a tall tree from South and Southeast Asia, a relative of the magnolias. Its flowers have narrow tepals that run from gold to saffron orange, and their scent is strong enough that the oil goes into perfume.

<p align="center"><img src="docs/images/champaca-1880.jpg" alt="Botanical plate of Michelia champaca: a branch with long green leaves, narrow saffron-orange flowers and a bud, beside a cluster of green fruit" width="240"></p>

*Michelia champaca* (now *Magnolia champaca*), plate 191 of Francisco Manuel
Blanco's *Flora de Filipinas*, Gran edición, Atlas I (about 1880 to 1883).
Public domain, via
[Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Michelia_champaca_Blanco1.191-cropped.jpg).
The logo in `docs/images/logo.svg` was drawn for this project.

## License

GPLv3 or later with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`. Each source file, task file and script starts with the SPDX line `GPL-3.0-or-later WITH Classpath-exception-2.0` and a copyright line, crediting OpenLDK where it derives from OpenLDK, as OpenLDK's own files do. Four pieces that once followed GPL-version-2-only OpenJDK code (`src/champaca/npe.clj`, `class-cast-message` in runtime.ss, the class name checks in natives.ss, `encode-path` in classpath.clj) were written again from HotSpot's observed behavior. `jolt bundle` ships jolt's EPL-2.0 binaries beside this GPL code, a pairing that isn't settled yet ([app-bundles](docs/guide/app-bundles.md#limits)). The Java test programs carry no header, since their recorded output has line numbers in it. The test programs in `test/aaa` are OpenLDK's own, under the same license (`test/aaa/README.md`).
