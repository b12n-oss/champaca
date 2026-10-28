package champaca.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An annotation with an element of every kind, for examples/classes.clj. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Tag {
    String name();
    boolean on() default true;
    byte b() default 1;
    char c() default 'c';
    short s() default 2;
    int i() default 3;
    long l() default 4L;
    float f() default 5.5f;
    double d() default 6.25;
    Class<?> type() default Object.class;
    Level level() default Level.LOW;
    Part part() default @Part;
    String[] names() default {};
    int[] counts() default {};
    Level[] levels() default {};
    Part[] parts() default {};
    Class<?>[] types() default {};

    enum Level { LOW, HIGH }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Part {
        String value() default "part";
    }

    /** Kept in the class file only, so reflection never sees it. */
    @Retention(RetentionPolicy.CLASS)
    @interface Kept {}

    /** For fields only. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface OnFields {}

    /** For type uses, which include a class declaration. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @interface OnTypes {}
}
