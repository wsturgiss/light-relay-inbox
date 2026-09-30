package com.thelightphone.relayinbox

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

internal const val SEND_REPLIES_JOB = "send-replies"

internal fun inboxUrl(): String = BuildConfig.RELAY_INBOX_URL.trim()

/** Queue a send of every pending reply. Offline is fine: the job retries with backoff. */
internal fun scheduleReplySend(context: SealedLightContext) {
    LightWork.enqueue(context, SEND_REPLIES_JOB)
}

@Serializable
private data class ReplyBody(
    val id: String,
    val messageId: String,
    val choice: String?,
    val text: String?,
    val sentAt: String,
)

/**
 * Posts pending replies to the public reply inbox. The reply's id makes a resend
 * harmless — the inbox stores each id once — so an unclear outcome is always retried.
 */
@LightJob("send-replies")
val sendReplies: LightJobHandler = { ctx, _ ->
    openStores(ctx.filesDir)
    val token = Pairing.keys.value?.replyToken
    val url = inboxUrl()
    if (token == null || url.isEmpty()) {
        Log.w(TAG, "Replies can't be sent: ${if (token == null) "not paired" else "relay.inboxUrl not set"}")
        LightJobResult.Error()
    } else {
        var retry = false
        for ((message, reply) in RelayStore.pending()) {
            val body = json.encodeToString(
                ReplyBody.serializer(),
                ReplyBody(reply.id, message.id, reply.choice, reply.text, Instant.ofEpochMilli(reply.at).toString()),
            )
            val status = try {
                withContext(Dispatchers.IO) { post(url, token, body) }
            } catch (e: IOException) {
                Log.w(TAG, "Reply ${reply.id} not sent yet: $e")
                retry = true
                continue
            }
            when {
                status in 200..299 -> RelayStore.setReplyState(reply.id, ReplyState.Sent)
                status == 429 || status >= 500 -> retry = true
                else -> {
                    // 400/401/413: resending the same bytes won't help. Shown as failed, retryable by hand.
                    Log.w(TAG, "Reply ${reply.id} refused with HTTP $status")
                    RelayStore.setReplyState(reply.id, ReplyState.Failed)
                }
            }
        }
        if (retry) LightJobResult.Retry else LightJobResult.Success()
    }
}

private fun post(url: String, token: String, body: String): Int {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.responseCode
    } finally {
        conn.disconnect()
    }
}
