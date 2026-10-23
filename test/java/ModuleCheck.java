import java.nio.charset.*;
import java.text.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.spi.ToolProvider;

// The module system boots (System.initPhase2 and initPhase3): the boot layer
// holds the JDK's modules, so ServiceLoader sees their providers
// (jdk.localedata, jdk.charsets, jdk.jartool), a JDK class reports its named
// module and the exports are checked as on the JVM, and the application
// class loader is the system and context class loader. User classes sit in
// the boot loader's unnamed module here (they have no class loader), which
// is unnamed on the JVM too, so the rows read the same.
public class ModuleCheck {
    static class Inner {}

    static String fails(Callable<?> c) {
        try { c.call(); return "no exception"; } catch (Throwable t) { return t.getClass().getName(); }
    }

    public static void main(String[] args) throws Exception {
        ModuleLayer boot = ModuleLayer.boot();
        System.out.println("boot layer: " + (boot != null) + " " + boot.findModule("java.base").isPresent()
                           + " " + boot.findModule("jdk.localedata").isPresent() + " " + boot.findModule("jdk.charsets").isPresent()
                           + " " + boot.findModule("no.such").isPresent() + " " + (boot.modules().size() > 20)
                           + " " + boot.parents().equals(List.of(ModuleLayer.empty())));
        Module base = String.class.getModule();
        System.out.println("java.base: " + base.getName() + " " + base.isNamed() + " " + (base == Object.class.getModule())
                           + " " + (base.getClassLoader() == null) + " " + base.getDescriptor().isOpen() + " " + (base.getLayer() == boot)
                           + " " + (base == boot.findModule("java.base").get()));
        System.out.println("other modules: " + java.util.logging.Logger.class.getModule().getName()
                           + " " + java.sql.Date.class.getModule().getName() + " " + javax.xml.parsers.DocumentBuilder.class.getModule().getName()
                           + " " + java.sql.Date.class.getModule().getClassLoader().getClass().getName());
        System.out.println("arrays and primitives: " + int[].class.getModule().getName() + " " + String[][].class.getModule().getName()
                           + " " + int.class.getModule().getName() + " " + java.sql.Date[].class.getModule().getName());
        Module mine = ModuleCheck.class.getModule();
        System.out.println("unnamed: " + mine.isNamed() + " " + (mine.getName() == null) + " " + (mine == Inner.class.getModule())
                           + " " + ModuleCheck[].class.getModule().isNamed() + " " + (mine.getLayer() == null)
                           + " " + mine.getDescriptor());
        System.out.println("exports: " + base.isExported("java.lang") + " " + base.isExported("jdk.internal.misc")
                           + " " + base.isExported("jdk.internal.misc", mine) + " " + base.isOpen("java.lang") + " " + mine.isExported("anything")
                           + " " + base.getPackages().contains("java.util.zip") + " " + mine.canRead(base) + " " + base.canRead(mine)
                           + " " + base.getDescriptor().exports().stream().anyMatch(e -> e.source().equals("java.util")));
        // reflection into a JDK internal fails as on the JVM: the package isn't exported or opened to us
        System.out.println("internal access: " + fails(() -> Class.forName("jdk.internal.misc.VM").getMethod("initLevel").invoke(null))
                           + " " + fails(() -> { var f = String.class.getDeclaredField("value"); f.setAccessible(true); return f; })
                           + " " + fails(() -> Class.forName("jdk.internal.misc.VM")) + " " + Class.forName("jdk.internal.misc.VM").getModule().getName()
                           + " " + fails(() -> ArrayList.class.getMethod("size").invoke(new ArrayList<>())));
        ClassLoader scl = ClassLoader.getSystemClassLoader();
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        System.out.println("loaders: " + scl.getClass().getName() + " " + (Thread.currentThread().getContextClassLoader() == scl)
                           + " " + scl.getParent().getClass().getName() + " " + (scl.getParent() == platform) + " " + (platform.getParent() == null)
                           + " " + scl.getName() + " " + platform.getName() + " " + (scl.getUnnamedModule().getClassLoader() == scl));
        Thread t = new Thread(() -> {});
        System.out.println("inherited context loader: " + (t.getContextClassLoader() == scl));
        System.out.println("load through loaders: " + (scl.loadClass("ModuleCheck") == ModuleCheck.class)
                           + " " + (scl.loadClass("java.util.ArrayList") == ArrayList.class)
                           + " " + (Class.forName("java.sql.Date", false, platform) == java.sql.Date.class)
                           + " " + platform.loadClass("java.sql.Savepoint").getName() + " " + scl.loadClass("java.util.zip.Adler32").getModule().getName()
                           + " " + (Class.forName("ModuleCheck$Inner", false, scl) == Inner.class)
                           + " " + fails(() -> scl.loadClass("no.such.Klass")) + " " + fails(() -> Class.forName("Nope", true, scl))
                           + " " + (scl.getResource("ModuleCheck.class") != null) + " " + (scl.getResourceAsStream("ModuleCheck.class") != null)
                           + " " + (System.getProperty("java.class.path").length() > 0));
        System.out.println("module resources: " + (base.getResourceAsStream("java/lang/Object.class") != null)
                           + " " + (base.getResourceAsStream("java/lang/NoSuch.class") == null)
                           + " " + (java.sql.Date.class.getModule().getResourceAsStream("java/sql/Date.class") != null));
        // providers from the JDK's own modules, through their module descriptors
        System.out.println("locale data: " + NumberFormat.getCurrencyInstance(Locale.GERMANY).format(1234.5)
                           + " | " + DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH).format(LocalDate.of(2026, 10, 22))
                           + " | " + DateFormatSymbols.getInstance(Locale.JAPAN).getMonths()[0]
                           + " | " + String.format(Locale.FRANCE, "%,.2f", 1234.5) + " | " + (Locale.getAvailableLocales().length > 100)
                           + " | " + TimeZone.getTimeZone("Europe/Paris").getDisplayName(false, TimeZone.LONG, Locale.GERMAN));
        System.out.println("charsets: " + new String("日本".getBytes("Shift_JIS"), "Shift_JIS") + " " + Charset.isSupported("windows-1252")
                           + " " + Charset.isSupported("EUC-JP") + " " + Charset.forName("Big5").name() + " " + (Charset.availableCharsets().size() > 100)
                           + " " + fails(() -> Charset.forName("no-such-charset")) + " " + Arrays.toString("é".getBytes("windows-1252")));
        int providers = 0;
        for (var p : ServiceLoader.loadInstalled(java.nio.charset.spi.CharsetProvider.class)) providers++;
        System.out.println("installed charset providers: " + providers
                           + " " + boot.findModule("jdk.charsets").get().getDescriptor().provides().iterator().next().service());
        // jdk.jartool is defined to the application class loader
        System.out.println("tool providers: " + ToolProvider.findFirst("jar").isPresent() + " " + ToolProvider.findFirst("nosuchtool").isPresent()
                           + " " + ToolProvider.findFirst("jar").get().getClass().getModule().getName());
    }
}
