package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

@Composable
actual fun rememberTimePickerLauncher(): TimePickerLauncher = remember {
    TimePickerLauncher { initialMinutes, allowedMinutes, onTimeSelected ->
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val safeInitial = initialMinutes.coerceIn(allowedMinutes)
        val hourPicker = JSpinner(SpinnerNumberModel(safeInitial / 60, 0, 23, 1))
        val minutePicker = JSpinner(SpinnerNumberModel(safeInitial % 60, 0, 59, 1))
        val panel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(JLabel("Heure"))
            add(hourPicker)
            add(JLabel("Minute"))
            add(minutePicker)
        }
        val result = JOptionPane.showConfirmDialog(
            null,
            panel,
            "Choisir l’heure",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (result == JOptionPane.OK_OPTION) {
            val selected = (hourPicker.value as Int) * 60 + minutePicker.value as Int
            onTimeSelected(selected.coerceIn(allowedMinutes))
        }
    }
}
