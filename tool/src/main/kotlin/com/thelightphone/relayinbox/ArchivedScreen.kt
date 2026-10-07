package com.thelightphone.relayinbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

class ArchivedViewModel : LightViewModel<Unit>()

/** Conversations you've archived that nothing new has happened in. Open one to move it back. */
class ArchivedScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, ArchivedViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArchivedViewModel>
        get() = ArchivedViewModel::class.java

    override fun createViewModel(): ArchivedViewModel = ArchivedViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by RelayStore.state.collectAsState()
        val archive by Archive.state.collectAsState()
        val archived = remember(messages, archive) { conversations(messages).filter { it.isArchived(archive) } }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Archived"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                if (archived.isEmpty()) {
                    LightText(
                        text = "Nothing archived.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                } else {
                    ConversationList(archived, modifier = Modifier.weight(1f)) { conversation ->
                        navigateTo(screenFactory = { ConversationScreen(it, conversation.id) })
                    }
                }
            }
        }
    }
}
