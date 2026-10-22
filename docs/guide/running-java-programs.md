# Running Java programs

`champaca.main` runs a compiled Java class's `main` method, much as `java` would:

```sh
javac -d classes Boom.java
jolt -M -m champaca.main classes Boom
```

The first argument is the classpath, colon-separated as with `java -cp` (`classes:lib/gson-2.13.2.jar`). The second is the main class, with dots or slashes. Anything after that goes to `main` as its `String[]`.

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
$ echo $?
1
```

The trace is the JVM's, file names and line numbers included; [debugging](debugging.md#java-stack-traces) has more.

## Checking output against the JVM

This is how `bb test` works: every program in `test/java` has a `.expected` file recorded from `java`, and the test compares champaca's output with it byte for byte. To check a program of your own the same way:

```sh
java -cp classes Main > main.jvm
jolt -M -m champaca.main classes Main > main.ldk
diff main.jvm main.ldk
```

A stack trace matches frame for frame, except that champaca's natives have no frames where the JVM prints `Native Method`. Output from several threads can interleave differently than on a JVM, as it can between two JVM runs.

## Speed

The first run of a program boots the JDK and fills the startup cache, so expect about a second before `main` starts. Later runs load the cache and start in about 0.3 s. Your own classes compile on every run, because only JDK code goes in the cache. [performance](performance.md) has numbers.
