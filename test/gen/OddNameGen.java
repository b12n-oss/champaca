import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes the class Odd|Na\me into the directory given as the argument. Its
// own name, a static and an instance field's and three methods' hold '|' or
// '\', which the JVM allows in any name (only . ; [ / and, for methods, < >
// are out) and Scala and Kotlin emit; javac never does. Its static run()
// sets and reads the fields, calls the methods (a static one, an instance
// one through invokevirtual, one that throws) and returns a line saying
// what it saw. OddNameCheck runs it. Run with `java OddNameGen.java <dir>`.
public class OddNameGen {
    public static void main(String[] args) throws Exception {
        ClassDesc me = ClassDesc.ofInternalName("Odd|Na\\me");
        ClassDesc sb = ClassDesc.of("java.lang.StringBuilder");
        MethodTypeDesc append = MethodTypeDesc.of(sb, CD_String);
        MethodTypeDesc appendInt = MethodTypeDesc.of(sb, CD_int);
        byte[] bytes = ClassFile.of().build(me, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withField("s|f", CD_int, ClassFile.ACC_STATIC);
            clb.withField("i\\f", CD_String, 0);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(CD_Object, INIT_NAME, MTD_void);
                cb.return_();
            });
            // static twice|it(int) = 2 * int
            clb.withMethodBody("twice|it", MethodTypeDesc.of(CD_int, CD_int), ClassFile.ACC_STATIC, cb -> {
                cb.iload(0);
                cb.iconst_2();
                cb.imul();
                cb.ireturn();
            });
            // instance tag\it() = "<" + this.i\f + ">"
            clb.withMethodBody("tag\\it", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC, cb -> {
                cb.new_(sb);
                cb.dup();
                cb.ldc("<");
                cb.invokespecial(sb, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String));
                cb.aload(0);
                cb.getfield(me, "i\\f", CD_String);
                cb.invokevirtual(sb, "append", append);
                cb.ldc(">");
                cb.invokevirtual(sb, "append", append);
                cb.invokevirtual(sb, "toString", MethodTypeDesc.of(CD_String));
                cb.areturn();
            });
            // static boom|\it() throws an IllegalStateException
            clb.withMethodBody("boom|\\it", MTD_void, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                ClassDesc ise = ClassDesc.of("java.lang.IllegalStateException");
                cb.new_(ise);
                cb.dup();
                cb.ldc("odd");
                cb.invokespecial(ise, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String));
                cb.athrow();
            });
            // public static String run(): s|f = twice|it(21); o.i\f = "x|y\z";
            // "s|f=" + s|f + " tag=" + o.tag\it()
            clb.withMethodBody("run", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.bipush(21);
                cb.invokestatic(me, "twice|it", MethodTypeDesc.of(CD_int, CD_int));
                cb.putstatic(me, "s|f", CD_int);
                cb.new_(me);
                cb.dup();
                cb.invokespecial(me, INIT_NAME, MTD_void);
                cb.astore(0);
                cb.aload(0);
                cb.ldc("x|y\\z");
                cb.putfield(me, "i\\f", CD_String);
                cb.new_(sb);
                cb.dup();
                cb.ldc("s|f=");
                cb.invokespecial(sb, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String));
                cb.getstatic(me, "s|f", CD_int);
                cb.invokevirtual(sb, "append", appendInt);
                cb.ldc(" tag=");
                cb.invokevirtual(sb, "append", append);
                cb.aload(0);
                cb.invokevirtual(me, "tag\\it", MethodTypeDesc.of(CD_String));
                cb.invokevirtual(sb, "append", append);
                cb.invokevirtual(sb, "toString", MethodTypeDesc.of(CD_String));
                cb.areturn();
            });
        });
        Files.write(Path.of(args[0], "Odd|Na\\me.class"), bytes);
    }
}
