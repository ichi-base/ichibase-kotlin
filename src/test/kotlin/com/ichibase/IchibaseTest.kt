package com.ichibase

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IchibaseTest {

    // ── JSON helpers ─────────────────────────────────────────────────────

    @Test
    fun jsonBuildAndRead() {
        val v = jsonObjectOf(
            "title" to "Hello",
            "published" to true,
            "views" to 3,
            "ratio" to 1.5,
            "tags" to listOf("a", "b"),
            "extra" to null,
        )
        assertEquals("Hello", v["title"]?.string)
        assertEquals(true, v["published"]?.bool)
        assertEquals(3, v["views"]?.int)
        assertEquals(1.5, v["ratio"]?.double)
        assertEquals("a", v["tags"]?.get(0)?.string)
        assertEquals("b", v["tags"]?.get(1)?.string)
        assertTrue(v["extra"]?.isNull == true)
        assertNull(v["missing"])
    }

    @Test
    fun anyToJsonNested() {
        val v = anyToJson(mapOf("n" to 42, "arr" to listOf(1, 2), "obj" to mapOf("k" to true)))
        assertEquals(42, v["n"]?.int)
        assertEquals(1, v["arr"]?.get(0)?.int)
        assertEquals(true, v["obj"]?.get("k")?.bool)
    }

    @Test
    fun queryStrings() {
        assertEquals("hi", valueToQueryString("hi"))
        assertEquals("5", valueToQueryString(5))
        assertEquals("true", valueToQueryString(true))
        assertEquals("null", valueToQueryString(null))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @Test
    fun urlJoinCollapsesSlash() {
        assertEquals("https://x.ichibase.net/auth/login", urlJoin("https://x.ichibase.net/", "/auth/login"))
        assertEquals("https://x.ichibase.net/auth/login", urlJoin("https://x.ichibase.net", "auth/login"))
    }

    @Test
    fun encodeQuery() {
        assertEquals("a%20b", encodeQueryComponent("a b"))
        assertEquals("a%2Cb", encodeQueryComponent("a,b"))
        assertEquals("title", encodeQueryComponent("title"))
    }

    // ── Session / response shapes ────────────────────────────────────────

    @Test
    fun sessionSerializesSnakeCase() {
        val s = Session(accessToken = "a", refreshToken = "r", expiresAt = 123)
        val text = ichiJson.encodeToString(Session.serializer(), s)
        val json = ichiJson.parseToJsonElement(text) as JsonObject
        assertEquals("a", json["access_token"]?.string)
        assertEquals("r", json["refresh_token"]?.string)
        assertEquals(123, json["expires_at"]?.int)
        assertEquals(s, ichiJson.decodeFromString(Session.serializer(), text))
    }

    @Test
    fun responseOk() {
        assertTrue(IchibaseResponse(data = 1).ok)
        assertTrue(!IchibaseResponse<Int>(error = IchibaseError("x", null, 400)).ok)
    }

    // ── client ───────────────────────────────────────────────────────────

    @Test
    fun rejectsServiceKey() {
        assertFailsWith<IllegalArgumentException> {
            Ichibase("https://demo.ichibase.net", "ich_admin_secret")
        }
    }

    @Test
    fun stripsSlashAndHydratesSession() {
        val store = InMemorySessionStore()
        val s = Session(accessToken = "a", refreshToken = "r")
        store.set("ichibase.session", ichiJson.encodeToString(Session.serializer(), s))
        val ichi = Ichibase("https://demo.ichibase.net/", "ich_pub_demo", store = store)
        assertEquals("https://demo.ichibase.net", ichi.url) // trailing slash stripped
        assertEquals("a", ichi.session?.accessToken)        // hydrated from store
    }
}
