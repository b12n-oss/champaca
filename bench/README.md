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
| HotSpot, fast-jar / uber-jar | Quarkus's default packaging, which loads the application through its RunnerClassLoader, and one jar with every class |
| champaca, cold | through champaca with an empty startup cache: every class parsed and every method translated and compiled, the JDK's boot included |
| champaca | through champaca with the startup cache its first run filled |

champaca's startup cache keeps compiled code and the class table, never
Java objects, so every run starts the framework from scratch and every
static initializer runs again. Nothing here is the counterpart of
upstream's OpenLDK snapshot image (a saved heap with the framework already
started) or of a GraalVM native image, so those variants are left out; the
champaca rows compare with HotSpot's, and with upstream's OpenLDK warm
image.

Classes that a framework's own class loader defines (the fat jar's nested
jars, under Spring Boot's launcher, and the Quarkus fast-jar's application,
under its RunnerClassLoader) aren't kept in the startup cache, so their
code is compiled on every run. The extracted jar and the uber-jar load from
the class path, which the cache keeps.

## Results

RESULTS

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

Left: `champaca.main` takes no `-D` options yet, so the Spring AOT variant
runs on HotSpot only.
