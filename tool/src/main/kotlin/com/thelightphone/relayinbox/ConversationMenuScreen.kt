package com.thelightphone.relayinbox

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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

private const val MEDIA_COLUMNS = 3

/** Keeps syncing while the gear is open, so agents' pictures Media is waiting on arrive. */
class ConversationMenuViewModel(filesDir: File) : LightViewModel<String>() {
    private val refresh = LiveRefresh(viewModelScope, filesDir)

    override fun onScreenShow(screen: SimpleLightScreen<String>) = refresh.start()

    override fun onScreenHide(screen: SimpleLightScreen<String>) = refresh.stop()

    override fun onAppPause() = refresh.stop()
}

/**
 * A conversation's gear: its actions (a tap returns the key, as [MenuScreen] does), then
 * **Media**: every picture in it, both ways, newest first, as a grid of thumbnails. A tap
 * opens one full screen. Agents' pictures not downloaded yet are fetched first on the next
 * syncs, a few at a time as the inbox's rate limit allows.
 */
class ConversationMenuScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val conversationId: String,
    private val items: List<MenuItem>,
) : LightScreen<String, ConversationMenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<ConversationMenuViewModel>
        get() = ConversationMenuViewModel::class.java

    override fun createViewModel(): ConversationMenuViewModel = ConversationMenuViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val agents by Agents.state.collectAsState()
        val media = remember(messages, agents) {
            channels(messages, agents).firstOrNull { it.id == conversationId }?.let(::conversationMedia).orEmpty()
        }
        DisposableEffect(media) {
            WantedImages.ids = media.mapNotNull { it.image?.id }
            onDispose { WantedImages.ids = emptyList() }
        }
        val filesDir = lightContext.filesDir

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 2f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
                ) {
                    items.forEach { item ->
                        LightText(
                            text = item.label,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { goBack(item.key) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                    }
                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    Divider()
                    LightText(
                        text = if (media.isEmpty()) "Media" else "Media · ${media.size}",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.fillMaxWidth().padding(top = 0.75f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
                    )
                    if (media.isEmpty()) {
                        LightText(text = "No pictures in this conversation yet.", variant = LightTextVariant.Detail, lighten = true)
                    }
                    media.chunked(MEDIA_COLUMNS).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 0.33f.gridUnitsAsDp()),
                            horizontalArrangement = Arrangement.spacedBy(0.33f.gridUnitsAsDp()),
                        ) {
                            row.forEach { item ->
                                MediaTile(item, filesDir, modifier = Modifier.weight(1f)) {
                                    navigateTo(screenFactory = { ImageScreen(it, item, item.title) })
                                }
                            }
                            repeat(MEDIA_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                }
            }
        }
    }
}

/** A square thumbnail in a hairline frame, cropped to fill it. Says so until the picture is here. */
@Composable
private fun MediaTile(item: MediaItem, filesDir: File, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val present by ImageFiles.present.collectAsState()
    val here = item.image == null || item.image.id in present
    val bitmap by produceState<Bitmap?>(initialValue = null, item.key, here) {
        value = if (here) withContext(Dispatchers.IO) { item.localFile(filesDir)?.let { Thumbnails.get(it, item.key, 240) } } else null
    }
    val missing = item.image?.let { ImageFiles.isUnavailable(it.id) } ?: (here && bitmap == null)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .border(1.dp, LightThemeTokens.colors.content)
            .padding(1.dp)
            .clipToBounds()
            .then(if (bitmap != null) Modifier.lightClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = remember(b) { b.asImageBitmap() },
                contentDescription = if (item.fromAgent) "Picture from the agent" else "Your photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LightText(
                text = if (missing) "Not on this phone" else "Loading…",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}
