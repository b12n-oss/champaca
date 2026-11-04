import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Path;
import java.util.Arrays;
import static java.lang.foreign.ValueLayout.*;

// java.lang.foreign downcalls against HotSpot: libc and libm through the
// default lookup, then target/libffitest (test/native/ffitest.c, which bb
// test builds) through SymbolLookup.libraryLookup and System.loadLibrary,
// with structs by value of each kind, variadic calls, errno capture,
// critical calls on heap arrays, and the linker's errors. A row named
// "right or refused" passes on a signature Chez Scheme misplaces on macOS
// arm64 (champaca refuses it) and checks the value elsewhere. The library's
// file name is masked to <lib>, identity hashes and native addresses too.
public class ForeignCheck {
  static int n = 0;
  interface Body { Object call() throws Throwable; }
  static void row(String name, Body c) {
    String r;
    try { Object o = c.call(); r = String.valueOf(o); }
    catch (Throwable t) { String m = t.getMessage(); r = t.getClass().getName() + (m == null ? "" : ": " + mask(m)); }
    System.out.println(++n + " " + name + ": " + mask(r));
  }
  static String mask(String m) { return m.replace(System.mapLibraryName("ffitest"), "<lib>").replaceAll("@[0-9a-f]+", "@H").replaceAll("0x[0-9a-f]{6,}", "0xADDR"); }
  static final Linker L = Linker.nativeLinker();
  static SymbolLookup lib;
  static MethodHandle h(String n, FunctionDescriptor fd, Linker.Option... o) { return L.downcallHandle(lib.findOrThrow(n), fd, o); }
  public static void main(String[] a) throws Throwable {
    SymbolLookup std = L.defaultLookup();
    row("linker", () -> L.getClass().getSimpleName().endsWith("Linker") && L == Linker.nativeLinker());
    row("strlen", () -> { var h = L.downcallHandle(std.findOrThrow("strlen"), FunctionDescriptor.of(JAVA_LONG, ADDRESS)); try (Arena ar = Arena.ofConfined()) { return (long) h.invokeExact(ar.allocateFrom("hello")); } });
    row("abs", () -> (int) L.downcallHandle(std.findOrThrow("abs"), FunctionDescriptor.of(JAVA_INT, JAVA_INT)).invokeExact(-7));
    row("labs long", () -> (long) L.downcallHandle(std.findOrThrow("labs"), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)).invokeExact(-(1L << 40)));
    row("getpid > 0", () -> (int) L.downcallHandle(std.findOrThrow("getpid"), FunctionDescriptor.of(JAVA_INT)).invokeExact() > 0);
    row("sqrt double", () -> (double) L.downcallHandle(std.findOrThrow("sqrt"), FunctionDescriptor.of(JAVA_DOUBLE, JAVA_DOUBLE)).invokeExact(2.0));
    row("sqrtf float", () -> (float) L.downcallHandle(std.findOrThrow("sqrtf"), FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT)).invokeExact(2.0f));
    row("toupper char-ish", () -> (int) L.downcallHandle(std.findOrThrow("toupper"), FunctionDescriptor.of(JAVA_INT, JAVA_INT)).invokeExact((int) 'q'));
    row("strcat into buffer", () -> { var h = L.downcallHandle(std.findOrThrow("strcat"), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)); try (Arena ar = Arena.ofConfined()) { var d = ar.allocate(32); d.setString(0, "ab"); var r = (MemorySegment) h.invokeExact(d, ar.allocateFrom("cd")); return d.getString(0) + " " + (r.address() == d.address()) + " " + r.byteSize(); } });
    row("snprintf variadic", () -> { var h = L.downcallHandle(std.findOrThrow("snprintf"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_DOUBLE, ADDRESS), Linker.Option.firstVariadicArg(3)); try (Arena ar = Arena.ofConfined()) { var b = ar.allocate(64); int n = (int) h.invokeExact(b, 64L, ar.allocateFrom("%d %.2f %s"), 42, 3.14159, ar.allocateFrom("x")); return n + " " + b.getString(0); } });
    row("void return", () -> { var h = L.downcallHandle(std.findOrThrow("memset"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG)); try (Arena ar = Arena.ofConfined()) { var s = ar.allocate(4); var r = (MemorySegment) h.invokeExact(s, 7, 4L); return s.get(JAVA_INT, 0); } });
    row("div struct return", () -> { var DIV = MemoryLayout.structLayout(JAVA_INT.withName("quot"), JAVA_INT.withName("rem")); var h = L.downcallHandle(std.findOrThrow("div"), FunctionDescriptor.of(DIV, JAVA_INT, JAVA_INT)); try (Arena ar = Arena.ofConfined()) { var r = (MemorySegment) h.invokeExact((SegmentAllocator) ar, 17, 5); return r.get(JAVA_INT, 0) + " " + r.get(JAVA_INT, 4) + " " + r.byteSize(); } });
    row("lldiv struct return", () -> { var D = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG); var h = L.downcallHandle(std.findOrThrow("lldiv"), FunctionDescriptor.of(D, JAVA_LONG, JAVA_LONG)); try (Arena ar = Arena.ofConfined()) { var r = (MemorySegment) h.invokeExact((SegmentAllocator) ar, -100L, 7L); return r.get(JAVA_LONG, 0) + " " + r.get(JAVA_LONG, 8); } });
    row("errno capture", () -> { var cs = Linker.Option.captureCallState("errno"); var h = L.downcallHandle(std.findOrThrow("open"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), cs); var csl = Linker.Option.captureStateLayout(); var eh = csl.varHandle(MemoryLayout.PathElement.groupElement("errno")); try (Arena ar = Arena.ofConfined()) { var st = ar.allocate(csl); int fd = (int) h.invokeExact(st, ar.allocateFrom("/nonexistent/x"), 0); return fd + " " + (int) eh.get(st, 0L); } });
    row("critical heap", () -> { var h = L.downcallHandle(std.findOrThrow("memchr"), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG), Linker.Option.critical(true)); byte[] b = "abcdef".getBytes(); var r = (MemorySegment) h.invokeExact(MemorySegment.ofArray(b), (int) 'd', 6L); return r.equals(MemorySegment.NULL); });
    row("heap not allowed", () -> { var h = L.downcallHandle(std.findOrThrow("strlen"), FunctionDescriptor.of(JAVA_LONG, ADDRESS)); return (long) h.invokeExact(MemorySegment.ofArray(new byte[]{65, 0})); });
    row("find missing", () -> std.find("no_such_function_xyz").isPresent());
    row("NULL symbol", () -> L.downcallHandle(MemorySegment.NULL, FunctionDescriptor.ofVoid()));
    row("handle type", () -> L.downcallHandle(std.findOrThrow("strlen"), FunctionDescriptor.of(JAVA_LONG, ADDRESS)).type());
    row("unbound type", () -> L.downcallHandle(FunctionDescriptor.of(JAVA_LONG, ADDRESS)).type());
    row("unbound call", () -> { var h = L.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_INT)); return (int) h.invokeExact(std.findOrThrow("abs"), -3); });
    row("libraryLookup missing", () -> { try (Arena ar = Arena.ofConfined()) { return SymbolLookup.libraryLookup("libnosuchlib_zz", ar).find("x"); } });
    row("libraryLookup libz", () -> { try (Arena ar = Arena.ofConfined()) { var z = SymbolLookup.libraryLookup(System.mapLibraryName("z"), ar); var h = L.downcallHandle(z.findOrThrow("zlibVersion"), FunctionDescriptor.of(ADDRESS)); return ((MemorySegment) h.invokeExact()).reinterpret(16).getString(0).isEmpty(); } });
    row("closed arg", () -> { MemorySegment s; try (Arena ar = Arena.ofConfined()) { s = ar.allocateFrom("x"); } return (long) L.downcallHandle(std.findOrThrow("strlen"), FunctionDescriptor.of(JAVA_LONG, ADDRESS)).invokeExact(s); });
    row("bad layout", () -> L.downcallHandle(std.findOrThrow("abs"), FunctionDescriptor.of(JAVA_INT, JAVA_INT.withByteAlignment(8))));
    row("canonical", () -> L.canonicalLayouts().get("size_t"));
    row("bool and byte", () -> (boolean) L.downcallHandle(std.findOrThrow("isalpha"), FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT)).invokeExact((int) 'x'));
    Arena ar = Arena.ofConfined();
    Path libPath = Path.of("target", System.mapLibraryName("ffitest")).toAbsolutePath();
    lib = SymbolLookup.libraryLookup(libPath, ar);
    var P2 = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));
    var F3 = MemoryLayout.structLayout(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    var D4 = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    var CD = MemoryLayout.structLayout(JAVA_BYTE, MemoryLayout.paddingLayout(7), JAVA_DOUBLE);
    var L3 = MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG);
    var N = MemoryLayout.structLayout(P2, MemoryLayout.sequenceLayout(3, JAVA_SHORT), MemoryLayout.paddingLayout(2));
    var U = MemoryLayout.unionLayout(JAVA_INT, JAVA_FLOAT);
    var B2 = MemoryLayout.structLayout(JAVA_BYTE, JAVA_BYTE);
    row("p2_add", () -> { var p = ar.allocate(P2); p.set(JAVA_INT, 0, 3); p.set(JAVA_INT, 4, 4); var q = ar.allocate(P2); q.set(JAVA_INT, 0, 10); q.set(JAVA_INT, 4, 20); var r = (MemorySegment) h("p2_add", FunctionDescriptor.of(P2, P2, P2)).invokeExact((SegmentAllocator) ar, p, q); return r.get(JAVA_INT, 0) + " " + r.get(JAVA_INT, 4) + " " + r.byteSize(); });
    row("p2 heap arg", () -> { var p = MemorySegment.ofArray(new int[]{1, 2}); var r = (MemorySegment) h("p2_add", FunctionDescriptor.of(P2, P2, P2)).invokeExact((SegmentAllocator) ar, p, p); return r.get(JAVA_INT, 0) + " " + r.get(JAVA_INT, 4); });
    row("p2 heap result", () -> { var p = MemorySegment.ofArray(new int[]{5, 6}); SegmentAllocator heapAlloc = (size, align) -> MemorySegment.ofArray(new int[(int) size / 4]); var r = (MemorySegment) h("p2_add", FunctionDescriptor.of(P2, P2, P2)).invokeExact(heapAlloc, p, p); return r.isNative() + " " + r.get(JAVA_INT, 0) + " " + r.get(JAVA_INT, 4); });
    row("p2 too small", () -> { var p = ar.allocate(4); return (MemorySegment) h("p2_add", FunctionDescriptor.of(P2, P2, P2)).invokeExact((SegmentAllocator) ar, p, p); });
    row("f3_sum hfa", () -> { var f = ar.allocate(F3); f.set(JAVA_FLOAT, 0, 1.5f); f.set(JAVA_FLOAT, 4, 2.25f); f.set(JAVA_FLOAT, 8, 4f); return (float) h("f3_sum", FunctionDescriptor.of(JAVA_FLOAT, F3)).invokeExact(f); });
    row("f3_scale", () -> { var f = ar.allocate(F3); f.set(JAVA_FLOAT, 0, 1f); f.set(JAVA_FLOAT, 4, 2f); f.set(JAVA_FLOAT, 8, 3f); var r = (MemorySegment) h("f3_scale", FunctionDescriptor.of(F3, F3, JAVA_FLOAT)).invokeExact((SegmentAllocator) ar, f, 0.5f); return r.get(JAVA_FLOAT, 0) + " " + r.get(JAVA_FLOAT, 4) + " " + r.get(JAVA_FLOAT, 8); });
    row("d4_rev big", () -> { var d = ar.allocate(D4); for (int i = 0; i < 4; i++) d.setAtIndex(JAVA_DOUBLE, i, i + 0.5); var r = (MemorySegment) h("d4_rev", FunctionDescriptor.of(D4, D4)).invokeExact((SegmentAllocator) ar, d); return r.getAtIndex(JAVA_DOUBLE, 0) + " " + r.getAtIndex(JAVA_DOUBLE, 3) + " " + d.getAtIndex(JAVA_DOUBLE, 0); });
    row("cd_sum padded", () -> { var v = ar.allocate(CD); v.set(JAVA_BYTE, 0, (byte) 7); v.set(JAVA_DOUBLE, 8, 0.25); return (double) h("cd_sum", FunctionDescriptor.of(JAVA_DOUBLE, CD)).invokeExact(v); });
    row("l3_mix", () -> { var v = ar.allocate(L3); v.setAtIndex(JAVA_LONG, 0, 1L << 33); v.setAtIndex(JAVA_LONG, 1, -5); v.setAtIndex(JAVA_LONG, 2, 0); var r = (MemorySegment) h("l3_mix", FunctionDescriptor.of(L3, JAVA_INT, L3, JAVA_DOUBLE)).invokeExact((SegmentAllocator) ar, 3, v, 9.9); return r.getAtIndex(JAVA_LONG, 0) + " " + r.getAtIndex(JAVA_LONG, 1) + " " + r.getAtIndex(JAVA_LONG, 2); });
    row("n_sum nested", () -> { var v = ar.allocate(N); v.set(JAVA_INT, 0, 1); v.set(JAVA_INT, 4, 2); v.set(JAVA_SHORT, 8, (short) 30); v.set(JAVA_SHORT, 10, (short) 400); v.set(JAVA_SHORT, 12, (short) 5000); return (int) h("n_sum", FunctionDescriptor.of(JAVA_INT, N)).invokeExact(v); });
    row("u_bits union", () -> { var v = ar.allocate(U); v.set(JAVA_FLOAT, 0, 1.0f); return Integer.toHexString((int) h("u_bits", FunctionDescriptor.of(JAVA_INT, U)).invokeExact(v)); });
    row("b2_swap", () -> { var v = ar.allocate(B2); v.set(JAVA_BYTE, 0, (byte) 1); v.set(JAVA_BYTE, 1, (byte) -2); var r = (MemorySegment) h("b2_swap", FunctionDescriptor.of(B2, B2)).invokeExact((SegmentAllocator) ar, v); return r.get(JAVA_BYTE, 0) + " " + r.get(JAVA_BYTE, 1); });
    row("many stack right or refused", () -> { try { var p = ar.allocate(P2); p.set(JAVA_INT, 0, 3); p.set(JAVA_INT, 4, 4); int r = (int) h("many", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, P2)).invokeExact(1, 2, 3, 4, 5, 6, 7, 8, 9, 10.7, p); return r == 4355 ? "ok" : "WRONG " + r; } catch (UnsupportedOperationException e) { return "ok"; } });
    var S1 = MemoryLayout.structLayout(JAVA_SHORT);
    var I8 = new MemoryLayout[]{JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT};
    row("v_pp", () -> { var p = ar.allocate(P2); p.set(JAVA_INT, 0, 3); p.set(JAVA_INT, 4, 4); var q = ar.allocate(P2); q.set(JAVA_INT, 0, 5); q.set(JAVA_INT, 4, 6); return (int) h("v_pp", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, P2, P2)).invokeExact(1, 2, 3, 4, 5, 6, 7, 8, p, q); });
    row("v_ip", () -> { var p = ar.allocate(P2); p.set(JAVA_INT, 0, 3); p.set(JAVA_INT, 4, 4); return (int) h("v_ip", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, P2)).invokeExact(1, 2, 3, 4, 5, 6, 7, 8, 9, 8, p); });
    row("v_sp right or refused", () -> { var p = ar.allocate(P2); p.set(JAVA_INT, 0, 3); p.set(JAVA_INT, 4, 4); try { int r = (int) h("v_sp", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_SHORT, P2)).invokeExact(1, 2, 3, 4, 5, 6, 7, 8, (short) 9, p); return r == 90034 ? "ok" : "WRONG " + r; } catch (UnsupportedOperationException e) { return "ok"; } });
    row("sum_va", () -> (int) h("sum_va", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(1)).invokeExact(3, 10, 20, 30));
    row("dsum_va", () -> (double) h("dsum_va", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE), Linker.Option.firstVariadicArg(1)).invokeExact(2, 1.25, 2.5));
    row("va float refused", () -> h("dsum_va", FunctionDescriptor.of(JAVA_DOUBLE, JAVA_INT, JAVA_FLOAT), Linker.Option.firstVariadicArg(1)));
    row("fill critical heap", () -> { int[] x = new int[5]; h("fill", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT), Linker.Option.critical(true)).invokeExact(MemorySegment.ofArray(x), 5); return java.util.Arrays.toString(x); });
    row("fill native", () -> { var s = ar.allocate(JAVA_INT, 4); h("fill", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)).invokeExact(s, 4); return s.getAtIndex(JAVA_INT, 3); });
    row("neg8", () -> (byte) h("neg8", FunctionDescriptor.of(JAVA_BYTE, JAVA_BYTE)).invokeExact((byte) -128));
    row("ch", () -> (int) (char) h("ch", FunctionDescriptor.of(JAVA_CHAR, JAVA_CHAR)).invokeExact((char) 0xffff));
    row("sh", () -> (short) h("sh", FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT)).invokeExact((short) 20000));
    row("is_odd", () -> (boolean) h("is_odd", FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT)).invokeExact(7));
    row("invoke boxed", () -> h("sh", FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT)).invoke((short) 3));
    row("null arg", () -> { MemorySegment n = null; return (int) h("n_sum", FunctionDescriptor.of(JAVA_INT, N)).invokeExact(n); });
    row("closed lib", () -> { MethodHandle m; try (Arena a2 = Arena.ofConfined()) { var l2 = SymbolLookup.libraryLookup(libPath, a2); m = L.downcallHandle(l2.findOrThrow("sh"), FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT)); } return (short) m.invokeExact((short) 1); });
    row("symbol by name missing", () -> lib.find("nope_x").isPresent());
    row("blocking call lets threads run", () -> {
      var nap = L.downcallHandle(SymbolLookup.libraryLookup(libPath, Arena.global()).findOrThrow("nap"), FunctionDescriptor.ofVoid(JAVA_INT));
      var started = new java.util.concurrent.CountDownLatch(1);
      Thread t = new Thread(() -> { try { started.countDown(); nap.invokeExact(500_000); } catch (Throwable e) { throw new RuntimeException(e); } });
      t.start(); started.await();
      int ticks = 0;
      while (t.isAlive()) { Thread.sleep(10); ticks++; }
      return ticks >= 10;
    });
    var PI = MemoryLayout.structLayout(JAVA_INT, MemoryLayout.paddingLayout(4), JAVA_INT);
    var C3 = MemoryLayout.structLayout(JAVA_BYTE, JAVA_BYTE, JAVA_BYTE);
    row("pad_sum extra padding", () -> { var v = ar.allocate(PI); v.set(JAVA_INT, 0, 4); v.set(JAVA_INT, 4, 99); v.set(JAVA_INT, 8, 2); return (int) h("pad_sum", FunctionDescriptor.of(JAVA_INT, PI)).invokeExact(v); });
    row("v_cs right or refused", () -> { var v = ar.allocate(C3); v.set(JAVA_BYTE, 0, (byte) 1); v.set(JAVA_BYTE, 1, (byte) 2); v.set(JAVA_BYTE, 2, (byte) 3); try { int r = (int) h("v_cs", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, C3, JAVA_SHORT)).invokeExact(1, 2, 3, 4, 5, 6, 7, 8, v, (short) 4); return r == 1234 ? "ok" : "WRONG " + r; } catch (UnsupportedOperationException e) { return "ok"; } });
    row("ch high", () -> (int) (char) h("ch", FunctionDescriptor.of(JAVA_CHAR, JAVA_CHAR)).invokeExact((char) 0x8000));
    row("unbound two symbols", () -> { var u = L.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_INT)); return (int) u.invokeExact(std.findOrThrow("abs"), -5) + " " + (int) u.invokeExact(std.findOrThrow("toupper"), (int) 'a') + " " + (int) u.invokeExact(std.findOrThrow("abs"), -6); });
    row("native access enabled", () -> ForeignCheck.class.getModule().isNativeAccessEnabled());
    // System.loadLibrary with -Djava.library.path=target (ForeignCheck.options),
    // then the loader's lookup
    row("loaderLookup before load", () -> SymbolLookup.loaderLookup().find("sh").isPresent());
    row("loadLibrary", () -> { System.loadLibrary("ffitest"); return "ok"; });
    row("loaderLookup after load", () -> (short) L.downcallHandle(SymbolLookup.loaderLookup().findOrThrow("sh"), FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT)).invokeExact((short) 21));
    row("loadLibrary again", () -> { System.loadLibrary("ffitest"); return "ok"; });
    row("loadLibrary missing", () -> { System.loadLibrary("nosuch_zz"); return "ok"; });
    row("load relative", () -> { System.load("target/" + System.mapLibraryName("ffitest")); return "ok"; });
    row("load absolute", () -> { System.load(libPath.toString()); return "ok"; });
    row("target from another thread", () -> {
      var sh = h("sh", FunctionDescriptor.of(JAVA_SHORT, JAVA_SHORT));
      Object[] out = new Object[1];
      Thread t = new Thread(() -> { try { out[0] = (short) sh.invokeExact((short) 1); } catch (Throwable e) { out[0] = e.getClass().getName() + ": " + e.getMessage(); } });
      t.start(); t.join();
      return out[0];
    });
  }
}
