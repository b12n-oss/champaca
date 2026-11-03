;; Class files compiled with --enable-preview (version 69.65535) load from
;; jolt when init! is given :preview true, as java --enable-preview runs
;; them. Without it they're refused with HotSpot's
;; UnsupportedClassVersionError, as test/java/VersionCheck.java checks.
;;   bb example preview
(ns examples.preview
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(def dir "target/preview-example")

(ldk/init! {:classpath [dir] :preview true})

(def preview-class
  "public class P extends Object, class file version 69.65535, no members:
  the header javac writes for a class using a preview feature"
  [-54 -2 -70 -66 -1 -1 0 69                 ; magic, minor 0xFFFF, major 69
   0 5 7 0 2 1 0 1 80 7 0 4 1 0 16            ; Class P, Class java/lang/Object
   106 97 118 97 47 108 97 110 103 47 79 98 106 101 99 116
   0 33 0 1 0 3 0 0 0 0 0 0 0 0])             ; public super, this, super, no members

(deftest a-preview-class-loads
  ;; the class directory is found lazily, so the file can be written after init!
  (ldk/invoke (ldk/construct "java.io.File" dir) "mkdirs")
  (let [out (ldk/construct "java.io.FileOutputStream" (str dir "/P.class"))]
    (try (ldk/invoke out "write" preview-class)
         (finally (ldk/invoke out "close"))))
  (is (= "P" (ldk/invoke (ldk/invoke-static "java.lang.Class" "forName" "P") "getName"))))
