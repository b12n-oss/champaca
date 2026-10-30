import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes IcTarget, IcIface and IcCaller into the directory given as the
// argument. IcCaller makes calls whose kind doesn't match the method they
// resolve to, which javac never emits but a class built against an older
// version of a library can (a method that became static, or stopped being):
// invokevirtual of a static method, invokestatic of an instance method,
// invokeinterface of an interface's static method, invokespecial of a static
// one. A JVM throws IncompatibleClassChangeError when the call runs (JVMS
// 5.4.3.3, 6.5). Each call sits in its own static method taking a boolean,
// so the call runs only when asked. Run with `java IcceGen.java <dir>`.
public class IcceGen {
    static final ClassDesc TARGET = ClassDesc.of("IcTarget");
    static final ClassDesc IFACE = ClassDesc.of("IcIface");
    static final ClassDesc CALLER = ClassDesc.of("IcCaller");
    // a static and a private method in a superinterface, which resolution
    // through a class or a subinterface doesn't see: NoSuchMethodError
    static final ClassDesc BASE = ClassDesc.of("IcBase");
    static final ClassDesc SUB = ClassDesc.of("IcSub");
    static final ClassDesc IMPL = ClassDesc.of("IcImpl");

    // static String <name>(boolean go) { if (!go) return "skipped"; <call>; return "called"; }
    static void guarded(ClassBuilder clb, String name, java.util.function.Consumer<CodeBuilder> call) {
        clb.withMethodBody(name, MethodTypeDesc.of(CD_String, CD_boolean), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
            Label run = cb.newLabel();
            cb.iload(0);
            cb.ifne(run);
            cb.ldc("skipped");
            cb.areturn();
            cb.labelBinding(run);
            call.accept(cb);
            cb.ldc("called");
            cb.areturn();
        });
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.write(dir.resolve("IcTarget.class"), ClassFile.of().build(TARGET, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0); cb.invokespecial(CD_Object, INIT_NAME, MTD_void); cb.return_();
            });
            clb.withMethodBody("st", MethodTypeDesc.of(CD_int, CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.iload(0); cb.ireturn();
            });
            clb.withMethodBody("inst", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC, cb -> {
                cb.ldc("inst"); cb.areturn();
            });
        }));
        Files.write(dir.resolve("IcIface.class"), ClassFile.of().build(IFACE, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withMethodBody("ist", MTD_void, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> cb.return_());
        }));
        Files.write(dir.resolve("IcBase.class"), ClassFile.of().build(BASE, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withMethodBody("bst", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> { cb.iconst_1(); cb.ireturn(); });
            clb.withMethodBody("bp", MethodTypeDesc.of(CD_int), ClassFile.ACC_PRIVATE, cb -> { cb.iconst_2(); cb.ireturn(); });
        }));
        Files.write(dir.resolve("IcSub.class"), ClassFile.of().build(SUB, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withInterfaceSymbols(BASE);
        }));
        Files.write(dir.resolve("IcImpl.class"), ClassFile.of().build(IMPL, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withInterfaceSymbols(SUB);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0); cb.invokespecial(CD_Object, INIT_NAME, MTD_void); cb.return_();
            });
        }));
        // IcCaller extends IcTarget, so its invokespecial of a superclass method verifies
        Files.write(dir.resolve("IcCaller.class"), ClassFile.of().build(CALLER, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(TARGET);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0); cb.invokespecial(TARGET, INIT_NAME, MTD_void); cb.return_();
            });
            // invokevirtual of a static method, on a real receiver
            guarded(clb, "virtualOfStatic", cb -> {
                cb.new_(TARGET); cb.dup(); cb.invokespecial(TARGET, INIT_NAME, MTD_void);
                cb.iconst_1();
                cb.invokevirtual(TARGET, "st", MethodTypeDesc.of(CD_int, CD_int));
                cb.pop();
            });
            // the same on a null receiver: resolution comes first
            guarded(clb, "virtualOfStaticNull", cb -> {
                cb.aconst_null(); cb.checkcast(TARGET);
                cb.iconst_1();
                cb.invokevirtual(TARGET, "st", MethodTypeDesc.of(CD_int, CD_int));
                cb.pop();
            });
            // invokestatic of an instance method
            guarded(clb, "staticOfInstance", cb -> {
                cb.invokestatic(TARGET, "inst", MethodTypeDesc.of(CD_String));
                cb.pop();
            });
            // invokeinterface of an interface's static method
            guarded(clb, "interfaceOfStatic", cb -> {
                cb.aconst_null(); cb.checkcast(IFACE);
                cb.invokeinterface(IFACE, "ist", MTD_void);
            });
            // a superinterface's static method through the class and through a subinterface
            guarded(clb, "virtualOfInheritedStatic", cb -> {
                cb.new_(IMPL); cb.dup(); cb.invokespecial(IMPL, INIT_NAME, MTD_void);
                cb.invokevirtual(IMPL, "bst", MethodTypeDesc.of(CD_int));
                cb.pop();
            });
            guarded(clb, "interfaceOfInheritedStatic", cb -> {
                cb.new_(IMPL); cb.dup(); cb.invokespecial(IMPL, INIT_NAME, MTD_void);
                cb.invokeinterface(SUB, "bst", MethodTypeDesc.of(CD_int));
                cb.pop();
            });
            // and its private method through the class
            guarded(clb, "virtualOfInheritedPrivate", cb -> {
                cb.new_(IMPL); cb.dup(); cb.invokespecial(IMPL, INIT_NAME, MTD_void);
                cb.invokevirtual(IMPL, "bp", MethodTypeDesc.of(CD_int));
                cb.pop();
            });
            // invokespecial of a static method of the superclass's
            guarded(clb, "specialOfStatic", cb -> {
                cb.new_(CALLER); cb.dup(); cb.invokespecial(CALLER, INIT_NAME, MTD_void);
                cb.iconst_1();
                cb.invokespecial(TARGET, "st", MethodTypeDesc.of(CD_int, CD_int));
                cb.pop();
            });
        }));
    }
}
