package com.thelightphone.relayinbox

import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PairingViewModel : LightViewModel<Unit>() {
    private val _confirmingRotate = MutableStateFlow(false)
    val confirmingRotate: StateFlow<Boolean> = _confirmingRotate.asStateFlow()

    /** New keys take two taps: the old ones stop working on the box the moment you use them. */
    fun rotateTapped() {
        if (!_confirmingRotate.value) {
            _confirmingRotate.value = true
            return
        }
        _confirmingRotate.value = false
        viewModelScope.launch { Pairing.rotate() }
    }
}

/**
 * Everything the Unraid box needs, shown once: as a QR code (scan it with another phone
 * and send the text to yourself) and as text. With the phone on USB, `adb logcat -s RelayInbox`
 * prints the same block from a debug build.
 */
class PairingScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, PairingViewModel>(sealedActivity) {

    override val viewModelClass: Class<PairingViewModel>
        get() = PairingViewModel::class.java

    override fun createViewModel(): PairingViewModel = PairingViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val keys by Pairing.keys.collectAsState()
        val endpoint by Pairing.pushEndpoint.collectAsState(initial = null)
        val confirming by viewModel.confirmingRotate.collectAsState()

        LaunchedEffect(Unit) {
            openStores(lightContext.filesDir)
            Pairing.ensure()
        }

        val block = keys?.let { Pairing.settingsBlock(endpoint, it) }
        LaunchedEffect(block) {
            if (block != null && BuildConfig.DEBUG) Log.i(TAG, "Pairing settings:\n$block")
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Pairing"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    Status("Push", if (endpoint != null) "registered with LightOS" else "none yet; messages are fetched every 15 min")
                    Status("Reply inbox", inboxUrl().ifEmpty { "not set in this build" })

                    if (block != null) {
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        QrCode(block, modifier = Modifier.fillMaxWidth())
                        LightText(
                            text = "Put PUSH_KEY (and PUSH_ENDPOINT, once there is one) in the relay " +
                                "container's settings, and REPLY_TOKEN in the inbox container's.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = block,
                            variant = LightTextVariant.Fine,
                            monospace = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                    Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = if (confirming) "Replace keys?" else "New keys",
                            onClick = { viewModel.rotateTapped() },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Status(label: String, value: String) {
    LightText(text = label, variant = LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
    LightText(text = value, variant = LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
}
