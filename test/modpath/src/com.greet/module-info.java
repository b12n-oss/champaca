module com.greet {
    exports com.greet;
    // a package whose resources anyone may read
    opens com.greet.open;
    provides java.util.function.Supplier with com.greet.Hi;
}
