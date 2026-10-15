// Doubles and floats inside string concatenation and a record's toString
// print as Double.toString / Float.toString do.
public class FloatFormatCheck {
  record P(double x, float y) {}

  public static void main(String[] args) {
    double[] ds = {1e10, 1.0 / 3, 0.001, 1e-5, 100.0, -0.0, Double.NaN,
                   Double.POSITIVE_INFINITY, Double.MIN_VALUE, 123456789.0};
    for (double d : ds) System.out.println("d=" + d);
    float[] fs = {0.1f, 1.0f / 3, 1e10f, 100.0f, Float.NaN, Float.NEGATIVE_INFINITY};
    for (float f : fs) System.out.println("f=" + f);
    System.out.println(new P(1e10, 0.1f));
  }
}
