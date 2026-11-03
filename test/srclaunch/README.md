# test/srclaunch

Source-file mode, `java Hello.java`, case by case. Each `<case>.args` holds
the launcher arguments, one a line, and bb test runs them through
`champaca.main` from this directory, so paths stay relative. `<case>.expected`
holds what HotSpot's `java` gives with the same arguments: a line
`exit <status>`, then stdout, then a line `--- stderr`, then stderr.
`record.sh` records them all, or the cases named:

```sh
JAVA=<jdk 25>/bin/java test/srclaunch/record.sh [case...]
```

`args-cp` and `uses-lib` need `target/lib.jar`, which bb test builds first.
`script` is a source file whose name doesn't end `.java`, run with `--source`.
