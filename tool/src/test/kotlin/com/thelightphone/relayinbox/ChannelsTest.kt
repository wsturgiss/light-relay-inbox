package com.thelightphone.relayinbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelsTest {
    private val agents = listOf("Huginn", "Jarvis", "Marcus Aurelius")

    private fun fromAgent(id: String, at: Long, from: String? = null, thread: String? = null, headline: String = "msg $id") =
        RelayMessage(id = id, headline = headline, from = from, thread = thread, receivedAt = at)

    private fun started(id: String, at: Long, to: String?) =
        RelayMessage(id = id, headline = "start $id", mine = true, to = to, receivedAt = at, read = true)

    @Test
    fun `each agent gets one channel with everything to and from it`() {
        val messages = listOf(
            fromAgent("m_1", 10, from = "Jarvis"),
            started("t_1", 20, to = "jarvis"),                     // the To spelling doesn't matter
            fromAgent("m_2", 30, thread = "t_1"),                  // an answer there, from before names
            fromAgent("m_3", 40, from = "Marcus Aurelius"),
            fromAgent("m_4", 50),                                  // nobody named: the default agent
        )
        val byAgent = channels(messages, agents).associate { it.agent to it.messages.map { m -> m.id } }
        assertEquals<Map<String?, List<String>>>(mapOf("Huginn" to listOf("m_4"), "Marcus Aurelius" to listOf("m_3"),
            "Jarvis" to listOf("m_1", "t_1", "m_2")), byAgent)
    }

    @Test
    fun `channels are most recently active first and keyed by name`() {
        val list = channels(listOf(fromAgent("m_1", 10, from = "Jarvis"), fromAgent("m_2", 20, from = "Huginn")), agents)
        assertEquals(listOf("@Huginn", "@Jarvis"), list.map { it.id })
    }

    @Test
    fun `the subject is the latest topic until you rename it`() {
        val channel = channels(listOf(
            fromAgent("m_1", 10, from = "Jarvis", headline = "Emulator is up"),
            started("t_1", 20, to = "Jarvis"),
        ), agents).single()
        assertEquals("Emulator is up", channel.autoSubject())
        assertEquals("Light SDK", channel.subject(mapOf("@Jarvis" to "Light SDK")))
        assertEquals("start t_1", channels(listOf(started("t_1", 20, to = "Jarvis")), agents).single().autoSubject())
    }

    @Test
    fun `a fresh voice note waits for its transcript, an old one doesn't`() {
        val note = Attachment(id = "a_1", type = "audio/mp4", uploaded = true)
        val reply = Reply(id = "r_1", at = 1_000_000, state = ReplyState.Sent, attachments = listOf(note))
        assertTrue(note.awaitingTranscript(reply, now = 1_060_000))
        assertFalse(note.awaitingTranscript(reply, now = 1_000_000 + 11 * 60_000))
        assertFalse(note.copy(transcript = "").awaitingTranscript(reply, now = 1_060_000))
        assertFalse(note.awaitingTranscript(reply.copy(state = ReplyState.Pending), now = 1_060_000))
    }

    @Test
    fun `a push names its sender`() {
        // Produced by light-relay's lightrelay.relay.sign() with key "test-push-key".
        val signed = """v1.3d2afb4cfdd3d9ec4c7d3cefd0f5e993e6dab714ef7ab106901b00eec03e7207.{"v":1,"id":"m_00000000000000aa","at":"2026-10-17T03:00:00Z","headline":"Hi","detail":"","choices":[],"from":"Marcus Aurelius"}"""
        assertEquals("Marcus Aurelius", assertNotNull(PushCodec.decode(signed.toByteArray(), "test-push-key")).from)
    }
}
