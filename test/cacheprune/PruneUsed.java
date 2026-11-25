// A generation a run loads from is marked used, and survives the pruning a
// later save runs; bb.edn's cache-prune row ages every file first.
public class PruneUsed {
    public static void main(String[] args) {
        System.out.println(String.format("%05d %s", 42, java.time.LocalDate.of(2024, 1, 2)));
    }
}
