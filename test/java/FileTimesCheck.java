import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.concurrent.TimeUnit;

// Setting a file's times: BasicFileAttributeView.setTimes with any of the
// three (on macOS the creation time goes through setattrlist, the others
// through futimens), Files.setLastModifiedTime, a copy keeping attributes,
// and a time before 1970. Linux can't set a creation time, so that row only
// checks it on macOS (and HotSpot on Linux moves a time with negative
// nanoseconds to the epoch, so no row has one).
public class FileTimesCheck {
    static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
    static FileTime t(long secs, long nanos) { return FileTime.from(secs * 1_000_000_000L + nanos, TimeUnit.NANOSECONDS); }
    static String times(Path p) throws Exception {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        return "mod=" + a.lastModifiedTime() + " acc=" + a.lastAccessTime();
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("times");
        Path f = Files.writeString(dir.resolve("f"), "x");
        BasicFileAttributeView v = Files.getFileAttributeView(f, BasicFileAttributeView.class);
        v.setTimes(t(1_000_000_000, 123_456_789), t(1_100_000_000, 0), null);
        System.out.println("mod and access: " + times(f));
        v.setTimes(null, t(1_200_000_000, 5), null);
        System.out.println("access only: " + times(f));
        FileTime created = t(900_000_000, 0);
        v.setTimes(null, null, created);
        System.out.println("creation set: " + (!MAC || Files.readAttributes(f, BasicFileAttributes.class).creationTime().equals(created)));
        System.out.println("creation left the rest: " + times(f));
        v.setTimes(t(1_300_000_000, 0), t(1_300_000_001, 0), t(800_000_000, 0));
        System.out.println("all three: " + times(f) + " " + (!MAC || Files.readAttributes(f, BasicFileAttributes.class).creationTime().equals(t(800_000_000, 0))));
        Files.setLastModifiedTime(f, t(-86_400, 0));
        System.out.println("before 1970: " + Files.getLastModifiedTime(f));
        Path g = Files.copy(f, dir.resolve("g"), StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("copy keeps: " + Files.getLastModifiedTime(g).equals(Files.getLastModifiedTime(f)));
        FileTime before = Files.getLastModifiedTime(f);
        Path link = Files.createSymbolicLink(dir.resolve("l"), f);
        Files.getFileAttributeView(link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
             .setTimes(t(1_400_000_000, 0), null, null);
        System.out.println("link itself: " + Files.getLastModifiedTime(link, LinkOption.NOFOLLOW_LINKS) + " target kept: " + Files.getLastModifiedTime(f).equals(before));
        try { Files.getFileAttributeView(dir.resolve("missing"), BasicFileAttributeView.class).setTimes(t(1, 0), null, t(1, 0)); }
        catch (Exception e) { System.out.println("missing: " + e.getClass().getName()); }
        for (Path p : new Path[]{link, g, f}) Files.delete(p);
        Files.delete(dir);
    }
}
