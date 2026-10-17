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
    /** For a conversation you started: the agent you picked under To, if the inbox offered any. */
    val to: String? = null,
    /** Which agent sent it, when the relay knows (the agent named itself with its token). */
    val from: String? = null,
    /** When the relay sent it, ISO-8601 UTC, as the relay stamped it. */
    val sentAt: String = "",
    /** When the phone received it, epoch millis. Fetched history uses [sentAt] instead. */
    val receivedAt: Long,
    val read: Boolean = false,
    val replies: List<Reply> = emptyList(),
    /** A picture the agent sent with it, shown under the text once downloaded (see Images.kt). */
    val image: MessageImage? = null,
    /** [choices] with ids and styles, when the agent gave them; else see [choiceOptions]. */
    val options: List<ChoiceOption>? = null,
    /** "many": pick any of the choices, then Done. Otherwise one tap answers. */
    val select: String? = null,
    /** Offers "Other…": a typed answer instead of a choice. */
    val other: Boolean = false,
)

/** One of the answers an agent offers. A "danger" one asks you to confirm. */
@Serializable
data class ChoiceOption(val id: String, val label: String, val style: String = "default") {
    val danger: Boolean get() = style == "danger"
}

/** The choices as options: the agent's, or its labels as c1, c2, … (as the relay numbers them). */
val RelayMessage.choiceOptions: List<ChoiceOption>
    get() = options?.takeIf { it.isNotEmpty() } ?: choices.mapIndexed { i, label -> ChoiceOption("c${i + 1}", label) }

val RelayMessage.multiSelect: Boolean get() = select == "many"

/** Your answer to its question: the first choice or "Other" you sent (or are sending). */
val RelayMessage.answer: Reply?
    get() = if (choices.isEmpty()) null
    else replies.firstOrNull { (it.choice != null || it.other) && it.state != ReplyState.Failed }

/** The ids of what you picked; for an answer from before ids, matched by label. */
val RelayMessage.pickedIds: Set<String>
    get() {
        val a = answer ?: return emptySet()
        a.choiceIds?.let { return it.toSet() }
        val choice = a.choice ?: return emptySet()
        val opts = choiceOptions
        opts.firstOrNull { it.label == choice }?.let { return setOf(it.id) }
        val parts = choice.split(", ").toSet()
        return opts.filter { it.label in parts }.map { it.id }.toSet()
    }

/**
 * A picture on an agent's message, as the signed message describes it. The file comes from
 * the inbox's `GET /images/<id>` and is kept only if it matches [sha256], so it is exactly
 * what the relay signed.
 */
@Serializable
data class MessageImage(
    /** `i_` and 16 hex digits. */
    val id: String,
    /** `image/png` or `image/jpeg`. */
    val type: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    /** Lower-case hex SHA-256 of the file. */
    val sha256: String,
)

/** The conversation this message is part of: the first message's id. */
val RelayMessage.threadId: String get() = thread ?: id

/** Waiting on you: it offers choices and you haven't answered. */
val RelayMessage.needsAnswer: Boolean
    get() = choices.isNotEmpty() && answer == null

/**
 * One conversation, oldest message first. A channel holds everything to and from one agent
 * ([agent]); a thread (from [conversations]) holds one exchange.
 */
data class Conversation(val id: String, val messages: List<RelayMessage>, val agent: String? = null) {
    val latest: RelayMessage get() = messages.last()
    val lastActivity: Long get() = messages.maxOf { m -> maxOf(m.receivedAt, m.replies.maxOfOrNull { it.at } ?: 0) }
    val unread: Boolean get() = messages.any { !it.read }
    val needsAnswer: Boolean get() = messages.any { it.needsAnswer }
    val lastReply: Reply? get() = messages.flatMap { it.replies }.maxByOrNull { it.at }

    /**
     * Who this conversation is waiting on, if anyone. On the agent when the last word is
     * yours (sent or still sending; a failed one is yours to resend). On you when the
     * agent offered choices you haven't answered.
     */
    val waiting: Waiting?
        get() {
            val reply = lastReply
            val fromAgent = messages.filterNot { it.mine }.maxOfOrNull { it.receivedAt } ?: 0
            return when {
                reply != null && reply.state != ReplyState.Failed && reply.at > fromAgent -> Waiting.OnAgent
                needsAnswer -> Waiting.OnYou
                else -> null
            }
        }
}

