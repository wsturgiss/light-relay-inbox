package com.thelightphone.relayinbox

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
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
    val messageId: String?,
    val thread: String?,
    val title: String?,
    val choice: String?,
    val text: String?,
    val sentAt: String,
    val attachments: List<String>,
    val to: String? = null,
    val choiceIds: List<String>? = null,
    val other: Boolean = false,
)

/**
 * Posts pending replies to the public reply inbox. The reply's id makes a resend
 * harmless — the inbox stores each id once — so an unclear outcome is always retried.
 * A reply's voice notes and photos go up first (`PUT /attachments/<id>`, just as
 * retry-safe), and the reply names them.
 */
@LightJob("send-replies")
val sendReplies: LightJobHandler = { ctx, _ ->
    openStores(ctx.filesDir)
    val keys = Pairing.keys.value
    val url = inboxUrl()
    keys?.let { Session.renewIfDue(it.replyToken) }
    val token = keys?.let(::inboxBearer)
    if (keys == null || url.isEmpty()) {
        Log.w(TAG, "Replies can't be sent: ${if (keys == null) "not paired" else "relay.inboxUrl not set"}")
        LightJobResult.Error()
    } else if (token == null) {
        // Sessions are on and this phone has none: replies wait until it's home.
        Log.i(TAG, "Replies waiting for a session from the home network")
        LightJobResult.Retry
    } else {
        var retry = false
        replies@ for ((message, reply) in RelayStore.pending()) {
            for (attachment in reply.attachments.filterNot { it.uploaded }) {
                val file = attachment.path?.let(::File)?.takeIf { it.exists() }
                if (file == null) {
                    Log.w(TAG, "Reply ${reply.id}: ${attachment.id} is no longer on this phone")
                    RelayStore.setReplyState(reply.id, ReplyState.Failed)
                    continue@replies
                }
                val status = try {
                    soonAfter429 { withContext(Dispatchers.IO) { upload(inboxRoute(url, "/attachments/${attachment.id}"), token, attachment.type, file) } }
                } catch (e: IOException) {
                    Log.w(TAG, "${attachment.id} not uploaded yet: $e")
                    retry = true
                    continue@replies
                }
                when {
                    status in 200..299 -> RelayStore.markUploaded(reply.id, attachment.id)
                    status == 401 && Session.enabled -> {
                        Session.drop()
                        retry = true
                        continue@replies
                    }
                    status == 429 || status >= 500 -> {
                        retry = true
                        continue@replies
                    }
                    else -> {
                        Log.w(TAG, "${attachment.id} refused with HTTP $status")
                        RelayStore.setReplyState(reply.id, ReplyState.Failed)
                        continue@replies
                    }
                }
            }
            val body = json.encodeToString(
                ReplyBody.serializer(),
                ReplyBody(
                    id = reply.id,
                    // Written in a conversation you started, before the agent answered.
                    messageId = message.id.takeUnless { message.mine },
                    thread = message.id.takeIf { message.mine },
                    title = message.headline.takeIf { message.mine },
                    choice = reply.choice,
                    text = reply.text,
                    sentAt = Instant.ofEpochMilli(reply.at).toString(),
                    attachments = reply.attachments.map { it.id },
                    to = message.to.takeIf { message.mine },
                    choiceIds = reply.choiceIds,
                    other = reply.other,
                ),
            )
            val status = try {
                soonAfter429 { withContext(Dispatchers.IO) { post(url, token, body) } }
            } catch (e: IOException) {
                Log.w(TAG, "Reply ${reply.id} not sent yet: $e")
                retry = true
                continue
            }
            when {
                status in 200..299 -> RelayStore.replySent(reply.id)
                // The session ran out or the keys changed: not this reply's fault.
                status == 401 && Session.enabled -> {
                    Session.drop()
                    retry = true
                }
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

/**
 * Asks again a few seconds after a 429, a couple of times, before the job gives up for now.
 * Left to WorkManager, a reply refused by the inbox's rate limit waited 30 seconds.
 */
private suspend fun soonAfter429(request: suspend () -> Int): Int {
    var status = request()
    repeat(2) { attempt ->
        if (status != 429) return status
        delay(3_000L * (attempt + 1))
        status = request()
    }
    return status
}

private fun upload(url: String, token: String, type: String, file: File): Int {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "PUT"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(file.length())
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", type)
        conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        return conn.responseCode
    } finally {
        conn.disconnect()
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
