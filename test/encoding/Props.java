// The encodings the JDK takes from the locale: native.encoding,
// sun.jnu.encoding and the console streams' (stdout here is a pipe, so the
// JDK gives it native.encoding), then a non-ASCII line through stdout.
public class Props {
    public static void main(String[] a) {
        for (String k : new String[]{"native.encoding", "sun.jnu.encoding", "stdout.encoding", "stderr.encoding", "file.encoding"})
            System.out.println(k + "=" + System.getProperty(k));
        System.out.println("héllo");
    }
}
