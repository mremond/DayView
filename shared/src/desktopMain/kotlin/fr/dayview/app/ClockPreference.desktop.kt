package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads the JVM locale's short time pattern. A pattern containing 'h'/'K'
 * (12-hour cycles) or 'a' (am/pm marker) in a real format field means the
 * locale uses a 12-hour clock. Quoted literal text (e.g. the `'h'` in the
 * Canadian French pattern `HH 'h' mm`) is stripped first so it isn't
 * mistaken for a format field.
 */
internal fun jvmUses24HourClock(locale: Locale = Locale.getDefault()): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)
        ?.toPattern()
        .orEmpty()
        .replace(Regex("'[^']*'"), "")
    return !(pattern.contains('h') || pattern.contains('K') || pattern.contains('a'))
}

/**
 * Read once per composition lifetime; deliberately does not live-track OS locale
 * changes made while the app keeps running.
 */
@Composable
actual fun rememberUses24HourClock(): Boolean = remember { jvmUses24HourClock() }
