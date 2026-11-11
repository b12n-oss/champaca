import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.file.*;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

// Writes Jsr.class into the directory given, for JsrCheck: a version 49 class
// (Java 5) whose methods use jsr, jsr_w and ret, as javac 1.4 and earlier
// compiled every finally block. javac can't emit them anymore. Each method is
// one shape: a finally on the normal and the exception path, one subroutine
// called from two places, a subroutine calling another, jsr_w with a wide ret,
// a subroutine inside a loop, a long kept across one, a subroutine that never
// returns, a value left on the stack across a call, an exception thrown inside
// one with and without a handler covering it, a nested call with values on the
// stack, a handler reading a local after a call, an NPE after a jsr_w, and a
// return address slot reused for an int. Run with `java JsrGen.java <dir>`.
public class JsrGen {
    static final ClassDesc JSR = ClassDesc.of("Jsr");
    static final MethodTypeDesc II = MethodTypeDesc.of(CD_int, CD_int);

    static void jsr(CodeBuilder c, Label l) { c.with(DiscontinuedInstruction.JsrInstruction.of(l)); }
    static void jsrW(CodeBuilder c, Label l) { c.with(DiscontinuedInstruction.JsrInstruction.of(Opcode.JSR_W, l)); }
    static void ret(CodeBuilder c, int slot) { c.with(DiscontinuedInstruction.RetInstruction.of(slot)); }
    static void count(CodeBuilder c) {
        c.getstatic(JSR, "count", CD_int); c.iconst_1(); c.iadd(); c.putstatic(JSR, "count", CD_int);
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = ClassFile.of(StackMapsOption.DROP_STACK_MAPS).build(JSR, b -> {
            b.withVersion(49, 0);
            b.withFlags(ACC_PUBLIC | ACC_SUPER);
            b.withField("count", CD_int, ACC_PUBLIC | ACC_STATIC);

            // try { r = 100 / x; } finally { count++; } return r;
            // as javac 1.4 compiles it: the normal path and the handler both
            // call the subroutine, which keeps its return address in slot 3
            b.withMethodBody("fin", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel(), sub = c.newLabel();
                c.labelBinding(start);
                c.bipush(100); c.iload(0); c.idiv(); c.istore(1);
                c.labelBinding(end);
                jsr(c, sub);
                c.iload(1); c.ireturn();
                c.labelBinding(handler);
                c.astore(2); jsr(c, sub); c.aload(2); c.athrow();
                c.labelBinding(sub);
                c.astore(3); count(c); ret(c, 3);
                c.exceptionCatchAll(start, end, handler);
            });

            // one subroutine, two call sites: ((x + 10) * 2) + 10
            b.withMethodBody("two", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.iload(0); c.istore(1);
                jsr(c, sub);
                c.iload(1); c.iconst_2(); c.imul(); c.istore(1);
                jsr(c, sub);
                c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(2); c.iinc(1, 10); ret(c, 2);
            });

            // a subroutine calling another, as javac compiles a finally inside
            // a finally: x + 1 + 10 + 100
            b.withMethodBody("nested", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label outer = c.newLabel(), inner = c.newLabel();
                c.iload(0); c.istore(1);
                jsr(c, outer);
                c.iload(1); c.ireturn();
                c.labelBinding(outer);
                c.astore(2); c.iinc(1, 1); jsr(c, inner); c.iinc(1, 100); ret(c, 2);
                c.labelBinding(inner);
                c.astore(3); c.iinc(1, 10); ret(c, 3);
            });

