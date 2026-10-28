# Upstream's `aaa` programs

These are the programs of OpenLDK's `testsuite/aaa`, by Anthony Green and
OpenLDK's contributors, copied from upstream commit `a962741`
(github.com/atgreen/OpenLDK). OpenLDK is GPLv3 with the Classpath Exception,
the same license as champaca (`COPYING` and `COPYING.CLASSPATH-EXCEPTION`
at the top of this repo).

Each one checks one behavior that a Java runtime got wrong at some point:
exception regions, `dup_x2`, lost wake-ups, `mmap`, zip streams,
`HttpServer` and so on. Most print `OK`. `bb test` runs every program that
has a `.expected` file next to it, and the program passes when it exits 0
and its output equals that file byte for byte. Each `.expected` was
recorded from `java` 25.0.2 on macOS, in the same layout, and the Linux
container's JVM prints the same.

The layout follows upstream's `run.exp`. All the programs compile together
into one `aaa` package under `target/aaa-classes`. Each one then runs from a
work dir of its own under `target/aaa-work/<name>/`, which holds a fresh copy
of that package (some programs delete their own class files), the program's
own source in `aaa/` (`filestat` looks for it) and a small `test.jar`
(`zipfile` opens it). The arguments are `1 2 3 4 5`, as `run.exp` gives.

The sources are upstream's, except:

- `netif.java` looks for the loopback interface as `lo0` on macOS. Upstream
  names Linux's `lo`.
- `fileops.java` doesn't expect a read-only file to be unwritable when it
  runs as root, as in `bb linux`'s container, since root may write it and
  the JVM's `canWrite` says so.
- `ni.java` and `sp.java` aren't here. `ni` prints the date and `sp` the
  machine's own properties, so neither can have a fixed `.expected`.
  `test/java/VmCheck.java` covers the properties.

Three programs have no `.expected` yet because champaca fails them.
Each gets one once champaca passes it:

- `childfirst` and `siblingloaders`: two class loaders each defining a
  class of the same name, which champaca's single class table refuses.
- `jsr292`: `MethodHandle.rebind` is abstract here.
