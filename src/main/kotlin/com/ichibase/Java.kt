package com.ichibase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// Java-friendly callback layer.
//
// The core SDK is Kotlin coroutines (suspend functions). This file wraps it in
// plain callbacks + SAM `fun interface`s so Android **Java** apps get first-class
// ergonomics — no coroutines on the call site:
//
//   IchibaseJava ichi = IchibaseJava.create("https://<project>.ichibase.net", "ich_pub_…");
//   ichi.execute(ichi.from("posts").select("*").eq("published", true), res -> {
//       if (res.getOk()) { /* res.getData() … */ }
//   });
//   ichi.auth().login("a@b.com", "pw", res -> { /* … */ });
//
// Callbacks fire on a background thread — hop to the main/UI thread yourself
// (e.g. Activity.runOnUiThread / Handler(Looper.getMainLooper())) before touching UI.
// The Kotlin `Ichibase` API is unchanged and still available via `ichi.kotlin()`.
// ─────────────────────────────────────────────────────────────────────────────

/** Result callback. `onResult` receives the same `{ data, error }` response the
 *  Kotlin API returns — check `response.getOk()`. */
public fun interface IchibaseCallback<T> {
    public fun onResult(response: IchibaseResponse<T>)
}

/** Callback for values not wrapped in a response (e.g. the current user). */
public fun interface IchibaseValueCallback<T> {
    public fun onResult(value: T?)
}

/** Realtime message listener (Java-friendly form of the Kotlin lambda). */
public fun interface RealtimeListener {
    public fun onMessage(message: JsonElement)
}

/** Auth-state listener for [IchibaseJava.addAuthStateListener]. */
public fun interface JavaAuthStateListener {
    public fun onChange(event: AuthEvent, session: Session?)
}

/** Handle to cancel an in-flight async call. */
public class Cancelable internal constructor(private val job: Job) {
    public fun cancel() {
        job.cancel()
    }
}

/**
 * Java-friendly facade over [Ichibase]. Create one with [create], then use the
 * callback methods. Anon key only.
 */
