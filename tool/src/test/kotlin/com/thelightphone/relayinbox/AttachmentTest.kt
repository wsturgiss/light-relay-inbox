package com.thelightphone.relayinbox

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTest {
    @Test
    fun `a voice note comes back from the inbox's history`() {
        // GET /replies on the inbox, as light-relay writes it.
        val page = """{"replies": [{"seq": 3, "id": "r_v1", "messageId": "m1",
            "choice": null, "text": null, "sentAt": "2026-10-13T12:54:29Z", "receivedAt": "2026-10-13T12:54:29Z",
            "attachments": [{"id": "a_1234567890abcdef", "type": "audio/mp4", "bytes": 48213}]}]}"""
        val replies = json.decodeFromString(RepliesPage.serializer(), page).replies
        val message = RelayMessage(id = "m1", headline = "Book the 9:40?", receivedAt = 1)
        val restored = withInboxReplies(listOf(message), replies).single().replies.single()
        val note = restored.attachments.single()
        assertEquals(Attachment(id = "a_1234567890abcdef", type = "audio/mp4", bytes = 48213, uploaded = true), note)
        assertTrue(note.isVoice)
        assertNull(note.path)
        assertEquals("You: Voice note", restored.label())
    }

    @Test
    fun `replies saved before attachments still load`() {
        val saved = """[{"id": "m1", "headline": "Hi", "receivedAt": 1,
            "replies": [{"id": "r1", "choice": "Yes", "at": 2, "state": "Sent"}]}]"""
        val messages = json.decodeFromString(ListSerializer(RelayMessage.serializer()), saved)
        assertEquals(emptyList(), messages.single().replies.single().attachments)
    }

    @Test
    fun `labels say what was sent`() {
        val voice = Attachment(id = "a_1", type = "audio/mp4", durationMs = 72_400)
        val photo = Attachment(id = "a_2", type = "image/jpeg")
        assertEquals("Voice note 1:12", voice.label())
        assertEquals("Photo", photo.label())
        val captioned = Reply(id = "r1", text = "Which one?", at = 1, state = ReplyState.Sent, attachments = listOf(photo))
        assertEquals("You: “Which one?”", captioned.label())
        val pending = Reply(id = "r2", at = 1, attachments = listOf(photo))
        assertEquals("You: Photo · sending", pending.label())
        assertEquals("You: Photo · waiting for home network", pending.label(waitingForHome = true))
    }

    @Test
    fun `a transcript lands on its voice note`() {
        val note = Attachment(id = "a_1", type = "audio/mp4", durationMs = 4_000, uploaded = true)
        val message = RelayMessage(id = "m1", headline = "Hi", receivedAt = 1,
            replies = listOf(Reply(id = "r1", at = 2, state = ReplyState.Sent, attachments = listOf(note))))
        val page = """{"transcripts": [{"seq": 1, "id": "a_1", "text": "Pick up milk."}, {"seq": 2, "id": "a_gone", "text": "x"}]}"""
        val transcripts = json.decodeFromString(TranscriptsPage.serializer(), page).transcripts
        val reply = withTranscripts(listOf(message), transcripts).single().replies.single()
        assertEquals("Pick up milk.", reply.attachments.single().transcript)
        assertEquals("You: “Pick up milk.”", reply.label())
    }

    @Test
    fun `an error shows until the transcript replaces it`() {
        val note = Attachment(id = "a_1", type = "audio/mp4", uploaded = true)
        val message = RelayMessage(id = "m1", headline = "Hi", receivedAt = 1,
            replies = listOf(Reply(id = "r1", at = 2, state = ReplyState.Sent, attachments = listOf(note))))
        val failed = json.decodeFromString(TranscriptsPage.serializer(),
            """{"transcripts": [{"seq": 1, "id": "a_1", "text": "", "error": "No transcription credit left"}]}""").transcripts
        val withError = withTranscripts(listOf(message), failed)
        val errored = withError.single().replies.single().attachments.single()
        assertEquals("No transcription credit left", errored.transcriptError)
        assertNull(errored.transcript)
        val later = listOf(FetchedTranscript(seq = 2, id = "a_1", text = "Pick up milk."))
        val fixed = withTranscripts(withError, later).single().replies.single().attachments.single()
        assertEquals("Pick up milk.", fixed.transcript)
        assertNull(fixed.transcriptError)
    }

    @Test
    fun `history brings transcripts back`() {
        val page = """{"replies": [{"seq": 3, "id": "r_v1", "messageId": "m1", "sentAt": "2026-10-13T12:54:29Z",
            "attachments": [{"id": "a_1", "type": "audio/mp4", "bytes": 10, "transcript": "Pick up milk."}]}]}"""
        val replies = json.decodeFromString(RepliesPage.serializer(), page).replies
        val message = RelayMessage(id = "m1", headline = "Hi", receivedAt = 1)
        val restored = withInboxReplies(listOf(message), replies).single().replies.single()
        assertEquals("Pick up milk.", restored.attachments.single().transcript)
    }
}
