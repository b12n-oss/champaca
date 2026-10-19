import java.net.IDN;
import java.text.Normalizer;

// The JDK's classes and resources as the runtime image holds them.
public class ImageCheck {
    public static void main(String[] args) throws Exception {
        ClassLoader app = ImageCheck.class.getClassLoader();
        // the image's /packages/sun.reflect lists java.base first, which has
        // the directory only on the way to sun/reflect/annotation and the
        // like; the class is in jdk.unsupported
        System.out.println(Class.forName("sun.reflect.ReflectionFactory", false, app).getName());
        // the same for java.awt: java.datatransfer, then java.desktop
        System.out.println(Class.forName("java.awt.Point", false, app).getName());
        System.out.println(Class.forName("java.sql.Timestamp", false, app).getName());

        // a missing class in a JDK package, in a package no module holds, and
        // in a directory every module has on the way down
        for (String n : new String[] {"java.lang.NoSuchClass", "no.such.pkg.Thing", "java.Thing"}) {
            try {
                Class.forName(n, false, app);
                System.out.println("found " + n);
            } catch (ClassNotFoundException e) {
                System.out.println("not found: " + e.getMessage());
            }
        }

        // resources in directories with no classes: ICU's normalization data
        // and the IDN string-prep profile
        String nfc = Normalizer.normalize("é", Normalizer.Form.NFC);
        System.out.println(nfc.length() + " " + Integer.toHexString(nfc.charAt(0)));
        System.out.println(Normalizer.normalize("é", Normalizer.Form.NFD).length());
        System.out.println(IDN.toASCII("bücher.example"));
    }
}
