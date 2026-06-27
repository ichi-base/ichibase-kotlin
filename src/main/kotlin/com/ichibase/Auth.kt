package com.ichibase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A signed-in user's session. */
@Serializable
public data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** Epoch seconds when the access token expires (best-effort, from `expires_in`). */
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: JsonElement? = null,
)

/** Auth lifecycle events emitted to listeners registered via
 *  [Ichibase.addAuthStateListener]. */
public enum class AuthEvent { SIGNED_IN, SIGNED_OUT, TOKEN_REFRESHED }

/** Outcome of [Auth.login]. Either [session] is set (normal login — already
 *  stored), or [twoFactorRequired] is true: the project requires 2-step
 *  verification, a code and/or magic link was emailed (see [twoFactorMethods]),
 *  and you finish with [Auth.verifyTwoFactor] / [Auth.verifyTwoFactorMagic]. */
public data class LoginResult(
    val session: Session? = null,
    val twoFactorRequired: Boolean = false,
    val twoFactorMethods: List<String> = emptyList(),
)

/**
 * Auth surface — signup/login plus session helpers. The [Ichibase] client owns
 * the live session; this talks to the project's `/auth` endpoints and reports changes back through the
 * supplied callbacks. Get it via `ichi.auth`.
 */
public class Auth internal constructor(
    private val baseUrl: String,
    private val key: String,
    private val requester: Requester,
    private val getSession: () -> Session?,
    private val setSession: suspend (Session?, AuthEvent) -> Unit,
) {
    private suspend fun call(
        path: String,
        method: String = "POST",
        body: JsonElement? = null,
        bearer: String? = null,
    ): IchibaseResponse<JsonElement> {
        // auth-svc wants the project (anon) key in the `apikey` header; the
        // end-user access token (when acting as a user) goes in Authorization.
        val res = requester.send(method, urlJoin(baseUrl, "/auth$path"), bearer, body.encodeOrNull(), mapOf("apikey" to key))
        return toResponse(res) { it }
    }

    /** Register a new end user. */
    public suspend fun signup(email: String, password: String): IchibaseResponse<JsonElement> =
        call("/signup", body = jsonObjectOf("email" to email, "password" to password))

    /** Log in. Normally `result.session` is set (and stored, so subsequent data
     *  calls run as this user). When the project requires 2-step verification,
     *  the password step yields `result.twoFactorRequired == true`. */
    public suspend fun login(email: String, password: String): IchibaseResponse<LoginResult> {
        val res = call("/login", body = jsonObjectOf("email" to email, "password" to password))
        val d = res.data ?: return IchibaseResponse(error = res.error)
        if (d["twofa_required"]?.bool == true) {
            val methods = d["methods"]?.array?.mapNotNull { it.string } ?: emptyList()
            return IchibaseResponse(data = LoginResult(twoFactorRequired = true, twoFactorMethods = methods))
        }
        val s = parseTokens(d)
        if (s != null) {
            setSession(s, AuthEvent.SIGNED_IN)
            return IchibaseResponse(data = LoginResult(session = s))
        }
        return IchibaseResponse(error = res.error ?: IchibaseError("login_failed", null, 0))
    }

    /** Exchange the stored refresh token for a fresh access token. */
    public suspend fun refresh(): IchibaseResponse<Session> {
        val cur = getSession()
            ?: return IchibaseResponse(error = IchibaseError("no_session", "not logged in", 401))
        val res = call("/refresh", body = jsonObjectOf("refresh_token" to cur.refreshToken))
        val s = res.data?.let { parseTokens(it, fallbackUser = cur.user) }
        if (s != null) {
            setSession(s, AuthEvent.TOKEN_REFRESHED)
            return IchibaseResponse(data = s)
        }
        return IchibaseResponse(error = res.error ?: IchibaseError("refresh_failed", null, 0))
    }

    /** The current signed-in user (from the live access token), or null. */
    public suspend fun getUser(): JsonElement? {
        val s = getSession() ?: return null
        return call("/me", method = "GET", bearer = s.accessToken).data
    }

    /** Sign out: revoke the refresh token and clear the local session. */
    public suspend fun logout() {
        getSession()?.let { s ->
            call("/logout", body = jsonObjectOf("refresh_token" to s.refreshToken), bearer = s.accessToken)
        }
        setSession(null, AuthEvent.SIGNED_OUT)
    }

    // ── Password reset / email verification ──────────────────────────────

    public suspend fun requestPasswordReset(email: String): IchibaseResponse<JsonElement> =
        call("/password-reset/request", body = jsonObjectOf("email" to email))

    public suspend fun confirmPasswordReset(token: String, newPassword: String): IchibaseResponse<JsonElement> =
        call("/password-reset/confirm", body = jsonObjectOf("token" to token, "new_password" to newPassword))

    /** Confirm a reset with the emailed 6-digit code (reset mode `otp`/`both`). */
    public suspend fun confirmPasswordResetOtp(email: String, code: String, newPassword: String): IchibaseResponse<JsonElement> =
        call("/password-reset/confirm-otp", body = jsonObjectOf("email" to email, "code" to code, "new_password" to newPassword))

    public suspend fun verifyEmail(token: String): IchibaseResponse<JsonElement> =
        call("/verify-email", body = jsonObjectOf("token" to token))

    public suspend fun verifyEmailOtp(email: String, code: String): IchibaseResponse<JsonElement> =
        call("/verify-email/otp", body = jsonObjectOf("email" to email, "code" to code))

    public suspend fun resendVerification(email: String): IchibaseResponse<JsonElement> =
        call("/verify-email/resend", body = jsonObjectOf("email" to email))

    // ── Passwordless (OTP + magic link) ──────────────────────────────────

    /** Send the passwordless sign-in email. Always succeeds (202) even for
     *  unknown emails. Finish with [verifyOtp] or [verifyMagicLink]. */
    public suspend fun signInWithOtp(email: String): IchibaseResponse<JsonElement> =
        call("/login/passwordless/request", body = jsonObjectOf("email" to email))

    public suspend fun verifyOtp(email: String, code: String): IchibaseResponse<Session> =
        finishLogin(call("/login/passwordless/verify", body = jsonObjectOf("email" to email, "code" to code)))

    public suspend fun verifyMagicLink(token: String): IchibaseResponse<Session> =
        finishLogin(call("/login/magic", body = jsonObjectOf("token" to token)))

    // ── 2-step verification (call after login returns twoFactorRequired) ──

    public suspend fun verifyTwoFactor(email: String, code: String): IchibaseResponse<Session> =
        finishLogin(call("/login/2fa/verify", body = jsonObjectOf("email" to email, "code" to code)))

    public suspend fun verifyTwoFactorMagic(token: String): IchibaseResponse<Session> =
        finishLogin(call("/login/2fa/magic", body = jsonObjectOf("token" to token)))

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun finishLogin(res: IchibaseResponse<JsonElement>): IchibaseResponse<Session> {
        val s = res.data?.let { parseTokens(it) }
        if (s != null) {
            setSession(s, AuthEvent.SIGNED_IN)
            return IchibaseResponse(data = s)
        }
        return IchibaseResponse(error = res.error ?: IchibaseError("verify_failed", null, 0))
    }

    private fun parseTokens(d: JsonElement, fallbackUser: JsonElement? = null): Session? {
        val access = d["access_token"]?.string ?: return null
        val refresh = d["refresh_token"]?.string ?: return null
        val expiresIn = d["expires_in"]?.long
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = expiresIn?.let { System.currentTimeMillis() / 1000 + it },
            user = d["user"] ?: fallbackUser,
        )
    }
}
