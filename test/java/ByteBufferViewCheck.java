import java.lang.invoke.*;
import java.nio.*;

// MethodHandles.byteBufferViewVarHandle: each view type over heap, direct,
// sliced and read-only buffers in both byte orders, the atomic modes where
// the JDK has them, and the JDK's bounds, alignment and read-only refusals.
public class ByteBufferViewCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e); } }
    static String bytes(ByteBuffer bb, int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) s.append(String.format("%02x", bb.get(i))); return s.toString(); }

    public static void main(String[] args) throws Throwable {
        for (boolean direct : new boolean[] { false, true }) {
            String k = direct ? "direct " : "heap ";
            for (ByteOrder o : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                String ko = k + (o == ByteOrder.BIG_ENDIAN ? "BE " : "LE ");
                ByteBuffer bb = direct ? ByteBuffer.allocateDirect(16) : ByteBuffer.allocate(16);
                VarHandle i = MethodHandles.byteBufferViewVarHandle(int[].class, o);
                VarHandle l = MethodHandles.byteBufferViewVarHandle(long[].class, o);
                VarHandle s = MethodHandles.byteBufferViewVarHandle(short[].class, o);
                VarHandle c = MethodHandles.byteBufferViewVarHandle(char[].class, o);
                VarHandle f = MethodHandles.byteBufferViewVarHandle(float[].class, o);
                VarHandle d = MethodHandles.byteBufferViewVarHandle(double[].class, o);
                t(ko + "int", () -> { i.set(bb, 0, 0x01020304); return bytes(bb, 4) + " " + Integer.toHexString((int) i.get(bb, 0)); });
                t(ko + "long", () -> { l.set(bb, 8, 0x0102030405060708L); return bytes(bb, 16).substring(16) + " " + Long.toHexString((long) l.get(bb, 8)); });
                t(ko + "short", () -> { s.set(bb, 1, (short) -2); return bytes(bb, 4) + " " + (short) s.get(bb, 1); });
                t(ko + "char", () -> { c.set(bb, 2, 'Z'); return (char) c.get(bb, 2) + " " + bytes(bb, 4); });
                t(ko + "float", () -> { f.set(bb, 4, 1.5f); return (float) f.get(bb, 4) + " " + bytes(bb, 8).substring(8); });
                t(ko + "double", () -> { d.set(bb, 8, -2.25); return (double) d.get(bb, 8) + " " + bytes(bb, 16).substring(16); });
                t(ko + "unaligned int", () -> { i.set(bb, 3, 7); return (int) i.get(bb, 3); });
                t(ko + "cas", () -> { i.set(bb, 4, 5); boolean ok = i.compareAndSet(bb, 4, 5, 6); return ok + " " + (int) i.getVolatile(bb, 4); });
                t(ko + "getAndAdd", () -> (int) i.getAndAdd(bb, 4, 10) + " " + (int) i.get(bb, 4));
                t(ko + "long getAndBitwiseOr", () -> { l.set(bb, 8, 1L); return (long) l.getAndBitwiseOr(bb, 8, 6L) + " " + (long) l.get(bb, 8); });
                t(ko + "double cas", () -> d.compareAndSet(bb, 8, 0.0, 1.0));
                t(ko + "unaligned cas", () -> i.compareAndSet(bb, 2, 0, 1));
                t(ko + "out of bounds", () -> i.get(bb, 13));
                t(ko + "negative", () -> i.get(bb, -1));
            }
        }
        VarHandle i = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
        ByteBuffer base = ByteBuffer.allocate(16);
        base.putInt(4, 0x0a0b0c0d);
        ByteBuffer slice = base.slice(4, 8);
        t("slice get", () -> Integer.toHexString((int) i.get(slice, 0)));
        t("slice set", () -> { i.set(slice, 4, 99); return base.getInt(8); });
        ByteBuffer ro = base.asReadOnlyBuffer();
        t("read-only get", () -> Integer.toHexString((int) i.get(ro, 4)));
        t("read-only set", () -> { i.set(ro, 4, 1); return "set"; });
        t("read-only cas", () -> i.compareAndSet(ro, 4, 1, 2));
        t("null buffer", () -> i.get((ByteBuffer) null, 0));
        t("varType", () -> i.varType() + " " + i.coordinateTypes());
        t("modes", () -> i.isAccessModeSupported(VarHandle.AccessMode.GET_AND_ADD) + " "
            + MethodHandles.byteBufferViewVarHandle(float[].class, ByteOrder.BIG_ENDIAN).isAccessModeSupported(VarHandle.AccessMode.GET_AND_ADD)
            + " " + MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN).isAccessModeSupported(VarHandle.AccessMode.COMPARE_AND_SET));
        t("not an array", () -> MethodHandles.byteBufferViewVarHandle(String.class, ByteOrder.BIG_ENDIAN));
        t("byte view", () -> MethodHandles.byteBufferViewVarHandle(byte[].class, ByteOrder.BIG_ENDIAN));
        t("boolean view", () -> MethodHandles.byteBufferViewVarHandle(boolean[].class, ByteOrder.BIG_ENDIAN));
        t("null order", () -> MethodHandles.byteBufferViewVarHandle(int[].class, null));
    }
}
