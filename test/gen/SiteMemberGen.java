import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes SiteMemberLambda into the directory given as the argument: its static
// make() runs a LambdaMetafactory invokedynamic whose implementation method,
// absent(SiteGone), doesn't exist, as a class compiled against another version
// of a library would have. javac only emits handles to methods that exist.
// SiteLinkCheck deletes SiteGone first, and HotSpot resolves the member before
// its descriptor, so the instruction is NoSuchMethodError, not
// NoClassDefFoundError. Run with `java SiteMemberGen.java <dir>`.
public class SiteMemberGen {
    public static void main(String[] args) throws Exception {
        ClassDesc me = ClassDesc.of("SiteMemberLambda");
        ClassDesc gone = ClassDesc.of("SiteGone");
        ClassDesc fn = ClassDesc.of("java.util.function.Function");
        MethodTypeDesc erased = MethodTypeDesc.of(CD_Object, CD_Object);
        DirectMethodHandleDesc impl = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, me, "absent", MethodTypeDesc.of(CD_Object, gone));
        DirectMethodHandleDesc bsm = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of("java.lang.invoke.LambdaMetafactory"), "metafactory",
            MethodTypeDesc.of(CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType,
                              CD_MethodType, CD_MethodHandle, CD_MethodType));
        DynamicCallSiteDesc site = DynamicCallSiteDesc.of(bsm, "apply", MethodTypeDesc.of(fn),
                                                          erased, impl, erased);
        byte[] bytes = ClassFile.of().build(me, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(CD_Object, INIT_NAME, MTD_void);
                cb.return_();
            });
            clb.withMethodBody("make", MethodTypeDesc.of(CD_Object), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.invokedynamic(site);
                cb.areturn();
            });
        });
        Files.write(Path.of(args[0], "SiteMemberLambda.class"), bytes);
    }
}
