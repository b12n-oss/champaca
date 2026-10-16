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

Six libraries have been run this way and checked against the JVM, with identical output:

| Library | What was exercised |
|---|---|
| commons-lang3 3.12.0 | `StringUtils` |
| gson 2.13.2 | parsing, `toJson` of a tree, a plain object and a `Map`, `fromJson` into a class with no default constructor, pretty printing |
| jsoup 1.18.1 | parsing HTML, CSS selectors, absolute URLs, `Jsoup.clean` with a safelist |
| Jackson 2.20 | `readTree`, `writeValueAsString` of maps and annotated classes, `@JsonProperty`, `@JsonIgnore`, `readValue` into a bean with a `List<Point>` property, indented output |
| Caffeine 3.2.2 | size, weight, write- and access-based eviction against a fake clock, removal listeners, refresh, `getAll`, async and loading caches, stats |
| Guava 33.4.8 | CRC32C, CRC32, Adler-32, Murmur3 and FarmHash hashing, a Bloom filter, immutable multimaps, `Joiner`, `Splitter` |

They aren't part of `bb test`, since that would mean downloading the jars. If you have them in `~/.m2`:

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

## JDK modules

The JDK's own classes come from `$JAVA_HOME/jmods`, the same files `jlink` builds runtimes from. `java.base` is where boot starts, but nothing limits you to it:

```clojure
(ldk/invoke (ldk/construct "java.sql.Date" 0) "getTime")                              ;=> 0       java.sql
(ldk/invoke (ldk/get-static "java.util.logging.Level" "WARNING") "getName")           ;=> "WARNING"  java.logging
```

A jmod is a zip file behind a 4-byte header, so it can't be opened as a zip directly. On its first run champaca reads the zip directory at the end of each of the 69 jmods in JDK 25 and builds an index from package to module (876 packages). It saves the index as `~/.cache/champaca/jmods/<jdk>/packages.edn`. After that a class lookup goes straight to the module that holds its package. The zip inside that one module gets copied out, once, beside the index. `bb test` touches four modules and copies 20 MB.

Builds disagree on where a jmod's zip offsets count from. The JDK's `jmod` tool counts them from the end of the header, but 30 of the 84 jmods in GraalVM CE 25.0.1 count them from the start of the file. Champaca takes whichever base puts the zip directory where its end record says it is, and copies the zip out from that same point, so both kinds work.

There's no module system on top of that: no `module-info` checks and no export rules. Every class sits in one unnamed module and can see every other.

## `sun.misc.Unsafe`

Some libraries still reach for `sun.misc.Unsafe`, which lives in `jdk.unsupported`. It works, and `Shelf.bumpThroughUnsafe` in the test jar uses it. Champaca sets `sun.misc.unsafe.memory.access=allow`, because the JDK's default (`warn`) finds its caller through `StackWalker`, which has no natives here yet.
