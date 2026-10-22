package champaca.lib;

import java.util.List;

// Fields for examples/fields.clj to read and write from jolt.
public class Fields implements FieldConsts {
    public static int count;
    public static String label = "start";
    public static final int FIXED = 1;
    public static final StringBuilder log = new StringBuilder();

    public int i;
    public long l;
    public byte b;
    public short s;
    public char c;
    public double d;
    public float f;
    public boolean z;
    public String str;
    public Object obj;
    public List<Object> list;
    public int[] ints;
    public final int ro = 5;

    // what Java code sees after jolt stores into the fields
    public String describe() {
        return i + " " + l + " " + b + " " + s + " " + c + " " + d + " " + f + " " + z + " " + str + " "
            + obj + " " + list + " " + (ints == null ? "null" : ints.length + ":" + ints[0]);
    }
    public static String statics() { return count + " " + label; }
    public static int bump() { return ++count; }

    public static class Base {
        static { log.append("Base;"); }
        public static int shared = 7;
        public int hidden = 1;
    }
    public static class Sub extends Base {
        static { log.append("Sub;"); }
        public int hidden = 2;
        public int baseHidden() { return ((Base) this).hidden; }
    }
}

interface FieldConsts {
    int LIMIT = 10;
    List<String> NAMES = List.of("x", Fields.log.append("FieldConsts;").toString());
}
