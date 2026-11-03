import java.util.List;

// the first class is the one run; the others in the file are compiled with it
public class Multi {
    public static void main(String[] args) {
        Shape s = new Circle(2);
        System.out.println(s.describe());
        System.out.println(List.of(new Point(1, 2), new Point(3, 4)));
        System.out.println(Helper.twice("ab"));
    }
}

interface Shape { String describe(); }

record Circle(int r) implements Shape {
    public String describe() { return "circle " + r; }
}

record Point(int x, int y) { }

class Helper {
    static String twice(String s) { return s + s; }
}
