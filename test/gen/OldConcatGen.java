import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes OldConcat.class into the directory given as the argument: a string
// concatenation whose invokedynamic takes an int[], an empty ArrayList and a
// null as raw Object operands, which is what javac 9 to 18 emit (19 and later
// call String.valueOf first, so no class javac 25 compiles has this shape;
// Spring's jars, built with JDK 17, do). Run with `java OldConcatGen.java <dir>`.
public class OldConcatGen {
    static final ClassDesc PRINT_STREAM = ClassDesc.of("java.io.PrintStream");
    static final ClassDesc SYSTEM = ClassDesc.of("java.lang.System");
    static final ClassDesc ARRAY_LIST = ClassDesc.of("java.util.ArrayList");

    public static void main(String[] args) throws Exception {
        DirectMethodHandleDesc bsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.StringConcatFactory"), "makeConcatWithConstants",
            MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_String, CD_Object.arrayType()));
        byte[] bytes = ClassFile.of().build(ClassDesc.of("OldConcat"), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody("main", MethodTypeDesc.of(CD_void, CD_String.arrayType()), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.iconst_2();
                cb.newarray(TypeKind.INT);
                cb.new_(ARRAY_LIST);
                cb.dup();
                cb.invokespecial(ARRAY_LIST, "<init>", MethodTypeDesc.of(CD_void));
                cb.aconst_null();
                cb.invokedynamic(DynamicCallSiteDesc.of(bsm, "makeConcatWithConstants",
                    MethodTypeDesc.of(CD_String, CD_Object, CD_Object, CD_Object), "arr <\u0001> list <\u0001> null <\u0001>"));
                // the array's identity hash varies: printed as @hash
                cb.ldc("@[0-9a-f]+");
                cb.ldc("@hash");
                cb.invokevirtual(CD_String, "replaceAll", MethodTypeDesc.of(CD_String, CD_String, CD_String));
                cb.getstatic(SYSTEM, "out", PRINT_STREAM);
                cb.swap();
                cb.invokevirtual(PRINT_STREAM, "println", MethodTypeDesc.of(CD_void, CD_String));
                cb.return_();
            });
        });
        Files.write(Path.of(args[0], "OldConcat.class"), bytes);
    }
}
