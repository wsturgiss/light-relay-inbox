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
import kotlin.time.Duration.Companion.minutes

internal const val SYNC_JOB = "sync"

/** The one-time run gets its own slot: sharing [SYNC_JOB]'s would cancel the periodic schedule. */
private const val SYNC_NOW_TAG = "sync-now"

/**
 * Fetch now, and keep fetching every 15 minutes (WorkManager's floor) while the tool is
 * installed. Without a push endpoint this is how messages arrive; with one, it fills any
 * gaps and keeps the history in step with the relay.
 */
internal fun scheduleSync(context: SealedLightContext) {
    LightWork.enqueue(context, SYNC_JOB, tag = SYNC_NOW_TAG)
    LightWork.enqueuePeriodic(context, SYNC_JOB, 15.minutes)
}

/** How far through the relay's messages and the inbox's replies this phone has read. */
@Serializable
data class SyncState(val messages: Long = 0, val replies: Long = 0)

internal object SyncCursor : JsonFileState<SyncState>(
    fileName = "sync.json",
    serializer = SyncState.serializer(),
    empty = SyncState(),
) {
    // max(): a one-time and a periodic run can overlap, and the cursor never goes back.
    suspend fun messagesUpTo(seq: Long) = update { it.copy(messages = maxOf(it.messages, seq)) }

    suspend fun repliesUpTo(seq: Long) = update { it.copy(replies = maxOf(it.replies, seq)) }
}

@Serializable
internal data class FetchedMessage(val seq: Long, val body: String)

@Serializable
internal data class MessagesPage(val messages: List<FetchedMessage> = emptyList())

@Serializable
internal data class RepliesPage(val replies: List<InboxReply> = emptyList())

/** The inbox's other routes sit beside `/replies`, which is what the build is given. */
internal fun inboxRoute(replyUrl: String, route: String): String =
    replyUrl.trim().removeSuffix("/").removeSuffix("/replies") + route

/**
 * Pulls what's new from the inbox: the relay's signed messages, then the replies it has
 * stored. Messages are checked against the push key exactly as a push would be, so a
 * fetch can't put anything on the phone that a push couldn't.
 */
@LightJob("sync")
val sync: LightJobHandler = { ctx, _ ->
    openStores(ctx.filesDir)
    val keys = Pairing.keys.value
    val url = inboxUrl()
    if (keys == null || url.isEmpty()) {
        Log.w(TAG, "Can't sync: ${if (keys == null) "not paired" else "relay.inboxUrl not set"}")
        LightJobResult.Error()
    } else {
        try {
            withContext(Dispatchers.IO) {
                fetchMessages(url, keys)
                fetchReplies(url, keys.replyToken)
            }
            LightJobResult.Success()
        } catch (e: IOException) {
            Log.w(TAG, "Sync not finished: $e")
            LightJobResult.Retry
        } catch (e: Refused) {
            Log.w(TAG, "Sync refused: ${e.message}")
            if (e.status == 429 || e.status >= 500) LightJobResult.Retry else LightJobResult.Error()
        }
    }
}

private const val PAGE = 100

private suspend fun fetchMessages(replyUrl: String, keys: PairingKeys) {
    while (true) {
        val after = SyncCursor.state.value.messages
        val page = json.decodeFromString(
            MessagesPage.serializer(),
            get(inboxRoute(replyUrl, "/messages?after=$after&limit=$PAGE"), keys.replyToken),
        ).messages
        for (fetched in page) {
            val message = PushCodec.decode(fetched.body.toByteArray(), keys.pushKey)
            if (message == null) {
                // The relay signs everything with one key, so this means it doesn't have ours
                // yet (just paired, or New keys). Stay here: once it restarts with the new key
                // it re-signs what it keeps, and the next sync picks up from this message.
                Log.w(TAG, "Fetched message ${fetched.seq} failed signature check; is PUSH_KEY on the relay current?")
                return
            }
            if (RelayStore.add(message.copy(receivedAt = parseInstant(message.sentAt) ?: message.receivedAt))) {
                Log.i(TAG, "Fetched ${message.id}")
            }
            SyncCursor.messagesUpTo(fetched.seq)
        }
        if (page.size < PAGE) return
    }
}

private suspend fun fetchReplies(replyUrl: String, token: String) {
    while (true) {
        val after = SyncCursor.state.value.replies
        val page = json.decodeFromString(
            RepliesPage.serializer(),
            get(inboxRoute(replyUrl, "/replies?after=$after&limit=$PAGE"), token),
        ).replies
        if (page.isEmpty()) return
        RelayStore.mergeReplies(page)
        SyncCursor.repliesUpTo(page.last().seq)
        if (page.size < PAGE) return
    }
}

private class Refused(val status: Int) : Exception("HTTP $status")

private fun get(url: String, token: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        val status = conn.responseCode
        if (status !in 200..299) throw Refused(status)
        return conn.inputStream.use { it.readBytes().decodeToString() }
    } finally {
        conn.disconnect()
    }
}
