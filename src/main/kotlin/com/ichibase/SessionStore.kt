package com.ichibase

import java.util.concurrent.ConcurrentHashMap

/**
 * Pluggable persistence for the auth session. The SDK keeps the session in
 * memory and mirrors it here so it survives a process restart. The default is
 * [InMemorySessionStore]; on Android pass a `SharedPreferences`-backed adapter
 * so logins persist across launches:
 *
 * ```kotlin
 * class PrefsSessionStore(context: Context) : SessionStore {
 *     private val p = context.getSharedPreferences("ichibase", Context.MODE_PRIVATE)
 *     override fun get(key: String) = p.getString(key, null)
 *     override fun set(key: String, value: String) { p.edit().putString(key, value).apply() }
 *     override fun remove(key: String) { p.edit().remove(key).apply() }
 * }
 * ```
 */
public interface SessionStore {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}

/** In-memory adapter (default) — the session is lost when the process ends. */
public class InMemorySessionStore : SessionStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
