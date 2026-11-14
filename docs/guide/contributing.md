# Contributing

A change has to behave as HotSpot does, and you show that with a test whose output is compared with the JVM's. This page covers how a change gets in. [development](development.md) has the commands.

## Set up

You need jolt 0.8.26 or later and JDK 25 with `JAVA_HOME` set. jolt runs the tasks in `bb.edn`, and they need 0.8.26's `System/mapLibraryName`, though champaca itself runs on 0.8.24 or later. Then:

```sh
jolt jolt      # build the jolt commit deps.edn pins (about 45 s, once per pin)
jolt test      # every test program against the JVM, plus the examples
```

`jolt test` has to pass on your machine before anything else. [getting-started](getting-started.md#running-the-tests) says what it runs.

## Making a change

**Start with a test.** A behavior change gets a Java program in `test/java/`, with its output recorded from the JVM into a `.expected` file of the same name (`java -cp target/classes YourCheck > test/java/YourCheck.expected`, on JDK 25). `jolt test` diffs champaca's output against it byte for byte. For a class `javac` won't write, such as one with a broken attribute or an unusual bytecode shape, a program in `test/gen/` generates it with the ClassFile API. A change to the jolt API gets an `examples/*.clj` test instead, asserting what each call returns.

**Check that the test can fail.** Remove the new behavior, rerun, and watch the test go red. A test that passes either way checks nothing. Output tests miss more than you'd expect, because a fast path and its slow fallback print the same thing.

**Keep a refactor behavior-neutral, and show it.** For a change meant to alter nothing, compare the old function with the new one on real inputs, such as every class file of `java.base` or every method a set of programs compiles, and give the number of cases you compared in the pull request.

**Mind the hot paths.** Generated code, the natives and the call paths between jolt and Java are timed. If you touch one, measure before and after and alternate the order of the runs, since the second loop in a process is often the slower one whichever version runs in it.

## Before a pull request

- `jolt test` passes.
- The battery passes on your final commit: `jolt battery <ref>` runs the suite cold, warm, with the cache off and with `CHAMPACA_BT=1`, then the twenty-three libraries. It takes half an hour or more, and only one may run at a time.
- If you can run Docker, `jolt linux` passes too.
- `jolt metrics:gate` passes: no critical jolt-metrics finding anywhere, and no unused code left behind ([development](development.md)).
- CI (`.github/workflows/ci.yml`) runs `jolt battery` on Linux x86_64, Linux arm64 and macOS arm64 for every push to a branch, and a pull request shows the checks of its branch's commit. Each push runs the fast lane: cold, warm and the libraries on each platform, though arm64 skips warm, plus `CHAMPACA_BT=1` on x86_64. A push that changes only `docs/` or Markdown runs no battery jobs. The full lane adds every mode everywhere, cache-off included, and runs every night on main. The `ci ok` check sums up a run. It passes when every battery job passed or there was nothing to run, which makes it the one check a rule on main would require. Either lane, or any part of one, runs by hand:

  ```sh
  gh workflow run ci -r my-branch -f full=true                          # the full lane
  gh workflow run ci -r my-branch -f modes=cache-off -f platforms=amd64
  gh workflow run ci -r my-branch -f modes=cold,warm -f platforms=macos -f repeat=10
  ```

  `modes` takes groups separated by spaces, one job each (`-f modes="cold,warm bt"`), and a group without `cold` starts on an empty cache, so it runs cold anyway. `repeat` (up to 20) runs each job that many times, which is how you catch a rare hang. A failed job names its failing programs in annotations and keeps its logs, with any hang's sample, as an artifact.
- Docs ship with the change. Put a new behavior in the guide page that covers its area, and a new gap in [limitations](limitations.md).
- The pull request says what changed and how it was checked: which tests, which comparison, which numbers.
- Commit messages and the pull request description carry no AI assistant attribution (a `Co-Authored-By: Claude` line, a session link, a "Generated with" footer). CI checks with `tools/attributioncheck.sh`, which you can run yourself: `sh tools/attributioncheck.sh origin/main..HEAD`.

Writing a change with an LLM's help is fine. You own what you submit either way, so it goes through the same review, tests and gates as any other change. [AI_POLICY.md](../../AI_POLICY.md) states the project's stance in full.

## Source files

Every source file, task file and script starts with the license header the others carry:

```
;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
```

Don't port OpenJDK code into champaca. That covers HotSpot's C++, the native libraries, and a JDK Java class rewritten as Scheme. Most of it is GPL version 2 only, and the Classpath exception lets a program link with it but gives no right to relicense it as GPL version 3. Write the code from what the JVM does instead, matching the JDK's behavior and its messages, which is what the tests check anyway. Champaca runs the JDK's own classes unchanged, and that's the use the Classpath exception is there for.

The Scheme runtime files share one top-level environment with jolt, so a new top-level name in a `.ss` file must not be one jolt already binds or another `.ss` file defines.

## A bug in jolt

When a champaca problem turns out to be jolt's, fix it in jolt through an issue and a pull request at [jolt-lang/jolt](https://github.com/jolt-lang/jolt) instead of working around it here, and note the jolt issue in your champaca pull request.