enum class Waiting { OnYou, OnAgent }

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

/** The agent a message is with: who sent it, else who the phone conversation it belongs to is for. */
internal fun agentOf(message: RelayMessage, byId: Map<String, RelayMessage>): String? =
    message.from
        ?: message.to?.takeIf { message.mine }
        ?: message.thread?.let { byId[it] }?.let { root -> root.to?.takeIf { root.mine } ?: root.from }

/** A channel's id: `@` and the agent's name. */
internal fun channelId(agent: String): String = "@$agent"

/**
 * One channel per agent, most recently active first: every message to and from that agent in
 * one long thread. Messages that don't say who they're with (from before agents had names)
 * go to [defaultAgent], the first in the inbox's list, which is where the relay sends their
 * replies too. Names match whatever the case; the spelling in [agents] wins.
 */
internal fun channels(messages: List<RelayMessage>, agents: List<String> = emptyList(), defaultAgent: String? = agents.firstOrNull()): List<Conversation> {
    val byId = messages.associateBy { it.id }
    fun canonical(name: String) = agents.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    return messages.groupBy { m -> (agentOf(m, byId) ?: defaultAgent)?.let(::canonical) ?: "Agent" }
        .map { (agent, ms) -> Conversation(channelId(agent), ms.sortedBy { it.receivedAt }, agent) }
        .sortedByDescending { it.lastActivity }
}

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
    /** The choices picked, by id; [choice] holds their labels. */
    val choiceIds: List<String>? = null,
    /** A typed answer to the question ("Other…"), in [text]. */
    val other: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
)

/**
 * A voice note or photo sent with a reply. The file waits in [path] until the reply is
 * sent, then goes; history fetched from the inbox never had one here.
 */
@Serializable
data class Attachment(
    /** `a_` and 16 hex digits. Chosen here, so an upload can be retried. */
    val id: String,
    /** `audio/mp4` or `image/jpeg`. */
    val type: String,
    val bytes: Long = 0,
    val durationMs: Long? = null,
    val path: String? = null,
    val uploaded: Boolean = false,
    /** A voice note's text, once the inbox has transcribed it. */
    val transcript: String? = null,
    /** Why the inbox couldn't transcribe it, such as "No transcription credit left". */
    val transcriptError: String? = null,
) {
    val isVoice: Boolean get() = type.startsWith("audio/")
}

/**
 * A voice note sent lately that the inbox hasn't transcribed yet (issue #14). Older ones
 * without text were from before transcripts, or the inbox has none for them.
 */
internal fun Attachment.awaitingTranscript(reply: Reply, now: Long = System.currentTimeMillis()): Boolean =
    isVoice && reply.state == ReplyState.Sent && transcript == null && transcriptError == null &&
        now - reply.at < 10 * 60_000

@Serializable
enum class ReplyState { Pending, Sent, Failed }

/** Who a new conversation can go to, from the inbox's `GET /agents`; the default first. */
internal object Agents : JsonFileState<List<String>>(
    fileName = "agents.json",
    serializer = ListSerializer(String.serializer()),
    empty = emptyList(),
) {
    suspend fun set(names: List<String>) = update { names }
}

/**
 * Subjects you gave channels, by channel id. Kept on this phone only. A channel without one
 * shows its latest topic ([autoSubject]).
 */
internal object Subjects : JsonFileState<Map<String, String>>(
    fileName = "subjects.json",
    serializer = MapSerializer(String.serializer(), String.serializer()),
    empty = emptyMap(),
) {
    /** Blank clears it, back to the automatic subject. */
    suspend fun rename(id: String, subject: String) = update {
        val trimmed = subject.trim().take(80)
        if (trimmed.isEmpty()) it - id else it + (id to trimmed)
    }
}

/**
 * A channel's subject when you haven't given it one: what it's about lately. The agent's
 * latest headline (agents write short ones), or the title of the conversation you started
 * if it hasn't answered yet.
 */
internal fun Conversation.autoSubject(): String =
    messages.lastOrNull { !it.mine }?.headline?.takeIf { it.isNotBlank() }
        ?: messages.lastOrNull()?.headline.orEmpty()

