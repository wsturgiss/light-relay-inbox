package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

private const val BOTTOMBAR_HEIGHT_UNITS = 4f
private const val HORIZONTAL_PADDING_MULTI_UNITS = 2f
private const val TOP_MARGIN_UNITS = 1f

private val BOTTOMBAR_TEXT_VARIANT = LightTextVariant.Button

/**
 * Bottom action bar matching LightOS ActionBar
 *
 * - allows up to 5 items (icons or custom painters)
 * - if any item is [LightBottomBarItem.Text], at most 3 items are allowed
 */
@Composable
fun LightBottomBar(
    items: List<LightBottomBarItem?>,
    modifier: Modifier = Modifier,
    topPadding: Dp = TOP_MARGIN_UNITS.gridUnitsAsDp(),
    /** The bar's height in grid units; a compact screen can pass less than the LightOS default. */
    heightUnits: Float = BOTTOMBAR_HEIGHT_UNITS,
) {
    require(items.size <= 5) { "LightBottomBar supports at most 5 items" }

    val textItemCount = items.count { it is LightBarButton.Text }
    require(textItemCount == 0 || items.size <= 3) {
        "LightBottomBar with text supports at most 3 items"
    }

    val barHeight = heightUnits.gridUnitsAsDp()
    val horizontalPadding = when (items.size) {
        0, 1 -> 0f.gridUnitsAsDp()
        else -> HORIZONTAL_PADDING_MULTI_UNITS.gridUnitsAsDp()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .height(barHeight)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (items.size) {
            0 -> Unit

            1 -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightBottomBarItemView(items[0], heightUnits)
                }
            }

            2 -> {
                LightBottomBarSlot(align = Alignment.CenterStart) {
                    LightBottomBarItemView(items[0], heightUnits)
                }
                LightBottomBarSlot(align = Alignment.CenterEnd) {
                    LightBottomBarItemView(items[1], heightUnits)
                }
            }

            3 -> {
                if (isMixedIconTextIconLayout(items)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items.forEach { item ->
                            LightBottomBarItemView(item, heightUnits)
                        }
                    }
                } else {
                    LightBottomBarSlot(align = Alignment.CenterStart) {
                        LightBottomBarItemView(items[0], heightUnits)
                    }
                    LightBottomBarSlot(align = Alignment.Center) {
                        LightBottomBarItemView(items[1], heightUnits)
                    }
                    LightBottomBarSlot(align = Alignment.CenterEnd) {
                        LightBottomBarItemView(items[2], heightUnits)
                    }
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        LightBottomBarItemView(item, heightUnits)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LightBottomBarSlot(
    align: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = align,
    ) {
        content()
    }
}

@Composable
private fun LightBottomBarItemView(item: LightBottomBarItem?, heightUnits: Float) {
    LightBarButtonView(
        button = item,
        heightUnits = heightUnits,
        iconSizeUnits = LightBarButtonDefaults.ICON_SIZE_UNITS,
        textVariant = BOTTOMBAR_TEXT_VARIANT,
        useSpacerWhenNull = true,
    )
}

private fun isMixedIconTextIconLayout(items: List<LightBottomBarItem?>): Boolean {
    if (items.size != 3) return false

    val firstIsIconOrEmpty =
        items[0] == null || items[0] is LightBarButton.LightIcon || items[0] is LightBarButton.Icon
    val centerIsText = items[1] is LightBarButton.Text
    val lastIsIconOrEmpty =
        items[2] == null || items[2] is LightBarButton.LightIcon || items[2] is LightBarButton.Icon

    return firstIsIconOrEmpty && centerIsText && lastIsIconOrEmpty
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightBottomBarIconsDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
        ) {
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(icon = LightIcons.DIALPAD, onClick = {}),
                    LightBarButton.LightIcon(icon = LightIcons.SEARCH, onClick = {}),
                    LightBarButton.LightIcon(icon = LightIcons.CONTACTS, onClick = {}),
                ),
            )
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightBottomBarMixedLight() {
    LightTheme(colors = LightThemeColors.Light) {
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.MICROPHONE, onClick = {}),
                LightBarButton.Text(text = "A LONGER LABEL", onClick = {}),
                LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = {}),
            ),
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightBottomBarFiveIconsDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.DIRECTIONS_FERRY, onClick = {}),
                LightBarButton.LightIcon(icon = LightIcons.DIRECTIONS_BUS, onClick = {}),
                LightBarButton.LightIcon(icon = LightIcons.DIRECTIONS_PEDESTRIAN, onClick = {}),
                LightBarButton.LightIcon(icon = LightIcons.DIRECTIONS_SUBWAY, onClick = {}),
                LightBarButton.LightIcon(icon = LightIcons.DIRECTIONS_TRAIN, onClick = {}),
            ),
        )
    }
}
