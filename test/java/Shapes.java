interface Shape { int area(); String name(); }

abstract class Base implements Shape {
    static int created;
    protected final int id;
    Base() { created++; id = created; }
    public String name() { return "shape#" + id; }
}

class Rect extends Base {
    int w, h;
    Rect(int w, int h) { super(); this.w = w; this.h = h; }
    public int area() { return w * h; }
}

class Square extends Rect {
    Square(int s) { super(s, s); }
    @Override public String name() { return "square(" + w + ")#" + id; }
}

class TooBig extends RuntimeException {
    final int size;
    TooBig(int size) { super("too big"); this.size = size; }
}

public class Shapes {
    static int checked(Shape s) {
        int a = s.area();
        if (a > 50) throw new TooBig(a);
        return a;
    }
    public static void main(String[] args) {
        Shape[] shapes = { new Rect(2, 3), new Square(4), new Square(9) };
        int total = 0;
        for (Shape s : shapes) {
            try {
                total += checked(s);
                System.out.println(s.name() + " area=" + s.area());
            } catch (TooBig e) {
                System.out.println("skipped " + s.name() + ": " + e.size);
            }
        }
        System.out.println("total=" + total + " created=" + Base.created);
        try {
            System.out.println(10 / (total - total));
        } catch (ArithmeticException e) {
            System.out.println("caught ArithmeticException");
        }
    }
}
