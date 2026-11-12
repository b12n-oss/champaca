import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.stat.descriptive.*;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.distribution.*;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.apache.commons.math3.transform.*;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.random.*;
import org.apache.commons.math3.fraction.BigFraction;
public class Cmath {
  public static void main(String[] a) {
    RealMatrix m = new Array2DRowRealMatrix(new double[][] {{4, 1, 2}, {1, 3, 0}, {2, 0, 5}});
    System.out.println(new LUDecomposition(m).getDeterminant() + " " + new LUDecomposition(m).getSolver().solve(new ArrayRealVector(new double[] {1, 2, 3})));
    EigenDecomposition e = new EigenDecomposition(m);
    System.out.printf("%.10f %.10f %.10f%n", e.getRealEigenvalue(0), e.getRealEigenvalue(1), e.getRealEigenvalue(2));
    System.out.println(new SingularValueDecomposition(m).getConditionNumber());
    DescriptiveStatistics st = new DescriptiveStatistics(new double[] {2, 4, 4, 4, 5, 5, 7, 9});
    System.out.println(st.getMean() + " " + st.getStandardDeviation() + " " + st.getPercentile(90) + " " + st.getSkewness() + " " + st.getKurtosis());
    SimpleRegression r = new SimpleRegression(); for (int i = 0; i < 10; i++) r.addData(i, 3 * i + 1 + (i % 2 == 0 ? 0.5 : -0.5));
    System.out.println(r.getSlope() + " " + r.getIntercept() + " " + r.getRSquare());
    System.out.println(new NormalDistribution(0, 1).inverseCumulativeProbability(0.975) + " " + new TDistribution(10).cumulativeProbability(2.0) + " " + new GammaDistribution(2, 3).density(4));
    System.out.println(new BrentSolver(1e-12).solve(100, x -> Math.cos(x) - x, 0, 1));
    Complex[] fft = new FastFourierTransformer(DftNormalization.STANDARD).transform(new double[] {1, 2, 3, 4, 0, 0, 0, 0}, TransformType.FORWARD);
    System.out.println(fft[1] + " " + fft[2]);
    RandomGenerator g = new MersenneTwister(42); System.out.println(g.nextInt() + " " + g.nextLong() + " " + g.nextDouble() + " " + g.nextGaussian());
    System.out.println(new BigFraction(1, 3).add(new BigFraction(1, 6)).pow(20));
  }
}
