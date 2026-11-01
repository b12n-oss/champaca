import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes CondyUser.class into the directory given, for CondyCheck: methods
// that each ldc one constant-pool entry (dynamic constants, a method handle,
// a method type, method handles to members that aren't there), one method
// that ldcs the same entry twice, a dynamic constant whose static argument
// is another one's entry, one two threads resolve at once, and an invokedynamic whose bootstrap fails once.
// javac shares an entry only within what it compiles, and never emits
// these. Run with `java CondyGen.java <dir>`.
public class CondyGen {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        ClassDesc boot = ClassDesc.of("CondyCheck$Boot");
        MethodTypeDesc bsmType = MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class);
        java.util.function.Function<String, DynamicConstantDesc<Object>> condy = m ->
            DynamicConstantDesc.ofNamed(MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, m, bsmType), m, CD_Object);
        DynamicConstantDesc<Object> ok = condy.apply("make");
        DynamicConstantDesc<Object> outer = DynamicConstantDesc.ofNamed(
            MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, "wrap",
                                      MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class, CD_Object)),
            "outer", CD_Object, ok);
        DirectMethodHandleDesc ibsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, "indyFailOnce",
            MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.CallSite"), CD_MethodHandles_Lookup, CD_String, CD_MethodType));
        Object[][] ldcs = {
            {"ok1", ok}, {"ok2", ok}, {"outer", outer},
            {"race", condy.apply("race")},
            {"nul1", condy.apply("nothing")}, {"nul2", condy.apply("nothing")},
            {"fail1", condy.apply("failOnce")}, {"fail2", condy.apply("failOnce")},
            {"err1", condy.apply("errorOnce")}, {"lerr1", condy.apply("linkageOnce")},
            {"mh1", MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, "make", bsmType)},
            {"mh2", MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, "make", bsmType)},
            {"mt1", MethodTypeDesc.of(CD_int, CD_String)}, {"mt2", MethodTypeDesc.of(CD_int, CD_String)},
            {"missm", MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, "nowhere", MethodTypeDesc.of(CD_void))},
            {"missv", MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.VIRTUAL, boot, "novirt", MethodTypeDesc.of(CD_String, CD_int.arrayType()))},
            {"missf", MethodHandleDesc.ofField(DirectMethodHandleDesc.Kind.STATIC_GETTER, boot, "nofield", CD_String.arrayType())},
            {"missc", MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("CondyNowhere"), "x", MethodTypeDesc.of(CD_void))},
        };
        Files.write(dir.resolve("CondyUser.class"), ClassFile.of().build(ClassDesc.of("CondyUser"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            for (Object[] m : ldcs)
                b.withMethodBody((String) m[0], MethodTypeDesc.of(CD_Object), ACC_PUBLIC | ACC_STATIC, cb -> { cb.ldc((ConstantDesc) m[1]); cb.areturn(); });
            // one method, two ldc of one entry
            b.withMethodBody("pair", MethodTypeDesc.of(CD_boolean), ACC_PUBLIC | ACC_STATIC, cb -> {
                Label no = cb.newLabel();
                cb.ldc(ok); cb.ldc(ok); cb.if_acmpne(no); cb.iconst_1(); cb.ireturn(); cb.labelBinding(no); cb.iconst_0(); cb.ireturn(); });
            b.withMethodBody("indy", MethodTypeDesc.of(CD_Object), ACC_PUBLIC | ACC_STATIC, cb -> {
                cb.invokedynamic(DynamicCallSiteDesc.of(ibsm, "go", MethodTypeDesc.of(CD_Object))); cb.areturn(); });
        }));
    }
}
