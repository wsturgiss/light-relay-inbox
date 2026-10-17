package com.thelightphone.relayinbox

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageTest {
    // Both produced by light-relay's lightrelay.relay.sign() with key "test-push-key".
    private val withImage = "v1.253595d9e1db767982b8fd6686f5876c6491540bb4fe3431c5a5036d0b13d7e9." +
        """{"v":1,"id":"m_0123456789abcdef","at":"2026-10-17T12:00:00Z","headline":"Shortlist","detail":"","choices":[],""" +
        """"from":"Othala","image":{"id":"i_00112233445566ff","type":"image/png","bytes":263076,"width":1080,"height":639,""" +
        """"sha256":"abababababababababababababababababababababababababababababababab"}}"""
    private val badId = "v1.f9c57ff2f05550e995139d26a8224ce134351cfffee7ab687e19a3800dd19d9d." +
        """{"v":1,"id":"m_0123456789abcdef","at":"2026-10-17T12:00:00Z","headline":"Shortlist","detail":"","choices":[],""" +
        """"from":"Othala","image":{"id":"../../etc","type":"image/png","bytes":263076,"width":1080,"height":639,""" +
        """"sha256":"abababababababababababababababababababababababababababababababab"}}"""

    @Test
    fun `reads the picture on a signed message`() {
        val message = assertNotNull(PushCodec.decode(withImage.toByteArray(), "test-push-key"))
        val image = assertNotNull(message.image)
        assertEquals("i_00112233445566ff", image.id)
        assertEquals(1080 to 639, image.width to image.height)
        assertEquals(263076, image.bytes)
    }

    @Test
    fun `a picture with an unsafe id is left off, the text kept`() {
        val message = assertNotNull(PushCodec.decode(badId.toByteArray(), "test-push-key"))
        assertNull(message.image)
        assertEquals("Shortlist", message.headline)
    }

    @Test
    fun `a downloaded file is kept only if it is what was signed`() {
        val data = "not really a png".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val image = MessageImage("i_00112233445566ff", "image/png", data.size.toLong(), 10, 10, sha)
        assertTrue(image.isValid())
        assertTrue(image.matches(data))
        assertFalse(image.matches(data + 1))
        assertFalse(image.copy(bytes = 99).matches(data))
        assertFalse(image.copy(type = "image/gif").isValid())
        assertFalse(image.copy(bytes = MAX_IMAGE_BYTES + 1).isValid())
    }
}
