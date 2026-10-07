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
    fun `follow-ups group under the first message, busiest conversation first`() {
        val muse = message("m1", 10, Reply(id = "r1", choice = "Got it", at = 12))
        val followUp = message("m2", 20, Reply(id = "r2", choice = "Nice", at = 25)).copy(thread = "m1")
        val other = message("m3", 15).copy(choices = listOf("Yes", "No"))
        val threads = conversations(listOf(other, followUp, muse))
        assertEquals(listOf("m1", "m3"), threads.map { it.id })
        assertEquals(listOf("m1", "m2"), threads[0].messages.map { it.id })
        assertEquals(25, threads[0].lastActivity)
        assertEquals("r2", threads[0].lastReply?.id)
        assertTrue(threads[1].needsAnswer)
    }

    @Test
    fun `a conversation's title is your first line`() {
        assertEquals("Plan Saturday" to "Plan Saturday\nHike or the market?", splitTitle("  Plan Saturday\nHike or the market?  "))
        assertEquals("Can you check whether the 9:40 train…", splitTitle("Can you check whether the 9:40 train still runs on Sundays?").first)
        assertEquals("Short one", splitTitle("Short one").first)
    }

    @Test
    fun `a conversation you started comes back after a reinstall, with the agent's answer under it`() {
        val start = InboxReply(seq = 9, id = "r9", thread = "t_8c1f0a2b3d4e5f60", title = "Plan Saturday",
            text = "Plan Saturday\nHike or the market?", sentAt = "2026-10-01T09:30:00Z")
        val answer = message("m4", 1_790_847_100_000).copy(thread = "t_8c1f0a2b3d4e5f60", headline = "Hike")
        val restored = withInboxReplies(listOf(answer), listOf(start))
        val root = assertNotNull(restored.firstOrNull { it.id == "t_8c1f0a2b3d4e5f60" })
        assertTrue(root.mine)
        assertEquals("Plan Saturday", root.headline)
        assertEquals(listOf("r9"), root.replies.map { it.id })
        assertEquals(ReplyState.Sent, root.replies.single().state)

        val conversation = conversations(restored).single()
        assertEquals(listOf("t_8c1f0a2b3d4e5f60", "m4"), conversation.messages.map { it.id })
    }

    @Test
    fun `an archived conversation comes back when something new happens in it`() {
        val quiet = conversations(listOf(message("m1", 10, Reply(id = "r1", choice = "Yes", at = 20)))).single()
        assertTrue(quiet.isArchived(mapOf("m1" to 30)))
        assertTrue(!quiet.isArchived(emptyMap()))

        val busier = conversations(listOf(
            message("m1", 10, Reply(id = "r1", choice = "Yes", at = 20)),
            message("m2", 40).copy(thread = "m1"),
        )).single()
        assertTrue(!busier.isArchived(mapOf("m1" to 30)))
    }
}
