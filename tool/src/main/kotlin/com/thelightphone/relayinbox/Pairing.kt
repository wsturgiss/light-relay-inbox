package com.thelightphone.relayinbox

import android.util.Base64
import com.thelightphone.sdk.LightSdkApplication
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.security.SecureRandom

/**
 * The two secrets the phone shares with the Unraid box, both generated here so nothing
 * long ever has to be typed on the phone:
 *
 * - [pushKey] signs every push; the tool drops pushes that don't verify under it.
 *   Goes into the relay's settings as PUSH_KEY.
 * - [replyToken] is the bearer the tool presents to the public reply inbox.
 *   Goes into the inbox's settings as REPLY_TOKEN.
 */
@Serializable
data class PairingKeys(val pushKey: String, val replyToken: String)

internal object Pairing : JsonFileState<PairingKeys?>(
    fileName = "pairing.json",
    serializer = PairingKeys.serializer().nullable,
    empty = null,
) {
    val keys: StateFlow<PairingKeys?> get() = state

    /** Where the relay must POST to reach this tool: our UnifiedPush endpoint on Light's server. */
    val pushEndpoint = LightSdkApplication.lightOsData.map { it.pushCredentials?.pushEndpoint }

    suspend fun ensure(): PairingKeys = update { it ?: newKeys() }!!

    suspend fun rotate(): PairingKeys = update { newKeys() }!!

    private fun newKeys() = PairingKeys(pushKey = randomKey(), replyToken = randomKey())

    private fun randomKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** What to paste into the two containers' settings on Unraid. */
    fun settingsBlock(endpoint: String?, keys: PairingKeys): String = buildString {
        appendLine("# relay container")
        appendLine("PUSH_ENDPOINT=${endpoint ?: "(not registered yet)"}")
        appendLine("PUSH_KEY=${keys.pushKey}")
        appendLine("# inbox container")
        append("REPLY_TOKEN=${keys.replyToken}")
    }
}
