package com.thelightphone.relayinbox

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * A short-lived session for the public inbox (light-relay's `POST /session`).
 *
 * With sessions in the build, the reply token never goes over the internet. At home the
 * tool trades it for a session on the LAN, over HTTPS to a certificate it pins, and
 * presents the session to the public inbox until it expires. Away from home past that,
 * nothing gets through until the phone is back on the LAN, which is the point: a lost
 * phone locks itself out.
 */
@Serializable
data class InboxSession(val token: String, val expiresAt: Long, val renewedAt: Long)

internal object Session : JsonFileState<InboxSession?>(
    fileName = "session.json",
    serializer = InboxSession.serializer().nullable,
    empty = null,
) {
    /** At home, a session is renewed once it's this old: within a few hours of its full length. */
    private const val RENEW_AFTER_MS = 3 * 60 * 60_000L

    /** Away from home, the LAN door times out; don't try it on every 20-second refresh. */
    private const val RETRY_AFTER_MS = 5 * 60_000L

    @Volatile
    private var lastAttempt = 0L

    val enabled: Boolean get() = sessionUrl().isNotEmpty() && sessionPin().isNotEmpty()

    /** The session, unless it's gone or about to be. */
    fun current(now: Long = System.currentTimeMillis()): InboxSession? = state.value?.takeIf { it.expiresAt > now + 60_000 }

    /** Ask the LAN door for a fresh session if it's time. Quiet when it can't be reached: that's being away. */
    suspend fun renewIfDue(replyToken: String, now: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val held = current(now)
        if (held != null && now - held.renewedAt < RENEW_AFTER_MS) return
        if (now - lastAttempt < RETRY_AFTER_MS) return
        lastAttempt = now
        try {
            val renewed = withContext(Dispatchers.IO) { requestSession(sessionUrl(), sessionPin(), replyToken, now) }
            update { renewed }
            Log.i(TAG, "Session renewed until ${java.time.Instant.ofEpochMilli(renewed.expiresAt)}")
        } catch (e: IOException) {
            Log.d(TAG, "No session renewal (not on the home network?): $e")
        }
    }

    /** The inbox refused the session: it expired early, or the keys changed. Try the LAN door next time. */
    suspend fun drop() {
        lastAttempt = 0
        update { null }
    }
}

internal fun sessionUrl(): String = BuildConfig.RELAY_SESSION_URL.trim().removeSuffix("/")

internal fun sessionPin(): String = BuildConfig.RELAY_SESSION_PIN.trim()

/**
 * What to present to the public inbox, or null when there's nothing usable: sessions are
 * on and the phone hasn't had one from home. The reply token goes out only in builds
 * without sessions.
 */
internal fun inboxBearer(keys: PairingKeys): String? =
    if (Session.enabled) Session.current()?.token else keys.replyToken

@Serializable
private data class RenewRequest(val at: Long, val nonce: String, val proof: String)

@Serializable
private data class RenewResponse(val session: String, val expiresAt: String)

/** The reply token's signature over `renew:<at>:<nonce>`: proof we hold it, without sending it. */
internal fun renewalProof(replyToken: String, at: Long, nonce: String): String =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(replyToken.toByteArray(), "HmacSHA256"))
        doFinal("renew:$at:$nonce".toByteArray())
    }.joinToString("") { "%02x".format(it) }

private fun requestSession(baseUrl: String, pin: String, replyToken: String, now: Long): InboxSession {
    val at = now / 1000
    val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val body = json.encodeToString(RenewRequest.serializer(), RenewRequest(at, nonce, renewalProof(replyToken, at, nonce)))
    val url = URL("$baseUrl/session")
    val conn = url.openConnection() as HttpsURLConnection
    try {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), null) }
        conn.sslSocketFactory = tls.socketFactory
        // The pin is what's trusted; the certificate names the LAN IP, which is all this checks.
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == url.host }
        conn.requestMethod = "POST"
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val status = conn.responseCode
        if (status != 200) throw IOException("session refused with HTTP $status")
        val response = json.decodeFromString(RenewResponse.serializer(), conn.inputStream.use { it.readBytes().decodeToString() })
        val expiresAt = parseInstant(response.expiresAt) ?: throw IOException("bad expiresAt ${response.expiresAt}")
        return InboxSession(token = response.session, expiresAt = expiresAt, renewedAt = now)
    } finally {
        conn.disconnect()
    }
}

/** Trusts exactly one key: the session door's, by the SHA-256 of its SubjectPublicKeyInfo. */
internal class PinnedTrust(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
        if (spkiPin(leaf) != pin) throw CertificateException("the session door's certificate doesn't match the pin")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun spkiPin(cert: X509Certificate): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
