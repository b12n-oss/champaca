package app;

// A stack frame names its class's module, the module's version and the
// class's loader, as HotSpot fills them: app@1.0/ before an application
// module's frames, com.greet/ before a module with no version, and a JDK
// module's name with its version left out of the text but kept in the
// element. Line numbers are left out (champaca keeps them for classes from
// directories only, unless CHAMPACA_LINES=all), and the JDK frame prints its
// fields alone.
public class Frames {
    public static void main(String[] a) {
        com.greet.Greeter.call(() -> java.util.List.of(1).forEach(x -> show()));
    }

    // the frame's text without its line number
    static String noLine(StackTraceElement e) {
        return e.toString().replaceAll(":\\d+\\)$", ")");
    }

    static void show() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("app.") || c.startsWith("com.greet.")) {
                System.out.println(noLine(e) + " | module " + e.getModuleName() + " version " + e.getModuleVersion() + " loader " + e.getClassLoaderName());
            } else if (c.equals("java.lang.Thread") || c.startsWith("java.util.")) {
                System.out.println("jdk " + c + " | module " + e.getModuleName()
                    + " version is java.version " + System.getProperty("java.version").equals(e.getModuleVersion())
                    + " loader " + e.getClassLoaderName() + " text starts " + e.toString().startsWith(e.getModuleName() + "/" + c));
            }
        }
    }
}
