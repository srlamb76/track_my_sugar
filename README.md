# Track My Sugar

A small, self-contained app for recording blood sugar (and other health)
readings. It is a Clojure web server backed by SQLite, with a plain HTML5 and
vanilla JavaScript front end. The front end is bundled into the same jar and
has no external dependencies (no CDN, frameworks or build step).

```
track_my_sugar/
├── deps.edn                     Dependencies and aliases (:run, :test, :build)
├── build.clj                    Builds the standalone jar
├── db/tms_db                    The SQLite database
├── schema/schema.sql            Table definitions
├── schema/default_load.sql      Seed data
├── resources/public/            Front end (index.html, app.js, style.css)
├── src/track_my_sugar/
│   ├── db.clj                   CRUD data access layer (next.jdbc + SQLite)
│   ├── api.clj                  JSON REST API (Ring + Compojure)
│   └── server.clj               Entry point (embedded Jetty)
└── test/track_my_sugar/
    ├── db_test.clj              Data access layer tests
    └── api_test.clj             REST API tests
```

## Prerequisites

- **Java** 11 or newer (`java -version`)
- **Clojure CLI** (`clojure` / `clj`), see https://clojure.org/guides/install_clojure

The first command you run downloads dependencies into `~/.m2` and
`~/.gitlibs`, so it needs network access. You don't need to install SQLite
separately, because the JDBC driver bundles it. The `sqlite3` command-line
tool is only needed to create a new database by hand.

## Running the application

### From source (development)

```sh
clojure -M:run
```

Then open http://localhost:3000.

By default the server uses port `3000` and the database `db/tms_db`, relative
to the directory you start it from. To change either, pass arguments or set
environment variables:

```sh
clojure -M:run 8080 /path/to/other.db      # [port] [db-path]
PORT=8080 TMS_DB=/path/to/other.db clojure -M:run
```

Stop the server with `Ctrl+C`.

### As a standalone jar

Build the jar:

```sh
clojure -T:build uber
```

This writes `target/track-my-sugar.jar`, a single jar containing the server,
the front end and all dependencies. Run it with plain Java:

```sh
java -jar target/track-my-sugar.jar                 # port 3000, db/tms_db
java -jar target/track-my-sugar.jar 8080 /data/tms.db
```

The jar doesn't include the database. Point it at an existing SQLite file, or
create a new one as described below. To delete build output, run
`clojure -T:build clean`.

### Creating a new database

```sh
sqlite3 my.db < schema/schema.sql
sqlite3 my.db < schema/default_load.sql   # optional seed data
```

### Note on `.gitignore`

The `db/` directory is listed in `.gitignore`, so the default database
`db/tms_db` (which holds your personal readings) is never committed. After
cloning the repository, create it before first run:

```sh
mkdir -p db
sqlite3 db/tms_db < schema/schema.sql
sqlite3 db/tms_db < schema/default_load.sql   # optional seed data
```

Build output (`target/`) and tool caches (`.cpcache/`, `.lsp/`) are ignored too.

## Using the front end

The page has three tabs:

- **Readings:** add, edit or delete readings. Each reading has a person, a
  metric type, a value and an optional date and time; if you leave the date
  blank, the current time is used. You can filter the list by person and
  metric type.
