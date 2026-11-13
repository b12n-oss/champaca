import java.util.*;
import java.util.function.*;

// One call site, many receiver classes. A site keeps the last class it saw,
// and a class keeps the methods found for it: the method chosen must be the
// right one for every class, in any order, on the first call and on every
// later one, for interface methods, defaults, overrides, abstract classes
// and lambdas.
public class PolyCallCheck {
    interface Shape { int sides(); default String label() { return "shape" + sides(); } }
    interface Named { String label(); }
    static class Tri implements Shape { public int sides() { return 3; } }
    static class Quad implements Shape, Named { public int sides() { return 4; } public String label() { return "quad"; } }
    static class Square extends Quad { public String label() { return "square"; } }
    static class Rhombus extends Quad { public int sides() { return 4; } }
    static abstract class Poly implements Shape { final int n; Poly(int n) { this.n = n; } public int sides() { return n; } abstract String kind(); public String label() { return kind() + n; } }
    static class Regular extends Poly { Regular(int n) { super(n); } String kind() { return "regular"; } }
    static class Star extends Poly { Star(int n) { super(n); } String kind() { return "star"; } public int sides() { return 2 * n; } }
    enum Unit implements Shape { DOT { public int sides() { return 0; } }, LINE { public int sides() { return 2; } public String label() { return "line"; } } }
    record Ngon(int sides) implements Shape {}

    static String call(Shape s) { return s.label() + ":" + s.sides(); }          // two sites
    static String viaObject(Object o) { return o.toString().length() + "/" + o.hashCode() % 2 + "/" + o.equals(o); }

    public static void main(String[] args) {
        Shape lambda = () -> 9;
        Shape[] shapes = {new Tri(), new Quad(), new Square(), new Rhombus(), new Regular(5), new Star(5), Unit.DOT, Unit.LINE,
                          new Ngon(7), lambda, new Shape() { public int sides() { return 1; } }, new Regular(6)};
        for (int pass = 0; pass < 3; pass++) {
            StringBuilder line = new StringBuilder("pass " + pass + ":");
            for (Shape s : shapes) line.append(' ').append(call(s));
            System.out.println(line);
        }
        StringBuilder back = new StringBuilder("reversed:");
        for (int i = shapes.length - 1; i >= 0; i--) back.append(' ').append(call(shapes[i]));
        System.out.println(back);
        // each class alone, repeatedly, then alternating pairs
        for (Shape s : shapes) { String first = call(s); for (int i = 0; i < 5; i++) if (!first.equals(call(s))) System.out.println("CHANGED " + first); }
        long sum = 0;
        for (int i = 0; i < 60000; i++) { Shape s = shapes[(i * 7) % shapes.length]; sum += s.sides() + s.label().length(); }
        System.out.println("sum " + sum);
        Object[] things = {"text", 42, 4.5, 'c', List.of(1, 2), new int[0].getClass(), Optional.empty(), new StringBuilder("sb"), Unit.DOT, 7L, Map.of()};
        StringBuilder objs = new StringBuilder("objects:");
        for (int pass = 0; pass < 2; pass++) for (Object o : things) objs.append(' ').append(viaObject(o).replaceAll("^\\d+/-?\\d", "n"));
        System.out.println(objs);
        List<Supplier<String>> suppliers = List.of(() -> "a", () -> "b", String::new, () -> call(new Tri()), new Supplier<>() { public String get() { return "anon"; } });
        StringBuilder got = new StringBuilder("suppliers:");
        for (int pass = 0; pass < 3; pass++) for (Supplier<String> s : suppliers) got.append(' ').append(s.get());
        System.out.println(got);
        Collection<?>[] colls = {new ArrayList<>(List.of(1)), new LinkedList<>(List.of(1, 2)), new HashSet<>(Set.of(3)), new TreeSet<>(Set.of(1, 2, 3)), new ArrayDeque<>(List.of(4)), List.of(), Set.of(5, 6)};
        int sizes = 0;
        for (int i = 0; i < 7000; i++) { Collection<?> c = colls[i % colls.length]; sizes += c.size() + (c.isEmpty() ? 1 : 0) + (c.iterator().hasNext() ? 2 : 0); }
        System.out.println("collections " + sizes);
    }
}