            // jsr_w, and a return address in slot 300, so astore and ret are wide
            b.withMethodBody("wide", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.iload(0); c.istore(1);
                jsrW(c, sub);
                c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(300); c.iinc(1, 7); ret(c, 300);
            });

            // a subroutine called in a loop over an int[], adding each element
            b.withMethodBody("loop", MethodTypeDesc.of(CD_int, CD_int.arrayType()), ACC_PUBLIC | ACC_STATIC, c -> {
                Label head = c.newLabel(), done = c.newLabel(), sub = c.newLabel();
                c.iconst_0(); c.istore(1);      // sum
                c.iconst_0(); c.istore(2);      // i
                c.labelBinding(head);
                c.iload(2); c.aload(0); c.arraylength(); c.if_icmpge(done);
                jsr(c, sub);
                c.iinc(2, 1); c.goto_(head);
                c.labelBinding(done);
                c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(3); c.iload(1); c.aload(0); c.iload(2); c.iaload(); c.iadd(); c.istore(1); ret(c, 3);
            });

            // a long local the subroutine changes: (x + 0x100000001) ^ x
            b.withMethodBody("longs", MethodTypeDesc.of(CD_long, CD_long), ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.lload(0); c.lstore(2);
                jsr(c, sub);
                c.lload(2); c.lload(0); c.lxor(); c.lreturn();
                c.labelBinding(sub);
                c.astore(4); c.lload(2); c.ldc(0x100000001L); c.ladd(); c.lstore(2); ret(c, 4);
            });

            // a subroutine that returns from the method instead of with ret
            b.withMethodBody("noReturn", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                jsr(c, sub);
                c.iconst_m1(); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); c.iload(0); c.iconst_3(); c.imul(); c.ireturn();
            });

            // a value left on the operand stack across the call: x + 5
            b.withMethodBody("stack", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.iload(0);
                jsr(c, sub);
                c.iconst_5(); c.iadd(); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); ret(c, 1);
            });

            // an ArithmeticException thrown inside the subroutine, whose code
            // the handler doesn't cover: 7, or the exception for x = 0
            b.withMethodBody("throwOut", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel(), sub = c.newLabel();
                c.labelBinding(start);
                jsr(c, sub);
                c.labelBinding(end);
                c.bipush(7); c.ireturn();
                c.labelBinding(handler);
                c.pop(); c.iconst_m1(); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); c.iconst_1(); c.iload(0); c.idiv(); c.pop(); ret(c, 1);
                c.exceptionCatch(start, end, handler, ClassDesc.of("java.lang.ArithmeticException"));
            });


            // the same with the subroutine's code covered too: 7, or -1 for x = 0
            b.withMethodBody("throwIn", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel(), sub = c.newLabel(), subEnd = c.newLabel();
                c.labelBinding(start);
                jsr(c, sub);
                c.labelBinding(end);
                c.bipush(7); c.ireturn();
                c.labelBinding(handler);
                c.pop(); c.iconst_m1(); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); c.iconst_1(); c.iload(0); c.idiv(); c.pop(); ret(c, 1);
                c.labelBinding(subEnd);
                c.exceptionCatch(start, end, handler, ClassDesc.of("java.lang.ArithmeticException"));
                c.exceptionCatch(sub, subEnd, handler, ClassDesc.of("java.lang.ArithmeticException"));
            });

            // values on the stack across two nested calls, a different depth at
            // each return: the inner ret must not go where the outer one
            // returns to. x + 9
            b.withMethodBody("stackNested", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label outer = c.newLabel(), inner = c.newLabel();
                c.iload(0);
                jsr(c, outer);
                c.ireturn();
                c.labelBinding(outer);
                c.astore(1); c.bipush(9); jsr(c, inner); c.iadd(); ret(c, 1);
                c.labelBinding(inner);
                c.astore(2); ret(c, 2);
            });

            // a handler that calls a subroutine, then reads a local its try range
            // assigned: the value from the throw, 5, not the one from before it
            b.withMethodBody("staleLocal", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label start = c.newLabel(), end = c.newLabel(), handler = c.newLabel(), sub = c.newLabel();
                c.iconst_0(); c.istore(1);
                c.labelBinding(start);
                c.iload(0); c.iconst_5(); c.iadd(); c.istore(1);
                c.new_(ClassDesc.of("java.lang.RuntimeException")); c.dup();
                c.invokespecial(ClassDesc.of("java.lang.RuntimeException"), "<init>", MethodTypeDesc.of(CD_void));
                c.athrow();
                c.labelBinding(end);
                c.labelBinding(handler);
                c.astore(2); jsr(c, sub); c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(3); ret(c, 3);
                c.exceptionCatchAll(start, end, handler);
            });

            // a null dereferenced after a jsr_w: the helpful NPE message's
            // analysis walks past the jsr_w
            b.withMethodBody("wideNpe", MethodTypeDesc.of(CD_int, CD_String), ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                jsrW(c, sub);
                c.aload(0); c.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int)); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); ret(c, 1);
            });

            // the same after a plain jsr
            b.withMethodBody("jsrNpe", MethodTypeDesc.of(CD_int, CD_String), ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                jsr(c, sub);
                c.aload(0); c.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int)); c.ireturn();
                c.labelBinding(sub);
                c.astore(1); ret(c, 1);
            });

            // and before any jsr: a null dereferenced, then a subroutine
            b.withMethodBody("npeBeforeJsr", MethodTypeDesc.of(CD_int, CD_String), ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.aload(0); c.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int)); c.istore(1);
                jsr(c, sub);
                c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(2); ret(c, 2);
            });

            // the return address slot holds an int between the two calls:
            // x + 2 + 3 + 2
            b.withMethodBody("reuse", II, ACC_PUBLIC | ACC_STATIC, c -> {
                Label sub = c.newLabel();
                c.iload(0); c.istore(1);
                jsr(c, sub);
                c.iconst_3(); c.istore(2); c.iload(1); c.iload(2); c.iadd(); c.istore(1);
                jsr(c, sub);
                c.iload(1); c.ireturn();
                c.labelBinding(sub);
                c.astore(2); c.iinc(1, 2); ret(c, 2);
            });
        });
        Files.write(Path.of(args[0], "Jsr.class"), bytes);
    }
}
