package com.borovas.animadroid.core.designsystem

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when running on an Android TV / leanback device. Provided by [AnimadroidTheme]. */
val LocalIsTelevision = compositionLocalOf { false }

/** Pure check so it can be unit tested: the UI mode type is the low nibble of `uiMode`. */
fun isTelevisionUiMode(uiMode: Int): Boolean = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

fun Context.isTelevision(): Boolean =
    getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** Content wider than this stops growing; extra width becomes margin (keeps lines/grids readable on desktop-sized windows). */
val MaxContentWidth: Dp = 1240.dp

fun Modifier.maxContentWidth(max: Dp = MaxContentWidth): Modifier = this.widthIn(max = max)

/** Minimum poster cell width for adaptive grids; slightly larger on TV (10-foot UI). */
@Composable
fun posterCellMinWidth(): Dp = if (LocalIsTelevision.current) 170.dp else 130.dp

data class ContextMenuAction(val label: String, val onClick: () -> Unit)

/**
 * Makes an item pointer-, keyboard- and D-pad-friendly: hover highlight, hand cursor, visible focus ring,
 * click, and a context menu opened by long press or mouse right-click (secondary button).
 * [menu] items are shown in a [DropdownMenu] anchored to the item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    menu: List<ContextMenuAction> = emptyList(),
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ring = MaterialTheme.colorScheme.primary
    val hoverTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val hasMenu = menu.isNotEmpty()
    Box(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
            .clip(shape)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .hoverable(source)
            .then(
                if (hasMenu) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                e.changes.forEach { it.consume() }
                                menuOpen = true
                            }
                        }
                    }
                } else Modifier,
            )
            .combinedClickable(
                interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled,
                onClick = onClick, onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
            )
            .drawWithContent { drawContent(); if (hovered && enabled) drawRect(hoverTint) },
    ) {
        content()
        if (hasMenu) DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() }) }
        }
    }
}

/** Typography/spacing multiplier on TV. */
internal const val TV_TEXT_SCALE = 1.15f

@Composable
internal fun ProvideTelevision(content: @Composable () -> Unit) {
    val tv = LocalContext.current.isTelevision()
    CompositionLocalProvider(LocalIsTelevision provides tv, content = content)
}
