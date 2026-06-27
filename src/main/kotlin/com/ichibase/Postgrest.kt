package com.ichibase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Entry point for the database REST API (PostgREST). Get one via `ichi.from(table)`. */
public class Postgrest internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
) {
    public fun from(table: String): PostgrestQueryBuilder =
        PostgrestQueryBuilder(baseUrl, key, requester, table)

    /** Call a Postgres function (RPC). Pass [count] to also get a total. */
    public suspend fun rpc(
        fn: String,
        args: Any? = emptyMap<String, Any?>(),
        schema: String? = null,
        count: String? = null,
    ): IchibaseResponse<JsonElement> {
        val headers = HashMap<String, String>()
        if (count != null) headers["Prefer"] = "count=$count"
        if (schema != null) headers["Content-Profile"] = schema
        val res = requester.send("POST", urlJoin(baseUrl, "/postgres/rpc/$fn"), key, anyToJson(args).encodeOrNull(), headers)
        return toResponse(res) { it }
    }
}

/**
 * A chainable PostgREST query. Call [execute] to run it. The resolved `data` is
 * a JSON array of rows, a single row for [single]/[maybeSingle], or
 * `{ rows, count }` when [count] is set. Bodies accept plain Kotlin
 * (`mapOf(...)`, `listOf(mapOf(...))`) or a [JsonElement].
 */
public class PostgrestQueryBuilder internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
    private val table: String,
) {
    private val filters = mutableListOf<String>()
    private var method = "GET"
    private var body: JsonElement? = null
    private var returnRepresentation = false
    private var wantSingle = false
    private var wantMaybeSingle = false
    private var countMode: String? = null
    private var onConflict: String? = null
    private var rangeFrom: Int? = null
    private var rangeTo: Int? = null
    private val extraHeaders = HashMap<String, String>()

    // ── verbs ────────────────────────────────────────────────────────────

    public fun select(columns: String = "*"): PostgrestQueryBuilder = apply {
        if (method != "GET" && method != "HEAD") returnRepresentation = true else method = "GET"
        filters.add("select=${encodeQueryComponent(columns)}")
    }

    public fun insert(rows: Any?, returning: Boolean = false): PostgrestQueryBuilder = apply {
        method = "POST"; body = anyToJson(rows); returnRepresentation = returning
    }

    public fun upsert(rows: Any?, onConflict: String? = null, returning: Boolean = false): PostgrestQueryBuilder = apply {
        method = "POST"; body = anyToJson(rows); this.onConflict = onConflict; returnRepresentation = returning
        extraHeaders["Prefer"] = "resolution=merge-duplicates"
    }

    public fun update(values: Any?, returning: Boolean = false): PostgrestQueryBuilder = apply {
        method = "PATCH"; body = anyToJson(values); returnRepresentation = returning
    }

    public fun delete(returning: Boolean = false): PostgrestQueryBuilder = apply {
        method = "DELETE"; returnRepresentation = returning
    }

    // ── filters ──────────────────────────────────────────────────────────

    public fun eq(col: String, value: Any?): PostgrestQueryBuilder = f(col, "eq", value)
    public fun neq(col: String, value: Any?): PostgrestQueryBuilder = f(col, "neq", value)
    public fun gt(col: String, value: Any?): PostgrestQueryBuilder = f(col, "gt", value)
    public fun gte(col: String, value: Any?): PostgrestQueryBuilder = f(col, "gte", value)
    public fun lt(col: String, value: Any?): PostgrestQueryBuilder = f(col, "lt", value)
    public fun lte(col: String, value: Any?): PostgrestQueryBuilder = f(col, "lte", value)
    public fun like(col: String, pattern: String): PostgrestQueryBuilder = f(col, "like", pattern)
    public fun ilike(col: String, pattern: String): PostgrestQueryBuilder = f(col, "ilike", pattern)
    public fun isFilter(col: String, value: Any?): PostgrestQueryBuilder = f(col, "is", value ?: "null")

    public fun isIn(col: String, values: List<Any?>): PostgrestQueryBuilder = apply {
        val encoded = values.joinToString(",") { valueToQueryString(it) }
        filters.add("${encodeQueryComponent(col)}=in.($encoded)")
    }

    /** Escape hatch for any PostgREST operator: `filter("age", "gte", 18)`. */
    public fun filter(col: String, op: String, value: Any?): PostgrestQueryBuilder = f(col, op, value)

    private fun f(col: String, op: String, value: Any?): PostgrestQueryBuilder = apply {
        filters.add("${encodeQueryComponent(col)}=$op.${encodeQueryComponent(valueToQueryString(value))}")
    }

    // ── modifiers ──────────────────────────────────────────────────────────

    public fun order(column: String, ascending: Boolean = true): PostgrestQueryBuilder = apply {
        filters.add("order=${encodeQueryComponent(column)}.${if (ascending) "asc" else "desc"}")
    }

    public fun limit(n: Int): PostgrestQueryBuilder = apply { filters.add("limit=$n") }

    public fun range(from: Int, to: Int): PostgrestQueryBuilder = apply { rangeFrom = from; rangeTo = to }

    /** Expect exactly one row; `data` is an object (not an array). */
    public fun single(): PostgrestQueryBuilder = apply { wantSingle = true }

    /** Return the first row or JSON null; never errors on empty. */
    public fun maybeSingle(): PostgrestQueryBuilder = apply { wantMaybeSingle = true }

    /** Ask for a total count: `data` becomes `{ rows: [...], count: n }`. */
    public fun count(mode: String = "exact"): PostgrestQueryBuilder = apply { countMode = mode }

    // ── execution ──────────────────────────────────────────────────────────

    public suspend fun execute(): IchibaseResponse<JsonElement> {
        val fs = ArrayList(filters)
        onConflict?.let { fs.add("on_conflict=${encodeQueryComponent(it)}") }
        val qs = if (fs.isEmpty()) "" else "?" + fs.joinToString("&")

        val headers = HashMap(extraHeaders)
        val prefer = ArrayList<String>()
        headers.remove("Prefer")?.let { prefer.add(it) }
        val isWrite = method != "GET" && method != "HEAD"
        if (isWrite) prefer.add(if (returnRepresentation) "return=representation" else "return=minimal")
        countMode?.let { prefer.add("count=$it") }
        if (prefer.isNotEmpty()) headers["Prefer"] = prefer.joinToString(",")
        if (wantSingle) headers["Accept"] = "application/vnd.pgrst.object+json"
        if (rangeFrom != null && rangeTo != null) {
            headers["Range-Unit"] = "items"
            headers["Range"] = "$rangeFrom-$rangeTo"
        }

        val res = requester.send(method, urlJoin(baseUrl, "/postgres/$table$qs"), key, body.encodeOrNull(), headers)

        countMode?.let {
            val total = res.headers["content-range"]?.substringAfterLast('/')
            val c = if (total != null && total != "*") total.toIntOrNull() ?: 0 else 0
            return toResponse(res) { b ->
                buildJsonObject {
                    put("rows", if (b is JsonNull) JsonArray(emptyList()) else b)
                    put("count", JsonPrimitive(c))
                }
            }
        }
        if (wantMaybeSingle) {
            return toResponse(res) { b -> (b as? JsonArray)?.firstOrNull() ?: b }
        }
        return toResponse(res) { it }
    }
}
