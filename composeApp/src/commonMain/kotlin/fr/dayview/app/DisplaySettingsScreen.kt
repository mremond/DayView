package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.settings_launch_at_login
import fr.dayview.app.generated.resources.settings_launch_at_login_description
import fr.dayview.app.generated.resources.settings_monochrome_icon
import fr.dayview.app.generated.resources.settings_monochrome_icon_description
import fr.dayview.app.generated.resources.settings_show_seconds
import fr.dayview.app.generated.resources.settings_show_seconds_description
import fr.dayview.app.generated.resources.settings_theme_dark
import fr.dayview.app.generated.resources.settings_theme_description
import fr.dayview.app.generated.resources.settings_theme_label
import fr.dayview.app.generated.resources.settings_theme_light
import fr.dayview.app.generated.resources.settings_theme_system
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DisplaySettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsDisplayScreen)) {
        ThemeModeSelector(
            selected = state.themeMode,
            onSelect = actions.changeThemeMode,
        )
        Spacer(Modifier.height(12.dp))
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

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = LocalDayViewColors.current
    SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            stringResource(Res.string.settings_theme_label),
            color = colors.cloud,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.settings_theme_description),
            color = colors.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsThemeMode),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeModeSegment(
                label = stringResource(Res.string.settings_theme_system),
                tag = DayViewTestTags.SettingsThemeSystem,
                active = selected == ThemeMode.SYSTEM,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(ThemeMode.SYSTEM) },
            )
            Spacer(Modifier.width(8.dp))
            ThemeModeSegment(
                label = stringResource(Res.string.settings_theme_light),
                tag = DayViewTestTags.SettingsThemeLight,
                active = selected == ThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(ThemeMode.LIGHT) },
            )
            Spacer(Modifier.width(8.dp))
            ThemeModeSegment(
                label = stringResource(Res.string.settings_theme_dark),
                tag = DayViewTestTags.SettingsThemeDark,
                active = selected == ThemeMode.DARK,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(ThemeMode.DARK) },
            )
        }
    }
}

@Composable
private fun ThemeModeSegment(
    label: String,
    tag: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier
            .testTag(tag)
            .background(
                if (active) colors.mint.copy(alpha = .16f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .border(
                1.dp,
                if (active) colors.mint.copy(alpha = .35f) else colors.overlay.copy(alpha = .10f),
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) colors.mint else colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .6.sp,
        )
    }
}
