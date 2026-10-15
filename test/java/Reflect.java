import java.lang.reflect.*;
import java.util.*;

interface Named { String name(); }

class Widget implements Named {
    public static int made;
    private int size;
    public final String label;
    public Widget(int size, String label) { this.size = size; this.label = label; made++; }
    private Widget() { this(0, "default"); }
    public String name() { return label + "#" + size; }
    public int grow(int by) { size += by; return size; }
    public static long twice(long x) { return x * 2; }
    public void boom() { throw new IllegalStateException("boom from " + label); }
}

enum Color { RED, GREEN, BLUE }

public class Reflect {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("Widget");
        System.out.println(c.getName() + " super=" + c.getSuperclass().getName()
            + " interfaces=" + Arrays.toString(c.getInterfaces()) + " " + Modifier.toString(c.getModifiers()));

        List<String> ms = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) ms.add(m.getName() + "/" + m.getParameterCount() + ":" + m.getReturnType().getName());
        Collections.sort(ms);
        System.out.println(ms);
        System.out.println(c.getDeclaredConstructors().length + " constructors, " + c.getConstructors().length + " public");

        Object w = c.getConstructor(int.class, String.class).newInstance(3, "w");
        System.out.println(c.getMethod("name").invoke(w));
        System.out.println(c.getMethod("grow", int.class).invoke(w, 4));
        System.out.println(c.getMethod("twice", long.class).invoke(null, 21L));

        Field size = c.getDeclaredField("size");
        size.setAccessible(true);
        System.out.println("size=" + size.getInt(w));
        size.setInt(w, 42);
        System.out.println(((Named) w).name() + " " + size.get(w));
        System.out.println("made=" + c.getField("made").get(null) + " label=" + c.getField("label").get(w));

        try {
            c.getMethod("boom").invoke(w);
        } catch (InvocationTargetException e) {
            System.out.println("ITE cause: " + e.getCause());
        }
        try {
            c.getMethod("nope");
        } catch (NoSuchMethodException e) {
            System.out.println("NoSuchMethodException " + e.getMessage());
        }

        Constructor<?> hidden = c.getDeclaredConstructor();
        hidden.setAccessible(true);
        System.out.println(((Named) hidden.newInstance()).name());

        EnumMap<Color, Integer> em = new EnumMap<>(Color.class);
        em.put(Color.BLUE, 3); em.put(Color.RED, 1);
        System.out.println(em + " " + Color.valueOf("GREEN").ordinal());
    }
}
