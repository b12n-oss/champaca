import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.ByteOrder;
import java.util.Arrays;
import static java.lang.foreign.ValueLayout.*;

// java.lang.foreign memory access against HotSpot: heap segments over every
// primitive array type, native segments from arenas, the layouts' VarHandles
// and their access modes, layout paths, and the errors for bounds,
// alignment, read-only, closed and confined segments. Identity hashes and
// native addresses are masked.
public class SegmentCheck {
  static int n = 0;
  interface Body { Object call() throws Throwable; }
  static void row(String name, Body c) {
    String r;
    try { Object o = c.call(); r = o instanceof Object[] a ? Arrays.deepToString(a) : String.valueOf(o); }
    catch (Throwable t) { String m = t.getMessage(); r = t.getClass().getName() + (m == null ? "" : ": " + m.replaceAll("@[0-9a-f]+", "@H").replaceAll("0x[0-9a-f]{6,}", "0xADDR")); }
    System.out.println(++n + " " + name + ": " + r);
  }
  static final StructLayout POINT = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));
  static final SequenceLayout POINTS = MemoryLayout.sequenceLayout(4, POINT);
  public static void main(String[] args) {
    row("int[] get", () -> MemorySegment.ofArray(new int[]{1,2,3}).getAtIndex(JAVA_INT, 2));
    row("long[] set get", () -> { var s = MemorySegment.ofArray(new long[2]); s.setAtIndex(JAVA_LONG, 1, -5L); return s.getAtIndex(JAVA_LONG, 1); });
    row("byte[] int unaligned", () -> MemorySegment.ofArray(new byte[]{0,1,0,0,0,0,0,0}).get(JAVA_INT_UNALIGNED, 1));
    row("byte[] int aligned refused", () -> MemorySegment.ofArray(new byte[8]).get(JAVA_INT, 0));
    row("byte[] int BE", () -> MemorySegment.ofArray(new byte[]{0,0,1,2}).get(JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0));
    row("int[] read bytes", () -> { var s = MemorySegment.ofArray(new int[]{0x04030201}); return "" + s.get(JAVA_BYTE, 0) + s.get(JAVA_BYTE, 3); });
    row("long[] read int halves", () -> { var s = MemorySegment.ofArray(new long[]{0x0000000200000001L}); return s.get(JAVA_INT, 0) + "," + s.get(JAVA_INT, 4); });
    row("int[] write short", () -> { var a = new int[1]; MemorySegment.ofArray(a).set(JAVA_SHORT, 2, (short)7); return a[0]; });
    row("double[] get", () -> MemorySegment.ofArray(new double[]{1.5, 2.5}).getAtIndex(JAVA_DOUBLE, 1));
    row("float[] set", () -> { var a = new float[2]; MemorySegment.ofArray(a).setAtIndex(JAVA_FLOAT, 1, 3.25f); return a[1]; });
    row("char[] get", () -> MemorySegment.ofArray(new char[]{'a','b'}).getAtIndex(JAVA_CHAR, 1));
    row("boolean", () -> { var s = MemorySegment.ofArray(new byte[]{0,1}); return s.get(JAVA_BOOLEAN, 0) + "," + s.get(JAVA_BOOLEAN, 1); });
    row("oob index", () -> MemorySegment.ofArray(new int[2]).getAtIndex(JAVA_INT, 2));
    row("negative offset", () -> MemorySegment.ofArray(new int[2]).get(JAVA_INT, -4));
    row("read-only set", () -> { MemorySegment.ofArray(new int[1]).asReadOnly().set(JAVA_INT, 0, 1); return "no"; });
    row("read-only get", () -> MemorySegment.ofArray(new int[]{9}).asReadOnly().get(JAVA_INT, 0));
    row("native set get", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocate(JAVA_LONG, 3); s.setAtIndex(JAVA_LONG, 2, Long.MIN_VALUE); return s.getAtIndex(JAVA_LONG, 2); } });
    row("native misaligned", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocate(16, 8); return s.get(JAVA_INT, 2); } });
    row("native unaligned ok", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocate(16, 8); s.set(JAVA_INT_UNALIGNED, 3, 77); return s.get(JAVA_INT_UNALIGNED, 3); } });
    row("closed arena", () -> { MemorySegment s; try (Arena a = Arena.ofConfined()) { s = a.allocate(8); } return s.get(JAVA_INT, 0); });
    row("null segment", () -> JAVA_INT.varHandle().get((MemorySegment) null, 0L));
    row("NULL segment", () -> MemorySegment.NULL.get(JAVA_INT, 0));
    var vh = JAVA_INT.varHandle();
    row("vh coords", () -> vh.coordinateTypes());
    row("vh type", () -> vh.varType());
    row("vh CAS", () -> { var s = MemorySegment.ofArray(new int[]{5}); return vh.compareAndSet(s, 0L, 5, 6) + "," + vh.compareAndSet(s, 0L, 5, 7) + "," + s.get(JAVA_INT, 0); });
    row("vh getAndAdd", () -> { var s = MemorySegment.ofArray(new int[]{5}); return vh.getAndAdd(s, 0L, 3) + "," + s.get(JAVA_INT, 0); });
    row("vh bitwise", () -> { var s = MemorySegment.ofArray(new int[]{6}); return vh.getAndBitwiseOr(s, 0L, 1) + "," + vh.getAndBitwiseAnd(s, 0L, 3) + "," + vh.getAndBitwiseXor(s, 0L, 2) + "," + s.get(JAVA_INT, 0); });
    row("vh volatile", () -> { var s = MemorySegment.ofArray(new int[1]); vh.setVolatile(s, 0L, 4); vh.setRelease(s, 0L, (int) vh.getAcquire(s, 0L) + 1); return vh.getVolatile(s, 0L); });
    row("vh cax", () -> { var s = MemorySegment.ofArray(new int[]{1}); return vh.compareAndExchange(s, 0L, 1, 2) + "," + vh.getAndSet(s, 0L, 9) + "," + s.get(JAVA_INT, 0); });
    row("vh long native add", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocate(8); var lv = JAVA_LONG.varHandle(); lv.set(s, 0L, 40L); return (long) lv.getAndAdd(s, 0L, 2L) + "," + s.get(JAVA_LONG, 0); } });
    row("vh BE getAndAdd", () -> { var s = MemorySegment.ofArray(new int[1]); var bv = JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).varHandle(); bv.set(s, 0L, 1); bv.getAndAdd(s, 0L, 1); return s.get(JAVA_INT, 0) + "," + bv.get(s, 0L); });
    row("vh float CAS", () -> { var s = MemorySegment.ofArray(new float[]{1f}); var fv = JAVA_FLOAT.varHandle(); return fv.compareAndSet(s, 0L, 1f, 2f) + "," + s.get(JAVA_FLOAT, 0); });
    row("vh float add refused", () -> { var s = MemorySegment.ofArray(new float[]{1f}); return JAVA_FLOAT.varHandle().getAndAdd(s, 0L, 1f); });
    row("vh byte CAS refused", () -> JAVA_BYTE.varHandle().compareAndSet(MemorySegment.ofArray(new byte[1]), 0L, (byte)0, (byte)1));
    row("vh byte supported", () -> JAVA_BYTE.varHandle().isAccessModeSupported(VarHandle.AccessMode.GET_VOLATILE) + "," + JAVA_BYTE.varHandle().isAccessModeSupported(VarHandle.AccessMode.COMPARE_AND_SET));
    row("vh unaligned CAS misaligned", () -> JAVA_INT_UNALIGNED.varHandle().compareAndSet(MemorySegment.ofArray(new byte[8]), 1L, 0, 1));
    row("vh unaligned getVolatile refused", () -> JAVA_INT_UNALIGNED.varHandle().getVolatile(MemorySegment.ofArray(new byte[8]), 0L));
    row("vh unaligned supported", () -> JAVA_INT_UNALIGNED.varHandle().isAccessModeSupported(VarHandle.AccessMode.GET) + "," + JAVA_INT_UNALIGNED.varHandle().isAccessModeSupported(VarHandle.AccessMode.GET_OPAQUE));
    row("vh toMethodHandle", () -> { var s = MemorySegment.ofArray(new int[]{3}); return vh.toMethodHandle(VarHandle.AccessMode.GET).invoke(s, 0L); });
    var xh = POINTS.varHandle(MemoryLayout.PathElement.sequenceElement(), MemoryLayout.PathElement.groupElement("y"));
    row("path coords", () -> xh.coordinateTypes());
    row("path set get", () -> { var s = MemorySegment.ofArray(new int[8]); xh.set(s, 0L, 2L, 42); return Arrays.toString(s.toArray(JAVA_INT)); });
    row("path index oob", () -> xh.get(MemorySegment.ofArray(new int[8]), 0L, 4L));
    row("path small segment", () -> xh.get(MemorySegment.ofArray(new int[3]), 0L, 0L));
    row("arrayElementVarHandle", () -> { var s = MemorySegment.ofArray(new int[]{1,2,3}); return JAVA_INT.arrayElementVarHandle().get(s, 0L, 2L); });
    row("struct offset", () -> POINT.byteOffset(MemoryLayout.PathElement.groupElement("y")));
    row("allocateFrom ints", () -> { try (Arena a = Arena.ofConfined()) { return Arrays.toString(a.allocateFrom(JAVA_INT, 1, 2, 3).toArray(JAVA_INT)); } });
    row("string utf8", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocateFrom("héllo"); return s.byteSize() + " " + s.getString(0); } });
    row("setString", () -> { var s = MemorySegment.ofArray(new byte[8]); s.setString(0, "abc"); return Arrays.toString(s.toArray(JAVA_BYTE)); });
    row("asSlice get", () -> MemorySegment.ofArray(new int[]{1,2,3,4}).asSlice(8).get(JAVA_INT, 4));
    row("mismatch", () -> MemorySegment.ofArray(new int[]{1,2,3}).mismatch(MemorySegment.ofArray(new int[]{1,2,4})));
    row("elements", () -> MemorySegment.ofArray(new int[]{1,2,3}).elements(JAVA_INT).mapToInt(e -> e.get(JAVA_INT, 0)).sum());
    row("auto arena", () -> { var s = Arena.ofAuto().allocate(JAVA_INT); s.set(JAVA_INT, 0, 11); return s.get(JAVA_INT, 0); });
    row("shared arena", () -> { try (Arena a = Arena.ofShared()) { var s = a.allocate(JAVA_DOUBLE); s.set(JAVA_DOUBLE, 0, 0.5); return s.get(JAVA_DOUBLE, 0); } });
    row("other thread confined", () -> { try (Arena a = Arena.ofConfined()) { var s = a.allocate(4); Object[] r = new Object[1]; Thread t = new Thread(() -> { try { r[0] = s.get(JAVA_INT, 0); } catch (Throwable e) { r[0] = e.getClass().getName() + ": " + e.getMessage(); } }); t.start(); t.join(); return r[0]; } });
    row("shared closed by another thread", () -> { Arena ar = Arena.ofShared(); var s = ar.allocate(JAVA_INT); s.set(JAVA_INT, 0, 3); Thread t = new Thread(ar::close); t.start(); t.join(); return s.get(JAVA_INT, 0); });
    row("shared mismatch", () -> { try (Arena ar = Arena.ofShared()) { var x = ar.allocate(64); var y = ar.allocate(64); x.fill((byte) 1); y.fill((byte) 1); y.set(JAVA_BYTE, 41, (byte) 2); return x.mismatch(y) + " " + x.asSlice(0, 40).mismatch(y.asSlice(0, 40)); } });
    row("closed mismatch", () -> { MemorySegment x; try (Arena ar = Arena.ofShared()) { x = ar.allocate(8); } return x.mismatch(MemorySegment.ofArray(new byte[8])); });
    row("double[] to native and back", () -> { try (Arena ar = Arena.ofConfined()) { var n = ar.allocate(JAVA_DOUBLE, 3); MemorySegment.copy(new double[]{1.5, -0.0, Double.NaN}, 0, n, JAVA_DOUBLE, 0, 3); double[] d = new double[3]; MemorySegment.copy(n, JAVA_DOUBLE, 0, d, 0, 3); return Arrays.toString(d) + " " + n.get(JAVA_LONG, 8); } });
    row("float[] to native and back", () -> { try (Arena ar = Arena.ofConfined()) { var f = ar.allocate(JAVA_FLOAT, 2); MemorySegment.copy(new float[]{3.25f, Float.MIN_VALUE}, 0, f, JAVA_FLOAT, 0, 2); float[] g = new float[2]; MemorySegment.copy(f, JAVA_FLOAT, 0, g, 0, 2); return Arrays.toString(g) + " " + f.get(JAVA_INT, 0); } });
    row("direct DoubleBuffer", () -> { var bb = java.nio.ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()); bb.asDoubleBuffer().put(new double[]{2.5, 7}); double[] e = new double[2]; bb.asDoubleBuffer().get(e); return Arrays.toString(e); });
    row("double[] copy swapped", () -> { double[] be = new double[2]; MemorySegment.copy(MemorySegment.ofArray(new double[]{1, 2}), JAVA_DOUBLE.withOrder(ByteOrder.BIG_ENDIAN), 0, be, 0, 2); return Arrays.toString(be); });
    row("long bits of a double[]", () -> MemorySegment.ofArray(new double[]{1.0}).get(JAVA_LONG, 0));
    row("double from a long[]", () -> MemorySegment.ofArray(new long[]{0x4000000000000000L}).get(JAVA_DOUBLE, 0));
    row("heap address", () -> MemorySegment.ofArray(new int[2]).asSlice(4).address());
    row("ofBuffer", () -> MemorySegment.ofBuffer(java.nio.ByteBuffer.wrap(new byte[]{0,0,0,5}).order(ByteOrder.BIG_ENDIAN)).get(JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), 0));
    row("asByteBuffer", () -> MemorySegment.ofArray(new byte[]{1,2,3,4}).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).getInt());
  }
}
