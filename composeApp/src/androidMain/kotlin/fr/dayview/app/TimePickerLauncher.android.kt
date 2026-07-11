package fr.dayview.app

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberTimePickerLauncher(): TimePickerLauncher {
    val context = LocalContext.current
    return remember(context) {
        TimePickerLauncher { initialMinutes, allowedMinutes, onTimeSelected ->
            val safeInitial = initialMinutes.coerceIn(0, 23 * 60 + 59)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onTimeSelected((hour * 60 + minute).coerceIn(allowedMinutes))
                },
                safeInitial / 60,
                safeInitial % 60,
                DateFormat.is24HourFormat(context),
            ).show()
        }
    }
}
