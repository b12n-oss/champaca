# Jars and JDK modules

## The classpath

`init!` takes a `:classpath` of jars and directories, searched in order like `java -cp`. With no `:classpath` it uses `CHAMPACA_CLASSPATH`'s entries, which an [app bundle](app-bundles.md)'s launcher sets, so a bundled program needn't know where its jars went. One difference from Java: the classpath is searched before the JDK's modules, so a jar that ships its own copy of a JDK class would win. Java looks in the JDK first.

```clojure
(ldk/init! {:classpath ["target/lib.jar"]})
(ldk/invoke-static "champaca.lib.Shelf" "sum" [1 2 3 4])   ;=> 10
```

(`target/lib.jar` is the small test library `jolt test` builds from `test/jar`. `examples/jars.clj` uses it.)

A directory holds `.class` files in package folders, the way `javac -d` writes them. A jar is read in place, nothing gets unpacked. Classes load lazily: a class is read, parsed and registered the first time something names it, and each method compiles the first time it's called.

Pass the whole classpath on the first `init!`. A second call does nothing, so it can't add jars later.

A jar or directory compiled with `javac --enable-preview`, whose classes use one of JDK 25's preview features, needs `:preview true` on that first call, as `java` needs `--enable-preview`: `(ldk/init! {:classpath ["classes"] :preview true})`. Without it such a class is refused with HotSpot's `UnsupportedClassVersionError` the first time something names it. The option also turns the JDK's preview features on, such as structured concurrency and stable values ([running Java programs](running-java-programs.md) has the version rules, `examples/preview.clj` loads one).

A module outside the JDK's default module graph, such as the Vector API's `jdk.incubator.vector`, needs `:add-modules` on that first call, as `java` needs `--add-modules`: `(ldk/init! {:add-modules ["jdk.incubator.vector"]})` puts it in the boot layer (`examples/vector.clj`). Without it the module's classes aren't there, and a call naming one throws `NoClassDefFoundError`. Lane-wise arithmetic, compares, masks, shuffles and reductions then give HotSpot's results, and so do the operations that change a vector's shape or lane type (`castShape`, `reinterpretAs*`, `test(IS_NAN)`) and loads and stores through a `MemorySegment`, but none of it is accelerated ([limitations](limitations.md)).

### A real library

Seventeen libraries have been run this way and checked against the JVM, with identical output:

| Library | What was exercised |
|---|---|
| commons-lang3 3.12.0 | `StringUtils` |
| commons-lang 2.4 | `SerializationUtils` round trips and a failed one, `StringUtils`, `WordUtils`, `DurationFormatUtils`; built for Java 1.2, so its `finally` blocks are `jsr`/`ret` subroutines |
| commons-compress 1.27.1 | a tar archive over gzip written and read back, bzip2 both ways, a zip entry with a UTF-8 name, entry sizes, times and CRCs |
| commons-io 2.17.0 | `FileUtils` reading, writing, copying (which keeps the file's times, through `setattrlist` on macOS), listing with filters and deleting a tree, `FilenameUtils`, `IOUtils`, BOM detection, counting and tee streams |
| gson 2.13.2 | parsing, `toJson` of a tree, a plain object and a `Map`, `fromJson` into a class with no default constructor, pretty printing |
| jsoup 1.18.1 | parsing HTML, CSS selectors, absolute URLs, `Jsoup.clean` with a safelist |
| Jackson 2.20 | `readTree`, `writeValueAsString` of maps and annotated classes, `@JsonProperty`, `@JsonIgnore`, `readValue` into a bean with a `List<Point>` property, indented output |
| Guice 7.0.0 | constructor, field and provider injection, a `@Named` constant, a singleton scope, a `Multibinder` set, a `@Provides` method, a missing binding's error, the injector's bindings |
| Joda-Time 2.13.0 | a daylight-saving gap in `America/New_York` from the jar's own zone data, the next transition, formatting in another zone and locale, ISO parsing, periods and their wording, day counts, intervals |
| Kryo 5.6.2 | registered classes, a record, an enum and a map with an `int[]` written and read back through `Unsafe`, Objenesis and ReflectASM, the exact bytes, a deep copy |
| Caffeine 3.2.2 | size, weight, write- and access-based eviction against a fake clock, removal listeners, refresh, `getAll`, async and loading caches, stats |
| Guava 33.4.8 | CRC32C, CRC32, Adler-32, Murmur3, FarmHash, SHA-256, SHA-512 and MD5 hashing, a Bloom filter, immutable multimaps, `Joiner`, `Splitter` |
| SLF4J 2.0.16 with `slf4j-simple` | logging through the provider `ServiceLoader` finds in `slf4j-simple`'s jar |
| Byte Buddy 1.17.5 | subclasses generated at run time and loaded by each strategy (a wrapper and a child-first loader, `Unsafe` injection into the application loader, `Lookup.defineClass`), fixed values, delegation to a static method, interception with `@SuperCall`, a field accessor implementing `Supplier`, a generated `toString` |
| H2 2.3.232 | an in-memory database through JDBC: tables with keys, a batch of prepared inserts with decimals, dates, arrays and JSON, joins, grouping, window functions, a recursive query, a rolled-back transaction, constraint and syntax errors, metadata, four threads querying at once |
| picocli 4.7.6 | annotated options and positional parameters with type conversion, split values, a map option, subcommands with a mixin and `@ParentCommand`, usage and version help, error messages, a command defined by an interface (a `Proxy`), `parseArgs` results |
| SnakeYAML 2.3 | loading and dumping maps, beans with generic `List` and `Map` properties, public fields and enums, anchors and merge keys, multi-document streams, block style, timestamps, error messages with their line and column marks |

`jolt libs` reruns all seventeen, from the probe programs in `test/libs`. Each one runs on the JVM and through champaca, from a fresh cache, and the outputs and exit codes have to match (stderr too, for SLF4J, whose logging goes there). The jars come from `$HOME/.m2/repository`, and each one's SHA-1 is checked against the pin in `test/libs/libs.edn` before it's used. A library whose jars are missing is skipped and named, and a skip fails the run unless you pass `--allow-skip`. A run that compares nothing fails either way. `jolt libs:fetch` downloads the missing jars from Maven Central and keeps a jar only when its SHA-1 matches its pin. `jolt libs Gua Yml` runs just those two, and an unknown name is an error. None of this is part of `jolt test`, which needs no network.

To call one of them from jolt, with the jars in `~/.m2`:

```clojure
(ldk/init! {:classpath ["/path/to/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.20.1/jackson-databind-2.20.1.jar"
                        "/path/to/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.20.1/jackson-core-2.20.1.jar"
                        "/path/to/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.20/jackson-annotations-2.20.jar"]})

(def mapper (ldk/construct "com.fasterxml.jackson.databind.ObjectMapper"))
(def json (ldk/invoke mapper "writeValueAsString" {:name "champaca" :tags ["a" "b"]}))
;=> "{\"name\":\"champaca\",\"tags\":[\"a\",\"b\"]}"
(ldk/->clj (ldk/invoke mapper "readValue" json (ldk/invoke-static "java.lang.Class" "forName" "java.util.Map")))
;=> {"name" "champaca", "tags" ["a" "b"]}
```

Champaca doesn't resolve Maven coordinates, so a library's dependencies have to be listed too, as Jackson's three jars are here, unless a jar names them in its manifest's `Class-Path`, which is followed as on the JVM.

### Resources

A jar's other files are reachable the way Java code expects. `getResourceAsStream` reads them, `getResource` gives a `jar:` URL (a `file:` URL for a directory on the classpath, where a directory itself is a resource too, and `""` the entry's root, as on the JVM) that opens like any other, and `ServiceLoader` finds the providers listed under `META-INF/services`. That last one is how SLF4J 2 finds its logging backend: without it, SLF4J prints "No SLF4J providers were found" and logs nothing. `test/java/JarCheck.java` loads a service, a properties file and a URL from `test/jar`.

