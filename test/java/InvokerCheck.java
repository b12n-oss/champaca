import java.lang.invoke.*;
import static java.lang.invoke.MethodType.methodType;
// Lookup.bind, the invokers findVirtual gives on MethodHandle (invoke,
// invokeExact) and VarHandle (an access mode's name), and
// MethodHandles.varHandleInvoker and varHandleExactInvoker, with the JDK's
// texts when the lookup or the call doesn't fit.
public class InvokerCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e); } }
    public String hello(String n) { return "hello " + n; }
    public static String shello(String n) { return "s " + n; }
    private String secret() { return "secret"; }
    static int x; long y;
    static class Sub extends InvokerCheck { public String hello(String n) { return "sub " + n; } }
    public static void main(String[] a) throws Throwable {
        var L = MethodHandles.lookup();
        t("bind missing", () -> L.bind(new InvokerCheck(), "nope", methodType(String.class)));
        t("bind static", () -> L.bind(new InvokerCheck(), "shello", methodType(String.class, String.class)));
        t("bind type", () -> L.bind(new InvokerCheck(), "hello", methodType(String.class, String.class)).type());
        t("bind sub", () -> (String) L.bind(new Sub(), "hello", methodType(String.class, String.class)).invoke("x"));
        t("bind private", () -> (String) L.bind(new InvokerCheck(), "secret", methodType(String.class)).invoke());
        t("bind null", () -> L.bind(null, "hello", methodType(String.class, String.class)));
        t("bind toString", () -> (String) L.bind(new InvokerCheck(), "toString", methodType(String.class)).invoke() != null);
        t("ie type", () -> L.findVirtual(MethodHandle.class, "invokeExact", methodType(int.class, int.class)).type());
        t("inv type", () -> L.findVirtual(MethodHandle.class, "invoke", methodType(Object.class, String.class)).type());
        t("ie mismatch", () -> { MethodHandle ie = L.findVirtual(MethodHandle.class, "invokeExact", methodType(int.class, int.class));
            return (int) ie.invokeExact(MethodHandles.identity(long.class), 5); });
        t("inv converts", () -> { MethodHandle ie = L.findVirtual(MethodHandle.class, "invoke", methodType(long.class, int.class));
            return (long) ie.invokeExact(MethodHandles.identity(long.class), 5); });
        t("vh findVirtual get", () -> { VarHandle vh = L.findStaticVarHandle(InvokerCheck.class, "x", int.class); x = 7;
            MethodHandle g = L.findVirtual(VarHandle.class, "get", methodType(int.class)); return g.type() + " " + (int) g.invokeExact(vh); });
        t("vh findVirtual cas", () -> { VarHandle vh = L.findVarHandle(InvokerCheck.class, "y", long.class); InvokerCheck o = new InvokerCheck();
            MethodHandle g = L.findVirtual(VarHandle.class, "compareAndSet", methodType(boolean.class, InvokerCheck.class, long.class, long.class));
            return g.type() + " " + (boolean) g.invokeExact(vh, o, 0L, 9L) + " " + o.y; });
        t("vh findVirtual bad", () -> L.findVirtual(VarHandle.class, "nope", methodType(int.class)));
        t("vh exact type", () -> MethodHandles.varHandleExactInvoker(VarHandle.AccessMode.GET, methodType(int.class)).type());
        t("vh exact mismatch", () -> { VarHandle vh = L.findStaticVarHandle(InvokerCheck.class, "x", int.class);
            return (long) MethodHandles.varHandleExactInvoker(VarHandle.AccessMode.GET, methodType(long.class)).invokeExact(vh); });
        t("vh invoker converts", () -> { VarHandle vh = L.findStaticVarHandle(InvokerCheck.class, "x", int.class); x = 3;
            return (long) MethodHandles.varHandleInvoker(VarHandle.AccessMode.GET, methodType(long.class)).invokeExact(vh); });
        t("vh invoker getAndAdd", () -> { VarHandle vh = L.findStaticVarHandle(InvokerCheck.class, "x", int.class); x = 3;
            int r = (int) MethodHandles.varHandleInvoker(VarHandle.AccessMode.GET_AND_ADD, methodType(int.class, int.class)).invokeExact(vh, 4); return r + " " + x; });
        t("vh invoker null vh", () -> (int) MethodHandles.varHandleInvoker(VarHandle.AccessMode.GET, methodType(int.class)).invokeExact((VarHandle) null));
        t("vh invoker unsupported", () -> { VarHandle vh = L.findStaticVarHandle(InvokerCheck.class, "x", int.class);
            return (int) MethodHandles.varHandleInvoker(VarHandle.AccessMode.GET_AND_ADD, methodType(int.class, String.class)).invokeExact(vh, "s"); });
    }
}
