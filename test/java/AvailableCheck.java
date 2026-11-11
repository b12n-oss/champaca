import java.io.*;
import java.nio.file.*;

// InputStream.available on what isn't a regular file: a pipe from a child
// process counts the bytes waiting in it (ioctl FIONREAD, as the JDK asks),
// a character device and a regular file as on the JVM.
public class AvailableCheck {
    public static void main(String[] args) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "printf hello").start();
        p.waitFor();
        InputStream in = p.getInputStream();
        System.out.println("pipe, child done: " + in.available());
        System.out.println("read: " + (char) in.read());
        System.out.println("pipe after one byte: " + in.available());
        in.readAllBytes();
        System.out.println("pipe drained: " + in.available());

        Process q = new ProcessBuilder("sh", "-c", "printf 'abc' >&2; printf 0123456789").start();
        q.waitFor();
        System.out.println("stderr pipe: " + q.getErrorStream().available());
        System.out.println("stdout pipe: " + q.getInputStream().available());

        Process r = new ProcessBuilder("sh", "-c", "head -c 70000 /dev/zero").start();
        InputStream big = r.getInputStream();
        long total = 0;
        while (big.read() != -1) total++;
        r.waitFor();
        System.out.println("big pipe read: " + total + ", left: " + big.available());

        try (FileInputStream dn = new FileInputStream("/dev/null")) {
            System.out.println("/dev/null: " + dn.available());
        }
        Path f = Files.createTempFile("avail", ".txt");
        Files.writeString(f, "0123456789");
        try (FileInputStream fi = new FileInputStream(f.toFile())) {
            fi.skip(3);
            System.out.println("file after skip: " + fi.available());
        }
        Files.delete(f);
    }
}
