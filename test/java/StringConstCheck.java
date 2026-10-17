import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.Charset;

// String constants outside plain ASCII: a supplementary character arrives in
// the classfile as a surrogate pair, and a lone surrogate is legal too
// (sun.nio.cs.GB18030's tables are full of them). Code units are printed in
// hex so the output doesn't depend on stdout's encoding.
public class StringConstCheck {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Label { String value(); String[] more() default {}; }

    static final String SMILE = "\uD83D\uDE00";

    static String units(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) b.append(' ');
            b.append(Integer.toHexString(s.charAt(i)));
        }
        return s.length() + ": " + b;
    }

    static String bytes(byte[] bs) {
        StringBuilder b = new StringBuilder();
        for (byte x : bs) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }

    static String lone1() { return "x\uD800y"; }
    static String lone2() { return "x\uD800y"; }

    static int kind(String s) {
        return switch (s) {
            case "\uD83D\uDE00" -> 1;
            case "\uDC00" -> 2;
            case "\uFFFD" -> 5;
            case String t when t.isEmpty() -> 3;
            default -> 4;
        };
    }

    @Label(value = "a\uD83D\uDE00b", more = {"\uDBFF", "\uD800\uDC00"})
    static void annotated() {}

    public static void main(String[] args) throws Exception {
        System.out.println(units("a\uD83D\uDE00b"));
        System.out.println("a\uD83D\uDE00b".codePointAt(1) == 0x1F600);
        System.out.println(units("\uD800"));
        System.out.println(units(lone1()));
        System.out.println(units("\uDC00\uD800"));
        System.out.println(lone1() == lone2());
        System.out.println(units((String) StringConstCheck.class.getDeclaredField("SMILE").get(null)));
        System.out.println(kind(SMILE) + " " + kind("\uDC00") + " " + kind("") + " " + kind("z"));
        Label l = StringConstCheck.class.getDeclaredMethod("annotated").getAnnotation(Label.class);
        System.out.println(units(l.value()));
        System.out.println(units(l.more()[0]) + " / " + units(l.more()[1]));
        String joined = "<" + lone1() + 7 + SMILE + ">";
        System.out.println(units(joined));
        char hi = '\uD83D', lo = '\uDE00', alone = '\uDC01';
        System.out.println(units("" + hi + lo + alone));
        System.out.println(units("p\uDBFF" + args.length + "\uD83D\uDE00"));
        // intern() agrees with the constants, and lone surrogates don't collide
        String d800 = new String(new char[]{0xD800}).intern();
        String dc00 = new String(new char[]{0xDC00}).intern();
        String fffd = new String(new char[]{0xFFFD}).intern();
        System.out.println((d800 == "\uD800") + " " + (dc00 == "\uDC00") + " " + (fffd == "\uFFFD")
                           + " " + (d800 != dc00) + " " + units(dc00) + " " + units(fffd));
        System.out.println((new String(SMILE.toCharArray()).intern() == SMILE) + " "
                           + kind(new String(new char[]{0xD800})) + " " + kind(new String(new char[]{0xFFFD})));
        Charset gb = Charset.forName("GB18030");
        byte[] enc = "\u4E2D\uD83D\uDE00".getBytes(gb);
        System.out.println(bytes(enc));
        System.out.println(units(new String(enc, gb)));
    }
}
