package com.trackmysugar

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Kotlin port of api.clj: the same /api routes, validation, status codes and
 * JSON shapes, handled in-process instead of over HTTP.
 */
class Api(private val db: SQLiteDatabase) {

    class Response(val status: Int, val body: String) {
        fun toJson(): String = JSONObject().put("status", status).put("body", body).toString()
    }

    private class HttpError(val status: Int, message: String) : Exception(message)

    private class Field(val type: Type, val description: String, val required: Boolean) {
        enum class Type { NON_BLANK_STRING, NULLABLE_STRING, POSITIVE_INT, NUMBER }
    }

    private class Table(val name: String, val fields: Map<String, Field>)

    private val persons = Table(
        "PERSON", linkedMapOf(
            "name" to Field(Field.Type.NON_BLANK_STRING, "a non-empty string", true),
            "email" to Field(Field.Type.NULLABLE_STRING, "a string or null", false),
        )
    )
    private val metricTypes = Table(
        "METRIC_TYPE", linkedMapOf(
            "type" to Field(Field.Type.NON_BLANK_STRING, "a non-empty string", true),
            "description" to Field(Field.Type.NON_BLANK_STRING, "a non-empty string", true),
        )
    )
    private val healthDetails = Table(
        "HEALTH_DETAIL", linkedMapOf(
            "person_id" to Field(Field.Type.POSITIVE_INT, "a positive integer", true),
            "metric_type_id" to Field(Field.Type.POSITIVE_INT, "a positive integer", true),
            "health_metric" to Field(Field.Type.NUMBER, "a number", true),
            "created_date_time" to Field(
                Field.Type.NON_BLANK_STRING,
                "a string such as \"2026-01-02 08:30:00\"", false
            ),
        )
    )

    private val tables = mapOf(
        "persons" to persons,
        "metric-types" to metricTypes,
        "health-details" to healthDetails,
    )

    private val collectionRoute = Regex("^/([a-z-]+)$")
    private val itemRoute = Regex("^/([a-z-]+)/([0-9]+)$")
    private val personReadingsRoute = Regex("^/persons/([0-9]+)/health-details$")

    @Synchronized
    fun handle(method: String, target: String, body: String?): Response =
        try {
            route(method.uppercase(), target, body)
        } catch (e: HttpError) {
            error(e.status, e.message ?: "")
        } catch (e: SQLiteConstraintException) {
            error(409, constraintMessage(e.message ?: ""))
        } catch (e: Exception) {
            error(500, "Database error")
        }

    // -----------------------------------------------------------------------
    // Routing

    private fun route(method: String, target: String, body: String?): Response {
        val path = target.substringBefore('?').removePrefix("/api").trimEnd('/')
        val query = parseQuery(target.substringAfter('?', ""))

        personReadingsRoute.matchEntire(path)?.let { m ->
            if (method != "GET") return notFound()
            val id = m.groupValues[1].toLong()
            if (get(persons, id) == null) return notFound()
            val metric = query["metric_type_id"]
            val metricId = metric?.toLongOrNull()?.takeIf { it > 0 }
            if (metric != null && metricId == null) {
                throw HttpError(400, "metric_type_id must be a positive integer")
            }
            val sql = "SELECT * FROM HEALTH_DETAIL WHERE person_id = ?" +
                (if (metricId != null) " AND metric_type_id = ?" else "") +
                " ORDER BY created_date_time, id"
            val args = listOfNotNull(id.toString(), metricId?.toString()).toTypedArray()
            return ok(rows(db.rawQuery(sql, args)))
        }

        collectionRoute.matchEntire(path)?.let { m ->
            val table = tables[m.groupValues[1]] ?: return notFound()
            return when (method) {
                "GET" -> ok(rows(db.rawQuery("SELECT * FROM ${table.name} ORDER BY id", null)))
                "POST" -> created(create(table, readJson(body)))
                else -> notFound()
            }
        }

        itemRoute.matchEntire(path)?.let { m ->
            val table = tables[m.groupValues[1]] ?: return notFound()
            val id = m.groupValues[2].toLong()
            return when (method) {
                "GET" -> okOrNotFound(get(table, id))
                "PUT" -> okOrNotFound(update(table, id, readJson(body)))
                "DELETE" ->
                    if (db.delete(table.name, "id = ?", arrayOf(id.toString())) > 0)
                        Response(204, "") else notFound()
                else -> notFound()
            }
        }

        return notFound()
    }

