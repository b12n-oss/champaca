import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes vers/V<major>_<minor>.class into the directory given, for
// VersionCheck: an empty class for each class file version, those JDK 25
// takes (45.3, 55.1, 69.0) and those it refuses (69.65535 without
// --enable-preview, an older release's preview, a minor version past 55, a
// major past 69 or before 45), which javac can't write; vers/D69_65535, a
// preview class only defined from bytes; and vers/Ref, whose
// methods name two of them in instructions (ldc, instanceof). Run with
// `java VersionGen.java <dir>`.
public class VersionGen {
    static final int[][] VERSIONS = {
        {45, 3}, {55, 1}, {69, 0}, {69, 0xFFFF}, {68, 0xFFFF}, {56, 0xFFFF},
        {69, 1}, {70, 0}, {70, 0xFFFF}, {44, 0}};

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0], "vers"));
        for (int[] v : VERSIONS) {
            String n = "V" + v[0] + "_" + v[1];
            Files.write(Path.of(args[0], "vers", n + ".class"), ClassFile.of().build(ClassDesc.of("vers." + n), b -> {
                b.withVersion(v[0], v[1]);
                b.withFlags(ACC_PUBLIC | ACC_SUPER);
            }));
        }
        // a preview class only ever defined from its bytes, by PreviewCheck
        // and then VersionCheck, through loaders of their own
        Files.write(Path.of(args[0], "vers", "D69_65535.class"), ClassFile.of().build(ClassDesc.of("vers.D69_65535"), b -> {
            b.withVersion(69, 0xFFFF);
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
        }));
        Files.write(Path.of(args[0], "vers", "Ref.class"), ClassFile.of().build(ClassDesc.of("vers.Ref"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            b.withMethodBody("newer", MethodTypeDesc.of(CD_Class), ACC_PUBLIC | ACC_STATIC, c -> {
                c.ldc(ClassDesc.of("vers.V70_0")); c.areturn(); });
            b.withMethodBody("preview", MethodTypeDesc.of(CD_boolean), ACC_PUBLIC | ACC_STATIC, c -> {
                c.ldc("s"); c.instanceOf(ClassDesc.of("vers.V69_65535")); c.ireturn(); });
        }));
    }
}
