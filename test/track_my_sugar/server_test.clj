(ns track-my-sugar.server-test
  "Starts the real Jetty server to check what mock requests can't: the
  listening address and behaviour over an actual socket."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [track-my-sugar.db :as db]
            [track-my-sugar.server :as server])
  (:import (java.io File)
           (java.net Socket)
           (org.eclipse.jetty.server ServerConnector)))

(defn- http-get
  "Sends a raw GET with the given Host header and returns the response text."
  [port host path]
  (with-open [s (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 5000)
    (doto (io/writer (.getOutputStream s))
      (.write (str "GET " path " HTTP/1.1\r\nHost: " host "\r\nConnection: close\r\n\r\n"))
      (.flush))
    (slurp (io/reader (.getInputStream s)))))

(deftest listens-on-loopback-and-checks-host
  (let [file (File/createTempFile "tms_server" ".db")
        ds   (db/datasource (.getPath file))
        _    (db/init-schema! ds (io/file "schema/schema.sql"))
        srv  (server/start! {:port 0 :db-path (.getPath file)})]
    (try
      (let [^ServerConnector conn (first (.getConnectors srv))
            port (.getLocalPort conn)]
        (testing "binds to loopback only by default"
          (is (= "127.0.0.1" (.getHost conn))))
        (testing "serves the app to a loopback Host"
          (let [r (http-get port (str "localhost:" port) "/api/persons")]
            (is (str/starts-with? r "HTTP/1.1 200"))
            (is (str/includes? r "X-Content-Type-Options: nosniff"))
            (is (not (re-find #"(?i)\r\nServer:" r)) "Jetty version is not advertised")))
        (testing "refuses a rebinding Host over a real connection"
          (is (str/starts-with? (http-get port "evil.example.com" "/api/persons") "HTTP/1.1 403")))
        (testing "creates the reading indexes on startup"
          (is (= 3 (count (next.jdbc/execute!
                           ds ["SELECT name FROM sqlite_master WHERE name LIKE 'health_detail_%idx'"]))))))
      (finally
        (.stop srv)
        (.delete file)))))
