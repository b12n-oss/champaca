import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;

// errno captured by a downcall (Linker.Option.captureCallState), the call
// state's layout and its errno read back. jolt test also runs this on a
// fresh cache of its own, then again warm: the static the capture writes
// through (ValueLayout.JAVA_INT) was unset on every warm run while the
// module-graph archive's walk gave unregistered statics a label of their own.
public class CaptureStateCheck {
  public static void main(String[] a) throws Throwable {
    Linker L = Linker.nativeLinker();
    var cs = Linker.Option.captureCallState("errno");
    var h = L.downcallHandle(L.defaultLookup().findOrThrow("open"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), cs);
    var csl = Linker.Option.captureStateLayout();
    System.out.println("layout: " + csl);
    var eh = csl.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    try (Arena ar = Arena.ofConfined()) {
      var st = ar.allocate(csl);
      int fd = (int) h.invokeExact(st, ar.allocateFrom("/nonexistent/x"), 0);
      System.out.println("open: " + fd + ", errno ENOENT: " + ((int) eh.get(st, 0L) == 2));
      int ok = (int) h.invokeExact(st, ar.allocateFrom("/"), 0);
      System.out.println("open /: " + (ok >= 0));
    }
  }
}
