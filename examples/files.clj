;; jolt reading and writing files through java.nio.file, and hashing with
;; MessageDigest, whose providers load through the same file system code.
;;   jolt -M examples/files.clj
(require '[champaca.api :as ldk])

(ldk/init!)

(defn p [label v] (println label v))

(def dir (ldk/invoke-static "java.nio.file.Files" "createTempDirectory" "champaca-example"))
(def notes (ldk/invoke dir "resolve" "notes.txt"))

(ldk/invoke-static "java.nio.file.Files" "writeString" notes "first\nsecond\n")
(p "read" (ldk/invoke-static "java.nio.file.Files" "readString" notes))
(p "lines" (ldk/->clj (ldk/invoke-static "java.nio.file.Files" "readAllLines" notes)))
(p "size" (ldk/invoke-static "java.nio.file.Files" "size" notes))
(ldk/invoke-static "java.nio.file.Files" "createDirectories" (ldk/invoke dir "resolve" "a/b"))
(p "listed" (sort (map #(ldk/to-string (ldk/invoke % "getFileName"))
                       (ldk/->clj (ldk/invoke (ldk/invoke-static "java.nio.file.Files" "list" dir) "toList")))))

(def md (ldk/invoke-static "java.security.MessageDigest" "getInstance" "SHA-256"))
(p "sha-256" (apply str (map #(format "%02x" (bit-and % 0xff))
                             (ldk/->clj (ldk/invoke md "digest" (ldk/invoke "abc" "getBytes"))))))

(doseq [f ["a/b" "a" "notes.txt"]] (ldk/invoke-static "java.nio.file.Files" "delete" (ldk/invoke dir "resolve" f)))
(ldk/invoke-static "java.nio.file.Files" "delete" dir)
(p "cleaned" (not (ldk/invoke-static "java.nio.file.Files" "exists" dir)))
