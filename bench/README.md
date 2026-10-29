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
jolt, on 2026-10-28, while other work kept the load average at 4 to 10. Ten
timed runs each, three for a cold variant. Spring Boot ran on champaca at
`ab48ef6`, load 4 to 6:

| Spring Boot | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fat jar | 875 ms | 856 ms | 997 ms | 231 MB |
| HotSpot, extracted jar | 765 ms | 713 ms | 819 ms | 185 MB |
| HotSpot + Spring AOT | 633 ms | 575 ms | 663 ms | 150 MB |
| HotSpot + JDK AOT cache | 348 ms | 324 ms | 372 ms | 135 MB |
| HotSpot + Spring AOT + AOT cache | 266 ms | 257 ms | 294 ms | 111 MB |
| champaca, extracted jar, cold | 52.8 s | 49.0 s | 53.5 s | 2505 MB |
| champaca, extracted jar | 4089 ms | 3913 ms | 5740 ms | 1101 MB |
| champaca + Spring AOT | 3233 ms | 3127 ms | 3289 ms | 1090 MB |
| champaca, fat jar | 21.0 s | 19.7 s | 24.9 s | 998 MB |

Quarkus ran on champaca at `07eafcc`, load 5 to 10:

| Quarkus | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fast-jar | 506 ms | 501 ms | 5485 ms | 100 MB |
| HotSpot, uber-jar | 516 ms | 497 ms | 518 ms | 111 MB |
| HotSpot + JDK AOT cache | 321 ms | 307 ms | 347 ms | 91 MB |
| champaca, uber-jar, cold | 40.0 s | 39.4 s | 41.3 s | 1917 MB |
| champaca, uber-jar | 3056 ms | 3020 ms | 3567 ms | 760 MB |
| champaca, fast-jar | 11.1 s | 10.3 s | 11.6 s | 809 MB |

Every variant printed what HotSpot prints. A warm champaca start takes five
to six times as long as HotSpot's, for both frameworks, and needs six to seven
times its memory. Spring AOT saves champaca a little more than it saves HotSpot,
21% of the start against 17%. The cold rows include the JDK's own first boot, every method of the
framework translated and compiled once and the cache written. The fat jar
and the fast-jar sit between the two: their loaders' classes come from the
cache too, but the loaders read their nested jars on every run, and a class
whose cached code depends on one the cache can't check compiles again (see
the variants above). Upstream's OpenLDK images can't keep loader-defined
classes at all, which is why they run the class path for Spring Boot and the
uber-jar for Quarkus. For scale, upstream measured its OpenLDK warm
image at 4.3 s (Spring Boot) and 1.5 s (Quarkus) on a 16-CPU Xeon E5-2670,
with HotSpot at 2.9 s and 1.3 s on the same machine, so the two projects'
numbers aren't comparable directly; the HotSpot rows are the yardstick.

The Quarkus fast-jar's HotSpot maximum, 5.5 s, is a single slow run while
other work was busy; its median stands, as does the 5.7 s maximum of
champaca's extracted jar.

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
