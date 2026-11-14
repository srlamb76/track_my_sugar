package com.trackmysugar

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Kotlin port of test/track_my_sugar/api_test.clj, run against the on-device SQLite API. */
class ApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "tms_api_test.db"
    private lateinit var helper: Database
    private lateinit var api: Api

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        helper = Database(context, dbName)
        api = Api(helper.writableDatabase)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun call(method: String, path: String, body: Any? = null): Api.Response =
        api.handle(method, path, body?.toString())

    private fun obj(r: Api.Response) = JSONObject(r.body)
    private fun arr(r: Api.Response) = JSONArray(r.body)

    private fun create(path: String, body: JSONObject): JSONObject {
        val r = call("POST", path, body)
        assertEquals("create failed: ${r.body}", 201, r.status)
        return obj(r)
    }

    private fun j(vararg pairs: Pair<String, Any?>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    private fun status(method: String, path: String, body: Any? = null) = call(method, path, body).status

    // ---- Persons ---------------------------------------------------------

    @Test
    fun personsCrud() {
        assertEquals(0, arr(call("GET", "/api/persons")).length())
        val created = create("/api/persons", j("name" to "Steve", "email" to "s@example.com"))
        val id = created.getLong("id")
        assertTrue(id > 0)
        assertEquals("Steve", created.getString("name"))
        assertEquals("s@example.com", created.getString("email"))

        assertEquals(created.toString(), obj(call("GET", "/api/persons/$id")).toString())
        assertEquals(1, arr(call("GET", "/api/persons")).length())

        val updated = obj(call("PUT", "/api/persons/$id", j("email" to "new@example.com")))
        assertEquals("Steve", updated.getString("name"))
        assertEquals("new@example.com", updated.getString("email"))

        assertTrue(obj(call("PUT", "/api/persons/$id", j("email" to null))).isNull("email"))

        assertEquals(204, status("DELETE", "/api/persons/$id"))
        assertEquals(404, status("GET", "/api/persons/$id"))
        assertEquals(404, status("DELETE", "/api/persons/$id"))
    }

    @Test
    fun personsErrors() {
        create("/api/persons", j("name" to "A", "email" to "a@example.com"))
        assertEquals(404, status("GET", "/api/persons/999"))
        assertEquals(404, status("PUT", "/api/persons/999", j("name" to "X")))
        assertEquals(404, status("GET", "/api/persons/abc"))

        val missing = call("POST", "/api/persons", j("email" to "x@example.com"))
        assertEquals(400, missing.status)
        assertEquals("name is required", obj(missing).getString("error"))
        assertEquals(400, status("POST", "/api/persons", j("name" to "   ")))
        assertEquals(400, status("POST", "/api/persons", j("name" to 42)))
        assertEquals(400, status("POST", "/api/persons", "{not json"))
        assertEquals(400, status("POST", "/api/persons", "[1,2]"))
        assertEquals(400, status("POST", "/api/persons", ""))
        assertEquals(400, status("PUT", "/api/persons/1", j()))

        val dup = call("POST", "/api/persons", j("name" to "A"))
        assertEquals(409, dup.status)
        assertTrue(obj(dup).getString("error").isNotEmpty())
        assertEquals(409, status("POST", "/api/persons", j("name" to "B", "email" to "a@example.com")))
    }

    // ---- Metric types ----------------------------------------------------

    @Test
    fun metricTypesCrud() {
        val m = create("/api/metric-types", j("type" to "BSL", "description" to "Blood Sugar Level"))
        val url = "/api/metric-types/${m.getLong("id")}"
        assertEquals("BSL", m.getString("type"))
        assertEquals(m.toString(), arr(call("GET", "/api/metric-types")).getJSONObject(0).toString())
        assertEquals(m.toString(), obj(call("GET", url)).toString())
        assertEquals("Glucose", obj(call("PUT", url, j("description" to "Glucose"))).getString("description"))
        assertEquals(204, status("DELETE", url))
        assertEquals(404, status("GET", url))
    }

    @Test
    fun metricTypesErrors() {
        create("/api/metric-types", j("type" to "BSL", "description" to "Blood Sugar Level"))
        assertEquals(400, status("POST", "/api/metric-types", j("type" to "X")))
        assertEquals(400, status("POST", "/api/metric-types", j("description" to "X")))
        assertEquals(409, status("POST", "/api/metric-types", j("type" to "BSL", "description" to "Other")))
        assertEquals(404, status("PUT", "/api/metric-types/999", j("type" to "X")))
        assertEquals(404, status("DELETE", "/api/metric-types/999"))
    }

    // ---- Health details --------------------------------------------------

    private fun seed(): Triple<JSONObject, JSONObject, JSONObject> = Triple(
        create("/api/persons", j("name" to "Steve Lamb", "email" to "steve@example.com")),
        create("/api/metric-types", j("type" to "BSL", "description" to "Blood Sugar Level")),
        create("/api/metric-types", j("type" to "HBA1C", "description" to "Glycated haemoglobin")),
    )

    @Test
    fun healthDetailsCrud() {
        val (person, bsl, hba1c) = seed()
        val h = create(
            "/api/health-details",
            j("person_id" to person.getLong("id"), "metric_type_id" to bsl.getLong("id"), "health_metric" to 5.6)
        )
        val url = "/api/health-details/${h.getLong("id")}"
        assertEquals(person.getLong("id"), h.getLong("person_id"))
        assertEquals(5.6, h.getDouble("health_metric"), 0.0)
        assertTrue(h.getString("created_date_time").isNotEmpty())

        assertEquals(h.toString(), obj(call("GET", url)).toString())
        assertEquals(1, arr(call("GET", "/api/health-details")).length())

        val u = obj(
            call(
                "PUT", url, j(
                    "health_metric" to 7, "metric_type_id" to hba1c.getLong("id"),
                    "created_date_time" to "2026-03-04 05:06:07"
                )
            )
        )
        assertEquals(7.0, u.getDouble("health_metric"), 0.0)
        assertEquals(hba1c.getLong("id"), u.getLong("metric_type_id"))
        assertEquals("2026-03-04 05:06:07", u.getString("created_date_time"))

        assertEquals(204, status("DELETE", url))
        assertEquals(404, status("GET", url))
    }

    @Test
    fun healthDetailsErrors() {
        val (person, bsl, _) = seed()
        val valid = { j("person_id" to person.getLong("id"), "metric_type_id" to bsl.getLong("id"), "health_metric" to 5.0) }
        val path = "/api/health-details"

        assertEquals(400, status("POST", path, valid().apply { remove("health_metric") }))
        assertEquals(400, status("POST", path, valid().apply { remove("person_id") }))
        assertEquals(400, status("POST", path, valid().put("health_metric", "high")))
        assertEquals(400, status("POST", path, valid().put("person_id", -1)))
        assertEquals(400, status("POST", path, valid().put("created_date_time", 5)))

        assertEquals(409, status("POST", path, valid().put("person_id", 999)))
        assertEquals(409, status("POST", path, valid().put("metric_type_id", 999)))

        create(path, valid())
        assertEquals(409, status("DELETE", "/api/persons/${person.getLong("id")}"))
        assertEquals(409, status("DELETE", "/api/metric-types/${bsl.getLong("id")}"))
        assertEquals(404, status("PUT", "$path/999", j("health_metric" to 1)))
    }

    @Test
    fun personHealthDetails() {
        val (person, bsl, hba1c) = seed()
        val other = create("/api/persons", j("name" to "Other"))
        fun add(p: JSONObject, m: JSONObject, v: Double, ts: String) = create(
            "/api/health-details",
            j("person_id" to p.getLong("id"), "metric_type_id" to m.getLong("id"),
                "health_metric" to v, "created_date_time" to ts)
        ).getLong("id")

        val r2 = add(person, bsl, 6.0, "2026-01-02 08:00:00")
        val r1 = add(person, bsl, 5.0, "2026-01-01 08:00:00")
        val r3 = add(person, hba1c, 48.0, "2026-01-03 08:00:00")
        add(other, bsl, 9.9, "2026-01-01 09:00:00")
        val url = "/api/persons/${person.getLong("id")}/health-details"

        fun ids(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getLong("id") }
        assertEquals(listOf(r1, r2, r3), ids(arr(call("GET", url))))
        assertEquals(listOf(r1, r2), ids(arr(call("GET", "$url?metric_type_id=${bsl.getLong("id")}"))))
        assertEquals(
            0,
            arr(call("GET", "/api/persons/${other.getLong("id")}/health-details?metric_type_id=${hba1c.getLong("id")}")).length()
        )
        assertEquals(400, status("GET", "$url?metric_type_id=abc"))
        assertEquals(404, status("GET", "/api/persons/999/health-details"))
    }

    @Test
    fun unknownApiPathIsJson404() {
        val r = call("GET", "/api/nope")
        assertEquals(404, r.status)
        assertEquals("Not found", obj(r).getString("error"))
    }

    // ---- Security and input hardening (mirrors the Clojure API tests) --------

    @Test
    fun enforcesTextLengthLimits() {
        fun rep(n: Int) = "a".repeat(n)
        assertEquals(400, status("POST", "/api/persons", j("name" to rep(201))))
        assertEquals(201, status("POST", "/api/persons", j("name" to rep(200))))
        assertEquals(400, status("POST", "/api/persons", j("name" to "B", "email" to rep(255))))
        assertEquals(400, status("POST", "/api/metric-types", j("type" to rep(51), "description" to "d")))
        assertEquals(400, status("POST", "/api/metric-types", j("type" to "T", "description" to rep(501))))
    }

    @Test
    fun timestampsMustBeInTheStoredFormat() {
        val (person, bsl, _) = seed()
        val valid = { j("person_id" to person.getLong("id"), "metric_type_id" to bsl.getLong("id"), "health_metric" to 5.0) }
        for (bad in listOf("yesterday", "2026-01-02", "2026-01-02T08:30:00", "2026-1-2 8:30:00",
            "2026-01-02 08:30:00; DROP TABLE PERSON")) {
            assertEquals(bad, 400, status("POST", "/api/health-details", valid().put("created_date_time", bad)))
        }
        assertEquals(201, status("POST", "/api/health-details", valid().put("created_date_time", "2026-01-02 08:30:00")))
    }

    @Test
    fun readingValuesMustBeBounded() {
        val (person, bsl, _) = seed()
        val valid = { j("person_id" to person.getLong("id"), "metric_type_id" to bsl.getLong("id"), "health_metric" to 5.0) }
        assertEquals(400, status("POST", "/api/health-details", valid().put("health_metric", 1e12)))
        assertEquals(201, status("POST", "/api/health-details", valid().put("health_metric", -3.5)))
    }

    @Test
    fun oversizedBodiesAndHugeIdsAreRejectedSafely() {
        assertEquals(413, status("POST", "/api/persons", "{\"name\":\"" + "a".repeat(70000) + "\"}"))
        assertEquals(0, arr(call("GET", "/api/persons")).length())
        assertEquals(404, status("GET", "/api/persons/99999999999999999999"))
        assertEquals(404, status("DELETE", "/api/health-details/99999999999999999999"))
        assertEquals(404, status("GET", "/api/persons/99999999999999999999/health-details"))
        assertEquals(404, status("GET", "/api/" + "a".repeat(3000)))
    }

    @Test
    fun sqlMetacharactersAreStoredAsPlainText() {
        val name = "Robert'); DROP TABLE PERSON;--"
        assertEquals(name, create("/api/persons", j("name" to name)).getString("name"))
        assertEquals(name, arr(call("GET", "/api/persons")).getJSONObject(0).getString("name"))
        // The table survives.
        assertEquals(200, status("GET", "/api/persons"))
    }

    @Test
    fun unknownFieldsCannotBeMassAssigned() {
        val p = create("/api/persons", j("name" to "Mass", "id" to 999, "admin" to true))
        assertTrue(p.getLong("id") != 999L)
        assertTrue(!p.has("admin"))
    }

    @Test
    fun readingsAreScopedToTheirPerson() {
        val (person, bsl, _) = seed()
        val other = create("/api/persons", j("name" to "Other"))
        create("/api/health-details", j("person_id" to person.getLong("id"), "metric_type_id" to bsl.getLong("id"), "health_metric" to 5.0))
        assertEquals(0, arr(call("GET", "/api/persons/${other.getLong("id")}/health-details")).length())
        assertEquals(1, arr(call("GET", "/api/persons/${person.getLong("id")}/health-details")).length())
    }

    @Test
    fun databaseHasReadingIndexesAndUpgradeAddsThem() {
        fun indexes(): Set<String> {
            val out = mutableSetOf<String>()
            helper.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index'", null).use {
                while (it.moveToNext()) out += it.getString(0)
            }
            return out
        }
        val wanted = setOf("health_detail_person_idx", "health_detail_metric_idx", "health_detail_time_idx")
        assertTrue(indexes().containsAll(wanted))
        val db = helper.writableDatabase
        db.execSQL("DROP INDEX health_detail_time_idx")
        helper.onUpgrade(db, 1, 2)
        helper.onUpgrade(db, 1, 2)  // repeatable
        assertTrue(indexes().containsAll(wanted))
    }
}
