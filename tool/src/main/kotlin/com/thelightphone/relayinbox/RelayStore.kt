package com.thelightphone.relayinbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

@Serializable
data class RelayMessage(
    val id: String,
    val headline: String,
    val detail: String = "",
    val choices: List<String> = emptyList(),
    /** The first message of the conversation this continues; null when it starts one. */
    val thread: String? = null,
    /**
     * Started on this phone, not sent by the agent: the start of a conversation you began.
     * Its id is the conversation's (`t_…`), [headline] is the title, and its replies are
     * what you wrote before the agent answered.
     */
    val mine: Boolean = false,
    /** When the relay sent it, ISO-8601 UTC, as the relay stamped it. */
    val sentAt: String = "",
    /** When the phone received it, epoch millis. Fetched history uses [sentAt] instead. */
    val receivedAt: Long,
    val read: Boolean = false,
    val replies: List<Reply> = emptyList(),
)

/** The conversation this message is part of: the first message's id. */
val RelayMessage.threadId: String get() = thread ?: id

/** Waiting on you: it offers choices and you haven't answered. */
val RelayMessage.needsAnswer: Boolean
    get() = choices.isNotEmpty() && replies.none { it.choice != null && it.state != ReplyState.Failed }

/** One conversation, oldest message first. */
data class Conversation(val id: String, val messages: List<RelayMessage>) {
    val latest: RelayMessage get() = messages.last()
    val lastActivity: Long get() = messages.maxOf { m -> maxOf(m.receivedAt, m.replies.maxOfOrNull { it.at } ?: 0) }
    val unread: Boolean get() = messages.any { !it.read }
    val needsAnswer: Boolean get() = messages.any { it.needsAnswer }
    val lastReply: Reply? get() = messages.flatMap { it.replies }.maxByOrNull { it.at }
}

/**
 * Conversations you've archived, by id, with when. Kept on this phone only. A conversation
 * stays archived until something new happens in it (a message, or a reply of yours); then it's
 * back in the inbox, so nothing new is ever hidden.
 */
internal object Archive : JsonFileState<Map<String, Long>>(
    fileName = "archive.json",
    serializer = MapSerializer(String.serializer(), Long.serializer()),
    empty = emptyMap(),
) {
    suspend fun archive(id: String, now: Long = System.currentTimeMillis()) = update { it + (id to now) }

    suspend fun restore(id: String) = update { it - id }
}

/** Archived, and nothing has happened in it since. [archive] is [Archive]'s state. */
internal fun Conversation.isArchived(archive: Map<String, Long>): Boolean =
    archive[id]?.let { it >= lastActivity } == true

/** Messages grouped by conversation, most recently active first. */
internal fun conversations(messages: List<RelayMessage>): List<Conversation> =
    messages.groupBy { it.threadId }
        .map { (id, ms) -> Conversation(id, ms.sortedBy { it.receivedAt }) }
        .sortedByDescending { it.lastActivity }

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

    /** Starts a conversation from the phone: [text]'s first line is the title. Returns its reply, queued to send. */
    suspend fun startConversation(text: String, now: Long = System.currentTimeMillis()): RelayMessage {
        val (title, _) = splitTitle(text)
        val message = RelayMessage(
            id = "t_" + randomHex(8),
            headline = title,
            mine = true,
            receivedAt = now,
            read = true,
            replies = listOf(Reply(id = "r_" + randomHex(8), text = text, at = now)),
        )
        add(message)
        return message
    }

    suspend fun markThreadRead(threadId: String) = update { list ->
        list.map { if (it.threadId == threadId && !it.read) it.copy(read = true) else it }
    }

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
    // A conversation you started is filed under its thread; everything else under its message.
    val byMessage = fromInbox.groupBy { it.messageId ?: it.thread }
    // Starts that this phone no longer has (a reinstall) come back as the conversation's root.
    val restoredStarts = fromInbox
        .filter { it.messageId == null && it.thread != null && it.title != null && list.none { m -> m.id == it.thread } }
        .distinctBy { it.thread }
        .map { RelayMessage(id = it.thread!!, headline = it.title!!, mine = true, receivedAt = it.sentAtMillis(), read = true) }
    return (list + restoredStarts).map { m ->
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
    /** Set, with [title], on what you wrote in a conversation you started. */
    val thread: String? = null,
    val title: String? = null,
    val choice: String? = null,
    val text: String? = null,
    val sentAt: String? = null,
    val receivedAt: String = "",
) {
    fun sentAtMillis(): Long = parseInstant(sentAt) ?: parseInstant(receivedAt) ?: 0
}

/**
 * A conversation's title is the first line you wrote. A single line is its own title,
 * cut at a word near 40 characters. Returns the title and the full text.
 */
internal fun splitTitle(text: String): Pair<String, String> {
    val trimmed = text.trim()
    val firstLine = trimmed.lineSequence().first().trim()
    val title = when {
        firstLine.length <= 40 -> firstLine
        else -> firstLine.take(40).substringBeforeLast(' ').ifBlank { firstLine.take(40) } + "…"
    }
    return title.take(120) to trimmed
}

internal fun randomHex(bytes: Int): String =
    ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

internal fun parseInstant(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
