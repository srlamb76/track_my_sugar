package com.trackmysugar

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Installing a newer version over an older one must not lose readings. Android
 * keeps the app's data folder across an update, so what matters is that
 * [Database] upgrades an existing database file in place.
 */
class UpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "tms_upgrade_test.db"

    @Before
    fun setUp() = clean()

    @After
    fun tearDown() = clean()

    private fun clean() {
        context.deleteDatabase(name)
    }

    /** A database exactly as version 1 of the app left it: no indexes, user_version 1, real data. */
    private fun createVersion1Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.use {
            it.execSQL("CREATE TABLE PERSON (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, email TEXT UNIQUE)")
            it.execSQL("CREATE TABLE METRIC_TYPE (id INTEGER PRIMARY KEY, type TEXT NOT NULL UNIQUE, description TEXT NOT NULL UNIQUE)")
            it.execSQL(
                "CREATE TABLE HEALTH_DETAIL (id INTEGER PRIMARY KEY, person_id INTEGER REFERENCES PERSON(id), " +
                    "health_metric REAL NOT NULL, metric_type_id INTEGER NOT NULL REFERENCES METRIC_TYPE(id), " +
                    "created_date_time NUMERIC NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            )
            it.execSQL("INSERT INTO PERSON (name, email) VALUES ('Steve', 's@x.com'), ('Jo', NULL)")
            it.execSQL("INSERT INTO METRIC_TYPE (type, description) VALUES ('BSL', 'Blood Sugar Level')")
            it.execSQL(
                "INSERT INTO HEALTH_DETAIL (person_id, health_metric, metric_type_id, created_date_time) VALUES " +
                    "(1, 5.6, 1, '2026-01-01 08:00:00'), (2, 7.25, 1, '2026-01-02 09:30:00')"
            )
            it.version = 1
        }
    }

    private fun indexes(db: SQLiteDatabase): Set<String> {
        val out = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index'", null).use {
            while (it.moveToNext()) out += it.getString(0)
        }
        return out
    }

    private fun readings(db: SQLiteDatabase): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery(
            "SELECT p.name, m.type, h.health_metric, h.created_date_time FROM HEALTH_DETAIL h " +
                "JOIN PERSON p ON p.id = h.person_id JOIN METRIC_TYPE m ON m.id = h.metric_type_id ORDER BY h.id", null
        ).use { while (it.moveToNext()) out += "${it.getString(0)}|${it.getString(1)}|${it.getDouble(2)}|${it.getString(3)}" }
        return out
    }

    @Test
    fun upgradeFromVersion1KeepsEveryReading() {
        createVersion1Database()
        val helper = Database(context, name)
        val db = helper.writableDatabase  // runs onUpgrade 1 -> current
        try {
            assertEquals(Database.DB_VERSION, db.version)
            assertEquals(
                listOf("Steve|BSL|5.6|2026-01-01 08:00:00", "Jo|BSL|7.25|2026-01-02 09:30:00"),
                readings(db)
            )
            assertTrue(indexes(db).containsAll(setOf("health_detail_person_idx", "health_detail_metric_idx", "health_detail_time_idx")))
            // The upgraded database still accepts new data and enforces its rules.
            db.execSQL("INSERT INTO PERSON (name) VALUES ('New')")
            assertEquals(3L, android.database.DatabaseUtils.queryNumEntries(db, "PERSON"))
        } finally {
            helper.close()
        }
    }

    @Test
    fun reopeningAnUpToDateDatabaseChangesNothing() {
        createVersion1Database()
        Database(context, name).also { it.writableDatabase; it.close() }
        val helper = Database(context, name)
        try {
            val db = helper.writableDatabase
            assertEquals(2L, android.database.DatabaseUtils.queryNumEntries(db, "HEALTH_DETAIL"))
            assertEquals(Database.DB_VERSION, db.version)
        } finally {
            helper.close()
        }
    }

    @Test
    fun aMissingMigrationStepFailsLoudlyInsteadOfSkipping() {
        createVersion1Database()
        val helper = Database(context, name)
        val db = helper.writableDatabase
        try {
            val e = runCatching { helper.onUpgrade(db, Database.DB_VERSION, Database.DB_VERSION + 1) }.exceptionOrNull()
            assertTrue("expected an error for an unknown version", e != null)
            assertTrue(e!!.message!!, e.message!!.contains("No migration"))
            assertEquals(2L, android.database.DatabaseUtils.queryNumEntries(db, "HEALTH_DETAIL"))
        } finally {
            helper.close()
        }
    }
}
