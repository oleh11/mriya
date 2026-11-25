package dev.mriya.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.mriya.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** One mail as the flight reader needs it. Never stored: it is parsed and dropped. */
data class GmailMessage(val id: String, val subject: String, val from: String, val date: String, val body: String)

class GmailException(message: String) : Exception(message)

/**
 * Read-only Gmail access for finding booking emails, using the user's own Google sign-in (OAuth 2.0 with PKCE, no
 * client secret, no Google Play Services). Only the `gmail.readonly` scope is requested; nothing is sent, marked,
 * moved or deleted. Mail is fetched when the user taps Search, parsed on the phone and discarded.
 *
 * The OAuth client id is the builder's own (see docs/GMAIL_SETUP.md); a build without one shows Gmail as unavailable.
 */
class GmailClient(
    ctx: Context? = null,
    private val clientId: String = BuildConfig.GMAIL_CLIENT_ID,
    private val http: OkHttpClient = shared,
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val apiBase: String = "https://gmail.googleapis.com",
    private val store: SecureStore? = ctx?.let { SecureStore(it) },
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = store?.get(REFRESH).orEmpty().isNotEmpty()

    // ---- sign-in ------------------------------------------------------------------------------------------------

    /** The browser intent that starts Google's own sign-in and consent page. Returns null when not configured. */
    fun signInIntent(): Intent? {
        if (!isConfigured) return null
        val pkce = newPkce()
        val state = randomUrlSafe(16)
        store?.put(PENDING, "${pkce.verifier}|$state")
        return Intent(Intent.ACTION_VIEW, Uri.parse(authUrl(clientId, pkce.challenge, state)))
    }

    /** Finishes sign-in from the redirect Google sends back to the app. Throws [GmailException] with a plain reason. */
    suspend fun finishSignIn(redirect: String) = withContext(Dispatchers.IO) {
        val pending = store?.get(PENDING).orEmpty().split('|')
        store?.put(PENDING, "")
        if (pending.size != 2) throw GmailException("Sign-in was not started from this app. Try again.")
        val code = parseRedirect(redirect, pending[1])
        val json = postToken(
            "client_id" to clientId, "code" to code, "code_verifier" to pending[0],
            "grant_type" to "authorization_code", "redirect_uri" to redirectUri(clientId),
        )
        val refresh = json["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw GmailException("Google did not return a long-lived sign-in. Try again.")
        store?.put(REFRESH, refresh)
        accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
        accessUntil = System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3000L) * 1000 - 60_000
    }

    /** Forgets the sign-in on this phone and asks Google to revoke it. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val refresh = store?.get(REFRESH).orEmpty()
        store?.put(REFRESH, "")
        accessToken = null
        if (refresh.isNotEmpty()) runCatching {
            http.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke").post(FormBody.Builder().add("token", refresh).build()).build()).execute().close()
        }
    }

    @Volatile internal var accessToken: String? = null
    @Volatile internal var accessUntil = 0L

    private fun postToken(vararg form: Pair<String, String>): JsonObject {
        val body = FormBody.Builder().apply { form.forEach { add(it.first, it.second) } }.build()
        http.newCall(Request.Builder().url(tokenUrl).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!r.isSuccessful || obj == null) {
                val why = obj?.get("error")?.jsonPrimitive?.contentOrNull
                throw GmailException(if (why == "invalid_grant") "Google sign-in expired. Connect Gmail again." else "Google sign-in failed (${r.code}).")
            }
            return obj
        }
    }

    private fun token(): String {
        accessToken?.takeIf { System.currentTimeMillis() < accessUntil }?.let { return it }
        val refresh = store?.get(REFRESH).orEmpty()
        if (refresh.isEmpty()) throw GmailException("Gmail is not connected.")
        val json = try {
            postToken("client_id" to clientId, "refresh_token" to refresh, "grant_type" to "refresh_token")
        } catch (e: GmailException) {
            if (e.message?.contains("expired") == true) store?.put(REFRESH, "")
            throw e
        }
        accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull ?: throw GmailException("Google did not return access.")
        accessUntil = System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3000L) * 1000 - 60_000
        return accessToken!!
    }

    // ---- search -------------------------------------------------------------------------------------------------

    /** Messages matching a Gmail search ([query] uses the same syntax as Gmail's own search box), newest first. */
    suspend fun search(query: String, max: Int = 40, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<GmailMessage> =
        withContext(Dispatchers.IO) {
            val list = getJson(apiBase.toHttpUrl().newBuilder().addPathSegments("gmail/v1/users/me/messages")
                .addQueryParameter("q", query).addQueryParameter("maxResults", max.coerceIn(1, 100).toString()).build().toString())
            val ids = list["messages"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            ids.mapIndexedNotNull { i, id ->
                onProgress(i, ids.size)
                runCatching {
                    parseMessage(getJson(apiBase.toHttpUrl().newBuilder().addPathSegments("gmail/v1/users/me/messages/$id")
                        .addQueryParameter("format", "full").build().toString()))
                }.getOrNull()
            }.also { onProgress(ids.size, ids.size) }
        }

    private fun getJson(url: String): JsonObject {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw GmailException(
                if (text.contains("accessNotConfigured") || text.contains("has not been used")) "The Gmail API is not enabled for this app's Google project."
                else "Google refused access. Disconnect and connect Gmail again.",
            )
            if (!r.isSuccessful) throw GmailException("Gmail returned an error (${r.code}).")
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        private const val REFRESH = "gmail_refresh"
        private const val PENDING = "gmail_pending"
        private const val MAX_BODY = 200_000

        private val shared: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
        }

        /** The custom scheme Google expects for an Android OAuth client: the client id, reversed into a scheme. */
        fun redirectScheme(clientId: String) = "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
        fun redirectUri(clientId: String) = redirectScheme(clientId) + ":/oauth2redirect"

        class Pkce(val verifier: String, val challenge: String)

        private val rng = SecureRandom()
        internal fun randomUrlSafe(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { rng.nextBytes(it) })

        internal fun newPkce(): Pkce {
            val verifier = randomUrlSafe(48)
            return Pkce(verifier, challengeFor(verifier))
        }

        internal fun challengeFor(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        internal fun authUrl(clientId: String, challenge: String, state: String): String =
            "https://accounts.google.com/o/oauth2/v2/auth".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirectUri(clientId))
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", SCOPE)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("state", state)
                .addQueryParameter("access_type", "offline")
                .addQueryParameter("prompt", "consent")
                .build().toString()

        /** The authorization code from the redirect, after checking the state. Throws [GmailException] on a refusal. */
        internal fun parseRedirect(redirect: String, expectedState: String): String {
            val query = runCatching { URI(redirect).rawQuery }.getOrNull().orEmpty()
            val params = query.split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            params["error"]?.let { throw GmailException(if (it == "access_denied") "Gmail access was not allowed." else "Google sign-in failed: $it") }
            if (params["state"] != expectedState) throw GmailException("Sign-in did not match. Try connecting again.")
            return params["code"] ?: throw GmailException("Google did not send a sign-in code.")
        }

        /** Subject, sender, date and the readable body (plain text if there is any, else the HTML) of a Gmail message. */
        internal fun parseMessage(msg: JsonObject): GmailMessage {
            val payload = msg["payload"]?.jsonObject ?: throw GmailException("Empty message")
            val headers = payload["headers"]?.jsonArray.orEmpty().associate {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase() to it.jsonObject["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val plain = StringBuilder(); val html = StringBuilder()
            collect(payload, plain, html)
            val body = (if (plain.isNotBlank()) plain else html).toString().take(MAX_BODY)
            return GmailMessage(msg["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), headers["subject"].orEmpty(), headers["from"].orEmpty(), headers["date"].orEmpty(), body)
        }

        private fun collect(part: JsonObject, plain: StringBuilder, html: StringBuilder) {
            val mime = part["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val data = part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
            if (data != null) {
                val text = runCatching { String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8) }.getOrNull()
                if (text != null) when (mime) { "text/plain" -> plain.append(text).append('\n'); "text/html" -> html.append(text).append('\n') }
            }
            part["parts"]?.jsonArray?.forEach { (it as? JsonObject)?.let { p -> collect(p, plain, html) } }
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

        /** What to look for by default: booking-like mail, not promotions, from the last three years. */
        const val DEFAULT_QUERY = "(flight OR itinerary OR \"boarding pass\" OR \"confirmation code\" OR \"e-ticket\") -category:promotions newer_than:3y"
    }
}
