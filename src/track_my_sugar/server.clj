(ns track-my-sugar.server
  "Entry point: serves the API and front end with embedded Jetty."
  (:require [ring.adapter.jetty :as jetty]
            [track-my-sugar.api :as api]
            [track-my-sugar.db :as db])
  (:gen-class))

(defn start!
  "Starts the server and returns the Jetty instance (call .stop to stop it)."
  [{:keys [port db-path join?] :or {port 3000 db-path db/default-db-path join? false}}]
  (jetty/run-jetty (api/app (db/datasource db-path))
                   {:port port :join? join?}))

(defn -main
  "Usage: [port] [db-path]. Also reads PORT and TMS_DB env vars."
  [& [port db-path]]
  (let [port    (parse-long (or port (System/getenv "PORT") "3000"))
        db-path (or db-path (System/getenv "TMS_DB") db/default-db-path)]
    (println (str "Track My Sugar running on http://localhost:" port " (db: " db-path ")"))
    (start! {:port port :db-path db-path :join? true})))
