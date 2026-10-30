# Startup benchmarks

How fast a small Spring Boot application and a small Quarkus application
start on champaca, next to HotSpot. Both applications, and the idea of
timing them every way they can be packaged, come from upstream OpenLDK's
`bench/` by Anthony Green (the `burinc/openldk` fork at `3d92c3f`, the same
license as champaca). Their sources here are upstream's, minus the
`Snapshot` main classes, which only OpenLDK's saved images use.

```bash
bb bench spring-boot          # build, then time (bb bench spring-boot build|bench)
bb bench quarkus
RUNS=20 COLD_RUNS=5 bb bench spring-boot bench
```

The task needs `JAVA_HOME` set to a JDK 25 and `mvn` on `PATH`, and Maven
Central reached directly: it runs Maven with the proxy variables unset.
Builds go to `bench/<app>/target`, which is ignored. Each variant runs once
for its output, then `RUNS` times (default 10) timed, then once more for its
peak RSS (`/usr/bin/time`). The table goes to stdout and to
`bench/<app>/target/results.md`. `SKIP_AOT=1` leaves out the JDK AOT cache
variants, `SKIP_CHAMPACA=1` the champaca ones.

## The applications

`demo.boot.BootApp` (Spring Boot 4.1.2, Spring Framework 7) uses
auto-configuration, binds a `@ConfigurationProperties` record from
`application.properties`, injects an `@Value`, and has a `CommandLineRunner`
write JSON with the auto-configured Jackson 3 mapper and read it back. It
logs through Logback and prints the banner.

`demo.quarkus.QuarkusApp` (Quarkus 3.40.1) is a command-mode application:
CDI beans, a `@ConfigMapping` interface, a `@ConfigProperty`, and the
Quarkus-managed Jackson mapper writing JSON and reading it back, logging
through JBoss Logging.

A run is the whole process, from exec to exit. Every variant's application
output, the JSON and the round trip, is compared with plain HotSpot's and
reported as `same` or `DIFFERENT`.

## The variants

| Variant | What runs |
|---|---|
| HotSpot, fat jar | `java -jar` on the jar `spring-boot-maven-plugin` builds |
| HotSpot, extracted jar | that jar extracted with `-Djarmode=tools extract`, as Spring Boot recommends for production |
| HotSpot + Spring AOT | the extracted jar with `-Dspring.aot.enabled=true`: bean definitions generated at build time |
| HotSpot + JDK AOT cache | a JDK 25 AOT cache (JEP 483/514/515) from one training run with the same command line |
| HotSpot + Spring AOT + AOT cache | both: the Spring AOT command line with a JDK AOT cache trained on it |
| HotSpot, fast-jar / uber-jar | Quarkus's default packaging, which loads the application through its RunnerClassLoader, and one jar with every class |
| champaca, cold | through champaca with an empty startup cache: every class parsed and every method translated and compiled, the JDK's boot included |
| champaca | through champaca with the startup cache its first run filled |
| champaca + Spring AOT | the extracted jar through champaca with `-Dspring.aot.enabled=true`, sharing the extracted jar's cache |

champaca's startup cache keeps compiled code and the class table, never
Java objects, so every run starts the framework from scratch and every
static initializer runs again. Nothing here is the counterpart of
upstream's OpenLDK snapshot image (a saved heap with the framework already
started) or of a GraalVM native image, so those variants are left out; the
champaca rows compare with HotSpot's, and with upstream's OpenLDK warm
image.

Classes that a framework's own class loader defines (the fat jar's nested
jars, under Spring Boot's launcher, and the Quarkus fast-jar's application,
under its RunnerClassLoader) are kept in the startup cache by their bytes,
as the class path's are, with their dependencies checked through the loader.
The extracted jar and the uber-jar load from the class path.

## Results

On an Apple M1 Pro (10 CPUs), macOS 26.5.2, JDK 25.0.2, with the pinned
jolt, on 2026-10-30, with the desktop's own applications running (load
average 3.5 to 5.5). Ten timed runs each, three for a cold variant. Both ran
on champaca at `62a86ce`:

