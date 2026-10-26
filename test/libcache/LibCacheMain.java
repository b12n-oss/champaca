public class LibCacheMain {
    public static void main(String[] args) {
        System.out.println(lc2.User.run());
        // a class from the cache still has its code source and its package
        System.out.println(where(lc2.User.class) + " " + where(lc.Base.class) + " " + lc.Sub.class.getPackage().getName());
        System.out.println(lc2.User.frame());
    }

    // the last name in the class's code source: a jar's, or a directory's
    static String where(Class<?> c) {
        String p = c.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
