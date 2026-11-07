package com.greet;

public class Greeter {
    public static String hello(String who) { return "hello " + who + " from " + Greeter.class.getModule().getName(); }
}
