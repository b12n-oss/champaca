# Working on champaca

The tasks below are in `bb.edn`, and `bb tasks` lists them with a line each. [getting-started](getting-started.md) has what you need installed and what `bb test` checks, and [debugging](debugging.md) explains the `CHAMPACA_*` switches.

## Tasks and switches

```sh
bb jolt                                               # build the pinned jolt (about 45 s, once per pin)
bb test                                               # every test/java and test/aaa program vs the JVM, plus the examples/*.clj tests
JOLT=jolt bb test                                     # the same suite on the jolt from your PATH
bb libs:fetch && bb libs                              # the eight real libraries vs the JVM (jars into ~/.m2)
bb battery [ref]                                      # before a merge: bb test cold, warm, cache off and CHAMPACA_BT=1, then bb libs (after bb libs:fetch), on a worktree of HEAD
                                                      # with its own cache (kept until the next run of another ref), logs under target/battery/; about 30 min, more under load; BATTERY_MODES=cold,libs for a subset
bb linux                                              # bb test and bb libs on Linux, in Docker (bb linux amd64 for x86_64)
bb examples                                           # list the examples/*.clj test files
bb example interop                                    # run one file's tests (builds target/lib.jar first if it needs to)
bb heap target/classes Hello                          # where the memory goes: the live heap by type after Hello (roots or java for the other views)
bb prof target/classes Streams                        # where the time goes: the hottest Java frames of a run, sampled (PROF_US=500 for a finer interval)
bb nrepl                                              # an nREPL server for your editor, on a free port written to .nrepl-port
bb metrics                                            # jolt-metrics: refactoring queue, hotspots, cleanup candidates (report only; bb metrics:edn or metrics:json for data)
bb metrics:html                                       # the same as one page to share, target/metrics.html
bb site:serve                                         # build the docs site and serve it on the first free port from 3000 (bb site:serve 4000 refuses a busy port); needs a docs-engine checkout
bb site:build                                         # build the docs site into _site/ (bb site:clean deletes it)
bb bundle -o out/app -cp CP -m Main --train          # a directory that runs Main with no JDK installed (--jolt NS --project DIR for a jolt program)
jolt -M -m champaca.main target/classes Streams   # after bb test has compiled the classes
jolt -M -m champaca.main -p mods -m app/app.Main  # a modular application, as java -p mods -m app/app.Main
CHAMPACA_BT=1 ...       # keep a shadow stack, so an error inside champaca itself prints Java frames, and name each missing native as it's hit
                        # (and say why an NPE got no message)
CHAMPACA_STATS=1 ...    # print classes loaded (and how many from the library cache), methods compiled, cached units loaded, loader-defined classes the cache missed
CHAMPACA_LINES=all ...  # line numbers in stack traces for jar and loader-defined classes too, at about the memory their code takes
CHAMPACA_PROF=1 ...     # time spent per phase (parse, register, codegen, compile, cache), after boot and when main returns
CHAMPACA_CALLER_DEBUG=1 ...  # print the frames each Reflection.getCallerClass call sees
CHAMPACA_NPE_STRICT=1 ...    # throw when an NPE message can't be built, instead of a null message (bb test sets it)
CHAMPACA_TRACE=1 ...    # log each compiled method, and write the generated Scheme to target/jit/trace.ss
CHAMPACA_CALL_CACHE=off ...  # choose the overload again on every invoke, invoke-static and construct
```

## Before a merge

`bb test` must pass, and so must the battery and the Linux run. The battery runs the whole suite four ways on a worktree of the ref: cold, warm, with the cache off and with `CHAMPACA_BT=1`. Then it runs the eight libraries. It removes earlier refs' caches and worktrees under `target/battery` when it starts, so never run a second one while one is running, even a `BATTERY_MODES` subset.

```sh
bb battery <ref>
bb linux arm64          # with HTTPS_PROXY and friends unset if your shell sets them
```

## Which jolt

`deps.edn` pins a jolt commit under `:champaca/jolt`, and `bb jolt` builds it. `bb test` uses `$JOLT` if it's set, then the pinned build if it exists, then `jolt` from your `PATH`, and its first line says which. [performance](performance.md#the-pinned-jolt) says why there's a pin and what the minimum version needs.

## Code health

`bb metrics` runs jolt-metrics over the source and prints the functions most worth splitting, the files that are both complex and often changed, and cleanup candidates. It's a report only, and nothing fails on it. `bb metrics:json` and `bb metrics:edn` give the same data for tools, and `bb metrics:html` makes one page to share.
