package com.greet;

public class Greeter {
    public static String hello(String who) { return "hello " + who + " from " + Greeter.class.getModule().getName(); }
    // a frame of com.greet's on the way to r, for app.Frames
    public static void call(Runnable r) { r.run(); }
}
