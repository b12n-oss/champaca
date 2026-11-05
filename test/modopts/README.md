# test/modopts

The launcher's module options, case by case: `--add-opens`, `--add-exports`,
`--add-reads`, `--limit-modules`, `--illegal-native-access` and
`--add-modules` with them. Each `<case>.args` holds the launcher arguments,
one a line, and bb test runs them through `champaca.main` from
`target/modopts`, where it compiles this directory's probes. `<case>.expected`
holds what HotSpot's `java` gives with the same arguments, from the same
compiled probes: a line `exit <status>`, then stdout, then a line
`--- stderr`, then stderr. `record.sh` records them all, or the cases named:

```sh
JAVA_HOME=<jdk 25> test/modopts/record.sh [case...]
```

The probes:

- `Opt` prints the VM's input arguments, then what `--add-opens`,
  `--add-exports` and `--add-reads` change for the unnamed module.
- `Lim` asks the application, boot and platform loaders for JDK classes, as
  `--limit-modules` and the default boot layer leave them. Its names are all
  strings: a class named in bytecode resolves whatever the boot layer holds
  on champaca, where HotSpot fails.
- `Inc` shows the default boot layer and that `jdk.incubator.vector`, outside
  it, has no classes without `--add-modules`.
- `Nat` makes a restricted call under `--illegal-native-access`.

Two shapes stay out because HotSpot's own output isn't fixed:

- A `--limit-modules` that leaves out a module a root requires names whichever
  missing module the resolver meets first, and that order changes from run
  to run unless class data sharing fixes it.
- The default `--illegal-native-access=warn` warning prints the class path's
  URL.

A lone `--add-opens=` is left out too, because HotSpot accepts it only when it
boots from its CDS archive (docs/guide/limitations.md).
