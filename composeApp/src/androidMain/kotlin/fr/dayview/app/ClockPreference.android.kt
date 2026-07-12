package fr.dayview.app

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Read once per composition lifetime; deliberately does not live-track OS clock/locale
 * changes made while the app keeps running (e.g. flipping "Use 24-hour format" in
 * Settings takes effect on the next launch, not immediately).
 */
@Composable
actual fun rememberUses24HourClock(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}
