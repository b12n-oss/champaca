import java.util.*;

// jolt test's bundle cache rows: run from a bundle-shaped directory, then from a
// copy of it, which must compile nothing (test/bundlecache).
public class BundleMain {
    public static void main(String[] args) {
        Map<String, Integer> m = new TreeMap<>();
        for (String w : "a bundle moves with its cache a bundle".split(" ")) m.merge(w, 1, Integer::sum);
        System.out.println(m + " " + String.format("%.2f", Math.E));
    }
}
