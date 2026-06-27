package com.ichibase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The SDK passes and returns JSON as [JsonElement] (kotlinx.serialization). You
 * build payloads from plain Kotlin maps/lists with [anyToJson] / [jsonObjectOf]
 * and read values back with the [string]/[int]/[bool]/get extensions below — or
 * decode straight into your own `@Serializable` model with
 * `ichiJson.decodeFromJsonElement<MyType>(res.data!!)`.
 */
public val ichiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/** Convert a plain Kotlin value (Map / List / String / Number / Boolean / null
 *  / JsonElement) into a [JsonElement]. */
public fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject { for ((k, v) in value) put(k.toString(), anyToJson(v)) }
    is Iterable<*> -> buildJsonArray { for (v in value) add(anyToJson(v)) }
    is Array<*> -> buildJsonArray { for (v in value) add(anyToJson(v)) }
    else -> JsonPrimitive(value.toString())
}

/** Build a JSON object from pairs: `jsonObjectOf("title" to "Hi", "n" to 1)`. */
public fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    buildJsonObject { for ((k, v) in pairs) put(k, anyToJson(v)) }

// ── Reading helpers ──────────────────────────────────────────────────────

/** The string content if this is a JSON string (not a number/bool), else null. */
public val JsonElement.string: String? get() = (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }
public val JsonElement.int: Int? get() = (this as? JsonPrimitive)?.intOrNull
public val JsonElement.long: Long? get() = (this as? JsonPrimitive)?.longOrNull
public val JsonElement.double: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
public val JsonElement.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
public val JsonElement.isNull: Boolean get() = this is JsonNull
public val JsonElement.array: List<JsonElement>? get() = this as? JsonArray
public val JsonElement.obj: Map<String, JsonElement>? get() = this as? JsonObject

/** Object-field access: `res.data?.get("title")?.string`. */
public operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Array-index access: `res.data?.get(0)`. */
public operator fun JsonElement.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** String form used when a value goes into a PostgREST filter (`?col=eq.<value>`). */
internal fun valueToQueryString(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    is String -> value
    is JsonPrimitive -> value.content
    is JsonNull -> "null"
    is JsonElement -> value.toString()
    else -> value.toString()
}

/** Serialize for a request body; null / JsonNull → no body. */
internal fun JsonElement?.encodeOrNull(): String? =
    if (this == null || this is JsonNull) null else this.toString()
