package com.thelightphone.relayinbox

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Taller than this (height over width), a picture shows its top in the channel; tap for all of it. */
private const val INLINE_MAX_TALL = 1.25f

/** The picture, decoded off the main thread, once it's on the phone; null until then. */
@Composable
private fun rememberImageBitmap(filesDir: File, image: MessageImage): Bitmap? {
    val present by ImageFiles.present.collectAsState()
    val here = image.id in present
    val bitmap by produceState<Bitmap?>(initialValue = null, image.id, here) {
        value = if (here) withContext(Dispatchers.IO) { ImageBitmaps.get(filesDir, image) } else null
    }
    return bitmap
}

/**
 * A picture under an agent's message: the full width, in a hairline frame, as tall as its
 * shape (a very tall one is cut at [INLINE_MAX_TALL] and shows its top). Until it's downloaded,
 * a frame of the same shape says so. Tap to see it full screen.
 */
@Composable
internal fun InlineImage(image: MessageImage, filesDir: File, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = rememberImageBitmap(filesDir, image)
    val unavailable = bitmap == null && ImageFiles.isUnavailable(image.id)
    val ratio = image.width.toFloat() / image.height
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(max(ratio, 1f / INLINE_MAX_TALL))
            .border(1.dp, LightThemeTokens.colors.content)
            .padding(1.dp)
            .clipToBounds()
            .then(if (bitmap != null) Modifier.lightClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "Picture from the agent. Tap to zoom.",
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LightText(
                text = if (unavailable) "Picture not available" else "Picture loading…",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}

class ImageViewModel : LightViewModel<Unit>()

/**
 * One picture, full screen: it opens fitted to the screen. Pinch to zoom (up to 6x, or more
 * for a wide one, so a table's rows can fill the height), drag to pan, double-tap to zoom in
 * where you tap or back out. Press and hold to hide the top bar and the hint for the whole
 * screen; hold again, or tap once, to bring them back. Back still leaves.
 */
class ImageScreen(
    sealedActivity: SealedLightActivity,
    private val image: MessageImage,
    private val title: String,
) : LightScreen<Unit, ImageViewModel>(sealedActivity) {

    override val viewModelClass: Class<ImageViewModel>
        get() = ImageViewModel::class.java

    override fun createViewModel(): ImageViewModel = ImageViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val bitmap = rememberImageBitmap(lightContext.filesDir, image)
        var barsHidden by remember { mutableStateOf(false) }
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (!barsHidden) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(title.ifBlank { "Picture" }),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bitmap == null) {
                        LightText(text = "Picture not available", variant = LightTextVariant.Detail, lighten = true)
                    } else {
                        ZoomableImage(
                            bitmap,
                            onHold = { barsHidden = !barsHidden },
                            onTap = { if (barsHidden) barsHidden = false },
                        )
                    }
                }
                if (!barsHidden) {
                    LightText(
                        text = "Pinch to zoom · double-tap · hold for full screen",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

/**
 * [onHold] is a press and hold with one finger that didn't move: a pinch or a pan never
 * counts, even if the fingers rest a while. [onTap] is a single tap (after the double-tap
 * window passes, so a double-tap stays a zoom).
 */
@Composable
private fun ZoomableImage(bitmap: Bitmap, onHold: () -> Unit = {}, onTap: () -> Unit = {}) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // The picture's size on screen at 1x: fitted, as ContentScale.Fit draws it.
        val fit = min(box.width / bitmap.width, box.height / bitmap.height)
        val base = Size(bitmap.width * fit, bitmap.height * fit)
        // A wide picture may zoom until its height fills the screen, whatever that takes.
        val maxScale = max(6f, box.height / base.height)
        var scale by remember(bitmap) { mutableFloatStateOf(1f) }
        var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
        // Set once a gesture has had a second finger or has zoomed or panned; cleared on the next first touch.
        var gestureMoved by remember { mutableStateOf(false) }

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = max(0f, (base.width * s - box.width) / 2)
            val maxY = max(0f, (base.height * s - box.height) / 2)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        // The screen grows when the bars hide: keep the picture inside the new edges.
        LaunchedEffect(box) { offset = clamp(offset, scale) }

        Image(
            bitmap = imageBitmap,
            contentDescription = "Picture from the agent",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                // Watches every gesture before the detectors do, without consuming anything.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.count { it.pressed }
                            if (down == 1 && event.changes.size == 1 && event.changes[0].pressed && !event.changes[0].previousPressed) {
                                gestureMoved = false
                            }
                            if (down > 1) gestureMoved = true
                        }
                    }
                }
                .pointerInput(box, bitmap) {
                    detectTapGestures(
                        onLongPress = { if (!gestureMoved) onHold() },
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Wide: rows fill the height. Otherwise a closer look.
                            val target = if (base.height < box.height * 0.8f) min(maxScale, max(2f, box.height / base.height)) else 2.5f
                            val c = tap - Offset(box.width / 2, box.height / 2)
                            offset = clamp(c * (1 - target), target)
                            scale = target
                        }
                    },
                    )
                }
                .pointerInput(box, bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        gestureMoved = true
                        val newScale = (scale * zoom).coerceIn(1f, maxScale)
                        // Keep the point under the fingers where it is while zooming.
                        val c = centroid - Offset(box.width / 2, box.height / 2)
                        offset = clamp((offset - c) * (newScale / scale) + c + pan, newScale)
                        scale = newScale
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
