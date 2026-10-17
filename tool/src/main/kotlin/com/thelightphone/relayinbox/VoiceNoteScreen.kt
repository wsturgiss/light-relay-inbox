package com.thelightphone.relayinbox

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioException
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VoiceNoteState { NeedsPermission, Ready, Recording, Review }

/** A note stops itself here (issue #12: about two minutes); the relay takes 10 MB, which AAC never nears. */
internal const val MAX_RECORDING_MS = 2 * 60_000L

class VoiceNoteViewModel(private val filesDir: File, audio: LightAudio) : LightViewModel<Attachment>() {
    val state = MutableStateFlow(VoiceNoteState.NeedsPermission)
    val elapsedMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val recorder = audio.newRecorder()
    private val player = audio.newPlayer()
    private var recording: Attachment? = null
    private var file: File? = null
    private var startedAt = 0L
    private var ticker: Job? = null
    /** Opening the screen starts recording, once (issue #12); after Delete, the microphone records again. */
    private var autoStarted = false

    init {
        viewModelScope.launch { player.isPlaying.collect { playing.value = it } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Attachment>) {
        // Back from the permission prompt lands here too.
        viewModelScope.launch {
            val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            if (state.value == VoiceNoteState.NeedsPermission && granted) state.value = VoiceNoteState.Ready
            if (!granted) state.value = VoiceNoteState.NeedsPermission
            if (state.value == VoiceNoteState.Ready && !autoStarted) {
                autoStarted = true
                record()
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Attachment>) {
        if (state.value == VoiceNoteState.Recording) stop()
        player.pause()
    }

    fun record() {
        if (state.value != VoiceNoteState.Ready) return
        error.value = null
        val id = newAttachmentId()
        val target = File(attachmentsDir(filesDir), "$id.m4a")
        try {
            recorder.start(target)
        } catch (e: LightAudioException) {
            Log.w(TAG, "Recording didn't start", e)
            error.value = e.message ?: "Couldn't start recording."
            return
        }
        file = target
        recording = Attachment(id = id, type = "audio/mp4", path = target.absolutePath)
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs.value = 0
        state.value = VoiceNoteState.Recording
        ticker = viewModelScope.launch {
            while (isActive) {
                elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs.value >= MAX_RECORDING_MS) {
                    stop()
                    break
                }
                delay(200)
            }
        }
    }

    fun stop() {
        if (state.value != VoiceNoteState.Recording) return
        ticker?.cancel()
        val duration = recorder.stop()
        val recorded = file?.takeIf { duration > 0 && it.exists() }
        if (recorded == null) {
            discard()
            error.value = "Nothing was recorded."
            return
        }
        elapsedMs.value = duration
        recording = recording?.copy(bytes = recorded.length(), durationMs = duration)
        player.setSource(recorded)
        state.value = VoiceNoteState.Review
    }

    fun togglePlayback() {
        if (playing.value) player.pause() else {
            if (player.durationMs.value > 0 && player.positionMs.value >= player.durationMs.value) player.seekTo(0)
            player.play()
        }
    }

    fun discard() {
        player.stop()
        file?.delete()
        file = null
        recording = null
        elapsedMs.value = 0
        state.value = VoiceNoteState.Ready
    }

    /** The finished note, handed over to its reply; the screen no longer owns the file. */
    fun take(): Attachment? {
        player.stop()
        return recording.also {
            recording = null
            file = null
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        // Left without sending: the file goes with the screen.
        if (state.value == VoiceNoteState.Recording) recorder.cancel()
        file?.delete()
        recorder.release()
        player.release()
        super.onCleared()
    }
}

/** Record a voice note: it starts recording at once; stop, listen back, then send or delete. Returns the note. */
class VoiceNoteScreen(
    private val sealedActivity: SealedLightActivity,
) : LightScreen<Attachment, VoiceNoteViewModel>(sealedActivity) {

    override val viewModelClass: Class<VoiceNoteViewModel>
        get() = VoiceNoteViewModel::class.java

    override fun createViewModel(): VoiceNoteViewModel = VoiceNoteViewModel(lightContext.filesDir, DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val permission = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val state by viewModel.state.collectAsState()
        val elapsed by viewModel.elapsedMs.collectAsState()
        val playing by viewModel.playing.collectAsState()
        val error by viewModel.error.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Voice note"),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = when (state) {
                            VoiceNoteState.NeedsPermission -> "Relay Inbox needs the microphone to record."
                            VoiceNoteState.Ready -> error ?: "Tap the microphone to record."
                            VoiceNoteState.Recording -> "Recording\n${formatDuration(elapsed)} of ${formatDuration(MAX_RECORDING_MS)}"
                            VoiceNoteState.Review -> formatDuration(elapsed)
                        },
                        variant = if (state == VoiceNoteState.Recording || state == VoiceNoteState.Review) {
                            LightTextVariant.Heading
                        } else {
                            LightTextVariant.Copy
                        },
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LightBottomBar(
                    items = when (state) {
                        VoiceNoteState.NeedsPermission -> listOf(LightBarButton.Text(text = "Allow", onClick = { permission?.launch() }))
                        VoiceNoteState.Ready -> listOf(null, LightBarButton.LightIcon(icon = LightIcons.MICROPHONE, onClick = viewModel::record), null)
                        VoiceNoteState.Recording -> listOf(null, LightBarButton.LightIcon(icon = LightIcons.STOP, onClick = viewModel::stop), null)
                        VoiceNoteState.Review -> listOf(
                            LightBarButton.LightIcon(icon = LightIcons.DELETE, onClick = viewModel::discard),
                            LightBarButton.LightIcon(icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY, onClick = viewModel::togglePlayback),
                            LightBarButton.LightIcon(icon = LightIcons.SEND, onClick = { viewModel.take()?.let { goBack(it) } }),
                        )
                    },
                )
            }
        }
    }
}
