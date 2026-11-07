package app;

import com.greet.Greeter;

// java -p: the main class and the module it requires are named modules of the
// application loader, in the boot layer, as HotSpot's launcher resolves them
public class Main {
    public static void main(String[] a) throws Exception {
        System.out.println(Greeter.hello("world"));
        Module m = Main.class.getModule();
        System.out.println("module " + m.getName() + " named " + m.isNamed() + " reads greet " + m.canRead(Greeter.class.getModule()));
        System.out.println("loader " + Main.class.getClassLoader().getName() + " same " + (Main.class.getClassLoader() == Greeter.class.getClassLoader()));
        System.out.println("layer has app " + ModuleLayer.boot().findModule("app").isPresent() + " greet " + ModuleLayer.boot().findModule("com.greet").isPresent());
        System.out.println("descriptor " + m.getDescriptor().requires().stream().map(r -> r.name()).sorted().toList());
        System.out.println("exports " + Greeter.class.getModule().getDescriptor().exports().stream().map(e -> e.source()).toList());
        System.out.println("java.class.path [" + System.getProperty("java.class.path") + "] jdk.module.path " + System.getProperty("jdk.module.path"));
        System.out.println("forName " + Class.forName("com.greet.Greeter").getName());
        System.out.println("args " + java.util.Arrays.toString(a));
        // com.greet doesn't open com.greet: its resources there are its own
        System.out.println("class resource " + read(com.greet.Hi.class.getResourceAsStream("msg.txt"))
            + " | module resource " + read(com.greet.Hi.class.getModule().getResourceAsStream("com/greet/msg.txt"))
            + " | loader resource " + read(ClassLoader.getSystemClassLoader().getResourceAsStream("com/greet/msg.txt"))
            + " | loader url " + (ClassLoader.getSystemClassLoader().getResource("com/greet/msg.txt") != null));
        System.out.println(com.greet.Hi.own());
        System.out.println("app top " + read(com.greet.Hi.class.getResourceAsStream("/top.txt"))
            + " | loader top " + read(ClassLoader.getSystemClassLoader().getResourceAsStream("top.txt")));
        for (Object s : java.util.ServiceLoader.load(java.util.function.Supplier.class))
            System.out.println("service " + ((java.util.function.Supplier<?>) s).get());
    }

    static String read(java.io.InputStream in) throws java.io.IOException {
        return in == null ? "null" : new String(in.readAllBytes()).trim();
    }
}
