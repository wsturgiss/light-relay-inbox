package com.thelightphone.relayinbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    // A GET /messages response as light-relay's inbox writes it, signed with key "test-push-key".
    // The inbox's JSON escapes the em dash; the signature covers the UTF-8 it stands for.
    private val messagesPage = """{"messages": [{"seq": 7, "body": "v1.f83a8f52ef6433421dcc98a9f5da6abe03f034556c844edb50d498ca8e5ed4d5.""" +
        """{\"v\":1,\"id\":\"m_00000000000000aa\",\"at\":\"2026-10-01T09:30:00Z\",\"headline\":\"Book the 9:40?\",""" +
        """\"detail\":\"Holds expire at noon — reply by 11.\",\"choices\":[\"Yes\",\"No\"]}"}]}"""

    @Test
    fun `a fetched message checks out like a push`() {
        val fetched = json.decodeFromString(MessagesPage.serializer(), messagesPage).messages.single()
        assertEquals(7, fetched.seq)
        val message = assertNotNull(PushCodec.decode(fetched.body.toByteArray(), "test-push-key"))
        assertEquals("m_00000000000000aa", message.id)
        assertEquals("Holds expire at noon — reply by 11.", message.detail)
        assertEquals(1_790_847_000_000, parseInstant(message.sentAt))
    }

    @Test
    fun `a fetched message under another key is refused`() {
        val fetched = json.decodeFromString(MessagesPage.serializer(), messagesPage).messages.single()
        assertNull(PushCodec.decode(fetched.body.toByteArray(), "an-old-key"))
    }

    @Test
    fun `inbox routes sit beside the reply url`() {
        val base = "https://box.example.ts.net:8443"
        assertEquals("$base/messages?after=0", inboxRoute("$base/replies", "/messages?after=0"))
        assertEquals("$base/replies?after=3", inboxRoute("$base/replies/ ", "/replies?after=3"))
    }

    private fun message(id: String, at: Long, vararg replies: Reply) =
        RelayMessage(id = id, headline = "h-$id", receivedAt = at, replies = replies.toList())

    private fun inbox(seq: Long, id: String, messageId: String, at: String) =
        InboxReply(seq = seq, id = id, messageId = messageId, choice = "Yes", sentAt = at)

    @Test
    fun `a reply the inbox has is sent`() {
        val pending = Reply(id = "r1", choice = "Yes", at = 10, state = ReplyState.Pending)
        val merged = withInboxReplies(listOf(message("m1", 1, pending)), listOf(inbox(1, "r1", "m1", "2026-10-01T09:30:00Z")))
        assertEquals(listOf(pending.copy(state = ReplyState.Sent)), merged.single().replies)
    }

    @Test
    fun `replies from before a reinstall come back under their message`() {
        val merged = withInboxReplies(
            listOf(message("m1", 1), message("m2", 2)),
            listOf(inbox(1, "r1", "m1", "2026-10-01T09:30:00Z"), inbox(2, "r2", "gone", "2026-10-01T09:31:00Z")),
        )
        val m1 = merged.first { it.id == "m1" }
        assertEquals(listOf("r1"), m1.replies.map { it.id })
        assertEquals(1_790_847_000_000, m1.replies.single().at)
        assertTrue(m1.read)
        assertEquals(emptyList(), merged.first { it.id == "m2" }.replies)
    }

    @Test
    fun `the conversation runs in order and marks answers to older messages`() {
        val messages = listOf(
            message("m2", 20, Reply(id = "r2", text = "late answer", at = 30)),
            message("m1", 10, Reply(id = "r1", choice = "Yes", at = 15)),
        )
        val turns = conversation(messages)
        assertEquals(
            listOf("m1", "r1", "m2", "r2"),
            turns.map { if (it is Turn.Answered) it.reply.id else (it as Turn.Sent).message.id },
        )
        // r1 follows m1 directly; r2 follows m2 directly.
        assertEquals(listOf(null, null), turns.filterIsInstance<Turn.Answered>().map { it.context })

        val interleaved = conversation(
            listOf(message("m2", 20), message("m1", 10, Reply(id = "r1", choice = "Yes", at = 25))),
        )
        assertEquals("h-m1", (interleaved.last() as Turn.Answered).context)
    }
}
