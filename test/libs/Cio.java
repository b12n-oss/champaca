import org.apache.commons.io.*;
import org.apache.commons.io.input.*;
import org.apache.commons.io.output.*;
import org.apache.commons.io.filefilter.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public class Cio {
  public static void main(String[] a) throws Exception {
    Path dir = Files.createTempDirectory("cio");
    File f = dir.resolve("a.txt").toFile();
    FileUtils.writeStringToFile(f, "one\ntwo\nthree\n", StandardCharsets.UTF_8);
    System.out.println(FileUtils.readLines(f, StandardCharsets.UTF_8));
    System.out.println(FileUtils.sizeOf(f) + " " + FilenameUtils.getExtension(f.getName()) + " " + FilenameUtils.getBaseName("x/y/z.tar.gz"));
    FileUtils.copyFile(f, dir.resolve("b.txt").toFile());
    FileUtils.forceMkdir(dir.resolve("sub/deeper").toFile());
    FileUtils.writeStringToFile(dir.resolve("sub/c.log").toFile(), "log", StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    for (File x : FileUtils.listFiles(dir.toFile(), new String[]{"txt", "log"}, true)) names.add(dir.relativize(x.toPath()).toString());
    Collections.sort(names); System.out.println(names);
    System.out.println(FileUtils.contentEquals(f, dir.resolve("b.txt").toFile()));
    System.out.println(IOUtils.toString(new ByteArrayInputStream("héllo".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    StringBuilderWriter w = new StringBuilderWriter(); IOUtils.copy(new StringReader("copy me"), w); System.out.println(w);
    System.out.println(IOUtils.lineIterator(new StringReader("l1\nl2")).hasNext());
    try (BOMInputStream b = BOMInputStream.builder().setInputStream(new ByteArrayInputStream(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF,'x'})).get()) { System.out.println(b.hasBOM() + " " + (char) b.read()); }
    CountingInputStream ci = new CountingInputStream(new ByteArrayInputStream(new byte[100])); ci.skip(40); ci.read(new byte[7]); System.out.println(ci.getByteCount());
    TeeOutputStream tee = new TeeOutputStream(new java.io.ByteArrayOutputStream(), new java.io.ByteArrayOutputStream()); tee.write(1); tee.close();
    System.out.println(FilenameUtils.normalize("/a/b/../c/./d") + " " + FilenameUtils.wildcardMatch("abc.txt", "*.t?t"));
    System.out.println(FileUtils.byteCountToDisplaySize(123456789L));
    System.out.println(new WildcardFileFilter.Builder().setWildcards("*.txt").get().accept(f));
    FileUtils.deleteDirectory(dir.toFile()); System.out.println(Files.exists(dir));
    System.out.println(HexDump.class.getSimpleName() + " " + EndianUtils.swapInteger(0x01020304));
  }
}
