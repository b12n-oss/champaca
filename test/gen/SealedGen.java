import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes sealed classes and the subclasses a JVM refuses, which javac won't
// compile, into the directory given as the argument, for SealedCheck: a
// subclass a sealed class or interface doesn't list, one of a sealed class
// listing none, a listed non-public one in another package, a class path
// class implementing java.base's sealed ConstantDesc, a final class with a
// PermittedSubclasses attribute, a class both unlisted and naming String as
// an interface; and the classes a loader of the program's
// own defines from sgbytes/<name>.bin (each in an unnamed module of its
// own). Run with `java SealedGen.java <dir>`.
public class SealedGen {
    static void init(ClassBuilder b, ClassDesc sup) {
        b.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, c -> { c.aload(0); c.invokespecial(sup, INIT_NAME, MTD_void); c.return_(); });
    }
    static void write(Path dir, String name, Consumer<ClassBuilder> body) throws Exception {
        Path p = dir.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, ClassFile.of().build(ClassDesc.of(name), body::accept));
    }
    static void bytes(Path dir, String name, Consumer<ClassBuilder> body) throws Exception {
        Files.write(dir.resolve("sgbytes").resolve(name + ".bin"), ClassFile.of().build(ClassDesc.of(name), body::accept));
    }
    // on the class path as name, and as name + "B" for defineClass from bytes
    static void both(Path dir, String name, BiConsumer<String, ClassBuilder> body) throws Exception {
        write(dir, name, b -> body.accept(name, b));
        bytes(dir, name + "B", b -> body.accept(name + "B", b));
    }
    static PermittedSubclassesAttribute permits(String... names) {
        return PermittedSubclassesAttribute.ofSymbols(java.util.Arrays.stream(names).map(ClassDesc::of).toArray(ClassDesc[]::new));
    }
    static Consumer<ClassBuilder> sealed(String... names) {
        return b -> { b.withFlags(ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT); init(b, CD_Object); b.with(permits(names)); };
    }
    static Consumer<ClassBuilder> sub(int flags, String sup) {
        return b -> { b.withFlags(flags | ACC_SUPER); b.withSuperclass(ClassDesc.of(sup)); init(b, ClassDesc.of(sup)); };
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir.resolve("sgbytes"));
        ClassDesc S = ClassDesc.of("SgSealed"), I = ClassDesc.of("SgSealedI");
        // lists SgA, a class that's nowhere, one that isn't its subclass, one in another package
        write(dir, "SgSealed", sealed("SgA", "SgNowhere", "java.lang.String", "sgp.SgOther"));
        write(dir, "SgSealedI", b -> { b.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT); b.with(permits("SgA", "SgSubI")); });
        write(dir, "SgEmpty", b -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); init(b, CD_Object); b.with(permits()); });
        write(dir, "SgA", b -> { b.withFlags(ACC_PUBLIC | ACC_SUPER | ACC_FINAL); b.withSuperclass(S); b.withInterfaceSymbols(I); init(b, S); });
        write(dir, "SgSubI", b -> { b.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT); b.withInterfaceSymbols(I); });
        write(dir, "sgp.SgOther", sub(ACC_PUBLIC, "SgSealed"));
        both(dir, "SgNotListed", (n, b) -> sub(ACC_PUBLIC, "SgSealed").accept(b));
        both(dir, "SgNotListedI", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withInterfaceSymbols(I); init(b, CD_Object); });
        both(dir, "SgNotListedJ", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT); b.withInterfaceSymbols(I); });
        both(dir, "SgFromEmpty", (n, b) -> sub(ACC_PUBLIC, "SgEmpty").accept(b));
        // listed and non-public: in another package refused, in the same one defined
        write(dir, "SgSealed4", sealed("sgp.q.SgPkgOther", "SgPkgSame"));
        write(dir, "sgp.q.SgPkgOther", sub(0, "SgSealed4"));
        write(dir, "SgPkgSame", sub(0, "SgSealed4"));
        // refused twice: by SgEmpty, and for naming a class as an interface, which HotSpot finds first
        write(dir, "SgOrder", b -> { sub(ACC_PUBLIC, "SgEmpty").accept(b); b.withInterfaceSymbols(CD_String); });
        write(dir, "SgJdk", b -> { b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withInterfaceSymbols(ClassDesc.of("java.lang.constant.ConstantDesc")); init(b, CD_Object); });
        both(dir, "SgFinalSealed", (n, b) -> { b.withFlags(ACC_PUBLIC | ACC_SUPER | ACC_FINAL); init(b, CD_Object); b.with(permits("SgA")); });
        // listed, but defined from bytes: another loader, so another module
        write(dir, "SgSealed2", sealed("SgOkB"));
        bytes(dir, "SgOkB", sub(ACC_PUBLIC, "SgSealed2"));
        // a hidden class extending a sealed class that lists its name
        write(dir, "SgSealed3", sealed("SgHidden"));
        bytes(dir, "SgHidden", sub(ACC_PUBLIC, "SgSealed3"));
        // one loader defines all three: a listed non-public subclass in
        // another package (refused), a listed public one (defined, and in the
        // list); twice, for an unnamed loader and a named one (champaca has
        // one class table, so a name is defined once)
        for (String k : new String[] {"5", "7"}) {
            String s = "SgSealed" + k + "B", other = "sgr.s.SgSub" + k + "B", ok = "SgOk" + k + "B";
            bytes(dir, s, sealed(other, ok, "SgNowhereB"));
            bytes(dir, other, sub(0, s));
            bytes(dir, ok, sub(ACC_PUBLIC | ACC_FINAL, s));
        }
    }
}
