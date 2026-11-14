# hn

The Hacker News front page in your terminal. A jolt program fetches it with OkHttp, which is written in Kotlin, and reads it with jsoup, which is written in Java. Both run inside the jolt process on champaca, with no JVM.

```sh
jolt fetch           # the jars in jars.edn, into ~/.m2
jolt run --limit 5   # the first five stories
jolt test            # the tests, on test/hn/frontpage.html
jolt dist            # target/hn: one file that runs with no JDK installed
```

Behind a network that re-signs HTTPS, pass a trust store holding its CA: `--trust-store FILE`, or `HN_TRUST_STORE` for `jolt dist`'s training run.

To start your own tool from this one, copy the directory and point `deps.edn`'s `champaca/champaca` at a git coordinate instead of `../..`. The guide page [a command-line tool](../../docs/guide/a-command-line-tool.md) explains the code, the one-file build and its numbers.
