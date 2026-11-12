import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.*;
import java.util.*;
public class Kry {
  public static class Point { int x, y; String label; List<Integer> tags = new ArrayList<>(); Point() {} Point(int x, int y, String l) { this.x = x; this.y = y; label = l; tags.add(x * y); } public String toString() { return label + "(" + x + "," + y + ")" + tags; } }
  public record Pair(String a, long b) {}
  public enum Color { RED, GREEN }
  public static void main(String[] args) {
    Kryo k = new Kryo();
    k.register(Point.class); k.register(ArrayList.class); k.register(Pair.class); k.register(Color.class);
    k.register(HashMap.class); k.register(int[].class);
    Output out = new Output(1024, -1);
    k.writeObject(out, new Point(3, 4, "p"));
    k.writeClassAndObject(out, new Pair("z", 1L << 40));
    k.writeObject(out, Color.GREEN);
    Map<String, Object> m = new HashMap<>(); m.put("k", new int[] {1, 2, 3});
    k.writeObject(out, m);
    byte[] bytes = out.toBytes();
    System.out.println("bytes " + bytes.length);
    Input in = new Input(bytes);
    System.out.println(k.readObject(in, Point.class));
    System.out.println(k.readClassAndObject(in));
    System.out.println(k.readObject(in, Color.class));
    System.out.println(Arrays.toString((int[]) k.readObject(in, HashMap.class).get("k")));
    Point copy = k.copy(new Point(5, 6, "c"));
    System.out.println(copy + " " + Arrays.toString(Arrays.copyOf(bytes, 12)));
  }
}
