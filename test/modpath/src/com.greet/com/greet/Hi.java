package com.greet;

import java.io.*;

// a service provider, and a class that reads its own module's resources: one
// in its package (encapsulated: com.greet isn't opened) and one at the root
public class Hi implements java.util.function.Supplier<String> {
    public String get() { return "hi from a provider in " + Hi.class.getModule().getName(); }
    static String read(InputStream in) throws IOException { return in == null ? "null" : new String(in.readAllBytes()).trim(); }
    public static String own() throws IOException {
        return "own class " + read(Hi.class.getResourceAsStream("msg.txt"))
            + " | own module " + read(Hi.class.getModule().getResourceAsStream("com/greet/msg.txt"))
            + " | own root " + read(Hi.class.getResourceAsStream("/top.txt"))
            + " | own loader " + read(Hi.class.getClassLoader().getResourceAsStream("com/greet/msg.txt"));
    }
}
