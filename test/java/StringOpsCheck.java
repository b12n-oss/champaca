import java.util.Arrays;

// String.hashCode, String.equals and String.indexOf(int) take HotSpot
// intrinsics (ArraysSupport.vectorizedHashCode, StringLatin1.equals and
// indexOfChar), which champaca overrides (spike 75): the hashes of Latin-1
// and UTF-16 strings and of every array type Arrays.hashCode hashes, equals
// on equal, shorter, longer and differing strings, indexOf at every kind of
// position, and the empty cases.
public class StringOpsCheck {
    public static void main(String[] args) {
        String[] ss = {"", "a", "hello", "org/springframework/boot/loader/zip/ZipContent", "été", "Āx", "😀!", "end\u0000nul"};
        for (String s : ss) System.out.println("hash " + s.length() + " " + s.hashCode());
        String a = "ZipContent$Loader", b = new String(new char[] {'Z','i','p','C','o','n','t','e','n','t','$','L','o','a','d','e','r'});
        System.out.println("equals " + a.equals(b) + " " + a.equals("ZipContent$Loade") + " " + a.equals("ZipContent$Loader!") + " " + a.equals("ZipContent$Loadex") + " " + "".equals("") + " " + a.equals("ĀipContent$Loader"));
        for (int from : new int[] {-5, 0, 3, 16, 17, 40}) {
            System.out.println("indexOf from " + from + ": " + a.indexOf('$', from) + " " + a.indexOf('r', from) + " " + a.indexOf('Z', from) + " " + a.indexOf('Ā', from) + " " + a.indexOf('!', from));
        }
        System.out.println("indexOf " + a.indexOf('o') + " " + a.lastIndexOf('o') + " " + "".indexOf('a') + " " + "éé".indexOf('é', 1) + " " + a.indexOf("$L") + " " + a.contains("oad"));
        System.out.println("arrays " + Arrays.hashCode(new byte[] {-1, 1, 127, -128}) + " " + Arrays.hashCode(new short[] {-1, 32767, -32768}) + " " + Arrays.hashCode(new char[] {'a', '￿', 'Ā'}) + " " + Arrays.hashCode(new int[] {Integer.MIN_VALUE, -1, 7}) + " " + Arrays.hashCode(new byte[0]) + " " + Arrays.hashCode((int[]) null));
        System.out.println("boolean-ish " + Arrays.hashCode(new boolean[] {true, false}) + " " + Arrays.hashCode(new long[] {1L << 40, -1}) + " " + Arrays.hashCode(new Object[] {"x", null}));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append((char) ('a' + (i % 26)));
        String big = sb.toString();
        System.out.println("big " + big.hashCode() + " " + big.equals(big + "") + " " + big.indexOf('z', 200) + " " + (big + "Ā").hashCode());
    }
}
