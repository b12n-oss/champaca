import java.io.*;

// Standard input read a byte at a time, then in small chunks, then through a
// BufferedReader: nothing is lost between reads. jolt test feeds StdinCheck.stdin.
public class StdinCheck {
    public static void main(String[] args) throws Exception {
        InputStream in = new FileInputStream(FileDescriptor.in);
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < 10; i++) first.append((char) in.read());
        System.out.println("bytes: " + first);
        byte[] buf = new byte[7];
        int n = in.read(buf);
        System.out.println("chunk: " + new String(buf, 0, n));
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        int lines = 0;
        long sum = 0;
        for (String l; (l = r.readLine()) != null; ) { lines++; sum += l.length(); }
        System.out.println("lines: " + lines + ", chars: " + sum);
        System.out.println("eof: " + in.read());
    }
}
