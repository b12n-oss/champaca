// a preview feature (JEP 507, primitive types in patterns): javac refuses it
// without --enable-preview, runs it with
public class Prim {
    public static void main(String[] args) {
        Object o = 300;
        String r = switch (o) {
            case byte b -> "byte " + b;
            case int i -> "int " + i;
            default -> "other";
        };
        System.out.println(r);
    }
}
