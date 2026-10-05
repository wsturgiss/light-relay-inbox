package com.thelightphone.relayinbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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

/** One line of the conversation: something the agent sent, or something you answered. */
internal sealed interface Turn {
    val at: Long

    data class Sent(val message: RelayMessage) : Turn {
        override val at get() = message.receivedAt
    }

    /** [context] is the message's headline when the answer doesn't directly follow it. */
    data class Answered(val message: RelayMessage, val reply: Reply, val context: String?) : Turn {
        override val at get() = reply.at
    }
}

/** Every message and reply, oldest first, as they happened. */
internal fun conversation(messages: List<RelayMessage>): List<Turn> {
    val events = messages.flatMap { m -> listOf(m.receivedAt to (m to null)) + m.replies.map { it.at to (m to it) } }
        .sortedBy { it.first }
        .map { it.second }
    var lastMessageId: String? = null
    return events.map { (message, reply) ->
        val turn = if (reply == null) {
            Turn.Sent(message)
        } else {
            Turn.Answered(message, reply, context = message.headline.takeIf { lastMessageId != message.id })
        }
        lastMessageId = message.id
        turn
    }
}

class HistoryViewModel : LightViewModel<Unit>()

class HistoryScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel(): HistoryViewModel = HistoryViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val turns = remember(messages) { conversation(messages) }
        val scrollState = rememberScrollState()
        var scrolledToEnd by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (Pairing.keys.value != null) scheduleSync(lightContext)
        }
        // Open on the latest, like any conversation; after that, leave the position alone.
        LaunchedEffect(scrollState.maxValue) {
            if (!scrolledToEnd && scrollState.maxValue > 0) {
                scrollState.scrollTo(scrollState.maxValue)
                scrolledToEnd = true
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("History"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    scrollState = scrollState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (turns.isEmpty()) {
                        LightText(text = "Nothing yet.", variant = LightTextVariant.Copy, lighten = true)
                    }
                    turns.forEach { turn ->
                        when (turn) {
                            is Turn.Sent -> SentTurn(turn.message) {
                                navigateTo(screenFactory = { MessageScreen(it, turn.message.id) })
                            }
                            is Turn.Answered -> AnsweredTurn(turn)
                        }
                    }
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                }
            }
        }
    }
}

@Composable
private fun SentTurn(message: RelayMessage, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(top = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = message.headline, variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
        if (message.detail.isNotBlank()) {
            LightText(
                text = message.detail,
                variant = LightTextVariant.Detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.25f.gridUnitsAsDp()),
            )
        }
        LightText(
            text = formatTime(message.receivedAt),
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun AnsweredTurn(turn: Turn.Answered) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1f.gridUnitsAsDp(), start = 3f.gridUnitsAsDp()),
    ) {
        if (turn.context != null) {
            LightText(
                text = "Re: ${turn.context}",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LightText(
            text = turn.reply.choice ?: turn.reply.text.orEmpty(),
            variant = LightTextVariant.Copy,
            align = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        val state = when (turn.reply.state) {
            ReplyState.Pending -> " · sending"
            ReplyState.Sent -> ""
            ReplyState.Failed -> " · not sent"
        }
        LightText(
            text = "You · ${formatTime(turn.reply.at)}$state",
            variant = LightTextVariant.Detail,
            lighten = true,
            align = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}
