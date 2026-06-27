package com.ichibase

import kotlinx.serialization.json.JsonElement

/** Invoke your deployed Edge Functions. Sets the `apikey`, attaches the
 *  signed-in user's token, JSON-encodes the body, and returns an
 *  [IchibaseResponse]. Get one via `ichi.functions`. */
public class Functions internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
    private val userToken: String? = null,
) {
    /** A Functions client that calls AS a specific end user. */
    public fun asUser(accessToken: String): Functions = Functions(baseUrl, key, requester, accessToken)

    /**
     * Invoke a function by name.
     *
     * ```kotlin
     * val res = ichi.functions.invoke("hello", body = mapOf("name" to "world"))
     * ```
     *
     * [path] is appended after the function name (e.g. `/items/42`). [body]
     * accepts plain Kotlin (`mapOf(...)`) or a [JsonElement].
     */
    public suspend fun invoke(
        name: String,
        method: String = "POST",
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        path: String = "",
    ): IchibaseResponse<JsonElement> {
        val h = HashMap(headers)
        h["apikey"] = key
        val jsonBody = if (body == null) null else anyToJson(body).encodeOrNull()
        val res = requester.send(method, urlJoin(baseUrl, "/functions/$name$path"), userToken, jsonBody, h)
        return toResponse(res) { it }
    }

    /** Invoke a function with a raw string body (e.g. text/plain). Set your own
     *  `Content-Type` in [headers]. */
    public suspend fun invokeRaw(
        name: String,
        rawBody: String,
        method: String = "POST",
        headers: Map<String, String> = emptyMap(),
        path: String = "",
    ): IchibaseResponse<JsonElement> {
        val h = HashMap(headers)
        h["apikey"] = key
        val res = requester.send(method, urlJoin(baseUrl, "/functions/$name$path"), userToken, rawBody, h)
        return toResponse(res) { it }
    }
}
