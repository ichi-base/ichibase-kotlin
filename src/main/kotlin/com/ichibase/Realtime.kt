package com.ichibase

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A live subscription. Call [unsubscribe] to stop; on a broadcast channel use
 *  [send] to publish and [track] to update presence. */
public class Subscription internal constructor(
    public val unsubscribe: () -> Unit,
    public val send: (event: String, payload: Any?) -> Unit,
    public val track: (state: Any?) -> Unit,
)

private class Sub(
    val ref: String,
    val kind: String,
    val table: String?,
    val collection: String?,
    val channel: String?,
    val events: List<String>?,
    val filter: Any?,
    val presence: Boolean,
    val state: Any?,
    val onMessage: (JsonElement) -> Unit,
)

/**
 * One WebSocket per client, multiplexing many subscriptions; reconnects and
 * re-subscribes automatically and speaks the ichibase realtime wire protocol.
 * Access it via `ichi.realtime`. Callbacks fire on a background thread — hop to
 * the main thread yourself before touching UI.
 */
public class RealtimeClient internal constructor(
    private val baseUrl: String,
    private val getToken: () -> String?,
    httpClient: OkHttpClient,
) {
    // A 0 read-timeout keeps the (idle) socket open; OkHttp upgrades the https URL.
    private val client = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val lock = Any()
    private var ws: WebSocket? = null
    private var open = false
    private var connecting = false
    private var closedByUser = false
    private var paused = false
    private val refSeq = AtomicInteger(0)
    private var reconnectAttempts = 0
    private val subs = LinkedHashMap<String, Sub>()
    private val outbox = ArrayList<String>()
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ichibase-realtime").apply { isDaemon = true }
    }
    private var heartbeat: ScheduledFuture<*>? = null

    /** Subscribe to postgres/mongo changes or a broadcast channel. [kind] is
     *  `"postgres"` (set [table]), `"mongo"` (set [collection]), or
     *  `"broadcast"` (set [channel]). */
    public fun subscribe(
        kind: String,
        table: String? = null,
        collection: String? = null,
        channel: String? = null,
        events: List<String>? = null,
        filter: Any? = null,
        presence: Boolean = false,
        state: Any? = null,
        onMessage: (JsonElement) -> Unit,
    ): Subscription {
        val ref = "s" + refSeq.incrementAndGet()
        synchronized(lock) {
            subs[ref] = Sub(ref, kind, table, collection, channel, events, filter, presence, state, onMessage)
            if (open) sendSubscribe(ref)
        }
        ensureConnected()

        return Subscription(
            unsubscribe = {
                val nowEmpty = synchronized(lock) {
                    subs.remove(ref)
                    if (open) rawSend(buildJsonObject { put("type", "unsubscribe"); put("ref", ref) })
                    subs.isEmpty()
                }
                if (nowEmpty) disconnect()
            },
            send = { event, payload ->
                if (kind == "broadcast") rawSend(buildJsonObject {
                    put("type", "broadcast"); put("channel", channel ?: ""); put("event", event)
                    put("payload", anyToJson(payload))
                })
            },
            track = { st ->
                if (kind == "broadcast") rawSend(buildJsonObject {
                    put("type", "presence"); put("channel", channel ?: ""); put("state", anyToJson(st))
                })
            },
        )
    }

    /** Close the socket and drop all subscriptions. */
    public fun disconnect() {
        synchronized(lock) {
            closedByUser = true
            paused = false
            stopHeartbeat()
            open = false
            ws?.close(1000, null)
            ws = null
        }
    }

    /** Pause realtime: close the socket but KEEP subscriptions, and don't
     *  reconnect until [resume]. Call from your Activity/Fragment lifecycle. */
    public fun pause() {
        synchronized(lock) {
            if (paused) return
            paused = true
            stopHeartbeat()
            open = false
            ws?.close(1000, null)
            ws = null
        }
    }

    /** Resume after [pause]: reconnect + re-subscribe if there are live subs. */
    public fun resume() {
        val go = synchronized(lock) {
            if (!paused) return
            paused = false
            reconnectAttempts = 0
            !closedByUser && subs.isNotEmpty()
        }
        if (go) ensureConnected()
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun ensureConnected() = exec.execute { connect() }

    private fun connect() {
        synchronized(lock) {
            if (ws != null || connecting || paused) return
            connecting = true
            closedByUser = false
        }
        val token = getToken()
        val url = "$baseUrl/realtime" + (token?.let { "?token=" + encodeQueryComponent(it) } ?: "")
        val socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
        synchronized(lock) { ws = socket }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (webSocket !== ws) return
                connecting = false
                open = true
                reconnectAttempts = 0
                for (ref in subs.keys) sendSubscribe(ref)
                for (raw in outbox) webSocket.send(raw)
                outbox.clear()
                startHeartbeat()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = handleDisconnect(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleDisconnect(webSocket)
    }

    private fun handleDisconnect(socket: WebSocket) {
        synchronized(lock) {
            if (socket !== ws) return
            open = false
            connecting = false
            stopHeartbeat()
            ws = null
            if (!closedByUser && !paused && subs.isNotEmpty()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val delay = (1000L shl reconnectAttempts).coerceIn(1000L, 15000L)
        reconnectAttempts++
        exec.schedule({
            val go = synchronized(lock) { !closedByUser && !paused && subs.isNotEmpty() }
            if (go) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = exec.scheduleAtFixedRate(
            { rawSend(buildJsonObject { put("type", "ping") }) },
            25, 25, TimeUnit.SECONDS,
        )
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private fun sendSubscribe(ref: String) {
        val s = subs[ref] ?: return
        val msg = buildJsonObject {
            put("type", "subscribe"); put("ref", ref); put("kind", s.kind)
            when (s.kind) {
                "postgres" -> {
                    s.table?.let { put("table", it) }
                    s.events?.let { put("events", anyToJson(it)) }
                    s.filter?.let { put("filter", anyToJson(it)) }
                }
                "mongo" -> {
                    s.collection?.let { put("collection", it) }
                    s.events?.let { put("events", anyToJson(it)) }
                    s.filter?.let { put("filter", anyToJson(it)) }
                }
                else -> {
                    s.channel?.let { put("channel", it) }
                    if (s.presence) put("presence", true)
                    s.state?.let { put("state", anyToJson(it)) }
                }
            }
        }
        rawSend(msg)
    }

    private fun rawSend(msg: JsonElement) {
        val raw = msg.toString()
        synchronized(lock) {
            val s = ws
            if (open && s != null) s.send(raw) else outbox.add(raw)
        }
    }

    private fun onFrame(text: String) {
        val m = try {
            ichiJson.parseToJsonElement(text) as? JsonObject ?: return
        } catch (_: Exception) {
            return
        }
        val targets = synchronized(lock) { subs.values.toList() }
        when (m["type"]?.string) {
            "change" -> for (s in targets) {
                if (s.kind == "postgres" && m["table"]?.string == qualify(s.table)) s.onMessage(m)
                else if (s.kind == "mongo" && m["collection"]?.string == s.collection) s.onMessage(m)
            }
            "broadcast" -> for (s in targets) {
                if (s.kind == "broadcast" && m["channel"]?.string == s.channel) s.onMessage(m)
            }
            "presence_state", "presence_diff" -> for (s in targets) {
                val ch = m["channel"]
                if (s.kind == "broadcast" && (ch?.string == s.channel || ch == null || ch is JsonNull)) s.onMessage(m)
            }
        }
    }

    private fun qualify(table: String?): String? =
        table?.let { if (it.contains('.')) it else "public.$it" }
}
