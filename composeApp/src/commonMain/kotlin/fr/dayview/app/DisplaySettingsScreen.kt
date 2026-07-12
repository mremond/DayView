package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.settings_launch_at_login
import fr.dayview.app.generated.resources.settings_launch_at_login_description
import fr.dayview.app.generated.resources.settings_monochrome_icon
import fr.dayview.app.generated.resources.settings_monochrome_icon_description
import fr.dayview.app.generated.resources.settings_show_seconds
import fr.dayview.app.generated.resources.settings_show_seconds_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DisplaySettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsDisplayScreen)) {
        SettingsToggleRow(
            title = stringResource(Res.string.settings_show_seconds),
            description = stringResource(Res.string.settings_show_seconds_description),
            checked = state.showSeconds,
            onCheckedChange = actions.changeShowSeconds,
            modifier = Modifier.testTag(DayViewTestTags.SettingsShowSeconds),
        )
        if (platformState.monochromeMenuBarIcon != null && actions.changeMonochromeMenuBarIcon != null) {
            Spacer(Modifier.height(12.dp))
            SettingsToggleRow(
                title = stringResource(Res.string.settings_monochrome_icon),
                description = stringResource(Res.string.settings_monochrome_icon_description),
                checked = platformState.monochromeMenuBarIcon,
                onCheckedChange = actions.changeMonochromeMenuBarIcon,
            )
        }
        if (platformState.launchAtLogin != null && actions.changeLaunchAtLogin != null) {
            Spacer(Modifier.height(12.dp))
            SettingsToggleRow(
                title = stringResource(Res.string.settings_launch_at_login),
                description = stringResource(Res.string.settings_launch_at_login_description),
                checked = platformState.launchAtLogin,
                onCheckedChange = actions.changeLaunchAtLogin,
            )
        }
    }
}
