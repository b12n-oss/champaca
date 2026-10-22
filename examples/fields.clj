;; jolt reading and writing Java fields, and ->clj's :key-fn.
;; bb test builds target/lib.jar from test/jar first.
;;   jolt -M examples/fields.clj
(require '[champaca.api :as ldk])

(ldk/init! {:classpath ["target/lib.jar"]})

(defn p [label v] (println label v))
(defn err [f] (try (f) :no-error (catch Exception e (ex-message e))))

(def F "champaca.lib.Fields")

;; statics: Java code sees what jolt stored
(ldk/set-static! F "count" 41)
(ldk/set-static! F "label" "from jolt")
(p "statics" (ldk/invoke-static F "statics"))
(p "bump" [(ldk/invoke-static F "bump") (ldk/get-static F "count")])

;; a static found in a superclass or interface initializes only its declarer
(p "inherited" [(ldk/get-static "champaca.lib.Fields$Sub" "shared") (ldk/to-string (ldk/get-static F "log"))])
(ldk/set-static! "champaca.lib.Fields$Sub" "shared" 8)
(p "inherited set" [(ldk/get-static "champaca.lib.Fields$Base" "shared") (ldk/to-string (ldk/get-static F "log"))])
(p "interface" [(ldk/->clj (ldk/get-static F "NAMES")) (ldk/get-static F "LIMIT")])

;; every primitive type, strings, collections and arrays
(def o (ldk/construct F))
(doseq [[f v] [["i" -7] ["l" 12345678901] ["b" -128] ["s" 300] ["c" \x] ["d" 2.5] ["f" 1.25] ["z" true]
               ["str" "hi"] ["obj" 3] ["list" [1 "two" :three]] ["ints" [4 5]]]]
  (ldk/set-field! o f v))
(p "describe" (ldk/invoke o "describe"))
(p "read back" (mapv #(ldk/get-field o %) ["i" "l" "b" "s" "c" "d" "f" "z" "str" "obj"]))
(ldk/set-field! o "c" 65)
(ldk/set-field! o "str" nil)
(p "char from int, nil" [(ldk/get-field o "c") (ldk/get-field o "str")])

;; a field hidden by a subclass: the most-derived one, as get-field reads
(def sub (ldk/construct "champaca.lib.Fields$Sub"))
(ldk/set-field! sub "hidden" 9)
(p "hidden" [(ldk/get-field sub "hidden") (ldk/invoke sub "baseHidden")])

;; refused
(p "final static" (err #(ldk/set-static! F "FIXED" 2)))
(p "final field" (err #(ldk/set-field! o "ro" 6)))
(p "byte range" (err #(ldk/set-field! o "b" 128)))
(p "int range" (err #(ldk/set-field! o "i" 3000000000)))
(p "wrong type" (err #(ldk/set-field! o "i" "seven")))
(p "nil primitive" (err #(ldk/set-field! o "z" nil)))
(p "no field" (err #(ldk/set-field! o "nope" 1)))
(p "no static" (err #(ldk/set-static! F "nope" 1)))
(p "array type" (err #(ldk/set-field! o "ints" ["x"])))
(p "class type" (err #(ldk/set-field! o "str" 5)))
(p "unchanged" (ldk/invoke o "describe"))

;; ->clj with :key-fn, applied to string keys at every depth
(def m (ldk/->java {"a" 1 "b" {"c" [{"d" 2}]}}))
(p "keys" (ldk/->clj m {:key-fn keyword}))
(p "plain" (ldk/->clj m))
(p "non-string keys" (ldk/->clj (ldk/->java {1 "one" "k" 2}) {:key-fn keyword}))
