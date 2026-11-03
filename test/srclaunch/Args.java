// what a source-file program sees of its launch: the VM options (the class
// path ones aren't listed, --source is a property, --add-modules=ALL-DEFAULT
// last), the class path, the command, assertions, its arguments
public class Args {
    public static void main(String[] args) {
        System.out.println(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("class path [" + System.getProperty("java.class.path") + "]");
        System.out.println("command [" + System.getProperty("sun.java.command") + "]");
        System.out.println("source [" + System.getProperty("jdk.internal.javac.source") + "]");
        System.out.println("x [" + System.getProperty("x") + "]");
        boolean on = false;
        assert on = true;
        System.out.println("assertions " + on);
        System.out.println("args " + String.join(",", args));
        System.out.println("loader " + (Args.class.getClassLoader() == ClassLoader.getSystemClassLoader()));
    }
}
