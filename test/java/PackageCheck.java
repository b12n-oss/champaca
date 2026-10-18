// Class.getPackage: one Package per name, and a classpath class's version
// information read from the manifest of the jar it came from, its package's
// own section winning over the main one (test/jar.mf).
public class PackageCheck {
    static String show(Package p) {
        if (p == null) return "null";
        return "name=" + p.getName() + " specT=" + p.getSpecificationTitle() + " specV=" + p.getSpecificationVersion()
            + " specVendor=" + p.getSpecificationVendor() + " implT=" + p.getImplementationTitle()
            + " implV=" + p.getImplementationVersion() + " implVendor=" + p.getImplementationVendor();
    }
    public static void main(String[] args) throws Exception {
        Package lib = champaca.lib.Greeter.class.getPackage();
        System.out.println("jar: " + show(lib));
        System.out.println("jar sealed: " + lib.isSealed());
        System.out.println("same object: " + (lib == champaca.lib.Shelf.class.getPackage()));
        System.out.println("dir: " + show(PackageCheck.class.getPackage()));
        System.out.println("dir sealed: " + PackageCheck.class.getPackage().isSealed());
        System.out.println("jdk: " + show(String.class.getPackage()));
        System.out.println("jdk name: " + java.util.concurrent.ConcurrentHashMap.class.getPackage().getName());
        System.out.println("primitive: " + int.class.getPackage());
        System.out.println("array: " + String[].class.getPackage());
        System.out.println("toString: " + lib);
        // a package split over two jars takes the attributes of the jar its
        // first loaded class came from, though lib.jar is first on the path
        loadSecondHalf();
        askFirstHalf();
    }
    static void loadSecondHalf() { new champaca.split.B(); }
    static void askFirstHalf() {
        Package p = champaca.split.A.class.getPackage();
        System.out.println("split: " + show(p));
        System.out.println("split same: " + (p == champaca.split.B.class.getPackage()));
    }
}
