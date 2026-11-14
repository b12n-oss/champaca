import java.util.*;

// An object's identity hash stays put whatever happens to its monitor: taken
// before a lock, inside one, after one, across wait and notify, and on a
// clone, which gets its own. Only facts every JVM agrees on are printed,
// never the hash values themselves.
public class IdentityHashCheck {
    static class Box implements Cloneable {
        int v;
        Box(int v) { this.v = v; }
        public Box clone() throws CloneNotSupportedException { return (Box) super.clone(); }
    }

    static void row(String name, boolean ok) { System.out.println(name + ": " + ok); }

    public static void main(String[] args) throws Exception {
        Object a = new Object();
        int h = a.hashCode();
        row("hashCode is stable", h == a.hashCode() && h == System.identityHashCode(a));
        synchronized (a) {
            row("same inside a lock taken after the hash", System.identityHashCode(a) == h);
            row("holdsLock after the hash", Thread.holdsLock(a));
        }
        row("same after the lock", a.hashCode() == h);
        row("lock released", !Thread.holdsLock(a));

        Object b = new Object();
        int hb;
        synchronized (b) {
            hb = b.hashCode();
            synchronized (b) { row("same inside a nested lock", b.hashCode() == hb); }
        }
        row("hash first taken inside a lock, same after", b.hashCode() == hb);

        Object c = new Object();
        int hc = c.hashCode();
        Thread t = new Thread(() -> {
            synchronized (c) { c.notifyAll(); }
        });
        synchronized (c) {
            t.start();
            c.wait(5000);
            row("same after wait and notify", c.hashCode() == hc);
        }
        t.join();
        row("same after the other thread locked it", System.identityHashCode(c) == hc);

        Box x = new Box(7);
        int hx = System.identityHashCode(x);
        Box y;
        synchronized (x) { y = x.clone(); }
        row("a clone is another object", x != y && y.v == 7);
        row("the clone's own monitor is free", !Thread.holdsLock(y));
        row("the original keeps its hash", System.identityHashCode(x) == hx);
        int hy = System.identityHashCode(y);
        row("the clone's hash is stable", System.identityHashCode(y) == hy);

        int[] arr = new int[3];
        int ha = System.identityHashCode(arr);
        synchronized (arr) { row("an array's hash inside its lock", System.identityHashCode(arr) == ha); }
        row("an array's hash after", arr.hashCode() == ha);

        row("null's identityHashCode is 0", System.identityHashCode(null) == 0);

        // many objects, some locked before and some after their hash
        int n = 20000;
        Object[] objs = new Object[n];
        IdentityHashMap<Object, Integer> map = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            objs[i] = (i % 3 == 0) ? new Box(i) : new Object();
            if (i % 5 == 0) synchronized (objs[i]) { map.put(objs[i], i); }
            else map.put(objs[i], i);
            if (i % 7 == 0) synchronized (objs[i]) { objs[i].notifyAll(); }
        }
        boolean all = map.size() == n;
        for (int i = 0; i < n; i++) all &= map.get(objs[i]) == i;
        row("IdentityHashMap over " + n + " objects", all);
        Set<Integer> distinct = new HashSet<>();
        for (Object o : objs) distinct.add(System.identityHashCode(o));
        row("hashes mostly distinct", distinct.size() > n * 99 / 100);
        System.gc();
        boolean after = true;
        for (int i = 0; i < n; i++) after &= map.get(objs[i]) == i;
        row("still found after a collection", after);
    }
}
