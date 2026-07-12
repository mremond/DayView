package fr.dayview.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.desktop_choose_time
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.dialog_ok
import org.jetbrains.compose.resources.stringResource

private data class PendingTimeRequest(
    val initialMinutes: Int,
    val allowedMinutes: IntRange,
    val onTimeSelected: (Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun rememberTimePickerLauncher(): TimePickerLauncher {
    var request by remember { mutableStateOf<PendingTimeRequest?>(null) }
    val use24Hour = LocalUses24HourClock.current
    request?.let { req ->
        val safeInitial = req.initialMinutes.coerceIn(req.allowedMinutes)
        val state = rememberTimePickerState(
            initialHour = safeInitial / 60,
            initialMinute = safeInitial % 60,
            is24Hour = use24Hour,
        )
        AlertDialog(
            onDismissRequest = { request = null },
            title = { Text(stringResource(Res.string.desktop_choose_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val selected = (state.hour * 60 + state.minute).coerceIn(req.allowedMinutes)
                    req.onTimeSelected(selected)
                    request = null
                }) { Text(stringResource(Res.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) { Text(stringResource(Res.string.dialog_cancel)) }
            },
        )
    }
    return remember {
        TimePickerLauncher { initialMinutes, allowedMinutes, onTimeSelected ->
            request = PendingTimeRequest(initialMinutes, allowedMinutes, onTimeSelected)
        }
    }
}
