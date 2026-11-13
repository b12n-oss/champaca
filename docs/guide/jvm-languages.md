# Other JVM languages

Champaca runs the bytecode, so it doesn't matter which compiler wrote it. Six languages that compile to the JVM have been run through it and compared with HotSpot line by line: Clojure, Groovy, Scala, Kawa Scheme, ABCL Common Lisp and Kotlin. Their compilers run here too. scalac and the Kotlin compiler write the same class files on champaca as on HotSpot, byte for byte.

Five of them are rows of `jolt libs`, so every change to champaca is checked against them in CI ([jars and modules](jars-and-modules.md)). Kotlin was checked by hand, because its probe needs `kotlinc` to build it first.

Each language runs through its own launcher, given to `champaca.main` the way you'd give it to `java`. The jars come from Maven Central (`jolt libs:fetch` downloads the pinned ones into `~/.m2`), or from the language's own install.

## Clojure

Clojure 1.12.4 needs its jar and the two spec jars:

```sh
M=~/.m2/repository/org/clojure
CP=$M/clojure/1.12.4/clojure-1.12.4.jar:$M/spec.alpha/0.5.238/spec.alpha-0.5.238.jar:$M/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar

jolt -M -m champaca.main -cp "$CP" clojure.main script.clj    # a script
jolt -M -m champaca.main -cp "$CP" clojure.main < forms.clj    # the REPL, fed on stdin
```

What's been checked: protocols, records, `deftype` and `reify`, multimethods, spec, atoms, refs, agents, futures and `pmap`, transducers, `pprint`, `eval` and `read-string`, Java interop, and hashing (`test/libs/Clj.clj`). The REPL reports errors at the same line HotSpot does (`Execution error ... (REPL:1)`). AOT compilation works both ways: classes `compile` writes on champaca run on HotSpot, and the other way round. They differ from HotSpot's only by gensym counters, which depend on what was evaluated first.

Loading a library from source is the slow part. Clojure compiles every form of a source-only library when it loads it, and that compiler is Java code champaca runs. Five source-only libraries (core.logic, core.match, test.check, data.json, tools.reader) took 26 s warm against HotSpot's 1.5 s, and 22.7 s of it was Java code running. An AOT-compiled library loads at the speed of any other jar.

## Groovy

Groovy 5.0.8, its JSON and XML modules on the class path:

```sh
G=~/.m2/repository/org/apache/groovy
CP=$G/groovy/5.0.8/groovy-5.0.8.jar:$G/groovy-json/5.0.8/groovy-json-5.0.8.jar:$G/groovy-xml/5.0.8/groovy-xml-5.0.8.jar

jolt -M -m champaca.main -cp "$CP" groovy.ui.GroovyMain script.groovy
```

Every call in a Groovy script goes through one of Groovy's own `invokedynamic` call sites, which champaca runs for real (it compiles only the JDK's bootstraps itself). Checked: closures, currying and composition, metaclasses and `Expando`, `methodMissing`, traits, `@CompileStatic` classes, JSON and XML, `Eval.me`, a second `GroovyShell`, threads (`test/libs/Grv.groovy`). Groovy 4.0 can't read JDK 25's class files, on HotSpot either, so use 5.0.

## Scala

Scala 2.13.18: the library, the compiler and scala-reflect.

```sh
S=~/.m2/repository/org/scala-lang
CP=$S/scala-library/2.13.18/scala-library-2.13.18.jar:$S/scala-compiler/2.13.18/scala-compiler-2.13.18.jar:$S/scala-reflect/2.13.18/scala-reflect-2.13.18.jar

jolt -M -m champaca.main -cp "$CP" scala.tools.nsc.Main -usejavacp -d out Probe.scala      # scalac
jolt -M -m champaca.main -cp "out:$S/scala-library/2.13.18/scala-library-2.13.18.jar" Probe  # the program
jolt -M -m champaca.main -cp "$CP" scala.tools.nsc.MainGenericRunner -usejavacp -nc Probe.scala  # compile in memory and run
```

Checked: case classes and pattern matching, sealed traits, implicits, `Enumeration`, collections, `LazyList`, for comprehensions, `Option`, `Either` and `Try`, `BigInt` and `BigDecimal`, `Future`s, threads (`test/libs/Scl.scala`). The class files scalac writes on champaca are byte for byte the ones it writes on HotSpot.

## Kawa Scheme

Kawa 3.1.1 is one jar:

```sh
jolt -M -m champaca.main -cp ~/.m2/repository/com/github/arvyy/kawa/3.1.1/kawa-3.1.1.jar kawa.repl script.scm
```

