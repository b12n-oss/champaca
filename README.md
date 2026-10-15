# openldk-jolt

> [!WARNING]
> **Throwaway spike.** This checks whether [OpenLDK](https://github.com/atgreen/openldk) can be rebuilt in [jolt](https://github.com/jolt-lang/jolt). It is not a usable Java runtime.

OpenLDK is Anthony Green's Java runtime written in Common Lisp. It translates JVM bytecode into Lisp and lets SBCL compile it to machine code. This spike tries the same approach on jolt. The translator is written in jolt's Clojure, it emits Chez Scheme source, and `jolt.scheme/eval-string` hands that to Chez's native compiler, one method at a time on first call.

## Run it

You need jolt (0.8.21 or later) and a JDK for `javac`.

```sh
bb test                 # compile test/java/*.java, run each through the JIT, diff against the JVM's output
javac -d target/classes test/java/Calc.java
jolt -M -m openldk-jolt.main target/classes Calc
OPENLDK_TRACE=1 jolt -M -m openldk-jolt.main target/classes Calc   # also writes target/jit/trace.ss
```

## What's here

| File | Job |
|---|---|
| `src/openldk_jolt/classfile.clj` | parses `.class` files: constant pool, fields, methods, `Code`, `BootstrapMethods` |
| `src/openldk_jolt/codegen.clj` | turns one method's bytecode into a Scheme `define`, with basic blocks as `letrec` thunks |
| `src/openldk_jolt/main.clj` | class loading, field layout, vtables, the compile-on-first-call hook, the CLI |
| `src/openldk_jolt/shims.clj` | metadata for the handful of JDK classes faked by hand |
| `resources/openldk_jolt/runtime.ss` | the Scheme side: 32/64-bit wraparound, objects, arrays, exceptions, string concat, `println` |

The tests cover static int and long math with overflow, loops, recursion, objects with inherited fields, virtual and interface calls, `invokedynamic` string concatenation, and `try`/`catch` for both user exceptions and runtime ones like divide by zero.

## What it doesn't do

It never loads the real JDK. `java.lang.String` is a Chez string, and `Object`, `Throwable`, `System`, `PrintStream` and a few exception classes are shims. There are no threads or monitors, no reflection, no `long`/`double` stack shuffles (`dup2`, `pop2`), no float-to-int conversions and no `wide`. Anything outside that raises a clear "not in spike" error.

## License

GPLv3 with the Classpath Exception, inherited from OpenLDK. See `COPYING` and `COPYING.CLASSPATH-EXCEPTION`.
