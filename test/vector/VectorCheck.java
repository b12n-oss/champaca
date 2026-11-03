import java.util.Arrays;
import jdk.incubator.vector.*;
import static jdk.incubator.vector.VectorOperators.*;

// The Vector API with --add-modules jdk.incubator.vector (VectorCheck.options).
// champaca answers the VM's questions (VectorSupport natives) and runs each
// operation through the JDK's Java fallback; every row is HotSpot's output,
// none depends on the machine's vector width.
// Not here: reinterpretAs*, castShape, viewAs*, test(IS_NAN) and the other
// operations that view lanes as another type, which go through heap
// MemorySegment access (ROADMAP 38o).
public class VectorCheck {
    static int row = 0;
    static void p(String what, Object v) {
        String s = v instanceof int[] a ? Arrays.toString(a) : v instanceof float[] a ? Arrays.toString(a)
                 : v instanceof long[] a ? Arrays.toString(a) : v instanceof double[] a ? Arrays.toString(a)
                 : v instanceof byte[] a ? Arrays.toString(a) : v instanceof short[] a ? Arrays.toString(a)
                 : v instanceof boolean[] a ? Arrays.toString(a) : String.valueOf(v);
        System.out.println(++row + " " + what + ": " + s);
    }
    static Object attempt(java.util.function.Supplier<Object> s) {
        try { return s.get(); } catch (Throwable t) { return t.getClass().getName() + ": " + t.getMessage(); }
    }

