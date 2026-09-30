package com.thelightphone.relayinbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushCodecTest {
    // Produced by light-relay's lightrelay.relay.sign() with key "test-push-key".
    // Non-ASCII on purpose: the MAC covers UTF-8 bytes on both sides.
    private val signed = "v1.f8960fa7e0995ddcf984ff4928aec0191a81be9eb50b3cbc1fd39360a1e6dd56." +
        """{"v":1,"id":"m_0123456789abcdef","at":"2026-09-30T12:00:00Z","headline":"Buy-box alert — 824 Dayton St",""" +
        """"detail":"2bd rent cut $2,800 → $2,650.","choices":["Yes","No"]}"""

    @Test
    fun `reads a push signed by the relay`() {
        val message = assertNotNull(PushCodec.decode(signed.toByteArray(), "test-push-key", receivedAt = 42))
        assertEquals("m_0123456789abcdef", message.id)
        assertEquals("Buy-box alert — 824 Dayton St", message.headline)
        assertEquals(listOf("Yes", "No"), message.choices)
        assertEquals(42, message.receivedAt)
    }

    @Test
    fun `drops a push under the wrong key`() {
        assertNull(PushCodec.decode(signed.toByteArray(), "some-other-key"))
    }

    @Test
    fun `drops a tampered push`() {
        assertNull(PushCodec.decode(signed.replace("$2,650", "$1,650").toByteArray(), "test-push-key"))
    }

    @Test
    fun `drops an unsigned push`() {
        assertNull(PushCodec.decode("""{"v":1,"id":"x","headline":"hi"}""".toByteArray(), "test-push-key"))
    }
}
