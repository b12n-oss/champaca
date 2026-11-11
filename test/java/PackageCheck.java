// Class.getPackage: one Package per name, and a classpath class's version
// information read from the manifest of the jar it came from, its package's
// own section winning over the main one (test/jar.mf). A class's Package is
// the one its loader defined: the application loader's lookups find it, and
// a loader of the program's own that defines a package before its class gets
// that one back.
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
        loaderLookups(lib);
        ownLoader();
    }
    static void loaderLookups(Package lib) {
        ClassLoader app = PackageCheck.class.getClassLoader();
        System.out.println("app getDefinedPackage: " + (lib == app.getDefinedPackage("champaca.lib")));
        System.out.println("Package.getPackage: " + (lib == Package.getPackage("champaca.lib")));
        System.out.println("unnamed package: " + (PackageCheck.class.getPackage() == app.getDefinedPackage("")));
        boolean listed = false;
        for (Package p : app.getDefinedPackages()) if (p == lib) listed = true;
        System.out.println("app getDefinedPackages: " + listed);
        boolean all = false;
        for (Package p : Package.getPackages()) if (p == lib) all = true;
        System.out.println("Package.getPackages: " + all);
        System.out.println("platform loader: " + ClassLoader.getPlatformClassLoader().getDefinedPackage("champaca.lib"));
        System.out.println("never loaded: " + app.getDefinedPackage("champaca.nowhere"));
    }
    static class Own extends ClassLoader {
        Own() { super(null); }
        Class<?> make() {
            definePackage("pkgcheck.own", "spec", "1.2", "spec vendor", "impl", "3.4", "impl vendor", null);
            byte[] b = java.lang.classfile.ClassFile.of().build(
                java.lang.constant.ClassDesc.of("pkgcheck.own.Gen"), cb -> cb.withSuperclass(java.lang.constant.ConstantDescs.CD_Object));
            return defineClass("pkgcheck.own.Gen", b, 0, b.length);
        }
    }
    static void ownLoader() {
        Own own = new Own();
        Class<?> c = own.make();
        Package p = c.getPackage();
        System.out.println("own loader: " + show(p));
        System.out.println("own loader same: " + (p == own.getDefinedPackage("pkgcheck.own")));
        System.out.println("own loader not app's: " + PackageCheck.class.getClassLoader().getDefinedPackage("pkgcheck.own"));
    }
    static void loadSecondHalf() { new champaca.split.B(); }
    static void askFirstHalf() {
        Package p = champaca.split.A.class.getPackage();
        System.out.println("split: " + show(p));
        System.out.println("split same: " + (p == champaca.split.B.class.getPackage()));
    }
}
