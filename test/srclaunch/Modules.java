// what --add-modules gives a program: the modules in the boot layer, the
// option as the VM reports it, and whether a reserved -Djdk.module.* got through
public class Modules {
    public static void main(String[] args) {
        for (String m : new String[]{"jdk.incubator.vector", "java.se", "java.sql"})
            System.out.println(m + " " + ModuleLayer.boot().findModule(m).isPresent());
        System.out.println(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        for (String k : new String[]{"jdk.module.addmods", "jdk.module.path.x", "jdk.module.pathfoo", "foo"})
            System.out.println(k + " [" + System.getProperty(k) + "]");
    }
}
