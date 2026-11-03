public class Throws {
    public static void main(String[] args) {
        System.out.println("before");
        deeper();
    }

    static void deeper() {
        throw new IllegalStateException("from a source file");
    }
}
