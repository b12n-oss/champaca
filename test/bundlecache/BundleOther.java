// Run only from the copy, once its cache is read-only: a class the cache never
// saw, whose code can't be kept, runs and exits 0 (test/bundlecache).
public class BundleOther {
    public static void main(String[] args) {
        System.out.println("other " + java.util.stream.IntStream.rangeClosed(1, 4).map(i -> i * i).sum());
    }
}
