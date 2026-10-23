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
// class loader is the system and context class loader. A classpath class
// reports the application loader and sits in its unnamed module, as on the
// JVM; the built-in loaders see what the JVM's do.
public class ModuleCheck {
    static class Inner {}

    static String fails(Callable<?> c) {
        try { c.call(); return "no exception"; } catch (Throwable t) { return t.getClass().getName(); }
    }

    // a class that exists but can't be loaded is a LinkageError (the JVM's ClassFormatError,
    // champaca's NoClassDefFoundError), never "not found"
    static String loads(Callable<?> c) {
        try { c.call(); return "loaded"; } catch (LinkageError e) { return "LinkageError"; } catch (Throwable t) { return t.getClass().getName(); }
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
        System.out.println("packages: " + String.class.getPackage().isSealed() + " " + String.class.getPackage().getImplementationVersion()
                           + " " + (String.class.getPackage() == Object.class.getPackage()) + " " + ModuleCheck.class.getPackage().isSealed()
                           + " " + java.sql.Date.class.getPackage().isSealed() + " " + java.sql.Date.class.getPackage().getName());
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
        // a classpath class's loader is the application loader, its module that loader's unnamed one
        System.out.println("class loaders: " + (ModuleCheck.class.getClassLoader() == scl) + " " + (Inner.class.getClassLoader() == scl)
                           + " " + (ModuleCheck[].class.getClassLoader() == scl) + " " + (String.class.getClassLoader() == null)
                           + " " + (int[].class.getClassLoader() == null) + " " + (java.sql.Date.class.getClassLoader() == platform)
                           + " " + (mine == scl.getUnnamedModule()) + " " + (mine.getClassLoader() == scl) + " " + (Inner.class.getModule() == mine)
                           + " " + (ModuleCheck.class.getClassLoader().getResourceAsStream("ModuleCheck.class") != null)
                           + " " + (ModuleCheck.class.getClassLoader().getResource("ModuleCheck.class") != null)
                           + " " + (ModuleCheck.class.getResourceAsStream("ModuleCheck.class") != null));
        // Class.forName through each built-in loader: null is the boot loader
        System.out.println("forName by loader: " + (Class.forName("ModuleCheck") == ModuleCheck.class) + " " + (Class.forName("ModuleCheck", false, scl) == ModuleCheck.class)
                           + " " + fails(() -> Class.forName("ModuleCheck", false, platform)) + " " + fails(() -> Class.forName("ModuleCheck", false, null))
                           + " " + (Class.forName("java.lang.String", false, null) == String.class) + " " + fails(() -> Class.forName("java.sql.Date", false, null))
                           + " " + fails(() -> Class.forName("sun.tools.jar.JarToolProvider", false, null)) + " " + (Class.forName("[LModuleCheck;", false, scl) == ModuleCheck[].class)
                           + " " + fails(() -> Class.forName("[LModuleCheck;", false, platform)) + " " + fails(() -> Class.forName("[LModuleCheck;", false, null)));
        // a loader of the program's own is asked for an array's element class; the boot loader never delegates to anyone
        ClassLoader own = new ClassLoader(scl) {};
        ClassLoader refusing = new ClassLoader(scl) {
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (n.startsWith("ModuleCheck")) throw new ClassNotFoundException("refused " + n);
                return super.loadClass(n, resolve);
            }
        };
        Class<?> proxy = java.lang.reflect.Proxy.newProxyInstance(scl, new Class<?>[]{Runnable.class}, (p, m, a) -> null).getClass();
        System.out.println("forName by own loader: " + (Class.forName("[LModuleCheck;", false, own) == ModuleCheck[].class)
                           + " " + (Class.forName("[[I", false, own) == int[][].class) + " " + fails(() -> Class.forName("[Lno.Such;", false, own))
                           + " " + fails(() -> Class.forName("[LModuleCheck;", false, refusing)) + " " + fails(() -> Class.forName("ModuleCheck", false, refusing))
                           + " " + (Class.forName("[[I", false, refusing) == int[][].class)
                           + " " + (Class.forName("ModuleCheck", false, own) == ModuleCheck.class) + " " + (Class.forName("[Ljava.sql.Date;", false, own) == java.sql.Date[].class)
                           + " " + proxy.getModule().isNamed() + " " + (Class.forName(proxy.getName(), false, scl) == proxy)
                           + " " + fails(() -> Class.forName(proxy.getName(), false, null)) + " " + fails(() -> Class.forName(proxy.getName(), false, platform)));
        System.out.println("load through loaders: " + (scl.loadClass("ModuleCheck") == ModuleCheck.class)
                           + " " + (scl.loadClass("java.util.ArrayList") == ArrayList.class)
                           + " " + (Class.forName("java.sql.Date", false, platform) == java.sql.Date.class)
                           + " " + platform.loadClass("java.sql.Savepoint").getName() + " " + scl.loadClass("java.util.zip.Adler32").getModule().getName()
                           + " " + (Class.forName("ModuleCheck$Inner", false, scl) == Inner.class)
                           + " " + fails(() -> scl.loadClass("no.such.Klass")) + " " + fails(() -> Class.forName("Nope", true, scl))
                           + " " + (scl.getResource("ModuleCheck.class") != null) + " " + (scl.getResourceAsStream("ModuleCheck.class") != null)
                           + " " + (System.getProperty("java.class.path").length() > 0));
        // what each built-in loader sees: a binary name only, and only the modules defined to it or its parents
        System.out.println("loader visibility: " + fails(() -> platform.loadClass("ModuleCheck")) + " " + fails(() -> scl.loadClass("java/lang/String"))
                           + " " + fails(() -> scl.loadClass("[Ljava.lang.String;")) + " " + fails(() -> scl.loadClass("int"))
                           + " " + platform.loadClass("java.util.zip.CRC32").getName() + " " + fails(() -> platform.loadClass("sun.tools.jar.JarToolProvider"))
                           + " " + fails(() -> Class.forName("java/lang/String")) + " " + fails(() -> Class.forName("int"))
                           + " " + Class.forName("[Ljava.lang.String;").getSimpleName());
        // Truncated.class (test/gen/TruncatedGen.java) is half a class file: present, unloadable
        System.out.println("exists but can't load: " + loads(() -> Class.forName("Truncated")) + " " + loads(() -> scl.loadClass("Truncated"))
                           + " " + loads(() -> Class.forName("Truncated", false, scl)) + " " + loads(() -> Class.forName("NoSuchClassAtAll")));
        // a loader with no parent delegates to the bootstrap loader, which has the boot modules only
        ClassLoader parentless = new ClassLoader(null) {};
        System.out.println("bootstrap delegation: " + parentless.loadClass("java.lang.String").getName() + " " + fails(() -> parentless.loadClass("java.sql.Date"))
                           + " " + fails(() -> parentless.loadClass("ModuleCheck")) + " " + (parentless.getParent() == null));
        System.out.println("module resources: " + (base.getResourceAsStream("java/lang/Object.class") != null)
                           + " " + (base.getResourceAsStream("ModuleCheck.class") == null) + " " + (base.getResourceAsStream("java/sql/Date.class") == null)
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
