import java.io.IOException;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

// Record components, parameter names, checked exceptions and type
// annotations, as reflection reports them. bb test compiles with -parameters,
// so this file's methods have real names and the JDK's are synthesized.
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@interface Tag { String value(); }

@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
@interface NonEmpty {}

record Pair<A, B>(@Tag("left") A first, B second) {}

record Range(int lo, int hi) {
    Range {
        if (lo > hi) throw new IllegalArgumentException("lo > hi: " + lo + " " + hi);
    }
    int size() { return hi - lo; }
}

record Empty() {}

record Holder(@NonEmpty String name, List<@NonEmpty String> tags, int[] counts) {}

class Ops {
    public Ops(String label, int size) {}
    public static void open(String path, final int mode) throws IOException, InterruptedException {}
    public <T extends Exception> void rethrow(T t) throws T { throw t; }
    public @NonEmpty String name(@Tag("who") @NonEmpty String who) { return who; }
    class Inner { Inner(int n) {} }
}

enum Level { LOW, HIGH }

public class RecordCheck {
    static String params(Executable e) {
        StringBuilder sb = new StringBuilder(e.getName() + "(");
        for (Parameter p : e.getParameters()) {
            sb.append(p.getName()).append(':').append(p.getType().getSimpleName())
              .append(p.isNamePresent() ? "" : "?")
              .append(p.isImplicit() ? " implicit" : "")
              .append(p.isSynthetic() ? " synthetic" : "")
              .append(Modifier.isFinal(p.getModifiers()) ? " final" : "")
              .append(' ');
        }
        return sb.toString().trim() + ")";
    }

    public static void main(String[] args) throws Exception {
        // records
        for (Class<?> c : new Class<?>[] {Pair.class, Range.class, Empty.class, Holder.class, Ops.class}) {
            RecordComponent[] rcs = c.getRecordComponents();
            System.out.println(c.getName() + " record=" + c.isRecord()
                + " components=" + (rcs == null ? "null" : rcs.length));
            if (rcs == null) continue;
            for (RecordComponent rc : rcs) {
                System.out.println("  " + rc + " | " + rc.getName() + " " + rc.getType().getName()
                    + " generic=" + rc.getGenericType().getTypeName()
                    + " sig=" + rc.getGenericSignature()
                    + " accessor=" + rc.getAccessor().getName()
                    + " declaring=" + rc.getDeclaringRecord().getSimpleName()
                    + " annotations=" + Arrays.toString(rc.getAnnotations())
                    + " typeAnnotations=" + Arrays.toString(rc.getAnnotatedType().getAnnotations()));
            }
        }
        Pair<String, Integer> p = new Pair<>("a", 1);
        RecordComponent first = Pair.class.getRecordComponents()[0];
        System.out.println(first.getAccessor().invoke(p) + " " + first.getAnnotation(Tag.class).value()
            + " " + first.isAnnotationPresent(NonEmpty.class));
        System.out.println(p + " " + p.equals(new Pair<>("a", 1)) + " " + (p.hashCode() == new Pair<>("a", 1).hashCode()));

        // the canonical constructor, the way serialization libraries find it
        Class<?>[] types = Arrays.stream(Range.class.getRecordComponents())
            .map(RecordComponent::getType).toArray(Class<?>[]::new);
        Constructor<?> canon = Range.class.getDeclaredConstructor(types);
        System.out.println(params(canon) + " -> " + canon.newInstance(2, 5));
        try {
            canon.newInstance(5, 2);
        } catch (InvocationTargetException e) {
            System.out.println("ITE " + e.getCause().getMessage());
        }

        // parameter names: compiled with -parameters here, synthesized for the JDK
        for (Constructor<?> k : Ops.class.getDeclaredConstructors()) System.out.println(params(k));
        System.out.println(params(Ops.Inner.class.getDeclaredConstructors()[0]));
        System.out.println(params(Ops.class.getMethod("open", String.class, int.class)));
        System.out.println(params(Ops.class.getMethod("name", String.class)));
        System.out.println(params(Level.class.getMethod("valueOf", String.class)));
        System.out.println(params(String.class.getMethod("regionMatches", int.class, String.class, int.class, int.class)));
        System.out.println(Ops.class.getMethod("open", String.class, int.class).getParameters()[0]);

        // checked exceptions
        Method open = Ops.class.getMethod("open", String.class, int.class);
        System.out.println(Arrays.toString(open.getExceptionTypes()) + " " + open);
        Method rethrow = Ops.class.getMethod("rethrow", Exception.class);
        System.out.println(Arrays.toString(rethrow.getExceptionTypes()) + " "
            + Arrays.toString(rethrow.getGenericExceptionTypes()) + " " + rethrow.toGenericString());
        System.out.println(Arrays.toString(Thread.class.getMethod("sleep", long.class).getExceptionTypes()));
        System.out.println(Arrays.toString(Class.class.getMethod("forName", String.class).getExceptionTypes()));
        System.out.println(Arrays.toString(Object.class.getConstructor().getExceptionTypes()));

        // type annotations
        Method name = Ops.class.getMethod("name", String.class);
        System.out.println(Arrays.toString(name.getAnnotatedReturnType().getAnnotations()));
        System.out.println(Arrays.toString(name.getAnnotatedParameterTypes()[0].getAnnotations())
            + " " + Arrays.toString(name.getParameters()[0].getAnnotations()));
        Field tags = Holder.class.getDeclaredField("tags");
        AnnotatedParameterizedType apt = (AnnotatedParameterizedType) tags.getAnnotatedType();
        System.out.println(Arrays.toString(apt.getAnnotatedActualTypeArguments()[0].getAnnotations()));
        System.out.println(Arrays.toString(Holder.class.getDeclaredField("name").getAnnotatedType().getAnnotations()));

        // annotations print, compare and hash as the JDK's own do
        Tag left = Pair.class.getRecordComponents()[0].getAnnotation(Tag.class);
        Tag left2 = Pair.class.getDeclaredField("first").getAnnotation(Tag.class);
        Tag who = name.getParameters()[0].getAnnotation(Tag.class);
        System.out.println(left.equals(left2) + " " + left.equals(who) + " " + left.equals("left") + " " + left.equals(null)
            + " " + left.hashCode() + " " + (left.hashCode() == left2.hashCode()) + " " + who.hashCode()
            + " " + left.annotationType().getSimpleName() + " " + left.value());
        Annotation ne = name.getAnnotatedReturnType().getAnnotations()[0];
        System.out.println(ne + " " + ne.hashCode() + " " + ne.equals(Holder.class.getDeclaredField("name").getAnnotatedType().getAnnotations()[0]));
        Set<Annotation> set = new HashSet<>(List.of(left));
        System.out.println(set.contains(left2) + " " + set.contains(who));
    }
}
