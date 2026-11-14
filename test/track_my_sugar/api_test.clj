(ns track-my-sugar.api-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [track-my-sugar.api :as api]
            [track-my-sugar.db :as db])
  (:import (java.io File)))

(def ^:dynamic *app* nil)

(defn with-fresh-app
  "Runs each test against an API backed by a new temporary SQLite database."
  [f]
  (let [file (File/createTempFile "tms_api_test" ".db")]
    (try
      (let [ds (db/datasource (.getPath file))]
        (db/init-schema! ds (io/file "schema/schema.sql"))
        (binding [*app* (api/app ds)] (f)))
      (finally (.delete file)))))

(use-fixtures :each with-fresh-app)

(defn- call
  "Sends a request to the app. `body` may be a Clojure value (sent as JSON)
  or a raw string. Returns {:status .. :body <parsed JSON or nil> :headers ..}."
  ([method path] (call method path nil))
  ([method path body]
   (let [req (cond-> (mock/request method path)
               (string? body) (-> (mock/body body) (mock/content-type "application/json"))
               (and body (not (string? body))) (mock/json-body body))
         {:keys [status headers] :as resp} (*app* req)
         raw (let [b (:body resp)] (if (string? b) b (some-> b slurp)))]
     {:status  status
      :headers headers
      :body    (when (and (not (str/blank? raw))
                          (some-> (get headers "Content-Type") (str/includes? "json")))
                 (json/read-str raw :key-fn keyword))})))

(defn- create! [path body]
  (let [{:keys [status body]} (call :post path body)]
    (assert (= 201 status) (str "create failed: " status " " body))
    body))

(defn- seed []
  {:person (create! "/api/persons" {:name "Steve Lamb" :email "steve@example.com"})
   :bsl    (create! "/api/metric-types" {:type "BSL" :description "Blood Sugar Level"})
   :hba1c  (create! "/api/metric-types" {:type "HBA1C" :description "Glycated haemoglobin"})})

;; ---------------------------------------------------------------------------
;; Persons

(deftest persons-crud
  (testing "list is empty initially"
    (is (= {:status 200 :body []} (select-keys (call :get "/api/persons") [:status :body]))))

  (let [{:keys [status body headers]} (call :post "/api/persons" {:name "Steve" :email "s@example.com"})
        id (:id body)]
    (testing "create returns 201 and the row as JSON"
      (is (= 201 status))
      (is (str/starts-with? (get headers "Content-Type") "application/json"))
      (is (pos-int? id))
      (is (= {:id id :name "Steve" :email "s@example.com"} body)))

    (testing "get by id and list"
      (is (= {:status 200 :body body} (select-keys (call :get (str "/api/persons/" id)) [:status :body])))
      (is (= [body] (:body (call :get "/api/persons")))))

    (testing "partial update"
      (let [r (call :put (str "/api/persons/" id) {:email "new@example.com"})]
        (is (= 200 (:status r)))
        (is (= {:id id :name "Steve" :email "new@example.com"} (:body r)))))

    (testing "email can be cleared with null"
      (is (nil? (:email (:body (call :put (str "/api/persons/" id) {:email nil}))))))

    (testing "delete returns 204 then 404"
      (is (= 204 (:status (call :delete (str "/api/persons/" id)))))
      (is (= 404 (:status (call :get (str "/api/persons/" id)))))
      (is (= 404 (:status (call :delete (str "/api/persons/" id))))))))

(deftest persons-errors
  (create! "/api/persons" {:name "A" :email "a@example.com"})
  (testing "404 for unknown ids"
    (is (= 404 (:status (call :get "/api/persons/999"))))
    (is (= 404 (:status (call :put "/api/persons/999" {:name "X"}))))
    (is (= 404 (:status (call :get "/api/persons/abc")))))
  (testing "400 for invalid input"
    (is (= {:status 400 :body {:error "name is required"}}
           (select-keys (call :post "/api/persons" {:email "x@example.com"}) [:status :body])))
    (is (= 400 (:status (call :post "/api/persons" {:name "   "}))))
    (is (= 400 (:status (call :post "/api/persons" {:name 42}))))
    (is (= 400 (:status (call :post "/api/persons" "{not json"))))
    (is (= 400 (:status (call :post "/api/persons" "[1,2]"))))
    (is (= 400 (:status (call :post "/api/persons" ""))))
    (is (= 400 (:status (call :put "/api/persons/1" {})))))
  (testing "409 for duplicates"
    (let [r (call :post "/api/persons" {:name "A"})]
      (is (= 409 (:status r)))
      (is (string? (get-in r [:body :error]))))
    (is (= 409 (:status (call :post "/api/persons" {:name "B" :email "a@example.com"}))))))

