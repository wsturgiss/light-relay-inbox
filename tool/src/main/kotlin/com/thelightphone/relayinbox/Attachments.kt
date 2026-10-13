package com.thelightphone.relayinbox

import java.io.File

/** Where voice notes and photos wait until their reply is sent. */
internal fun attachmentsDir(filesDir: File): File = File(filesDir, "attachments").apply { mkdirs() }

internal fun newAttachmentId(): String = "a_" + randomHex(8)

internal fun Attachment.label(): String =
    if (isVoice) "Voice note" + (durationMs?.let { " ${formatDuration(it)}" } ?: "") else "Photo"

internal fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
