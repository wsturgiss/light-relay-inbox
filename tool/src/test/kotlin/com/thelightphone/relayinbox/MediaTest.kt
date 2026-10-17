package com.thelightphone.relayinbox

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaTest {
    private fun img(n: Int) = MessageImage(id = "i_%016x".format(n), type = "image/jpeg", bytes = 10, width = 4, height = 3,
        sha256 = "a".repeat(64))

    private fun photo(id: String, path: String? = null) = Attachment(id = id, type = "image/jpeg", bytes = 10, path = path)
    private val voice = Attachment(id = "a_00000000000000v1", type = "audio/mp4", bytes = 10)

    private val conversation = Conversation("@Jarvis", listOf(
        RelayMessage(id = "m1", headline = "Shortlist", receivedAt = 10, image = img(1),
            replies = listOf(
                Reply(id = "r1", at = 20, state = ReplyState.Sent, attachments = listOf(photo("a_0000000000000001"), voice)),
                Reply(id = "r2", at = 25, state = ReplyState.Failed, attachments = listOf(photo("a_0000000000000002"))),
            )),
        RelayMessage(id = "m2", headline = "No picture", receivedAt = 30),
        RelayMessage(id = "m3", headline = "Floor plan", receivedAt = 40, image = img(3)),
        RelayMessage(id = "m4", headline = "Odd", receivedAt = 50, image = img(4).copy(type = "image/gif")),
        RelayMessage(id = "t_00000000000000aa", headline = "Mine", mine = true, receivedAt = 60,
            replies = listOf(Reply(id = "r3", at = 60, state = ReplyState.Pending, attachments = listOf(photo("a_0000000000000003", "/x.jpg"))))),
    ), "Jarvis")

    @Test
    fun `pictures both ways, newest first, no voice notes or failed sends`() {
        val media = conversationMedia(conversation)
        assertEquals(listOf("a_0000000000000003", img(3).id, "a_0000000000000001", img(1).id), media.map { it.key })
        assertEquals(listOf(false, true, false, true), media.map { it.fromAgent })
        assertEquals("Floor plan", media[1].title)
        assertEquals("/x.jpg", media[0].pendingPath)
    }

    @Test
    fun `pictures Media is showing download first, then the newest`() {
        val missing = listOf(img(1) to 10L, img(2) to 20L, img(3) to 30L, img(4) to 40L)
        assertEquals(listOf(img(1), img(2), img(4)), downloadOrder(missing, listOf(img(1).id, img(2).id), 3))
        assertEquals(listOf(img(4), img(3)), downloadOrder(missing, emptyList(), 2))
    }

    @Test
    fun `finds the file on this phone`() {
        val dir = Files.createTempDirectory("media").toFile()
        val agent = MediaItem(img(1).id, 1, fromAgent = true, image = img(1))
        val mine = MediaItem("a_0000000000000001", 1, fromAgent = false, photoId = "a_0000000000000001")
        assertNull(agent.localFile(dir))
        assertNull(mine.localFile(dir))
        imageFile(dir, img(1)).writeBytes(byteArrayOf(1))
        sentPhotoFile(dir, "a_0000000000000001").writeBytes(byteArrayOf(1))
        assertEquals(imageFile(dir, img(1)), agent.localFile(dir))
        assertEquals(sentPhotoFile(dir, "a_0000000000000001"), mine.localFile(dir))
        val pending = java.io.File(attachmentsDir(dir), "a_0000000000000002.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(pending, mine.copy(pendingPath = pending.path).localFile(dir))
        dir.deleteRecursively()
    }
}
