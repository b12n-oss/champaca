# Porting a class's static methods to jolt

`jolt port` writes a Java class's static methods out as jolt functions and checks each one against the method it came from. The functions use jolt's own `String`, `StringBuilder`, arrays and `clojure.core`, so a ported function needs neither champaca nor a JDK to run.

It is a first version. It handles static methods over ints, longs, strings, `int[]` and the JDK members it has rules for, and it says so about everything else.

```sh
jolt port target/lib.jar champaca.lib.PortSample
```

## What comes out

For Euclid's algorithm in `test/jar/champaca/lib/PortSample.java`:

```java
public static int gcd(int a, int b) {
    while (b != 0) { int t = a % b; a = b; b = t; }
    return a;
}
```

the tool prints the method, the check's result and the function:

```clojure
;; champaca/lib/PortSample.gcd(II)I
;; gcd: agrees on 300 inputs (seed 84)
(defn gcd
  [l0 l1]
  (loop [l0 l0 l1 l1 l2 0] (if (= l1 0) l0 (let [l2 (rem l0 l1)] (recur l1 l2 l2)))))
```

The names are the bytecode's local slots, since the class was compiled without debug information. A method that reads a string by index names the condition it holds for:

```clojure
;; champaca/lib/PortSample.hash31(Ljava/lang/String;)I
;; holds for: strings-within-the-bmp
;; hash31: agrees on 300 inputs (seed 84)
(defn hash31
  [^String l0]
  (loop [l1 7 l2 0]
    (let [s1 (.length l0)]
      (if (>= l2 s1)
        l1
        (let [s0 (unchecked-multiply-int 31 l1)
              s1 (int (.charAt l0 l2))
              l1 (unchecked-add-int s0 s1)
              l2_1 (unchecked-add-int l2 1)]
          (recur l1 l2_1))))))
```

After the functions come the methods it left, each with the first thing in it that has no rule, and a count:

```
;; 15 static methods: 15 ported (15 checked and agree, 0 disagree, 0 not checked), 0 left
```

The exit status is 1 when any check disagrees. A function whose check disagrees must not be used.

## How a method becomes a function

The method is translated as champaca translates any method, in a plain form with no callee compiled into it and no second copy of its array loops. The structuring pass ([debugging](debugging.md#structured-method-code)) turns that into a procedure for each loop head and a `let` for each value. `champaca.port` writes the result as Clojure:

- a loop head called from one place is `loop`, and the jumps back to it are `recur`;
- a join point or an exit is copied into each path that reaches it;
- `int` arithmetic that wraps is `unchecked-add-int` and its relatives, division is `(unchecked-int (quot a b))`, and remainder is `rem`;
- array access is `aget`, `aset` and `alength`, and `new int[n]` is `(int-array n)`;
- `new` with its constructor call is one constructor form;
- a call of a JDK method goes through a rule, which names the jolt form that stands for it.

Then a tidy pass merges nested `let`s, drops bindings of a name to itself and locals a path never reads, and removes from each loop the locals no `recur` changes.

Scheme's `let` and a block's parameters bind all at once, and Clojure's `let` and `loop` bind in order. Where a value uses a name bound earlier in the same list, as in a swap, the port takes every value first under a name ending in `_in`. Leaving that out was this tool's first bug, and the check found it on commons-lang's `overlay`.

## Rules

A rule maps one JDK member to a jolt form and may carry a condition. `String.length` and `charAt` map to `.length` and `.charAt` under the condition `strings-within-the-bmp`: Java indexes a string by UTF-16 code unit and jolt by character, so the two agree while no character is above U+FFFF. The check then generates only such strings.

A method returning `boolean` gives `true` or `false`, a `char` parameter takes a char, and inside the function a boolean is 1 or 0 and a char its code, as in the bytecode.

There are rules for the `String`, `StringBuilder`, `StringBuffer`, `Character` and `Math` members in `direct-rules`, for the `List`, `Iterator` and `Object` methods in `virtual-rules`, and for constructing the common exceptions. Adding one is a line in `src/champaca/port.clj`, and the check on a method that uses it is what shows it's right.

## What a port calls

A call of another static method of the same class becomes a call of that method's function. A port stands only if everything it calls does: when a method is left, so is every method that calls it, with that as the reason. The conditions of what a port calls become its own.

A method name used once gives the function its name in kebab case. An overloaded one has its parameter types after it (`within-int-int`, `within-int-int-int`), and a name `clojure.core` already has gets a star (`reverse*`).

## The check

Each port is run beside its method by `champaca.portcheck` ([checking-a-port](checking-a-port.md)) on 300 generated inputs, or `PORT_RUNS`. The argument specs come from the parameter types and the port's conditions. An `int` or `long` parameter stays within -64 to 64: it may be a loop's count or a buffer's size, and the largest `int` would run a loop two thousand million times. Array elements keep the full range.

A port whose parameters have no input generator (a `char[]`, say) is printed and counted as not checked.

## On commons-lang

`org.apache.commons.lang.StringUtils` in commons-lang 2.4 has 160 static methods with code. On 2026-11-14 the tool ported 89 of them: 83 checked and agree, none disagree, and 6 weren't checked for lack of a generator. The other 71 were left. 34 of those call a method that was left, so they follow from the rest, and the rest lack these:

| What has no rule | Methods |
|---|---|
| `String.toCharArray` | 8 |
| a static method of another class (`ArrayUtils`, `CharUtils`, `WordUtils`) | 8 |
| `new` of a `String`, an `ArrayList` or a `StringBuffer` from another | 6 |
| a `StringBuffer` method (`append(Object)`, `insert` and others) | 4 |
| a `Character` method (`toLowerCase`, `toTitleCase`, `isUpperCase`) | 3 |
| `String.toUpperCase` and `toLowerCase` | 3 |
| a `String[]` or another reference array | 2 |
| a `switch`, a reference comparison, a narrowing to `byte` | 1 each |

A first run with a dozen rules ported 9. Calls between the class's own methods took it to 53, rules for `indexOf(int)` and its relatives, `valueOf(char)`, `regionMatches` and the `List` and `Iterator` methods to 71, and leaving out the initialization check before a static call into a JDK class to 89.

That last step also turned up nine ports that disagreed with their methods, all through `Character.isWhitespace`: jolt's own differs from the JDK's on five chars (U+001C to U+001F and U+0085, jolt-lang/jolt#1655). The rule now settles those five itself, and `examples/port.clj` compares its form with the JDK's method on every char.

Each count is of the first thing found in a method, so a method may lack more than one.

## From jolt

```clojure
(require '[champaca.api :as ldk] '[champaca.port :as port])
(ldk/init! {:classpath ["target/lib.jar"]})

(def results (port/check-ports (port/port-class "champaca.lib.PortSample") 300))
(:source (first results))     ; the defn's text
(:holds (first results))      ; the conditions it holds for
(:report (first results))     ; champaca.portcheck's report
(:left (first results))       ; or why it was left
```

`examples/port.clj` checks all of this on `PortSample`, and `jolt test` runs it.

## What it doesn't do yet

Instance methods, fields and classes. Methods with exception handlers, `switch`, or long arithmetic beyond comparisons. Calls into other classes. Local names from debug information. Each is left with its reason, and a left method still runs under champaca as it always did.
