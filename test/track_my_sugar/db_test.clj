(ns track-my-sugar.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [track-my-sugar.db :as db])
  (:import (java.io File)))

(def ^:dynamic *ds* nil)

(defn with-fresh-db
  "Runs each test against a new temporary SQLite file built from
  schema/schema.sql, so the real db/tms_db is never touched."
  [f]
  (let [file (File/createTempFile "tms_test" ".db")]
    (try
      (let [ds (db/datasource (.getPath file))]
        (db/init-schema! ds (io/file "schema/schema.sql"))
        (binding [*ds* ds] (f)))
      (finally (.delete file)))))

(use-fixtures :each with-fresh-db)

(defn- sql-exception? [f]
  (try (f) false
       (catch java.sql.SQLException _ true)))

;; ---------------------------------------------------------------------------
;; PERSON

(deftest person-crud
  (let [p (db/create-person! *ds* {:name "Steve Lamb" :email "steve@example.com"})]
    (testing "create returns the row with a generated id"
      (is (pos-int? (:id p)))
      (is (= {:name "Steve Lamb" :email "steve@example.com"}
             (dissoc p :id))))

    (testing "read"
      (is (= p (db/get-person *ds* (:id p))))
      (is (= p (db/get-person-by-name *ds* "Steve Lamb")))
      (is (= p (db/get-person-by-email *ds* "steve@example.com")))
      (is (= [p] (db/list-persons *ds*)))
      (is (nil? (db/get-person *ds* 999)))
      (is (nil? (db/get-person-by-name *ds* "Nobody"))))

    (testing "update"
      (let [u (db/update-person! *ds* (:id p) {:email "new@example.com"})]
        (is (= (assoc p :email "new@example.com") u))
        (is (= u (db/get-person *ds* (:id p)))))
      (is (nil? (db/update-person! *ds* 999 {:name "Ghost"}))))

    (testing "delete"
      (is (true? (db/delete-person! *ds* (:id p))))
      (is (nil? (db/get-person *ds* (:id p))))
      (is (false? (db/delete-person! *ds* (:id p))))
      (is (empty? (db/list-persons *ds*))))))

