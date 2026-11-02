import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes LongCmp.class into the directory given, for LongOpsCheck: lcmp in
// the shapes javac never emits, since javac always branches on lcmp's result
// with the next instruction. cmp returns it, stored keeps it in a local
// before the branch, and joined branches on it in a block of its own (a
// jump target, with the int on the stack). halves returns it for operands
// a long operation made (a ^ 0), which champaca keeps as 32-bit halves. Run with
// `java LongCmpGen.java <dir>`.
public class LongCmpGen {
    public static void main(String[] args) throws Exception {
        MethodTypeDesc jji = MethodTypeDesc.of(CD_int, CD_long, CD_long);
        Files.write(Path.of(args[0], "LongCmp.class"), ClassFile.of().build(ClassDesc.of("LongCmp"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            b.withMethodBody("cmp", jji, ACC_PUBLIC | ACC_STATIC, c -> {
                c.lload(0); c.lload(2); c.lcmp(); c.ireturn(); });
            b.withMethodBody("stored", jji, ACC_PUBLIC | ACC_STATIC, c -> {
                Label ge = c.newLabel();
                c.lload(0); c.lload(2); c.lcmp(); c.istore(4); c.iload(4); c.ifge(ge);
                c.iconst_1(); c.ireturn();
                c.labelBinding(ge); c.iconst_0(); c.ireturn(); });
            b.withMethodBody("joined", jji, ACC_PUBLIC | ACC_STATIC, c -> {
                Label join = c.newLabel(), lt = c.newLabel();
                c.lload(0); c.lload(2); c.lcmp(); c.goto_(join);
                c.labelBinding(join); c.iflt(lt);
                c.iconst_0(); c.ireturn();
                c.labelBinding(lt); c.iconst_1(); c.ireturn(); });
            b.withMethodBody("halves", jji, ACC_PUBLIC | ACC_STATIC, c -> {
                c.lload(0); c.lconst_0(); c.lxor(); c.lload(2); c.lconst_0(); c.lxor(); c.lcmp(); c.ireturn(); });
        }));
    }
}
