import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.util.Random;

// Every long operation on values each side of the 60-bit fixnum edge, the
// 32-bit halves' edges and the long range's ends, each operation's results
// hashed on a line of its own; then lcmp in each branch form, lcmp in the
// shapes javac doesn't emit (LongCmp, from test/gen/LongCmpGen.java), and
// the JDK code built on long arithmetic (Random, BigInteger, the unsigned
// and exact helpers).
public class LongOpsCheck {
    static final long F = 1L << 60;
    static long[] vals;
    static long h(long acc, long v) { return acc * 1000003L ^ v; }

    public static void main(String[] args) throws Throwable {
        long[] edges = {0, 1, -1, 2, -2, 3, 0x7fffffffL, 0x80000000L, 0xffffffffL, 0x100000000L, -0x80000000L,
            -0x80000001L, -0x100000000L, 0x3fffffffL, 0x40000000L, -0x40000000L, -0x40000001L,
            F / 2 - 1, F / 2, -F / 2, F - 1, F, -F, -F - 1, 2 * F - 1, 2 * F, -4 * F,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1,
            0x123456789abcdefL, -0x123456789abcdefL, 0x5DEECE66DL, 0x9E3779B97F4A7C15L, 0xff51afd7ed558ccdL};
        vals = new long[edges.length + 60];
        System.arraycopy(edges, 0, vals, 0, edges.length);
        long seed = 12345;
        for (int k = 0; k < 60; k++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            vals[edges.length + k] = seed >> (k % 4 * 13);
        }
        long add = 0, sub = 0, mul = 0, div = 0, rem = 0, and = 0, or = 0, xor = 0, cmp = 0;
        for (long a : vals) for (long b : vals) {
            add = h(add, a + b); sub = h(sub, a - b); mul = h(mul, a * b);
            and = h(and, a & b); or = h(or, a | b); xor = h(xor, a ^ b);
            if (b != 0) { div = h(div, a / b); rem = h(rem, a % b); }
            cmp = h(cmp, Long.compare(a, b));
        }
        System.out.println("add " + add); System.out.println("sub " + sub); System.out.println("mul " + mul);
        System.out.println("div " + div); System.out.println("rem " + rem); System.out.println("and " + and);
        System.out.println("or " + or); System.out.println("xor " + xor); System.out.println("compare " + cmp);
        long shl = 0, shr = 0, ushr = 0, neg = 0, l2i = 0, l2d = 0;
        for (long a : vals) {
            for (int n = -70; n < 70; n++) { shl = h(shl, a << n); shr = h(shr, a >> n); ushr = h(ushr, a >>> n); }
            neg = h(neg, -a); l2i = h(l2i, (int) a); l2d = h(l2d, Double.doubleToLongBits((double) a) ^ Float.floatToIntBits((float) a));
        }
        System.out.println("shl " + shl); System.out.println("shr " + shr); System.out.println("ushr " + ushr);
        System.out.println("neg " + neg); System.out.println("l2i " + l2i); System.out.println("l2d " + l2d);
        System.out.println("min/-1 " + (Long.MIN_VALUE / div(-1)) + " " + (Long.MIN_VALUE % div(-1)) + " " + -Long.MIN_VALUE
            + " " + (Long.MAX_VALUE + 1) + " " + (Long.MIN_VALUE - 1) + " " + (Long.MIN_VALUE * -1) + " " + (F * 8) + " " + (F + F));
        // printed as they are: a value outside the range hashes the same once
        // the next operation reads its 64 bits
        StringBuilder negs = new StringBuilder("neg edges");
        for (int k = 27; k < 31; k++) negs.append(' ').append(-vals[k]).append(' ').append(vals[k] * -1).append(' ').append(vals[k] / div(-1));
        System.out.println(negs);
        try { System.out.println(1L / div(0)); } catch (ArithmeticException e) { System.out.println("div0 " + e.getMessage()); }
        try { System.out.println(1L % div(0)); } catch (ArithmeticException e) { System.out.println("rem0 " + e.getMessage()); }
        // each comparison javac compiles to lcmp and a branch
        int lt = 0, le = 0, gt = 0, ge = 0, eq = 0, ne = 0;
        for (long a : vals) for (long b : vals) {
            if (a < b) lt++; if (a <= b) le++; if (a > b) gt++; if (a >= b) ge++; if (a == b) eq++; if (a != b) ne++;
        }
        System.out.println("branches " + lt + " " + le + " " + gt + " " + ge + " " + eq + " " + ne);
        MethodType jji = MethodType.methodType(int.class, long.class, long.class);
        Class<?> lc = Class.forName("LongCmp");
        MethodHandle cmpMh = MethodHandles.lookup().findStatic(lc, "cmp", jji);
        MethodHandle storedMh = MethodHandles.lookup().findStatic(lc, "stored", jji);
        MethodHandle joinedMh = MethodHandles.lookup().findStatic(lc, "joined", jji);
        MethodHandle halvesMh = MethodHandles.lookup().findStatic(lc, "halves", jji);
        long gen = 0;
        for (long a : vals) for (long b : vals) {
            gen = h(gen, (int) cmpMh.invokeExact(a, b));
            gen = h(gen, (int) storedMh.invokeExact(a, b));
            gen = h(gen, (int) joinedMh.invokeExact(a, b));
            gen = h(gen, (int) halvesMh.invokeExact(a, b));
        }
        System.out.println("lcmp shapes " + gen);
        Random r = new Random(42);
        long rs = 0;
        for (int k = 0; k < 2000; k++) rs = h(rs, r.nextLong() ^ r.nextInt() ^ Double.doubleToLongBits(r.nextDouble()));
        System.out.println("random " + rs);
        BigInteger x = new BigInteger("123456789012345678901234567890123456789"), p = BigInteger.ONE;
        for (int k = 0; k < 40; k++) p = p.multiply(x).add(BigInteger.valueOf(k)).mod(x.add(BigInteger.TWO.pow(200)));
        System.out.println("biginteger " + p + " " + p.toString(16).length() + " " + x.pow(5).divide(x.pow(2).subtract(BigInteger.TEN)));
        long un = 0;
        for (long a : vals) for (long b : vals) {
            un = h(un, Long.compareUnsigned(a, b));
            if (b != 0) { un = h(un, Long.divideUnsigned(a, b)); un = h(un, Long.remainderUnsigned(a, b)); }
            un = h(un, Math.multiplyHigh(a, b)); un = h(un, Math.unsignedMultiplyHigh(a, b));
        }
        System.out.println("unsigned " + un);
        long bits = 0;
        for (long a : vals) {
            bits = h(bits, Long.numberOfLeadingZeros(a) + 64L * Long.numberOfTrailingZeros(a) + 4096L * Long.bitCount(a));
            bits = h(bits, Long.reverse(a) ^ Long.reverseBytes(a) ^ Long.rotateLeft(a, 13) ^ Long.rotateRight(a, 7) ^ Long.hashCode(a));
            bits = h(bits, Long.highestOneBit(a) ^ Long.lowestOneBit(a) ^ Long.signum(a));
        }
        System.out.println("bits " + bits + " " + Long.toHexString(vals[40]) + " " + Long.toUnsignedString(-1L) + " " + Long.parseLong("-9223372036854775808"));
        int exact = 0;
        for (long a : vals) for (long b : vals) {
            try { Math.addExact(a, b); } catch (ArithmeticException e) { exact++; }
            try { Math.multiplyExact(a, b); } catch (ArithmeticException e) { exact += 1000; }
        }
        System.out.println("exact " + exact);
    }
    static long div(long d) { return d; }
}
