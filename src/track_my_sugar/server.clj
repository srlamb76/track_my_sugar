(ns track-my-sugar.server
  "Entry point: serves the API and front end with embedded Jetty.

  The app has no login, so by default it listens on loopback only
  (127.0.0.1) and accepts only loopback Host headers. To reach it from
  another device set TMS_HOST (for example 0.0.0.0) and TMS_ALLOWED_HOSTS
  (comma-separated names clients use), and put it behind something that
  authenticates, such as a VPN or an authenticating reverse proxy."
  (:require [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [track-my-sugar.api :as api]
            [track-my-sugar.db :as db])
  (:gen-class))

(def ^:private loopback-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn start!
  "Starts the server and returns the Jetty instance (call .stop to stop it).
  Options: :port (3000), :db-path, :host (127.0.0.1), :allowed-hosts (loopback
  names), :join?."
  [{:keys [port db-path host allowed-hosts join?]
    :or {port 3000 db-path db/default-db-path host "127.0.0.1"
         allowed-hosts loopback-hosts join? false}}]
  (let [ds (db/datasource db-path)]
    (db/ensure-indexes! ds)
    (jetty/run-jetty (api/app ds allowed-hosts)
                     {:host host
                      :port port
                      :join? join?
                      :send-server-version? false     ; don't advertise the Jetty version
                      :request-header-size 8192       ; reject oversized headers early
                      :min-threads 2                  ; small pool: this is a one-user app
                      :max-threads 16})))

(defn -main
  "Usage: [port] [db-path]. Also reads the PORT, TMS_DB, TMS_HOST and
  TMS_ALLOWED_HOSTS environment variables."
  [& [port db-path]]
  (let [port    (parse-long (or port (System/getenv "PORT") "3000"))
        db-path (or db-path (System/getenv "TMS_DB") db/default-db-path)
        host    (or (System/getenv "TMS_HOST") "127.0.0.1")
        extra   (some->> (System/getenv "TMS_ALLOWED_HOSTS") (re-seq #"[^,\s]+") (map str/lower-case))]
    (println (str "Track My Sugar running on http://" host ":" port " (db: " db-path ")"))
    (when-not (contains? #{"127.0.0.1" "localhost" "::1"} host)
      (println "WARNING: listening beyond loopback and there is no login; anyone who can"
               "reach this port can read and change every record."))
    (start! {:port port :db-path db-path :host host
             :allowed-hosts (into loopback-hosts extra) :join? true})))
