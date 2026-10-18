import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.*;
public class Gs {
  static class Point { int x; int y; String label; Point(int x, int y, String l) { this.x = x; this.y = y; this.label = l; } }
  public static void main(String[] a) {
    JsonObject o = JsonParser.parseString("{\"name\":\"champaca\",\"n\":3,\"tags\":[\"a\",\"b\"]}").getAsJsonObject();
    System.out.println(o.get("name").getAsString() + " " + o.get("n").getAsInt() + " " + o.getAsJsonArray("tags").size());
    Gson g = new Gson();
    System.out.println(g.toJson(o));
    System.out.println(g.toJson(new Point(1, 2, "p")));
    Point p = g.fromJson("{\"x\":5,\"y\":6,\"label\":\"q\"}", Point.class);
    System.out.println(p.x + p.y + p.label);
    Map<String,Object> m = new TreeMap<>(); m.put("k", 1); m.put("list", List.of(1, 2));
    System.out.println(g.toJson(m));
    System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(List.of("x", "y")));
    System.out.println(new java.sql.Date(0L).getClass().getName());
  }
}