    // -----------------------------------------------------------------------
    // CRUD

    private fun get(table: Table, id: Long): JSONObject? =
        rows(db.rawQuery("SELECT * FROM ${table.name} WHERE id = ?", arrayOf(id.toString())))
            .optJSONObject(0)

    private fun create(table: Table, data: JSONObject): JSONObject {
        val values = validate(table, data, creating = true)
        val id = db.insertOrThrow(table.name, null, values)
        return get(table, id)!!
    }

    private fun update(table: Table, id: Long, data: JSONObject): JSONObject? {
        val values = validate(table, data, creating = false)
        if (db.update(table.name, values, "id = ?", arrayOf(id.toString())) == 0) return null
        return get(table, id)
    }

    // -----------------------------------------------------------------------
    // Validation

    private fun readJson(body: String?): JSONObject {
        if (body.isNullOrBlank()) throw HttpError(400, "Request body must be a JSON object")
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw HttpError(400, "Request body is not valid JSON")
        }
    }

    /**
     * Mirrors `validate` in api.clj: on create every required field must be
     * present; on update at least one field is needed. Returns the known
     * fields as ContentValues. A null value is only stored where it is valid.
     */
    private fun validate(table: Table, data: JSONObject, creating: Boolean): ContentValues {
        val present = table.fields.keys.filter { data.has(it) }
        if (!creating && present.isEmpty()) {
            throw HttpError(400, "Supply at least one of: ${table.fields.keys.joinToString(", ")}")
        }
        val values = ContentValues()
        for ((key, field) in table.fields) {
            val missing = !data.has(key) || data.isNull(key)
            if (creating && field.required && missing) throw HttpError(400, "$key is required")
            if (!data.has(key)) continue
            val v = if (data.isNull(key)) null else data.get(key)
            when (field.type) {
                Field.Type.NON_BLANK_STRING ->
                    if (v is String && v.isNotBlank()) values.put(key, v) else bad(key, field)
                Field.Type.NULLABLE_STRING ->
                    when (v) {
                        null -> values.putNull(key)
                        is String -> values.put(key, v)
                        else -> bad(key, field)
                    }
                Field.Type.POSITIVE_INT ->
                    if ((v is Int || v is Long) && (v as Number).toLong() > 0) values.put(key, v.toLong())
                    else bad(key, field)
                Field.Type.NUMBER ->
                    if (v is Number) values.put(key, v.toDouble()) else bad(key, field)
            }
        }
        return values
    }

    private fun bad(key: String, field: Field): Nothing =
        throw HttpError(400, "$key must be ${field.description}")

    // -----------------------------------------------------------------------
    // Helpers

    private fun rows(cursor: Cursor): JSONArray {
        val out = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val row = JSONObject()
                for (i in 0 until it.columnCount) {
                    val name = it.getColumnName(i).lowercase()
                    when (it.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, it.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, it.getDouble(i))
                        else -> row.put(name, it.getString(i))
                    }
                }
                out.put(row)
            }
        }
        return out
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun constraintMessage(msg: String): String = when {
        "UNIQUE" in msg -> "A record with that value already exists"
        "FOREIGN KEY" in msg || "FOREIGNKEY" in msg ->
            "Referenced record does not exist, or record is still in use"
        "NOT NULL" in msg -> "A required value is missing"
        else -> msg
    }

    private fun ok(body: Any) = Response(200, body.toString())
    private fun created(body: Any) = Response(201, body.toString())
    private fun error(status: Int, message: String) =
        Response(status, JSONObject().put("error", message).toString())
    private fun notFound() = error(404, "Not found")
    private fun okOrNotFound(row: JSONObject?) = if (row != null) ok(row) else notFound()
}
