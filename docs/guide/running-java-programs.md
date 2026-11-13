# Running Java programs

`champaca.main` runs a compiled Java class's `main` method, much as `java` would:

```sh
javac -d classes Boom.java
jolt -M -m champaca.main classes Boom
```

The first argument is the classpath, colon-separated as with `java -cp` (`classes:lib/gson-2.13.2.jar`). The second is the main class, with dots or slashes, and anything after that goes to `main` as its `String[]`.

`java`'s own form works too. `-cp classes Boom`, `-classpath` and `--class-path` (with a space or `=`) all mean the same, the last one given wins, and the main class comes next. `-cp` without a class path is the launcher's `Error: -cp requires class path specification`, status 1. Before `-jar` it's ignored, as `java` ignores it there.

An entry `lib/*` stands for every name ending `.jar` or `.JAR` in `lib`, as the `java` launcher expands it, and `java.class.path` shows the jars it found. Champaca sorts them by name, while `java` takes the directory's own order, which its documentation leaves unspecified. A `*` entry that finds no jar, or names no directory, stays as written, as `java` leaves it, so a directory named `*` is still searched.

`-D` options go before the classpath or `-jar`, as with `java`:

```sh
jolt -M -m champaca.main -Dapp.mode=test -Dfile.encoding=ISO-8859-1 classes Main
```

Each one sets a system property before the JDK starts, so the JDK's own startup reads it too (`file.encoding` there picks the default charset). `-Dkey` alone is an empty value, and the last of a repeated key wins.

