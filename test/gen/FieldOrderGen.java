import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes FoRead into the directory given as the argument: a static method per
// FieldOrderCheck case, each a getstatic of f through FoSub<n>, which inherits
// an f from both an interface and a superclass. javac rejects such a read as
// ambiguous, but a class compiled before one of the two fields existed has
// it. Run with `java FieldOrderGen.java <dir>`.
public class FieldOrderGen {
    public static void main(String[] args) throws Exception {
        byte[] bytes = ClassFile.of().build(ClassDesc.of("FoRead"), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            for (int i = 1; i <= 6; i++) {
                ClassDesc sub = ClassDesc.of("FoSub" + i);
                clb.withMethodBody("sub" + i, MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                    cb.getstatic(sub, "f", CD_String);
                    cb.areturn();
                });
            }
        });
        Files.write(Path.of(args[0], "FoRead.class"), bytes);
    }
}
