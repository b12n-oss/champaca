import java.lang.invoke.*;
import java.util.function.*;

// A signature-polymorphic call (MethodHandle invoke and invokeExact, a
// VarHandle access mode) or an invokedynamic resolves the classes its
// descriptor names when it links, so a missing one is NoClassDefFoundError at
// that instruction, whatever the receiver; an array names the array. The two
// classes at the end are deleted before anything uses them.
public class SiteLinkCheck {
    interface Callable { Object call() throws Throwable; }
    static void t(String label, Callable r) {
        try { System.out.println(label + ": " + r.call()); }
        catch (Throwable e) { System.out.println(label + ": " + e + (e.getCause() == null ? "" : " | cause " + e.getCause())); }
    }
    static void take(Object o) {}
    static Object give() { return null; }
    static Object fld;
    static MethodHandle take, give, nul;
    static VarHandle vh;
    static Object invokeArg() throws Throwable { take.invoke((SiteGone) null); return "ran"; }
    static Object invokeRet() throws Throwable { SiteGone g = (SiteGone) give.invoke(); return "ran " + g; }
    static Object exactArg() throws Throwable { take.invokeExact((SiteGone) null); return "ran"; }
    static Object nullInvoke() throws Throwable { nul.invoke((SiteGone) null); return "ran"; }
    static Object twoMissing() throws Throwable { SiteGone g = (SiteGone) give.invoke((SiteGone2) null); return "ran"; }
    static Object vhSet() throws Throwable { vh.set((SiteGone) null); return "ran"; }
    static Object vhGet() throws Throwable { SiteGone g = (SiteGone) vh.get(); return "ran"; }
    static Object lambda() { SiteGone g = null; Runnable r = () -> System.out.println(g); return "made"; }
    static Object invokeArray() throws Throwable { take.invoke((SiteGone[]) null); return "ran"; }
    static Object invokeArray2() throws Throwable { take.invoke((SiteGone[][]) null); return "ran"; }
    static String plain(SiteGone g, SiteGone2[] h) { return "plain " + (g == null) + " " + (h == null); }
    static Object plainCall() { return plain(null, null); }
    static Object concat2(SiteGone g) { return "x" + g; }
    static Object concat() { return concat2(null); }
    static Object notTaken(boolean b) throws Throwable { if (b) take.invoke((SiteGone) null); return "skipped"; }
    public static void main(String[] a) throws Throwable {
        var L = MethodHandles.lookup();
        take = L.findStatic(SiteLinkCheck.class, "take", MethodType.methodType(void.class, Object.class));
        give = L.findStatic(SiteLinkCheck.class, "give", MethodType.methodType(Object.class));
        vh = L.findStaticVarHandle(SiteLinkCheck.class, "fld", Object.class);
        for (String n : new String[] {"SiteGone", "SiteGone2"})
            new java.io.File(SiteLinkCheck.class.getResource(n + ".class").toURI()).delete();
        t("not taken", () -> notTaken(false));
        t("invoke arg", SiteLinkCheck::invokeArg);
        t("invoke arg again", SiteLinkCheck::invokeArg);
        t("invoke ret", SiteLinkCheck::invokeRet);
        t("invokeExact arg", SiteLinkCheck::exactArg);
        t("null handle", SiteLinkCheck::nullInvoke);
        t("two missing", SiteLinkCheck::twoMissing);
        t("vh set", SiteLinkCheck::vhSet);
        t("vh get", SiteLinkCheck::vhGet);
        t("lambda", SiteLinkCheck::lambda);
        t("invoke array", SiteLinkCheck::invokeArray);
        t("invoke 2d array", SiteLinkCheck::invokeArray2);
        t("concat", SiteLinkCheck::concat);
        t("plain invoke", SiteLinkCheck::plainCall);
    }
}
class SiteGone {}
class SiteGone2 {}
