import java.util.*;
import java.util.stream.*;

// The JVM's answers for examples/dynamic.clj: same calls, same labels.
public class DynamicCheck {
    static void p(String label, Object v) { System.out.println(label + " " + v); }
    public static void main(String[] args) {
        p("max-int", Math.max(3, 7));
        p("max-mixed", Math.max(3, 7.5));
        p("max-long", Math.max(1, 5000000000L));
        p("abs", Math.abs(-4) + " " + Math.abs(-2.5));
        p("valueOf", String.valueOf(42) + "|" + String.valueOf(true) + "|" + String.valueOf('x'));
        p("parseInt", Integer.parseInt("123") + 1);
        p("builder", new StringBuilder("ab").append(1).append('c').append(2.5).append(true).toString());
        List<String> xs = new ArrayList<>(); xs.add("a"); xs.add("b"); xs.add("c");
        p("remove-index", xs.remove(0));
        p("remove-object", xs.remove("b"));
        p("list", xs);
        Map<String, Integer> m = new HashMap<>(); m.put("k", 1);
        p("map-get", m.get("k") + 1);
        List<String> ws = new ArrayList<>(List.of("pear", "fig", "banana", "kiwi"));
        ws.sort((a, b) -> Integer.compare(a.length(), b.length()));
        p("sorted", ws);
        p("removeIf", ws.removeIf(w -> w.startsWith("k")));
        p("after", ws);
        p("stream", ws.stream().map(w -> w.toUpperCase()).collect(Collectors.toList()));
        p("upper", "hello".toUpperCase());
        p("charAt", "abc".charAt(1));
        p("split", "a,b,c".split(",").length);
        p("MAX_VALUE", Integer.MAX_VALUE);
        try { Integer.parseInt("x"); } catch (NumberFormatException e) { p("caught", e); }
        // what jolt reaches through a byte or short parameter, which takes an
        // integer only when no other overload fits
        p("byte", Byte.valueOf((byte) 5) + " " + Byte.valueOf((byte) -128) + " " + Short.valueOf((short) 300));
        p("byte[]", java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}).remaining() + " " + new String(new byte[] {104, 105})
          + " " + java.util.Base64.getEncoder().encodeToString(new byte[] {104, 105}));
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(new byte[] {104, 105});
            p("sha-256", java.util.HexFormat.of().formatHex(md.digest()));
        } catch (java.security.NoSuchAlgorithmException e) { p("sha-256", e); }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4); bb.put((byte) 5); bb.putShort((short) 1000);
        p("put", bb.get(0) + " " + bb.getShort(1));
        p("short[]", java.util.Arrays.toString(java.nio.ShortBuffer.wrap(new short[] {1, -2, 30000}).array()));
        p("append-int", new StringBuilder().append(65));
    }
}
