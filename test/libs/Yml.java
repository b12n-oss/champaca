import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.*;
import java.util.*;

public class Yml {
    public static class Point {
        private int x; private int y;
        public Point() {}
        public Point(int x, int y) { this.x = x; this.y = y; }
        public int getX() { return x; } public void setX(int x) { this.x = x; }
        public int getY() { return y; } public void setY(int y) { this.y = y; }
        public String toString() { return "Point(" + x + "," + y + ")"; }
    }
    public static class Shape {
        private String name; private List<Point> points = new ArrayList<>();
        private Map<String, Integer> tags = new LinkedHashMap<>();
        private boolean closed; private double weight; private Long id;
        public String getName() { return name; } public void setName(String n) { name = n; }
        public List<Point> getPoints() { return points; } public void setPoints(List<Point> p) { points = p; }
        public Map<String, Integer> getTags() { return tags; } public void setTags(Map<String, Integer> t) { tags = t; }
        public boolean isClosed() { return closed; } public void setClosed(boolean c) { closed = c; }
        public double getWeight() { return weight; } public void setWeight(double w) { weight = w; }
        public Long getId() { return id; } public void setId(Long i) { id = i; }
        public String toString() { return "Shape"; }
    }
    public enum Color { RED, GREEN }
    public static class Paint { public Color color; public int[] rgb; public String toString() { return "Paint"; } }

    static void t(String label, java.util.concurrent.Callable<Object> c) {
        try { System.out.println(label + ": " + c.call()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + ": " + e.getMessage()); }
    }

    public static void main(String[] args) {
        t("map", () -> new Yaml().load("a: 1\nb: [x, y]\nc: {d: true, e: null}\nf: 2.5\ng: 12345678901234567890\n"));
        t("types", () -> { Map<String, Object> m = new Yaml().load("i: 1\nl: 9999999999\nbig: 12345678901234567890\nd: 1.5\nb: yes\ns: '1'\nn: ~\n");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName()).append(' ');
            return sb.toString().trim(); });
        t("anchors", () -> new Yaml().load("base: &b {x: 1, y: 2}\nother: *b\nmerged:\n  <<: *b\n  z: 3\n"));
        t("loadAll", () -> { List<Object> out = new ArrayList<>(); for (Object o : new Yaml().loadAll("--- 1\n--- [2, 3]\n--- {k: v}\n")) out.add(o); return out; });
        t("dump-map", () -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("name", "x"); m.put("list", List.of(1, 2, 3)); m.put("nested", Map.of("k", "v")); return "\n" + new Yaml().dump(m); });
        t("dump-block", () -> { DumperOptions o = new DumperOptions(); o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); o.setIndent(4); o.setPrettyFlow(true);
            Map<String, Object> m = new LinkedHashMap<>(); m.put("list", List.of(1, 2)); m.put("map", new LinkedHashMap<>(Map.of("a", "b"))); m.put("multi", "line one\nline two\n");
            return "\n" + new Yaml(o).dump(m); });
        t("bean-load", () -> { LoaderOptions lo = new LoaderOptions(); Yaml y = new Yaml(new Constructor(Shape.class, lo));
            Shape s = y.load("name: tri\npoints:\n  - {x: 0, y: 0}\n  - {x: 3, y: 4}\ntags: {a: 1, b: 2}\nclosed: true\nweight: 2.5\nid: 42\n");
            return s.getName() + " " + s.getPoints() + " " + s.getTags() + " " + s.isClosed() + " " + s.getWeight() + " " + s.getId() + " " + s.getPoints().get(1).getClass().getSimpleName(); });
        t("bean-dump", () -> { Shape s = new Shape(); s.setName("sq"); s.getPoints().add(new Point(1, 2)); s.getTags().put("k", 7); s.setClosed(true); s.setWeight(1.25); s.setId(9L);
            return "\n" + new Yaml().dumpAsMap(s); });
        t("bean-tagged", () -> { Shape s = new Shape(); s.setName("t"); return "\n" + new Yaml().dump(s); });
        t("public-fields", () -> { LoaderOptions lo = new LoaderOptions(); Paint p = new Yaml(new Constructor(Paint.class, lo)).load("color: GREEN\nrgb: [1, 2, 3]\n");
            return p.color + " " + Arrays.toString(p.rgb); });
        t("enum-bad", () -> { LoaderOptions lo = new LoaderOptions(); return new Yaml(new Constructor(Paint.class, lo)).load("color: BLUE\n"); });
        t("bad-yaml", () -> new Yaml().load("a: [1, 2\nb: 3\n"));
        t("unknown-prop", () -> { LoaderOptions lo = new LoaderOptions(); return new Yaml(new Constructor(Shape.class, lo)).load("nope: 1\n"); });
        t("global-tag-blocked", () -> new Yaml().load("!!java.util.Date 1\n"));
        t("roundtrip", () -> { Yaml y = new Yaml(); Object o = y.load("x: [1, {a: b}, 'q']\n"); return y.load(y.dump(o)).equals(o); });
        t("timestamp", () -> { Map<String, Object> m = new Yaml().load("t: 2001-12-14t21:59:43.10-05:00\n"); Object d = m.get("t"); return d.getClass().getName() + " " + ((Date) d).getTime(); });
    }
}
