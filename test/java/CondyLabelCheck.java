// A pattern switch whose labels are dynamic constants javac doesn't make
// (ConstantBootstraps.invoke computing an Integer and a String) runs as the
// JDK's SwitchBootstraps.typeSwitch decides: the index of the first label
// that matches, the label count when none does, -1 for null. CondyLabels comes
// from test/gen/CondyLabelGen.java.
public class CondyLabelCheck {
    public static void main(String[] args) throws Exception {
        java.lang.reflect.Method index = Class.forName("CondyLabels").getMethod("index", Object.class);
        Object[] inputs = {42, 43, "7", "8", 7, 'x', (short) 42, (byte) 42, 4.2, null};
        for (Object o : inputs) {
            String shown = o == null ? "null" : o.getClass().getSimpleName() + " " + o;
            System.out.println(shown + " -> " + index.invoke(null, o));
        }
    }
}