A class loader of your program's own, such as a `URLClassLoader` over a jar a plugin system found, answers `getResource`, `getResourceAsStream` and `getResources` the way the JDK has it: its parent first, then its own `findResource`. A class it defines finds its resources through it, and its `getProtectionDomain()` is the one the loader gave `defineClass`. A classpath class's code source is its entry's URL, a jar's or a directory's, which is how Quarkus's launcher and Spring Boot find their own jar (`test/java/ResourceCheck.java`).

Resources inside the JDK's own modules, such as the ICU data the JDK uses for Unicode normalization, can be read as streams but have no URL.

## JDK modules

The JDK's own classes come from `$JAVA_HOME/lib/modules`, the runtime image that `jlink` writes and the JVM loads its classes from. `java.base` is where boot starts, but nothing limits you to it:

```clojure
(ldk/invoke (ldk/construct "java.sql.Date" 0) "getTime")                              ;=> 0       java.sql
(ldk/invoke (ldk/get-static "java.util.logging.Level" "WARNING") "getName")           ;=> "WARNING"  java.logging
```

The image's format is jimage, read the way the JDK's own `jdk.internal.jimage.BasicImageReader` reads it. A header and an index come first, then every entry's bytes. The index is a perfect hash over full names like `/java.base/java/lang/Object.class`, and each package has an entry of its own, `/packages/java.lang`, naming its module. So a class lookup finds its package's module, then the class, and reads just those bytes from where they lie. Only the index is held in memory, about 1.5 MB for JDK 25, and nothing is copied or indexed beforehand.

Every JDK has an image, so any build of JDK 25 works. That includes Temurin's, which ship no jmods (JDK 24's JEP 493 made them optional). The image is also what the JVM runs, which isn't quite what the jmods hold: jlink generates a few classes as it links, such as `jdk.internal.module.SystemModules$all` and the `java.lang.invoke` holder classes, 21 of the roughly 27,850 in JDK 25.0.2.

A runtime built with `jlink` works as `JAVA_HOME` too, as long as it has the modules your code uses. One made with `--compress zip-6` (or any `zip-N`) is inflated as it's read. jlink's older string-sharing compression, `--compress 1`, isn't supported, and champaca says so the first time it reads a class from such an image.

The module system boots on top of that, as on the JVM: `System.initPhase2()` builds the boot layer from the module graph jlink resolved into the image, so `ModuleLayer.boot().modules()` lists what the JVM's does for the same JDK, `ServiceLoader` finds the providers those modules declare (`jdk.localedata`'s locale data, `jdk.charsets`' charsets, `jdk.jartool`'s `ToolProvider`), a JDK class's `getModule()` is its named module, and reflection into a package the module doesn't export or open fails the way it does on the JVM. Your own classes are in the application loader's unnamed module and report that loader, as on the JVM, but nothing reads a `module-info` from your jars, so there's no module path and no checks between your own packages ([limitations](limitations.md#other-gaps)).

## `sun.misc.Unsafe`

Some libraries still reach for `sun.misc.Unsafe`, which lives in `jdk.unsupported`. It works, and `Shelf.bumpThroughUnsafe` in the test jar uses it. JDK 25 prints a warning on stderr the first time a program touches memory through it (the default, `warn`), and champaca prints the same one, word for word; `-Dsun.misc.unsafe.memory.access=allow` turns it off and `deny` makes the call throw, as on the JVM.
