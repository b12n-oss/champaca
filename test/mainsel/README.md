# test/mainsel

How the launcher picks `main` (JEP 512, final in Java 25), case by case. bb
test compiles these into target/mainsel and runs each class that has a
`<Class>.expected` with the arguments `a b`. The file holds what HotSpot's
`java` gives: a line `exit <status>`, then stdout, then a line
`--- stderr`, then stderr. To record one:

```sh
java -cp target/mainsel 'Class' a b > out 2> err; st=$?
{ printf 'exit %s\n' "$st"; cat out; printf -- '--- stderr\n'; cat err; } > 'Class.expected'
```
