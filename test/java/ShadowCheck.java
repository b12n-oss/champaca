import java.lang.reflect.*;

// A class path entry holding classes in packages the JDK's modules own
// (written by test/gen/ShadowGen.java): the JVM's application loader leaves
// such a package to its module, so the class path can neither replace one
// of its classes nor add one, while a package whose module the boot layer
// didn't resolve is the class path's like any other.
public class ShadowCheck {
    static void row(String name, Object v) { System.out.println(name + ": " + v); }
    static String load(String name) {
        try {
            Class<?> c = Class.forName(name);
            String marker;
            try { marker = (String) c.getMethod("marker").invoke(null); }
            catch (NoSuchMethodException e) { marker = "no marker"; }
            return marker + ", " + c.getModule().isNamed() + ", " + (c.getClassLoader() == null ? "boot" : c.getClassLoader() == ClassLoader.getSystemClassLoader() ? "app" : "other");
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }
    public static void main(String[] a) throws Exception {
        row("a JDK class the class path also has", load("javax.xml.XMLConstants"));
        row("its field", Class.forName("javax.xml.XMLConstants").getField("NULL_NS_URI").get(null).equals(""));
        row("a class added to a JDK package", load("javax.xml.ChampacaOnly"));
        row("through the app loader", load2("javax.xml.ChampacaOnly"));
        row("a package outside the boot layer", load("jdk.incubator.vector.ChampacaShadow"));
        row("javax.xml's module", javax.xml.XMLConstants.class.getModule().getName());
        // compiled against the JDK's class: the constant is inlined, the
        // class the code links against is the JDK's too
        row("linked", javax.xml.XMLConstants.XML_NS_PREFIX + " " + javax.xml.XMLConstants.class.getDeclaredFields().length);
        row("resource", ClassLoader.getSystemResource("javax/xml/XMLConstants.class").getProtocol() + " "
            + ClassLoader.getSystemResource("javax/xml/ChampacaOnly.class").getProtocol());
    }
    static String load2(String name) {
        try { return ClassLoader.getSystemClassLoader().loadClass(name).getName(); }
        catch (Throwable t) { return t.getClass().getName() + ": " + t.getMessage(); }
    }
}
