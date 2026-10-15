;; The call-site cache: a call remembers which overload it chose for the kinds
;; of arguments it saw. Each line here reuses a site with arguments that need a
;; different choice, so a cache keyed too coarsely prints something else.
;;   jolt -M examples/callsite.clj
;;   CHAMPACA_CALL_CACHE=off jolt -M examples/callsite.clj   (same, but the last line)
(require '[champaca.api :as ldk]
         '[jolt.scheme :as s])

(ldk/init!)

(defn p [label v] (println label v))
(defn max-of [a b] (ldk/invoke-static "java.lang.Math" "max" a b))
(defn value-of [x] (ldk/invoke-static "java.lang.String" "valueOf" x))
(defn text [o] (ldk/invoke o "toString"))
(defn fmt [f & args] (apply ldk/invoke-static "java.lang.String" "format" f args))
(defn as-list [v] (ldk/invoke (ldk/invoke-static "java.util.Arrays" "asList" v) "toString"))

;; one site, three overloads: max(int,int), max(long,long), max(double,double)
(p "max" (mapv (fn [[a b]] (max-of a b)) [[3 7] [1 5000000000] [2 7.5] [3 7] [2.5 1]]))
;; valueOf(int), valueOf(char), valueOf(boolean), valueOf(double), valueOf(Object)
(p "valueOf" (mapv value-of [42 \x true 2.5 42 (ldk/construct "java.lang.StringBuilder" "sb")]))
;; one method name, receivers of different classes
(p "toString" (mapv text [(ldk/construct "java.lang.StringBuilder" "a")
                          (ldk/construct "java.util.ArrayList")
                          (ldk/construct "java.lang.StringBuilder" "b")]))
;; varargs: the packed tail has a different length each time
(p "format" (mapv (fn [args] (apply fmt args)) [["%s" "a"] ["%s-%s-%s" "a" "b" "c"] ["%d+%d" 1 2] ["%s" "z"]]))
;; vectors: same element kind at other lengths, then other element kinds
(p "asList" (mapv as-list [["a" "b"] ["c"] [1 2 3] [1 "mixed"] [] ["d" "e" "f"]]))
;; constructors: StringBuilder(String), StringBuilder(int), StringBuilder()
(p "construct" (mapv (fn [args] (ldk/invoke (apply ldk/construct "java.lang.StringBuilder" args) "length"))
                     [["abc"] [16] [] ["xy"]]))
;; a failed lookup isn't cached: it fails the same way twice
(p "no fit" (vec (for [_ (range 2)]
                   (try (ldk/invoke-static "java.lang.Math" "max" "a" "b") :no-error
                        (catch :default e (:champaca/error (ex-data e)))))))
;; nil picks the most specific overload, every time
(p "nil" (mapv (fn [_] (ldk/invoke-static "java.util.Objects" "toString" nil)) (range 2)))
(p "cached sites" (pos? (s/call "call-cache-size")))
