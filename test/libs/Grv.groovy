import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.transform.EqualsAndHashCode
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper

def row(k, v) { println "$k: $v" }

@ToString @EqualsAndHashCode
class Point { int x; int y; Point plus(Point o) { new Point(x: x + o.x, y: y + o.y) } }

@CompileStatic
class Fast {
    static long fib(int n) { n < 2 ? (long) n : fib(n - 1) + fib(n - 2) }
    static List<Integer> squares(int n) { (1..n).collect { int i -> i * i } }
}

trait Greets { String greet() { "hi from ${name()}" }; abstract String name() }
class Bob implements Greets { String name() { "bob" } }

enum Planet { MERCURY(3.303e+23), EARTH(5.976e+24); final double mass; Planet(double m) { mass = m } }

row "closures", [1, 2, 3, 4].findAll { it % 2 == 0 }.collect { it * 10 }
row "curry", ({ a, b -> a + b }.curry(5))(10)
row "compose", (({ it + 1 } >> { it * 2 })(3))
row "gstring", "sum ${1 + 2} and ${[1, 2].sum()}"
row "ranges", [(1..5).toList(), ('a'..'e').join(), (5..1).step(2)]
row "maps", [a: 1, b: 2].collectEntries { k, v -> [(k.toUpperCase()): v * 2] }
row "spread", [[1, 2], [3]]*.size()
row "elvis", [null ?: "dflt", "x"?.size(), null?.size()]
row "operators", [new Point(x: 1, y: 2) + new Point(x: 3, y: 4), new Point(x: 1, y: 1) == new Point(x: 1, y: 1)]
row "compileStatic", [Fast.fib(20), Fast.squares(5)]
row "traits", new Bob().greet()
row "enum", [Planet.EARTH.mass, Planet.values()*.name()]
row "metaclass", { String.metaClass.shout = { -> delegate.toUpperCase() + "!" }; "hey".shout() }()
row "expando", { def e = new Expando(n: 2); e.twice = { -> n * 2 }; e.twice() }()
row "methodMissing", { def o = new Object() { def methodMissing(String n, args) { "missing $n${args.toList()}" } }; o.foo(1, 2) }()
row "regex", [("a1b22c333" =~ /\d+/).collect(), "2024-01-02" ==~ /\d{4}-\d{2}-\d{2}/]
row "strings", ["abc".reverse(), "x" * 3, "a,b,c".tokenize(","), String.format("%05.1f", 3.14159d), "Hello"[1..3]]
row "bigdecimal", [0.1 + 0.2, 1 / 3, 2 ** 100]
row "json", [JsonOutput.toJson([a: [1, 2], b: "c"]), new JsonSlurper().parseText('{"x":[1,2.5,null]}')]
row "xml", new XmlSlurper().parseText("<r><i n='1'>a</i><i n='2'>b</i></r>").i.collect { "${it.@n}=${it.text()}" }
row "exceptions", { try { throw new IllegalStateException("boom") } catch (e) { e.message } }()
row "with", (new StringBuilder().with { append("a"); append("b"); delegate.toString() })
row "inject", (1..10).inject(0) { s, x -> s + x }
row "sort", [[3, 1, 2].sort(), ["bb", "a", "ccc"].sort { it.size() }, [3, 1, 2].sort { a, b -> b <=> a }]
row "eval", Eval.me("2 * 21")
row "threads", { def t = Thread.start { 6 * 7 }; t.join(); "joined" }()
row "switch", [1, "s", [1], 2.5].collect { v -> switch (v) { case Integer: "int"; break; case String: "str"; break; case List: "list"; break; default: "other" } }
