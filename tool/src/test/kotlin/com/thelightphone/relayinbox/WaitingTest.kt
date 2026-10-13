package com.thelightphone.relayinbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaitingTest {
    private fun agent(at: Long, choices: List<String> = emptyList(), vararg replies: Reply) =
        RelayMessage(id = "m_$at", headline = "From the agent", choices = choices, receivedAt = at, replies = replies.toList())

    private fun reply(at: Long, choice: String? = null, text: String? = null, state: ReplyState = ReplyState.Sent) =
        Reply(id = "r_$at", choice = choice, text = text, at = at, state = state)

    private fun conversation(vararg messages: RelayMessage) = Conversation("c", messages.toList())

    @Test
    fun `unanswered choices wait on you`() {
        assertEquals(Waiting.OnYou, conversation(agent(1, listOf("Yes", "No"))).waiting)
    }

    @Test
    fun `a plain message waits on no one`() {
        assertNull(conversation(agent(1)).waiting)
    }

    @Test
    fun `your reply waits on the agent until it answers`() {
        val asked = agent(1, listOf("Yes", "No"), reply(2, choice = "Yes"))
        assertEquals(Waiting.OnAgent, conversation(asked).waiting)
        assertNull(conversation(asked, agent(3)).waiting)
    }

    @Test
    fun `typing instead of choosing still hands it to the agent`() {
        val asked = agent(1, listOf("Yes", "No"), reply(2, text = "Only if it's before ten"))
        assertEquals(Waiting.OnAgent, conversation(asked).waiting)
    }

    @Test
    fun `a conversation you started waits on the agent while sending`() {
        val started = RelayMessage(id = "t_0123456789abcdef", headline = "Plan Saturday", mine = true, receivedAt = 1,
            read = true, replies = listOf(reply(1, text = "Plan Saturday", state = ReplyState.Pending)))
        assertEquals(Waiting.OnAgent, conversation(started).waiting)
    }

    @Test
    fun `a reply that failed to send is yours to deal with`() {
        val asked = agent(1, listOf("Yes", "No"), reply(2, choice = "Yes", state = ReplyState.Failed))
        assertEquals(Waiting.OnYou, conversation(asked).waiting)
        assertNull(conversation(agent(1, emptyList(), reply(2, text = "hi", state = ReplyState.Failed))).waiting)
    }
}
