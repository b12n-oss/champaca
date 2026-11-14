import java.io.Serializable;
import java.util.*;

// instanceof and checkcast on arrays: every array kind against every target,
// three times over from the same sites, then in reverse order, so an answer
// remembered for one array type never leaks to another. Failed casts print
// HotSpot's message up to its module clause.
public class ArrayTypeCheck {
    interface Shape {}
    static class Sq implements Shape, Comparable<Sq> { public int compareTo(Sq o) { return 0; } }

    static String tests(Object x) {
        StringBuilder sb = new StringBuilder();
        sb.append(x instanceof Object[] ? 'O' : '-');
        sb.append(x instanceof Comparable[] ? 'C' : '-');
        sb.append(x instanceof Serializable[] ? 'S' : '-');
        sb.append(x instanceof Shape[] ? 'H' : '-');
        sb.append(x instanceof Sq[] ? 'Q' : '-');
        sb.append(x instanceof Object[][] ? '2' : '-');
        sb.append(x instanceof int[] ? 'i' : '-');
        sb.append(x instanceof long[] ? 'l' : '-');
        sb.append(x instanceof Cloneable ? 'K' : '-');
        sb.append(x instanceof Serializable ? 's' : '-');
        sb.append(x instanceof Number[] ? 'N' : '-');
        return sb.toString();
    }

    static String cast(Object x) {
        StringBuilder sb = new StringBuilder();
        try { Object[] a = (Object[]) x; sb.append("Object[] ok"); }
        catch (ClassCastException e) { sb.append(msg(e)); }
        sb.append(" / ");
        try { Shape[] a = (Shape[]) x; sb.append("Shape[] ok"); }
        catch (ClassCastException e) { sb.append(msg(e)); }
        return sb.toString();
    }

    static String msg(ClassCastException e) { return "CCE " + e.getMessage().replaceAll(" \\(.*", ""); }

    static Object[] cloneCast(Object[] a) { return (Object[]) a.clone(); }

    public static void main(String[] args) {
        Object[] xs = { new Object[1], new String[1], new Integer[1], new Sq[1], new Shape[1],
                        new Object[1][1], new String[1][1], new int[1], new long[1], new int[1][1],
                        new Comparable[1], new Number[1], "not an array", 7, null };
        String[] names = { "Object[]", "String[]", "Integer[]", "Sq[]", "Shape[]", "Object[][]", "String[][]",
                           "int[]", "long[]", "int[][]", "Comparable[]", "Number[]", "String", "Integer", "null" };
        for (int pass = 0; pass < 3; pass++) {
            StringBuilder line = new StringBuilder("pass " + pass + ":");
            for (int i = 0; i < xs.length; i++) line.append(' ').append(names[i]).append('=').append(tests(xs[i]));
            System.out.println(line);
        }
        StringBuilder line = new StringBuilder("reversed:");
        for (int i = xs.length - 1; i >= 0; i--) line.append(' ').append(names[i]).append('=').append(tests(xs[i]));
        System.out.println(line);
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < xs.length; i++) System.out.println("cast " + pass + " " + names[i] + ": " + cast(xs[i]));
        // a clone keeps its runtime type: a String[] clone is still no Integer[]
        Object[] c = cloneCast(new String[] { "a" });
        System.out.println("clone of String[]: " + c.getClass().getSimpleName() + " " + (c instanceof String[]) + " " + (c instanceof Integer[]));
        try { c[0] = 1; System.out.println("stored an Integer"); }
        catch (ArrayStoreException e) { System.out.println("ArrayStoreException " + e.getMessage()); }
    }
}
