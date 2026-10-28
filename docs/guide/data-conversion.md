# Data conversion

Java objects live in the same heap as jolt values, but they're different things: a jolt vector isn't a `java.util.List`, and a Java `String[]` isn't a vector. Champaca converts at the call boundary. On the way in, the target is the parameter type of the overload it picked ([calling-java](calling-java.md#which-overload-runs)). On the way out, only strings and boxes convert, and everything else is left for you to copy.

## Going in

| jolt value | Java parameter | What Java gets |
|---|---|---|
| integer | `int`, `long`, `float`, `double` | the number, widened if needed |
| integer in range | `byte`, `short` | the number, when no overload fits without narrowing |
| integer | `Object`, `Number`, `Integer` | a boxed `Integer` if it fits in 32 bits, else a `Long` |
| integer | `Long` | a boxed `Long` |
| double | `double`, `float`, `Object` | the number, or a boxed `Double` |
| string | `String`, `CharSequence`, `Object` | a `java.lang.String` |
| boolean, char | `boolean`, `char`, or a box | the value, boxed for a reference type |
| keyword | `String`, `Object` | its name as a string, namespace included: `:ns/kw` is `"ns/kw"` |
| `nil` | any reference type | `null` |
| fn | a single-method interface | an implementation of it |
| vector | an array type | an array of that element type |
| vector | `List`, `Collection`, `Iterable` | an `ArrayList` |
| vector | `Object` | an array, typed by its contents |
| map | any type a `LinkedHashMap` is: `Map`, `HashMap`, `Object` | a `LinkedHashMap` |
| set | any type a `LinkedHashSet` is: `Set`, `Collection`, `Object` | a `LinkedHashSet` |
| other seq | any type an `ArrayList` is: `List`, `Collection`, `Object` | an `ArrayList` |
| Java object | its class or a supertype | itself |

### Vectors and arrays

A vector given to an array parameter becomes a fresh array of that type, element by element:

```clojure
(ldk/invoke-static "java.util.Arrays" "toString" [3 1 2])        ;=> "[3, 1, 2]"     toString(int[])
(ldk/invoke-static "java.util.Arrays" "toString" [1.5 2.5])      ;=> "[1.5, 2.5]"    toString(double[])
(ldk/->clj (ldk/invoke-static "java.util.Arrays" "copyOf" [3 1 2] 5))  ;=> [3 1 2 0 0]
```

A vector of integers becomes a `byte[]` or `short[]` too, when the method has no overload that takes it without narrowing, so `(ldk/invoke-static "java.nio.ByteBuffer" "wrap" [1 2 3])`, `(ldk/construct "java.lang.String" [104 105])` and a `MessageDigest`'s `update` work, as long as every element is in the type's range. A Java `byte` is signed, -128 to 127, so a byte written as `0xCA` or `200` fits nothing: write it as the negative number with the same bits (`0xCA` is -54, `0xFF` is -1), or let Java parse hex, `(ldk/invoke (ldk/invoke-static "java.util.HexFormat" "of") "parseHex" "cafebabe")`. Nothing wraps a value silently, as Clojure's `byte` refuses one out of range. `->clj` reads a `byte[]` back as a vector of signed integers: `[104 105]`, or `[-54 -2 -70 -66]` for that hex.

When the parameter is just `Object`, nothing says which array type to build, so the contents decide: all small integers make an `int[]`, any integer beyond 32 bits makes a `long[]`, all doubles a `double[]`, all strings (keywords count as strings) a `String[]`, vectors of one kind a nested array, and anything else an `Object[]`: characters, booleans, `nil`s, an empty vector, or integers mixed with doubles.

### Collections

Maps, sets and seqs become the `java.util` collection that keeps jolt's iteration order. The copy is deep: a nested map becomes a nested `LinkedHashMap`, and a vector inside a collection becomes an `ArrayList` (inside a collection there's no parameter type to ask for an array). Keywords become strings, keys included:

```clojure
(let [m (ldk/construct "java.util.HashMap" {:a 1 "b" [1 2]})]
  [(ldk/to-string m) (ldk/->clj m)])
;=> ["{a=1, b=[1, 2]}" {"a" 1, "b" [1 2]}]

(ldk/invoke-static "java.util.Collections" "max" [3 9 4])   ;=> 9    max(Collection)
```

That makes the JSON libraries easy to feed. gson and Jackson both take a jolt map directly:

```clojure
(def mapper (ldk/construct "com.fasterxml.jackson.databind.ObjectMapper"))
(ldk/invoke mapper "writeValueAsString" {:name "champaca" :tags ["a" "b"]})
;=> "{\"name\":\"champaca\",\"tags\":[\"a\",\"b\"]}"
```

(That one needs the Jackson jars on the classpath; see [jars-and-modules](jars-and-modules.md).)

## Coming out

A result converts only if it's a string or a box: `String`, `Integer`, `Long`, `Double`, `Boolean`, `Character` and the rest come back as jolt values. Arrays, collections and every other object stay Java, so you can keep calling methods on them:

```clojure
(ldk/invoke (ldk/invoke-static "java.util.Set" "of" "x") "contains" "x")   ;=> true
```

### `->clj`

`ldk/->clj` copies Java data into jolt data, all the way down. An array or a `List` (any `Collection` that isn't a `Set`) becomes a vector, a `Set` a set, and a `Map` a map, with strings and boxes converted inside. Anything else comes back unchanged.

```clojure
(ldk/->clj (ldk/invoke "a,b,c" "split" ","))                     ;=> ["a" "b" "c"]
(ldk/->clj (ldk/invoke-static "java.util.Map" "of" "k" 1))       ;=> {"k" 1}
```

Map keys stay strings unless you pass `:key-fn`, which `->clj` applies to every string key at any depth. Keys of other types are left alone, so `keyword` can't turn `1` into `nil`. Two keys that `:key-fn` maps to the same key throw instead of one being dropped:

```clojure
(ldk/->clj (ldk/invoke-static "java.util.Map" "of" "k" 1) {:key-fn keyword})   ;=> {:k 1}
```

### `->java`

`ldk/->java` runs the inbound conversion by hand, for when you want the Java collection itself, perhaps to hold on to it or pass it through an `Object` parameter as a list. A top-level vector becomes an `ArrayList` here:

```clojure
(ldk/to-string (ldk/->java {:name "champaca" :tags #{"tree"} :n [1 2]}))
;=> "{name=champaca, tags=[tree], n=[1, 2]}"
```

## Conversions copy

Every conversion copies. A Java method that changes a collection you passed in changes the copy, and your jolt value stays as it was:

```clojure
(let [v [1 2 3]
      l (ldk/->java v)]
  (ldk/invoke l "add" 4)
  [v (ldk/->clj l)])
;=> [[1 2 3] [1 2 3 4]]
```

That's usually what you want from immutable data, but it costs something: a big collection is copied on every call that takes it. If you pass the same large map to Java many times, convert it once with `->java` and pass the Java object instead.
