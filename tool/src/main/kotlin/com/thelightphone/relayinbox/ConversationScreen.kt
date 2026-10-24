package com.thelightphone.relayinbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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
import java.io.File

/** The chat's bars, in grid units (1 unit = 1/27 of the screen width, 13.3 dp on the LP3). */
internal const val CHAT_TOP_BAR_UNITS = 3f            // 40 dp, the tap target; no gap below (was 1 unit)
internal const val CHAT_BOTTOM_BAR_GAP_UNITS = 0.25f  // was 1
internal const val CHAT_BOTTOM_BAR_UNITS = 3f         // 40 dp, was 4

class ConversationViewModel(filesDir: File) : LightViewModel<Unit>() {
    private val refresh = LiveRefresh(viewModelScope, filesDir)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = refresh.start()

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = refresh.stop()

    override fun onAppPause() = refresh.stop()

    /** Sends to [to]; [messageId] is only where it's kept here, and the question it answers if it's an answer. */
    fun reply(
        context: SealedLightContext,
        to: String?,
        messageId: String,
        choice: String? = null,
        text: String? = null,
        attachments: List<Attachment> = emptyList(),
        choiceIds: List<String>? = null,
        other: Boolean = false,
    ) {
        viewModelScope.launch {
            val id = "r_" + randomHex(8)
            RelayStore.addReply(
                messageId,
                Reply(id = id, choice = choice, text = text, at = System.currentTimeMillis(), attachments = attachments,
                    choiceIds = choiceIds, other = other, to = to),
            )
            scheduleReplySend(context)
        }
    }

    fun rename(id: String, subject: String) {
        viewModelScope.launch { Subjects.rename(id, subject) }
    }

    fun archive(channelId: String, then: () -> Unit) {
        viewModelScope.launch {
            Archive.archive(channelId)
            then()
        }
    }

