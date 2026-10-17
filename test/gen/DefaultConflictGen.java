import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes DcC, DcT, DcD and DcSub into the directory given as the argument.
// javac refuses a class that inherits conflicting defaults (DcC, DcT) or
// leaves a reabstracted method unimplemented (DcD), so these are built with
// the ClassFile API; the conflicts exist only at run time, which is where
// the JVM throws IncompatibleClassChangeError. Run with
// `java DefaultConflictGen.java <dir>`.
public class DefaultConflictGen {
    static void write(Path dir, String name, String superName, String[] ifaces, String body) throws Exception {
        ClassDesc me = ClassDesc.of(name);
        ClassDesc sup = ClassDesc.of(superName);
        byte[] bytes = ClassFile.of().build(me, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(sup);
            ClassDesc[] ids = new ClassDesc[ifaces.length];
            for (int i = 0; i < ifaces.length; i++) ids[i] = ClassDesc.of(ifaces[i]);
            clb.withInterfaceSymbols(ids);
            clb.withMethodBody(INIT_NAME, MTD_void, 0, cb -> {
                cb.aload(0);
                cb.invokespecial(sup, INIT_NAME, MTD_void);
                cb.return_();
            });
            if (body != null)
                clb.withMethodBody("m", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC, cb -> {
                    cb.loadConstant(body);
                    cb.areturn();
                });
        });
        Files.write(dir.resolve(name + ".class"), bytes);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        write(dir, "DcC", "java.lang.Object", new String[] {"DcI1", "DcI2"}, null);
        write(dir, "DcT", "java.lang.Object", new String[] {"DcI1", "DcI2", "DcI4"}, null);
        write(dir, "DcD", "java.lang.Object", new String[] {"DcI3"}, null);
        write(dir, "DcSub", "DcC", null2(), "sub");
    }

    static String[] null2() { return new String[0]; }
}
