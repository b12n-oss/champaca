import java.lang.classfile.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes ArrayLoopShapes.class into the directory given, for ArrayLoopCheck:
// loops that sum b[0..n) after moving b on the stack with dup_x1 or swap
// over a, both array locals the loop never stores, shapes javac never emits.
// A versioned loop's copy knows which stack values an aload of such a local
// pushed (codegen emit-block's origins); a stale one would read a's elements.
// Run with `java ArrayLoopGen.java <dir>`.
public class ArrayLoopGen {
    interface Move { void emit(CodeBuilder c); }

    static void sumAfter(ClassBuilder b, String name, Move move) {
        ClassDesc ints = CD_int.arrayType();
        b.withMethodBody(name, MethodTypeDesc.of(CD_int, ints, ints, CD_int), ACC_PUBLIC | ACC_STATIC, c -> {
            Label test = c.newLabel(), done = c.newLabel();
            c.iconst_0(); c.istore(3); c.iconst_0(); c.istore(4);
            c.labelBinding(test);
            c.iload(4); c.iload(2); c.if_icmpge(done);
            c.aload(0); c.aload(1); move.emit(c);
            c.iload(4); c.iaload(); c.iload(3); c.iadd(); c.istore(3);
            c.iinc(4, 1); c.goto_(test);
            c.labelBinding(done); c.iload(3); c.ireturn(); });
    }

    public static void main(String[] args) throws Exception {
        Files.write(Path.of(args[0], "ArrayLoopShapes.class"), ClassFile.of().build(ClassDesc.of("ArrayLoopShapes"), b -> {
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            // [a b] -> [b a b] -> [b]
            sumAfter(b, "dupX1", c -> { c.dup_x1(); c.pop(); c.pop(); });
            // [a b] -> [b a] -> [b]
            sumAfter(b, "swap", c -> { c.swap(); c.pop(); });
        }));
    }
}
