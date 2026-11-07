package cp;

// a class path class naming a module path class: found only when the module
// is in the boot layer (--add-modules), else NoClassDefFoundError
public class Probe {
    public static void main(String[] a) {
        try {
            System.out.println(com.greet.Greeter.hello("cp"));
            System.out.println("Probe named " + Probe.class.getModule().isNamed() + ", Greeter in " + com.greet.Greeter.class.getModule().getName());
        } catch (NoClassDefFoundError e) {
            System.out.println(e + " cause " + e.getCause());
        }
        try { Class.forName("com.greet.Greeter"); System.out.println("forName found"); }
        catch (ClassNotFoundException e) { System.out.println("forName " + e); }
    }
}
