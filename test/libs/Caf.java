import com.github.benmanes.caffeine.cache.*;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Caf {
    public static void main(String[] args) throws Exception {
        Cache<String, Integer> c = Caffeine.newBuilder().maximumSize(100).build();
        c.put("a", 1);
        System.out.println("a=" + c.getIfPresent("a") + " b=" + c.getIfPresent("b"));
        System.out.println("computed " + c.get("hello", String::length));
        System.out.println("asMap " + new TreeMap<>(c.asMap()));

        // unbounded with stats: deterministic counts
        LoadingCache<Integer, Integer> sq = Caffeine.newBuilder().recordStats().build(k -> k * k);
        int sum = 0;
        for (int i = 0; i < 50; i++) sum += sq.get(i % 20);
        CacheStats st = sq.stats();
        System.out.println("sum " + sum + " hits " + st.hitCount() + " misses " + st.missCount() + " loads " + st.loadCount());
        System.out.println("getAll " + new TreeMap<>(sq.getAll(List.of(2, 3, 30))));

        // size eviction, maintenance on this thread, a removal listener
        List<String> removed = Collections.synchronizedList(new ArrayList<>());
        Cache<Integer, String> small = Caffeine.newBuilder()
            .maximumSize(5).executor(Runnable::run)
            .removalListener((Integer k, String v, RemovalCause cause) -> removed.add(cause.toString()))
            .build();
        for (int i = 0; i < 20; i++) small.put(i, "v" + i);
        small.cleanUp();
        System.out.println("size " + small.estimatedSize() + " evicted " + removed.size() + " " + new TreeSet<>(removed));

        // weights
        Cache<String, String> weighed = Caffeine.newBuilder()
            .maximumWeight(10).weigher((String k, String v) -> v.length()).executor(Runnable::run).build();
        weighed.put("x", "12345");
        weighed.put("y", "123456789012");
        weighed.cleanUp();
        System.out.println("weighed y present " + (weighed.getIfPresent("y") != null));

        // expiry against a fake clock
        AtomicLong now = new AtomicLong();
        Cache<String, String> exp = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).ticker(now::get).executor(Runnable::run).build();
        exp.put("k", "v");
        now.addAndGet(Duration.ofMinutes(4).toNanos());
        System.out.println("after 4 min " + exp.getIfPresent("k"));
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        System.out.println("after 6 min " + exp.getIfPresent("k"));

        Cache<String, String> acc = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(10)).ticker(now::get).executor(Runnable::run).build();
        acc.put("k", "v");
        for (int i = 0; i < 5; i++) { now.addAndGet(Duration.ofSeconds(8).toNanos()); acc.getIfPresent("k"); }
        System.out.println("kept alive by access " + acc.getIfPresent("k"));
        now.addAndGet(Duration.ofSeconds(11).toNanos());
        System.out.println("idle too long " + acc.getIfPresent("k"));

        // refresh
        AtomicInteger version = new AtomicInteger();
        LoadingCache<String, String> ref = Caffeine.newBuilder()
            .refreshAfterWrite(Duration.ofSeconds(30)).ticker(now::get).executor(Runnable::run)
            .build(k -> k + "#" + version.incrementAndGet());
        System.out.println("first " + ref.get("r"));
        now.addAndGet(Duration.ofSeconds(31).toNanos());
        ref.get("r");
        System.out.println("refreshed " + ref.get("r"));

        // async, completing on this thread
        AsyncLoadingCache<Integer, String> async = Caffeine.newBuilder()
            .executor(Runnable::run).buildAsync(k -> "async-" + k);
        System.out.println(async.get(7).get() + " " + async.synchronous().get(8));

        // invalidation and compute
        c.invalidate("a");
        c.asMap().compute("n", (k, v) -> v == null ? 1 : v + 1);
        c.asMap().compute("n", (k, v) -> v == null ? 1 : v + 1);
        System.out.println("after invalidate " + c.getIfPresent("a") + " computed n " + c.getIfPresent("n"));
    }
}