Kawa compiles each form to bytecode as it reads it, so this exercises classes defined at run time as much as the language itself. Checked: records, `syntax-rules`, tail calls, `call/cc`, `dynamic-wind`, `guard`, SRFI 1 and 69, Java interop (`test/libs/Kwa.scm`). It matched HotSpot on its first run, with no change to champaca.

## ABCL Common Lisp

ABCL 1.9.2 is one jar too:

```sh
jolt -M -m champaca.main -cp ~/.m2/repository/org/abcl/abcl/1.9.2/abcl-1.9.2.jar \
  org.armedbear.lisp.Main --noinform --noinit --batch --load script.lisp
```

Checked: CLOS with generic functions and inheritance, structs, macros, conditions and restarts, `loop`, `compile`, bignums and ratios, Java calls (`test/libs/Abcl.lisp`). ABCL is the language that needs the most from champaca's class loading. Its boot loads each compiled Lisp file through a class loader of its own, and several of those define classes of the same name. A class one loader defines can share its name with another loader's, as on the JVM ([how classes of one name coexist](limitations.md#native-code)). Its Lisp also runs in a thread of its own, which calls `System.exit`, and the startup cache is saved then too ([running Java programs](running-java-programs.md)).

ABCL prints one line on stderr at startup that HotSpot prints too (it can't make virtual threads accessible); the module identity it names differs from run to run on both.

## Kotlin

Kotlin 2.2 needs its standard library to run a program, and its own install's jars to compile one:

```sh
K=$KOTLIN_HOME/lib    # the kotlinc install's lib/ (its kotlin-compiler.jar and friends)
jolt -M -m champaca.main -cp "probe.jar:$K/kotlin-stdlib.jar:$K/kotlin-reflect.jar" probe.ProbeKt

jolt -M -m champaca.main --enable-native-access=ALL-UNNAMED -Dkotlin.home=$KOTLIN_HOME -Dsun.misc.unsafe.memory.access=allow \
  -cp "$K/kotlin-compiler.jar:$K/kotlin-stdlib.jar:$K/kotlin-reflect.jar:$K/kotlin-script-runtime.jar:$K/kotlinx-coroutines-core-jvm.jar:$K/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler Hello.kt -d out
```

A program `kotlinc` built (data, sealed and value classes, enums, delegation with `lazy` and `observable`, `lateinit` errors, reified generics, `tailrec`, collections and sequences, unsigned numbers, `kotlin-reflect`, threads) prints what it prints on HotSpot. So does the compiler, whose class files are HotSpot's byte for byte. The compiler prints one extra line on stderr: Jansi, the terminal library it bundles, can't load its JNI library here and falls back to plain output ([limitations](limitations.md#native-code)).

The compiler opens its JDK with `FileSystems.newFileSystem(jrt:/, {java.home: ...})`, which the JDK serves by loading that JDK's `jrt-fs.jar` through a loader of its own. That works for this JDK and for another one's home.

## Speed

Measured on an Apple M1 Pro on 2026-11-13. Cold is a fresh startup cache, warm is the run after. The machine was busy for some of these (the ABCL runs had a load average near 20), so read them as orders of magnitude.

| Program | HotSpot | champaca, cold | champaca, warm |
|---|---|---|---|
| Clojure script (`Clj.clj`) | 0.7 s | 65 s | 4 s |
| Clojure, five libraries from source | 1.5 s | 97 s | 26 s |
| Groovy script (`Grv.groovy`) | 1.0 s | 49 s | 7 s |
| Scala program, compiled by scalac | 0.3 s | 14 s | 1 s |
| scalac compiling it | 0.9 s | 73 s | 12 s |
| Kawa script (`Kwa.scm`) | 0.3 s | 23 s | 2 s |
| ABCL script (`Abcl.lisp`) | 1.7 s | 90 s | 14 s |
| Kotlin program | 0.3 s | 27 s | 1 s |
| Kotlin compiler, one small file | 2.8 s | 109 s | 9 s |

A cold run pays for translating and compiling everything the language's runtime touches, its compiler included. The cache keeps that for every later run. What stays slow warm is code that runs a lot: a language compiler working through source runs as translated Java, at a few times HotSpot's speed at best ([performance](performance.md)).

## When something differs

Run the program on HotSpot and on champaca and compare, as `jolt libs` does. A difference in stdout is a bug worth reporting with the two outputs. `CHAMPACA_BT=1` adds Java frames to champaca's own errors, and names each native the program reaches that champaca hasn't written ([debugging](debugging.md)).