    public static void main(String[] args) {
        var I = IntVector.SPECIES_128; var L = LongVector.SPECIES_128; var F = FloatVector.SPECIES_128;
        var D = DoubleVector.SPECIES_128; var B = ByteVector.SPECIES_128; var S = ShortVector.SPECIES_128;
        p("module in the boot layer", ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent());
        // the widest vector is the machine's (128 bits on arm64, 256 or 512 on
        // x86_64), so these rows check how the sizes relate, not the size
        var max = IntVector.SPECIES_MAX;
        p("preferred is the max shape's size", VectorShape.preferredShape().vectorBitSize() == max.vectorBitSize()
            && IntVector.SPECIES_PREFERRED.length() == max.length());
        p("species", I + " " + L + " " + F + " " + D + " " + B + " " + S);
        // NEON's 128 bits on aarch64 (an SVE machine would differ); at least
        // that on x86_64
        p("width for the arch", System.getProperty("os.arch").equals("aarch64") ? max.vectorBitSize() == 128 : max.vectorBitSize() >= 128);
        p("max species lanes", max.length() * 32 == max.vectorBitSize() && max.length() >= 4
            && DoubleVector.SPECIES_MAX.length() * 2 == max.length());
        p("species 64 and 256", IntVector.SPECIES_64.length() + " " + IntVector.SPECIES_256.length() + " " + IntVector.SPECIES_512.length());

        int[] a = {1, -2, 3, Integer.MAX_VALUE}, b = {10, 20, -30, 1};
        var va = IntVector.fromArray(I, a, 0); var vb = IntVector.fromArray(I, b, 0);
        p("int add (wraps)", va.add(vb).toArray());
        p("int mul, sub, neg", va.mul(vb) + " " + va.sub(vb) + " " + va.neg());
        p("int min, max, abs", va.min(vb) + " " + va.max(vb) + " " + va.abs());
        p("int and, or, xor, not", va.and(vb) + " " + va.or(vb) + " " + va.lanewise(XOR, vb) + " " + va.not());
        p("int shifts", va.lanewise(LSHL, 3) + " " + va.lanewise(ASHR, 1) + " " + va.lanewise(LSHR, 1) + " " + va.lanewise(ROL, 4));
        p("int div", attempt(() -> vb.div(va)));
        p("int div by zero", attempt(() -> va.div(IntVector.zero(I))));
        p("int reductions", va.reduceLanes(ADD) + " " + va.reduceLanes(MUL) + " " + va.reduceLanes(MIN) + " " + va.reduceLanes(MAX)
            + " " + va.reduceLanes(AND) + " " + va.reduceLanes(OR) + " " + va.reduceLanes(XOR));
        p("int bit counts", va.lanewise(BIT_COUNT) + " " + va.lanewise(LEADING_ZEROS_COUNT) + " " + va.lanewise(TRAILING_ZEROS_COUNT) + " " + va.lanewise(REVERSE_BYTES));
        var m = va.compare(LT, vb);
        p("compare LT", m + " " + m.trueCount() + " " + m.firstTrue() + " " + m.lastTrue() + " " + m.toLong());
        p("blend", va.blend(vb, m));
        p("masked add", va.add(vb, m));
        p("masked reduce", va.reduceLanes(ADD, m));
        p("unsigned compare", va.compare(ULT, vb));
        p("broadcast, iota", IntVector.broadcast(I, 7) + " " + I.iotaShuffle(1, 1, true).toVector());
        p("lane, withLane", va.lane(3) + " " + va.withLane(0, 99));
        p("rearrange", va.rearrange(VectorShuffle.fromValues(I, 3, 2, 1, 0)));
        p("slice, unslice", va.slice(1, vb) + " " + va.unslice(1, vb, 0));
        p("selectFrom", IntVector.fromArray(I, new int[]{3, 0, 0, 1}, 0).selectFrom(va));
        p("compress, expand", va.compress(m) + " " + va.expand(m));
        p("loopBound, indexInRange", I.loopBound(10) + " " + I.indexInRange(2, 5));
        p("fromArray past the end", attempt(() -> IntVector.fromArray(I, a, 1)));
        p("masked fromArray", IntVector.fromArray(I, a, 2, I.indexInRange(2, a.length)));
        int[] out = new int[6];
        va.intoArray(out, 1);
        p("intoArray", out);
        p("equals, hashCode", va.equals(IntVector.fromArray(I, a, 0)) + " " + (va.hashCode() == IntVector.fromArray(I, a, 0).hashCode()));

        p("convert I2F, I2L, I2B", va.convert(I2F, 0) + " " + va.convert(I2L, 0) + " " + va.convert(I2B, 0));

        long[] la = {Long.MIN_VALUE, -1, 1L << 40, 7};
        var vl = LongVector.fromArray(L, la, 0); var vl2 = LongVector.fromArray(L, la, 2);
        p("long add, mul", vl.add(vl2) + " " + vl.mul(vl2));
        p("long shifts", vl.lanewise(LSHR, 1) + " " + vl.lanewise(ASHR, 63));

        byte[] ba = new byte[16]; for (int i = 0; i < 16; i++) ba[i] = (byte) (i * 20 - 100);
        var vbt = ByteVector.fromArray(B, ba, 0);
        p("byte add (wraps), saturating", vbt.add(vbt) + " " + vbt.lanewise(SADD, vbt));
        p("byte reduce", vbt.reduceLanes(ADD) + " " + vbt.reduceLanes(UMAX));
        short[] sa = {1, -1, 300, Short.MIN_VALUE, 5, 6, 7, 8};
        var vs = ShortVector.fromArray(S, sa, 0);
        p("short mul, unsigned max", vs.mul(vs) + " " + vs.lanewise(UMAX, ShortVector.broadcast(S, (short) 2)));

        float[] fa = {0f, 1f, -2.5f, Float.NaN};
        var vf = FloatVector.fromArray(F, fa, 0);
        p("float add, div, sqrt", vf.add(1f) + " " + vf.div(0f) + " " + vf.lanewise(SQRT));
        p("float math", vf.lanewise(SIN) + " " + vf.lanewise(EXP) + " " + vf.lanewise(LOG) + " " + vf.lanewise(TANH));
        p("float fma, pow", vf.fma(vf, vf) + " " + vf.pow(2f));
        p("float compare with NaN", vf.compare(EQ, vf) + " " + vf.compare(LT, 1f));
        p("float min, max with NaN", vf.min(0.5f) + " " + vf.max(0.5f));
        p("float reduce", vf.reduceLanes(ADD) + " " + FloatVector.fromArray(F, fa, 0).withLane(3, 4f).reduceLanes(ADD));
        double[] da = {Math.PI, -0.0, 1e300, 2};
        var vd = DoubleVector.fromArray(D, da, 0);
        p("double mul, hypot, atan2", vd.mul(vd) + " " + vd.lanewise(HYPOT, vd) + " " + vd.lanewise(ATAN2, vd));
        p("double cbrt, log10, expm1", vd.lanewise(CBRT) + " " + vd.lanewise(LOG10) + " " + vd.lanewise(EXPM1));

        var mask = VectorMask.fromArray(I, new boolean[]{true, false, true, true}, 0);
        p("mask ops", mask.not() + " " + mask.and(m) + " " + mask.or(m) + " " + mask.anyTrue() + " " + mask.allTrue());
        p("mask toArray", mask.toArray());
        p("shuffle wrap and exceptions", VectorShuffle.fromValues(I, 5, -1, 2, 3).wrapIndexes().toVector() + " "
            + attempt(() -> va.rearrange(VectorShuffle.fromValues(I, 5, 0, 1, 2))));

        int[] big = new int[1003]; for (int i = 0; i < big.length; i++) big[i] = i * 7 - 3000;
        var sp = IntVector.SPECIES_PREFERRED; var acc = IntVector.zero(sp); int i = 0;
        for (; i < sp.loopBound(big.length); i += sp.length()) acc = acc.add(IntVector.fromArray(sp, big, i));
        int sum = acc.reduceLanes(ADD); for (; i < big.length; i++) sum += big[i];
        p("loop sum", sum);
    }
}
