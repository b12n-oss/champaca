import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
public class Jack {
  public static class Point {
    public int x; public int y;
    @JsonProperty("name") public String label;
    @JsonIgnore public String secret = "s";
    public Point() {}
    public Point(int x, int y, String l) { this.x = x; this.y = y; this.label = l; }
  }
  public static class Bean {
    private List<Point> points = new ArrayList<>();
    public List<Point> getPoints() { return points; }
    public void setPoints(List<Point> p) { points = p; }
  }
  // records bind through their components and canonical constructor
  public record Item(String sku, @JsonProperty("qty") int quantity, List<String> tags) {}
  public record Order(long id, Item first, List<Item> items, Map<String, Double> prices) {}
  public record Checked(int lo, int hi) {
    public Checked { if (lo > hi) throw new IllegalArgumentException("lo > hi"); }
  }
  public static void main(String[] a) throws Exception {
    ObjectMapper m = new ObjectMapper();
    JsonNode t = m.readTree("{\"a\":1,\"b\":[true,null,2.5],\"c\":{\"d\":\"e\"}}");
    System.out.println(t.get("b").get(2).asDouble() + " " + t.at("/c/d").asText() + " " + t);
    System.out.println(m.writeValueAsString(new TreeMap<>(Map.of("k", 1, "l", List.of(1, 2)))));
    System.out.println(m.writeValueAsString(new Point(1, 2, "p")));
    Point p = m.readValue("{\"x\":5,\"y\":6,\"name\":\"q\"}", Point.class);
    System.out.println(p.x + p.y + p.label);
    Bean b = m.readValue("{\"points\":[{\"x\":1,\"y\":2,\"name\":\"a\"}]}", Bean.class);
    System.out.println(b.getPoints().get(0).label + " " + b.getPoints().get(0).getClass().getSimpleName());
    System.out.println(m.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(List.of("x")));
    Item it = new Item("a-1", 3, List.of("x", "y"));
    String js = m.writeValueAsString(it);
    System.out.println(js + " " + m.readValue(js, Item.class).equals(it));
    Order o = m.readValue("{\"id\":7,\"first\":{\"sku\":\"b\",\"qty\":1,\"tags\":[]},"
        + "\"items\":[{\"sku\":\"c\",\"qty\":2,\"tags\":[\"z\"]}],\"prices\":{\"c\":1.5}}", Order.class);
    System.out.println(o + " " + m.writeValueAsString(o));
    try {
      m.readValue("{\"lo\":5,\"hi\":2}", Checked.class);
    } catch (Exception e) {
      System.out.println(e.getClass().getSimpleName() + " " + (e.getCause() == null ? "" : e.getCause().getMessage()));
    }
  }
}
