;; bb test's failed-save row, second process (fail.clj says why)
(require '[champaca.api :as ldk])
(ldk/init! {})
(println "adler" (ldk/invoke (ldk/construct "java.util.zip.Adler32") "getValue"))
(def bytes-in #(ldk/construct "java.io.ByteArrayInputStream" [0]))
;; the subclass first, so its superclass, left out of state.img, loads under it
(println "zip" (some? (ldk/construct "java.util.zip.ZipInputStream" (bytes-in))))
(println "inflater" (some? (ldk/construct "java.util.zip.InflaterInputStream" (bytes-in))))
(System/exit 0)
