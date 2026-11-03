// a compact source file (JEP 512): an implicit class with an instance main
void main() {
    IO.println("compact " + greet("source"));
}

String greet(String who) { return "hello " + who; }
