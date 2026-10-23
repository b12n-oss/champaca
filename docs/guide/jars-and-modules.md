# Jars and JDK modules

## The classpath

`init!` takes a `:classpath` of jars and directories, searched in order like `java -cp`. One difference from Java: the classpath is searched before the JDK's modules, so a jar that ships its own copy of a JDK class would win. Java looks in the JDK first.

```clojure
(ldk/init! {:classpath ["target/lib.jar"]})
(ldk/invoke-static "champaca.lib.Shelf" "sum" [1 2 3 4])   ;=> 10
```

(`target/lib.jar` is the small test library `bb test` builds from `test/jar`. `examples/jars.clj` uses it.)

A directory holds `.class` files in package folders, the way `javac -d` writes them. A jar is read in place, nothing gets unpacked. Classes load lazily: a class is read, parsed and registered the first time something names it, and each method compiles the first time it's called.

Pass the whole classpath on the first `init!`. A second call does nothing, so it can't add jars later.

### A real library

Eight libraries have been run this way and checked against the JVM, with identical output:

| Library | What was exercised |
|---|---|
| commons-lang3 3.12.0 | `StringUtils` |
| gson 2.13.2 | parsing, `toJson` of a tree, a plain object and a `Map`, `fromJson` into a class with no default constructor, pretty printing |
| jsoup 1.18.1 | parsing HTML, CSS selectors, absolute URLs, `Jsoup.clean` with a safelist |
| Jackson 2.20 | `readTree`, `writeValueAsString` of maps and annotated classes, `@JsonProperty`, `@JsonIgnore`, `readValue` into a bean with a `List<Point>` property, indented output |
| Caffeine 3.2.2 | size, weight, write- and access-based eviction against a fake clock, removal listeners, refresh, `getAll`, async and loading caches, stats |
| Guava 33.4.8 | CRC32C, CRC32, Adler-32, Murmur3, FarmHash, SHA-256, SHA-512 and MD5 hashing, a Bloom filter, immutable multimaps, `Joiner`, `Splitter` |
| SLF4J 2.0.16 with `slf4j-simple` | logging through the provider `ServiceLoader` finds in `slf4j-simple`'s jar |
| SnakeYAML 2.3 | loading and dumping maps, beans with generic `List` and `Map` properties, public fields and enums, anchors and merge keys, multi-document streams, block style, timestamps, error messages with their line and column marks |

`bb libs` reruns all eight, from the probe programs in `test/libs`. Each one runs on the JVM and through champaca, from a fresh cache, and the outputs and exit codes have to match (stderr too, for SLF4J, whose logging goes there). The jars come from `$HOME/.m2/repository`, and each one's SHA-1 is checked against the pin in `test/libs/libs.edn` before it's used. A library whose jars are missing is skipped and named, and a skip fails the run unless you pass `--allow-skip`. A run that compares nothing fails either way. `bb libs:fetch` downloads the missing jars from Maven Central and keeps a jar only when its SHA-1 matches its pin. `bb libs Gua Yml` runs just those two, and an unknown name is an error. None of this is part of `bb test`, which needs no network.

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

Champaca doesn't resolve Maven coordinates, so a library's dependencies have to be listed too, as Jackson's three jars are here.

### Resources

A jar's other files are reachable the way Java code expects. `getResourceAsStream` reads them, `getResource` gives a `jar:` URL (a `file:` URL for a directory on the classpath) that opens like any other, and `ServiceLoader` finds the providers listed under `META-INF/services`. That last one is how SLF4J 2 finds its logging backend: without it, SLF4J prints "No SLF4J providers were found" and logs nothing. `test/java/JarCheck.java` loads a service, a properties file and a URL from `test/jar`.

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

Some libraries still reach for `sun.misc.Unsafe`, which lives in `jdk.unsupported`. It works, and `Shelf.bumpThroughUnsafe` in the test jar uses it. Champaca sets `sun.misc.unsafe.memory.access=allow`, because the JDK's default (`warn`) finds its caller through `StackWalker`, which has no natives here yet.
