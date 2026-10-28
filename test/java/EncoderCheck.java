import java.nio.charset.Charset;

// Each standard charset's encoder and decoder, ASCII output only. bb test also
// runs it after a warm start whose boot loaded the ISO-8859-1 encoder for
// -Dstdout.encoding=ISO-8859-1, which the cached boot never did: that boot's
// classes must reach the startup cache with their code.
public class EncoderCheck {
    public static void main(String[] args) throws Exception {
        for (String n : new String[] {"US-ASCII", "ISO-8859-1", "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "windows-1252"}) {
            Charset c = Charset.forName(n);
            byte[] b = "héllo €".getBytes(c);
            String back = new String(b, c);
            System.out.println(n + ": " + c.newEncoder().getClass().getName() + " " + c.newDecoder().getClass().getName()
                               + " max " + c.newEncoder().maxBytesPerChar() + " bytes " + b.length
                               + " round trip " + back.equals("héllo €") + " " + back.length());
        }
    }
}
