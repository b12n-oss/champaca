import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes CondyLabels into the directory given as the argument: a static
// index(Object) running SwitchBootstraps.typeSwitch whose labels are dynamic
// constants of ConstantBootstraps.invoke (Integer.valueOf(42),
// String.valueOf(7)) next to a plain Number.class. javac only makes a dynamic
// label for a primitive pattern or a qualified enum constant, but the
// bootstrap takes any constant that yields a Class, String, Integer or
// EnumDesc, and other compilers and class file generators use that. Run with
// `java CondyLabelGen.java <dir>`.
public class CondyLabelGen {
    public static void main(String[] args) throws Exception {
        DirectMethodHandleDesc typeSwitch = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("java.lang.runtime.SwitchBootstraps"), "typeSwitch",
            MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_Object.arrayType()));
        DynamicConstantDesc<Integer> fortyTwo = DynamicConstantDesc.ofNamed(BSM_INVOKE, DEFAULT_NAME, CD_Integer,
            MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int)), 42);
        DynamicConstantDesc<String> seven = DynamicConstantDesc.ofNamed(BSM_INVOKE, DEFAULT_NAME, CD_String,
            MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_int)), 7);
        DynamicCallSiteDesc site = DynamicCallSiteDesc.of(typeSwitch, "typeSwitch", MethodTypeDesc.of(CD_int, CD_Object, CD_int),
                                                          fortyTwo, seven, CD_Number);
        byte[] bytes = ClassFile.of().build(ClassDesc.of("CondyLabels"), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody("index", MethodTypeDesc.of(CD_int, CD_Object), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.aload(0);
                cb.iconst_0();
                cb.invokedynamic(site);
                cb.ireturn();
            });
        });
        Files.write(Path.of(args[0], "CondyLabels.class"), bytes);
    }
}
