public class LamMain {
    public static void main(String[] args) {
        li.Greeter g = () -> "bob";
        System.out.println(g.greet() + " " + g.shout());
    }
}
