package com.thelightphone.relayinbox

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object RelayEntryPoint : LightEntryPoint {
    override val enablePushNotifications: Boolean
        get() = true

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        serverData.collect {
            Log.i(TAG, "Push endpoint: ${it?.pushCredentials?.pushEndpoint ?: "not registered yet"}")
        }
    }

    // The SDK can wake us for a push but can't raise a notification while the tool is
    // closed, so a message is stored here and waits in the inbox until it's opened.
    override suspend fun onPushNotification(data: ByteArray) {
        openStores(ToolFiles.dir())
        val key = Pairing.keys.value?.pushKey ?: run {
            Log.w(TAG, "Push arrived before pairing; dropped")
            return
        }
        val message = PushCodec.decode(data, key) ?: run {
            Log.w(TAG, "Push failed signature check; dropped (${data.size} bytes)")
            return
        }
        if (RelayStore.add(message)) Log.i(TAG, "Stored ${message.id}")
    }
}
