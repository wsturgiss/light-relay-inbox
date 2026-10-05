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
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
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
import kotlinx.coroutines.launch

class ConversationViewModel : LightViewModel<Unit>() {
    fun reply(context: SealedLightContext, messageId: String, choice: String? = null, text: String? = null) {
        viewModelScope.launch {
            val id = "r_" + randomHex(8)
            RelayStore.addReply(messageId, Reply(id = id, choice = choice, text = text, at = System.currentTimeMillis()))
            scheduleReplySend(context)
        }
    }

    fun retry(context: SealedLightContext) {
        viewModelScope.launch {
            RelayStore.retryFailed()
            scheduleReplySend(context)
        }
    }
}

/**
 * One conversation, oldest first and opening on the latest: each message from the agent
 * with your replies under it. A message still waiting on a choice keeps its choices;
 * **Reply** answers the latest message.
 */
class ConversationScreen(
    sealedActivity: SealedLightActivity,
    private val threadId: String,
) : LightScreen<Unit, ConversationViewModel>(sealedActivity) {

    override val viewModelClass: Class<ConversationViewModel>
        get() = ConversationViewModel::class.java

    override fun createViewModel(): ConversationViewModel = ConversationViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val keys by Pairing.keys.collectAsState()
        val conversation = remember(messages) { conversations(messages).firstOrNull { it.id == threadId } }
        val scrollState = rememberScrollState()
        // Follow the bottom while the layout settles and as messages arrive, until you scroll up.
        var pinnedToEnd by remember { mutableStateOf(true) }

        // Read covers anything that arrives while it's open, too.
        LaunchedEffect(messages) { RelayStore.markThreadRead(threadId) }
        LaunchedEffect(scrollState.maxValue) {
            if (pinnedToEnd) scrollState.scrollTo(scrollState.maxValue)
        }
        LaunchedEffect(scrollState.value) {
            pinnedToEnd = scrollState.value >= scrollState.maxValue
        }

        val canReply = keys != null && inboxUrl().isNotEmpty()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(conversation?.messages?.first()?.headline.orEmpty()),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    scrollState = scrollState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 2f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp()),
                ) {
                    if (conversation == null) {
                        LightText(text = "This conversation is gone.", variant = LightTextVariant.Copy, lighten = true)
                        return@LightScrollView
                    }
                    timeline(conversation).forEach { entry ->
                        when (entry) {
                            is Entry.FromAgent -> AgentEntry(entry.message, canReply) { choice ->
                                viewModel.reply(lightContext, entry.message.id, choice = choice)
                            }
                            is Entry.FromYou -> YourEntry(entry.reply)
                        }
                    }

                    if (!canReply) {
                        LightText(
                            text = if (keys == null) "Pair the tool to reply." else "No reply inbox address in this build.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                }

                val hasFailed = conversation?.messages?.any { m -> m.replies.any { it.state == ReplyState.Failed } } == true
                LightBottomBar(
                    items = buildList {
                        if (conversation != null && canReply) {
                            val latest = conversation.latest
                            add(
                                LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = {
                                    navigateTo(
                                        screenFactory = { TextEditorScreen(it, EditorRequest(title = "Reply", initialValue = "", initialCaps = true)) },
                                        resultCallback = { text ->
                                            if (text.isNotBlank()) viewModel.reply(lightContext, latest.id, text = text.trim())
                                        },
                                    )
                                }),
                            )
                        }
                        if (hasFailed && canReply) {
                            add(LightBarButton.Text(text = "Retry", onClick = { viewModel.retry(lightContext) }))
                        }
                    },
                )
            }
        }
    }
}

/** What happened in a conversation, in order: the agent's messages and what you wrote. */
internal sealed interface Entry {
    val at: Long

    data class FromAgent(val message: RelayMessage) : Entry {
        override val at get() = message.receivedAt
    }

    data class FromYou(val reply: Reply) : Entry {
        override val at get() = reply.at
    }
}

internal fun timeline(conversation: Conversation): List<Entry> =
    conversation.messages
        .flatMap { m -> (if (m.mine) emptyList() else listOf(Entry.FromAgent(m))) + m.replies.map { Entry.FromYou(it) } }
        .sortedBy { it.at }

/** Left, like the other side in Messages: the date, then the message, then any choices. */
@Composable
private fun AgentEntry(message: RelayMessage, canReply: Boolean, onChoice: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 1.5f.gridUnitsAsDp())) {
        LightText(text = formatDateTime(message.receivedAt), variant = LightTextVariant.Detail, modifier = Modifier.fillMaxWidth())
        LightText(text = message.headline, variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
        if (message.detail.isNotBlank()) {
            LightText(
                text = message.detail,
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
        if (message.needsAnswer) {
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            message.choices.forEach { choice ->
                ActionRow(label = choice, enabled = canReply) { onChoice(choice) }
            }
        }
    }
}

/** Right, like your side in Messages: the date flush right, the text indented under it. */
@Composable
private fun YourEntry(reply: Reply) {
    val state = when (reply.state) {
        ReplyState.Pending -> " · sending"
        ReplyState.Failed -> " · not sent"
        ReplyState.Sent -> ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.5f.gridUnitsAsDp(), start = 2f.gridUnitsAsDp()),
    ) {
        LightText(
            text = formatDateTime(reply.at) + state,
            variant = LightTextVariant.Detail,
            align = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        LightText(text = reply.choice ?: reply.text.orEmpty(), variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
    }
}
