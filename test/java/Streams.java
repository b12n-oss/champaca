import java.util.*;
import java.util.stream.*;

public class Streams {
    record Point(int x, int y) {
        int dist2() { return x * x + y * y; }
    }
    public static void main(String[] args) {
        System.out.println(IntStream.rangeClosed(1, 10).filter(i -> i % 2 == 0).map(i -> i * i).sum());
        List<String> words = List.of("delta", "alpha", "charlie", "bravo", "echo");
        System.out.println(words.stream().filter(w -> w.length() == 5).sorted().collect(Collectors.joining(", ")));
        int base = 100;
        System.out.println(words.stream().map(String::length).map(n -> n + base).toList());
        Map<Integer, List<String>> byLen = words.stream().collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.toList()));
        System.out.println(byLen);
        Point p = new Point(3, 4);
        System.out.println(p + " " + p.dist2() + " " + p.equals(new Point(3, 4)));
        Optional<String> longest = words.stream().max(Comparator.comparingInt(String::length));
        System.out.println(longest.orElse("none"));
    }
}
