import java.util.*;

// The Java side of examples/collections.clj: the same calls with the
// collections the jolt values become (LinkedHashMap, ArrayList, LinkedHashSet).
public class CollectionsCheck {
  static void p(String label, Object v) { System.out.println(label + " " + (v instanceof String ? "\"" + v + "\"" : v)); }

  public static void main(String[] args) {
    LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
    m.put("b", 2); m.put("a", 1); m.put("c", new ArrayList<>(List.of(1, 2)));
    p("treemap", new TreeMap<>(m).toString());
    p("max", Collections.max(new ArrayList<>(List.of(3, 9, 4))));
    p("frequency", Collections.frequency(new ArrayList<>(List.of(1, 2, 1, 1)), 1));
    p("join", String.join("-", new ArrayList<>(List.of("a", "b", "c"))));
    p("contains", new HashSet<>(new LinkedHashSet<>(List.of(1, 2, 3))).contains(2));
    LinkedHashMap<Object, Object> kv = new LinkedHashMap<>();
    kv.put("k", "v");
    p("copyOf", List.copyOf(new ArrayList<>(List.of(1, new ArrayList<>(List.of(2, 3)), kv))).toString());
    HashMap<Object, Object> h = new HashMap<>();
    LinkedHashMap<Object, Object> xy = new LinkedHashMap<>();
    xy.put("x", 1); xy.put("y", null);
    h.putAll(xy);
    System.out.println("putAll [" + h.get("x") + " " + h.containsKey("y") + " " + h.size() + "]");
    p("array-still", Arrays.toString(new int[] {1, 2, 3}));
  }
}
