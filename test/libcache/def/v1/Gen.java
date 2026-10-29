// Defined by DefMain through MethodHandles.lookup().defineClass, so by the
// application loader, which the cache doesn't keep: A2, which extends it and
// reads g, can't be cached either, since Gen's bytes can't be checked next run
public class Gen {
    public int g = 3;
}