internal fun Conversation.subject(subjects: Map<String, String>): String = subjects[id] ?: autoSubject()

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
    suspend fun startConversation(text: String, to: String? = null, now: Long = System.currentTimeMillis()): RelayMessage {
        val (title, _) = splitTitle(text)
        val message = RelayMessage(
            id = "t_" + randomHex(8),
            headline = title,
            mine = true,
            to = to,
            receivedAt = now,
            read = true,
            replies = listOf(Reply(id = "r_" + randomHex(8), text = text, at = now)),
        )
        add(message)
        return message
    }

    suspend fun markRead(ids: Set<String>) = update { list ->
        if (list.none { it.id in ids && !it.read }) list
        else list.map { if (it.id in ids && !it.read) it.copy(read = true) else it }
    }

    suspend fun markThreadRead(threadId: String) = update { list ->
        list.map { if (it.threadId == threadId && !it.read) it.copy(read = true) else it }
    }

    suspend fun addReply(messageId: String, reply: Reply) =
        updateMessage(messageId) { it.copy(replies = it.replies + reply) }

    suspend fun setReplyState(replyId: String, state: ReplyState) = updateReply(replyId) { it.copy(state = state) }

    suspend fun markUploaded(replyId: String, attachmentId: String) = updateReply(replyId) { r ->
        r.copy(attachments = r.attachments.map { if (it.id == attachmentId) it.copy(uploaded = true) else it })
    }

    /** Sent: the inbox has the files, so this phone lets them go. */
    suspend fun replySent(replyId: String) = updateReply(replyId) { r ->
        r.attachments.forEach { a -> a.path?.let { java.io.File(it).delete() } }
        r.copy(state = ReplyState.Sent, attachments = r.attachments.map { it.copy(path = null) })
    }

    /** Voice notes' text, or why there is none, from the inbox. */
    suspend fun addTranscripts(transcripts: List<FetchedTranscript>) = update { withTranscripts(it, transcripts) }

    private suspend fun updateReply(replyId: String, transform: (Reply) -> Reply) = update { list ->
        list.map { m ->
            if (m.replies.none { it.id == replyId }) m
            else m.copy(replies = m.replies.map { if (it.id == replyId) transform(it) else it })
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
        .map { RelayMessage(id = it.thread!!, headline = it.title!!, mine = true, to = it.to, receivedAt = it.sentAtMillis(), read = true) }
    return (list + restoredStarts).map { m ->
        val incoming = byMessage[m.id].orEmpty()
        if (incoming.isEmpty()) return@map m
        val stored = incoming.map { it.id }.toSet()
        val known = m.replies.map { it.id }.toSet()
        val updated = m.replies.map { if (it.id in stored && it.state != ReplyState.Sent) it.copy(state = ReplyState.Sent) else it }
        val restored = incoming.filter { it.id !in known }.map {
            Reply(id = it.id, choice = it.choice, text = it.text, at = it.sentAtMillis(), state = ReplyState.Sent,
                choiceIds = it.choiceIds, other = it.other,
                attachments = it.attachments.map { a ->
                    Attachment(id = a.id, type = a.type, bytes = a.bytes, uploaded = true,
                        transcript = a.transcript, transcriptError = a.transcriptError)
                })
        }
        m.copy(read = m.read || restored.isNotEmpty(), replies = (updated + restored).sortedBy { it.at })
    }
}

/**
 * Puts each transcript, or its error, on its voice note, wherever that is. The later of two
 * for one note wins: text after an error once the inbox retries. Unknown ids are skipped.
 */
internal fun withTranscripts(list: List<RelayMessage>, transcripts: List<FetchedTranscript>): List<RelayMessage> {
    val byId = transcripts.associateBy { it.id }
    return list.map { m ->
        if (m.replies.none { r -> r.attachments.any { it.id in byId } }) m
        else m.copy(replies = m.replies.map { r ->
            r.copy(attachments = r.attachments.map { a ->
                byId[a.id]?.let { t -> a.copy(transcript = t.text.takeIf { t.error == null }, transcriptError = t.error) } ?: a
            })
        })
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
    /** On a conversation you started: who it was for. */
    val to: String? = null,
    val attachments: List<InboxAttachment> = emptyList(),
    val choiceIds: List<String>? = null,
    val other: Boolean = false,
) {
    fun sentAtMillis(): Long = parseInstant(sentAt) ?: parseInstant(receivedAt) ?: 0
}

@Serializable
data class InboxAttachment(
    val id: String,
    val type: String,
    val bytes: Long = 0,
    val transcript: String? = null,
    val transcriptError: String? = null,
)

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
