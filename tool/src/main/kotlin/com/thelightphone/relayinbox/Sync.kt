package com.thelightphone.relayinbox

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
data class SyncState(val messages: Long = 0, val replies: Long = 0, val transcripts: Long = 0)

internal object SyncCursor : JsonFileState<SyncState>(
    fileName = "sync.json",
    serializer = SyncState.serializer(),
    empty = SyncState(),
) {
    // max(): a one-time and a periodic run can overlap, and the cursor never goes back.
    suspend fun messagesUpTo(seq: Long) = update { it.copy(messages = maxOf(it.messages, seq)) }

    suspend fun repliesUpTo(seq: Long) = update { it.copy(replies = maxOf(it.replies, seq)) }

    suspend fun transcriptsUpTo(seq: Long) = update { it.copy(transcripts = maxOf(it.transcripts, seq)) }
}

@Serializable
internal data class FetchedMessage(val seq: Long, val body: String)

@Serializable
internal data class MessagesPage(val messages: List<FetchedMessage> = emptyList())

@Serializable
internal data class RepliesPage(val replies: List<InboxReply> = emptyList())

@Serializable
internal data class AgentsPage(val agents: List<String> = emptyList())

@Serializable
internal data class FetchedTranscript(val seq: Long, val id: String, val text: String = "", val error: String? = null)

@Serializable
internal data class TranscriptsPage(val transcripts: List<FetchedTranscript> = emptyList())

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
    when (syncOnce(ctx.filesDir)) {
        SyncOutcome.Done -> LightJobResult.Success()
        SyncOutcome.TryAgain -> LightJobResult.Retry
        SyncOutcome.CantSync -> LightJobResult.Error()
        // Away from home without a session: the next periodic run tries the LAN again.
        SyncOutcome.NeedsHome -> LightJobResult.Success()
    }
}

internal enum class SyncOutcome { Done, TryAgain, CantSync, NeedsHome }

/** One sync at a time: the job, the inbox and an open conversation all ask, often at once. */
private val syncing = Mutex()

/** A sync this soon after the last one adds nothing, and each costs the inbox's per-minute allowance. */
private const val MIN_GAP_MS = 10_000L

/** The agents list rarely changes: fetched on the first sync and hourly after. */
private const val AGENTS_EVERY_MS = 60 * 60_000L

@Volatile
private var lastSynced = 0L

@Volatile
private var agentsFetched = 0L

/**
 * One fetch of messages, replies and transcripts. The job runs it, and so does an open
 * screen. Asked again while one is running, or within [MIN_GAP_MS] of the last, it does
 * nothing: overlapping syncs used to spend the inbox's 20 requests a minute, and a reply
 * sent then was refused and waited out a 30-second backoff.
 */
internal suspend fun syncOnce(filesDir: File): SyncOutcome = syncing.withLock {
    if (System.currentTimeMillis() - lastSynced < MIN_GAP_MS) return@withLock SyncOutcome.Done
    syncNow(filesDir).also { if (it == SyncOutcome.Done) lastSynced = System.currentTimeMillis() }
}

private suspend fun syncNow(filesDir: File): SyncOutcome {
    openStores(filesDir)
    val keys = Pairing.keys.value
    val url = inboxUrl()
    if (keys == null || url.isEmpty()) {
        Log.w(TAG, "Can't sync: ${if (keys == null) "not paired" else "relay.inboxUrl not set"}")
        return SyncOutcome.CantSync
    }
    Session.renewIfDue(keys.replyToken)
    val bearer = inboxBearer(keys) ?: return SyncOutcome.NeedsHome
    return try {
        withContext(Dispatchers.IO) {
            fetchMessages(url, keys, bearer)
            fetchReplies(url, bearer)
            fetchTranscripts(url, bearer)
            fetchImages(filesDir, url, bearer)
            if (System.currentTimeMillis() - agentsFetched > AGENTS_EVERY_MS || Agents.state.value.isEmpty()) {
                fetchAgents(url, bearer)
                agentsFetched = System.currentTimeMillis()
            }
        }
        SyncOutcome.Done
    } catch (e: IOException) {
        Log.w(TAG, "Sync not finished: $e")
        SyncOutcome.TryAgain
    } catch (e: Refused) {
        Log.w(TAG, "Sync refused: ${e.message}")
        if (e.status == 401 && Session.enabled) {
            Session.drop()
            return SyncOutcome.NeedsHome
        }
        if (e.status == 429 || e.status >= 500) SyncOutcome.TryAgain else SyncOutcome.CantSync
    }
}

/** How often an open screen checks in. Three small requests each time; the inbox allows 20 a minute. */
private val LIVE_REFRESH = 20.seconds

/**
 * Checks for new messages every [LIVE_REFRESH] while a screen is on show, so a reply
 * turns up in an open conversation without leaving it. Start it from `onScreenShow`
 * (which also runs when the tool comes back to the front) and stop it from
 * `onScreenHide` and `onAppPause`; the 15-minute job covers the rest.
 */
internal class LiveRefresh(private val scope: CoroutineScope, private val filesDir: File) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (syncOnce(filesDir) == SyncOutcome.CantSync) return@launch
                delay(LIVE_REFRESH)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private const val PAGE = 100

private suspend fun fetchMessages(replyUrl: String, keys: PairingKeys, bearer: String) {
    while (true) {
        val after = SyncCursor.state.value.messages
        val page = json.decodeFromString(
            MessagesPage.serializer(),
            get(inboxRoute(replyUrl, "/messages?after=$after&limit=$PAGE"), bearer),
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

/** Who a new conversation can go to. An inbox from before agents (404) leaves the list as it was. */
private suspend fun fetchAgents(replyUrl: String, token: String) {
    val body = try {
        get(inboxRoute(replyUrl, "/agents"), token)
    } catch (e: Refused) {
        if (e.status == 404) return else throw e
    }
    Agents.set(json.decodeFromString(AgentsPage.serializer(), body).agents)
}

/** Voice notes' text, which the inbox adds a few seconds after the reply. */
private suspend fun fetchTranscripts(replyUrl: String, token: String) {
    while (true) {
        val after = SyncCursor.state.value.transcripts
        val body = try {
            get(inboxRoute(replyUrl, "/transcripts?after=$after&limit=$PAGE"), token)
        } catch (e: Refused) {
            // An inbox from before transcripts.
            if (e.status == 404) return else throw e
        }
        val page = json.decodeFromString(TranscriptsPage.serializer(), body).transcripts
        if (page.isEmpty()) return
        RelayStore.addTranscripts(page)
        SyncCursor.transcriptsUpTo(page.last().seq)
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
