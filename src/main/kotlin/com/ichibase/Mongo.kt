package com.ichibase

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/** MongoDB data client. The project key goes in the `apikey` header; the
 *  signed-in user's token (when present) goes in `Authorization: Bearer`, so
 *  your Mongo policy sees both. Get one via `ichi.mongo`. */
public class Mongo internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
    private val userToken: String? = null,
) {
    /** A Mongo client that acts as a specific end user. */
    public fun asUser(accessToken: String): Mongo = Mongo(baseUrl, key, requester, accessToken)

    public fun collection(name: String): MongoCollection =
        MongoCollection(baseUrl, key, requester, name, userToken)
}

/** Operations on one collection. Every call POSTs to `/mongo/v1/<op>/<name>`;
 *  reads/writes are gated by your Mongo policy. Filters/docs accept plain Kotlin
 *  (`mapOf(...)`) or a [JsonElement]. */
public class MongoCollection internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
    public val name: String,
    private val userToken: String?,
) {
    private suspend fun op(op: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): IchibaseResponse<JsonElement> {
        val body = buildJsonObject(build)
        val headers = HashMap<String, String>()
        headers["apikey"] = key
        val res = requester.send("POST", urlJoin(baseUrl, "/mongo/v1/$op/$name"), userToken, body.encodeOrNull(), headers)
        return toResponse(res) { it }
    }

    // ── reads ──────────────────────────────────────────────────────────────

    public suspend fun find(
        filter: Any?,
        projection: Any? = null,
        sort: Any? = null,
        limit: Int? = null,
        skip: Int? = null,
    ): IchibaseResponse<JsonElement> = op("find") {
        put("filter", anyToJson(filter))
        projection?.let { put("projection", anyToJson(it)) }
        sort?.let { put("sort", anyToJson(it)) }
        limit?.let { put("limit", anyToJson(it)) }
        skip?.let { put("skip", anyToJson(it)) }
    }

    public suspend fun findOne(filter: Any?, projection: Any? = null): IchibaseResponse<JsonElement> = op("findOne") {
        put("filter", anyToJson(filter))
        projection?.let { put("projection", anyToJson(it)) }
    }

    public suspend fun count(filter: Any?): IchibaseResponse<JsonElement> = op("count") { put("filter", anyToJson(filter)) }

    public suspend fun aggregate(pipeline: List<Any?>): IchibaseResponse<JsonElement> =
        op("aggregate") { put("pipeline", anyToJson(pipeline)) }

    public suspend fun distinct(field: String, filter: Any? = null): IchibaseResponse<JsonElement> = op("distinct") {
        put("field", anyToJson(field))
        filter?.let { put("filter", anyToJson(it)) }
    }

    // ── inserts ──────────────────────────────────────────────────────────────

    public suspend fun insertOne(doc: Any?): IchibaseResponse<JsonElement> = op("insertOne") { put("doc", anyToJson(doc)) }

    public suspend fun insertMany(docs: List<Any?>): IchibaseResponse<JsonElement> =
        op("insertMany") { put("docs", anyToJson(docs)) }

    // ── updates ──────────────────────────────────────────────────────────────

    public suspend fun updateOne(filter: Any?, update: Any?, upsert: Boolean = false): IchibaseResponse<JsonElement> = op("updateOne") {
        put("filter", anyToJson(filter)); put("update", anyToJson(update)); put("upsert", anyToJson(upsert))
    }

    public suspend fun updateMany(filter: Any?, update: Any?, upsert: Boolean = false): IchibaseResponse<JsonElement> = op("updateMany") {
        put("filter", anyToJson(filter)); put("update", anyToJson(update)); put("upsert", anyToJson(upsert))
    }

    public suspend fun replaceOne(filter: Any?, replacement: Any?, upsert: Boolean = false): IchibaseResponse<JsonElement> = op("replaceOne") {
        put("filter", anyToJson(filter)); put("replacement", anyToJson(replacement)); put("upsert", anyToJson(upsert))
    }

    public suspend fun findOneAndUpdate(
        filter: Any?,
        update: Any?,
        upsert: Boolean = false,
        returnAfter: Boolean = true,
    ): IchibaseResponse<JsonElement> = op("findOneAndUpdate") {
        put("filter", anyToJson(filter)); put("update", anyToJson(update))
        put("upsert", anyToJson(upsert)); put("return", anyToJson(if (returnAfter) "after" else "before"))
    }

    public suspend fun findOneAndDelete(filter: Any?): IchibaseResponse<JsonElement> =
        op("findOneAndDelete") { put("filter", anyToJson(filter)) }

    // ── deletes ──────────────────────────────────────────────────────────────

    public suspend fun deleteOne(filter: Any?): IchibaseResponse<JsonElement> = op("deleteOne") { put("filter", anyToJson(filter)) }

    public suspend fun deleteMany(filter: Any?): IchibaseResponse<JsonElement> = op("deleteMany") { put("filter", anyToJson(filter)) }

    // ── bulk ─────────────────────────────────────────────────────────────────

    public suspend fun bulkWrite(operations: List<Any?>, ordered: Boolean = true): IchibaseResponse<JsonElement> = op("bulkWrite") {
        put("operations", anyToJson(operations)); put("ordered", anyToJson(ordered))
    }
}
