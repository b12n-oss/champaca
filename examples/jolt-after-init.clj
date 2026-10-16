;; jolt keeps working after champaca boots. The runtime's Scheme files share
;; jolt's top-level environment, and a definition there with one of jolt's
;; own names (jfloat->string, jclass?, reflect-args, ...) used to replace
;; jolt's, which broke its float printing and Java interop.
(require '[champaca.api :as ldk])

(defn jolt-side []
  [(str (float 1.5)) (pr-str (float 0.1)) (str 2.5)
   (str (class (java.util.ArrayList.)))
   (.getName (class "x"))
   (Math/max 1 2)])

(def before (jolt-side))
(ldk/init!)
(println (jolt-side))
(println (= before (jolt-side)))
(println (ldk/invoke-static "java.lang.Float" "toString" 1.5))
