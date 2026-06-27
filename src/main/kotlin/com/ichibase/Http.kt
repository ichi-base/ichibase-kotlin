package com.ichibase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A structured error returned by the ichibase API (mirrors the TS / Dart /
 *  Swift `IchibaseError`). Transport failures surface with `status == 0`. */
public class IchibaseError(
    public val code: String,
    public val detail: String?,
    public val status: Int,
) {
    override fun toString(): String =
        "IchibaseError(code=$code, status=$status" + (detail?.let { ", detail=$it" } ?: "") + ")"
}

/** Every call resolves to one of these: [data] on success, [error] on failure.
 *  Check [ok]. */
public class IchibaseResponse<T>(
    public val data: T? = null,
    public val error: IchibaseError? = null,
) {
    public val ok: Boolean get() = error == null
}

internal class RawResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
    val transportError: String?,
)

internal fun urlJoin(base: String, path: String): String {
    val b = base.trimEnd('/')
    val p = if (path.startsWith("/")) path else "/$path"
    return b + p
}

/** encodeURIComponent-style percent-encoding so PostgREST filter columns/values
 *  round-trip safely. */
internal fun encodeQueryComponent(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
            sb.append(ch)
        } else {
            sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}

internal fun decodeBody(body: String): JsonElement =
    if (body.isBlank()) JsonNull
    else try {
        ichiJson.parseToJsonElement(body)
    } catch (_: Exception) {
        JsonPrimitive(body)
    }

internal fun <T> toResponse(res: RawResult, map: (JsonElement) -> T): IchibaseResponse<T> {
    val bodyJson = decodeBody(res.body)
    if (res.status in 200..299) return IchibaseResponse(data = map(bodyJson))
    if (res.status == 0) {
        return IchibaseResponse(error = IchibaseError("network_error", res.transportError ?: "request failed", 0))
    }
    var code: String? = null
    var detail: String? = null
    (bodyJson as? JsonObject)?.let {
        code = it["code"]?.string
        detail = it["detail"]?.string ?: it["message"]?.string
    }
    return IchibaseResponse(
        error = IchibaseError(code ?: "http_${res.status}", detail ?: "HTTP ${res.status}", res.status),
    )
}

internal val JSON_MEDIA = "application/json".toMediaType()

/** Performs HTTP requests and, when given a [refresh] closure, transparently
 *  refreshes an expired user JWT and retries once on a 401 — the same behavior
 *  as the other SDKs. Auth's own `/refresh` uses a *raw* requester (no refresh)
 *  so it can't recurse. */
internal class Requester(
    private val client: OkHttpClient,
    private val currentUserToken: () -> String? = { null },
    private val refresh: (suspend () -> Boolean)? = null,
) {
    suspend fun send(
        method: String,
        url: String,
        bearer: String?,
        jsonBody: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): RawResult {
        val first = perform(method, url, bearer, jsonBody, headers)
        val token = currentUserToken()
        if (first.status != 401 || refresh == null || token == null || bearer != token) return first
        val refreshed = refresh.invoke()
        val fresh = currentUserToken()
        if (!refreshed || fresh == null) return first
        return perform(method, url, fresh, jsonBody, headers)
    }

    private suspend fun perform(
        method: String,
        url: String,
        bearer: String?,
        jsonBody: String?,
        headers: Map<String, String>,
    ): RawResult = withContext(Dispatchers.IO) {
        try {
            val reqBody = jsonBody?.toRequestBody(JSON_MEDIA)
            val finalBody = when {
                reqBody != null -> reqBody
                method == "GET" || method == "HEAD" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            val builder = Request.Builder().url(url).method(method, finalBody)
            if (bearer != null) builder.header("Authorization", "Bearer $bearer")
            for ((k, v) in headers) builder.header(k, v)

            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val hdrs = HashMap<String, String>()
                for (name in resp.headers.names()) hdrs[name.lowercase()] = resp.headers[name] ?: ""
                RawResult(resp.code, text, hdrs, null)
            }
        } catch (e: IOException) {
            RawResult(0, "", emptyMap(), e.message)
        }
    }
}
