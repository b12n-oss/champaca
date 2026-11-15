;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; The launcher's module options from jolt: init! takes :limit-modules,
;; :add-opens, :add-exports, :add-reads and :enable-native-access as java
;; takes --limit-modules and the rest, so the boot layer is the one HotSpot
;; builds for that command line. Each answer below is what HotSpot 25.0.2
;; printed for
;;   java --limit-modules java.base,java.logging
;;        --add-opens java.base/java.lang=ALL-UNNAMED
;;        --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
;;        --add-reads java.logging=ALL-UNNAMED --enable-native-access ALL-UNNAMED
;; and differs without the option.
;;   jolt example module_options
(ns examples.module-options
  (:require [clojure.test :refer [deftest is]]
            [champaca.api :as ldk]))

(ldk/init! {:limit-modules ["java.base" "java.logging"]
            :add-opens ["java.base/java.lang=ALL-UNNAMED"]
            ;; one option given as a string, as on the command line
            :add-exports "java.base/jdk.internal.misc=ALL-UNNAMED"
            :add-reads ["java.logging=ALL-UNNAMED"]
            :enable-native-access "ALL-UNNAMED"})

(defn- boot-layer [] (ldk/invoke-static "java.lang.ModuleLayer" "boot"))
(defn- present? [m] (ldk/invoke (ldk/invoke (boot-layer) "findModule" m) "isPresent"))
(defn- unnamed [] (ldk/invoke (ldk/invoke-static "java.lang.ClassLoader" "getSystemClassLoader") "getUnnamedModule"))
(defn- java-base [] (ldk/invoke (ldk/invoke (boot-layer) "findModule" "java.base") "get"))

(deftest limit-modules
  (is (= [false true 2] [(present? "java.sql") (present? "java.logging")
                         (ldk/invoke (ldk/invoke (boot-layer) "modules") "size")])))

(deftest add-opens
  (is (= [true false] [(ldk/invoke (java-base) "isOpen" "java.lang" (unnamed))
                       (ldk/invoke (java-base) "isOpen" "java.util" (unnamed))])))

(deftest add-exports
  (is (= [true false] [(ldk/invoke (java-base) "isExported" "jdk.internal.misc" (unnamed))
                       (ldk/invoke (java-base) "isExported" "jdk.internal.reflect" (unnamed))])))

(deftest add-reads
  (is (true? (ldk/invoke (ldk/invoke (ldk/invoke (boot-layer) "findModule" "java.logging") "get")
                         "canRead" (unnamed)))))

(deftest enable-native-access
  (is (true? (ldk/invoke (unnamed) "isNativeAccessEnabled"))))
