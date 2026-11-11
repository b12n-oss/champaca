import java.lang.invoke.*;
import java.util.*;
import static java.lang.invoke.MethodType.methodType;
// MethodHandles.explicitCastArguments: Java's casting conversions between
// primitives (narrowing too), booleans as 1/0 and as a low bit, null as zero,
// unboxing through Number with the JDK's exception texts, boxing in the
// source's own wrapper, unchecked casts to interfaces, and the arity check.
public class ExplicitCastCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e); } }
    static Object conv(Class<?> from, Class<?> to, Object v) throws Throwable {
        MethodHandle id = MethodHandles.identity(to);
        MethodHandle h = MethodHandles.explicitCastArguments(id, methodType(to, from));
        return h.invokeWithArguments(v);
    }
    static Object ret(Class<?> from, Class<?> to, Object v) throws Throwable {
        MethodHandle id = MethodHandles.identity(from);
        MethodHandle h = MethodHandles.explicitCastArguments(id, methodType(to, from));
        return h.invokeWithArguments(v);
    }
    public static void main(String[] a) throws Throwable {
        t("long->int", () -> conv(long.class, int.class, 0x1_0000_0005L));
        t("int->byte", () -> conv(int.class, byte.class, 300));
        t("int->short", () -> conv(int.class, short.class, 70000));
        t("int->char", () -> conv(int.class, char.class, 65 + 65536));
        t("double->int", () -> conv(double.class, int.class, 3.9));
        t("double->long", () -> conv(double.class, long.class, -1e30));
        t("float->byte", () -> conv(float.class, byte.class, 200.7f));
        t("int->boolean", () -> conv(int.class, boolean.class, 6));
        t("int->boolean odd", () -> conv(int.class, boolean.class, 7));
        t("boolean->int", () -> conv(boolean.class, int.class, true));
        t("boolean->double", () -> conv(boolean.class, double.class, true));
        t("Integer->byte", () -> conv(Integer.class, byte.class, 300));
        t("Object->short", () -> conv(Object.class, short.class, 70000));
        t("Object(null)->int", () -> conv(Object.class, int.class, null));
        t("Object(null)->boolean", () -> conv(Object.class, boolean.class, null));
        t("Long->int", () -> conv(Long.class, int.class, 5L));
        t("Object(Double)->int", () -> conv(Object.class, int.class, 2.5));
        t("Object(String)->int", () -> conv(Object.class, int.class, "x"));
        t("Object->Integer", () -> conv(Object.class, Integer.class, "x"));
        t("int->Object", () -> conv(int.class, Object.class, 5));
        t("byte->Integer", () -> conv(byte.class, Integer.class, (byte) 5));
        t("long->Integer", () -> conv(long.class, Integer.class, 5L));
        t("Object->List (iface)", () -> String.valueOf(conv(Object.class, List.class, "notalist")));
        t("ret int->byte", () -> ret(int.class, byte.class, 300));
        t("ret double->char", () -> ret(double.class, char.class, 66.6));
        t("ret long->boolean", () -> ret(long.class, boolean.class, 3L));
        t("void ret", () -> MethodHandles.explicitCastArguments(MethodHandles.empty(methodType(void.class)), methodType(int.class)).invoke());
        t("char->byte", () -> conv(char.class, byte.class, (char) 0x1ff));
        t("char->boolean", () -> conv(char.class, boolean.class, 'c'));
        t("double->float", () -> conv(double.class, float.class, 1e40));
        t("double NaN->int", () -> conv(double.class, int.class, Double.NaN));
        t("float->char", () -> (int) (char) conv(float.class, char.class, -1.5f));
        t("long->short", () -> conv(long.class, short.class, 0x7_0000_8001L));
        t("long->float", () -> conv(long.class, float.class, Long.MAX_VALUE));
        t("double->boolean", () -> conv(double.class, boolean.class, 3.9));
        t("boolean->char", () -> (int) (char) conv(boolean.class, char.class, true));
        t("Character->int", () -> conv(Character.class, int.class, 'z'));
        t("Object(Character)->byte", () -> conv(Object.class, byte.class, (char) 300));
        t("Object(Boolean)->long", () -> conv(Object.class, long.class, true));
        t("Object(Long)->boolean", () -> conv(Object.class, boolean.class, 3L));
        t("Number(BigInteger)->int", () -> conv(Number.class, int.class, java.math.BigInteger.TEN));
        t("Object(BigInteger)->boolean", () -> conv(Object.class, boolean.class, java.math.BigInteger.ONE));
        t("boolean->Object", () -> conv(boolean.class, Object.class, true));
        t("int->Number", () -> conv(int.class, Number.class, 7));
        t("int->Comparable", () -> conv(int.class, Comparable.class, 7));
        t("Object->Runnable[]", () -> conv(Object.class, Runnable[].class, "x"));
        t("Object->String", () -> conv(Object.class, String.class, 5));
        t("ret Object->int", () -> ret(Object.class, int.class, null));
        t("arity", () -> MethodHandles.explicitCastArguments(MethodHandles.identity(int.class), methodType(int.class)));
    }
}
