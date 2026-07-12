package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.desktop_choose_time
import fr.dayview.app.generated.resources.desktop_hour
import fr.dayview.app.generated.resources.desktop_minute
import org.jetbrains.compose.resources.stringResource
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

@Composable
actual fun rememberTimePickerLauncher(): TimePickerLauncher {
    // Resolve the strings while composing; the Swing dialog is shown later, outside composition.
    val hourLabel = stringResource(Res.string.desktop_hour)
    val minuteLabel = stringResource(Res.string.desktop_minute)
    val dialogTitle = stringResource(Res.string.desktop_choose_time)
    return remember(hourLabel, minuteLabel, dialogTitle) {
        TimePickerLauncher { initialMinutes, allowedMinutes, onTimeSelected ->
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val safeInitial = initialMinutes.coerceIn(allowedMinutes)
            val hourPicker = JSpinner(SpinnerNumberModel(safeInitial / 60, 0, 23, 1))
            val minutePicker = JSpinner(SpinnerNumberModel(safeInitial % 60, 0, 59, 1))
            val panel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                add(JLabel(hourLabel))
                add(hourPicker)
                add(JLabel(minuteLabel))
                add(minutePicker)
            }
            val result = JOptionPane.showConfirmDialog(
                null,
                panel,
                dialogTitle,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            )
            if (result == JOptionPane.OK_OPTION) {
                val selected = (hourPicker.value as Int) * 60 + minutePicker.value as Int
                onTimeSelected(selected.coerceIn(allowedMinutes))
            }
        }
    }
}
