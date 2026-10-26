import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Given a class file and a destination, copies one over the other after Q
// has loaded, then reads a frame of Q and calls a method of Q nothing has
// called yet: both are the Q already loaded, as on the JVM.
public class EditMain {
    public static void main(String[] args) throws Exception {
        System.out.println(Q.a());
        System.out.println(Q.where());
        if (args.length == 2) {
            Files.copy(Path.of(args[0]), Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
            System.out.println(Q.where());
            System.out.println(Q.b());
        }
    }
}
