import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Log {
    public static void main(String[] a) {
        Logger log = LoggerFactory.getLogger(Log.class);
        log.info("hello {} from slf4j", "champaca");
        log.warn("a warning with {} args", 2);
        log.debug("not shown at the default level");
        System.out.println("logger " + log.getClass().getSimpleName());
    }
}
