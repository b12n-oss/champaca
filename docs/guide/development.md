# Working on champaca

The tasks below are in `bb.edn`, and `jolt tasks` lists them with a line each. [getting-started](getting-started.md) has what you need installed and what `jolt test` checks, and [debugging](debugging.md) explains the `CHAMPACA_*` switches.

## Tasks and switches

```sh
jolt jolt                                               # build the pinned jolt (about 45 s, once per pin)
jolt test                                               # every test/java and test/aaa program vs the JVM, plus the examples/*.clj tests
                                                      # (a failing program's output goes to target/fails/, and the start of its diff is printed)
JOLT=jolt jolt test                                     # the same suite on the jolt from your PATH
jolt libs:fetch && jolt libs                              # the twenty-three real libraries and four JVM languages vs the JVM (jars into ~/.m2)
jolt battery [ref]                                      # before a merge: jolt test cold, warm, cache off and CHAMPACA_BT=1, then jolt libs (after jolt libs:fetch), on a worktree of HEAD
                                                      # with its own cache (kept until the next run of another ref), logs under target/battery/; about 30 min, more under load; BATTERY_MODES=cold,libs for a subset
jolt linux                                              # jolt test and jolt libs on Linux, in Docker (jolt linux amd64 for x86_64)
                                                      # (CHAMPACA_LINUX_HOME=dir keeps the container's home there instead of a volume)
                                                      # (CHAMPACA_LINUX_RUN='BATTERY_MODES=bt jolt battery' runs that instead of jolt test and jolt libs; CI runs the battery's modes this way)
jolt examples                                           # list the examples/*.clj test files
jolt example interop                                    # run one file's tests (builds target/lib.jar first if it needs to)
jolt heap target/classes Hello                          # where the memory goes: the live heap by type after Hello (roots or java for the other views)
jolt prof target/classes Streams                        # where the time goes: the hottest Java frames of a run, sampled (PROF_US=500 for a finer interval)
JOLT=~/Downloads/jolt jolt ffi:release                 # the foreign calls an unpatched Chez misplaces, made on a jolt release binary (ForeignCheck, strict)
jolt warm                                               # a training run for the startup cache of the jolt on PATH (JOLT=... for another): compiles the JDK paths programs reach early
jolt nrepl                                              # an nREPL server for your editor, on a free port written to .nrepl-port
jolt metrics                                            # jolt-metrics: refactoring queue, hotspots, cleanup candidates (report only; jolt metrics:edn or metrics:json for data)
jolt metrics:html                                       # the same as one page to share, target/metrics.html
jolt metrics:gate                                       # what CI fails on: criticals outside resources/, unused code not in ci/metrics-allow.edn
jolt site:serve                                         # build the docs site and serve it on the first free port from 3000 (jolt site:serve 4000 refuses a busy port); needs a docs-engine checkout
jolt site:build                                         # build the docs site into _site/ (jolt site:clean deletes it)
jolt bundle -o out/app -cp CP -m Main --train          # a directory that runs Main with no JDK installed (--jolt NS --project DIR for a jolt program)
jolt -M -m champaca.main target/classes Streams   # after jolt test has compiled the classes
jolt -M -m champaca.main -p mods -m app/app.Main  # a modular application, as java -p mods -m app/app.Main
CHAMPACA_BT=1 ...       # keep a shadow stack, so an error inside champaca itself prints Java frames, and name each missing native as it's hit
                        # (and say why an NPE got no message)
CHAMPACA_STATS=1 ...    # print classes loaded (and how many from the library cache), methods compiled, cached units loaded, loader-defined classes the cache missed
CHAMPACA_LINES=all ...  # line numbers in stack traces for jar and loader-defined classes too, at about the memory their code takes
CHAMPACA_PROF=1 ...     # time spent per phase (parse, register, codegen, compile, cache), after boot and when main returns
CHAMPACA_CALLER_DEBUG=1 ...  # print the frames each Reflection.getCallerClass call sees
CHAMPACA_NPE_STRICT=1 ...    # throw when an NPE message can't be built, instead of a null message (jolt test sets it)
CHAMPACA_TRACE=1 ...    # log each compiled method, and write the generated Scheme to target/jit/trace.ss
CHAMPACA_CALL_CACHE=off ...  # choose the overload again on every invoke, invoke-static and construct
```

## Before a merge

A change merges once `jolt test`, the battery and the Linux run all pass. The battery runs the whole suite four ways on a worktree of the ref: cold, warm, with the cache off and with `CHAMPACA_BT=1`. Then it runs the twenty-three libraries. It deletes every battery worktree and cache under `target/battery` when it starts, so each run starts cold, and a second battery started while one runs (even a `BATTERY_MODES` subset) pulls the first one's tree out from under it. `jolt linux` needs Docker: it builds the `ci/linux` image, fetches the library jars into `~/.m2` on this machine (`jolt libs:fetch`, which needs the network), then runs `jolt jolt`, `jolt test` and `jolt libs` in a container that mounts them read-only.

```sh
jolt battery <ref>
jolt linux arm64          # or amd64
```

## Which jolt

`deps.edn` pins a jolt commit under `:champaca/jolt`, and `jolt jolt` builds it. `jolt test` uses `$JOLT` if it's set, then the pinned build if it exists, then `jolt` from your `PATH`, and its first line says which. [performance](performance.md#the-pinned-jolt) says why there's a pin and what the minimum version needs.

## Code health

`jolt metrics` runs jolt-metrics over the source and prints the functions most worth splitting, the files that are both complex and often changed, and cleanup candidates. The report itself fails nothing. `jolt metrics:json` and `jolt metrics:edn` give the same data for tools, and `jolt metrics:html` makes one page to share.

`jolt metrics:gate` is the check CI runs on every push, and `ci ok` waits for it. It fails on a critical finding (a function past a complexity, length, nesting or parameter limit, or a file past the length limit) under the paths `ci/metrics-allow.edn` lists as `:critical-free`, which is everything but `resources/` for now. There's no list of accepted criticals, so a function that crosses a limit gets split. It also fails on an unused require, private function or public function, unless the allowlist names it with the reason it stays, and on an allowlist entry that's no longer reported. `resources/` joins the critical-free paths once its seven remaining criticals are gone. They're hot paths and fixed native signatures, kept on purpose so far. The gate follows the one jolt's CI added in [jolt#1640](https://github.com/jolt-lang/jolt/pull/1640), at the same jolt-metrics commit.
