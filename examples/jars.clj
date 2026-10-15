;; jolt calling into a jar, and into JDK modules other than java.base.
;; bb test builds target/lib.jar from test/jar first.
;;   jolt -M examples/jars.clj
(require '[champaca.api :as ldk])

(ldk/init! {:classpath ["target/lib.jar"]})

(defn p [label v] (println label v))

(p "sum" (ldk/invoke-static "champaca.lib.Shelf" "sum" [1 2 3 4]))
(p "fill" (ldk/->clj (ldk/invoke-static "champaca.lib.Shelf" "fill"
                                        (ldk/invoke-static "java.lang.Class" "forName" "java.lang.String")
                                        2 "x")))
(p "grid" (mapv count (ldk/->clj (ldk/invoke-static "champaca.lib.Shelf" "grid" 2 3))))
(p "count" (ldk/get-static "champaca.lib.Shelf" "count"))
(p "unsafe" (ldk/invoke-static "champaca.lib.Shelf" "bumpThroughUnsafe"))
(p "level" (ldk/invoke (ldk/get-static "java.util.logging.Level" "WARNING") "getName"))
(p "sql" (ldk/invoke (ldk/construct "java.sql.Date" 0) "getTime"))
(p "tokyo" (ldk/invoke (ldk/invoke-static "java.util.TimeZone" "getTimeZone" "Asia/Tokyo") "getRawOffset"))
