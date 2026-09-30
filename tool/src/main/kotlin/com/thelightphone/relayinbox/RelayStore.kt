package com.thelightphone.relayinbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class RelayMessage(
    val id: String,
    val headline: String,
    val detail: String = "",
    val choices: List<String> = emptyList(),
    /** When the relay sent it, ISO-8601 UTC, as the relay stamped it. */
    val sentAt: String = "",
    /** When the phone received it, epoch millis. */
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

/** The inbox, newest first. */
internal object RelayStore : JsonFileState<List<RelayMessage>>(
    fileName = "messages.json",
    serializer = ListSerializer(RelayMessage.serializer()),
    empty = emptyList(),
) {
    private const val KEEP = 200

    /** Adds a pushed message. False if it was already here (a redelivered push). */
    suspend fun add(message: RelayMessage): Boolean {
        var added = false
        update { list ->
            if (list.any { it.id == message.id }) {
                list
            } else {
                added = true
                (listOf(message) + list).sortedByDescending { it.receivedAt }.take(KEEP)
            }
        }
        return added
    }

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
