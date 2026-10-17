package com.thelightphone.relayinbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * One picture in a conversation: one an agent sent with a message ([image]), or a photo you
 * sent with a reply ([photoId], an attachment id). Media lists pictures only, never voice notes.
 */
internal data class MediaItem(
    /** Unique in the conversation: the image id (`i_…`) or the attachment id (`a_…`). */
    val key: String,
    val at: Long,
    val fromAgent: Boolean,
    val image: MessageImage? = null,
    val photoId: String? = null,
    /** What the full-screen view is titled with. */
    val title: String = "",
    /** A photo still waiting to send is in its attachment file. */
    val pendingPath: String? = null,
)

/** Every picture in [conversation], both ways, newest first. */
internal fun conversationMedia(conversation: Conversation): List<MediaItem> =
    conversation.messages.flatMap { m ->
        val fromAgent = m.image?.takeIf { !m.mine && it.isValid() }
            ?.let { listOf(MediaItem(it.id, m.receivedAt, fromAgent = true, image = it, title = m.headline)) }
            .orEmpty()
        val yours = m.replies.filter { it.state != ReplyState.Failed }.flatMap { r ->
            r.attachments.filter { it.type.startsWith("image/") }
                .map { MediaItem(it.id, r.at, fromAgent = false, photoId = it.id, title = "Your photo", pendingPath = it.path) }
        }
        fromAgent + yours
    }.distinctBy { it.key }.sortedByDescending { it.at }

/**
 * Where photos you sent are kept once their reply has gone, by attachment id, so Media can
 * show them. They go when their reply does. Photos sent before this, or before a reinstall,
 * aren't on the phone (the inbox doesn't hand them back), and Media says so.
 */
internal fun photosDir(filesDir: File): File = File(filesDir, "photos").apply { mkdirs() }

internal fun sentPhotoFile(filesDir: File, attachmentId: String): File = File(photosDir(filesDir), "$attachmentId.jpg")

/** The file a picture is in on this phone: an agent's download, a kept photo, or one still waiting to send. */
internal fun MediaItem.localFile(filesDir: File): File? = when {
    image != null -> imageFile(filesDir, image)
    pendingPath != null && File(pendingPath).exists() -> File(pendingPath)
    photoId != null -> sentPhotoFile(filesDir, photoId)
    else -> null
}?.takeIf { it.exists() }

/** Agents' pictures Media is showing and wants first, ahead of the rest of the sync's downloads. */
internal object WantedImages {
    @Volatile
    var ids: List<String> = emptyList()
}

/**
 * The order to download missing pictures in: those Media is showing first (in its order),
 * then the rest newest first. At most [limit]; the caller keeps to the inbox's rate limit.
 */
internal fun downloadOrder(missing: List<Pair<MessageImage, Long>>, wanted: List<String>, limit: Int): List<MessageImage> {
    val rank = wanted.withIndex().associate { (i, id) -> id to i }
    return missing.sortedWith(compareBy<Pair<MessageImage, Long>> { rank[it.first.id] ?: Int.MAX_VALUE }
        .thenByDescending { it.second })
        .map { it.first }
        .take(limit)
}

/** Small decoded copies for Media's grid, so it doesn't decode whole pictures per tile. */
internal object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(12 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun get(file: File, key: String, sizePx: Int): Bitmap? {
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
