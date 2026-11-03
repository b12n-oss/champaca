# Running Java programs

`champaca.main` runs a compiled Java class's `main` method, much as `java` would:

```sh
javac -d classes Boom.java
jolt -M -m champaca.main classes Boom
```

The first argument is the classpath, colon-separated as with `java -cp` (`classes:lib/gson-2.13.2.jar`). The second is the main class, with dots or slashes. Anything after that goes to `main` as its `String[]`. `java`'s own form works too: `-cp classes Boom`, `-classpath` and `--class-path` (with a space or `=`) are the same, the last one given wins, and the main class comes next. An entry `lib/*` stands for every name ending `.jar` or `.JAR` in `lib`, as the `java` launcher expands it, and `java.class.path` shows the jars it found. They come sorted by name, where `java` takes the directory's own order, which its documentation leaves unspecified. One that finds no jar, or names no directory, stays as written, as `java` leaves it, so a directory really named `*` is still searched. `-cp` without a class path is the launcher's `Error: -cp requires class path specification`, status 1, and before `-jar` it's ignored, as `java` ignores it there.

`-D` options go before the classpath or `-jar`, as with `java`:

```sh
jolt -M -m champaca.main -Dapp.mode=test -Dfile.encoding=ISO-8859-1 classes Main
```

Each one sets a system property before the JDK starts, so the JDK's own startup reads it too (`file.encoding` there picks the default charset). `-Dkey` alone is an empty value, and the last of a repeated key wins. A property the JDK leaves to the launcher, such as `user.home`, `os.name` or `java.library.path`, takes the option's value, as on HotSpot. One the VM owns doesn't, again as on HotSpot: `java.vm.name` and the other `java.vm.*` properties, `sun.java.command` and `java.class.path`, which stays the classpath given. champaca keeps a few more than HotSpot does. `java.home` is always `JAVA_HOME`, where HotSpot takes the option (and fails to start unless it names a JDK), `sun.boot.library.path` ignores the option, which HotSpot appends to its own, and `jdk.reflect.useNativeAccessorOnly` and `sun.misc.unsafe.memory.access` keep the values champaca's reflection and `Unsafe` need. `-Xss` bounds a thread's stack ([limitations](limitations.md)). A heap size (`-Xmx`, `-Xms`, `-Xmn`), `-Xshare:` and any `-XX:` flag are accepted and ignored, each with a `champaca: ignoring option ...` line on stderr, since start scripts pass them and Chez Scheme's heap grows as it needs to. HotSpot reads them, so the warning is champaca's own. `-server` and `-client` are accepted without one, as `java` accepts them, and `--enable-preview` turns on JDK 25's preview features (below). Any other option stops with the launcher's `Unrecognized option` and status 1, since dropping it could change what the program does (`test/java/PropsCheck.java`).

A class file's version is checked as HotSpot checks it, before anything else in the file is read. JDK 25 takes 45 through 69, any minor version before 56 and 0 from 56 on. Anything else is `UnsupportedClassVersionError` with HotSpot's text, wherever the class is needed: loaded by name, named by an instruction, defined from bytes or run as the main class. A class that javac compiled with `--enable-preview` and that uses a preview feature is version 69.65535 (one that uses none stays 69.0), and it loads only when the program runs with `--enable-preview` too:

```sh
javac --release 25 --enable-preview -d classes Main.java
jolt -M -m champaca.main --enable-preview classes Main
```

Without the option the launcher stops as `java` does (`Preview features are not enabled for Main (class file version 69.65535). Try running with '--enable-preview'`), and an older release's preview class file is refused either way. With it, `jdk.internal.misc.PreviewFeatures` says preview is on, so structured concurrency (`StructuredTaskScope`) and stable values run, and primitive types work in patterns: a `switch` on a `long`, `float`, `double` or `boolean`, `case int i` on an `Object`, `x instanceof byte b`. Those switches run the JDK's own `SwitchBootstraps`, which generates a class for each one, where champaca matches other pattern switches itself. A preview class is never kept in the library cache, and neither is anything that depends on one (a class calling it, a lambda over a preview interface), so a run without the option always gets to refuse it (`test/java/VersionCheck.java`, `test/preview/PreviewCheck.java`). From jolt, `champaca.api/init!` takes `:preview true` for the same thing ([jars and modules](jars-and-modules.md)).

