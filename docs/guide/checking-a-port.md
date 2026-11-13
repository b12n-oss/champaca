# Checking a jolt port of a Java method

Say you've rewritten a Java static method as a jolt function, by hand or with a tool, and want to know the two do the same thing. `champaca.portcheck` runs both on the same generated inputs and compares what a caller can see. The Java side is the method itself, run by champaca from its class file, so no JVM is needed.

It is a test. Agreement covers the inputs that ran and says nothing about the others, so every report names how many inputs there were and the seed they came from.

## A check

`test/jar/champaca/lib/PortSample.java` has a method that sums the even numbers of an array:

```java
public static int sumEven(int[] xs) {
    int s = 0;
    for (int x : xs) if (x % 2 == 0) s += x;
    return s;
}
```

Java's `int` addition wraps, so the jolt version uses `unchecked-add-int`:

```clojure
(require '[champaca.api :as ldk]
         '[champaca.portcheck :as pc])

(ldk/init! {:classpath ["target/lib.jar"]})

(defn sum-even [^ints xs]
  (areduce xs i s (int 0)
           (let [x (aget xs i)] (if (zero? (rem x 2)) (unchecked-add-int s x) s))))

(pc/report-line
  (pc/check {:name "sumEven" :class "champaca.lib.PortSample" :method "sumEven"
             :args [:ints] :f sum-even}))
;; => "sumEven: agrees on 300 inputs (seed 84)"
```

Write `+` where `unchecked-add-int` is and the check finds an array whose even numbers overflow:

```
sumEven, + for unchecked-add-int: DIFFERS at input 1 (seed 84): [[-1582129699 255 -1516640081 7 -2038611360 -2147483648]] java {:result 108872288, :args [[...]]} jolt {:result -4186095008, :args [[...]]}
```

## What is compared

For each input the two sides must agree on three things: the result, the class of the exception if one was thrown, and the contents of every array and list argument after the call. A method that reverses an array in place and returns nothing is checked through that third one.

Exception messages aren't compared. Jolt's wording for a null receiver differs from HotSpot's, and callers rarely read it.

## Arguments

`:args` gives one spec per parameter, a keyword or a map with `:type` and options.

| Spec | Values | Options |
|---|---|---|
| `:int` | a third random 32-bit values, a third edge values (powers of two, the limits and their neighbours), a third from `0 1 -1 Integer.MAX_VALUE Integer.MIN_VALUE` | `:range [lo hi]` for values in a range only |
| `:long` | the same, with the 64-bit limits added | |
| `:boolean` | `true` or `false` | |
| `:char` | one character | `:chars`, as for `:string` |
| `:string` | up to eight characters | `:chars` `:ascii`, `:latin1`, `:bmp` (the default) or `:any`; `:nil? true` to pass `null` now and then |
| `:ints` | an `int[]` of up to six values | |
| `:strings` | a `java.util.List` of up to four strings | `:of`, a `:string` spec for the elements |

`:runs` sets the number of inputs (300 if left out) and `:seed` the generator's seed (84). The same seed gives the same inputs on every machine. `float` and `double` parameters aren't supported yet.

The sharp values are drawn that often for a reason. The first generator missed a seeded bug that shows only for `Integer.MIN_VALUE / -1`, because 300 inputs rarely paired those two numbers.

## Conditions

Some ports hold only for some inputs. Java reads a string by UTF-16 code unit and jolt by character, so a port that walks a string by index agrees with Java only while no character is above U+FFFF. Say so in the spec, with `:chars :bmp`, and the check stays inside the condition.

Then run it once with the condition broken, `:chars :any`. That run should fail. `hash31` does:

```
hash31, given characters above U+FFFF: DIFFERS at input 19 (seed 84): ["😀_\t﻿0"] java {:result -1445628731, :args []} jolt {:result -1370286988, :args []}
```

If it passes, the condition wasn't needed. That happened with `countChar`, which counts one `char` in a string: a `char` is never half of a surrogate pair, so both sides count the same, and the port holds for any string.

## The example and its seeded bugs

`examples/portcheck.clj` ports the eleven methods of `PortSample` and checks each. It then checks eleven wrong versions, each with one mistake of the kind a person or a translator makes: `+` for `unchecked-add-int`, `mod` for `rem`, a missing exception, a loop that stops one step early, `str/join` and `str/trim` for a `StringBuilder` loop and `String.trim`. The check has to report every one, since a check that passes everything shows nothing. `jolt test` runs the file, and `jolt example portcheck` runs it alone.

The last of those is worth a look, because it is the version a Clojure programmer would write first:

```clojure
(defn join-trim [parts sep] (str/join sep (map str/trim parts)))
```

Java's loop appends the text `null` for a null separator where `str/join` appends nothing, and `String.trim` leaves U+2003 (an em space) alone where `str/trim` strips it. The check reports it at its third input, where one string ends in U+2003.
