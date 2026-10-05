package com.thelightphone.relayinbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeOfDay = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val date = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/** "9:40 AM" today, "Mon 9:40 AM" this week, "Sep 3" before that, in the phone's locale. */
internal fun formatTime(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        at.toLocalDate() == today -> timeOfDay.format(at)
        at.toLocalDate().isAfter(today.minusDays(6)) -> "${weekday.format(at)} ${timeOfDay.format(at)}"
        else -> date.format(at)
    }
}

/** "You: Yes", plus how it's going until it has reached the relay. [full] keeps all of typed text. */
internal fun Reply.label(full: Boolean = false): String {
    val what = choice ?: if (full) text.orEmpty() else "“${text.orEmpty().take(40)}”"
    return when (state) {
        ReplyState.Pending -> "You: $what · sending"
        ReplyState.Sent -> "You: $what"
        ReplyState.Failed -> "You: $what · not sent"
    }
}

@Composable
internal fun Divider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LightThemeTokens.colors.content),
    )
}

/** A full-width tappable line of centred text — one per choice. */
@Composable
internal fun ActionRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.6f.gridUnitsAsDp())
            .then(if (enabled) Modifier.lightClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = !enabled,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Black on white regardless of theme — scanners want it that way round. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
        )
    }
    Canvas(modifier = modifier.aspectRatio(1f)) {
        drawRect(Color.White)
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // A hair of overlap so no seams show between modules.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
