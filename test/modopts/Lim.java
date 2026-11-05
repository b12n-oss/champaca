// Which JDK classes the built-in loaders find under --limit-modules (and
// without --add-modules for a module the default boot layer leaves out):
// Class.forName through the app loader and the boot loader, the platform
// loader's loadClass, and the layer's own view. Only names in strings: a
// class named in the bytecode resolves whatever the layer (champaca gap).
public class Lim {
    static String find(String c, ClassLoader l) {
        try {
            Class.forName(c, false, l);
            return "found";
        } catch (ClassNotFoundException e) {
            return "CNFE " + e.getMessage();
        }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("boot modules: " + ModuleLayer.boot().modules().size());
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (String c : new String[]{"java.sql.Connection", "java.util.logging.Logger",
                                     "java.lang.management.ManagementFactory", "java.net.http.HttpClient",
                                     "jdk.incubator.vector.IntVector", "int[]"}) {
            String n = c.equals("int[]") ? "[I" : c;
            System.out.println(c + ": " + find(n, Lim.class.getClassLoader()) + "; boot loader " + find(n, null)
                               + "; platform " + find(n, platform));
        }
        try {
            platform.loadClass("java.sql.Date");
            System.out.println("platform loadClass java.sql.Date: found");
        } catch (ClassNotFoundException e) {
            System.out.println("platform loadClass java.sql.Date: CNFE");
        }
        System.out.println("findModule java.logging: " + ModuleLayer.boot().findModule("java.logging").isPresent());
    }
}
