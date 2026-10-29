// Assertions from the launcher's -ea, -da, -esa and -dsa (AssertCheck.options):
// each class's desired status (a class directive, then its package's, then the
// default, system classes being the boot loader's), an assert statement that
// fires and one that doesn't, and ClassLoader's own assertion maps, which start
// from the same directives (ClassLoader.retrieveDirectives). The helpers are
// top-level classes: javac has a nested class's assert follow its outermost
// class's status.
class AcOff { static String check() { assert false : "off fired"; return "skipped"; } }
class AcLate { static String check() { assert false : "late fired"; return "skipped"; } }
class AcLater { static String check() { assert false : "later fired"; return "skipped"; } }

public class AssertCheck {
    static String status(String n) {
        try { return n + "=" + Class.forName(n).desiredAssertionStatus(); }
        catch (Throwable t) { return n + ": " + t; }
    }

    static String fire(int x) {
        try { assert x > 1 : "x is " + x; return "skipped"; }
        catch (AssertionError e) { return "AssertionError: " + e.getMessage(); }
    }

    public static void main(String[] args) throws Exception {
        for (String n : new String[] {"java.lang.String", "java.lang.Integer", "java.sql.Date", "AssertCheck", "AcOff",
                                      "champaca.lib.Overloads", "champaca.lib.Tag"})
            System.out.println(status(n));
        System.out.println("assert here: " + fire(1));
        System.out.println("assert in AcOff: " + AcOff.check());
        // the loader's maps take over once one is set, starting from the directives
        ClassLoader cl = AssertCheck.class.getClassLoader();
        cl.setClassAssertionStatus("AcLate", false);
        System.out.println("after setClassAssertionStatus: " + status("AcOff") + " " + status("champaca.lib.Overloads")
                           + " " + status("champaca.lib.Tag") + " " + status("AssertCheck"));
        System.out.println("assert in AcLate: " + AcLate.check());
        cl.clearAssertionStatus();
        System.out.println("after clearAssertionStatus: " + status("AssertCheck") + " " + status("champaca.lib.Overloads"));
        System.out.println("assert in AcLater: " + AcLater.check());
    }
}
