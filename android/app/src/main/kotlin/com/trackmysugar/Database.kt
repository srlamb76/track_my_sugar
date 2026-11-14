package com.trackmysugar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite database using the same schema as the Clojure server (schema/schema.sql). */
class Database(private val context: Context, name: String = DEFAULT_NAME) :
    SQLiteOpenHelper(context, name, null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val sql = context.assets.open("schema.sql").bufferedReader().use { it.readText() }
        sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val DEFAULT_NAME = "tms_db"
    }
}
