import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.function.BiConsumer;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes class files a JVM refuses to define, which javac won't compile,
// into the directory given as the argument, for ClassDefCheck: a final
// superclass, an interface as superclass, a class among the interfaces, an
// override of a final method, an interface whose superclass isn't Object, a
// class extending itself; and two it accepts: a private final method
// "overridden", a static method beside a superclass's final instance one.
// Each also as <name>B in cdbytes/<name>B.bin, for defineClass from bytes
// (champaca has one class table, so the class path's copy can't be defined
// again). Run with `java ClassDefGen.java <dir>`.
public class ClassDefGen {
    static final ClassDesc PARENT = ClassDesc.of("CdParent");
    static void init(ClassBuilder b, ClassDesc sup) {
        b.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, c -> { c.aload(0); c.invokespecial(sup, INIT_NAME, MTD_void); c.return_(); });
    }
    static void both(Path dir, String name, BiConsumer<String, ClassBuilder> body) throws Exception {
        Files.write(dir.resolve(name + ".class"), ClassFile.of().build(ClassDesc.of(name), b -> body.accept(name, b)));
        String twin = name + "B";
        Files.write(dir.resolve("cdbytes").resolve(twin + ".bin"), ClassFile.of().build(ClassDesc.of(twin), b -> body.accept(twin, b)));
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir.resolve("cdbytes"));
        // the parent: a public final method, a private final one
        Files.write(dir.resolve("CdParent.class"), ClassFile.of().build(PARENT, b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            init(b, CD_Object);
            b.withMethodBody("done", MTD_void, ACC_PUBLIC | ACC_FINAL, c -> c.return_());
            b.withMethodBody("secret", MTD_void, ACC_PRIVATE | ACC_FINAL, c -> c.return_());
            b.withMethodBody("pkgFinal", MTD_void, ACC_FINAL, c -> c.return_());
        }));
        // for a hidden class (its name gains /0x<n>): overrides the package-private final pkgFinal
        Files.write(dir.resolve("cdbytes").resolve("CdHiddenOverride.bin"), ClassFile.of().build(ClassDesc.of("CdHiddenOverride"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(PARENT); init(b, PARENT);
            b.withMethodBody("pkgFinal", MTD_void, ACC_PUBLIC, c -> c.return_());
        }));
        // two classes extending a class that's nowhere, for two loaders
        for (String n : new String[] {"CdMissingSuper1", "CdMissingSuper2"})
            Files.write(dir.resolve("cdbytes").resolve(n + ".bin"), ClassFile.of().build(ClassDesc.of(n), b -> {
                b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(ClassDesc.of("CdNowhere"));
            }));
        both(dir, "CdFinalChild", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(CD_String); });
        both(dir, "CdInterfaceSuper", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(ClassDesc.of("java.lang.Runnable")); });
        both(dir, "CdClassInterface", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withInterfaceSymbols(CD_String); });
        both(dir, "CdFinalOverride", (n, b) -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(PARENT); init(b, PARENT);
            b.withMethodBody("done", MTD_void, ACC_PUBLIC, c -> c.return_());
        });
        both(dir, "CdIfaceWithSuper", (n, b) -> {
            b.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
            b.withSuperclass(ClassDesc.of("java.lang.Thread"));
        });
        both(dir, "CdSelf", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(ClassDesc.of(n)); });
        both(dir, "CdPrivateFinal", (n, b) -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(PARENT); init(b, PARENT);
            b.withMethodBody("secret", MTD_void, ACC_PUBLIC, c -> c.return_());
        });
        // CdUser.make() runs `new CdFinalChild`, which the JVM refuses where it runs
        Files.write(dir.resolve("CdUser.class"), ClassFile.of().build(ClassDesc.of("CdUser"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            init(b, CD_Object);
            ClassDesc fc = ClassDesc.of("CdFinalChild");
            b.withMethodBody("make", MethodTypeDesc.of(CD_Object), ACC_PUBLIC | ACC_STATIC, c -> {
                c.new_(fc); c.dup(); c.invokespecial(fc, INIT_NAME, MTD_void); c.areturn();
            });
        }));
        both(dir, "CdStaticBeside", (n, b) -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(PARENT); init(b, PARENT);
            b.withMethodBody("done", MTD_void, ACC_PUBLIC | ACC_STATIC, c -> c.return_());
        });
    }
}
