package com.thelightphone.relayinbox

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
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
import java.security.SecureRandom

class MessageViewModel : LightViewModel<Unit>() {
    fun reply(context: SealedLightContext, messageId: String, choice: String? = null, text: String? = null) {
        viewModelScope.launch {
            val id = "r_" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
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

class MessageScreen(
    sealedActivity: SealedLightActivity,
    private val messageId: String,
) : LightScreen<Unit, MessageViewModel>(sealedActivity) {

    override val viewModelClass: Class<MessageViewModel>
        get() = MessageViewModel::class.java

    override fun createViewModel(): MessageViewModel = MessageViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val keys by Pairing.keys.collectAsState()
        val message = messages.firstOrNull { it.id == messageId }

        LaunchedEffect(messageId) { RelayStore.markRead(messageId) }

        val canReply = keys != null && inboxUrl().isNotEmpty()
        val answered = message?.replies?.any { it.choice != null && it.state != ReplyState.Failed } == true

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(message?.let { formatTime(it.receivedAt) } ?: ""),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    if (message == null) {
                        LightText(text = "This message is gone.", variant = LightTextVariant.Copy, lighten = true)
                        return@LightScrollView
                    }
                    LightText(text = message.headline, variant = LightTextVariant.Heading, modifier = Modifier.fillMaxWidth())
                    if (message.detail.isNotBlank()) {
                        LightText(
                            text = message.detail,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 0.75f.gridUnitsAsDp()),
                        )
                    }

                    if (message.choices.isNotEmpty() && !answered) {
                        Spacer(modifier = Modifier.height(1.5f.gridUnitsAsDp()))
                        Divider()
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        message.choices.forEach { choice ->
                            ActionRow(label = choice, enabled = canReply && !answered) {
                                viewModel.reply(lightContext, message.id, choice = choice)
                            }
                        }
                    }

                    if (message.replies.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(1.5f.gridUnitsAsDp()))
                        Divider()
                        message.replies.forEach { reply ->
                            LightText(
                                text = reply.label(),
                                variant = LightTextVariant.Copy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 1f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = formatTime(reply.at),
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
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

                val hasFailed = message?.replies?.any { it.state == ReplyState.Failed } == true
                LightBottomBar(
                    items = buildList {
                        if (message != null && canReply) {
                            add(
                                LightBarButton.Text(text = "Reply", onClick = {
                                    navigateTo(
                                        screenFactory = { TextEditorScreen(it, EditorRequest(title = "Reply", initialValue = "", initialCaps = true)) },
                                        resultCallback = { text ->
                                            if (text.isNotBlank()) viewModel.reply(lightContext, message.id, text = text.trim())
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
