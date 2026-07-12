package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reads the JVM locale's short time pattern. A pattern containing 'h'/'K'
 * (12-hour cycles) or 'a' (am/pm marker) means the locale uses a 12-hour clock.
 */
internal fun jvmUses24HourClock(locale: Locale = Locale.getDefault()): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)
        ?.toPattern()
        .orEmpty()
    return !(pattern.contains('h') || pattern.contains('K') || pattern.contains('a'))
}

@Composable
actual fun rememberUses24HourClock(): Boolean = remember { jvmUses24HourClock() }
