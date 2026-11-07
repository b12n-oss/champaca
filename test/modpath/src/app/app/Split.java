package app;

// -cp split -p mods -m app/app.Split: com.greet.Extra is on the class path,
// in a package the resolved module com.greet holds
public class Split {
    public static void main(String[] a) {
        try { System.out.println("Extra in " + Class.forName("com.greet.Extra").getModule().getName()); }
        catch (ClassNotFoundException e) { System.out.println("Extra " + e); }
    }
}
