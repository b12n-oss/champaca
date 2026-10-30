import java.lang.ref.*;
import java.nio.ByteBuffer;
import java.util.*;

// Weak and phantom references are cleared by the collector and enqueued, as
// on the JVM: a referent nothing else holds is gone after System.gc(), a live
// one stays, WeakHashMap drops a key no one holds, a Cleaner's action runs,
// clear() and enqueue() behave, and System.gc() runs while another thread
// sleeps (it ended the process: Chez's collect refuses with other threads
// active). Every wait for the collector retries, as the JVM's timing varies.
public class ReferenceCheck {
    static boolean collectUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 40 && !done.getAsBoolean(); i++) { System.gc(); Thread.sleep(25); }
        return done.getAsBoolean();
    }
    public static void main(String[] args) throws Exception {
        Thread sleeper = new Thread(() -> { try { Thread.sleep(600000); } catch (InterruptedException e) { } });
        sleeper.setDaemon(true);
        sleeper.start();
        Thread.sleep(50);
        System.gc();
        System.out.println("gc with a sleeping thread: ok");

        ReferenceQueue<Object> q = new ReferenceQueue<>();
        Object live = new Object();
        WeakReference<Object> held = new WeakReference<>(live, q);
        WeakReference<Object> dropped = new WeakReference<>(new Object(), q);
        PhantomReference<Object> phantom = new PhantomReference<>(new Object(), q);
        System.out.println("phantom get: " + phantom.get());
        System.out.println("weak cleared: " + collectUntil(() -> dropped.refersTo(null)) + ", held kept: " + (held.get() == live));
        Set<Reference<?>> enqueued = new HashSet<>();
        for (int i = 0; i < 2; i++) { Reference<?> r = q.remove(5000); if (r != null) enqueued.add(r); }
        System.out.println("enqueued: weak " + enqueued.contains(dropped) + ", phantom " + enqueued.contains(phantom) + ", held " + enqueued.contains(held));

        WeakReference<Object> manual = new WeakReference<>(new Object(), q);
        manual.clear();
        System.out.println("cleared by hand: get " + manual.get() + ", enqueue " + manual.enqueue() + ", again " + manual.enqueue() + ", polled " + (q.poll() == manual));

        WeakHashMap<Object, String> map = new WeakHashMap<>();
        Object kept = new Object();
        map.put(kept, "kept");
        map.put(new Object(), "dropped");
        System.out.println("weak map shrank: " + collectUntil(() -> map.size() == 1) + ", holds " + map.get(kept));

        Cleaner cleaner = Cleaner.create();
        boolean[] ran = {false};
        cleaner.register(new Object(), () -> ran[0] = true);
        System.out.println("cleaner ran: " + collectUntil(() -> ran[0]));

        ThreadLocal<String> local = ThreadLocal.withInitial(() -> "initial");
        local.set("set");
        System.gc();
        System.out.println("thread local after gc: " + local.get());

        for (int i = 0; i < 64; i++) ByteBuffer.allocateDirect(1 << 20);
        System.gc();
        System.out.println("direct buffers: ok");
        System.out.println("live still reachable: " + (live != null));
    }
}
