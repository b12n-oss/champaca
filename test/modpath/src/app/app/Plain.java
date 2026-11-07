package app;

// run from the class path (-cp mods/app) while mods/app is also a module path
// root: it loads from the class path, in the unnamed module, and the module
// path's com.greet, outside the boot layer without -m, isn't there
public class Plain {
    public static void main(String[] a) {
        System.out.println("Plain named " + Plain.class.getModule().isNamed());
        try { System.out.println(com.greet.Greeter.hello("plain")); }
        catch (NoClassDefFoundError e) { System.out.println(e + " cause " + e.getCause()); }
    }
}
