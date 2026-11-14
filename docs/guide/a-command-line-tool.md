# A command-line tool

`examples/hn` is a small jolt project that prints the Hacker News front page. It fetches the page with [OkHttp](https://square.github.io/okhttp/), a library written in Kotlin, and reads it with [jsoup](https://jsoup.org), one written in Java. Both run inside the jolt process on champaca, and `jolt dist` packs the whole thing into one file you can copy to another Mac and run, with no JDK installed.

It's a standalone project with its own `deps.edn` and `bb.edn`, so you can copy the directory out and start your own tool from it.

## Running it

```sh
cd examples/hn
jolt fetch               # the jars in jars.edn, into ~/.m2, each checked against its pinned SHA-1
jolt run --limit 5       # the first five stories
jolt test                # the tests, on a hand-made page, no network
```

`jolt hn` from the repository's root does the same (`jolt hn test`, `jolt hn dist`). The first `jolt run` compiles OkHttp's, jsoup's and the JDK's code it reaches, so it takes about half a minute. Runs after that start from the cache.

```
  1. Ggml v1.0.0 (github.com/ggml-org)
     174 points by cobychapple, 4 hours ago, 29 comments
     https://github.com/ggml-org/ggml/releases/tag/v1.0.0
```

`--file page.html` reads a saved page instead, `--edn` prints a map per story, and `--help` lists the rest.

## The code

`src/hn/main.clj` is about 130 lines. Java and Kotlin classes are reached through `champaca.api` by name, so the jolt side doesn't change with the language a library was written in:

```clojure
(let [client (ldk/construct "okhttp3.OkHttpClient")
      request (-> (ldk/construct "okhttp3.Request$Builder")
                  (ldk/invoke "url" url)
                  (ldk/invoke "build"))
      response (ldk/invoke (ldk/invoke client "newCall" request) "execute")]
  (ldk/invoke (ldk/invoke response "body") "string"))
```

jsoup's CSS selectors do the reading: each story is a `tr.athing` row and the row after it. The options come from `babashka.cli`, which jolt ships. `jars.edn` lists the four jars (OkHttp, Okio, Kotlin's standard library, jsoup), and the tasks hand them to champaca as `CHAMPACA_CLASSPATH`, which `(ldk/init!)` reads.

## One file

```sh
jolt dist                # writes target/hn
```

`jolt dist` runs champaca's `jolt bundle` with `--single`, so it needs `JAVA_HOME` pointing at a JDK 25 and a champaca checkout (`CHAMPACA_HOME`, by default the repository the example sits in). The bundle is a directory: native builds of champaca and of `hn.main`, a trimmed JDK runtime, the four jars and a startup cache, warmed by one live run at build time ([app-bundles](app-bundles.md)). `--single` packs it into `target/hn`, a shell header followed by the directory as a `tar.gz`.

The first run of `target/hn` unpacks it under `~/.cache/champaca/single/hn-<digest>/` (`CHAMPACA_SINGLE_DIR` changes the place), and every later run starts it from there. A new build has a new digest, so it never runs an older build's files. A damaged copy stops with "couldn't unpack" and leaves nothing behind.

Measured on an M1 Pro, 2026-11-14, the file copied to another directory and run with `env -i`, so with no `JAVA_HOME`:

| | |
|---|---|
| `target/hn` | 80.8 MB |
| unpacked | 167 MB |
| first run, unpacking included | 7.7 s |
| later runs, live front page | 2.3 s (fetching it with curl took about 1 s on that network) |
| later runs, `--file` | 0.63 s |

The file runs on the OS and processor it was built on, here macOS on Apple Silicon. The binaries inside it aren't signed. Before handing it to other people, build them signable and sign them as [app-bundles](app-bundles.md) describes.

## Behind a proxy that re-signs TLS

Some company networks inspect HTTPS by re-signing every site's certificate with their own CA. The bundle's runtime trusts only the JDK's standard CAs, so there `hn` fails with `PKIX path building failed`. Point it at a Java trust store that holds the company's CA, such as the one your own JDK uses:

```sh
hn --trust-store "$JAVA_HOME/lib/security/cacerts"
HN_TRUST_STORE="$JAVA_HOME/lib/security/cacerts" jolt dist   # the training run needs it too
```

The option sets `javax.net.ssl.trustStore` before the first connection. The path isn't kept in the file, so a build made behind such a proxy still trusts only the standard CAs elsewhere.

## Your own tool

Copy `examples/hn`, change `:local/root "../.."` in `deps.edn` to a git coordinate for champaca, list your jars in `jars.edn` and set `CHAMPACA_HOME` to a champaca checkout for `jolt dist`. A library that needs the network at build time to warm the cache can train on a saved input instead: `jolt bundle`'s `--train` takes any arguments ([app-bundles](app-bundles.md#the-training-run)).
