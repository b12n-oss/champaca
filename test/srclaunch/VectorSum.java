// the Vector API from a source file: javac on champaca sees the module only
// when --add-modules names it, as on HotSpot
import jdk.incubator.vector.*;

public class VectorSum {
    public static void main(String[] args) {
        var sp = IntVector.SPECIES_128;
        int[] x = {1, 2, 3, 4}, y = {10, 20, 30, 40};
        var sum = IntVector.fromArray(sp, x, 0).add(IntVector.fromArray(sp, y, 0));
        System.out.println(sum + " " + sum.reduceLanes(VectorOperators.ADD));
    }
}
