package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.system_settings_body
import fr.dayview.app.generated.resources.system_settings_open
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SystemSettingsScreen(onOpenPowerSettings: () -> Unit) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsSystemScreen)) {
        SettingsPanelCard {
            Text(
                stringResource(Res.string.system_settings_body),
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsAccentButton(
            text = stringResource(Res.string.system_settings_open),
            onClick = onOpenPowerSettings,
            modifier = Modifier.testTag(DayViewTestTags.SettingsOpenPowerSettings),
        )
    }
}
