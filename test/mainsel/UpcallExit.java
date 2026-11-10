import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

// An upcall's target that calls System.exit(3) from inside C: the shutdown hooks run and
// the status is 3, as HotSpot (jolt test builds target/libffitest).
public class UpcallExit {
    static int target(int i) {
        System.out.println("exiting from the upcall");
        System.exit(3);
        return i;
    }
    public static void main(String[] args) throws Throwable {
        Linker l = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of("target", System.mapLibraryName("ffitest")), Arena.global());
        MethodHandle apply = l.downcallHandle(lib.findOrThrow("ffi_apply_int"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        MemorySegment f = l.upcallStub(MethodHandles.lookup().findStatic(UpcallExit.class, "target", MethodType.methodType(int.class, int.class)),
                                       FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        System.out.println("calling");
        System.out.println("returned " + (int) apply.invokeExact(f, 1));
    }
}
