;; Varargs and vector-to-array calls from jolt. Trailing arguments are packed
;; into the varargs array; a jolt vector passed for an array parameter
;; becomes a Java array of the parameter's element type.
;;   jolt -M examples/varargs.clj
(require '[openldk-jolt.api :as ldk])

(ldk/init!)

(defn p [label v] (println label (if (string? v) v (ldk/to-string v))))

(p "list-of-3" (ldk/invoke-static "java.util.List" "of" "a" "b" "c"))
(p "list-of-1" (ldk/invoke-static "java.util.List" "of" "x"))
(p "list-of-0" (ldk/invoke-static "java.util.List" "of"))
(p "list-of-12" (apply ldk/invoke-static "java.util.List" "of" (range 1 13)))
(p "asList" (ldk/invoke-static "java.util.Arrays" "asList" 1 2 3))
(p "join" (ldk/invoke-static "java.lang.String" "join" "-" "x" "y" "z"))
(p "hash" (str (ldk/invoke-static "java.util.Objects" "hash" 1 2 3)))
(p "toString-int[]" (ldk/invoke-static "java.util.Arrays" "toString" [3 1 2]))
(p "toString-String[]" (ldk/invoke-static "java.util.Arrays" "toString" ["p" "q"]))
(p "deepToString" (ldk/invoke-static "java.util.Arrays" "deepToString" [[1 2] [3]]))
(p "format" (ldk/invoke-static "java.lang.String" "format" "%s has %d items at %.2f each, %b" "cart" 3 2.5 true))
(p "format-pad" (ldk/invoke-static "java.lang.String" "format" "[%5d|%-4s|%x]" 42 "ab" 255))
(p "max-of" (str (ldk/invoke-static "java.util.Collections" "max" (ldk/invoke-static "java.util.Arrays" "asList" 4 9 2))))
(p "enumset" (ldk/invoke-static "java.util.EnumSet" "of"
                                (ldk/get-static "java.lang.Thread$State" "NEW")
                                (ldk/get-static "java.lang.Thread$State" "BLOCKED")))
