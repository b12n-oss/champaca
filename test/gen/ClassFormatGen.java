import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes CfTruncated, CfBadMagic, CfSub and CfCaller into the directory given as the
// argument. CfTruncated is the first half of a class file and CfBadMagic one
// whose first four bytes are wrong: both exist but can't be loaded, which the
// JVM reports as ClassFormatError from the instruction that first needs the
// class, and again from each later one. CfCaller reaches them through new,
// ldc, invokestatic, getstatic, checkcast and instanceof, each in a method
// of its own, so the others run fine. Run with
// `java ClassFormatGen.java <dir>`.
public class ClassFormatGen {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        ClassDesc trunc = ClassDesc.of("CfTruncated");
        ClassDesc magic = ClassDesc.of("CfBadMagic");
        byte[] whole = ClassFile.of().build(trunc, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withField("X", CD_int, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(CD_Object, INIT_NAME, MTD_void);
                cb.return_();
            });
            clb.withMethodBody("f", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.iconst_1();
                cb.ireturn();
            });
        });
        Files.write(dir.resolve("CfTruncated.class"), java.util.Arrays.copyOf(whole, whole.length / 2));
        byte[] bad = ClassFile.of().build(magic, clb -> clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER));
        bad[0] = 0x12; bad[1] = 0x34; bad[2] = 0x56; bad[3] = 0x78;
        Files.write(dir.resolve("CfBadMagic.class"), bad);
        // CfSub is a whole class file whose superclass is CfTruncated
        ClassDesc sub = ClassDesc.of("CfSub");
        Files.write(dir.resolve("CfSub.class"), ClassFile.of().build(sub, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(trunc);
        }));
        ClassDesc caller = ClassDesc.of("CfCaller");
        Files.write(dir.resolve("CfCaller.class"), ClassFile.of().build(caller, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(CD_Object, INIT_NAME, MTD_void);
                cb.return_();
            });
            // each returns a String; the instruction under test comes first
            clb.withMethodBody("newIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.new_(trunc);
                cb.pop();
                cb.loadConstant("made");
                cb.areturn();
            });
            clb.withMethodBody("ldcIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.ldc(trunc);
                cb.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_Object));
                cb.areturn();
            });
            clb.withMethodBody("callIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.invokestatic(trunc, "f", MethodTypeDesc.of(CD_int));
                cb.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_int));
                cb.areturn();
            });
            clb.withMethodBody("getIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.getstatic(trunc, "X", CD_int);
                cb.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_int));
                cb.areturn();
            });
            clb.withMethodBody("castIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.loadConstant("s");
                cb.checkcast(trunc);
                cb.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_Object));
                cb.areturn();
            });
            clb.withMethodBody("isIt", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.loadConstant("s");
                cb.instanceOf(trunc);
                cb.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_boolean));
                cb.areturn();
            });
            clb.withMethodBody("newBadMagic", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.new_(magic);
                cb.pop();
                cb.loadConstant("made");
                cb.areturn();
            });
            clb.withMethodBody("newSub", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.new_(sub);
                cb.pop();
                cb.loadConstant("made");
                cb.areturn();
            });
            clb.withMethodBody("fine", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.loadConstant("fine");
                cb.areturn();
            });
        }));
    }
}
