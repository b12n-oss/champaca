import java.lang.invoke.*;
import java.lang.reflect.Array;

// Five behaviors upstream OpenLDK fixed that champaca had wrong too (ROADMAP
// 38b-f): a record's hashCode over float and double components (Float and
// Double.hashCode: canonical NaN, the double's two halves); ScopedValue's
// failure path, which rethrows through Unsafe.throwException; every dimension
// of a multi-dimensional array checked before any is made; a float or double
// VarHandle compare-and-set comparing bits, as the JVM does, so a NaN with
// another payload doesn't match; clone() of an object that isn't Cloneable.
public class SmallSemanticsCheck {
    record F(float f) { }
    record D(double d) { }
    record Mixed(int i, float f, double d, long l, boolean b) { }
    static class Plain implements Cloneable {
        int x = 7;
        Object copy() throws CloneNotSupportedException { return super.clone(); }
    }
    static class NotCloneable {
        Object copy() throws CloneNotSupportedException { return super.clone(); }
    }
    static float fv;
    static double dv;
    static final ScopedValue<String> NAME = ScopedValue.newInstance();

    public static void main(String[] args) throws Throwable {
        for (float f : new float[] {0f, -0f, 1.25f, Float.NaN, Float.intBitsToFloat(0x7fc01234), Float.POSITIVE_INFINITY, Float.MIN_VALUE})
            System.out.println("record F(" + Integer.toHexString(Float.floatToRawIntBits(f)) + ") " + new F(f).hashCode());
        for (double d : new double[] {0.0, -0.0, 1.25, Double.NaN, Double.longBitsToDouble(0x7ff8000000001234L), Double.NEGATIVE_INFINITY, 1e300})
            System.out.println("record D(" + Long.toHexString(Double.doubleToRawLongBits(d)) + ") " + new D(d).hashCode());
        System.out.println("record Mixed " + new Mixed(3, -2.5f, 7.75, -1L, true).hashCode() + " " + new Mixed(0, Float.NaN, Double.NaN, Long.MIN_VALUE, false).hashCode());

        try {
            ScopedValue.where(NAME, "x").run(() -> { throw new IllegalStateException("from the scope " + NAME.get()); });
        } catch (IllegalStateException e) { System.out.println("scoped value: " + e.getMessage()); }
        System.out.println("scoped value call: " + ScopedValue.where(NAME, "y").call(() -> NAME.get()));

        for (int[] dims : new int[][] {{0, -1}, {-1, 0}, {2, -3}, {0, 0, -2}}) {
            try { Array.newInstance(int.class, dims); System.out.println("newInstance made it"); }
            catch (Throwable e) { System.out.println("newInstance " + java.util.Arrays.toString(dims) + ": " + e.getClass().getName() + ": " + e.getMessage()); }
        }
        int z = args.length, m = z - 1;
        try { int[][] a = new int[z][m]; System.out.println("multianewarray made it " + a.length); }
        catch (NegativeArraySizeException e) { System.out.println("multianewarray [0][-1]: " + e.getMessage()); }
        try { int[][][] a = new int[2][z][m]; System.out.println("multianewarray made it"); }
        catch (NegativeArraySizeException e) { System.out.println("multianewarray [2][0][-1]: " + e.getMessage()); }
        for (int n : new int[] {255, 256}) {
            try { Object a = Array.newInstance(int.class, new int[n]); System.out.println("dimensions " + n + ": " + a.getClass().getName().length()); }
            catch (Throwable e) { System.out.println("dimensions " + n + ": " + e.getClass().getName() + ": " + e.getMessage()); }
        }
        try { Array.newInstance(int.class, new int[0]); } catch (Throwable e) { System.out.println("no dimensions: " + e.getClass().getName() + ": " + e.getMessage()); }
        try { Array.newInstance(void.class, 1); System.out.println("void array made"); } catch (Throwable e) { System.out.println("void array: " + e.getClass().getName() + ": " + e.getMessage()); }
        try { Array.newInstance(void.class, new int[] {1, 2}); System.out.println("void arrays made"); } catch (Throwable e) { System.out.println("void arrays: " + e.getClass().getName() + ": " + e.getMessage()); }

        VarHandle F_ = MethodHandles.lookup().findStaticVarHandle(SmallSemanticsCheck.class, "fv", float.class);
        VarHandle D_ = MethodHandles.lookup().findStaticVarHandle(SmallSemanticsCheck.class, "dv", double.class);
        fv = Float.intBitsToFloat(0x7fc01234);
        System.out.println("cas float other NaN: " + F_.compareAndSet(Float.intBitsToFloat(0x7fc05678), 3f) + " same NaN: " + F_.compareAndSet(Float.intBitsToFloat(0x7fc01234), 3f) + " now " + fv);
        fv = -0f;
        System.out.println("cas float 0 vs -0: " + F_.compareAndSet(0f, 1f) + " " + F_.compareAndSet(-0f, 2f) + " now " + fv);
        dv = Double.longBitsToDouble(0x7ff8000000001234L);
        System.out.println("cas double other NaN: " + D_.compareAndSet(Double.NaN, 4.0) + " exchange " + Long.toHexString(Double.doubleToRawLongBits((double) D_.compareAndExchange(Double.NaN, 5.0))) + " now " + Long.toHexString(Double.doubleToRawLongBits(dv)));
        float[] fa = {Float.intBitsToFloat(0x7fc0abcd)};
        VarHandle FA = MethodHandles.arrayElementVarHandle(float[].class);
        System.out.println("cas float[] other NaN: " + FA.compareAndSet(fa, 0, Float.NaN, 1f) + " weak same: " + FA.weakCompareAndSet(fa, 0, Float.intBitsToFloat(0x7fc0abcd), 1f));

        Plain p = new Plain();
        System.out.println("clone Cloneable: " + ((Plain) p.copy()).x);
        try { new NotCloneable().copy(); System.out.println("clone of a non-Cloneable made it"); }
        catch (CloneNotSupportedException e) { System.out.println("clone non-Cloneable: " + e.getMessage()); }
        int[] arr = {1, 2};
        System.out.println("clone array: " + arr.clone()[1]);
    }
}
