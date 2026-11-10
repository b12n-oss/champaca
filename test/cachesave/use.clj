;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; jolt test's failed-save row, second process (fail.clj says why)
(require '[champaca.api :as ldk])
(ldk/init! {})
(println "adler" (ldk/invoke (ldk/construct "java.util.zip.Adler32") "getValue"))
(def bytes-in #(ldk/construct "java.io.ByteArrayInputStream" [0]))
;; the subclass first, so its superclass, left out of state.img, loads under it
(println "zip" (some? (ldk/construct "java.util.zip.ZipInputStream" (bytes-in))))
(println "inflater" (some? (ldk/construct "java.util.zip.InflaterInputStream" (bytes-in))))
(System/exit 0)
