package fr.dayview.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Keyboard affordances that are intentionally limited to the desktop app. */
internal expect fun desktopKeyboardShortcutsEnabled(): Boolean

internal fun Modifier.startFocusOnCommandEnter(
    onStart: () -> Unit,
): Modifier = if (!desktopKeyboardShortcutsEnabled()) {
    this
} else {
    onPreviewKeyEvent { event ->
        val handled =
            event.type == KeyEventType.KeyDown &&
                event.key == Key.Enter &&
                event.isMetaPressed
        if (handled) onStart()
        handled
    }
}

/**
 * Handles horizontal arrows after the focused child, so an editable duration field keeps
 * ownership of its cursor keys while the surrounding stepper buttons can adjust the value.
 */
internal fun Modifier.adjustDurationWithArrowKeys(
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
): Modifier = if (!desktopKeyboardShortcutsEnabled()) {
    this
} else {
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionLeft -> {
                onDecrease()
                true
            }
            Key.DirectionRight -> {
                onIncrease()
                true
            }
            else -> false
        }
    }
}

internal fun Modifier.dismissOnEscape(onDismiss: () -> Unit): Modifier = if (!desktopKeyboardShortcutsEnabled()) {
    this
} else {
    onPreviewKeyEvent { event ->
        val handled = event.type == KeyEventType.KeyDown && event.key == Key.Escape
        if (handled) onDismiss()
        handled
    }
}
