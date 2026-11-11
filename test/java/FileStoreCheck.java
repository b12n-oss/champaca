import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

// Files.getFileStore and FileSystem.getFileStores, through the mount table
// (getfsstat on macOS, /proc/mounts on Linux): only facts true on any
// machine are printed, never a name, a type or a size.
public class FileStoreCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String l, Callable c) { try { System.out.println(l + ": " + c.call()); } catch (Throwable e) { System.out.println(l + ": " + e.getClass().getName()); } }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("fstore");
        Path file = Files.writeString(dir.resolve("f.txt"), "x");
        try {
            FileStore st = Files.getFileStore(dir);
            t("store", () -> st != null);
            t("name and type", () -> !st.name().isEmpty() && !st.type().isEmpty());
            t("toString names it", () -> st.toString().contains(st.name()));
            t("sizes", () -> st.getTotalSpace() > 0 && st.getUsableSpace() <= st.getTotalSpace()
                && st.getUnallocatedSpace() <= st.getTotalSpace() && st.getBlockSize() > 0);
            t("attribute totalSpace", () -> st.getAttribute("totalSpace").equals(st.getTotalSpace()));
            t("writable", () -> !st.isReadOnly());
            t("file's store is its directory's", () -> Files.getFileStore(file).equals(st));
            t("basic view", () -> st.supportsFileAttributeView(BasicFileAttributeView.class) && st.supportsFileAttributeView("basic"));
            t("posix view", () -> st.supportsFileAttributeView(PosixFileAttributeView.class));
            t("owner view", () -> st.supportsFileAttributeView("owner"));
            t("root store", () -> Files.getFileStore(Path.of("/")) != null);
            t("stores listed", () -> {
                List<FileStore> all = new ArrayList<>();
                for (FileStore s : FileSystems.getDefault().getFileStores()) all.add(s);
                return !all.isEmpty();
            });
            t("missing path", () -> Files.getFileStore(dir.resolve("nope")));
        } finally {
            Files.delete(file);
            Files.delete(dir);
        }
    }
}
