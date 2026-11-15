package app;

import java.net.URL;
import java.util.Collections;

// Resource URLs from named modules, as the JDK's module readers make them:
// jar:file:/// before a module jar, the entry's own name after !/ (a
// directory's ending in /, asked with or without it), an exploded module's
// file: URI. A package com.greet doesn't open has none for the loader. The
// working directory prints as <cwd>, so the output is machine-independent.
public class Urls {
    public static void main(String[] a) throws Exception {
        String cwd = new java.io.File(".").getCanonicalFile().toURI().getRawPath();
        ClassLoader l = ClassLoader.getSystemClassLoader();
        for (String n : new String[] {"com/greet/msg.txt", "com/greet/open/o.txt", "com/greet/open/", "com/greet/open",
                                      "META-INF/greet.txt", "top.txt", "app/Main.class", "app/", "app"}) {
            System.out.println(n + " -> " + show(l.getResource(n), cwd) + " | all "
                + Collections.list(l.getResources(n)).stream().map(u -> show(u, cwd)).toList());
        }
        System.out.println("Hi open/o.txt -> " + show(com.greet.Hi.class.getResource("open/o.txt"), cwd));
        System.out.println("Hi /top.txt -> " + show(com.greet.Hi.class.getResource("/top.txt"), cwd));
        System.out.println("Urls Main.class -> " + show(Urls.class.getResource("Main.class"), cwd));
    }

    static String show(URL u, String cwd) {
        return u == null ? "null" : u.toString().replace(cwd, "<cwd>/");
    }
}
