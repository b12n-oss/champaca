package d;

// v3: a field before s, so A's k lands on another slot. A's cached
// registration would put k where s is now; a restored A must be refused
// for its changed superclass, not just have its methods compiled again
public class S {
    public int p = 9;
    public int s = 5;
    public int sv() { return s + p; }
}
