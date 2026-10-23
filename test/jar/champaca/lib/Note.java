package champaca.lib;

import java.io.Serializable;

/** A serializable class of the program's own, for examples/serialization.clj. */
public class Note implements Serializable {
    public String title;
    public int stars;
    public transient String scratch = "lost";

    public Note() {}
    public Note(String title, int stars) { this.title = title; this.stars = stars; }

    @Override public String toString() { return "Note(" + title + ", " + stars + ", " + scratch + ")"; }
}
