package com.thelightphone.relayinbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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

class InboxViewModel : LightViewModel<Unit>()

@InitialScreen
class InboxScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, InboxViewModel>(sealedActivity) {

    override val viewModelClass: Class<InboxViewModel>
        get() = InboxViewModel::class.java

    override fun createViewModel(): InboxViewModel = InboxViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val keys by Pairing.keys.collectAsState()

        LaunchedEffect(Unit) {
            openStores(lightContext.filesDir)
            // Anything left unsent from last time (offline, killed) goes out now.
            if (RelayStore.pending().isNotEmpty()) scheduleReplySend(lightContext)
            if (Pairing.keys.value != null) scheduleSync(lightContext)
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Relay Inbox"),
                    rightButton = if (keys == null) null else LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { navigateTo(screenFactory = { PairingScreen(it) }) },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (messages.isEmpty()) {
                    EmptyBody(paired = keys != null, modifier = Modifier.weight(1f))
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        messages.forEachIndexed { index, message ->
                            MessageRow(message) {
                                navigateTo(screenFactory = { MessageScreen(it, message.id) })
                            }
                            if (index != messages.lastIndex) Divider()
                        }
                        Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                    }
                }

                // Pairing is a one-time setup: a bottom-bar action until it's done, then the top-bar icon.
                if (keys == null) {
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(text = "Pair", onClick = { navigateTo(screenFactory = { PairingScreen(it) }) }),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBody(paired: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = if (paired) "Nothing yet." else "Not paired.",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = if (paired) {
                    "Messages from the agent arrive when the tool checks in, every 15 minutes or when you open it."
                } else {
                    "Open Pair and enter the codes in the relay's settings on the Unraid box."
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun MessageRow(message: RelayMessage, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = if (message.read) message.headline else "• ${message.headline}",
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (message.detail.isNotBlank()) {
            LightText(
                text = message.detail,
                variant = LightTextVariant.Detail,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.25f.gridUnitsAsDp()),
            )
        }
        val meta = listOfNotNull(
            formatTime(message.receivedAt),
            message.replies.lastOrNull()?.label(),
            if (message.replies.isEmpty() && message.choices.isNotEmpty()) "needs an answer" else null,
        ).joinToString(" · ")
        LightText(
            text = meta,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}
