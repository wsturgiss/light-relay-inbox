package com.thelightphone.relayinbox

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal const val TAG = "RelayInbox"

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Where the tool keeps its files.
 *
 * Screens and jobs get a real `filesDir` from the SDK. `onPushNotification` gets no
 * Context at all (SDK gap), so a push that arrives before any screen has opened in this
 * process falls back to the app's standard files dir for the primary user — the same
 * directory, reached by path.
 */
internal object ToolFiles {
    @Volatile
    private var known: File? = null

    fun remember(dir: File) {
        known = dir
    }

    fun dir(): File = known ?: File("/data/user/0/${BuildConfig.APPLICATION_ID}/files")
}

/**
 * One JSON document in a file, held in memory as a [StateFlow] and written back atomically
 * on every change. Shared by the push handler, the screens and the reply job, which all run
 * in the same process.
 */
internal open class JsonFileState<T>(
    private val fileName: String,
    private val serializer: KSerializer<T>,
    private val empty: T,
) {
    private val mutex = Mutex()
    private var file: File? = null
    private val _state = MutableStateFlow(empty)
    val state: StateFlow<T> = _state.asStateFlow()

    /** Binds to [dir] and loads, the first time only. */
    suspend fun open(dir: File) = mutex.withLock {
        if (file != null) {
            if (file!!.parentFile != dir) Log.w(TAG, "$fileName already open in ${file!!.parent}, not $dir")
            return@withLock
        }
        val f = File(dir, fileName)
        file = f
        _state.value = runCatching {
            if (f.exists()) json.decodeFromString(serializer, f.readText()) else empty
        }.getOrElse {
            Log.e(TAG, "Unreadable $fileName; starting empty", it)
            empty
        }
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val f = checkNotNull(file) { "$fileName used before open()" }
        val next = transform(_state.value)
        if (next != _state.value) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$fileName.tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            check(tmp.renameTo(f)) { "Could not replace $fileName" }
            _state.value = next
        }
        next
    }
}

internal suspend fun openStores(dir: File) {
    ToolFiles.remember(dir)
    RelayStore.open(dir)
    Pairing.open(dir)
}
