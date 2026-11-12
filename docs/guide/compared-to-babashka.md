# Champaca compared with babashka

Both run Java-flavoured work without starting a JVM, but they're built almost
inside out from each other. [Babashka](https://github.com/babashka/babashka)
is a Clojure interpreter, [SCI](https://github.com/babashka/sci), that
GraalVM's native-image compiled ahead of time into one binary along with a
selection of Java classes. Your Clojure is interpreted there, and the Java it
can reach was chosen when babashka was built. Champaca is a Java runtime inside
jolt. It loads real class files at run time, turns each method's bytecode into
Chez Scheme the first time it's called and compiles that to native code in the
same process, so your Clojure is jolt's, compiled natively, and the Java it
calls is compiled on demand.

| | Babashka (native-image and SCI) | Champaca (jolt and Chez, after OpenLDK) |
|---|---|---|
| What runs your Clojure | SCI, which runs it form by form without compiling it | jolt, which compiles it to native code through Chez |
| What runs the Java | Classes native-image compiled into the binary | The JDK's own class files, read from `$JAVA_HOME/lib/modules`, and your jars, translated method by method |
| Which Java classes there are | A pre-selected set; you can't add classes at run time | Any class file: the whole JDK, the class path, and classes made at run time (`defineClass`, proxies, hidden classes) |
| When Java code is compiled | Before release, on the build machine | At run time, the first time each method is called |
| Where Java objects live | The native image's heap; Clojure's data types are the same as on the JVM | Chez records in jolt's own heap |
| How the JDK starts | Whatever native-image prepared at build time | The JDK's own boot, as HotSpot runs it, module system included ([how-it-works](how-it-works.md)) |
| Startup | Fast, with nothing left to compile | A first `Hello` takes 4.3 s, a warm one 0.39 s on an M1 Pro, from the startup cache ([performance](performance.md#the-startup-cache)) |
| Threads | Run in parallel (`pmap`, `future`) | Real threads, but one lock lets one run Java at a time ([limitations](limitations.md#threads-take-turns)) |
| Clojure it lacks | SCI is a subset: no `deftype`, `definterface` or unboxed math, `defprotocol` and `defrecord` emulated with multimethods and maps, `reify` of one class at a time | Whatever jolt lacks; champaca adds no limits of its own |
| Adding capability | A new babashka build with more classes, or a [pod](https://github.com/babashka/pods) | A jar on the class path |
| Status | `clojure.core` and `java.lang` support is stable, by its README | Experimental, checked along its tests and the twenty-three libraries `jolt libs` runs |

The babashka column comes from its README's "Differences with Clojure" and
"Setting expectations" sections (at `b429a12e`).

## What that means in practice

Babashka starts instantly and runs the Java it shipped with at native speed.
Its own Clojure goes through an interpreter, though, which is why its README
sends a script that runs for more than a few seconds, or loops a lot, to
Clojure on the JVM. And a Java class outside its set isn't there at all.

Champaca's strength is reach. Real libraries run unchanged: Jackson, Guava,
Guice, H2, Byte Buddy and the rest of what `jolt libs` checks, with the same
output as HotSpot. Once a method is compiled it runs as native Chez code with
inline caches, and jolt code calls it as a plain procedure call.

What champaca costs is a slow first run (the startup cache softens it, much as
class data sharing does for HotSpot), Java code that can't run on two threads
at once, and warm runs slower than the JVM's for library-heavy code: a warm
Jackson run takes 1.34 s against 0.28 s on the JVM
([performance](performance.md)).

At the boundary, babashka's Clojure data and Java objects are the same JVM
objects. Champaca's Java objects are Chez records, apart from jolt's own
values, so data crossing between them is converted on the way
([data-conversion](data-conversion.md)).
