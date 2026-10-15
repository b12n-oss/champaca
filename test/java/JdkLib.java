import java.util.*;

public class JdkLib {
    public static void main(String[] args) {
        List<String> names = new ArrayList<>(List.of("pear", "apple", "fig", "banana"));
        Collections.sort(names);
        System.out.println(names);
        names.sort(new Comparator<String>() {
            public int compare(String a, String b) { return Integer.compare(a.length(), b.length()); }
        });
        System.out.println(names);

        Map<String, Integer> counts = new HashMap<>();
        for (String w : "the quick brown fox jumps over the lazy dog the end".split(" ")) {
            counts.merge(w, 1, Integer::sum);
        }
        System.out.println(new TreeMap<>(counts));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i).append(',');
        sb.setLength(sb.length() - 1);
        System.out.println(sb.reverse());

        System.out.println(Integer.parseInt("-12345") + Long.parseLong("9000000000"));
        System.out.println(String.join("|", "a", "b", "c").toUpperCase());
        System.out.println(Math.max(3, 7) + " " + Math.abs(-2.5) + " " + Integer.toHexString(255));
        System.out.println("héllo wörld ✓".length() + " " + "héllo".indexOf('l'));
    }
}
