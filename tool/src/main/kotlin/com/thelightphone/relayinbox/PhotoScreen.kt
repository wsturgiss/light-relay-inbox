package com.thelightphone.relayinbox

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.thelightphone.sdk.LightPhotoCapture
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightCapturedPhoto
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/**
 * Long edge of a sent photo. Plenty for an agent to read a sign or a label, and a few
 * hundred KB, well under the relay's 10 MB.
 */
private const val PHOTO_LONG_EDGE_PX = 1600

class PhotoViewModel(private val filesDir: File) : LightViewModel<Attachment>() {
    /** The photo taken, waiting for Send or Delete; null while the camera is up. */
    val taken = MutableStateFlow<Attachment?>(null)

    fun keep(photo: LightCapturedPhoto) {
        val id = newAttachmentId()
        val file = File(attachmentsDir(filesDir), "$id.jpg")
        try {
            file.writeBytes(photo.jpeg)
        } catch (e: IOException) {
            Log.w(TAG, "Photo not saved", e)
            return
        }
        taken.value = Attachment(id = id, type = "image/jpeg", bytes = file.length(), path = file.absolutePath)
    }

    fun discard() {
        taken.value?.path?.let { File(it).delete() }
        taken.value = null
    }

    /** The photo, handed over to its reply; the screen no longer owns the file. */
    fun take(): Attachment? = taken.value.also { taken.value = null }

    override fun onCleared() {
        // Left without sending: the file goes with the screen.
        discard()
        super.onCleared()
    }
}

/** Take a photo: shoot, look at it, then send or delete. Returns the photo. */
class PhotoScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Attachment, PhotoViewModel>(sealedActivity) {

    override val viewModelClass: Class<PhotoViewModel>
        get() = PhotoViewModel::class.java

    override fun createViewModel(): PhotoViewModel = PhotoViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val taken by viewModel.taken.collectAsState()

        LightTheme(colors = themeColors) {
            val photo = taken
            if (photo == null) {
                LightPhotoCapture(
                    onCaptured = viewModel::keep,
                    onBack = { goBack(null) },
                    title = "Photo",
                    maxLongEdgePx = PHOTO_LONG_EDGE_PX,
                )
            } else {
                Review(photo)
            }
        }
    }

    @Composable
    private fun Review(photo: Attachment) {
        val bitmap = remember(photo.path) { photo.path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = viewModel::discard),
                center = LightTopBarCenter.Text("Photo"),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(bitmap = it, contentDescription = "The photo", contentScale = ContentScale.Fit) }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(icon = LightIcons.DELETE, onClick = viewModel::discard),
                    null,
                    LightBarButton.LightIcon(icon = LightIcons.SEND, onClick = { viewModel.take()?.let { goBack(it) } }),
                ),
            )
        }
    }
}
