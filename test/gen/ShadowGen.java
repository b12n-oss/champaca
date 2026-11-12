import java.lang.classfile.*;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.constant.*;
import java.nio.file.*;

import static java.lang.constant.ConstantDescs.*;

// Writes class files in packages the JDK's modules hold, which javac won't
// compile outside those modules, into the directory given: a stand-in for
// javax.xml.XMLConstants (NULL_NS_URI "SHADOWED" and a static marker()), a
// class java.xml doesn't have, javax.xml.ChampacaOnly, and
// jdk.incubator.vector.ChampacaShadow, whose package's module the default
// boot layer doesn't resolve. ShadowCheck loads each through the class
// path: the JVM gives the JDK's XMLConstants, doesn't find ChampacaOnly, and
// finds ChampacaShadow, since nothing in the layer owns its package. Run
// with `java ShadowGen.java <dir>`.
public class ShadowGen {
    static void write(Path dir, String name, byte[] bytes) throws Exception {
        Path p = dir.resolve(name + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }
    static byte[] withMarker(String name, String fieldValue, String marker) {
        ClassDesc me = ClassDesc.ofInternalName(name);
        return ClassFile.of().build(me, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            if (fieldValue != null)
                clb.withField("NULL_NS_URI", CD_String, fb -> fb
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                    .with(ConstantValueAttribute.of(fieldValue)));
            clb.withMethodBody("marker", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cb -> {
                cb.ldc(marker);
                cb.areturn();
            });
        });
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        write(dir, "javax/xml/XMLConstants", withMarker("javax/xml/XMLConstants", "SHADOWED", "class path copy"));
        write(dir, "javax/xml/ChampacaOnly", withMarker("javax/xml/ChampacaOnly", null, "class path only"));
        write(dir, "jdk/incubator/vector/ChampacaShadow", withMarker("jdk/incubator/vector/ChampacaShadow", null, "class path, outside the layer"));
    }
}
