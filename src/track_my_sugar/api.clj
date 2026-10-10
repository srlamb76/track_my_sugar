(ns track-my-sugar.api
  "JSON REST API over the track-my-sugar.db CRUD layer.

  All endpoints live under /api and exchange JSON with snake_case keys
  matching the database columns. Status codes:
    200 OK, 201 Created, 204 No Content (delete),
    400 invalid input, 404 not found, 409 constraint violation."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [compojure.core :refer [DELETE GET POST PUT context routes]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp]
            [track-my-sugar.db :as db])
  (:import (java.sql SQLException)))

;; ---------------------------------------------------------------------------
;; Responses

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-str body)})

(defn- ok [body] (json-response 200 body))
(defn- created [body] (json-response 201 body))
(defn- error [status message] (json-response status {:error message}))
(defn- not-found [] (error 404 "Not found"))

(defn- ok-or-404 [row] (if row (ok row) (not-found)))

(defn- no-content-or-404 [deleted?]
  (if deleted? {:status 204 :headers {} :body ""} (not-found)))

;; ---------------------------------------------------------------------------
;; Request parsing & validation

(defn- read-json-body
  "Parses the request body as a JSON object with keyword keys.
  Throws ex-info with ::status 400 when the body is not a JSON object."
  [request]
  (let [body (some-> (:body request) slurp)
        data (try
               (when-not (str/blank? body) (json/read-str body :key-fn keyword))
               (catch Exception _
                 (throw (ex-info "Request body is not valid JSON" {::status 400}))))]
    (when-not (map? data)
      (throw (ex-info "Request body must be a JSON object" {::status 400})))
    data))

(defn- invalid! [message]
  (throw (ex-info message {::status 400})))

(defn- validate
  "Checks `data` against `fields`, a map of key -> [pred description required?].
  On create (`creating?` true) every required field must be present. On
  update, at least one field must be supplied. Only present fields are
  type-checked. Returns the known fields from `data`."
  [data fields creating?]
  (let [present (select-keys data (keys fields))]
    (when (and (not creating?) (empty? present))
      (invalid! (str "Supply at least one of: "
                     (str/join ", " (map name (keys fields))))))
    (doseq [[k [pred desc required?]] fields
            :let [v (get data k)]]
      (cond
        (and creating? required? (nil? v)) (invalid! (str (name k) " is required"))
        (and (contains? data k) (not (pred v))) (invalid! (str (name k) " must be " desc))))
    present))

(def ^:private non-blank-string? #(and (string? %) (not (str/blank? %))))

(def ^:private person-fields
  {:name  [non-blank-string? "a non-empty string" true]
   :email [#(or (nil? %) (string? %)) "a string or null" false]})

(def ^:private metric-type-fields
  {:type        [non-blank-string? "a non-empty string" true]
   :description [non-blank-string? "a non-empty string" true]})

(def ^:private health-detail-fields
  {:person_id         [pos-int? "a positive integer" true]
   :metric_type_id    [pos-int? "a positive integer" true]
   :health_metric     [number? "a number" true]
   :created_date_time [non-blank-string? "a string such as \"2026-01-02 08:30:00\"" false]})

(defn- parse-id [s]
  (try (Long/parseLong s) (catch NumberFormatException _ nil)))

;; ---------------------------------------------------------------------------
;; Routes

(defn- person-routes [ds]
  (routes
   (GET "/persons" [] (ok (db/list-persons ds)))
   (POST "/persons" req
     (created (db/create-person! ds (validate (read-json-body req) person-fields true))))
   (context "/persons/:id{[0-9]+}" [id :<< parse-id]
     (GET "/" [] (ok-or-404 (db/get-person ds id)))
     (PUT "/" req
       (ok-or-404 (db/update-person! ds id (validate (read-json-body req) person-fields false))))
     (DELETE "/" [] (no-content-or-404 (db/delete-person! ds id)))
     (GET "/health-details" [metric_type_id]
       (if-not (db/get-person ds id)
         (not-found)
         (ok (if metric_type_id
               (if-let [mt (parse-id metric_type_id)]
                 (db/list-health-details-for-person ds id mt)
                 (invalid! "metric_type_id must be a positive integer"))
               (db/list-health-details-for-person ds id))))))))

(defn- metric-type-routes [ds]
  (routes
   (GET "/metric-types" [] (ok (db/list-metric-types ds)))
   (POST "/metric-types" req
     (created (db/create-metric-type! ds (validate (read-json-body req) metric-type-fields true))))
   (context "/metric-types/:id{[0-9]+}" [id :<< parse-id]
     (GET "/" [] (ok-or-404 (db/get-metric-type ds id)))
     (PUT "/" req
       (ok-or-404 (db/update-metric-type! ds id (validate (read-json-body req) metric-type-fields false))))
     (DELETE "/" [] (no-content-or-404 (db/delete-metric-type! ds id))))))

(defn- health-detail-routes [ds]
  (routes
   (GET "/health-details" [] (ok (db/list-health-details ds)))
   (POST "/health-details" req
     (created (db/create-health-detail! ds (validate (read-json-body req) health-detail-fields true))))
   (context "/health-details/:id{[0-9]+}" [id :<< parse-id]
     (GET "/" [] (ok-or-404 (db/get-health-detail ds id)))
     (PUT "/" req
       (ok-or-404 (db/update-health-detail! ds id (validate (read-json-body req) health-detail-fields false))))
     (DELETE "/" [] (no-content-or-404 (db/delete-health-detail! ds id))))))

;; ---------------------------------------------------------------------------
;; Middleware

(defn- constraint-message [^SQLException e]
  (let [msg (or (.getMessage e) "")]
    (cond
      (str/includes? msg "UNIQUE")      "A record with that value already exists"
      (str/includes? msg "FOREIGNKEY")  "Referenced record does not exist, or record is still in use"
      (str/includes? msg "FOREIGN KEY") "Referenced record does not exist, or record is still in use"
      (str/includes? msg "NOT NULL")    "A required value is missing"
      :else                             msg)))

(defn- wrap-errors [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (if-let [status (::status (ex-data e))]
          (error status (ex-message e))
          (throw e)))
      (catch SQLException e
        (if (str/includes? (or (.getMessage e) "") "CONSTRAINT")
          (error 409 (constraint-message e))
          (error 500 "Database error"))))))

(defn- wrap-index [handler]
  (fn [request]
    (if (= "/" (:uri request))
      (some-> (resp/resource-response "public/index.html")
              (resp/content-type "text/html; charset=utf-8"))
      (handler request))))

;; ---------------------------------------------------------------------------
;; Handler

(defn app
  "Builds the Ring handler: the JSON API under /api backed by `ds`, plus the
  static front end from resources/public."
  [ds]
  (-> (routes
       (context "/api" []
         (person-routes ds)
         (metric-type-routes ds)
         (health-detail-routes ds)
         (fn [_] (not-found)))
       (route/resources "/" {:root "public"})
       (route/not-found "Not found"))
      wrap-errors
      wrap-params
      wrap-index
      wrap-content-type
      wrap-not-modified))
