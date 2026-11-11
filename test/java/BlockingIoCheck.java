import java.io.*;
import java.nio.file.*;

// Opening, reading and writing a FIFO wait on the other end. Other threads
// keep running meanwhile, as on the JVM: the reader's open waits for the
// writer, which first sleeps; the writer's writes wait on the full pipe while
// the first reader sleeps; and every reader waits on the empty pipe while the
// writer sleeps halfway. jolt test makes the FIFO. Main ticks every 20 ms
// while the reader lives, at least 600 ms (27 to 40 times on HotSpot); were
// a blocked thread to keep the GIL, main would tick once or hang, so 3 ticks
// is "kept running". When macOS throttles the process's timers (background
// QoS, as a loaded CI runner showed) HotSpot's own count drops to 7 or 8 for
// Files.copy, below the old 10; a count under 10 goes to stderr, which isn't
// compared.
public class BlockingIoCheck {
    static final String FIFO = "target/blocking-io.fifo";
    static final String COPY = "target/blocking-io.copy";
    static final int SIZE = 200_000;

    interface Reader { long[] read() throws Exception; }

    static long[] count(long[] got, byte[] buf, int n) {
        got[0] += n;
        for (int i = 0; i < n; i++) got[1] = got[1] * 31 + buf[i];
        return got;
    }

    static void phase(String name, Reader body) throws Exception {
        long[][] got = {null};
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(300);
                byte[] chunk = new byte[1000];
                for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) ('a' + i % 26);
                try (FileOutputStream out = new FileOutputStream(FIFO)) {
                    for (int n = 0; n < SIZE; n += chunk.length) {
                        if (n == SIZE / 2) Thread.sleep(300);
                        out.write(chunk);
                    }
                }
            } catch (Exception e) { System.out.println("writer: " + e); }
        });
        Thread reader = new Thread(() -> {
            try { got[0] = body.read(); }
            catch (Exception e) { System.out.println("reader: " + e); }
        });
        writer.start();
        reader.start();
        int ticks = 0;
        while (reader.isAlive()) { Thread.sleep(20); ticks++; }
        reader.join();
        writer.join();
        if (ticks < 10) System.err.println(name + ": main ticked only " + ticks + " times");
        System.out.println(name + ": read " + got[0][0] + " bytes, hash " + got[0][1]
                           + ", main kept running: " + (ticks >= 3));
    }

    public static void main(String[] args) throws Exception {
        phase("FileInputStream", () -> {
            long[] got = {0, 0};
            try (FileInputStream in = new FileInputStream(FIFO)) {
                Thread.sleep(300);
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) count(got, buf, n);
            }
            return got;
        });
        phase("RandomAccessFile", () -> {
            long[] got = {0, 0};
            try (RandomAccessFile in = new RandomAccessFile(FIFO, "r")) {
                byte[] one = new byte[1];
                for (int b; (b = in.read()) >= 0; ) { one[0] = (byte) b; count(got, one, 1); }
            }
            return got;
        });
        phase("Files.copy", () -> {
            Files.copy(Paths.get(FIFO), Paths.get(COPY), StandardCopyOption.REPLACE_EXISTING);
            byte[] all = Files.readAllBytes(Paths.get(COPY));
            Files.delete(Paths.get(COPY));
            return count(new long[] {0, 0}, all, all.length);
        });
    }
}
