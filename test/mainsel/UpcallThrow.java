import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

// An upcall's target that throws: the JDK's upcall wrapper prints the trace and its
// line, then System.exit(1), as HotSpot (the stub's hidden class leaves no
// frame) (bb test builds target/libffitest).
public class UpcallThrow {
    static int target(int i) {
        throw new IllegalStateException("boom " + i);
    }
    public static void main(String[] args) throws Throwable {
        Linker l = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of("target", System.mapLibraryName("ffitest")), Arena.global());
        MethodHandle apply = l.downcallHandle(lib.findOrThrow("ffi_apply_int"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        MemorySegment f = l.upcallStub(MethodHandles.lookup().findStatic(UpcallThrow.class, "target", MethodType.methodType(int.class, int.class)),
                                       FunctionDescriptor.of(JAVA_INT, JAVA_INT), Arena.global());

        System.out.println("calling");
        System.out.println("returned " + (int) apply.invokeExact(f, 1));
    }
}
