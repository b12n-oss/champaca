public class Q {
    public static String a() { return "A"; }

    public static String b() { return "B!"; }


    // where() two lines further down than in v1
    public static int where() { return new Throwable().getStackTrace()[0].getLineNumber(); }
}
