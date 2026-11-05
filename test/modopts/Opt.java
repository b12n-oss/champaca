import java.lang.management.ManagementFactory;

// What --add-opens, --add-exports and --add-reads change, seen from the
// unnamed module: deep reflection into java.lang, the export and opening of
// jdk.internal.misc, two reads edges; the VM's input arguments first.
public class Opt {
    public static void main(String[] a) throws Exception {
        System.out.println("args " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        try {
            var f = String.class.getDeclaredField("value");
            f.setAccessible(true);
            System.out.println("opens java.lang: yes");
        } catch (RuntimeException e) {
            System.out.println("opens java.lang: " + e.getClass().getName());
        }
        Module me = Opt.class.getModule();
        Module base = Object.class.getModule();
        System.out.println("exports jdk.internal.misc: " + base.isExported("jdk.internal.misc", me));
        System.out.println("opens jdk.internal.misc: " + base.isOpen("jdk.internal.misc", me));
        try {
            Object booted = Class.forName("jdk.internal.misc.VM").getMethod("isBooted").invoke(null);
            System.out.println("VM.isBooted through reflection: " + booted);
        } catch (IllegalAccessException e) {
            System.out.println("VM.isBooted through reflection: IllegalAccessException");
        }
        Module log = java.util.logging.Logger.class.getModule();
        Module sql = ModuleLayer.boot().findModule("java.sql").orElseThrow();
        System.out.println("java.logging reads java.sql: " + log.canRead(sql));
        System.out.println("java.base reads the unnamed module: " + base.canRead(me));
    }
}
