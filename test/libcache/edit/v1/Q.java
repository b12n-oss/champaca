// Restored from the cache with a() and where() compiled; b() first
// compiles after the file has been replaced, from the version the class
// was restored as, and where()'s frame keeps that version's line.
public class Q {
    public static String a() { return "a"; }

    public static String b() { return "b"; }

    public static int where() { return new Throwable().getStackTrace()[0].getLineNumber(); }
}
