package com.ichibase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The single client an Android (Kotlin or Java) app uses. **Anon key only.**
 *
 * ```kotlin
 * val ichi = Ichibase(
 *     "https://<project>.ichibase.net",
 *     "ich_pub_…", // publishable (anon) key — safe to ship in your app
 * )
 * val res = ichi.from("posts").select("*").eq("published", true).execute()
 * ichi.auth.login(email, password)
 * ```
 *
 * One config + one session shared across Postgres, Auth, Mongo, Functions, and
 * Realtime. After login, data calls use the user's access token so your RLS /
 * policies / realtime rules apply per-user; logged out, they use the publishable
 * anon key. All data/auth calls are `suspend` functions (run them from a
 * coroutine). Pass a `SharedPreferences`-backed [SessionStore] so logins persist
 * across launches.
 */
public class Ichibase @JvmOverloads constructor(
    url: String,
    private val anonKey: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val store: SessionStore = InMemorySessionStore(),
    private val storageKey: String = "ichibase.session",
) {
    /** Project base URL, e.g. `https://abc.ichibase.net` (no trailing slash). */
    public val url: String = url.trimEnd('/')

    @Volatile
    private var sessionRef: Session? = null
    private val listeners = ConcurrentHashMap<String, (AuthEvent, Session?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshJob: Deferred<Boolean>? = null

    private val rawRequester = Requester(httpClient)
    private val dataRequester = Requester(
        httpClient,
        currentUserToken = { sessionRef?.accessToken },
        refresh = { autoRefresh() },
    )

    /** Auth surface (signup / login / logout / refresh / getUser / …). */
    public val auth: Auth = Auth(
        this.url, anonKey, rawRequester,
        getSession = { sessionRef },
        setSession = { s, ev -> applySession(s, ev) },
    )

    /** Realtime subscriptions (Postgres/Mongo changes + broadcast channels). */
    public val realtime: RealtimeClient by lazy { RealtimeClient(this.url, { bearer() }, httpClient) }

    init {
        require(anonKey.isNotEmpty()) { "ichibase: anon key is required" }
        require(!anonKey.startsWith("ich_admin_")) {
            "ichibase: this client is anon-key only. ich_admin_ (service) keys bypass RLS — " +
                "never ship them in an app. Use them server-side instead."
        }
        // Hydrate any persisted session.
        store.get(storageKey)?.let { raw ->
            sessionRef = try {
                ichiJson.decodeFromString(Session.serializer(), raw)
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── Postgres ─────────────────────────────────────────────────────────────

    /** Start a PostgREST query against a table or view. */
    public fun from(table: String): PostgrestQueryBuilder =
        Postgrest(url, bearer(), dataRequester).from(table)

    /** Call a Postgres function (RPC). */
    public suspend fun rpc(
        fn: String,
        args: Any? = emptyMap<String, Any?>(),
        schema: String? = null,
        count: String? = null,
    ): IchibaseResponse<JsonElement> =
        Postgrest(url, bearer(), dataRequester).rpc(fn, args, schema, count)

    // ── Mongo / Functions ─────────────────────────────────────────────────────

    /** MongoDB data client (acts as the signed-in user when logged in). */
    public val mongo: Mongo
        get() {
            val m = Mongo(url, anonKey, dataRequester)
            val s = sessionRef
            return if (s != null) m.asUser(s.accessToken) else m
        }

    /** Invoke your deployed Edge Functions (as the signed-in user when logged in). */
    public val functions: Functions
        get() {
            val f = Functions(url, anonKey, dataRequester)
            val s = sessionRef
            return if (s != null) f.asUser(s.accessToken) else f
        }

    // ── Session ────────────────────────────────────────────────────────────────

    /** The current signed-in session, or null. */
    public val session: Session? get() = sessionRef

    /** Set the session directly (e.g. restored from your own storage). */
    public suspend fun setSession(session: Session?) {
        applySession(session, if (session != null) AuthEvent.SIGNED_IN else AuthEvent.SIGNED_OUT)
    }

    /** Register an auth-state listener. Returns a token; pass it to
     *  [removeAuthStateListener] to stop. Callbacks fire on a background thread —
     *  post to the main thread before touching UI. */
    public fun addAuthStateListener(listener: (AuthEvent, Session?) -> Unit): String {
        val id = UUID.randomUUID().toString()
        listeners[id] = listener
        return id
    }

    public fun removeAuthStateListener(id: String) {
        listeners.remove(id)
    }

    /** Release resources (realtime socket). */
    public fun close() {
        realtime.disconnect()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun bearer(): String = sessionRef?.accessToken ?: anonKey

    private suspend fun applySession(s: Session?, event: AuthEvent) {
        sessionRef = s
        try {
            if (s != null) {
                store.set(storageKey, ichiJson.encodeToString(Session.serializer(), s))
            } else {
                store.remove(storageKey)
            }
        } catch (_: Exception) {
            // persistence is best-effort
        }
        for (l in listeners.values) {
            try {
                l(event, s)
            } catch (_: Exception) {
            }
        }
    }

    // Refresh the access token, sharing one in-flight refresh across concurrent
    // 401 retries. Returns true if a valid session is in place afterwards.
    private suspend fun autoRefresh(): Boolean {
        val job = refreshMutex.withLock {
            refreshJob ?: scope.async {
                val r = auth.refresh()
                r.ok && sessionRef != null
            }.also { refreshJob = it }
        }
        return try {
            job.await()
        } finally {
            refreshMutex.withLock { if (refreshJob === job) refreshJob = null }
        }
    }

    public companion object {
        @Volatile
        private var instanceRef: Ichibase? = null

        /** The global client created by [initialize]. Throws if [initialize]
         *  hasn't run. Optional convenience — you can also just hold your own. */
        @JvmStatic
        public val instance: Ichibase
            get() = instanceRef
                ?: throw IllegalStateException("ichibase: call Ichibase.initialize(url, anonKey) before Ichibase.instance")

        @JvmStatic
        public val isInitialized: Boolean get() = instanceRef != null

        /** Initialize the global singleton once (e.g. in Application.onCreate). */
        @JvmStatic
        @JvmOverloads
        public fun initialize(
            url: String,
            anonKey: String,
            httpClient: OkHttpClient = OkHttpClient(),
            store: SessionStore = InMemorySessionStore(),
            storageKey: String = "ichibase.session",
        ): Ichibase {
            val client = Ichibase(url, anonKey, httpClient, store, storageKey)
            instanceRef = client
            return client
        }
    }
}
