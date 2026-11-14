# Getting started

## What you need

- **jolt 0.8.24 or later.** Champaca's `deps.edn` declares it as `:jolt/min-version "0.8.24"`.
- **JDK 25, with `JAVA_HOME` set.** Champaca doesn't run a JVM. It reads the JDK's classes out of the runtime image, `$JAVA_HOME/lib/modules`, and compiles them itself, so that's all it needs, plus `javac` for compiling Java of your own. The suite runs on OpenJDK 25.0.2, the build from jdk.java.net, on macOS and Linux. GraalVM CE 25.0.1, Temurin 25.0.4.1 on macOS and a `jlink`-built runtime compressed with `--compress zip-6` passed earlier, smaller versions of it and haven't been tried on the current one.

You need the JDK where a program is built and developed, but a machine that only runs it can do without. `jolt bundle` packs the slice of the JDK a program needs, champaca built into a native binary, and a warmed cache into one directory, which runs anywhere on the same OS and architecture ([app-bundles](app-bundles.md)).

The repo's tasks live in `bb.edn`, which jolt reads as it is, and `jolt tasks` lists them. Inside this repo `jolt jolt` builds the jolt commit pinned in `deps.edn`, and `jolt test` prefers that build. It's optional. [performance](performance.md#the-pinned-jolt) explains why the pin exists.

## The first call

From the repo root:

```sh
jolt -M -e "(require '[champaca.api :as ldk]) (ldk/init!) (println (ldk/invoke \"champaca\" \"toUpperCase\"))"
```

That prints `CHAMPACA`. `init!` boots the JDK, which takes four or five seconds the first time and about 0.4 s once the startup cache exists. After that every call goes straight into compiled code.

A file works the same way. This is a cut-down `examples/dynamic.clj`:

```clojure
(require '[champaca.api :as ldk])

(ldk/init!)

(ldk/invoke-static "java.lang.Math" "max" 3 7.5)                                  ;=> 7.5
(ldk/invoke-static "java.lang.String" "format" "%s has %d tepals" "champaca" 12)  ;=> "champaca has 12 tepals"
(ldk/invoke "champaca" "substring" 1 4)                                         ;=> "ham"
```

Run it with `jolt -M path/to/file.clj`.

## From your own project

Add champaca as a dependency. A local checkout is the simplest:

```clojure
;; deps.edn
{:paths ["src"]
 :deps {b12n-oss/champaca {:local/root "/path/to/champaca"}}}
```

```clojure
;; src/app/main.clj
(ns app.main
  (:require [champaca.api :as ldk]))

(defn -main [& _]
  (ldk/init!)
  (println (ldk/invoke-static "java.lang.String" "format" "%s has %d tepals" "champaca" 12))
  (println (ldk/invoke "champaca" "toUpperCase")))
```

`jolt -M -m app.main` prints `champaca has 12 tepals` and then `CHAMPACA`.

Call `init!` once per process, before any other champaca function. A second call returns `:ok` and does nothing else. It ignores a different `:classpath` or `:preview` too, so pass the full classpath the first time. Classes from your own jars load with `(ldk/init! {:classpath ["lib/gson-2.13.2.jar"]})`, and [jars-and-modules](jars-and-modules.md) has the details.

## From an editor

Each file in `examples/` is a namespace of `clojure.test` tests, so every call sits next to the value it returns. `jolt examples` lists them. `jolt example interop` runs one file's tests through `test/run_example.clj`, which also saves the startup cache. `jars`, `fields`, `serialization` and `classes` need `target/lib.jar`, built by `jolt test` from `test/jar`, and `jolt example` builds it too when it's missing or older than its sources.

To work live from an editor, start jolt's nREPL server from the repo root:

```sh
jolt nrepl
```

It binds a free port on 127.0.0.1 and writes it to `.nrepl-port`, which CIDER and Calva read. In Emacs, `M-x cider-connect-clj` with host `localhost` offers that port.

`jolt nrepl 7890`, or `CHAMPACA_NREPL_PORT=7890`, asks for a port of your own. A busy one is refused, never swapped for another. If `.nrepl-port` already names a server that answers, `jolt nrepl` says so and stops, and you connect to that one. `^C` stops the server and removes the file.

`init!` runs once per process, so the first form to evaluate in a fresh session is

```clojure
(require '[champaca.api :as ldk])
(ldk/init! {:classpath ["target/lib.jar"]})
```

After that every example loads in the same session (`C-c C-k` in CIDER), because their own `init!` calls do nothing. Load `interop.clj` first instead and its plain `init!` wins, so `jars.clj` can't find its classes until you restart the server.

Loading a file only defines its tests. Evaluate any form inside a `deftest` to see its value, or `(clojure.test/run-tests)` in the file's namespace to run them all. jolt's server has eval, load-file and completion but no test or lookup op, so CIDER's test commands, jump to definition and documentation popups don't work.

One test, `class-initialization` in `fields.clj`, passes only the first time it runs in a session, because a class initializes once per process.

## The docs site

These guides can be read as a local site. `jolt site:serve` builds `docs/` into `_site/` and serves it on the first free port from 3000, printing the URL (`http://localhost:<port>/champaca/`). `jolt site:serve 4000` asks for a port of your own and refuses a busy one. `jolt site:build` only builds, and `jolt site:clean` deletes `_site/`.

[jlt-commons/docs-engine](https://github.com/jlt-commons/docs-engine) does the rendering. It must be checked out at `$JLT_DOCS_ENGINE`, `~/dev/jlt-commons/docs-engine` or `../docs-engine` (`git clone https://github.com/jlt-commons/docs-engine ~/dev/jlt-commons/docs-engine`), and its config for this project is `docs/site.edn`.

## Running the tests

```sh
jolt test
```

It compiles `test/java/*.java` with `javac`, runs each program through champaca and compares the output with a `.expected` file recorded from the JVM. A program with a `.stdin` file next to it reads that file as standard input. One with a `.options` file gets that file's lines as launcher options (`-D` ones) before the classpath. A program that runs past five minutes fails.

All those programs share one startup cache, and a bug that shows only on a program's own second run would hide behind a cache the others filled. So a handful run again alone, each on a fresh cache, cold and then warm. These cover features a program exercises on its own: foreign calls and upcalls, sockets, serialization, lambdas, processes, the management beans, the Vector API, modules, classes defined at run time and stack traces.

`test/libcache` checks the cache of classpath classes. It runs three programs (`LibCacheMain`, `LamMain`, `EditMain`) over jars and directories that change between runs, twenty-five runs in all. Upstream OpenLDK's own `aaa` test programs run the same way as `test/java`'s, from `test/aaa`, whose README says how they're laid out. Last come the tests in each `examples/*.clj` file, each file in a process of its own that saves the startup cache when its tests are done. The first line of output says which jolt it used.

## Where next

[calling-java](calling-java.md) covers the API in full.
