import java.io.*;

// Opening, reading and writing a FIFO wait on the other end. Other threads
// keep running meanwhile, as on the JVM: here the reader's open waits for
// the writer, which first sleeps, and the writer's writes wait on the full
// pipe while the reader sleeps. bb test makes the FIFO.
public class BlockingIoCheck {
    static final String FIFO = "target/blocking-io.fifo";
    static final int SIZE = 200_000;

    public static void main(String[] args) throws Exception {
        long[] got = {0, 0};
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(300);
                byte[] chunk = new byte[1000];
                for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) ('a' + i % 26);
                try (FileOutputStream out = new FileOutputStream(FIFO)) {
                    for (int n = 0; n < SIZE; n += chunk.length) out.write(chunk);
                }
            } catch (Exception e) { System.out.println("writer: " + e); }
        });
        Thread reader = new Thread(() -> {
            try (FileInputStream in = new FileInputStream(FIFO)) {
                Thread.sleep(300);
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) {
                    got[0] += n;
                    for (int i = 0; i < n; i++) got[1] = got[1] * 31 + buf[i];
                }
            } catch (Exception e) { System.out.println("reader: " + e); }
        });
        writer.start();
        reader.start();
        int ticks = 0;
        while (reader.isAlive()) { Thread.sleep(20); ticks++; }
        reader.join();
        writer.join();
        System.out.println("read " + got[0] + " bytes, hash " + got[1]);
        System.out.println("main kept running: " + (ticks >= 10));
    }
}
