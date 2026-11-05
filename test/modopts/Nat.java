import java.lang.foreign.*;

// A restricted method (Linker.downcallHandle) from the unnamed module, under
// --illegal-native-access and --enable-native-access. The refusal's message
// names the class path URL, so only its start is printed.
public class Nat {
    public static void main(String[] a) throws Throwable {
        try {
            Linker l = Linker.nativeLinker();
            var h = l.downcallHandle(l.defaultLookup().find("getpid").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            System.out.println("downcall ok " + ((int) h.invokeExact() > 0));
        } catch (IllegalCallerException e) {
            System.out.println("IllegalCallerException " + e.getMessage().startsWith("Illegal native access from an unnamed module"));
        }
    }
}
