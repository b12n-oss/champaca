import java.util.*;

// Class.getDeclaredClasses and getClasses: the member classes a class's
// InnerClasses attribute says it declares, as the JVM lists them; local and
// anonymous classes aren't members, getClasses adds public inherited ones.
public class DeclaredClassesCheck {
    public static class PubStatic {}
    class Inner { class Deeper {} }
    private interface Iface { class InIface {} }
    enum Color { RED }
    record Point(int x) {}
    protected static class Prot extends Base {}
    public static class Base { public static class FromBase {} static class HiddenBase {} }
    @interface Note {}

    static String names(Class<?>[] cs) {
        List<String> l = new ArrayList<>();
        for (Class<?> c : cs) l.add(c.getName());
        return l.toString();
    }
    public static void main(String[] args) {
        Runnable anon = new Runnable() { public void run() {} };
        class Local {}
        System.out.println("declared: " + names(DeclaredClassesCheck.class.getDeclaredClasses()));
        System.out.println("classes: " + names(DeclaredClassesCheck.class.getClasses()));
        System.out.println("inner's: " + names(Inner.class.getDeclaredClasses()));
        System.out.println("deeper's: " + names(Inner.Deeper.class.getDeclaredClasses()));
        System.out.println("interface's: " + names(Iface.class.getDeclaredClasses()));
        System.out.println("base's: " + names(Base.class.getDeclaredClasses()));
        System.out.println("prot getClasses: " + names(Prot.class.getClasses()));
        System.out.println("enum: " + names(Color.class.getDeclaredClasses()));
        System.out.println("local: " + names(Local.class.getDeclaredClasses()));
        System.out.println("anonymous: " + names(anon.getClass().getDeclaredClasses()));
        System.out.println("array: " + names(PubStatic[].class.getDeclaredClasses()));
        System.out.println("int: " + names(int.class.getDeclaredClasses()));
        System.out.println("jdk Map: " + names(Map.class.getDeclaredClasses()));
        System.out.println("jdk Character: " + names(Character.class.getDeclaredClasses()));
        System.out.println("jdk AbstractMap: " + names(AbstractMap.class.getClasses()));
        System.out.println("member check: " + PubStatic.class.isMemberClass() + " " + Local.class.isMemberClass());
    }
}
