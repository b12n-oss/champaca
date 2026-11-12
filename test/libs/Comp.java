import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.*;
import org.apache.commons.compress.compressors.bzip2.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
public class Comp {
  static byte[] data(int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) ("champaca".charAt(i % 8) + i / 97); return b; }
  static long crc(byte[] b) { CRC32 c = new CRC32(); c.update(b); return c.getValue(); }
  public static void main(String[] a) throws Exception {
    byte[] payload = data(50000);
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream t = new TarArchiveOutputStream(new GzipCompressorOutputStream(tarBytes))) {
      for (String name : new String[] {"a.txt", "dir/b.bin"}) {
        TarArchiveEntry e = new TarArchiveEntry(name); e.setSize(payload.length); e.setModTime(1_700_000_000_000L);
        t.putArchiveEntry(e); t.write(payload); t.closeArchiveEntry();
      }
    }
    try (TarArchiveInputStream t = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(tarBytes.toByteArray())))) {
      for (TarArchiveEntry e; (e = t.getNextEntry()) != null; ) System.out.println("tar " + e.getName() + " " + e.getSize() + " " + crc(t.readAllBytes()) + " " + e.getModTime().getTime());
    }
    ByteArrayOutputStream bz = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream o = new BZip2CompressorOutputStream(bz)) { o.write(payload); }
    try (BZip2CompressorInputStream i = new BZip2CompressorInputStream(new ByteArrayInputStream(bz.toByteArray()))) { System.out.println("bzip2 " + bz.size() + " " + crc(i.readAllBytes())); }
    ByteArrayOutputStream zb = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream z = new ZipArchiveOutputStream(zb)) {
      ZipArchiveEntry e = new ZipArchiveEntry("ü/naïve.txt"); e.setTime(1_700_000_000_000L); z.putArchiveEntry(e); z.write("héllo".getBytes(StandardCharsets.UTF_8)); z.closeArchiveEntry();
    }
    try (ZipArchiveInputStream z = new ZipArchiveInputStream(new ByteArrayInputStream(zb.toByteArray()))) {
      ZipArchiveEntry e = z.getNextEntry(); System.out.println("zip " + e.getName() + " " + new String(z.readAllBytes(), StandardCharsets.UTF_8) + " " + e.getCrc());
    }
    System.out.println("gzip " + crc(tarBytes.toByteArray()) + " " + tarBytes.size());
  }
}
