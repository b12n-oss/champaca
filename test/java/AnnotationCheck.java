import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

// Annotations (values of every kind, defaults, every target), generic
// signatures, and nested, local and anonymous classes: reflection metadata
// read from the classfile's attributes.
public class AnnotationCheck {
  enum Color { RED, GREEN }
  @Retention(RetentionPolicy.RUNTIME) @interface Inner { String value(); }
  @Retention(RetentionPolicy.RUNTIME)
  @interface Info {
    String name() default "none";
    int count() default 7;
    long big() default 1L << 40;
    double ratio() default 0.5;
    boolean flag() default false;
    char ch() default 'c';
    Color color() default Color.GREEN;
    Class<?> type() default Object.class;
    String[] tags() default {};
    int[] nums() default {1, 2};
    Inner inner() default @Inner("d");
  }
  @Retention(RetentionPolicy.CLASS) @interface NotAtRuntime {}

  @Info(name = "box", count = 3, tags = {"a", "b"}, color = Color.RED, type = String.class, inner = @Inner("i"))
  static class Box<T extends Comparable<T>> extends ArrayList<T> implements Comparable<Box<T>> {
    @Info(flag = true) @NotAtRuntime public Map<String, List<Integer>> table;
    @Info public Box() {}
    @Info(ch = 'z') public <U> List<U> pick(@Info(nums = {9}) T t, U u) { return null; }
    public int compareTo(Box<T> o) { return 0; }
  }
  private static class Hidden {}
  class NonStatic {}

  static String show(Info i) {
    return i.name() + " " + i.count() + " " + i.big() + " " + i.ratio() + " " + i.flag() + " " + i.ch() + " "
        + i.color() + " " + i.type().getSimpleName() + " " + Arrays.toString(i.tags()) + " "
        + Arrays.toString(i.nums()) + " " + i.inner().value() + " " + i.annotationType().getSimpleName();
  }

  public static void main(String[] args) throws Exception {
    Class<?> b = Box.class;
    System.out.println(show(b.getAnnotation(Info.class)));
    Field table = b.getField("table");
    System.out.println(show(table.getAnnotation(Info.class)) + " | " + table.getAnnotations().length + " " + table.isAnnotationPresent(NotAtRuntime.class));
    System.out.println(show(b.getConstructor().getAnnotation(Info.class)));
    Method pick = b.getMethod("pick", Comparable.class, Object.class);
    System.out.println(show(pick.getAnnotation(Info.class)));
    System.out.println(show((Info) pick.getParameterAnnotations()[0][0]) + " " + pick.getParameterAnnotations()[1].length);
    System.out.println(Info.class.getMethod("count").getDefaultValue() + " " + Info.class.isAnnotation() + " " + b.getAnnotation(Inner.class));
    int[] nums = b.getAnnotation(Info.class).nums();
    nums[0] = 99;
    System.out.println(b.getAnnotation(Info.class).nums()[0]);

    System.out.println(table.getGenericType());
    System.out.println(pick.getGenericReturnType() + " " + Arrays.toString(pick.getGenericParameterTypes()) + " " + Arrays.toString(pick.getTypeParameters()));
    System.out.println(b.getGenericSuperclass() + " " + Arrays.toString(b.getGenericInterfaces()));
    TypeVariable<?> t = b.getTypeParameters()[0];
    System.out.println(t + " " + Arrays.toString(t.getBounds()));

    System.out.println(b.getSimpleName() + " " + b.getName() + " " + b.getDeclaringClass().getSimpleName() + " " + b.isMemberClass() + " " + Modifier.toString(b.getModifiers()));
    System.out.println(Modifier.toString(Hidden.class.getModifiers()) + " | " + Modifier.toString(NonStatic.class.getModifiers()) + " " + NonStatic.class.getEnclosingClass().getSimpleName());
    class Local {}
    Runnable anon = new Runnable() { public void run() {} };
    System.out.println(Local.class.getSimpleName() + " " + Local.class.isLocalClass() + " " + Local.class.getEnclosingMethod().getName()
        + " " + anon.getClass().isAnonymousClass() + " [" + anon.getClass().getSimpleName() + "] " + anon.getClass().getEnclosingMethod().getName());
    System.out.println(AnnotationCheck.class.getSimpleName() + " " + AnnotationCheck.class.getDeclaringClass() + " " + AnnotationCheck.class.isMemberClass());
  }
}
