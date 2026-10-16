import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes IndyCheck.class into the directory given as the argument. javac only
// emits invokedynamic for its own bootstraps (lambdas, string concatenation,
// records, switches), so this class is built with the ClassFile API to call
// IndyBootstraps' methods instead: a plain call site, every kind of static
// argument, a MutableCallSite that changes its target, and ldc of a method
// handle, a method type and a dynamic constant. Run with `java IndyCheckGen.java <dir>`.
public class IndyCheckGen {
    static final ClassDesc BOOTS = ClassDesc.of("IndyBootstraps");
    static final ClassDesc LOOKUP = CD_MethodHandles_Lookup;
    static final ClassDesc PRINT_STREAM = ClassDesc.of("java.io.PrintStream");
    static final ClassDesc SYSTEM = ClassDesc.of("java.lang.System");

    static DirectMethodHandleDesc bsm(String name, ClassDesc... extra) {
        ClassDesc[] params = new ClassDesc[3 + extra.length];
        params[0] = LOOKUP; params[1] = CD_String; params[2] = CD_MethodType;
        System.arraycopy(extra, 0, params, 3, extra.length);
        return MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, BOOTS, name, MethodTypeDesc.of(CD_CallSite, params));
    }

    static void println(CodeBuilder cb, ClassDesc type) {
        cb.getstatic(SYSTEM, "out", PRINT_STREAM);
        cb.swap();
        cb.invokevirtual(PRINT_STREAM, "println", MethodTypeDesc.of(CD_void, type));
    }

    public static void main(String[] args) throws Exception {
        DirectMethodHandleDesc addHandle = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, BOOTS, "add",
            MethodTypeDesc.of(CD_int, CD_int, CD_int));
        DirectMethodHandleDesc answerBsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, BOOTS, "answer",
            MethodTypeDesc.of(CD_int, LOOKUP, CD_String, CD_Class));
        byte[] bytes = ClassFile.of().build(ClassDesc.of("IndyCheck"), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody("tick", MethodTypeDesc.of(CD_String), ClassFile.ACC_STATIC, cb -> {
                cb.invokedynamic(DynamicCallSiteDesc.of(bsm("flip"), "tick", MethodTypeDesc.of(CD_String)));
                cb.areturn();
            });
            clb.withMethodBody("main", MethodTypeDesc.of(CD_void, CD_String.arrayType()),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                // a plain site: IndyBootstraps.add through a ConstantCallSite
                cb.bipush(40);
                cb.iconst_2();
                cb.invokedynamic(DynamicCallSiteDesc.of(bsm("byName"), "add", MethodTypeDesc.of(CD_int, CD_int, CD_int)));
                println(cb, CD_int);
                cb.ldc("quiet");
                cb.invokedynamic(DynamicCallSiteDesc.of(bsm("byName"), "shout", MethodTypeDesc.of(CD_String, CD_String)));
                println(cb, CD_String);
                // a void call site
                cb.ldc("void site");
                cb.invokedynamic(DynamicCallSiteDesc.of(bsm("byName"), "note", MethodTypeDesc.of(CD_void, CD_String)));
                // static arguments of every kind
                cb.ldc("ignored");
                cb.invokedynamic(DynamicCallSiteDesc.of(
                    bsm("describe", CD_String, CD_int, CD_long, CD_float, CD_double, CD_Class, CD_MethodType, CD_MethodHandle),
                    "describe", MethodTypeDesc.of(CD_String, CD_String),
                    "text", 7, 8_000_000_000L, 1.5f, 2.25, CD_Integer,
                    MethodTypeDesc.of(CD_int, CD_long), addHandle));
                println(cb, CD_String);
                // the same mutable site, called three times
                for (int i = 0; i < 3; i++) {
                    cb.invokestatic(ClassDesc.of("IndyCheck"), "tick", MethodTypeDesc.of(CD_String));
                    println(cb, CD_String);
                }
                // ldc of a method handle and a method type
                cb.ldc(addHandle);
                cb.iconst_3();
                cb.iconst_4();
                cb.invokevirtual(CD_MethodHandle, "invokeExact", MethodTypeDesc.of(CD_int, CD_int, CD_int));
                println(cb, CD_int);
                cb.ldc(MethodTypeDesc.of(CD_String, CD_int, CD_Object));
                println(cb, CD_Object);
                // bootstraps that fail: each call is wrapped in a handler that prints the error
                for (String b : new String[] {"boom", "wrongType"}) {
                    cb.trying(tb -> tb.iconst_1()
                                      .invokedynamic(DynamicCallSiteDesc.of(bsm(b), b, MethodTypeDesc.of(CD_String, CD_int)))
                                      .pop(),
                              catches -> catches.catchingAll(hb -> {
                                  hb.invokevirtual(CD_Object, "getClass", MethodTypeDesc.of(CD_Class));
                                  hb.invokevirtual(CD_Class, "getName", MethodTypeDesc.of(CD_String));
                                  println(hb, CD_String);
                              }));
                }
                // ldc of a dynamic constant
                cb.ldc(DynamicConstantDesc.ofNamed(answerBsm, "answer", CD_int));
                println(cb, CD_int);
                cb.return_();
            });
        });
        Files.write(Path.of(args[0], "IndyCheck.class"), bytes);
    }
}
