# Startup benchmarks

How fast a small Spring Boot application and a small Quarkus application
start on champaca, next to HotSpot. Both applications, and the idea of
timing them every way they can be packaged, come from upstream OpenLDK's
`bench/` by Anthony Green (the `burinc/openldk` fork at `3d92c3f`, the same
license as champaca). Their sources here are upstream's, minus the
`Snapshot` main classes, which only OpenLDK's saved images use.

```bash
jolt bench spring-boot          # build, then time (jolt bench spring-boot build|bench)
jolt bench quarkus
jolt bench petclinic          # a server: SERVER_RUNS (default 3) per variant
RUNS=20 COLD_RUNS=5 jolt bench spring-boot bench
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
average 3.3 to 7.6). Ten timed runs each, three for a cold variant. Both ran
on champaca at `6579ba1`:

| Spring Boot | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fat jar | 872 ms | 835 ms | 923 ms | 234 MB |
| HotSpot, extracted jar | 721 ms | 703 ms | 740 ms | 192 MB |
| HotSpot + Spring AOT | 603 ms | 584 ms | 666 ms | 157 MB |
| HotSpot + JDK AOT cache | 343 ms | 336 ms | 357 ms | 147 MB |
| HotSpot + Spring AOT + AOT cache | 272 ms | 257 ms | 279 ms | 116 MB |
| champaca, extracted jar, cold | 48.4 s | 48.0 s | 48.6 s | 2690 MB |
| champaca, extracted jar | 2573 ms | 2554 ms | 2746 ms | 757 MB |
| champaca + Spring AOT | 2313 ms | 2277 ms | 2471 ms | 743 MB |
| champaca, fat jar | 4419 ms | 4200 ms | 5968 ms | 775 MB |

| Quarkus | Median | Min | Max | Peak RSS |
|---|---|---|---|---|
| HotSpot, fast-jar | 401 ms | 367 ms | 5378 ms | 100 MB |
| HotSpot, uber-jar | 409 ms | 386 ms | 438 ms | 112 MB |
| HotSpot + JDK AOT cache | 282 ms | 246 ms | 298 ms | 91 MB |
| champaca, uber-jar, cold | 31.8 s | 30.8 s | 32.1 s | 2076 MB |
| champaca, uber-jar | 1640 ms | 1550 ms | 2199 ms | 493 MB |
| champaca, fast-jar | 1945 ms | 1864 ms | 2185 ms | 618 MB |

Every variant printed what HotSpot prints. A warm champaca start from the
class path takes three and a half to four times as long as HotSpot's and
needs four times its memory. On 2026-10-29 it took five to six times as long
and six to eight times the memory: since then the class entries became
bytes with their strings interned, the inspector information left the jar
classes ([performance](../docs/guide/performance.md#memory)), and an entry's
vtable and methods stay unparsed until something reads them, while the
`ByteBuffer` accessors, the copies between arrays and native memory, string
concatenation and the string intrinsics got faster
([performance](../docs/guide/performance.md#a-framework-loaders-own-work)).
Every champaca row is 25 to 40% down on the day before. Spring AOT saves
HotSpot 16% of its start and champaca 10%. The cold rows include the JDK's
own first boot, every method of the framework translated and compiled once
and the cache written. The fat jar and the fast-jar take longer than the
class path by what their own loaders do: 1.8 s for Spring Boot's launcher
to read its nested jars (150 ms on HotSpot) and 0.3 s for Quarkus's
RunnerClassLoader (nothing HotSpot's medians show), since the classes those
loaders define come from the cache like the class path's. Until 2026-10-29
they didn't, and the fat jar took 21.0 s, the fast-jar 11.1 s; on 2026-10-29
the launcher's own work still cost 3.3 s and 0.5 s. Upstream's
OpenLDK images can't keep loader-defined classes, which is why they run the
class path for Spring Boot and the uber-jar for Quarkus. For scale, upstream
measured its OpenLDK warm image at 4.3 s (Spring Boot) and 1.5 s (Quarkus)
on a 16-CPU Xeon E5-2670, with HotSpot at 2.9 s and 1.3 s on the same
machine, so the two projects' numbers aren't comparable directly; the
HotSpot rows are the yardstick.

The maxima well above their medians (HotSpot's Quarkus fast-jar at 5378 ms,
champaca's fat jar at 5968) are single slow runs each; the medians stand.

## Spring PetClinic

`jolt bench petclinic` times a server instead of a command. It clones
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) at
`500158f`, the application upstream OpenLDK lists among the ones it runs,
and builds it with Maven into `bench/petclinic/target`. Each run starts the
jar on port 0 and waits for Tomcat's "started on port" line. Then it
fetches eleven pages with curl (the owner list, a search, an owner, the
add and edit forms, the vets, the error page and a 404) and stops the
server with SIGTERM. The pages are compared byte for byte with HotSpot's.
The table gives medians of `SERVER_RUNS` runs (default 3) for three times
measured from exec: when Tomcat said it started, when the first page came
back, and when the last one did.

Measured 2026-11-13 on an Apple M1 Pro (10 CPUs, macOS 26.5.2), JDK 25.0.2,
champaca `7f506ea`, load average about 6 before the runs:

| Variant | Started | First page | All pages | Pages |
|---|---|---|---|---|
| HotSpot | 3875 ms | 4165 ms | 4746 ms | same |
| champaca, cold | 118.4 s | 131.7 s | 145.5 s | same |
| champaca | 26.2 s | 28.6 s | 30.4 s | same |

A warm start is about 7x HotSpot's, well behind the Spring Boot command
above, because PetClinic starts far more of the framework: Hibernate,
Thymeleaf, the H2 database and its schema, and Tomcat itself. Each page
also runs code the startup didn't, which champaca translates and compiles
the first time it runs.
The cache that the warm runs read is the one each SIGTERM saves, so a
warm row was impossible before champaca ran Java's shutdown hooks on a
signal (see below).

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
- A server stopped with SIGTERM, SIGINT or SIGHUP ran no Java shutdown hook
  and saved nothing to the startup cache, so PetClinic started cold every
  time (`test/signal`).
