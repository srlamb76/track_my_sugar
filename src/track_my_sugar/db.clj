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
                         :foreign_keys "true"
                         ;; Wait up to 5s for a competing writer instead of
                         ;; failing at once with SQLITE_BUSY.
                         :busy_timeout "5000"})))

(defn ensure-indexes!
  "Creates the HEALTH_DETAIL indexes (also in schema.sql) if they are missing,
  so databases made before they existed get them on startup. They serve the
  per-person / per-metric / date-ordered reading queries. Safe to repeat."
  [ds]
  (doseq [stmt ["CREATE INDEX IF NOT EXISTS health_detail_person_idx ON HEALTH_DETAIL(person_id, metric_type_id, created_date_time)"
                "CREATE INDEX IF NOT EXISTS health_detail_metric_idx ON HEALTH_DETAIL(metric_type_id)"
                "CREATE INDEX IF NOT EXISTS health_detail_time_idx ON HEALTH_DETAIL(created_date_time)"]]
    (jdbc/execute! ds [stmt])))

(def schema-version
  "Version of the database layout, kept in SQLite's `PRAGMA user_version`.
  Raise it and add a step to `migrations` for every schema change."
  2)

(defn- user-version [ds]
  (:user_version (jdbc/execute-one! ds ["PRAGMA user_version"] opts)))

(defn- table-exists? [ds table]
  (boolean (seq (jdbc/execute! ds ["SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?" table]))))

(def ^:private migrations
  "Target version -> function that upgrades a database from the version
  before it. Steps must only add or convert; never drop user data."
  {2 ensure-indexes!})

(defn migrate!
  "Brings an existing database up to `schema-version` without losing data, so
  installing a newer version of the app over old data is safe.

  - Steps run in order, each in its own transaction (a failure rolls that step
    back and leaves earlier ones in place).
  - A database from before versions were tracked (user_version 0 but already
    holding tables) counts as version 1.
  - A database from a NEWER app than this one is refused (ex-info) rather than
    opened, so an older build can't damage it.
  - When `db-path` is given and steps are pending, the file is first copied to
    `<db-path>.bak-v<old-version>`.
  - A file with no tables is left alone (create it from schema.sql)."
  ([ds] (migrate! ds nil))
  ([ds db-path]
   (when (table-exists? ds "PERSON")
     (let [stored  (user-version ds)
           current (if (zero? stored) 1 stored)]
       (when (> current schema-version)
         (throw (ex-info (str "The database is from a newer version of Track My Sugar (layout "
                              current ", this one understands " schema-version ")")
                         {:found current :supported schema-version})))
       (when (< current schema-version)
         (when db-path
           (java.nio.file.Files/copy (.toPath (java.io.File. ^String db-path))
                                     (.toPath (java.io.File. (str db-path ".bak-v" current)))
                                     (into-array java.nio.file.CopyOption
                                                 [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
         (doseq [v (range (inc current) (inc schema-version))]
           (jdbc/with-transaction [tx ds]
             ((get migrations v #(throw (ex-info (str "No migration to version " v) {:version v}))) tx)
             (jdbc/execute! tx [(str "PRAGMA user_version = " v)]))))
       schema-version))))

(defn init-schema!
  "Executes each statement in the SQL file at `schema-path` against `ds`.
  Useful for building a fresh database (e.g. in tests)."
  [ds schema-path]
  (jdbc/with-transaction [tx ds]
    (doseq [stmt (->> (str/split (slurp schema-path) #";")
                      (map str/trim)
                      (remove str/blank?))]
      (jdbc/execute! tx [stmt]))
    ;; A brand-new database starts at the current layout.
    (jdbc/execute! tx [(str "PRAGMA user_version = " schema-version)])))

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
