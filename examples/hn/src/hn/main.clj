;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

(ns hn.main
  "The Hacker News front page in a terminal: a jolt program that fetches the
  page with OkHttp, a library written in Kotlin, and reads it with jsoup, one
  written in Java. Both run inside this process on champaca, with no JVM.

    hn                     the front page, 30 stories
    hn --limit 5           the first five
    hn --file page.html    a saved page instead of the live one
    hn --edn               the stories as EDN, one map each
    hn --trust-store FILE  trust the CA certificates in FILE (a Java trust
                           store) instead of the bundled ones; also read
                           from HN_TRUST_STORE"
  (:require [babashka.cli :as cli]
            [champaca.api :as ldk]
            [clojure.string :as str]))

(def front-page "https://news.ycombinator.com/")

(def cli-spec
  {:limit {:coerce :long :default 30 :desc "How many stories to print"}
   :file {:desc "Read a saved front page instead of fetching it"}
   :edn {:coerce :boolean :desc "Print the stories as EDN"}
   :trust-store {:desc "A Java trust store to check the site's certificate against (default: the runtime's, or HN_TRUST_STORE)"}
   :help {:coerce :boolean :alias :h :desc "Show this help"}})

;; --- fetching, through OkHttp (Kotlin) -----------------------------------------

(defn fetch
  "The page at url as a string, through an OkHttp client. A status other than
  200 is an ex-info with the status."
  [url]
  (let [client (ldk/construct "okhttp3.OkHttpClient")
        request (-> (ldk/construct "okhttp3.Request$Builder")
                    (ldk/invoke "url" url)
                    (ldk/invoke "header" "User-Agent" "champaca-hn-example")
                    (ldk/invoke "build"))
        response (ldk/invoke (ldk/invoke client "newCall" request) "execute")]
    (try
      (let [status (ldk/invoke response "code")]
        (when-not (= 200 status)
          (throw (ex-info (str url " answered " status) {:status status})))
        (ldk/invoke (ldk/invoke response "body") "string"))
      (finally (ldk/invoke response "close")))))

;; --- reading the page, through jsoup (Java) --------------------------------------

(defn- text
  "The trimmed text of the first element under el matching css, with the
  page's non-breaking spaces as plain ones, or nil."
  [el css]
  (let [found (ldk/invoke el "selectFirst" css)]
    (when found
      (-> (ldk/invoke found "text") (str/replace " " " ") str/trim))))

(defn- leading-number [s]
  (when s (some-> (re-find #"\d+" s) parse-long)))

(defn- story
  "One story: its row (tr.athing) for the rank, title, link and site, and the
  row after it for the points, author, age and comments. A job post has no
  points, author or comments."
  [row]
  (let [link (ldk/invoke row "selectFirst" "span.titleline > a")
        sub (ldk/invoke row "nextElementSibling")
        comments (when sub
                   (some (fn [a] (let [t (ldk/invoke a "text")]
                                   (when (str/includes? t "comment") t)))
                         (ldk/->clj (ldk/invoke sub "select" "span.subline > a"))))]
    {:id (ldk/invoke row "id")
     :rank (leading-number (text row "span.rank"))
     :title (str/trim (ldk/invoke link "text"))
     :url (ldk/invoke link "absUrl" "href")
     :site (text row "span.sitestr")
     :points (leading-number (when sub (text sub "span.score")))
     :by (when sub (text sub "a[href^=user?id=]"))
     :age (when sub (text sub "span.age"))
     :comments (if comments (leading-number comments) (when (and sub (text sub "a[href^=user?id=]")) 0))}))

(defn stories
  "The stories in the front page's HTML, in page order."
  [html]
  (let [doc (ldk/invoke-static "org.jsoup.Jsoup" "parse" html front-page)]
    (mapv story (ldk/->clj (ldk/invoke doc "select" "tr.athing")))))

;; --- printing ------------------------------------------------------------------

(defn- line-for [{:keys [rank title site points by age comments url]}]
  (str (format "%3d. " rank) title (when site (str " (" site ")")) "\n"
       "     " (if points (str points " points by " by ", " age ", " comments " comments") age) "\n"
       "     " url))

(defn print-stories [ss edn?]
  (if edn?
    (doseq [s ss] (prn s))
    (println (str/join "\n" (map line-for ss)))))

(defn- usage []
  (println "hn: the Hacker News front page, fetched with OkHttp (Kotlin) and read with jsoup (Java)\n")
  (println (cli/format-opts {:spec cli-spec})))

(defn run
  "Print the stories for the parsed options. Answers the exit status."
  [{:keys [limit file edn help trust-store]}]
  (if help
    (do (usage) 0)
    (let [store (or trust-store (System/getenv "HN_TRUST_STORE"))
          ;; read when the first TLS connection is made, so setting it here,
          ;; before any, is enough. A network whose proxy re-signs TLS needs
          ;; its own CA, which the bundled runtime's store doesn't have.
          _ (when store (ldk/invoke-static "java.lang.System" "setProperty" "javax.net.ssl.trustStore" store))
          html (if file (slurp file) (fetch front-page))]
      (print-stories (take limit (stories html)) edn)
      0)))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})
        status (try
                 (ldk/init!)
                 (run opts)
                 (catch Exception e
                   (binding [*out* *err*] (println "hn:" (or (ex-message e) (str e))))
                   1))]
    ;; keep what this run compiled, so the next one starts warm
    (ldk/save-cache!)
    (flush)
    (System/exit status)))
