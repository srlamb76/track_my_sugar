package com.trackmysugar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite database using the same schema as the Clojure server (schema/schema.sql). */
class Database(private val context: Context, name: String = DEFAULT_NAME) :
    SQLiteOpenHelper(context, name, null, 2) {

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

    /** Version 2 added the HEALTH_DETAIL indexes; they are all `IF NOT EXISTS`, so this is repeatable. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) schemaStatements("CREATE INDEX IF NOT EXISTS").forEach { db.execSQL(it) }
    }

    companion object {
        const val DEFAULT_NAME = "tms_db"
    }
}
