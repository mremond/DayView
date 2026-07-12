package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_end
import fr.dayview.app.generated.resources.day_start
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DaySettingsScreen(
    state: DayViewUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val timePicker = rememberTimePickerLauncher()
    SettingsPanelCard(
        modifier = Modifier.testTag(DayViewTestTags.SettingsDayScreen),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TimePreferenceRow(
            label = stringResource(Res.string.day_start),
            hour = progress.startHour,
            minute = progress.startMinute,
            onClick = {
                timePicker.show(
                    initialMinutes = state.startMinutes,
                    allowedMinutes = 0..state.endMinutes - 30,
                    onTimeSelected = actions.changeStartTime,
                )
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.overlay.copy(alpha = .06f)))
        TimePreferenceRow(
            label = stringResource(Res.string.day_end),
            hour = progress.endHour,
            minute = progress.endMinute,
            onClick = {
                timePicker.show(
                    initialMinutes = state.endMinutes,
                    allowedMinutes = state.startMinutes + 30..23 * 60 + 59,
                    onTimeSelected = actions.changeEndTime,
                )
            },
        )
    }
}
