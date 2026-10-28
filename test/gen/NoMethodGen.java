import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes NmTarget and NmCaller into the directory given as the argument.
// NmCaller calls methods NmTarget doesn't have, which javac refuses to
// compile: a static one, and a superclass one through invokespecial (as
// Spring's CGLIB subclasses call setBeanFactory on a class without it). A
// JVM resolves such a call when it runs, so the methods holding them run
// fine until then. Run with `java NoMethodGen.java <dir>`.
public class NoMethodGen {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        ClassDesc target = ClassDesc.of("NmTarget");
        ClassDesc caller = ClassDesc.of("NmCaller");
        Files.write(dir.resolve("NmTarget.class"), ClassFile.of().build(target, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(CD_Object, INIT_NAME, MTD_void);
                cb.return_();
            });
        }));
        MethodTypeDesc gone = MethodTypeDesc.of(CD_Object, CD_int, CD_String, CD_long.arrayType());
        MethodTypeDesc set = MethodTypeDesc.of(CD_void, CD_Object);
        Files.write(dir.resolve("NmCaller.class"), ClassFile.of().build(caller, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(target);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(target, INIT_NAME, MTD_void);
                cb.return_();
            });
            // static String callGone(boolean go): go ? NmTarget.gone(1, "a", null) : "not called"
            clb.withMethodBody("callGone", MethodTypeDesc.of(CD_String, CD_boolean), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                Label call = cb.newLabel();
                cb.iload(0);
                cb.ifne(call);
                cb.loadConstant("not called");
                cb.areturn();
                cb.labelBinding(call);
                cb.iconst_1();
                cb.loadConstant("a");
                cb.aconst_null();
                cb.invokestatic(target, "gone", gone);
                cb.invokevirtual(CD_Object, "toString", MethodTypeDesc.of(CD_String));
                cb.areturn();
            });
            // String callSuper(boolean go): go ? super.setThing(this) : "not called"
            clb.withMethodBody("callSuper", MethodTypeDesc.of(CD_String, CD_boolean), ClassFile.ACC_PUBLIC, cb -> {
                Label call = cb.newLabel();
                cb.iload(1);
                cb.ifne(call);
                cb.loadConstant("not called");
                cb.areturn();
                cb.labelBinding(call);
                cb.aload(0);
                cb.aload(0);
                cb.invokespecial(target, "setThing", set);
                cb.loadConstant("called");
                cb.areturn();
            });
        }));
    }
}
