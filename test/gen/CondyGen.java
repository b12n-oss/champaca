import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes CondyUser.class into the directory given, for CondyCheck: methods
// that each ldc one constant-pool entry (dynamic constants, a method handle,
// a method type, method handles to members that aren't there), one method
// that ldcs the same entry twice, a dynamic constant whose static argument
// is another one's entry, ones two threads resolve at once, one failing
// with no message, and invokedynamics whose bootstraps fail once, return no
// CallSite, null, or a target of the wrong type, and ConstantBootstraps.
// getStaticFinal on a primitive constant, a field that isn't final and one
// that isn't there. javac shares an entry only within what it compiles, and never emits
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
        ClassDesc callSite = ClassDesc.of("java.lang.invoke.CallSite");
        java.util.function.BiFunction<String, ClassDesc, DynamicCallSiteDesc> indy = (m, ret) -> DynamicCallSiteDesc.of(
            MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, boot, m, MethodTypeDesc.of(ret, CD_MethodHandles_Lookup, CD_String, CD_MethodType)),
            "go", MethodTypeDesc.of(CD_Object));
        Object[][] ldcs = {
            {"ok1", ok}, {"ok2", ok}, {"outer", outer},
            {"race", condy.apply("race")},
            {"raceFail", condy.apply("raceFail")}, {"nullMessage", condy.apply("nullMessage")},
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
            // getStaticFinal: the int is boxed for the method's Object result
            Object[][] statics = {
                {"sfInt", DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "MAX_VALUE", CD_int, CD_Integer), CD_int},
                {"sfNonFinal", DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "made", CD_int, boot), CD_int},
                {"sfMissing", DynamicConstantDesc.ofNamed(BSM_GET_STATIC_FINAL, "nope", CD_int, boot), CD_int},
                // the three-argument form: the declaring class is the type's own
                {"sfBool", DynamicConstantDesc.ofNamed(MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, CD_ConstantBootstraps,
                    "getStaticFinal", MethodTypeDesc.of(CD_Object, CD_MethodHandles_Lookup, CD_String, CD_Class)), "TRUE", CD_Boolean), CD_Boolean}};
            for (Object[] m : statics)
                b.withMethodBody((String) m[0], MethodTypeDesc.of(CD_Object), ACC_PUBLIC | ACC_STATIC, cb -> {
                    cb.ldc((ConstantDesc) m[1]);
                    if (m[2] == CD_int) cb.invokestatic(CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int));
                    cb.areturn(); });
            // indy bootstraps: failing once, returning a String (declared Object), null, a target of another type
            Object[][] indys = {{"indy", "indyFailOnce", callSite}, {"notCallSite", "notCallSite", CD_Object},
                                {"nullCallSite", "nullCallSite", callSite}, {"wrongType", "wrongType", callSite}};
            for (Object[] m : indys)
                b.withMethodBody((String) m[0], MethodTypeDesc.of(CD_Object), ACC_PUBLIC | ACC_STATIC, cb -> {
                    cb.invokedynamic(indy.apply((String) m[1], (ClassDesc) m[2])); cb.areturn(); });
        }));
    }
}
