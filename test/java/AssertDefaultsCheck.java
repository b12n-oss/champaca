// The two assertion defaults apart (AssertDefaultsCheck.options: -da -esa): a
// class the boot loader defines follows -esa, any other, a platform loader's
// included, follows -da, when no directive names it or its package.
public class AssertDefaultsCheck {
    public static void main(String[] args) throws Exception {
        for (String n : new String[] {"sun.nio.cs.UTF_8", "java.util.ArrayList", "javax.sql.RowSet", "java.sql.Date", "AssertDefaultsCheck"})
            System.out.println(n + "=" + Class.forName(n).desiredAssertionStatus());
    }
}