Assertions follow `java`'s switches: `-ea` (or `-enableassertions`) turns them on for every class but the JDK's own, `-ea:com.example...` for a package and those inside it, `-ea:com.example.Main` for one class and `-ea:...` for the unnamed package, `-da` with the same forms turns them off, and `-esa` and `-dsa` do the same for the JDK's classes. A class's own switch beats its package's, a package beats an enclosing one and both beat the default, and the last switch for the same name wins. An array or a primitive class never has assertions on. As on HotSpot, the JDK's classes means the boot loader's: `java.sql`'s classes, which the platform loader defines, follow `-ea`. `ClassLoader.setClassAssertionStatus` and its siblings start from the same switches. javac makes a nested class's `assert` follow its outermost class, so `-da:Outer$Inner` changes what `desiredAssertionStatus` says about it but not whether its asserts run, on the JVM too (`test/java/AssertCheck.java`).

As with `java -jar`, `jolt -M -m champaca.main -jar app.jar args...` runs the class the jar's manifest names in `Main-Class`, with the jar as the class path, whatever the file's name. A jar's `Class-Path` entries are searched right after it, as the JVM's application loader does, whether the jar came through `-jar` or the class path, and `java.class.path` stays what was given. Each entry is a URL relative to the jar's real location (a symlinked jar's target), so `/opt/lib/x.jar` is absolute, `%` escapes are UTF-8, a name ending in `/` is a directory and any other a jar, and one that isn't there or can't be read is left out, as the JVM leaves it. A missing jar, one without `Main-Class` and one that isn't a zip fail with the JVM's messages and status 1 (`test/jarmain`, run by `bb test`). Every jar's manifest is read when the class path is set up, so an invalid one is reported then, even for a jar nothing uses.

`main` is found as Java 25's launcher finds it (JEP 512), by the JDK's own `LauncherHelper`: a `static` or instance `main`, with a `String[]` or no parameters, public, protected or package-private, declared or inherited, a default method included. `main(String[])` wins over `main()`, whichever of them is `static`. An instance `main` runs on an object made with the class's no-argument constructor. A class with none of these, a private constructor, an abstract class or a class that isn't there fails with the launcher's text and status 1 (`test/mainsel`, run by `bb test`).

`System.out` and `System.err` go through the JDK's own `PrintStream` and encoder down to a native write, and the process exits with the status a JVM would give: 0, the argument to `System.exit`, or 1 after an uncaught exception. As with `java`, the process waits for any non-daemon threads still running when `main` returns, then runs the shutdown hooks.

Here's a small program that throws on its second line of output:

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

The frames are the JVM's. Your own, from a class directory, carry file names and line numbers as on the JVM; a frame of a JDK class, or of a jar's class unless `CHAMPACA_LINES=all` is set, names its file but no line; [debugging](debugging.md#java-stack-traces) has more.

## Frameworks

Spring Boot and Quarkus applications run as they do on `java`, from any of their packagings:

```sh
jolt -M -m champaca.main -jar target/boot-demo-1.0.jar               # Boot's fat jar
jolt -M -m champaca.main -jar extracted/boot-demo-1.0.jar            # after -Djarmode=tools extract
jolt -M -m champaca.main -jar target/quarkus-app/quarkus-run.jar     # Quarkus's fast-jar
jolt -M -m champaca.main -jar target/quarkus-uber.jar                # an uber-jar
```

Which one you pick matters here too. The fat jar loads its nested jars through Spring Boot's launcher, and the fast-jar loads the application through Quarkus's RunnerClassLoader. Classes such a loader defines are kept in the startup cache by their bytes, like the class path's, but a class whose cached code depends on one the cache can't check compiles again on every run, and the loader's own work (reading the nested jars) is repeated, so the extracted jar and the uber-jar, which put everything on the class path, still start faster once the cache is warm ([performance](performance.md#classes-from-jars-and-directories)). `bench/` holds upstream OpenLDK's two benchmark applications and times each packaging against HotSpot (`bb bench spring-boot`, `bb bench quarkus`; [bench/README](../../bench/README.md)).

Spring's AOT mode works too, on the extracted jar that `spring-boot-maven-plugin` built with AOT processing: `jolt -M -m champaca.main -Dspring.aot.enabled=true -jar extracted/boot-demo-1.0.jar`.

## Checking output against the JVM

This is how `bb test` works: every program in `test/java` has a `.expected` file recorded from `java`, and the test compares champaca's output with it byte for byte. To check a program of your own the same way:

```sh
java -cp classes Main > main.jvm
jolt -M -m champaca.main classes Main > main.ldk
diff main.jvm main.ldk
```

A stack trace matches frame for frame, except that champaca's natives have no frames where the JVM prints `Native Method`, and JDK frames carry no line numbers. Output from several threads can interleave differently than on a JVM, as it can between two JVM runs.

## Speed

The first run of a program boots the JDK and fills the startup cache, so expect six or seven seconds before `main` starts. Later runs load the cache and start in about 0.4 s. Classes from jars and directories are cached too, so a library's code compiles once, and after you rebuild your own classes only the ones whose bytes changed, and those that depend on them, compile again. [performance](performance.md) has numbers.