    fun restore(channelId: String, then: () -> Unit) {
        viewModelScope.launch {
            Archive.restore(channelId)
            then()
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
 * One channel, oldest first and opening on the latest: everything from one agent, with your
 * replies in between. A message still waiting on a choice keeps its choices. Whatever you
 * write here goes to this channel's agent, named on the reply; there are no threads.
 */
class ConversationScreen(
    sealedActivity: SealedLightActivity,
    private val channelId: String,
) : LightScreen<Unit, ConversationViewModel>(sealedActivity) {

    override val viewModelClass: Class<ConversationViewModel>
        get() = ConversationViewModel::class.java

    override fun createViewModel(): ConversationViewModel = ConversationViewModel(lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val keys by Pairing.keys.collectAsState()
        val archive by Archive.state.collectAsState()
        val agents by Agents.state.collectAsState()
        val subjects by Subjects.state.collectAsState()
        val conversation = remember(messages, agents) { channels(messages, agents).firstOrNull { it.id == channelId } }
        val scrollState = rememberScrollState()
        // Follow the bottom while the layout settles and as messages arrive, until you scroll up.
        var pinnedToEnd by remember { mutableStateOf(true) }

        // Read covers anything that arrives while it's open, too.
        LaunchedEffect(messages) { conversation?.let { c -> RelayStore.markRead(c.messages.map { it.id }.toSet()) } }
        LaunchedEffect(scrollState.maxValue) {
            if (pinnedToEnd) scrollState.scrollTo(scrollState.maxValue)
        }
        LaunchedEffect(scrollState.value) {
            pinnedToEnd = scrollState.value >= scrollState.maxValue
        }

        val canReply = keys != null && inboxUrl().isNotEmpty()
        // Everything written here is for this channel's agent, whatever message it's kept under.
        val to = conversation?.replyTo(agents)
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(conversation?.agent ?: conversation?.messages?.first()?.headline.orEmpty()),
                    rightButton = conversation?.let { c ->
                        LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = {
                            val archived = c.isArchived(archive)
                            navigateTo(
                                screenFactory = {
                                    ConversationMenuScreen(it, c.agent ?: "Conversation", c.id, listOf(
                                        MenuItem("rename", "Rename subject"),
                                        if (archived) MenuItem("restore", "Move to inbox") else MenuItem("archive", "Archive"),
                                    ))
                                },
                                resultCallback = { key ->
                                    when (key) {
                                        "rename" -> navigateTo(
                                            screenFactory = {
                                                TextEditorScreen(it, EditorRequest(title = "Subject", initialValue = c.subject(subjects), initialCaps = true))
                                            },
                                            // Blank goes back to the automatic subject.
                                            resultCallback = { text -> if (text != null) viewModel.rename(c.id, text) },
                                        )
                                        // Archived: back to where you came from, which no longer lists it.
                                        "archive" -> viewModel.archive(channelId) { goBack() }
                                        "restore" -> viewModel.restore(channelId) { goBack() }
                                    }
                                },
                            )
                        })
                    },
                    // Compact, as Will asked: the LightOS bar is 3 units with a unit below it.
                    heightUnits = CHAT_TOP_BAR_UNITS,
                )

                LightScrollView(
                    scrollState = scrollState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 2f.gridUnitsAsDp(), end = 0.33f.gridUnitsAsDp()),
                ) {
                    if (conversation == null) {
                        LightText(text = "This conversation is gone.", variant = LightTextVariant.Copy, lighten = true)
                        return@LightScrollView
                    }
                    ToLine(conversation.agent, conversation.subject(subjects))
                    timeline(conversation).forEach { entry ->
                        when (entry) {
                            is Entry.FromAgent -> AgentEntry(
                                entry.message,
                                canReply,
                                filesDir = lightContext.filesDir,
                                onImage = { image ->
                                    navigateTo(screenFactory = { ImageScreen(it, image, entry.message.headline) })
                                },
                                onOther = {
                                    navigateTo(
                                        screenFactory = { TextEditorScreen(it, EditorRequest(title = "Other", initialValue = "", initialCaps = true)) },
                                        resultCallback = { text ->
                                            if (!text.isNullOrBlank()) viewModel.reply(lightContext, to, entry.message.id, text = text.trim(), other = true)
                                        },
                                    )
                                },
                            ) { picked ->
                                val send = {
                                    viewModel.reply(lightContext, to, entry.message.id, choice = picked.joinToString(", ") { it.label },
                                        choiceIds = picked.map { it.id })
                                }
                                val risky = picked.filter { it.danger }
                                if (risky.isEmpty()) send()
                                else navigateTo(
                                    screenFactory = {
                                        MenuScreen(it, "Are you sure?", listOf(
                                            MenuItem("yes", "Yes, ${risky.joinToString(", ") { o -> o.label }}"),
                                            MenuItem("no", "Cancel"),
                                        ))
                                    },
                                    resultCallback = { key -> if (key == "yes") send() },
                                )
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
                // Voice note and photo on the left, compose on the right as in Messages; Retry
                // between them when needed (an icon: a text button would cap the bar at 3).
                val latest = conversation?.latest?.takeIf { canReply }
                LightBottomBar(
                    // Compact: the LightOS bar is 4 units with a unit above it.
                    topPadding = CHAT_BOTTOM_BAR_GAP_UNITS.gridUnitsAsDp(),
                    heightUnits = CHAT_BOTTOM_BAR_UNITS,
                    items = listOfNotNull(
                        latest?.let {
                            LightBarButton.LightIcon(icon = LightIcons.MICROPHONE, onClick = {
                                navigateTo(
                                    screenFactory = { VoiceNoteScreen(it) },
                                    resultCallback = { note ->
                                        if (note != null) viewModel.reply(lightContext, to, latest.id, attachments = listOf(note))
                                    },
                                )
                            })
                        },
                        latest?.let {
                            LightBarButton.LightIcon(icon = LightIcons.CAMERA, onClick = {
                                navigateTo(
                                    screenFactory = { PhotoScreen(it) },
                                    resultCallback = { photo ->
                                        if (photo != null) viewModel.reply(lightContext, to, latest.id, attachments = listOf(photo))
                                    },
                                )
                            })
                        },
                        if (hasFailed && canReply) {
                            LightBarButton.LightIcon(icon = LightIcons.REFRESH, onClick = { viewModel.retry(lightContext) }, contentDescription = "Retry")
                        } else {
                            null
                        },
                        latest?.let {
                            LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = {
                                navigateTo(
                                    screenFactory = { TextEditorScreen(it, EditorRequest(title = "Reply", initialValue = "", initialCaps = true)) },
                                    resultCallback = { text ->
                                        if (text.isNotBlank()) viewModel.reply(lightContext, to, latest.id, text = text.trim())
                                    },
                                )
                            })
                        },
                    ),
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
private fun AgentEntry(
    message: RelayMessage,
    canReply: Boolean,
    filesDir: File,
    onImage: (MessageImage) -> Unit,
    onOther: () -> Unit,
    onChoice: (List<ChoiceOption>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 1.7f.gridUnitsAsDp())) {
        LightText(text = formatDateTime(message.receivedAt), variant = LightTextVariant.Detail, modifier = Modifier.fillMaxWidth())
        // Messages sets message text in Paragraph, not Copy.
        LightText(text = message.headline, variant = LightTextVariant.Paragraph, modifier = Modifier.fillMaxWidth())
        if (message.detail.isNotBlank()) {
            LightText(
                text = message.detail,
                variant = LightTextVariant.Paragraph,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.25f.gridUnitsAsDp()),
            )
        }
        message.image?.let { image ->
            InlineImage(
                image,
                filesDir,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), end = 1.67f.gridUnitsAsDp()),
                onClick = { onImage(image) },
            )
        }
        val options = message.choiceOptions
        if (options.isNotEmpty()) {
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            val answer = message.answer
            if (answer != null) {
                // Answered: the choices stay, locked, with a check on what you picked.
                val picked = message.pickedIds
                options.forEach { option ->
                    ActionRow(label = option.label, enabled = false, checked = option.id in picked, danger = option.danger) {}
                }
                if (message.other) ActionRow(label = "Other…", enabled = false, checked = answer.other) {}
            } else if (message.multiSelect) {
                var selected by remember(message.id) { mutableStateOf(emptySet<String>()) }
                options.forEach { option ->
                    ActionRow(label = option.label, enabled = canReply, checked = option.id in selected, danger = option.danger,
                        showBox = true) {
                        selected = if (option.id in selected) selected - option.id else selected + option.id
                    }
                }
                if (message.other) ActionRow(label = "Other…", enabled = canReply, onClick = onOther)
                ActionRow(label = "Done", enabled = canReply && selected.isNotEmpty(), underline = true) {
                    onChoice(options.filter { it.id in selected })
                }
            } else {
                options.forEach { option ->
                    ActionRow(label = option.label, enabled = canReply, danger = option.danger) { onChoice(listOf(option)) }
                }
                if (message.other) ActionRow(label = "Other…", enabled = canReply, onClick = onOther)
            }
        }
    }
}

/** Who you're writing to, and about what, above the messages, as a mail header would. */
@Composable
private fun ToLine(agent: String?, subject: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        agent?.let { LightText(text = "To: $it", variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth()) }
        if (subject.isNotBlank()) {
            LightText(text = subject, variant = LightTextVariant.Detail, lighten = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A voice note as a waveform and its length, not words. */
@Composable
private fun VoiceLabel(attachment: Attachment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LightIcon(LightIcons.AUDIO_MESSAGE, size = 1.2f, contentDescription = "voice note")
        attachment.durationMs?.let {
            LightText(
                text = formatDuration(it),
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(start = 0.4f.gridUnitsAsDp()),
            )
        }
    }
}

/** Right, like your side in Messages: the date flush right, the text indented under it. */
@Composable
private fun YourEntry(reply: Reply) {
    val session by Session.state.collectAsState()
    val state = when (reply.state) {
        ReplyState.Pending -> " · ${pendingStatus(waitingForHome(session = session))}"
        ReplyState.Failed -> " · not sent"
        ReplyState.Sent -> ""
    }
    // As in Messages: the text block is only as wide as it needs and sits against the right
    // edge, so a short reply is flush right and a long one fills the indent, left-aligned.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.7f.gridUnitsAsDp(), start = 2f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.End,
    ) {
        LightText(text = formatDateTime(reply.at) + state, variant = LightTextVariant.Detail)
        (reply.choice ?: reply.text)?.let { if (reply.other) "Other: $it" else it }?.let { LightText(text = it, variant = LightTextVariant.Paragraph) }
        reply.attachments.forEach { attachment ->
            if (attachment.isVoice) VoiceLabel(attachment) else LightText(text = "[${attachment.label()}]", variant = LightTextVariant.Paragraph)
            attachment.transcript?.takeIf { it.isNotBlank() }?.let { LightText(text = it, variant = LightTextVariant.Paragraph) }
            attachment.transcriptError?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true) }
            if (attachment.awaitingTranscript(reply)) {
                LightText(text = "Transcribing…", variant = LightTextVariant.Detail, lighten = true)
            }
        }
    }
}
