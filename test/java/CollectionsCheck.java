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
    // collectors: a list that grows past its first array, parallel, grouped,
    // into a chosen collection, and finished (once reported as an NPE on
    // ArrayList's elementData, not seen since)
    p("toList grows", java.util.stream.IntStream.range(0, 50).boxed().collect(java.util.stream.Collectors.toList()).size());
    p("toList parallel", java.util.stream.IntStream.range(0, 1000).parallel().boxed()
        .collect(java.util.stream.Collectors.toList()).stream().mapToInt(Integer::intValue).sum());
    p("groupingBy", new TreeMap<>(java.util.stream.Stream.of("a", "bb", "cc", "ddd")
        .collect(java.util.stream.Collectors.groupingBy(String::length))));
    p("toCollection", java.util.stream.Stream.of(3, 1, 2).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
    p("collectingAndThen", java.util.stream.Stream.of(1, 2).collect(
        java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), Collections::unmodifiableList)));
    p("Stream.toList", java.util.stream.Stream.of("x", "y").filter(z -> !z.isEmpty()).toList());
  }
}
