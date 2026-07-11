package fr.dayview.app

import androidx.compose.runtime.Composable

fun interface TimePickerLauncher {
    fun show(
        initialMinutes: Int,
        allowedMinutes: IntRange,
        onTimeSelected: (Int) -> Unit,
    )
}

@Composable
expect fun rememberTimePickerLauncher(): TimePickerLauncher
