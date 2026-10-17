package com.thelightphone.relayinbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private val IMAGE_ID = Regex("^i_[0-9a-f]{16}$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private val IMAGE_TYPES = setOf("image/png", "image/jpeg")

/** The relay shrinks pictures to well under this; anything bigger isn't what it signed. */
internal const val MAX_IMAGE_BYTES = 4L shl 20

/** Pictures fetched per sync. Each is one request against the inbox's 20 a minute. */
private const val IMAGES_PER_SYNC = 3

/** Safe to use as a file name and to ask the inbox for. */
internal fun MessageImage.isValid(): Boolean =
    IMAGE_ID.matches(id) && type in IMAGE_TYPES && SHA256_HEX.matches(sha256) &&
        bytes in 1..MAX_IMAGE_BYTES && width > 0 && height > 0

/** Where agents' pictures are kept, by id. They go when their message does. */
internal fun imagesDir(filesDir: File): File = File(filesDir, "images").apply { mkdirs() }

internal fun imageFile(filesDir: File, image: MessageImage): File = File(imagesDir(filesDir), image.id)

/** True when [data] is the file [image] describes. */
internal fun MessageImage.matches(data: ByteArray): Boolean =
    data.size.toLong() == bytes &&
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } == sha256

/** Which pictures are on this phone, so a screen redraws as each one arrives. */
internal object ImageFiles {
    private val _present = MutableStateFlow<Set<String>>(emptySet())
    val present: StateFlow<Set<String>> = _present.asStateFlow()

    /** Ids the inbox no longer has (404) or sent wrong; not asked for again until the tool restarts. */
    private val unavailable = mutableSetOf<String>()

    @Volatile
    private var scanned = false

    fun scan(filesDir: File) {
        if (scanned) return
        _present.value = imagesDir(filesDir).list()?.filter { IMAGE_ID.matches(it) }?.toSet().orEmpty()
        scanned = true
    }

    fun has(id: String) = id in _present.value

    fun isUnavailable(id: String) = synchronized(unavailable) { id in unavailable }

    fun added(id: String) {
        _present.value = _present.value + id
    }

    fun markUnavailable(id: String) = synchronized(unavailable) { unavailable += id }

    fun removed(ids: Set<String>) {
        _present.value = _present.value - ids
    }
}

/**
 * Downloads the pictures on recent messages that aren't here yet, newest first and a few at
 * a time, keeping each only if it matches its signed message. Then lets go of pictures whose
 * message this phone no longer has. A failure leaves the rest of the sync alone.
 */
internal suspend fun fetchImages(filesDir: File, replyUrl: String, bearer: String) = withContext(Dispatchers.IO) {
    ImageFiles.scan(filesDir)
    val messages = RelayStore.state.value
    val wanted = messages.mapNotNull { it.image }.filter { it.isValid() }
    val missing = downloadOrder(
        wanted.filter { !ImageFiles.has(it.id) && !ImageFiles.isUnavailable(it.id) }
            .map { img -> img to messages.first { it.image == img }.receivedAt },
        WantedImages.ids,
        IMAGES_PER_SYNC,
    )
    for (image in missing) {
        val data = try {
            getBytes(inboxRoute(replyUrl, "/images/${image.id}"), bearer)
        } catch (e: ImageRefused) {
            Log.w(TAG, "Image ${image.id} not fetched: HTTP ${e.status}")
            // 404: gone, or an inbox from before pictures. 429 and 5xx: next sync.
            if (e.status == 404 || e.status == 400) ImageFiles.markUnavailable(image.id)
            if (e.status == 429) break
            continue
        } catch (e: IOException) {
            Log.w(TAG, "Image ${image.id} not fetched: $e")
            break
        }
        if (!image.matches(data)) {
            Log.w(TAG, "Image ${image.id} doesn't match its signed message; dropped")
            ImageFiles.markUnavailable(image.id)
            continue
        }
        val file = imageFile(filesDir, image)
        val part = File(file.parentFile, "${image.id}.part")
        part.writeBytes(data)
        if (part.renameTo(file)) {
            ImageFiles.added(image.id)
            Log.i(TAG, "Fetched image ${image.id} (${data.size} bytes)")
        }
    }
    val keep = wanted.map { it.id }.toSet()
    val gone = imagesDir(filesDir).listFiles()?.filter { it.name !in keep }.orEmpty()
    gone.forEach { it.delete() }
    if (gone.isNotEmpty()) {
        ImageFiles.removed(gone.map { it.name }.toSet())
        ImageBitmaps.forget(gone.map { it.name }.toSet())
    }
    // Photos you sent go when their reply does.
    val photos = messages.flatMap { m -> m.replies.flatMap { r -> r.attachments.map { "${it.id}.jpg" } } }.toSet()
    photosDir(filesDir).listFiles()?.filter { it.name !in photos }?.forEach { it.delete() }
}

private class ImageRefused(val status: Int) : Exception("HTTP $status")

private fun getBytes(url: String, token: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        val status = conn.responseCode
        if (status !in 200..299) throw ImageRefused(status)
        if (conn.contentLengthLong > MAX_IMAGE_BYTES) throw ImageRefused(413)
        return conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IMAGE_BYTES) throw ImageRefused(413)
            }
            out.toByteArray()
        }
    } finally {
        conn.disconnect()
    }
}

/** Decoded pictures, so scrolling a channel doesn't decode them again. */
internal object ImageBitmaps {
    private val cache = object : LruCache<String, Bitmap>(48 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun get(filesDir: File, image: MessageImage): Bitmap? {
        cache.get(image.id)?.let { return it }
        val file = imageFile(filesDir, image)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        cache.put(image.id, bitmap)
        return bitmap
    }

    fun forget(ids: Set<String>) = ids.forEach { cache.remove(it) }
}
