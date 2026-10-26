import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Given a class file and a destination, copies one over the other after Q
// has loaded, then calls a method of Q nothing has called yet, which runs
// the Q already loaded, as the JVM does.
public class EditMain {
    public static void main(String[] args) throws Exception {
        System.out.println(Q.a());
        if (args.length == 2) {
            Files.copy(Path.of(args[0]), Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
            System.out.println(Q.b());
        }
    }
}
