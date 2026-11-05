// The default boot layer: a module it leaves out (jdk.incubator.vector) has no
// classes for Class.forName, one it holds does; the layer's module names.
public class Inc { public static void main(String[] a) throws Exception {
  for (String c : new String[]{"jdk.incubator.vector.IntVector", "jdk.jsobject.JSObject", "netscape.javascript.JSObject", "jdk.internal.misc.VM"}) {
    try { Class<?> k = Class.forName(c); System.out.println(c + ": found in " + k.getModule()); } catch (ClassNotFoundException e) { System.out.println(c + ": CNFE"); } }
  System.out.println(ModuleLayer.boot().modules().stream().map(Module::getName).sorted().toList());
} }
