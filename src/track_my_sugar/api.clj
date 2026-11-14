(ns track-my-sugar.api
  "JSON REST API over the track-my-sugar.db CRUD layer.

  All endpoints live under /api and exchange JSON with snake_case keys
  matching the database columns. Status codes:
    200 OK, 201 Created, 204 No Content (delete),
    400 invalid input, 404 not found, 409 constraint violation,
    403 untrusted Host header, 413 body too large, 415 body not JSON.

  Security model: the app has no accounts, so every record is visible to
  anyone who can reach the port. It therefore listens on loopback only by
  default (see server.clj) and the middleware below blocks the web attacks
  that can reach a loopback server through a browser: DNS rebinding (Host
  check), cross-site form posts (JSON content type required) and content
  sniffing / framing / script injection (response headers incl. CSP)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compojure.core :refer [DELETE GET POST PUT context routes]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp]
            [track-my-sugar.db :as db])
  (:import (java.io Reader)
           (java.sql SQLException)))

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

(def ^:private max-body-chars
  "Largest request body accepted. Real payloads are a few hundred bytes."
  65536)

(defn- slurp-limited
  "Reads `body` as text, throwing ex-info with ::status 413 once it is longer
  than `max-body-chars` (so an oversized body is never held in memory)."
  [body]
  (with-open [^Reader r (io/reader body)]
    (let [buf (char-array 4096)
          sb  (StringBuilder.)]
      (loop []
        (let [n (.read r buf)]
          (when (pos? n)
            (.append sb buf 0 n)
            (when (> (.length sb) max-body-chars)
              (throw (ex-info "Request body too large" {::status 413})))
            (recur))))
      (str sb))))

(defn- read-json-body
  "Parses the request body as a JSON object with keyword keys.
  Throws ex-info with ::status 400 when the body is not a JSON object."
  [request]
  (let [body (some-> (:body request) slurp-limited)
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

;; Length limits keep one request from storing megabytes of text.
(defn- string-up-to [n] #(and (string? %) (<= (count %) n)))
(defn- name-string? [n] #(and (non-blank-string? %) (<= (count %) n)))

;; The only timestamp format the app writes and sorts on ("YYYY-MM-DD HH:MM:SS");
;; accepting anything else would break ordering and the front end's date filters.
(def ^:private timestamp-re #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}")
(def ^:private timestamp? #(and (string? %) (boolean (re-matches timestamp-re %))))

;; Largest id / reading value accepted: ids are SQLite integers and an
;; unbounded double would only ever be a typo or an attack on charts.
(def ^:private max-value 1e9)
(def ^:private bounded-number? #(and (number? %) (<= (- max-value) % max-value)))

(def ^:private person-fields
  {:name  [(name-string? 200) "a non-empty string of at most 200 characters" true]
   :email [#(or (nil? %) ((string-up-to 254) %)) "a string of at most 254 characters, or null" false]})

(def ^:private metric-type-fields
  {:type        [(name-string? 50) "a non-empty string of at most 50 characters" true]
   :description [(name-string? 500) "a non-empty string of at most 500 characters" true]})

(def ^:private health-detail-fields
  {:person_id         [pos-int? "a positive integer" true]
   :metric_type_id    [pos-int? "a positive integer" true]
   :health_metric     [bounded-number? "a number between -1e9 and 1e9" true]
   :created_date_time [timestamp? "a string such as \"2026-01-02 08:30:00\"" false]})

(defn- parse-id
  "Parses a numeric id from the URL. Returns nil (a 404, never an exception)
  for anything that does not fit a long, such as 99999999999999999999."
  [s]
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
          (error 500 "Database error")))
      ;; Anything unexpected: log it server-side, tell the client nothing, so
      ;; stack traces and SQL never leak in a response.
      (catch Exception e
        (binding [*out* *err*] (println "Unhandled error:" (.toString e)))
        (error 500 "Internal error")))))

(def ^:private default-allowed-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn- host-name
  "The host part of a Host header value, without the port."
  [host]
  (when host
    (str/lower-case (if (str/starts-with? host "[")
                      (subs host 0 (inc (or (str/index-of host "]") (dec (count host)))))
                      (first (str/split host #":" 2))))))

(defn- wrap-host-check
  "Rejects requests whose Host header is not an allowed name (403). This
  stops DNS rebinding, where a hostile web page resolves its own domain to
  127.0.0.1 and then reads the API from the victim's browser."
  [handler allowed-hosts]
  (fn [request]
    (if (contains? allowed-hosts (host-name (get-in request [:headers "host"])))
      (handler request)
      (error 403 "Host not allowed"))))

(defn- wrap-json-only
  "Requires a JSON Content-Type on POST and PUT (415 otherwise). A cross-site
  HTML form can only send form content types, and a JSON request from another
  origin needs a CORS preflight that this server never grants, so this blocks
  CSRF without tokens."
  [handler]
  (fn [request]
    (if (and (#{:post :put} (:request-method request))
             (not (some-> (get-in request [:headers "content-type"])
                          str/lower-case
                          (str/starts-with? "application/json"))))
      (error 415 "Content-Type must be application/json")
      (handler request))))

;; The page loads only its own files: no inline script or style, no remote
;; origins, no plugins and no framing. index.html carries the same policy in a
;; <meta> tag because the Android app has no HTTP headers to put it in.
(def ^:private content-security-policy
  (str "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
       "connect-src 'self'; object-src 'none'; base-uri 'none'; "
       "form-action 'self'; frame-ancestors 'none'"))

(defn- wrap-security-headers [handler]
  (fn [request]
    (when-let [response (handler request)]
      (update response :headers merge
              {"Content-Security-Policy"   content-security-policy
               "X-Content-Type-Options"    "nosniff"
               "X-Frame-Options"           "DENY"
               "Referrer-Policy"           "no-referrer"
               "Cross-Origin-Resource-Policy" "same-origin"
               "Permissions-Policy"        "camera=(), microphone=(), geolocation=()"}
              ;; Health data must not linger in browser or proxy caches.
              (when (str/starts-with? (:uri request) "/api")
                {"Cache-Control" "no-store"})))))

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
  static front end from resources/public. `allowed-hosts` are the Host header
  names accepted (default: loopback names only)."
  ([ds] (app ds default-allowed-hosts))
  ([ds allowed-hosts]
   (-> (routes
        (context "/api" []
          (person-routes ds)
          (metric-type-routes ds)
          (health-detail-routes ds)
          (fn [_] (not-found)))
        (route/resources "/" {:root "public"})
        (route/not-found "Not found"))
       wrap-errors
       wrap-json-only
       wrap-params
       wrap-index
       wrap-content-type
       wrap-not-modified
       (wrap-host-check allowed-hosts)
       wrap-security-headers)))
