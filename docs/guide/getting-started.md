# Getting started

## What you need

- **jolt 0.8.23 or later.** Champaca's `deps.edn` declares it as `:jolt/min-version "0.8.23"`.
- **JDK 25, with `JAVA_HOME` set.** Champaca doesn't run a JVM. It reads the JDK's classes out of its runtime image, `$JAVA_HOME/lib/modules`, and compiles them itself, so it needs only that, plus `javac` if you want to compile Java of your own. It's been tested with OpenJDK 25.0.2 (the build from jdk.java.net) and Temurin 25.0.4.1 on macOS and Linux, GraalVM CE 25.0.1, and a `jlink`-built runtime compressed with `--compress zip-6`.
- **babashka**, for the `bb` tasks in this repo.

Inside this repo, `bb jolt` builds the jolt commit pinned in `deps.edn` and `bb test` prefers it. That's optional; [performance](performance.md#the-pinned-jolt) explains why the pin exists.

## The first call

From the repo root:

```sh
jolt -M -e "(require '[champaca.api :as ldk]) (ldk/init!) (println (ldk/invoke \"champaca\" \"toUpperCase\"))"
```

That prints `CHAMPACA`. `init!` boots the JDK, which takes about a second the first time and about 0.3 s once the startup cache exists. After that every call goes straight into compiled code.

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

Call `init!` once per process, before any other champaca function. A second call returns `:ok` and does nothing else, and that includes ignoring a different `:classpath`, so pass the full classpath the first time. To load classes from your own jars: `(ldk/init! {:classpath ["lib/gson-2.13.2.jar"]})`. [jars-and-modules](jars-and-modules.md) has the details.

## From an editor

`bb examples` lists the programs in `examples/`, and `bb example interop` runs one. `jars`, `fields` and `serialization` need `target/lib.jar`, which `bb test` builds from `test/jar`. `bb example` builds that jar too, when it's missing or older than its sources.

To work live from an editor, start jolt's nREPL server from the repo root:

```sh
bb nrepl
```

It binds a free port on 127.0.0.1 and writes it to `.nrepl-port`, which CIDER and Calva read. In Emacs, `M-x cider-connect-clj` with host `localhost` offers that port. `bb nrepl 7890`, or `CHAMPACA_NREPL_PORT=7890`, asks for a port of your own, and a busy one is refused rather than swapped for another. When `.nrepl-port` already names a server that answers, `bb nrepl` says so and stops, so connect to that one instead. Stop the server with `^C`, which also removes the file.

Since `init!` runs once per process, as above, the first form to evaluate in a fresh session is

```clojure
(require '[champaca.api :as ldk])
(ldk/init! {:classpath ["target/lib.jar"]})
```

After that every example loads in the same session (`C-c C-k` in CIDER), because their own `init!` calls do nothing. Skip it and load `interop.clj` first, though, and that example's plain `init!` wins: `jars.clj` then can't find its classes until you restart the server. Expect one difference from `bb example`: nREPL evaluates on a connection thread, so `threads.clj` prints a `jolt-<n>` name where a plain run prints `main`. jolt's server has eval, load-file and completion but no lookup op, so CIDER's jump to definition and documentation popups don't work.

## Running the tests

```sh
bb test
```

It compiles `test/java/*.java` with `javac`, runs each program through champaca and compares the output with a `.expected` file recorded from the JVM. A program with a `.stdin` file next to it reads that file as its standard input, and one that runs past five minutes fails. Then it runs the `examples/*.clj` programs and checks them the same way. The first line says which jolt it used.

## Where next

[calling-java](calling-java.md) covers the API in full.
