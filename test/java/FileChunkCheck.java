import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;

// Bytes between files and byte[]s in every chunk size around the copy loop's
// steps (4) and the I/O buffers (4096, 65536), at odd offsets inside the
// array, every value -128..127: FileInputStream and FileOutputStream,
// RandomAccessFile, Files.readAllBytes, a FileChannel into a direct buffer
// (Unsafe.copyMemory), and GZIP (zlib's buffers). Each row is a size, then a
// CRC of what each path read back, so a byte out of place shows.
public class FileChunkCheck {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("chunk");
        int[] sizes = {0, 1, 3, 4, 5, 7, 4095, 4096, 4097, 65535, 65536, 65537, 65541, 200003};
        for (int n : sizes) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) data[i] = (byte) (i * 31 + (i >> 8));
            Path f = dir.resolve("f" + n);
            // write from an odd offset inside a larger array
            byte[] big = new byte[n + 7];
            System.arraycopy(data, 0, big, 3, n);
            try (OutputStream o = new FileOutputStream(f.toFile())) { o.write(big, 3, n); }
            StringBuilder row = new StringBuilder(n + ":");
            row.append(" file ").append(crc(data) == crc(Files.readAllBytes(f)));
            // read into an odd offset, with reads of several sizes
            for (int buf : new int[] {1, 5, 4096, 70000}) {
                byte[] back = new byte[n + 9];
                int got = 0;
                try (InputStream in = new FileInputStream(f.toFile())) {
                    int r;
                    while (got < n && (r = in.read(back, 2 + got, Math.min(buf, n - got))) > 0) got += r;
                }
                byte[] slice = java.util.Arrays.copyOfRange(back, 2, 2 + n);
                row.append(" read").append(buf).append(' ').append(got == n && crc(slice) == crc(data)
                    && back[0] == 0 && back[1] == 0 && back[n + 2] == 0);
            }
            try (RandomAccessFile r = new RandomAccessFile(f.toFile(), "r")) {
                byte[] back = new byte[n];
                r.readFully(back);
                row.append(" raf ").append(crc(back) == crc(data));
            }
            try (FileChannel ch = FileChannel.open(f)) {
                ByteBuffer bb = ByteBuffer.allocateDirect(Math.max(n, 1));
                while (bb.position() < n && ch.read(bb) > 0) { }
                bb.flip();
                byte[] back = new byte[n];
                bb.get(back);
                row.append(" channel ").append(crc(back) == crc(data));
            }
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (OutputStream g = new GZIPOutputStream(z)) { g.write(big, 3, n); }
            byte[] unz;
            try (InputStream g = new GZIPInputStream(new ByteArrayInputStream(z.toByteArray()))) { unz = g.readAllBytes(); }
            row.append(" gzip ").append(crc(unz) == crc(data)).append(' ').append(Long.toHexString(crc(data)));
            System.out.println(row);
            Files.delete(f);
        }
        Files.delete(dir);
    }

    static long crc(byte[] b) { CRC32 c = new CRC32(); c.update(b); return c.getValue(); }
}
