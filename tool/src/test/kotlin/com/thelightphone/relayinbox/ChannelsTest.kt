package com.thelightphone.relayinbox

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChannelsTest {
    private val agents = listOf("Huginn", "Jarvis", "Marcus Aurelius")

    private fun fromAgent(id: String, at: Long, from: String? = null, headline: String = "msg $id") =
        RelayMessage(id = id, headline = headline, from = from, receivedAt = at)

    private fun started(id: String, at: Long, to: String?) =
        RelayMessage(id = id, headline = "start $id", mine = true, to = to, receivedAt = at, read = true)

    @Test
    fun `each agent gets one channel with everything to and from it`() {
        val messages = listOf(
            fromAgent("m_1", 10, from = "Jarvis"),
            started("t_1", 20, to = "jarvis"),                     // the To spelling doesn't matter
            fromAgent("m_2", 30),                                  // nobody named, wherever it was: the default agent
            fromAgent("m_3", 40, from = "Marcus Aurelius"),
            fromAgent("m_4", 50),
        )
        val byAgent = channels(messages, agents).associate { it.agent to it.messages.map { m -> m.id } }
        assertEquals<Map<String?, List<String>>>(mapOf("Huginn" to listOf("m_2", "m_4"), "Marcus Aurelius" to listOf("m_3"),
            "Jarvis" to listOf("m_1", "t_1")), byAgent)
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

    /** Oct 20: Jarvis's message, saved by a build from before `from`, was the newest in Huginn's channel. */
    private val misfiled = listOf(
        started("t_5c", 5, to = "Huginn"),
        fromAgent("m_huginn", 10),                                  // Huginn names no one: the default agent
        fromAgent("m_f29735", 20),                                  // Jarvis's, saved without its sender
        fromAgent("m_jarvis", 30, from = "Jarvis"),
    )

    @Test
    fun `what you write in Huginn's channel goes to Huginn, whatever its newest message is`() {
        val huginn = channels(misfiled, agents).single { it.agent == "Huginn" }
        assertEquals("m_f29735", huginn.latest.id)
        val body = json.parseToJsonElement(replyBody(huginn.latest, Reply(id = "r_1", text = "Helloooo", at = 40, to = huginn.replyTo(agents)))).jsonObject
        assertEquals("Huginn", body.getValue("to").jsonPrimitive.content)
        assertEquals(JsonNull, body.getValue("messageId"))
        assertFalse("thread" in body || "title" in body)
    }

    @Test
    fun `a tapped choice names its agent and the question it answers`() {
        val jarvis = channels(misfiled, agents).single { it.agent == "Jarvis" }
        val body = json.parseToJsonElement(replyBody(jarvis.latest, Reply(id = "r_1", choice = "Yes", choiceIds = listOf("c1"), at = 40, to = jarvis.replyTo(agents)))).jsonObject
        assertEquals("Jarvis", body.getValue("to").jsonPrimitive.content)
        assertEquals("m_jarvis", body.getValue("messageId").jsonPrimitive.content)
    }

    @Test
    fun `without agents a reply names no one, and one queued before names goes by its message`() {
        assertEquals(null, channels(listOf(fromAgent("m_1", 10))).single().replyTo(emptyList()))
        val body = json.parseToJsonElement(replyBody(misfiled.last(), Reply(id = "r_1", text = "Hi", at = 40))).jsonObject
        assertEquals("Jarvis", body.getValue("to").jsonPrimitive.content)
    }

    @Test
    fun `a message seen again picks up the sender it was saved without`() {
        val stored = misfiled.map { if (it.id == "m_f29735") it.copy(read = true, replies = listOf(Reply(id = "r_1", text = "Hi", at = 25))) else it }
        val list = withMessage(stored, fromAgent("m_f29735", 99, from = "Jarvis"), now = 100)
        val healed = list.single { it.id == "m_f29735" }
        assertEquals("Jarvis", healed.from)
        assertEquals(20, healed.receivedAt)
        assertTrue(healed.read)
        assertEquals(listOf("r_1"), healed.replies.map { it.id })
        val channels = channels(list, agents).associateBy { it.agent }
        assertEquals(listOf("m_f29735", "m_jarvis"), channels.getValue("Jarvis").messages.map { it.id })
        assertEquals("m_huginn", channels.getValue("Huginn").latest.id)
    }

    @Test
    fun `a new message is added newest first and a known one is otherwise left alone`() {
        val list = withMessage(misfiled, fromAgent("m_new", 40, from = "Jarvis"), now = 100)
        assertEquals(listOf("m_new", "m_jarvis", "m_f29735", "m_huginn", "t_5c"), list.map { it.id })
        assertEquals(list, withMessage(list, fromAgent("m_jarvis", 99, from = "Othala"), now = 100))
    }
}
