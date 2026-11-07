module com.greet {
    exports com.greet;
    provides java.util.function.Supplier with com.greet.Hi;
}
