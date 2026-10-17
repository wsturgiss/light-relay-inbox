package com.thelightphone.relayinbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionTest {
    // Produced by light-relay's sign() (question-upgrades) with key "test-push-key".
    private val signed = "v1.67d1a8ab2daa090714f1ea792cba7e67ce86610ab2ba6bbe8aa5422d7cea1f0d." +
        """{"v":1,"id":"m_00000000000000q1","at":"2026-10-17T12:00:00Z","headline":"Clean up?","detail":"",""" +
        """"choices":["Keep","Delete all"],"options":[{"id":"keep","label":"Keep","style":"default"},""" +
        """{"id":"del","label":"Delete all","style":"danger"}],"select":"many","other":true}"""

    private fun question(vararg replies: Reply, select: String? = null, other: Boolean = false, options: List<ChoiceOption>? = null) =
        RelayMessage(id = "m1", headline = "Q?", choices = listOf("Milk", "Eggs", "Bread"), receivedAt = 1,
            replies = replies.toList(), select = select, other = other, options = options)

    @Test
    fun `reads options, select and other from a signed message`() {
        val m = assertNotNull(PushCodec.decode(signed.toByteArray(), "test-push-key"))
        assertEquals(listOf("keep", "del"), m.choiceOptions.map { it.id })
        assertTrue(m.choiceOptions[1].danger)
        assertTrue(m.multiSelect)
        assertTrue(m.other)
    }

    @Test
    fun `plain choices number as the relay does`() {
        assertEquals(listOf("c1", "c2", "c3"), question().choiceOptions.map { it.id })
        assertFalse(question().choiceOptions.any { it.danger })
    }

    @Test
    fun `an answer locks the question and names the pick`() {
        val m = question(Reply(id = "r1", choice = "Eggs", choiceIds = listOf("c2"), at = 2))
        assertFalse(m.needsAnswer)
        assertEquals(setOf("c2"), m.pickedIds)
    }

    @Test
    fun `an answer from before ids is matched by label`() {
        assertEquals(setOf("c3"), question(Reply(id = "r1", choice = "Bread", at = 2)).pickedIds)
        assertEquals(setOf("c1", "c3"), question(Reply(id = "r1", choice = "Milk, Bread", at = 2), select = "many").pickedIds)
    }

    @Test
    fun `Other answers, plain text doesn't`() {
        assertTrue(question(Reply(id = "r1", text = "maybe", at = 2), other = true).needsAnswer)
        val m = question(Reply(id = "r1", text = "Oat milk", other = true, at = 2), other = true)
        assertFalse(m.needsAnswer)
        assertTrue(m.pickedIds.isEmpty())
        assertTrue(assertNotNull(m.answer).other)
    }

    @Test
    fun `a failed answer unlocks it again`() {
        val m = question(Reply(id = "r1", choice = "Milk", choiceIds = listOf("c1"), at = 2, state = ReplyState.Failed))
        assertTrue(m.needsAnswer)
        assertNull(m.answer)
    }

    @Test
    fun `history from the inbox keeps the ids and Other`() {
        val restored = withInboxReplies(
            listOf(question()),
            listOf(
                InboxReply(seq = 1, id = "r1", messageId = "m1", choice = "Milk, Bread", choiceIds = listOf("c1", "c3"),
                    sentAt = "2026-10-17T12:00:00Z"),
            ),
        ).single()
        assertEquals(listOf("c1", "c3"), restored.replies.single().choiceIds)
        assertEquals(setOf("c1", "c3"), restored.pickedIds)
    }

    @Test
    fun `stored messages from before this still load`() {
        val old = """[{"id":"m1","headline":"Q?","choices":["Yes","No"],"receivedAt":1,
            "replies":[{"id":"r1","choice":"Yes","at":2,"state":"Sent"}]}]"""
        val m = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RelayMessage.serializer()), old).single()
        assertEquals(setOf("c1"), m.pickedIds)
        assertFalse(m.other)
    }
}
