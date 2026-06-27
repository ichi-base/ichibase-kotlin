# ichibase (Android · Kotlin/Java)

The official **client-side** SDK for [ichibase](https://ichibase.com) — Postgres,
MongoDB, Auth, Edge Functions, and Realtime from a single client. For **Android**
(Kotlin-first, Java-interoperable), and any Kotlin/JVM app. **Anon key only.**

> Mirrors the TypeScript [`@ichibase/client`](https://github.com/ichi-base/ichibase-client),
> the [Flutter](https://github.com/ichi-base/ichibase-flutter) and
> [Swift](https://github.com/ichi-base/ichibase-swift) SDKs. Building a
> backend/admin tool with the **service** key? Use the server SDKs — this package
> refuses `ich_admin_` keys by design.

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.ichi-base:ichibase-kotlin:0.1.0")
}
```

Add the INTERNET permission in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

```kotlin
import com.ichibase.Ichibase

val ichi = Ichibase(
    "https://<project>.ichibase.net",
    "ich_pub_…", // publishable (anon) key — safe to ship in your app
)
```

All data/auth calls are `suspend` functions — call them from a coroutine
(`lifecycleScope.launch { … }`). Every call returns an `IchibaseResponse` with
`data` and `error` — check `res.ok`. JSON flows as kotlinx `JsonElement`: build
payloads from plain Kotlin (`mapOf(...)`, `listOf(...)`) and read values with the
`string`/`int`/`bool`/`get` helpers, or `ichiJson.decodeFromJsonElement<T>(...)`
into your own `@Serializable` model.

## Database (PostgREST)

```kotlin
// Read with filters / ordering / pagination
val res = ichi.from("posts")
    .select("id, title, author")
    .eq("published", true)
    .order("created_at", ascending = false)
    .limit(20)
    .execute()

res.data?.array?.forEach { row -> println(row["title"]?.string) }

// Insert / update / delete (chain .select() to get the rows back)
ichi.from("posts").insert(mapOf("title" to "Hello", "published" to false)).select().execute()
ichi.from("posts").update(mapOf("published" to true)).eq("id", 1).execute()
ichi.from("posts").delete().eq("id", 1).execute()

// RPC
ichi.rpc("increment_views", args = mapOf("post_id" to 1))
```

After login, queries run **as the user** (their access token), so your
Row-Level Security and policies apply per-user. Logged out, they use the anon key.

## Auth

```kotlin
ichi.auth.signup(email, password)
val login = ichi.auth.login(email, password)
when {
    login.data?.session != null -> { /* signed in; session stored + reused */ }
    login.data?.twoFactorRequired == true -> {
        ichi.auth.verifyTwoFactor(email, code) // a code/link was emailed
    }
}

val user = ichi.auth.getUser()
ichi.auth.logout()

// Passwordless
ichi.auth.signInWithOtp(email)
ichi.auth.verifyOtp(email, code)

// React to auth changes (fires on a background thread — post to main for UI)
ichi.addAuthStateListener { event, session -> /* SIGNED_IN / SIGNED_OUT / TOKEN_REFRESHED */ }
```

Pass a `SharedPreferences`-backed `SessionStore` so logins persist across
launches (see the KDoc on `SessionStore`); the default is in-memory.

## MongoDB

```kotlin
val users = ichi.mongo.collection("users")
users.find(mapOf("active" to true), sort = mapOf("created_at" to -1), limit = 20)
users.insertOne(mapOf("name" to "Ada", "active" to true))
users.updateOne(mapOf("_id" to "abc"), mapOf("\$set" to mapOf("active" to false)))
```

## Edge Functions

```kotlin
val res = ichi.functions.invoke("hello", body = mapOf("name" to "world"))
println(res.data?.get("message")?.string)
```

## Realtime

```kotlin
val sub = ichi.realtime.subscribe(kind = "postgres", table = "posts", events = listOf("INSERT", "UPDATE")) { msg ->
    println("change: ${msg["event"]?.string} ${msg["record"]}")
}
// later
sub.unsubscribe()

// Broadcast channel (publish + presence)
val chat = ichi.realtime.subscribe(kind = "broadcast", channel = "room:42", presence = true) { msg -> println(msg) }
chat.send("message", mapOf("text" to "hi"))
chat.track(mapOf("typing" to true))
```

Call `ichi.realtime.pause()` / `.resume()` from your lifecycle (e.g. `onStop`/
`onStart`) to drop the socket while backgrounded.

## Java

Java apps use **`IchibaseJava`** — the same client wrapped in plain callbacks
(SAM `fun interface`s), so there are no coroutines at the call site. Callbacks
fire on a background thread; hop to the UI thread (e.g. `runOnUiThread`) before
touching views.

```java
IchibaseJava ichi = IchibaseJava.create(
    "https://<project>.ichibase.net",
    "ich_pub_…" // publishable (anon) key
);

// Database — build the chain, then execute(query, callback)
ichi.execute(
    ichi.from("posts").select("*").eq("published", true).order("created_at", false).limit(20),
    res -> {
        if (res.getOk()) { /* res.getData() … */ }
        else { /* res.getError() */ }
    }
);

// Auth
ichi.auth().login("a@b.com", "secret", res -> {
    if (res.getOk() && res.getData().getSession() != null) { /* signed in */ }
});

// Mongo / Functions
ichi.mongo().collection("users").find(java.util.Map.of("active", true), res -> { });
ichi.functions().invoke("hello", java.util.Map.of("name", "world"), res -> { });

// Realtime
JavaSubscription sub = ichi.realtime().subscribePostgres("posts", msg -> { });
// sub.unsubscribe();
```

`Cancelable` is returned from every async call (`.cancel()` to abort). Prefer the
coroutine API from Kotlin? `ichi.kotlin()` returns the underlying `Ichibase`.

## Files / Storage

File **uploads/downloads are intentionally not on the client.** The project
owner mints read tokens / signs upload URLs server-side (an Edge Function + the
service key) and hands them to the app. Public files are served from
`cdn.ichibase.net/<project>/…`.

## License

MIT