;; ---------------------------------------------------------------------------
;; Metric types

(deftest metric-types-crud
  (let [m (create! "/api/metric-types" {:type "BSL" :description "Blood Sugar Level"})
        url (str "/api/metric-types/" (:id m))]
    (is (= {:type "BSL" :description "Blood Sugar Level"} (dissoc m :id)))
    (is (= [m] (:body (call :get "/api/metric-types"))))
    (is (= m (:body (call :get url))))
    (is (= (assoc m :description "Glucose")
           (:body (call :put url {:description "Glucose"}))))
    (is (= 204 (:status (call :delete url))))
    (is (= 404 (:status (call :get url))))))

(deftest metric-types-errors
  (create! "/api/metric-types" {:type "BSL" :description "Blood Sugar Level"})
  (is (= 400 (:status (call :post "/api/metric-types" {:type "X"}))))
  (is (= 400 (:status (call :post "/api/metric-types" {:description "X"}))))
  (is (= 409 (:status (call :post "/api/metric-types" {:type "BSL" :description "Other"}))))
  (is (= 404 (:status (call :put "/api/metric-types/999" {:type "X"}))))
  (is (= 404 (:status (call :delete "/api/metric-types/999")))))

;; ---------------------------------------------------------------------------
;; Health details

(deftest health-details-crud
  (let [{:keys [person bsl hba1c]} (seed)
        h (create! "/api/health-details" {:person_id (:id person)
                                          :metric_type_id (:id bsl)
                                          :health_metric 5.6})
        url (str "/api/health-details/" (:id h))]
    (testing "create"
      (is (= (:id person) (:person_id h)))
      (is (= (:id bsl) (:metric_type_id h)))
      (is (== 5.6 (:health_metric h)))
      (is (string? (:created_date_time h))))

    (testing "read"
      (is (= h (:body (call :get url))))
      (is (= [h] (:body (call :get "/api/health-details")))))

    (testing "update value, metric type and timestamp"
      (let [u (:body (call :put url {:health_metric 7 :metric_type_id (:id hba1c)
                                     :created_date_time "2026-03-04 05:06:07"}))]
        (is (== 7 (:health_metric u)))
        (is (= (:id hba1c) (:metric_type_id u)))
        (is (= "2026-03-04 05:06:07" (:created_date_time u)))))

    (testing "delete"
      (is (= 204 (:status (call :delete url))))
      (is (= 404 (:status (call :get url)))))))

(deftest health-details-errors
  (let [{:keys [person bsl]} (seed)
        valid {:person_id (:id person) :metric_type_id (:id bsl) :health_metric 5.0}]
    (testing "400 for missing or mistyped fields"
      (is (= 400 (:status (call :post "/api/health-details" (dissoc valid :health_metric)))))
      (is (= 400 (:status (call :post "/api/health-details" (dissoc valid :person_id)))))
      (is (= 400 (:status (call :post "/api/health-details" (assoc valid :health_metric "high")))))
      (is (= 400 (:status (call :post "/api/health-details" (assoc valid :person_id -1)))))
      (is (= 400 (:status (call :post "/api/health-details" (assoc valid :created_date_time 5))))))
    (testing "409 when referenced rows do not exist"
      (is (= 409 (:status (call :post "/api/health-details" (assoc valid :person_id 999)))))
      (is (= 409 (:status (call :post "/api/health-details" (assoc valid :metric_type_id 999))))))
    (testing "409 when deleting a person or metric type that has readings"
      (create! "/api/health-details" valid)
      (is (= 409 (:status (call :delete (str "/api/persons/" (:id person))))))
      (is (= 409 (:status (call :delete (str "/api/metric-types/" (:id bsl)))))))
    (testing "404 for unknown reading"
      (is (= 404 (:status (call :put "/api/health-details/999" {:health_metric 1})))))))

