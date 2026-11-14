// A static field read through a class that inherits one of that name from
// both an interface and a superclass resolves as JVMS 5.4.3.2 says: the
// class, then its superinterfaces recursively, then its superclass. Only the
// declaring type is initialized, so the log shows which one was found. javac
// refuses such a read as ambiguous, so FoRead's getstatic instructions come
// from test/gen/FieldOrderGen.java.
public class FieldOrderCheck {
    static final StringBuilder log = new StringBuilder();
    static String note(String who) { log.append(who).append(';'); return who; }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("FoRead");
        for (String name : new String[] {"sub1", "sub2", "sub3", "sub4", "sub5", "sub6"}) {
            log.setLength(0);
            Object v = c.getMethod(name).invoke(null);
            System.out.println(name + " " + v + " initialized " + log);
        }
    }
}

// the superclass and an interface both declare f
class FoBase { static String f = FieldOrderCheck.note("FoBase"); }
interface FoI { String f = FieldOrderCheck.note("FoI"); }
// a superinterface of a superinterface
interface FoJ extends FoI {}
interface FoNone {}
// an interface of a superclass, before that superclass's own superclass
class FoTop { static String f = FieldOrderCheck.note("FoTop"); }
interface FoK { String f = FieldOrderCheck.note("FoK"); }
class FoMid extends FoTop implements FoK {}

class FoSub1 extends FoBase implements FoI {}
class FoSub2 extends FoBase implements FoJ {}
class FoSub3 extends FoMid {}
class FoSub4 extends FoBase implements FoNone {}
class FoSub5 extends FoBase implements FoI { static String f = FieldOrderCheck.note("FoSub5"); }
// two interfaces: the first one listed that has the field
class FoSub6 extends FoBase implements FoNone, FoK, FoI {}
