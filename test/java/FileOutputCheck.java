import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

// FileOutputStream as io_util.c and FileChannelImpl see it (ROADMAP 39c,
// upstream OpenLDK 752bc6c8): an empty write after close is no error, the
// arguments are checked before the stream, and a stream opened to append
// gives its channel the file's size as the position. Each row is what
// HotSpot 25.0.2 gives.
public class FileOutputCheck {
    interface Op { Object run() throws Throwable; }
    static void row(String label, Op op) {
        String r;
        try { Object v = op.run(); r = v == null ? "ok" : String.valueOf(v); }
        catch (Throwable t) { r = t.getClass().getName() + ": " + t.getMessage(); }
        System.out.println(label + " -> " + r);
    }
    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile("champaca-output-", ".bin");
        try {
            FileOutputStream out = new FileOutputStream(path.toFile());
            out.write(1);
            out.close();
            row("write(byte[0]) after close", () -> { out.write(new byte[0]); return null; });
            row("write(b, 1, 0) after close", () -> { out.write(new byte[] {4}, 1, 0); return null; });
            row("write(int) after close", () -> { out.write(3); return null; });
            row("write(byte[1]) after close", () -> { out.write(new byte[] {1}); return null; });
            row("write(null) after close", () -> { out.write((byte[]) null); return null; });
            row("write(b, -1, 0) after close", () -> { out.write(new byte[0], -1, 0); return null; });
            row("write(b, 0, 2) after close", () -> { out.write(new byte[1], 0, 2); return null; });
            row("flush after close", () -> { out.flush(); return null; });
            row("file after close", () -> Arrays.toString(Files.readAllBytes(path)));

            Files.write(path, new byte[0]);
            try (FileOutputStream a = new FileOutputStream(path.toFile(), true);
                 FileOutputStream b = new FileOutputStream(path.toFile(), true)) {
                a.write(1);
                b.write(2);
                FileChannel ch = a.getChannel();
                row("append channel position", ch::position);
                row("append channel position(0)", () -> ch.position(0).position());
                row("append channel write", () -> ch.write(ByteBuffer.wrap(new byte[] {7})));
                row("append channel position after write", ch::position);
                a.write(new byte[] {3, 4});
                row("append file", () -> Arrays.toString(Files.readAllBytes(path)));
                row("append channel size", ch::size);
            }
            try (FileOutputStream t = new FileOutputStream(path.toFile())) {
                t.write(new byte[] {9, 8});
                FileChannel ch = t.getChannel();
                row("truncate channel position", ch::position);
                row("truncate channel position(0)", () -> ch.position(0).position());
                t.write(5);
                row("truncate file", () -> Arrays.toString(Files.readAllBytes(path)));
            }
            try (FileOutputStream a = new FileOutputStream(path.toString(), true);
                 FileOutputStream alias = new FileOutputStream(a.getFD())) {
                alias.write(6);
                row("alias of an append stream: channel position", () -> alias.getChannel().position());
                row("alias file", () -> Arrays.toString(Files.readAllBytes(path)));
            }
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                row("RandomAccessFile channel position", () -> raf.getChannel().position());
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