(deftest person-health-details
  (let [{:keys [person bsl hba1c]} (seed)
        other (create! "/api/persons" {:name "Other"})
        add! (fn [p m v ts]
               (create! "/api/health-details" {:person_id (:id p) :metric_type_id (:id m)
                                               :health_metric v :created_date_time ts}))
        r2 (add! person bsl 6.0 "2026-01-02 08:00:00")
        r1 (add! person bsl 5.0 "2026-01-01 08:00:00")
        r3 (add! person hba1c 48.0 "2026-01-03 08:00:00")
        _  (add! other bsl 9.9 "2026-01-01 09:00:00")
        url (str "/api/persons/" (:id person) "/health-details")]
    (is (= [r1 r2 r3] (:body (call :get url))))
    (is (= [r1 r2] (:body (call :get (str url "?metric_type_id=" (:id bsl))))))
    (is (= [] (:body (call :get (str "/api/persons/" (:id other) "/health-details?metric_type_id=" (:id hba1c))))))
    (is (= 400 (:status (call :get (str url "?metric_type_id=abc")))))
    (is (= 404 (:status (call :get "/api/persons/999/health-details"))))))

;; ---------------------------------------------------------------------------
;; Static front end

(deftest serves-front-end
  (testing "index page"
    (let [{:keys [status headers body]} (*app* (mock/request :get "/"))]
      (is (= 200 status))
      (is (str/starts-with? (get headers "Content-Type") "text/html"))
      (is (str/includes? (slurp body) "Track My Sugar"))))
  (testing "assets"
    (is (= 200 (:status (*app* (mock/request :get "/app.js")))))
    (is (= 200 (:status (*app* (mock/request :get "/style.css"))))))
  (testing "unknown API path is a JSON 404"
    (let [r (call :get "/api/nope")]
      (is (= 404 (:status r)))
      (is (= {:error "Not found"} (:body r))))))

;; ---------------------------------------------------------------------------
;; Security

(defn- raw
  "Like `call` but returns the raw response, for header checks."
  [req] (*app* req))

