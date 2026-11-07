import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

// An upcall on a thread C started (ffi_apply_in_thread) calls System.exit(4):
// the process ends there with status 4 and the shutdown hooks run, as
// HotSpot (bb test builds target/libffitest).
public class UpcallThreadExit {
    static int target(int i) {
        System.out.println("exiting from " + Thread.currentThread().getName());
        System.exit(4);
        return i;
    }
    public static void main(String[] args) throws Throwable {
        Linker l = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of("target", System.mapLibraryName("ffitest")), Arena.global());
        MethodHandle in = l.downcallHandle(lib.findOrThrow("ffi_apply_in_thread"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        MemorySegment f = l.upcallStub(MethodHandles.lookup().findStatic(UpcallThreadExit.class, "target", MethodType.methodType(int.class, int.class)),
                                       FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        System.out.println("calling");
        System.out.println("returned " + (int) in.invokeExact(f, 1, 1));
    }
}
