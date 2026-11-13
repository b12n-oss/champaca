// System.exit from a thread other than main: the process ends there, and the
// startup cache must still be saved, as when main returns (bb.edn's exit-save
// row checks a generation file appears in a fresh cache).
public class ExitSave {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            System.out.println(String.format("%05d %s", 42, java.time.LocalDate.of(2024, 1, 2)));
            System.exit(3);
        });
        t.start();
        t.join();
        System.out.println("main returned");
    }
}
