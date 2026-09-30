package com.thelightphone.relayinbox

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Reads a push from the relay: `v1.<hex HMAC-SHA256 of the JSON>.<JSON>`, keyed by
 * [PairingKeys.pushKey]. Anything else — unsigned, mis-signed, or malformed — is null.
 */
internal object PushCodec {
    @Serializable
    private data class Wire(
        val v: Int,
        val id: String,
        val at: String = "",
        val headline: String,
        val detail: String = "",
        val choices: List<String> = emptyList(),
    )

    fun decode(data: ByteArray, pushKey: String, receivedAt: Long = System.currentTimeMillis()): RelayMessage? {
        val parts = data.decodeToString().split('.', limit = 3)
        if (parts.size != 3 || parts[0] != "v1") return null
        val given = parts[1].hexToBytesOrNull() ?: return null
        val payload = parts[2]

        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(pushKey.toByteArray(), "HmacSHA256"))
            doFinal(payload.toByteArray())
        }
        if (!MessageDigest.isEqual(mac, given)) return null

        val wire = runCatching { json.decodeFromString(Wire.serializer(), payload) }.getOrNull() ?: return null
        if (wire.v != 1) return null
        return RelayMessage(
            id = wire.id,
            headline = wire.headline,
            detail = wire.detail,
            choices = wire.choices,
            sentAt = wire.at,
            receivedAt = receivedAt,
        )
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length != 64) return null
        return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }
}