(deftest person-constraints
  (db/create-person! *ds* {:name "A" :email "a@example.com"})
  (testing "name is unique"
    (is (sql-exception? #(db/create-person! *ds* {:name "A" :email "other@example.com"}))))
  (testing "email is unique"
    (is (sql-exception? #(db/create-person! *ds* {:name "B" :email "a@example.com"}))))
  (testing "name is required"
    (is (sql-exception? #(db/create-person! *ds* {:email "c@example.com"}))))
  (testing "email is optional"
    (is (nil? (:email (db/create-person! *ds* {:name "D"}))))))

;; ---------------------------------------------------------------------------
;; METRIC_TYPE

(deftest metric-type-crud
  (let [m (db/create-metric-type! *ds* {:type "BSL" :description "Blood Sugar Level"})]
    (testing "create"
      (is (pos-int? (:id m)))
      (is (= {:type "BSL" :description "Blood Sugar Level"} (dissoc m :id))))

    (testing "read"
      (is (= m (db/get-metric-type *ds* (:id m))))
      (is (= m (db/get-metric-type-by-type *ds* "BSL")))
      (is (= [m] (db/list-metric-types *ds*)))
      (is (nil? (db/get-metric-type *ds* 999))))

    (testing "update"
      (let [u (db/update-metric-type! *ds* (:id m) {:description "Blood glucose"})]
        (is (= "Blood glucose" (:description u)))
        (is (= u (db/get-metric-type *ds* (:id m)))))
      (is (nil? (db/update-metric-type! *ds* 999 {:type "X"}))))

    (testing "delete"
      (is (true? (db/delete-metric-type! *ds* (:id m))))
      (is (nil? (db/get-metric-type *ds* (:id m))))
      (is (false? (db/delete-metric-type! *ds* (:id m)))))))

(deftest metric-type-constraints
  (db/create-metric-type! *ds* {:type "BSL" :description "Blood Sugar Level"})
  (is (sql-exception? #(db/create-metric-type! *ds* {:type "BSL" :description "Other"})))
  (is (sql-exception? #(db/create-metric-type! *ds* {:type "HBA1C" :description "Blood Sugar Level"})))
  (is (sql-exception? #(db/create-metric-type! *ds* {:type "HBA1C"}))))

;; ---------------------------------------------------------------------------
;; HEALTH_DETAIL

(defn- seed []
  {:person (db/create-person! *ds* {:name "Steve Lamb" :email "steve@example.com"})
   :bsl    (db/create-metric-type! *ds* {:type "BSL" :description "Blood Sugar Level"})
   :hba1c  (db/create-metric-type! *ds* {:type "HBA1C" :description "Glycated haemoglobin"})})

(deftest health-detail-crud
  (let [{:keys [person bsl]} (seed)
        h (db/create-health-detail! *ds* {:person_id (:id person)
                                          :metric_type_id (:id bsl)
                                          :health_metric 5.6})]
    (testing "create defaults created_date_time"
      (is (pos-int? (:id h)))
      (is (= (:id person) (:person_id h)))
      (is (= (:id bsl) (:metric_type_id h)))
      (is (== 5.6 (:health_metric h)))
      (is (some? (:created_date_time h))))

    (testing "read"
      (is (= h (db/get-health-detail *ds* (:id h))))
      (is (= [h] (db/list-health-details *ds*)))
      (is (nil? (db/get-health-detail *ds* 999))))

    (testing "update"
      (let [u (db/update-health-detail! *ds* (:id h) {:health_metric 7.2})]
        (is (== 7.2 (:health_metric u)))
        (is (= u (db/get-health-detail *ds* (:id h)))))
      (is (nil? (db/update-health-detail! *ds* 999 {:health_metric 1.0}))))

    (testing "delete"
      (is (true? (db/delete-health-detail! *ds* (:id h))))
      (is (nil? (db/get-health-detail *ds* (:id h))))
      (is (false? (db/delete-health-detail! *ds* (:id h)))))))

(deftest health-detail-explicit-timestamp
  (let [{:keys [person bsl]} (seed)
        h (db/create-health-detail! *ds* {:person_id (:id person)
                                          :metric_type_id (:id bsl)
                                          :health_metric 6.1
                                          :created_date_time "2026-01-02 08:30:00"})]
    (is (= "2026-01-02 08:30:00" (:created_date_time h)))))

(deftest health-details-for-person
  (let [{:keys [person bsl hba1c]} (seed)
        other (db/create-person! *ds* {:name "Other" :email "other@example.com"})
        add! (fn [p m v ts]
               (db/create-health-detail! *ds* {:person_id (:id p) :metric_type_id (:id m)
                                                :health_metric v :created_date_time ts}))
        r2 (add! person bsl 6.0 "2026-01-02 08:00:00")
        r1 (add! person bsl 5.0 "2026-01-01 08:00:00")
        r3 (add! person hba1c 48.0 "2026-01-03 08:00:00")
        _  (add! other bsl 9.9 "2026-01-01 09:00:00")]
    (testing "all readings for a person, ordered by time"
      (is (= [r1 r2 r3] (db/list-health-details-for-person *ds* (:id person)))))
    (testing "filtered by metric type"
      (is (= [r1 r2] (db/list-health-details-for-person *ds* (:id person) (:id bsl))))
      (is (= [r3] (db/list-health-details-for-person *ds* (:id person) (:id hba1c)))))
    (testing "unknown person"
      (is (empty? (db/list-health-details-for-person *ds* 999))))))

(deftest health-detail-constraints
  (let [{:keys [person bsl]} (seed)]
    (testing "health_metric is required"
      (is (sql-exception? #(db/create-health-detail! *ds* {:person_id (:id person)
                                                           :metric_type_id (:id bsl)}))))
    (testing "metric_type_id must reference an existing METRIC_TYPE"
      (is (sql-exception? #(db/create-health-detail! *ds* {:person_id (:id person)
                                                           :metric_type_id 999
                                                           :health_metric 1.0}))))
    (testing "person_id must reference an existing PERSON"
      (is (sql-exception? #(db/create-health-detail! *ds* {:person_id 999
                                                           :metric_type_id (:id bsl)
                                                           :health_metric 1.0}))))
    (testing "cannot delete a person that still has readings"
      (db/create-health-detail! *ds* {:person_id (:id person)
                                      :metric_type_id (:id bsl)
                                      :health_metric 1.0})
      (is (sql-exception? #(db/delete-person! *ds* (:id person)))))))

;; ---------------------------------------------------------------------------
;; Connectivity against the real database (read-only)

(deftest real-db-connectivity
  (let [ds (db/datasource)]
    (is (vector? (db/list-persons ds)))
    (is (some? (db/get-metric-type-by-type ds "BSL")))))
