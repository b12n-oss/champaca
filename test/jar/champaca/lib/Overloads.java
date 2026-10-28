package champaca.lib;

// Overloads where an integer from jolt could narrow to a byte. Java never
// narrows an argument, so pick(5) is pick(Object...) and wide(5) is
// wide(long); only only(5) needs the byte. examples/jars.clj checks that
// champaca chooses the same.
public class Overloads {
  public static String pick(byte b) { return "byte " + b; }
  public static String pick(Object... xs) { return "varargs " + xs.length; }

  public static String wide(byte b) { return "byte " + b; }
  public static String wide(long l) { return "long " + l; }

  public static String only(byte b) { return "byte " + b; }
}
