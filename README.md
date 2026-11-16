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

- **Java** 17 or newer (`java -version`)
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

The server listens on `127.0.0.1` only and accepts only `localhost`,
`127.0.0.1` and `[::1]` as the `Host` header (see [Security](#security)). To use
it from another device set `TMS_HOST` and `TMS_ALLOWED_HOSTS`, for example
`TMS_HOST=0.0.0.0 TMS_ALLOWED_HOSTS=myhost.lan clojure -M:run`.

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
  metric type, a value (decimals such as 7.3 are fine) and a date and time that
  defaults to now; if you clear the date, the current time is used. You can filter the list by person and
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

## Security

The app has **no accounts or login**: it is a single-user tool, so every record
belongs to whoever runs it. That means there is no per-user data to protect
from other users (the classic IDOR, where one user reads another's record by
changing an id), but also that *anyone who can reach the port sees everything*.
The defences follow from that:

| Risk (OWASP) | What the app does |
|---|---|
| Broken access control, exposure | Listens on loopback only by default. Binding elsewhere needs `TMS_HOST` and prints a warning; put it behind a VPN or an authenticating proxy. IDs in URLs only ever address the one shared dataset, and `/persons/:id/health-details` returns only that person's readings. |
| DNS rebinding | The `Host` header must be an allowed name (403 otherwise). |
| CSRF | `POST` and `PUT` need `Content-Type: application/json` (415 otherwise). A cross-site form can't send it, and a cross-site JSON request needs a CORS preflight the server never grants. |
| Injection (SQL) | Every query is parameterised; table and column names come from code, never from requests. Unknown request fields are ignored, so ids can't be mass-assigned. |
| Injection (XSS) | The front end escapes every value it puts in HTML. A Content-Security-Policy (header and `<meta>`) allows only the app's own scripts and styles, no inline script, no remote origins and no framing. |
| Insecure design / DoS | Request bodies over 64 KB get 413, text fields have length limits, readings are numbers within ±1e9 and timestamps must be `YYYY-MM-DD HH:MM:SS`. Jetty uses a small thread pool, a header size limit and doesn't advertise its version. |
| Security misconfiguration | `nosniff`, `X-Frame-Options`, `Referrer-Policy`, `Cross-Origin-Resource-Policy` and `Permissions-Policy` headers; API responses are `no-store`; unexpected errors return a generic 500 and are logged, never sent to the client. |
| Vulnerable components | Dependencies were updated to current releases (Ring/Jetty, sqlite-jdbc, next.jdbc and others). CI runs with read-only token permissions. |
| CSV injection | Exported cells starting with `=`, `+`, `-` or `@` get a leading apostrophe. |

**Android app:** the page loads only from the app's own assets and every other
URL is blocked. The app has no `INTERNET` permission. Backups are off
(`allowBackup="false"`) so readings don't go to cloud backup, and the database
is in the app's private storage. The native bridge only saves CSV or JSON files
to Downloads, with a sanitised name and a size limit. Release builds are
minified with R8.

Not covered: encryption of the SQLite file at rest (rely on device or disk
encryption), and authentication for multi-user use. If you need either, say so;
both are real design changes.

## Upgrading without losing data

**Android.** Installing a newer version over an old one (`adb install -r`, or
opening a newer APK) keeps the app's data; the readings live in the app's
private storage, outside the APK. On first launch the new version upgrades the
database in place (`Database.onUpgrade`): each step only adds or converts, runs
in one transaction (a failure rolls back), and an unknown step fails loudly
instead of being skipped. `UpgradeTest` builds a version 1 database with
readings, opens it with the current code and checks every reading survives; I
also installed the original 1.0 build, added readings, installed the current one
over it and confirmed the same rows and the new indexes.

Things that *do* lose the data, and how to avoid them:

- **Uninstalling.** Android deletes the data with the app. The app sets
  `hasFragileUserData`, so Android offers to keep the data when you uninstall.
- **A different signing key.** Android refuses to update an app signed with a
  different key, and the only way round it is to uninstall first. Debug builds
  are signed with `~/.android/debug.keystore` and release builds with your own
  keystore. Keep a backup of whichever key you use, and always build updates
  with the same one.
- **Backups are off** (`allowBackup="false"`, for privacy), so data is not
  restored to a new phone. Use the CSV or JSON export on the Analytics tab to
  keep a copy.
- **Running the instrumented tests on a phone.** Gradle uninstalls the app after
  them. The tests use their own database (never the real one) and the Gradle
  build refuses to run them unless `ANDROID_SERIAL` names an emulator:
  `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`.

**Server.** The database is a plain SQLite file outside the jar (`db/tms_db` by
default), so replacing the jar never touches it. On start the server upgrades an
older file in place (`db/migrate!`, using SQLite's `user_version`), after
copying it to `<file>.bak-v<old version>`. A file written by a *newer* version is
refused instead of opened, and the startup line prints the database's absolute
path, so a different working directory can't silently pick an empty database.
To add a schema change: raise `schema-version` (and `DB_VERSION` in `Database.kt`),
add a step to `migrations` and the matching branch in `Database.migrate`, and
update `schema.sql` for new installs.

## Performance and memory

- `HEALTH_DETAIL` has indexes on person, metric type and date (`schema.sql`).
  The server adds them to an existing database on startup, and the Android
  database upgrades itself.
- The readings table shows the newest 500 rows (filter to see others).
- The analytics graph reads the readings only while that tab is open and
  releases them when you leave it. With more than 500 readings the point
  markers are dropped and only the lines are drawn, so the page stays light.
  Names are looked up in maps rather than by scanning lists.
- The Android app closes its WebView and database when it exits, and release
  builds are shrunk with R8.

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

This runs all three test namespaces:

- `track-my-sugar.db-test` tests the CRUD functions for each table: required
  fields, unique and foreign-key constraints, ordering and filtering.
- `track-my-sugar.api-test` tests every REST endpoint, including status codes,
  JSON bodies, validation errors, `404` and `409` handling, serving the front
  end, and the security checks (Host header, content type, headers, size and
  format limits, hostile input).
- `track-my-sugar.server-test` starts the real Jetty server and checks the
  loopback binding and the `Host` check over a socket.

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

### Version

The version lives in one file, `resources/public/version.txt` (`MAJOR.MINOR.PATCH`).
The web page shows it next to the title, the Android `versionName` is that
text, and `versionCode` is `MAJOR*10000 + MINOR*100 + PATCH`. The Android launch
screen shows it too, but as part of an image, so after changing the version run
`python3 android/branding/make_assets.py`. The Android build fails with that
reminder if you forget, and a Clojure test checks it as well.

### Icon and launch screen

The launcher icon is a ball-and-stick glucose molecule above "TMS" on the app's
green gradient (an adaptive icon, with a single-colour layer for Android 13+
themed icons). The launch screen shows the same molecule and "TMS" on green, and
on Android 12+ the "TMS - TrackMySugar" wordmark underneath. It stays up until
the page has loaded, so there is no white flash. The app is named
"TrackMySugar" on the home screen (the full name doesn't fit under an icon).

The artwork is generated, not hand-drawn: `android/branding/make_assets.py`
writes the SVG sources and the PNGs in `res/`. Run `python3 make_assets.py`
there (needs `rsvg-convert` and the Noto Sans Bold font) to change it. The PNGs
are committed, so building the app doesn't need the script.

### Android tests

The tests run on a device or emulator (start one first, for example an AVD
with an Android 35 image):

```sh
cd android
./gradlew connectedDebugAndroidTest
```

- `ApiTest` is a port of `api_test.clj` and checks the Kotlin API against
  on-device SQLite.
- `BrandingTest` checks the app name, adaptive icon and launch-screen theme.
- `UiTest` drives the real front end inside the app's WebView: tabs, adding,
  editing and deleting everything, the analytics graph and filters, and the CSV
  and JSON exports. It reads the exported files back from Downloads.

The Clojure tests (`clojure -M:test`) don't cover the front end. The browser
download path (a `Blob` link) is only used outside the app and has no automated
test.