- **Analytics:** a line graph of readings over time, with one line per person
  and metric type, a legend and a table of count, min, average, max and latest
  value. Filter by person, metric type and a from/to date range (inclusive).
  The graph is drawn as plain SVG, so it needs no charting library.
  **Download CSV** and **Download JSON** export the readings that match the
  current filters (see [Exporting readings](#exporting-readings)).
- **Administration:** two sub-sections, **People** and **Metric types**, to
  add, edit or delete people and metric types (for example `BSL`, Blood Sugar
  Level).

Errors from the API, such as a duplicate name or deleting a person who still
has readings, appear in a banner at the top of the page.

### Exporting readings

On the Analytics tab, **Download CSV** and **Download JSON** save the readings
that match the person, metric type and date filters, so what you export is what
the graph shows. If no readings match, nothing is saved and a message says so.
The file is named `track-my-sugar-readings-YYYY-MM-DD.csv` (or `.json`).

Readings are ordered oldest first and each has these fields:

| Field | Description |
|---|---|
| `id` | Reading id |
| `person_id`, `person` | Person id and name |
| `metric_type_id`, `metric_type` | Metric type id and short name (for example `BSL`) |
| `health_metric` | The value |
| `created_date_time` | `YYYY-MM-DD HH:MM:SS` |

- **CSV** has a header row, CRLF line endings (RFC 4180) and a UTF-8 byte order
  mark so Excel shows accented names correctly. Text that starts with `=`, `+`,
  `-` or `@` gets a leading apostrophe so a spreadsheet shows it as text and
  doesn't run it as a formula.
- **JSON** is an array of objects with the same fields.

In a browser this is a normal download. In the Android app the file is saved to
the phone's **Downloads** folder (a second export on the same day is stored as
`...(1).csv`).

## REST API

All endpoints are under `/api` and send and receive JSON. Field names match
the database columns.

| Method | Path | Description |
|---|---|---|
| GET    | `/api/persons` | List people |
| POST   | `/api/persons` | Create `{ "name": "...", "email": "..." }` (email optional) |
| GET    | `/api/persons/:id` | Get one person |
| PUT    | `/api/persons/:id` | Update any of `name`, `email` |
| DELETE | `/api/persons/:id` | Delete a person |
| GET    | `/api/persons/:id/health-details[?metric_type_id=N]` | A person's readings, oldest first |
| GET    | `/api/metric-types` | List metric types |
| POST   | `/api/metric-types` | Create `{ "type": "BSL", "description": "Blood Sugar Level" }` |
| GET    | `/api/metric-types/:id` | Get one metric type |
| PUT    | `/api/metric-types/:id` | Update any of `type`, `description` |
| DELETE | `/api/metric-types/:id` | Delete a metric type |
| GET    | `/api/health-details` | List all readings |
| POST   | `/api/health-details` | Create `{ "person_id": 1, "metric_type_id": 1, "health_metric": 5.6, "created_date_time": "2026-01-02 08:30:00" }` (`created_date_time` optional, defaults to now) |
| GET    | `/api/health-details/:id` | Get one reading |
| PUT    | `/api/health-details/:id` | Update any of the fields above |
| DELETE | `/api/health-details/:id` | Delete a reading |

Status codes:

| Code | Meaning |
|---|---|
| `200` | OK |
| `201` | Created; the body is the new row |
| `204` | Deleted |
| `400` | Invalid input |
| `404` | Not found |
| `409` | Constraint violation, such as a duplicate value, a missing referenced row, or a row still in use |

Error responses have the body `{ "error": "message" }`.

Example:

```sh
curl -X POST localhost:3000/api/health-details \
     -H 'Content-Type: application/json' \
     -d '{"person_id":1,"metric_type_id":1,"health_metric":5.8}'
```

## Using the data access layer from Clojure

```clojure
(require '[track-my-sugar.db :as db])

(def ds (db/datasource))                 ; or (db/datasource "path/to.db")
(db/create-person! ds {:name "Jo" :email "jo@example.com"})
(db/list-health-details-for-person ds 1)
```

Foreign-key enforcement is turned on for every connection.

## Running the unit tests

```sh
clojure -M:test
```

This runs both test namespaces:

- `track-my-sugar.db-test` tests the CRUD functions for each table: required
  fields, unique and foreign-key constraints, ordering and filtering.
- `track-my-sugar.api-test` tests every REST endpoint, including status codes,
  JSON bodies, validation errors, `404` and `409` handling, and serving the
  front end.

Each test builds a new temporary SQLite database from `schema/schema.sql` and
deletes it afterwards, so the tests never change `db/tms_db`. One test opens
`db/tms_db` read-only to check the connection works, so run the tests from
the project root.

To run a single namespace or a single test:

```sh
clojure -M:test -n track-my-sugar.api-test
clojure -M:test -v track-my-sugar.api-test/persons-crud
```

## Android app

`android/` is an Android (Kotlin) app that runs the same front end in a
WebView with a local SQLite database, so it works offline. It doesn't use the
Clojure server: `Api.kt` is a Kotlin port of `api.clj` with the same routes,
validation and status codes, and `app.js` calls it through a JavaScript bridge
when running in the app. `resources/public/` and `schema/` are shared with the
server, not copied.

Open `android/` in Android Studio and run it, or from a machine with the
Android SDK and Gradle installed:

```sh
cd android
gradle wrapper        # once, to generate ./gradlew
./gradlew assembleDebug
```

The app's data lives in its own private database, separate from `db/tms_db`.
It needs Android 10 (API 29) or newer, because exports are written to
Downloads with `MediaStore.Downloads`.

### Android tests

The tests run on a device or emulator (start one first, for example an AVD
with an Android 35 image):

```sh
cd android
./gradlew connectedDebugAndroidTest
```

- `ApiTest` is a port of `api_test.clj` and checks the Kotlin API against
  on-device SQLite.
- `UiTest` drives the real front end inside the app's WebView: tabs, adding,
  editing and deleting everything, the analytics graph and filters, and the CSV
  and JSON exports. It reads the exported files back from Downloads.

The Clojure tests (`clojure -M:test`) don't cover the front end. The browser
download path (a `Blob` link) is only used outside the app and has no automated
test.
