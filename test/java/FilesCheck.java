import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

// Files: java.io streams, readers and writers, File, RandomAccessFile,
// java.nio.file (Files, Path, attributes, permissions, symlinks, directory
// streams, walking), FileChannel, the exceptions each throws, and
// MessageDigest, whose providers load through java.nio.file. Everything
// happens under a fresh temporary directory, and nothing prints its path.
public class FilesCheck {
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("champaca-files");
        System.out.println("temp dir " + Files.isDirectory(root) + " empty " + (Files.list(root).count() == 0));

        // java.io: write, append, read back
        File f = new File(root.toFile(), "io.txt");
        try (FileOutputStream out = new FileOutputStream(f)) { out.write("hello\n".getBytes(StandardCharsets.UTF_8)); }
        try (FileOutputStream out = new FileOutputStream(f, true)) { out.write('!'); out.write("\nmore\n".getBytes(StandardCharsets.UTF_8)); }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(root.toFile(), "w.txt")))) {
            for (int i = 0; i < 3; i++) { w.write("line " + i); w.newLine(); }
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            System.out.println("io lines " + r.lines().collect(Collectors.toList()));
        }
        try (FileInputStream in = new FileInputStream(new File(root.toFile(), "w.txt"))) {
            System.out.println("w.txt " + new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\n", "|"));
        }
        System.out.println("File length " + f.length() + " isFile " + f.isFile() + " canRead " + f.canRead() + " canWrite " + f.canWrite());

        // File: directories, rename, delete, timestamps
        File sub = new File(root.toFile(), "a/b/c");
        System.out.println("mkdirs " + sub.mkdirs() + " again " + sub.mkdirs() + " isDirectory " + sub.isDirectory());
        File created = new File(sub, "new.txt");
        System.out.println("createNewFile " + created.createNewFile() + " again " + created.createNewFile());
        File renamed = new File(sub, "renamed.txt");
        System.out.println("renameTo " + created.renameTo(renamed) + " old exists " + created.exists() + " new exists " + renamed.exists());
        System.out.println("setLastModified " + renamed.setLastModified(1_000_000_000_000L) + " lastModified " + renamed.lastModified());
        System.out.println("list " + new TreeSet<>(Arrays.asList(root.toFile().list())));
        System.out.println("delete non-empty dir " + new File(root.toFile(), "a").delete() + " file " + renamed.delete() + " gone " + !renamed.exists());

        // RandomAccessFile
        File rafFile = new File(root.toFile(), "raf.bin");
        try (RandomAccessFile raf = new RandomAccessFile(rafFile, "rw")) {
            raf.writeInt(42); raf.writeLong(7L); raf.writeUTF("utf");
            raf.seek(4);
            long l = raf.readLong();
            raf.seek(0);
            System.out.println("raf " + raf.readInt() + " " + l + " length " + raf.length() + " pointer " + raf.getFilePointer());
            raf.setLength(4);
            System.out.println("raf truncated " + raf.length());
        }

        // java.nio.file: strings, bytes, lines
        Path p = root.resolve("nio.txt");
        Files.writeString(p, "alpha\nbeta\ngamma\n");
        System.out.println("readString " + Files.readString(p).replace("\n", "|"));
        System.out.println("readAllLines " + Files.readAllLines(p));
        try (Stream<String> s = Files.lines(p)) { System.out.println("lines with a " + s.filter(x -> x.contains("a")).count()); }
        Files.write(p, List.of("delta"), StandardOpenOption.APPEND);
        System.out.println("appended size " + Files.size(p) + " bytes " + Files.readAllBytes(p).length);
        try (BufferedWriter w = Files.newBufferedWriter(root.resolve("buf.txt"))) { w.write("buffered\n"); }
        try (BufferedReader r = Files.newBufferedReader(root.resolve("buf.txt"))) { System.out.println("newBufferedReader " + r.readLine()); }
        try (OutputStream o = Files.newOutputStream(root.resolve("os.bin"))) { o.write(new byte[] {1, 2, 3, 4, 5}); }
        try (InputStream in = Files.newInputStream(root.resolve("os.bin"))) { System.out.println("newInputStream " + Arrays.toString(in.readAllBytes())); }

        // attributes and permissions
        System.out.println("exists " + Files.exists(p) + " notExists " + Files.notExists(root.resolve("nope"))
            + " isRegularFile " + Files.isRegularFile(p) + " isDirectory " + Files.isDirectory(p) + " isReadable " + Files.isReadable(p));
        Files.setLastModifiedTime(p, FileTime.fromMillis(1_500_000_000_000L));
        BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class);
        System.out.println("attrs size " + at.size() + " dir " + at.isDirectory() + " regular " + at.isRegularFile() + " mtime " + at.lastModifiedTime());
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r-----"));
        System.out.println("permissions " + PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));

        // directories, copy, move, walk, glob
        Path deep = Files.createDirectories(root.resolve("x/y/z"));
        Files.writeString(deep.resolve("leaf.txt"), "leaf");
        Files.copy(p, root.resolve("x/copy.txt"));
        Files.copy(root.resolve("buf.txt"), root.resolve("x/copy.txt"), StandardCopyOption.REPLACE_EXISTING);
        System.out.println("copied " + Files.readString(root.resolve("x/copy.txt")).trim());
        Files.move(root.resolve("x/copy.txt"), root.resolve("x/y/moved.txt"));
        try (Stream<Path> w = Files.walk(root.resolve("x"))) {
            System.out.println("walk " + w.map(q -> root.resolve("x").relativize(q).toString()).sorted().collect(Collectors.toList()));
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.txt")) {
            List<String> names = new ArrayList<>();
            for (Path q : ds) names.add(q.getFileName().toString());
            Collections.sort(names);
            System.out.println("glob *.txt " + names);
        }
        try (Stream<Path> found = Files.find(root, 10, (q, a) -> a.isRegularFile() && q.toString().endsWith(".txt"))) {
            System.out.println("find .txt " + found.count());
        }

        // symlinks and real paths
        Path link = root.resolve("link.txt");
        Files.createSymbolicLink(link, p);
        System.out.println("symlink " + Files.isSymbolicLink(link) + " target name " + Files.readSymbolicLink(link).getFileName()
            + " same file " + Files.isSameFile(link, p) + " real path matches " + link.toRealPath().equals(p.toRealPath())
            + " not same " + Files.isSameFile(p, root.resolve("buf.txt")));
        System.out.println("canonical through link " + new File(link.toString()).getCanonicalFile().getName());

        // FileChannel
        try (FileChannel ch = FileChannel.open(root.resolve("chan.bin"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap("0123456789".getBytes(StandardCharsets.US_ASCII)));
            ch.position(3);
            ByteBuffer buf = ByteBuffer.allocate(4);
            ch.read(buf);
            ByteBuffer direct = ByteBuffer.allocateDirect(3);
            ch.read(direct, 7);
            direct.flip();
            byte[] d = new byte[3];
            direct.get(d);
            ch.truncate(6);
            System.out.println("channel read " + new String(buf.array(), StandardCharsets.US_ASCII) + " direct " + new String(d, StandardCharsets.US_ASCII)
                + " size " + ch.size() + " position " + ch.position());
        }
        System.out.println("mismatch " + Files.mismatch(p, p) + " " + Files.mismatch(p, root.resolve("buf.txt")));

        // owner, a memory map, a lock, transferTo, force, gathering writes
        System.out.println("owner known " + !Files.getOwner(p).getName().isEmpty());
        try (FileChannel ch = FileChannel.open(root.resolve("chan.bin"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, 6);
            byte first = m.get(0);
            m.put(1, (byte) 'X');
            m.force();
            try (java.nio.channels.FileLock lock = ch.lock()) { System.out.println("map " + (char) first + " locked " + lock.isValid()); }
            ch.force(true);
            try (FileChannel out = FileChannel.open(root.resolve("copy.bin"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                System.out.println("transferTo " + ch.transferTo(0, 6, out));
                out.write(new ByteBuffer[] {ByteBuffer.wrap("ab".getBytes()), ByteBuffer.wrap("cd".getBytes())});
            }
        }
        System.out.println("after map and gather " + Files.readString(root.resolve("copy.bin")));
        try (RandomAccessFile rws = new RandomAccessFile(new File(root.toFile(), "sync.bin"), "rws")) {
            rws.write(new byte[] {9, 8, 7});
            rws.getFD().sync();
            System.out.println("rws length " + rws.length());
        }
        try (FileInputStream in = new FileInputStream(new File(root.toFile(), "sync.bin"))) {
            in.read(new byte[2], 1, 5);
        } catch (IndexOutOfBoundsException e) { System.out.println("bad offset: IndexOutOfBoundsException"); }

        // gzip and zip, through Inflater and Deflater
        String text = "champaca ".repeat(500);
        try (OutputStream gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(root.resolve("t.gz")))) { gz.write(text.getBytes(StandardCharsets.UTF_8)); }
        try (InputStream gz = new java.util.zip.GZIPInputStream(Files.newInputStream(root.resolve("t.gz")))) {
            System.out.println("gzip " + (Files.size(root.resolve("t.gz")) < 200) + " " + new String(gz.readAllBytes(), StandardCharsets.UTF_8).equals(text));
        }
        try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(Files.newOutputStream(root.resolve("t.zip")))) {
            zo.putNextEntry(new java.util.zip.ZipEntry("a/one.txt")); zo.write(text.getBytes(StandardCharsets.UTF_8)); zo.closeEntry();
            zo.putNextEntry(new java.util.zip.ZipEntry("two.txt")); zo.write("2".getBytes(StandardCharsets.UTF_8)); zo.closeEntry();
        }
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(root.resolve("t.zip").toFile())) {
            System.out.println("zip entries " + zf.stream().map(java.util.zip.ZipEntry::getName).sorted().collect(Collectors.toList())
                + " " + new String(zf.getInputStream(zf.getEntry("a/one.txt")).readAllBytes(), StandardCharsets.UTF_8).length());
        }
        java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
        def.setInput("aaaaaaaaaabbbbbbbbbb".getBytes(StandardCharsets.UTF_8)); def.finish();
        byte[] packed = new byte[100]; int plen = def.deflate(packed); def.end();
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(packed, 0, plen);
        byte[] unpacked = new byte[100]; int ulen = inf.inflate(unpacked);
        System.out.println("deflate/inflate " + new String(unpacked, 0, ulen, StandardCharsets.UTF_8) + " finished " + inf.finished() + " adler " + inf.getAdler());
        inf.end();
        try { java.util.zip.Inflater bad = new java.util.zip.Inflater(); bad.setInput(new byte[] {1, 2, 3, 4}); bad.inflate(new byte[10]); }
        catch (java.util.zip.DataFormatException e) { System.out.println("bad data: DataFormatException"); }

        // resources: a file: URL from the classpath, and names that leave it
        java.net.URL self = FilesCheck.class.getResource("FilesCheck.class");
        try (InputStream in = self.openStream()) {
            System.out.println("resource " + self.getProtocol() + " " + (in.readAllBytes().length > 1000));
        }
        System.out.println("escaping names " + (FilesCheck.class.getResource("../FilesCheck.class") == null)
            + " " + (ClassLoader.getSystemResource("/FilesCheck.class") == null));

        // failures
        try { Files.readString(root.resolve("missing")); } catch (NoSuchFileException e) { System.out.println("NoSuchFileException " + Path.of(e.getFile()).getFileName()); }
        try { Files.createDirectory(root.resolve("x")); } catch (FileAlreadyExistsException e) { System.out.println("FileAlreadyExistsException"); }
        try { Files.delete(root.resolve("x")); } catch (DirectoryNotEmptyException e) { System.out.println("DirectoryNotEmptyException"); }
        try { new FileInputStream(new File(root.toFile(), "missing")); } catch (FileNotFoundException e) { System.out.println("FileNotFoundException"); }
        System.out.println("deleteIfExists " + Files.deleteIfExists(root.resolve("missing")) + " " + Files.deleteIfExists(root.resolve("os.bin")));
        Path tmp = Files.createTempFile(root, "pre", ".suf");
        System.out.println("temp file " + tmp.getFileName().toString().startsWith("pre") + " " + tmp.getFileName().toString().endsWith(".suf"));

        // MessageDigest
        byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        System.out.println("sha-256 " + hex(MessageDigest.getInstance("SHA-256").digest(data)));
        System.out.println("md5 " + hex(MessageDigest.getInstance("MD5").digest(data)));

        // clean up
        try (Stream<Path> w = Files.walk(root)) {
            for (Path q : w.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(q);
        }
        System.out.println("cleaned " + !Files.exists(root));
    }
}