public class IchibaseJava private constructor(private val client: Ichibase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    public companion object {
        /** Create a client. `anonKey` must be a publishable `ich_pub_` key. */
        @JvmStatic
        @JvmOverloads
        public fun create(
            url: String,
            anonKey: String,
            httpClient: OkHttpClient = OkHttpClient(),
            store: SessionStore = InMemorySessionStore(),
        ): IchibaseJava = IchibaseJava(Ichibase(url, anonKey, httpClient, store))
    }

    /** Escape hatch: the underlying coroutine-based Kotlin client. */
    public fun kotlin(): Ichibase = client

    internal fun <T> run(cb: IchibaseCallback<T>, block: suspend () -> IchibaseResponse<T>): Cancelable {
        val job = scope.launch {
            val res = try {
                block()
            } catch (e: Throwable) {
                IchibaseResponse<T>(error = IchibaseError("client_error", e.message, 0))
            }
            cb.onResult(res)
        }
        return Cancelable(job)
    }

    /** Launch a suspend block that delivers its own result (used by getUser/logout). */
    internal fun launchAny(block: suspend () -> Unit): Cancelable {
        val job = scope.launch {
            try {
                block()
            } catch (_: Throwable) {
                // best-effort; getUser/logout swallow errors like the Kotlin API
            }
        }
        return Cancelable(job)
    }

    // ── Postgres ─────────────────────────────────────────────────────────────

    /** Start a query. Build the chain with the normal (non-suspend) methods, then
     *  hand it to [execute]. */
    public fun from(table: String): PostgrestQueryBuilder = client.from(table)

    public fun execute(query: PostgrestQueryBuilder, cb: IchibaseCallback<JsonElement>): Cancelable =
        run(cb) { query.execute() }

    public fun rpc(fn: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        run(cb) { client.rpc(fn) }

    public fun rpc(fn: String, args: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        run(cb) { client.rpc(fn, args) }

    // ── Sub-clients ────────────────────────────────────────────────────────────

    private val authJava = JavaAuth(this, client.auth)
    public fun auth(): JavaAuth = authJava

    public fun mongo(): JavaMongo = JavaMongo(this, client.mongo)

    public fun functions(): JavaFunctions = JavaFunctions(this, client.functions)

    private val realtimeJava by lazy { JavaRealtime(client.realtime) }
    public fun realtime(): JavaRealtime = realtimeJava

    // ── Session ────────────────────────────────────────────────────────────────

    public fun getSession(): Session? = client.session

    public fun addAuthStateListener(listener: JavaAuthStateListener): String =
        client.addAuthStateListener { event, session -> listener.onChange(event, session) }

    public fun removeAuthStateListener(id: String) {
        client.removeAuthStateListener(id)
    }

    /** Release resources (realtime socket + the callback scope). */
    public fun close() {
        client.close()
        scope.cancel()
    }
}

/** Java-friendly Auth (callbacks). Reached via `ichi.auth()`. */
public class JavaAuth internal constructor(private val parent: IchibaseJava, private val auth: Auth) {
    public fun signup(email: String, password: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.signup(email, password) }

    public fun login(email: String, password: String, cb: IchibaseCallback<LoginResult>): Cancelable =
        parent.run(cb) { auth.login(email, password) }

    public fun refresh(cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.refresh() }

    /** The current signed-in user, or null. */
    public fun getUser(cb: IchibaseValueCallback<JsonElement>): Cancelable =
        parent.launchAny { cb.onResult(auth.getUser()) }

    /** Sign out; [onComplete] (optional) runs when done. */
    @JvmOverloads
    public fun logout(onComplete: Runnable? = null): Cancelable =
        parent.launchAny {
            auth.logout()
            onComplete?.run()
        }

    public fun requestPasswordReset(email: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.requestPasswordReset(email) }

    public fun confirmPasswordReset(token: String, newPassword: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.confirmPasswordReset(token, newPassword) }

    public fun confirmPasswordResetOtp(email: String, code: String, newPassword: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.confirmPasswordResetOtp(email, code, newPassword) }

    public fun verifyEmail(token: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.verifyEmail(token) }

    public fun verifyEmailOtp(email: String, code: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.verifyEmailOtp(email, code) }

    public fun resendVerification(email: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.resendVerification(email) }

    public fun signInWithOtp(email: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.signInWithOtp(email) }

    public fun verifyOtp(email: String, code: String, cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.verifyOtp(email, code) }

    public fun signInWithPhone(phone: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { auth.signInWithPhone(phone) }

    public fun verifyPhoneOtp(phone: String, code: String, cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.verifyPhoneOtp(phone, code) }

    public fun verifyMagicLink(token: String, cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.verifyMagicLink(token) }

    public fun verifyTwoFactor(email: String, code: String, cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.verifyTwoFactor(email, code) }

    public fun verifyTwoFactorMagic(token: String, cb: IchibaseCallback<Session>): Cancelable =
        parent.run(cb) { auth.verifyTwoFactorMagic(token) }
}

/** Java-friendly Mongo. Reached via `ichi.mongo()`. */
public class JavaMongo internal constructor(private val parent: IchibaseJava, private val mongo: Mongo) {
    public fun collection(name: String): JavaMongoCollection =
        JavaMongoCollection(parent, mongo.collection(name))
}

/** Java-friendly Mongo collection (callbacks). */
public class JavaMongoCollection internal constructor(private val parent: IchibaseJava, private val coll: MongoCollection) {
    public fun find(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.find(filter) }

    public fun find(filter: Any?, projection: Any?, sort: Any?, limit: Int?, skip: Int?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.find(filter, projection, sort, limit, skip) }

    public fun findOne(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.findOne(filter) }

    public fun count(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.count(filter) }

    public fun aggregate(pipeline: List<Any?>, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.aggregate(pipeline) }

    @JvmOverloads
    public fun distinct(field: String, filter: Any? = null, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.distinct(field, filter) }

    public fun insertOne(doc: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.insertOne(doc) }

    public fun insertMany(docs: List<Any?>, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.insertMany(docs) }

    @JvmOverloads
    public fun updateOne(filter: Any?, update: Any?, upsert: Boolean = false, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.updateOne(filter, update, upsert) }

    @JvmOverloads
    public fun updateMany(filter: Any?, update: Any?, upsert: Boolean = false, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.updateMany(filter, update, upsert) }

    @JvmOverloads
    public fun replaceOne(filter: Any?, replacement: Any?, upsert: Boolean = false, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.replaceOne(filter, replacement, upsert) }

    @JvmOverloads
    public fun findOneAndUpdate(filter: Any?, update: Any?, upsert: Boolean = false, returnAfter: Boolean = true, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.findOneAndUpdate(filter, update, upsert, returnAfter) }

    public fun findOneAndDelete(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.findOneAndDelete(filter) }

    public fun deleteOne(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.deleteOne(filter) }

    public fun deleteMany(filter: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.deleteMany(filter) }

    @JvmOverloads
    public fun bulkWrite(operations: List<Any?>, ordered: Boolean = true, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { coll.bulkWrite(operations, ordered) }
}

/** Java-friendly Edge Functions. Reached via `ichi.functions()`. */
public class JavaFunctions internal constructor(private val parent: IchibaseJava, private val functions: Functions) {
    public fun invoke(name: String, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { functions.invoke(name) }

    public fun invoke(name: String, body: Any?, cb: IchibaseCallback<JsonElement>): Cancelable =
        parent.run(cb) { functions.invoke(name, body = body) }
}

/** Java-friendly realtime. Reached via `ichi.realtime()`. */
public class JavaRealtime internal constructor(private val realtime: RealtimeClient) {
    public fun subscribePostgres(table: String, listener: RealtimeListener): JavaSubscription =
        JavaSubscription(realtime.subscribe(kind = "postgres", table = table) { listener.onMessage(it) })

    public fun subscribePostgres(table: String, events: List<String>?, listener: RealtimeListener): JavaSubscription =
        JavaSubscription(realtime.subscribe(kind = "postgres", table = table, events = events) { listener.onMessage(it) })

    public fun subscribeMongo(collection: String, listener: RealtimeListener): JavaSubscription =
        JavaSubscription(realtime.subscribe(kind = "mongo", collection = collection) { listener.onMessage(it) })

    public fun subscribeMongo(collection: String, events: List<String>?, listener: RealtimeListener): JavaSubscription =
        JavaSubscription(realtime.subscribe(kind = "mongo", collection = collection, events = events) { listener.onMessage(it) })

    @JvmOverloads
    public fun subscribeBroadcast(channel: String, presence: Boolean = false, listener: RealtimeListener): JavaSubscription =
        JavaSubscription(realtime.subscribe(kind = "broadcast", channel = channel, presence = presence) { listener.onMessage(it) })

    public fun pause(): Unit = realtime.pause()
    public fun resume(): Unit = realtime.resume()
    public fun disconnect(): Unit = realtime.disconnect()
}

/** Java-friendly subscription handle. */
public class JavaSubscription internal constructor(private val sub: Subscription) {
    public fun unsubscribe(): Unit = sub.unsubscribe()

    /** Publish on a broadcast channel (no-op on postgres/mongo subscriptions). */
    public fun send(event: String, payload: Any?): Unit = sub.send(event, payload)

    /** Update presence on a broadcast channel. */
    public fun track(state: Any?): Unit = sub.track(state)
}
