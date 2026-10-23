;; Java object serialization driven from jolt: a class of the program's own
;; (champaca.lib.Note, in target/lib.jar) and JDK classes, written and read
;; back through ObjectOutputStream and ObjectInputStream. ObjectInputStream
;; resolves class names through the application class loader when jolt is
;; the caller, as the JVM would for a Clojure caller.
;; bb test builds target/lib.jar from test/jar first.
;;   jolt -M examples/serialization.clj
(require '[champaca.api :as ldk])

(ldk/init! {:classpath ["target/lib.jar"]})

(defn p [label v] (println label v))

(defn freeze [o]
  (let [bytes (ldk/construct "java.io.ByteArrayOutputStream")
        out (ldk/construct "java.io.ObjectOutputStream" bytes)]
    (ldk/invoke out "writeObject" o)
    (ldk/invoke out "close")
    (ldk/invoke bytes "toByteArray")))

(defn thaw [bytes]
  (ldk/invoke (ldk/construct "java.io.ObjectInputStream" (ldk/construct "java.io.ByteArrayInputStream" bytes)) "readObject"))

;; a class of the program's own: found through the application loader
(def note (ldk/construct "champaca.lib.Note" "chez" 5))
(def back (thaw (freeze note)))
(p "note" (ldk/to-string back))
(p "same class, new object" [(= (ldk/invoke back "getClass") (ldk/invoke note "getClass")) (identical? back note)])
(p "fields" [(ldk/get-field back "title") (ldk/get-field back "stars")])

;; JDK classes, and a jolt collection converted on the way in
(p "list" (ldk/->clj (thaw (freeze (ldk/->java [1 "two" 3.5])))))
(p "map" (ldk/->clj (thaw (freeze (ldk/->java {"a" [1 2] "b" nil})))))
(p "nested" (ldk/to-string (thaw (freeze (ldk/->java [note note])))))

;; the stream's own view of the class
(p "serialVersionUID" (ldk/invoke (ldk/invoke-static "java.io.ObjectStreamClass" "lookup" (ldk/invoke note "getClass")) "getSerialVersionUID"))
(p "bytes" (count (ldk/->clj (freeze note))))
