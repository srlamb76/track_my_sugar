(ns track-my-sugar.db
  "CRUD data access layer for the track_my_sugar SQLite database.

  Tables: PERSON, METRIC_TYPE, HEALTH_DETAIL (see schema/schema.sql).

  Every function takes a connectable `ds` (a datasource from `datasource`,
  a connection, or a transaction) as its first argument. Rows are returned
  as maps with unqualified, lower-case keyword keys, e.g.
  {:id 1 :name \"Steve Lamb\" :email \"...\"}."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]))

(def default-db-path "db/tms_db")

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

;; ---------------------------------------------------------------------------
;; Connectivity

(defn datasource
  "Returns a next.jdbc datasource for the SQLite file at `db-path`
  (defaults to db/tms_db). Foreign key enforcement is enabled on every
  connection."
  ([] (datasource default-db-path))
  ([db-path]
   (jdbc/get-datasource {:dbtype "sqlite"
                         :dbname db-path
                         :foreign_keys "true"})))

(defn init-schema!
  "Executes each statement in the SQL file at `schema-path` against `ds`.
  Useful for building a fresh database (e.g. in tests)."
  [ds schema-path]
  (jdbc/with-transaction [tx ds]
    (doseq [stmt (->> (str/split (slurp schema-path) #";")
                      (map str/trim)
                      (remove str/blank?))]
      (jdbc/execute! tx [stmt]))))

;; ---------------------------------------------------------------------------
;; Generic helpers

(defn- insert-returning! [ds table row]
  (sql/insert! ds table row (assoc opts :return-keys false :suffix "RETURNING *")))

(defn- get-by-id [ds table id]
  (sql/get-by-id ds table id opts))

(defn- find-by [ds table where]
  (sql/find-by-keys ds table where (assoc opts :order-by [:id])))

(defn- update-by-id!
  "Updates the row with `id` and returns the updated row, or nil when no
  row matched."
  [ds table id changes]
  (when (pos? (::jdbc/update-count (sql/update! ds table changes {:id id})))
    (get-by-id ds table id)))

(defn- delete-by-id!
  "Deletes the row with `id`. Returns true when a row was deleted."
  [ds table id]
  (pos? (::jdbc/update-count (sql/delete! ds table {:id id}))))

;; ---------------------------------------------------------------------------
;; PERSON

(defn create-person!
  "Inserts a person and returns the new row. `person` is {:name ... :email ...}."
  [ds {:keys [name email]}]
  (insert-returning! ds :person {:name name :email email}))

(defn get-person [ds id]
  (get-by-id ds :person id))

(defn get-person-by-name [ds name]
  (first (find-by ds :person {:name name})))

(defn get-person-by-email [ds email]
  (first (find-by ds :person {:email email})))

(defn list-persons [ds]
  (find-by ds :person :all))

(defn update-person!
  "Updates the given fields (:name and/or :email) of person `id`.
  Returns the updated row, or nil if the person does not exist."
  [ds id changes]
  (update-by-id! ds :person id (select-keys changes [:name :email])))

(defn delete-person! [ds id]
  (delete-by-id! ds :person id))

;; ---------------------------------------------------------------------------
;; METRIC_TYPE

(defn create-metric-type!
  "Inserts a metric type and returns the new row.
  `metric-type` is {:type ... :description ...}."
  [ds {:keys [type description]}]
  (insert-returning! ds :metric_type {:type type :description description}))

(defn get-metric-type [ds id]
  (get-by-id ds :metric_type id))

(defn get-metric-type-by-type [ds type]
  (first (find-by ds :metric_type {:type type})))

(defn list-metric-types [ds]
  (find-by ds :metric_type :all))

(defn update-metric-type!
  "Updates :type and/or :description of metric type `id`.
  Returns the updated row, or nil if it does not exist."
  [ds id changes]
  (update-by-id! ds :metric_type id (select-keys changes [:type :description])))

(defn delete-metric-type! [ds id]
  (delete-by-id! ds :metric_type id))

;; ---------------------------------------------------------------------------
;; HEALTH_DETAIL

(def ^:private health-detail-keys
  [:person_id :health_metric :metric_type_id :created_date_time])

(defn create-health-detail!
  "Inserts a health reading and returns the new row. `detail` is
  {:person_id ... :health_metric ... :metric_type_id ...} with an optional
  :created_date_time (defaults to CURRENT_TIMESTAMP)."
  [ds detail]
  (insert-returning! ds :health_detail
                     (into {} (remove (comp nil? val))
                           (select-keys detail health-detail-keys))))

(defn get-health-detail [ds id]
  (get-by-id ds :health_detail id))

(defn list-health-details [ds]
  (find-by ds :health_detail :all))

(defn list-health-details-for-person
  "Returns all readings for `person-id`, optionally restricted to
  `metric-type-id`, ordered by created_date_time then id."
  ([ds person-id]
   (jdbc/execute! ds ["SELECT * FROM health_detail
                       WHERE person_id = ?
                       ORDER BY created_date_time, id" person-id]
                  opts))
  ([ds person-id metric-type-id]
   (jdbc/execute! ds ["SELECT * FROM health_detail
                       WHERE person_id = ? AND metric_type_id = ?
                       ORDER BY created_date_time, id" person-id metric-type-id]
                  opts)))

(defn update-health-detail!
  "Updates the given fields of health detail `id`.
  Returns the updated row, or nil if it does not exist."
  [ds id changes]
  (update-by-id! ds :health_detail id (select-keys changes health-detail-keys)))

(defn delete-health-detail! [ds id]
  (delete-by-id! ds :health_detail id))
