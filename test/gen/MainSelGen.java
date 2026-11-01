import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
// Writes two main classes javac won't compile into the directory given (after
// test/mainsel is compiled there): PrivStatic, a private static main(String[])
// hiding Base's public one, which the launcher's MethodFinder skips but JNI's
// GetStaticMethodID finds and runs; PrivInstance, a private instance main
// over InstArgs's protected one, which the launcher refuses. Run with
// `java MainSelGen.java <dir>`.
public class MainSelGen {
    public static void main(String[] a) throws Exception {
        MethodTypeDesc mt = MethodTypeDesc.of(CD_void, CD_String.arrayType());
        Files.write(Path.of(a[0], "PrivStatic.class"), ClassFile.of().build(ClassDesc.of("PrivStatic"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(ClassDesc.of("Base"));
            b.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, c -> { c.aload(0); c.invokespecial(ClassDesc.of("Base"), INIT_NAME, MTD_void); c.return_(); });
            b.withMethodBody("main", mt, ACC_PRIVATE | ACC_STATIC, c -> {
                c.getstatic(ClassDesc.of("java.lang.System"), "out", ClassDesc.of("java.io.PrintStream"));
                c.ldc("private static main of the subclass");
                c.invokevirtual(ClassDesc.of("java.io.PrintStream"), "println", MethodTypeDesc.of(CD_void, CD_String));
                c.return_(); });
        }));
        Files.write(Path.of(a[0], "PrivInstance.class"), ClassFile.of().build(ClassDesc.of("PrivInstance"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER); b.withSuperclass(ClassDesc.of("InstArgs"));
            b.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, c -> { c.aload(0); c.invokespecial(ClassDesc.of("InstArgs"), INIT_NAME, MTD_void); c.return_(); });
            b.withMethodBody("main", mt, ACC_PRIVATE, c -> {
                c.getstatic(ClassDesc.of("java.lang.System"), "out", ClassDesc.of("java.io.PrintStream"));
                c.ldc("private instance main of the subclass");
                c.invokevirtual(ClassDesc.of("java.io.PrintStream"), "println", MethodTypeDesc.of(CD_void, CD_String));
                c.return_(); });
        }));
    }
}