| Spring Boot | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fat jar | 859 ms | 835 ms | 891 ms | 235 MB |
| HotSpot, extracted jar | 732 ms | 714 ms | 840 ms | 193 MB |
| HotSpot + Spring AOT | 601 ms | 581 ms | 622 ms | 156 MB |
| HotSpot + JDK AOT cache | 347 ms | 335 ms | 367 ms | 146 MB |
| HotSpot + Spring AOT + AOT cache | 268 ms | 255 ms | 276 ms | 118 MB |
| champaca, extracted jar, cold | 45.0 s | 44.8 s | 46.0 s | 2742 MB |
| champaca, extracted jar | 3489 ms | 3325 ms | 3743 ms | 820 MB |
| champaca + Spring AOT | 3063 ms | 2967 ms | 3340 ms | 784 MB |
| champaca, fat jar | 5454 ms | 5260 ms | 5918 ms | 847 MB |

| Quarkus | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fast-jar | 393 ms | 379 ms | 4615 ms | 100 MB |
| HotSpot, uber-jar | 401 ms | 390 ms | 412 ms | 111 MB |
| HotSpot + JDK AOT cache | 258 ms | 237 ms | 269 ms | 93 MB |
| champaca, uber-jar, cold | 32.4 s | 30.9 s | 34.8 s | 2931 MB |
| champaca, uber-jar | 2205 ms | 2126 ms | 2709 ms | 617 MB |
| champaca, fast-jar | 2587 ms | 2543 ms | 3286 ms | 695 MB |

Every variant printed what HotSpot prints. A warm champaca start from the
class path takes five times as long as HotSpot's, for both frameworks, and
needs four to six times its memory; two days earlier it needed six to eight
times, before the class entries became bytes, the strings were interned and
the inspector information left the jar classes
([performance](../docs/guide/performance.md#memory)). Spring AOT saves
HotSpot 18% of its start and champaca 12%. The cold rows include the JDK's
own first boot, every method of the framework translated and compiled once
and the cache written. The fat jar and the fast-jar take longer than the
class path by what their own loaders do: 2.0 s for Spring Boot's launcher to
read its nested jars (130 ms on HotSpot) and 0.4 s for Quarkus's
RunnerClassLoader (nothing HotSpot's medians show), since the classes those
loaders define come from the cache like the class path's. Until 2026-10-29
they didn't, and the fat jar took 21.0 s, the fast-jar 11.1 s; on 2026-10-29
the launcher's own work still cost 3.3 s and 0.5 s, before the `ByteBuffer`
accessors, the array copies and string concatenation were sped up
([performance](../docs/guide/performance.md#a-framework-loaders-own-work)),
which took every champaca row down by 10 to 25%. Upstream's
OpenLDK images can't keep loader-defined classes, which is why they run the
class path for Spring Boot and the uber-jar for Quarkus. For scale, upstream
measured its OpenLDK warm image at 4.3 s (Spring Boot) and 1.5 s (Quarkus)
on a 16-CPU Xeon E5-2670, with HotSpot at 2.9 s and 1.3 s on the same
machine, so the two projects' numbers aren't comparable directly; the
HotSpot rows are the yardstick.

The maxima well above their medians (HotSpot's Quarkus fast-jar at 4615 ms,
champaca's at 3286) are single slow runs each; the medians stand.

## What it took

Running these applications turned up these gaps, now fixed:

- A class's initialization didn't initialize its superinterfaces that
  declare default methods (JVMS 5.5 step 7), so Spring Boot's
  `PropertyMapper.getAncestorOfCheck` read a static field before its
  initializer ran (`test/java/InterfaceInitCheck.java`).
- `java.io.Console.istty` was missing.
- A call to a method its class doesn't have, which Spring's CGLIB subclasses
  make on paths they never take, failed the whole method's translation
  instead of throwing `NoSuchMethodError` when it runs
  (`test/java/NoMethodCheck.java`).
- `ldc` of a class and reflection's member types didn't resolve through the
  defining loader, so Quarkus's generated classes couldn't see classes only
  its RunnerClassLoader sees (`test/java/LinkCheck.java`).
- `champaca.main` took no `-D` options, so the Spring AOT variant ran on
  HotSpot only (`test/java/PropsCheck.java`).
- Classes a framework's own loader defines were compiled on every run; they
  are cached by their bytes now, with their dependencies checked through the
  loader (`test/libcache/def`).
