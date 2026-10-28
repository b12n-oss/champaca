# Running Java programs

`champaca.main` runs a compiled Java class's `main` method, much as `java` would:

```sh
javac -d classes Boom.java
jolt -M -m champaca.main classes Boom
```

The first argument is the classpath, colon-separated as with `java -cp` (`classes:lib/gson-2.13.2.jar`). The second is the main class, with dots or slashes. Anything after that goes to `main` as its `String[]`.

`-D` options go before the classpath or `-jar`, as with `java`:

```sh
jolt -M -m champaca.main -Dapp.mode=test -Dfile.encoding=ISO-8859-1 classes Main
```

Each one sets a system property before the JDK starts, so the JDK's own startup reads it too (`file.encoding` there picks the default charset). `-Dkey` alone is an empty value, and the last of a repeated key wins. A property the JDK leaves to the launcher, such as `user.home`, `os.name` or `java.library.path`, takes the option's value, as on HotSpot. One the VM owns doesn't: `java.vm.name` and the other `java.vm.*` properties, `sun.java.command`, and `java.home` and `java.class.path`, which come from `JAVA_HOME` and the classpath given. champaca also keeps `jdk.reflect.useNativeAccessorOnly` and `sun.misc.unsafe.memory.access`, which its reflection and `Unsafe` need. A heap or stack size (`-Xmx`, `-Xms`, `-Xmn`, `-Xss`) and any `-XX:` flag are accepted and ignored, each with a `champaca: ignoring option ...` line on stderr, as HotSpot warns of an option it ignores: start scripts pass them, and Chez Scheme's heap grows as it needs to. Any other option, `-cp` and `-ea` among them, stops with the launcher's `Unrecognized option` and status 1, since dropping it could change what the program does (`test/java/PropsCheck.java`).

As with `java -jar`, `jolt -M -m champaca.main -jar app.jar args...` runs the class the jar's manifest names in `Main-Class`, with the jar as the class path, whatever the file's name. A jar's `Class-Path` entries are searched right after it, as the JVM's application loader does, whether the jar came through `-jar` or the class path, and `java.class.path` stays what was given. Each entry is a URL relative to the jar's real location (a symlinked jar's target), so `/opt/lib/x.jar` is absolute, `%` escapes are UTF-8, a name ending in `/` is a directory and any other a jar, and one that isn't there or can't be read is left out, as the JVM leaves it. A missing jar, one without `Main-Class` and one that isn't a zip fail with the JVM's messages and status 1 (`test/jarmain`, run by `bb test`). Every jar's manifest is read when the class path is set up, so an invalid one is reported then, even for a jar nothing uses.

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

The trace is the JVM's, file names and line numbers included; [debugging](debugging.md#java-stack-traces) has more.

## Frameworks

Spring Boot and Quarkus applications run as they do on `java`, from any of their packagings:

```sh
jolt -M -m champaca.main -jar target/boot-demo-1.0.jar               # Boot's fat jar
jolt -M -m champaca.main -jar extracted/boot-demo-1.0.jar            # after -Djarmode=tools extract
jolt -M -m champaca.main -jar target/quarkus-app/quarkus-run.jar     # Quarkus's fast-jar
jolt -M -m champaca.main -jar target/quarkus-uber.jar                # an uber-jar
```

Which one you pick matters more here than on the JVM. The fat jar loads its nested jars through Spring Boot's launcher, and the fast-jar loads the application through Quarkus's RunnerClassLoader; classes a loader of the program's own defines aren't kept in the startup cache, so their code is compiled again on every run. The extracted jar and the uber-jar put everything on the class path, which the cache keeps, and they start several times faster once it's warm. `bench/` holds upstream OpenLDK's two benchmark applications and times each packaging against HotSpot (`bb bench spring-boot`, `bb bench quarkus`; [bench/README](../../bench/README.md)).

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

The first run of a program boots the JDK and fills the startup cache, so expect about a second before `main` starts. Later runs load the cache and start in about 0.3 s. Classes from jars and directories are cached too, so a library's code compiles once, and after you rebuild your own classes only the ones whose bytes changed, and those that depend on them, compile again. [performance](performance.md) has numbers.
