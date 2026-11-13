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
  (within-bmp! l0)
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
;; 26 static methods: 22 ported (22 checked and agree, 0 disagree, 0 not checked), 4 left
;; 4 static methods of 1 other class that those call: 3 ported (3 checked and agree, 0 disagree, 0 not checked), 1 left
;; 5 of the checks kept int and long parameters within -64 to 64 (the method loops or allocates by size); the others used the full range
```

The exit status is 1 when any check disagrees. A function whose check disagrees must not be used.

## How a method becomes a function

The method is translated as champaca translates any method, in a plain form with no callee compiled into it and no second copy of its array loops. The structuring pass ([debugging](debugging.md#structured-method-code)) turns that into a procedure for each loop head and a `let` for each value. `champaca.port` writes the result as Clojure:

- a loop head called from one place is `loop`, and the jumps back to it are `recur`;
- a join point or an exit is copied into each path that reaches it;
- `int` arithmetic that wraps is `unchecked-add-int` and its relatives, division is `(unchecked-int (quot a b))`, and remainder is `rem`;
- array access is `aget`, `aset` and `alength`, a read or write going through `non-null` (jolt's `aget` answers nil for a nil array where Java throws), and `new int[n]` is `(int-array n)`;
- an element of a `char[]` is a char in jolt and its code in the method, so a read is `(int (aget a i))` and a write `(aset a i (char x))`, and `new String[n]` is `(make-array String n)`;
- `new` with its constructor call is one constructor form;
- a call of a JDK method goes through a rule, which names the jolt form that stands for it.

Then a tidy pass merges nested `let`s, drops bindings of a name to itself and locals a path never reads, and removes from each loop the locals no `recur` changes.

Scheme's `let` and a block's parameters bind all at once, and Clojure's `let` and `loop` bind in order. Where a value uses a name bound earlier in the same list, as in a swap, the port takes every value first under a name ending in `_in`. Leaving that out was this tool's first bug, and the check found it on commons-lang's `overlay`.

## Rules

A rule maps one JDK member to a jolt form and may carry a condition. `String.length` and `charAt` map to `.length` and `.charAt` under the condition `strings-within-the-bmp`: Java indexes a string by UTF-16 code unit and jolt by character, so the two agree while no character is above U+FFFF. The check then generates only such strings.

A port tests its condition when it runs. A function that holds for strings within the BMP starts with `(within-bmp! s)` for each `String` parameter, which throws an `ex-info` carrying `{:champaca.port/outside :strings-within-the-bmp}` when the string has a character above U+FFFF. So such a string passed as a parameter gets an exception where it could have got an answer Java wouldn't give. `within-bmp!` is printed once before the functions and costs a pass over the string. The test covers the function's own `String` parameters only. A string reached another way, an element of a list or an array say, isn't tested when the function runs, and the check's generators make those elements within the BMP too, so that case is neither guarded nor checked.

The other condition, `letters-jolt-knows` on the case rules, has no test at run time. It names a handful of chars from a Unicode newer than JDK 25's tables, on which jolt and the JDK convert case differently; `examples/port.clj` compares the two on every char to keep that list honest.

A method returning `boolean` gives `true` or `false`, a `char` parameter takes a char, and inside the function a boolean is 1 or 0 and a char its code, as in the bytecode.

There are rules for the `String`, `StringBuilder`, `StringBuffer`, `Character` and `Math` members in `direct-rules`, for the `List`, `Iterator` and `Object` methods in `virtual-rules`, and for constructing `StringBuilder`, `StringBuffer`, `ArrayList` and the common exceptions. The case rules (`toUpperCase`, `Character.toLowerCase` and the like) hold under `letters-jolt-knows`: jolt and JDK 25 agree on every char but three each way, from a newer Unicode. Adding one is a line in `src/champaca/port.clj`, and the check on a method that uses it is what shows it's right.

Some rules name a helper printed before the functions, where jolt's own form is looser than Java's. `new String(a, from, count)` becomes `(string-of a from count)`, which throws for a nil array or a range outside it; jolt's `(String. a from count)` answers a string for both. `toCharArray` and `new String(char[])` go through `non-null` for the same reason.

## What a port calls

A call of another static method of the same class becomes a call of that method's function. A port stands only if everything it calls does: when a method is left, so is every method that calls it, with that as the reason. The conditions of what a port calls become its own.

A call of a static method of another class of the program works the same way. That method is ported too, and so is what it calls, and those functions are printed after the class's own, each marked as called from the class being ported. Their names start with the class's simple name (`array-utils-is-empty-chars`), and they are checked like the rest. Classes of the JDK are never written out: a call into one needs a rule.

A function has no class to initialize, so the initialization check before a call into another class is dropped. In Java the first call of a static method runs its class's static initializer. An initializer that only fills the class's own fields with constants and arrays does nothing a caller can see. One that calls a method, makes an object or throws might, so a port of a method of such a class, or of anything that calls one, holds under the condition `initializers-without-effects`. Nothing tests that when the function runs.

## Static fields

A read of a `static final` field becomes a `def` printed before the functions, when the field's value can't change: a number, a string, null, or an empty array. The tool reads the value once the class has initialized, so the initializer runs at port time under champaca. The def is named after the class and the field, the field in capitals (`array-utils-EMPTY-STRING-ARRAY`), and an empty array stays one object, as the field is.

Any other static field read leaves the method, with the reason: the field isn't final, or it holds an array with elements or another object, which code can change.

A method name used once gives the function its name in kebab case. An overloaded one has its parameter types after it (`within-int-int`, `within-int-int-int`), and a name `clojure.core` already has gets a star (`reverse*`).

## The check

Each port is run beside its method by `champaca.portcheck` ([checking-a-port](checking-a-port.md)) on 300 generated inputs, or `PORT_RUNS`. The argument specs come from the parameter types and the port's conditions.

An `int` or `long` parameter gets the full range, the limits and their neighbours included, unless the method loops or allocates by a size, or calls one that does. There the parameter may be the loop's count or a buffer's size, and the largest `int` would run the loop two thousand million times, so it stays within -64 to 64. The check's line says when that was so, and the count at the end says how many checks it applied to. A port checked that way has not been tried on large numbers: if it does arithmetic on such a parameter, check it by hand with a wider `:range` before relying on it. Array elements always get the full range, and an array, a list or an iterator is passed as null now and then, as a `String` is.

A port whose parameters have no input generator is printed and counted as not checked. Treat it as unknown: the two ports that turned out wrong on `StringUtils` sat in that count first.

## On commons-lang

`org.apache.commons.lang.StringUtils` in commons-lang 2.4 has 160 static methods with code. On 2026-11-14 the tool ported 139 of them, and all 139 are checked and agree on 300 inputs each. Twelve of those checks kept int parameters within -64 to 64, and the rest used the full range. They call 13 static methods of six other classes, of which 8 are ported, checked and agree, and 5 are left. The other 21 of `StringUtils` were left. 16 of those call a method that was left, so they follow from the rest, and the rest lack these:

| What has no rule | Methods |
|---|---|
| a `boolean[]`, a `switch`, a reference comparison, `Character.getType`, a constructor argument built with another `new` | 1 each |

A first run with a dozen rules ported 9. Calls between the class's own methods took it to 53, rules for `indexOf(int)` and its relatives, `valueOf(char)`, `regionMatches` and the `List` and `Iterator` methods to 71, leaving out the initialization check before a static call into a JDK class to 89, and rules for case conversion, `StringBuffer.append(Object)` and a few constructors to 102.

Two of those 102 were wrong. They take a `char[]`, and an element read from one is a char in jolt where the code expects its number, so a comparison never matched. They had counted as "not checked", since no generator made a `char[]`. Once one did, the check reported both, and methods with a `char[]` or `boolean[]` in their signature were left until arrays were typed. That was the 100.

A `char[]` has instructions of its own in the bytecode, so the plain form now marks its reads and writes and the port converts at each one. With that, rules for `toCharArray`, the `String` constructors from a `char[]`, `StringBuffer.reverse`, `Character.isTitleCase` and `List.toArray`, and `String[]` allocation, the count is 110. `Character.getType` stays without a rule: jolt and JDK 25 differ on 63 chars there.

Following calls into the program's other classes took it to 121. Two of the five methods left in those classes have an exception handler or call `CharSet.contains`, an instance method.

Reading `ArrayUtils.EMPTY_STRING_ARRAY` as a def brought in the split methods and their callers, which is the 139.

Earlier nine ports had disagreed with their methods, all through `Character.isWhitespace`: jolt's own differs from the JDK's on five chars (U+001C to U+001F and U+0085, jolt-lang/jolt#1655). The rule settles those five itself, and `examples/port.clj` compares its form with the JDK's method on every char outside the surrogates.

Each count is of the first thing found in a method, so a method may lack more than one.

## From jolt

```clojure
(require '[champaca.api :as ldk] '[champaca.port :as port])
(ldk/init! {:classpath ["target/lib.jar"]})

(def results (port/check-ports (port/port-class "champaca.lib.PortSample") 300))
(:source (first results))     ; the defn's text
(:holds (first results))      ; the conditions it holds for
(:report (first results))     ; champaca.portcheck's report
(:small-ints (first results)) ; whether the check kept ints within -64 to 64
(:left (first results))       ; or why it was left
```

`examples/port.clj` checks all of this on `PortSample`, and `jolt test` runs it.

## What it doesn't do yet

Instance methods, instance fields, static fields whose value can change, and classes. Methods with exception handlers, `switch`, or long arithmetic beyond comparisons. `boolean[]`, which the bytecode reads with the instruction that reads a `byte[]`, and reference arrays of anything but `String` and `Object`. Local names from debug information. Each is left with its reason, and a left method still runs under champaca as it always did.

Two limits aren't reported as reasons yet. Copying a join point into each path that reaches it has no size limit, so a method with many independent `if`s in a row grows with each one. And the code assumes loops don't nest with a group of blocks that call each other; the plain form it reads doesn't produce that today.
