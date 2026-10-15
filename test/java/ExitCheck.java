// System.exit ends the program where it's called: the status reaches the
// launcher and nothing after the call runs.
public class ExitCheck {
  static void finish() {
    System.out.println("exiting from a nested call");
    System.exit(0);
  }

  public static void main(String[] args) {
    System.out.println("before");
    try {
      finish();
    } finally {
      System.out.println("finally must not run");
    }
    System.out.println("after must not run");
  }
}