Given a value, `-Djava.endorsed.dirs` and `-Djava.ext.dirs` (Java 8's endorsed and extension directories) stop the launch: the first such option prints HotSpot's "is not supported" text on stdout and the launcher's two lines on stderr, with status 1. An empty value is accepted.

A property the JDK leaves to the launcher, such as `user.home`, `os.name` or `java.library.path`, takes the option's value, as on HotSpot. One the VM owns doesn't: `java.vm.name` and the other `java.vm.*` properties, `sun.java.command`, and `java.class.path`, which stays the classpath given. champaca keeps a few more than HotSpot does:

- `java.home` is always `JAVA_HOME`, where HotSpot takes the option (and fails to start unless it names a JDK).
- `sun.boot.library.path` ignores the option, which HotSpot appends to its own.
- `jdk.reflect.useNativeAccessorOnly` keeps the value champaca's reflection needs.

`-Xss` bounds a thread's stack ([limitations](limitations.md)). A heap size (`-Xmx`, `-Xms`, `-Xmn`), `-Xshare:` and any `-XX:` flag are accepted and ignored, each with a `champaca: ignoring option ...` line on stderr, because start scripts pass them and Chez Scheme's heap grows as it needs to.

So are the options that trace or tune only HotSpot: `-verbose` (alone or with any kind), `-Xlog` (alone or with a selection, which isn't checked), the execution modes `-Xint`, `-Xmixed`, `-Xcomp` and `-Xbatch`, and `-Xrs`, `-Xverify:`, `-Xcheck:jni` and `-Xnoclassgc`. HotSpot acts on them and champaca can't, so the warning is champaca's own. `-verbose`, for example, makes HotSpot print a line per class it loads.

`-server`, `-client` and `-Xdiag` (which only lengthens the launcher's own errors) are accepted without a warning, as `java` accepts them. `--enable-preview` turns on JDK 25's preview features, and the module options and the version switches work as on HotSpot (all below). Any other option stops with the launcher's `Unrecognized option` and status 1, since dropping it could change what the program does (`test/java/PropsCheck.java`).

A class file's version is checked as HotSpot checks it, before anything else in the file is read. JDK 25 takes 45 through 69, with any minor version before 56 and 0 from 56 on. Anything else is `UnsupportedClassVersionError` with HotSpot's text, wherever the class is needed: loaded by name, named by an instruction, defined from bytes or run as the main class.

A class javac compiled with `--enable-preview` is version 69.65535 if it uses a preview feature (one that uses none stays 69.0). It loads only when the program runs with `--enable-preview` too:

```sh
javac --release 25 --enable-preview -d classes Main.java
jolt -M -m champaca.main --enable-preview classes Main
```

Without the option the launcher stops as `java` does (`Preview features are not enabled for Main (class file version 69.65535). Try running with '--enable-preview'`). An older release's preview class file is refused either way.

With the option, `jdk.internal.misc.PreviewFeatures` says preview is on. Structured concurrency (`StructuredTaskScope`) and stable values then run, and primitive types work in patterns: a `switch` on a `long`, `float`, `double` or `boolean`, `case int i` on an `Object`, `x instanceof byte b`. Those switches run the JDK's own `SwitchBootstraps`, which generates a class for each one, while champaca matches other pattern switches itself.

The library cache never keeps a preview class, or anything that depends on one (a class calling it, a lambda over a preview interface), so a run without the option always gets to refuse it (`test/java/VersionCheck.java`, `test/preview/PreviewCheck.java`). From jolt, `champaca.api/init!` takes `:preview true` for the same thing ([jars and modules](jars-and-modules.md)).

`--add-modules <module>[,<module>...]`, or `--add-modules=...`, adds root modules to the boot layer, as on HotSpot. That's how a program gets the Vector API, since the JDK's default graph leaves out its incubator modules:

```sh
javac --add-modules jdk.incubator.vector -d classes Main.java
jolt -M -m champaca.main --add-modules jdk.incubator.vector classes Main
```

Without the option, the boot layer comes from the module graph the JDK image carries for the default case. With it, the JDK's `ModuleBootstrap` resolves the graph itself. On a warm cache that took 3.9 s more the first time, while the resolver's code compiled, and 0.03 s on later runs.

A module that doesn't exist stops the program with HotSpot's two lines on stdout (`Error occurred during initialization of boot layer` and the `FindException`) and status 1. The incubator warning goes to stderr as on HotSpot, and `getInputArguments()` lists each occurrence as `--add-modules=<modules>`. `ALL-SYSTEM`, `ALL-DEFAULT` and `ALL-MODULE-PATH` mean what they mean to `java`. A `-D` option naming one of the module system's own properties (`jdk.module.addmods`, `jdk.module.path` and the rest, alone or with a dotted suffix) is dropped with HotSpot's warning.

The Vector API's operations match HotSpot's results, including the ones that change a vector's shape or lane type (`castShape`, `reinterpretAs*`, `test(IS_NAN)`) and loads and stores through a `MemorySegment`. The transcendental ones, `SIN` and the like, can differ in the last place where HotSpot uses its vector math library. Every operation runs on the Java fallback the JDK keeps for each intrinsic, though, so it's much slower than a plain loop ([limitations](limitations.md)). From jolt, `init!` takes `:add-modules ["jdk.incubator.vector"]` (`examples/vector.clj`, `test/vector/VectorCheck.java`).

`--enable-native-access <module>[,<module>...]`, or `--enable-native-access=...`, lets those modules call restricted methods such as `Linker.downcallHandle` without the JDK's warning, as on HotSpot. `ALL-UNNAMED` is the class path. The option reaches the JDK as HotSpot's launcher passes it, so an unknown module gets the JDK's warning and `getInputArguments()` lists `--enable-native-access=<modules>`.

`--illegal-native-access=allow|warn|deny` (only that form, as on HotSpot) decides what a restricted call from any other module does. With `deny` it's an `IllegalCallerException`. A value the JDK doesn't know stops the program with its text, and the last occurrence wins.

The other module options work the same way, each reaching the JDK's `ModuleBootstrap` as the property HotSpot's launcher makes of it:

- `--add-opens <module>/<package>=<target>[,<target>...]` opens a package for deep reflection (`setAccessible` on `String.value`, say, with `ALL-UNNAMED` as the target for the class path), `--add-exports` exports one, and `--add-reads <module>=<target>[,...]` adds reads edges. Each may be given more than once, as `--opt value` or `--opt=value`. Since they don't change the boot layer, they cost nothing measurable at startup: a warm `Hello` took 0.44 to 0.49 s with all three against 0.45 to 0.46 s without.
- `--limit-modules <module>[,<module>...]` limits the observable modules to those and what they require (plus `--add-modules`' modules), the last occurrence winning. As with `--add-modules`, the JDK then resolves the boot layer itself.
- An unknown module or package is the JDK's warning on stderr, a value that doesn't parse its `Error occurred during initialization of boot layer` lines and status 1, and a missing value the launcher's `requires modules to be specified`, all as on HotSpot. `getInputArguments()` lists each option as `--opt=value`.

Once the boot layer is built, the JVM's class loaders find a JDK class only when one of the layer's modules holds its package, as on HotSpot. Under `--limit-modules java.base`, `Class.forName("java.sql.Connection")` is a `ClassNotFoundException`, and so is `jdk.incubator.vector.IntVector` without `--add-modules`.

A class named in your bytecode is refused too: an `ldc`, `new`, `getstatic`, `invokestatic`, `anewarray` or `instanceof` of an object naming `java.sql.Connection` throws `NoClassDefFoundError` when it runs, with the `ClassNotFoundException` as its cause, and throws again the next time. A class compiled into the startup cache under a layer that held the module compiles again under one that doesn't. A name given to the jolt API is refused too. The cases are in `test/modopts`, each recorded from HotSpot 25.0.2, and [limitations](limitations.md) has the one place champaca still differs.

A modular application runs from its module path, as `java -p mods -m app/app.Main` runs it:

```sh
jolt -M -m champaca.main -p mods -m app/app.Main arg...
```

`-p` (or `--module-path`, `--module-path=`) names jars and exploded modules (a directory holding `module-info.class`), or directories of them. `-m` (`--module`, `--module=`) names the module whose main class runs, as `module/class`, or the module alone when its jar names a main class (`jar --main-class`). What follows `-m` goes to `main`.

The JDK's `ModuleBootstrap` reads the modules' descriptors and resolves them into the boot layer. The main module and what it requires become named modules of the application class loader, with their `requires`, `exports` and services as on HotSpot, and `ModuleLayer.boot()` finds them. `java.class.path` is `-cp`'s or empty, and `jdk.module.path` is the path given.

A module path class is found only once its module is in the layer. A class path class naming one gets `NoClassDefFoundError` unless `-m` or `--add-modules` resolves it, and a module that isn't resolved shows none of its resources either. A package of a named module comes from that module alone, so a class path class in the same package isn't found (a split package). When the path holds a module twice, the first copy wins.

Resources follow HotSpot's loader:

- A resource in one of a named module's packages that the module doesn't open is the module's own. Its classes find it through `Class.getResourceAsStream` and `Module.getResourceAsStream`, other modules don't, and the class loader skips the module and looks on the class path instead.
- For a package the module opens, and for a class file or a directory in any of its packages, the loader looks in the module and then on the class path.
- For a resource in no package (`top.txt`, `META-INF/...`) it looks in the resolved modules, in the order HotSpot's loader keeps them (not by name), then on the class path.
- A class of a named module looks for resources in its own module alone.

A missing value, a module that isn't there, a module with no main class and a main class it doesn't have give HotSpot's messages (`test/modpath`, recorded from HotSpot 25.0.2, from a modular jar and an exploded module).

`-version` prints the version on stderr and exits, and `--version` prints it on stdout and exits. `-showversion` and `--show-version` print it the same way, then run the program, or with no program print champaca's usage after it. As in `java`, nothing after a `-version` or `--version` is read, and once a `--` form has been given the text goes to stdout, so `--version -version` prints on stdout.

The text is the JDK's own (`VersionProps`), so the first two lines are those of the JDK in `JAVA_HOME`. The third names champaca's VM, `champaca (build 0.0-spike, compiled mode)`, where HotSpot names its own.

A source file runs as `java Hello.java` runs it, without a `javac` step:

```sh
jolt -M -m champaca.main Hello.java a b
jolt -M -m champaca.main -cp lib/gson-2.13.2.jar --source 25 script a b
```

A first argument that ends `.java` and is a file, or any first argument after `--source <release>` (or `--source=<release>`), is the program, and what follows it goes to its `main`.

As in `java`, javac's own source launcher does the work, here running on champaca. `com.sun.tools.javac.launcher.SourceLauncher` compiles the file in memory, defines its classes through a loader of its own and runs the first class's `main`, picked as for a compiled class, so a compact source file (`void main()` with no class around it) runs too. Compile errors, and a `--source` it doesn't know, are javac's text on stderr and status 1.

The class path is `-cp`'s, else `.`, or nothing with `--source`, matching what `java` gives `java.class.path`. A lone `.java` name that isn't a file is a main class that isn't found, as in `java`. With more arguments after it, it stays champaca's class path (a directory may be named `d.java`). `--source` with nothing after it, or before `-jar`, is the launcher's error, status 1.

Every class defined while a source file runs, the program's own among them, compiles with line numbers, as a directory's classes do ([stack traces](how-it-works.md#stack-traces)). javac is a lot of code. The first run of a cold cache compiles it, about 25 s, and after that a small program starts in a little over a second.

`RuntimeMXBean.getInputArguments()` lists the options as HotSpot does: the class path ones left out, `--source` as `-Djdk.internal.javac.source=<release>`, and `--add-modules=ALL-DEFAULT` last, the module option the `java` launcher adds in this mode. `--add-modules` reaches javac through that list, as on HotSpot, so a source file using the Vector API compiles only with `--add-modules jdk.incubator.vector` (`test/srclaunch`).

Assertions follow `java`'s switches. `-ea` (or `-enableassertions`) turns them on for every class but the JDK's own, `-ea:com.example...` for a package and those inside it, `-ea:com.example.Main` for one class and `-ea:...` for the unnamed package. `-da` with the same forms turns them off, and `-esa` and `-dsa` do the same for the JDK's classes. As on HotSpot, the JDK's classes means the boot loader's, so `java.sql`'s classes, which the platform loader defines, follow `-ea`.

A class's own switch beats its package's, a package beats an enclosing one, both beat the default, and the last switch for the same name wins. An array or a primitive class never has assertions on. `ClassLoader.setClassAssertionStatus` and its siblings start from the same switches.

javac makes a nested class's `assert` follow its outermost class. So `-da:Outer$Inner` changes what `desiredAssertionStatus` says about it but not whether its asserts run, on the JVM too (`test/java/AssertCheck.java`).

As with `java -jar`, `jolt -M -m champaca.main -jar app.jar args...` runs the class the jar's manifest names in `Main-Class`, with the jar as the class path, whatever the file's name. A missing jar, one without `Main-Class` and one that isn't a zip fail with the JVM's messages and status 1 (`test/jarmain`, run by `jolt test`).

A jar's `Class-Path` entries are searched right after it, as the JVM's application loader does, whether the jar came through `-jar` or the class path. `java.class.path` stays what was given. Each entry is a URL relative to the jar's real location (a symlinked jar's target): `/opt/lib/x.jar` is absolute, `%` escapes are UTF-8, and a name ending in `/` is a directory while any other is a jar. An entry that isn't there or can't be read is left out, as the JVM leaves it. Every jar's manifest is read when the class path is set up, so an invalid one is reported then, even for a jar nothing uses.

The main jar's `Add-Opens`, `Add-Exports` and `Enable-Native-Access` attributes apply as on the JVM, because the JDK's own launcher code reads them. A Quarkus fast-jar's `Add-Opens: java.base/java.lang`, for one, lets its thread pool reach `Thread`'s fields (`test/jarmain`).

The JDK's own `LauncherHelper` finds `main` as Java 25's launcher does (JEP 512). It can be `static` or instance, take a `String[]` or no parameters, be public, protected or package-private, and be declared or inherited, a default method included. `main(String[])` wins over `main()`, whichever of them is `static`, and an instance `main` runs on an object made with the class's no-argument constructor. A class with none of these, a private constructor, an abstract class or a class that isn't there fails with the launcher's text and status 1 (`test/mainsel`, run by `jolt test`).

`System.out` and `System.err` go through the JDK's own `PrintStream` and encoder down to a native write. The process exits with the status a JVM would give: 0, the argument to `System.exit`, or 1 after an uncaught exception.

A program that only ends on a signal, such as a server stopped with `kill` or Ctrl-C, runs its shutdown hooks on SIGTERM, SIGINT or SIGHUP and exits with 128 plus the signal, as on HotSpot (`test/signal`). The startup cache is saved then too, so the server's next start is warm. The same goes for a thread other than main calling `System.exit`, which ends the process from that thread, as ABCL's interpreter thread does (`test/exitsave`).

As with `java`, the main thread ends when `main` returns, and a thread that joins it wakes and sees it terminated. The process then waits for any non-daemon threads still running and runs the shutdown hooks (`test/java/MainExitCheck.java`). `test/mainsel`'s MainEndThrows and MainEndExit cover an uncaught exception and `System.exit`, after which main never ends.

An uncaught-exception handler that throws gets HotSpot's one line on stderr, `Exception: <class> thrown from the UncaughtExceptionHandler in thread "<name>"`, for main and for a started thread, and what it threw goes to no other handler. A `System.exit` inside a handler sets the status (`test/mainsel`'s Handler cases).

This small program throws on its second line of output:

```java
public class Boom {
  static int parse(String s) { return Integer.parseInt(s); }
  static int total(String[] xs) { int t = 0; for (String x : xs) t += parse(x); return t; }
  public static void main(String[] args) {
    System.out.println(total(new String[] {"1", "2"}));
    System.out.println(total(new String[] {"3", "four"}));
  }
}
```

```console
$ jolt -M -m champaca.main classes Boom
3
Exception in thread "main" java.lang.NumberFormatException: For input string: "four"
	at java.base/java.lang.NumberFormatException.forInputString(NumberFormatException.java)
	at java.base/java.lang.Integer.parseInt(Integer.java)
	at java.base/java.lang.Integer.parseInt(Integer.java)
	at Boom.parse(Boom.java:2)
	at Boom.total(Boom.java:3)
	at Boom.main(Boom.java:6)
$ echo $?
1
```

The frames are the JVM's. Your own, from a class directory, carry file names and line numbers. A frame of a JDK class, or of a jar's class unless `CHAMPACA_LINES=all` is set, names its file but no line ([debugging](debugging.md#java-stack-traces) has more).

## Frameworks

Spring Boot and Quarkus applications run as they do on `java`, from any of their packagings:

```sh
jolt -M -m champaca.main -jar target/boot-demo-1.0.jar               # Boot's fat jar
jolt -M -m champaca.main -jar extracted/boot-demo-1.0.jar            # after -Djarmode=tools extract
jolt -M -m champaca.main -jar target/quarkus-app/quarkus-run.jar     # Quarkus's fast-jar
jolt -M -m champaca.main -jar target/quarkus-uber.jar                # an uber-jar
```

The packaging you pick affects startup. The fat jar loads its nested jars through Spring Boot's launcher, and the fast-jar loads the application through Quarkus's RunnerClassLoader. The startup cache keeps classes such a loader defines by their bytes, like the class path's. Still, a class whose cached code depends on one the cache can't check compiles again on every run, and the loader's own work (reading the nested jars) is repeated. So the extracted jar and the uber-jar, which put everything on the class path, start faster once the cache is warm ([performance](performance.md#classes-from-jars-and-directories)).

`bench/` holds upstream OpenLDK's two benchmark applications and times each packaging against HotSpot (`jolt bench spring-boot`, `jolt bench quarkus`; [bench/README](../../bench/README.md)). `jolt bench petclinic` does the same for a server, Spring PetClinic. It starts the jar, fetches eleven pages, compares them with HotSpot's and stops it with SIGTERM, whose shutdown saves the cache the next start reads. A warm start took 26 s against HotSpot's 3.9 s on an M1 Pro, because PetClinic starts Hibernate, Thymeleaf, H2 and Tomcat as well.

Spring's AOT mode works too, on the extracted jar that `spring-boot-maven-plugin` built with AOT processing: `jolt -M -m champaca.main -Dspring.aot.enabled=true -jar extracted/boot-demo-1.0.jar`.

## Checking output against the JVM

Every program in `test/java` has a `.expected` file recorded from `java`, and `jolt test` compares champaca's output with it byte for byte. To check a program of your own the same way:

```sh
java -cp classes Main > main.jvm
jolt -M -m champaca.main classes Main > main.ldk
diff main.jvm main.ldk
```

A stack trace matches frame for frame, except that champaca's natives have no frames where the JVM prints `Native Method`, and JDK frames carry no line numbers. Output from several threads can interleave differently than on a JVM, as it can between two JVM runs.

## Speed

The first run of a program boots the JDK and fills the startup cache, so expect six or seven seconds before `main` starts. Later runs load the cache and start in about 0.4 s. Classes from jars and directories are cached too, so a library's code compiles once. After you rebuild your own classes, only the ones whose bytes changed, and those that depend on them, compile again. [performance](performance.md) has numbers.
