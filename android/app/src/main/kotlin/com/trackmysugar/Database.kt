package com.trackmysugar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite database using the same schema as the Clojure server (schema/schema.sql). */
class Database(private val context: Context, name: String = DEFAULT_NAME) :
    SQLiteOpenHelper(context, name, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    /** The statements of schema.sql, optionally only those starting with `prefix`. */
    private fun schemaStatements(prefix: String = ""): List<String> =
        context.assets.open("schema.sql").bufferedReader().use { it.readText() }
            .split(";").map { it.trim() }.filter { it.isNotEmpty() && it.startsWith(prefix) }

    override fun onCreate(db: SQLiteDatabase) {
        schemaStatements().forEach { db.execSQL(it) }
    }

    /**
     * Upgrades the user's existing database in place when a newer version of the
     * app is installed over an older one. Android's data folder survives an app
     * update, so this is what keeps the readings: each step may only add or
     * convert, never drop data. SQLiteOpenHelper runs the whole upgrade in one
     * transaction, so a failing step rolls back and leaves the data as it was.
     *
     * To change the schema: raise DB_VERSION, add the statements to schema.sql
     * for new installs, and add a `when` branch here for existing ones.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (version in oldVersion + 1..newVersion) migrate(db, version)
    }

    private fun migrate(db: SQLiteDatabase, toVersion: Int) {
        when (toVersion) {
            // 2: HEALTH_DETAIL indexes (all IF NOT EXISTS, so repeatable).
            2 -> schemaStatements("CREATE INDEX IF NOT EXISTS").forEach { db.execSQL(it) }
            // A missing step must be loud: skipping it could corrupt or lose data.
            else -> error("No migration to database version $toVersion")
        }
    }

    companion object {
        const val DEFAULT_NAME = "tms_db"

        /** Layout version, stored by SQLite itself (user_version). Matches schema-version in db.clj. */
        const val DB_VERSION = 2
    }
}
