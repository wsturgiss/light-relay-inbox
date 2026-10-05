package com.thelightphone.relayinbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

@Serializable
data class RelayMessage(
    val id: String,
    val headline: String,
    val detail: String = "",
    val choices: List<String> = emptyList(),
    /** When the relay sent it, ISO-8601 UTC, as the relay stamped it. */
    val sentAt: String = "",
    /** When the phone received it, epoch millis. Fetched history uses [sentAt] instead. */
    val receivedAt: Long,
    val read: Boolean = false,
    val replies: List<Reply> = emptyList(),
)

@Serializable
data class Reply(
    val id: String,
    val choice: String? = null,
    val text: String? = null,
    val at: Long,
    val state: ReplyState = ReplyState.Pending,
)

@Serializable
enum class ReplyState { Pending, Sent, Failed }

/** The conversation, newest first. Kept as long as the relay keeps it. */
internal object RelayStore : JsonFileState<List<RelayMessage>>(
    fileName = "messages.json",
    serializer = ListSerializer(RelayMessage.serializer()),
    empty = emptyList(),
) {
    private const val KEEP = 1000
    private const val KEEP_DAYS = 90L

    /** Adds a pushed or fetched message. False if it was already here (pushed and fetched, or redelivered). */
    suspend fun add(message: RelayMessage): Boolean {
        var added = false
        update { list ->
            if (list.any { it.id == message.id }) {
                list
            } else {
                added = true
                val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000
                (listOf(message) + list).filter { it.receivedAt >= cutoff }.sortedByDescending { it.receivedAt }.take(KEEP)
            }
        }
        return added
    }

    /** See [withInboxReplies]. */
    suspend fun mergeReplies(fromInbox: List<InboxReply>) = update { withInboxReplies(it, fromInbox) }

    suspend fun markRead(id: String) = updateMessage(id) { it.copy(read = true) }

    suspend fun addReply(messageId: String, reply: Reply) =
        updateMessage(messageId) { it.copy(replies = it.replies + reply) }

    suspend fun setReplyState(replyId: String, state: ReplyState) = update { list ->
        list.map { m ->
            if (m.replies.none { it.id == replyId }) m
            else m.copy(replies = m.replies.map { if (it.id == replyId) it.copy(state = state) else it })
        }
    }

    /** Failed replies go back in the queue. */
    suspend fun retryFailed() = update { list ->
        list.map { m ->
            m.copy(replies = m.replies.map { if (it.state == ReplyState.Failed) it.copy(state = ReplyState.Pending) else it })
        }
    }

    fun pending(): List<Pair<RelayMessage, Reply>> =
        state.value.flatMap { m -> m.replies.filter { it.state == ReplyState.Pending }.map { m to it } }

    private suspend fun updateMessage(id: String, transform: (RelayMessage) -> RelayMessage) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }
}

/**
 * Replies as the inbox stored them. One already here was sent from this phone, and the
 * inbox having it means it's [ReplyState.Sent]. One that isn't is history from before a
 * reinstall: it goes back under its message, which then counts as read. Replies to a
 * message this phone no longer has are dropped.
 */
internal fun withInboxReplies(list: List<RelayMessage>, fromInbox: List<InboxReply>): List<RelayMessage> {
    val byMessage = fromInbox.groupBy { it.messageId }
    return list.map { m ->
        val incoming = byMessage[m.id].orEmpty()
        if (incoming.isEmpty()) return@map m
        val stored = incoming.map { it.id }.toSet()
        val known = m.replies.map { it.id }.toSet()
        val updated = m.replies.map { if (it.id in stored && it.state != ReplyState.Sent) it.copy(state = ReplyState.Sent) else it }
        val restored = incoming.filter { it.id !in known }.map {
            Reply(id = it.id, choice = it.choice, text = it.text, at = it.sentAtMillis(), state = ReplyState.Sent)
        }
        m.copy(read = m.read || restored.isNotEmpty(), replies = (updated + restored).sortedBy { it.at })
    }
}

/** A reply as `GET /replies` on the inbox returns it. */
@Serializable
data class InboxReply(
    val seq: Long,
    val id: String,
    val messageId: String? = null,
    val choice: String? = null,
    val text: String? = null,
    val sentAt: String? = null,
    val receivedAt: String = "",
) {
    fun sentAtMillis(): Long = parseInstant(sentAt) ?: parseInstant(receivedAt) ?: 0
}

internal fun parseInstant(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