(deftest rejects-untrusted-host-header
  (testing "DNS rebinding: a request with a foreign Host is refused"
    (let [r (raw (mock/header (mock/request :get "/api/persons") "Host" "evil.example.com"))]
      (is (= 403 (:status r)))
      (is (= "{\"error\":\"Host not allowed\"}" (:body r)))))
  (testing "loopback names are accepted, with or without a port"
    (doseq [h ["localhost" "localhost:3000" "127.0.0.1:8080" "[::1]:3000" "LOCALHOST"]]
      (is (= 200 (:status (raw (mock/header (mock/request :get "/api/persons") "Host" h))))
          h)))
  (testing "extra hosts can be allowed explicitly"
    (let [app (api/app (db/datasource (.getPath (doto (File/createTempFile "tms_host" ".db") .deleteOnExit)))
                       #{"tms.lan"})]
      (is (= 403 (:status (app (mock/header (mock/request :get "/api/persons") "Host" "localhost")))))
      (is (not= 403 (:status (app (mock/header (mock/request :get "/") "Host" "tms.lan"))))))))

(deftest requires-json-content-type-on-writes
  (testing "a cross-site HTML form post cannot create or change data"
    (doseq [ct ["application/x-www-form-urlencoded" "text/plain" "multipart/form-data; boundary=x"]]
      (let [req (-> (mock/request :post "/api/persons")
                    (mock/body "name=Mallory")
                    (mock/content-type ct))]
        (is (= 415 (:status (raw req))) ct)))
    (is (= 415 (:status (raw (-> (mock/request :put "/api/persons/1") (mock/body "{}"))))))
    (is (= [] (:body (call :get "/api/persons")))))
  (testing "JSON with a charset is accepted"
    (is (= 201 (:status (raw (-> (mock/request :post "/api/persons")
                                 (mock/body "{\"name\":\"Ok\"}")
                                 (mock/content-type "application/json; charset=utf-8"))))))))

(deftest sends-security-headers
  (doseq [path ["/" "/api/persons" "/app.js" "/api/nope"]]
    (let [h (:headers (raw (mock/request :get path)))]
      (is (= "nosniff" (get h "X-Content-Type-Options")) path)
      (is (= "DENY" (get h "X-Frame-Options")) path)
      (is (= "no-referrer" (get h "Referrer-Policy")) path)
      (is (str/includes? (get h "Content-Security-Policy") "default-src 'self'") path)
      (is (str/includes? (get h "Content-Security-Policy") "frame-ancestors 'none'") path)
      (is (not (str/includes? (get h "Content-Security-Policy") "unsafe")) path)))
  (testing "API responses are not cacheable"
    (is (= "no-store" (get-in (raw (mock/request :get "/api/persons")) [:headers "Cache-Control"])))))

(deftest rejects-oversized-bodies
  (let [big (str "{\"name\":\"" (apply str (repeat 70000 "a")) "\"}")
        r   (call :post "/api/persons" big)]
    (is (= 413 (:status r)))
    (is (= [] (:body (call :get "/api/persons"))))))

(deftest enforces-field-limits-and-formats
  (testing "text length limits"
    (is (= 400 (:status (call :post "/api/persons" {:name (apply str (repeat 201 "a"))}))))
    (is (= 201 (:status (call :post "/api/persons" {:name (apply str (repeat 200 "a"))}))))
    (is (= 400 (:status (call :post "/api/persons" {:name "B" :email (apply str (repeat 255 "a"))}))))
    (is (= 400 (:status (call :post "/api/metric-types" {:type (apply str (repeat 51 "T")) :description "d"}))))
    (is (= 400 (:status (call :post "/api/metric-types" {:type "T" :description (apply str (repeat 501 "d"))})))))
  (testing "timestamps must be YYYY-MM-DD HH:MM:SS"
    (let [{:keys [person bsl]} (seed)
          valid {:person_id (:id person) :metric_type_id (:id bsl) :health_metric 5.0}]
      (doseq [bad ["yesterday" "2026-01-02" "2026-01-02T08:30:00" "2026-1-2 8:30:00" "2026-01-02 08:30:00; DROP TABLE PERSON"]]
        (is (= 400 (:status (call :post "/api/health-details" (assoc valid :created_date_time bad)))) bad))
      (is (= 201 (:status (call :post "/api/health-details" (assoc valid :created_date_time "2026-01-02 08:30:00")))))
      (testing "value range"
        (is (= 400 (:status (call :post "/api/health-details" (assoc valid :health_metric 1e12)))))
        (is (= 201 (:status (call :post "/api/health-details" (assoc valid :health_metric -3.5)))))))))

(deftest hostile-input-is-handled-safely
  (testing "ids that overflow a long are a 404, not a crash"
    (is (= 404 (:status (call :get "/api/persons/99999999999999999999"))))
    (is (= 404 (:status (call :delete "/api/health-details/99999999999999999999")))))
  (testing "SQL metacharacters are stored as plain text"
    (let [name "Robert'); DROP TABLE PERSON;--"
          p    (create! "/api/persons" {:name name})]
      (is (= name (:name p)))
      (is (= name (:name (first (:body (call :get "/api/persons"))))))))
  (testing "unknown fields cannot be mass-assigned"
    (let [p (create! "/api/persons" {:name "Mass" :id 999 :admin true})]
      (is (not= 999 (:id p)))
      (is (not (contains? p :admin)))))
  (testing "one person's id cannot be used to read or change another's readings"
    (let [{:keys [person bsl]} (seed)
          other (create! "/api/persons" {:name "Other"})
          h (create! "/api/health-details" {:person_id (:id person) :metric_type_id (:id bsl) :health_metric 5.0})]
      (is (= [] (:body (call :get (str "/api/persons/" (:id other) "/health-details")))))
      (is (= [h] (:body (call :get (str "/api/persons/" (:id person) "/health-details"))))))))
