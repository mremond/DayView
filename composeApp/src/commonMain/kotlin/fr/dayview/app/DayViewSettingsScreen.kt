package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.calendar_no_name
import fr.dayview.app.generated.resources.day_end
import fr.dayview.app.generated.resources.day_start
import fr.dayview.app.generated.resources.interval_minutes
import fr.dayview.app.generated.resources.modify
import fr.dayview.app.generated.resources.modify_label
import fr.dayview.app.generated.resources.settings_add_app
import fr.dayview.app.generated.resources.settings_add_apps
import fr.dayview.app.generated.resources.settings_autosave_note
import fr.dayview.app.generated.resources.settings_back
import fr.dayview.app.generated.resources.settings_calendar_permission_prompt
import fr.dayview.app.generated.resources.settings_calendars
import fr.dayview.app.generated.resources.settings_close
import fr.dayview.app.generated.resources.settings_day_description
import fr.dayview.app.generated.resources.settings_disabled_default_m
import fr.dayview.app.generated.resources.settings_disabled_default_p
import fr.dayview.app.generated.resources.settings_grant_calendar_access
import fr.dayview.app.generated.resources.settings_launch_at_login
import fr.dayview.app.generated.resources.settings_launch_at_login_description
import fr.dayview.app.generated.resources.settings_monochrome_icon
import fr.dayview.app.generated.resources.settings_monochrome_icon_description
import fr.dayview.app.generated.resources.settings_net_time_description
import fr.dayview.app.generated.resources.settings_net_time_footnote
import fr.dayview.app.generated.resources.settings_net_time_toggle
import fr.dayview.app.generated.resources.settings_no_apps_available
import fr.dayview.app.generated.resources.settings_no_apps_selected
import fr.dayview.app.generated.resources.settings_no_calendars
import fr.dayview.app.generated.resources.settings_on_goal_description
import fr.dayview.app.generated.resources.settings_remove
import fr.dayview.app.generated.resources.settings_remove_app
import fr.dayview.app.generated.resources.settings_section_day
import fr.dayview.app.generated.resources.settings_section_display
import fr.dayview.app.generated.resources.settings_section_net_time
import fr.dayview.app.generated.resources.settings_section_on_goal
import fr.dayview.app.generated.resources.settings_section_sounds
import fr.dayview.app.generated.resources.settings_show_seconds
import fr.dayview.app.generated.resources.settings_show_seconds_description
import fr.dayview.app.generated.resources.settings_sound_cues
import fr.dayview.app.generated.resources.settings_sounds_description
import fr.dayview.app.generated.resources.settings_title
import fr.dayview.app.generated.resources.sound_day_end_detail
import fr.dayview.app.generated.resources.sound_day_start_detail
import fr.dayview.app.generated.resources.sound_every
import fr.dayview.app.generated.resources.sound_interval_decrease
import fr.dayview.app.generated.resources.sound_interval_detail
import fr.dayview.app.generated.resources.sound_interval_increase
import fr.dayview.app.generated.resources.sound_interval_label
import fr.dayview.app.generated.resources.sound_interval_value
import fr.dayview.app.generated.resources.sound_preview
import fr.dayview.app.generated.resources.sound_volume
import fr.dayview.app.generated.resources.volume_decrease
import fr.dayview.app.generated.resources.volume_increase
import fr.dayview.app.generated.resources.volume_percent
import fr.dayview.app.generated.resources.volume_value
import org.jetbrains.compose.resources.stringResource

internal data class SettingsPlatformUiState(
    val monochromeMenuBarIcon: Boolean?,
    val launchAtLogin: Boolean?,
    val netTimeSupported: Boolean = false,
    val onGoalSupported: Boolean = false,
    val runningApps: () -> List<AppRef> = { emptyList() },
)

internal data class SettingsScreenActions(
    val changeStartTime: (Int) -> Unit,
    val changeEndTime: (Int) -> Unit,
    val changeShowSeconds: (Boolean) -> Unit,
    val changeMonochromeMenuBarIcon: ((Boolean) -> Unit)?,
    val changeLaunchAtLogin: ((Boolean) -> Unit)?,
    val changeSoundSettings: (SoundSettings) -> Unit,
    val previewSound: (SoundCue) -> Unit,
    val changeNetTimeSettings: (NetTimeSettings) -> Unit = {},
    val requestCalendarPermission: () -> Unit = {},
    val changeOnGoalApps: (Set<AppRef>) -> Unit = {},
    val back: () -> Unit,
)

@Composable
internal fun SettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val timePicker = rememberTimePickerLauncher()
    Box(
        modifier = Modifier.fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(colors.glow, colors.ink),
                    radius = 950f,
                ),
            )
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.settings_back),
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClick = actions.back)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.settings_title), color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            }

            Spacer(Modifier.height(48.dp))
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                Text(stringResource(Res.string.settings_section_day), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_day_description),
                    color = colors.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.panel, RoundedCornerShape(18.dp))
                        .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                Spacer(Modifier.height(24.dp))
                Text(stringResource(Res.string.settings_section_display), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.panel, RoundedCornerShape(18.dp))
                        .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                        .toggleable(
                            value = state.showSeconds,
                            role = Role.Switch,
                            onValueChange = actions.changeShowSeconds,
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.settings_show_seconds),
                            color = colors.cloud,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.settings_show_seconds_description),
                            color = colors.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = state.showSeconds,
                        onCheckedChange = null,
                    )
                }
                if (
                    platformState.monochromeMenuBarIcon != null &&
                    actions.changeMonochromeMenuBarIcon != null
                ) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(colors.panel, RoundedCornerShape(18.dp))
                            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                            .toggleable(
                                value = platformState.monochromeMenuBarIcon,
                                role = Role.Switch,
                                onValueChange = actions.changeMonochromeMenuBarIcon,
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.settings_monochrome_icon),
                                color = colors.cloud,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.settings_monochrome_icon_description),
                                color = colors.muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = platformState.monochromeMenuBarIcon,
                            onCheckedChange = null,
                        )
                    }
                }
                if (platformState.launchAtLogin != null && actions.changeLaunchAtLogin != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(colors.panel, RoundedCornerShape(18.dp))
                            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                            .toggleable(
                                value = platformState.launchAtLogin,
                                role = Role.Switch,
                                onValueChange = actions.changeLaunchAtLogin,
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.settings_launch_at_login),
                                color = colors.cloud,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.settings_launch_at_login_description),
                                color = colors.muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = platformState.launchAtLogin,
                            onCheckedChange = null,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                SoundSettingsPanel(
                    settings = state.soundSettings,
                    onSettingsChange = actions.changeSoundSettings,
                    onPreview = actions.previewSound,
                )
                if (platformState.netTimeSupported) {
                    Spacer(Modifier.height(24.dp))
                    NetTimeSettingsPanel(
                        settings = state.netTimeSettings,
                        calendars = state.availableCalendars,
                        hasPermission = state.netCalendarPermission,
                        onSettingsChange = actions.changeNetTimeSettings,
                        onRequestPermission = actions.requestCalendarPermission,
                    )
                }
                if (platformState.onGoalSupported) {
                    Spacer(Modifier.height(24.dp))
                    OnGoalAppsPanel(
                        onGoalApps = state.onGoalApps,
                        runningApps = platformState.runningApps,
                        onOnGoalAppsChange = actions.changeOnGoalApps,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.settings_autosave_note),
                    color = colors.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun NetTimeSettingsPanel(
    settings: NetTimeSettings,
    calendars: List<CalendarInfo>,
    hasPermission: Boolean,
    onSettingsChange: (NetTimeSettings) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(stringResource(Res.string.settings_section_net_time), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.settings_net_time_description),
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .toggleable(
                value = settings.enabled,
                role = Role.Switch,
                onValueChange = { onSettingsChange(settings.copy(enabled = it)) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.settings_net_time_toggle), color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.settings_disabled_default_m), color = colors.muted, fontSize = 11.sp)
        }
        Switch(checked = settings.enabled, onCheckedChange = null)
    }

    if (settings.enabled) {
        Spacer(Modifier.height(10.dp))
        if (!hasPermission) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(18.dp))
                    .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(Res.string.settings_calendar_permission_prompt),
                    color = colors.cloud,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .background(colors.mint.copy(alpha = .12f), RoundedCornerShape(10.dp))
                        .border(1.dp, colors.mint.copy(alpha = .25f), RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onRequestPermission)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Res.string.settings_grant_calendar_access),
                        color = colors.mint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .7.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(18.dp))
                    .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(Res.string.settings_calendars),
                    color = colors.muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                if (calendars.isEmpty()) {
                    Text(
                        stringResource(Res.string.settings_no_calendars),
                        color = colors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                } else {
                    calendars.forEachIndexed { index, calendar ->
                        if (index > 0) SettingsDivider()
                        val included = settings.includedCalendarIds.isEmpty() ||
                            calendar.id in settings.includedCalendarIds
                        NetTimeCalendarRow(
                            name = calendar.displayName.ifBlank { stringResource(Res.string.calendar_no_name) },
                            checked = included,
                            onCheckedChange = { checked ->
                                onSettingsChange(
                                    settings.copy(
                                        includedCalendarIds = nextIncludedCalendars(
                                            allIds = calendars.map { it.id },
                                            current = settings.includedCalendarIds,
                                            toggledId = calendar.id,
                                            include = checked,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.settings_net_time_footnote),
            color = colors.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun NetTimeCalendarRow(
    name: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun OnGoalAppsPanel(
    onGoalApps: Set<AppRef>,
    runningApps: () -> List<AppRef>,
    onOnGoalAppsChange: (Set<AppRef>) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(stringResource(Res.string.settings_section_on_goal), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.settings_on_goal_description),
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (onGoalApps.isEmpty()) {
            Text(
                stringResource(Res.string.settings_no_apps_selected),
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            onGoalApps.sortedBy { it.displayName.lowercase() }.forEachIndexed { index, app ->
                if (index > 0) SettingsDivider()
                OnGoalAppRow(
                    name = app.displayName,
                    onRemove = { onOnGoalAppsChange(onGoalApps - app) },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    var showPicker by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.minimumInteractiveComponentSize()
            .background(colors.mint.copy(alpha = .12f), RoundedCornerShape(10.dp))
            .border(1.dp, colors.mint.copy(alpha = .25f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = { showPicker = !showPicker })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showPicker) stringResource(Res.string.settings_close) else stringResource(Res.string.settings_add_apps),
            color = colors.mint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .7.sp,
        )
    }
    if (showPicker) {
        Spacer(Modifier.height(10.dp))
        val candidates = remember(showPicker) { runningApps() }.filter { it !in onGoalApps }
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            if (candidates.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_no_apps_available),
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                candidates.forEachIndexed { index, app ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(Res.string.settings_add_app, app.displayName),
                                onClick = { onOnGoalAppsChange(onGoalApps + app) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.displayName, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("+", color = colors.mint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnGoalAppRow(
    name: String,
    onRemove: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.settings_remove_app, name), onClick = onRemove)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(stringResource(Res.string.settings_remove), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun SoundSettingsPanel(
    settings: SoundSettings,
    onSettingsChange: (SoundSettings) -> Unit,
    onPreview: (SoundCue) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(stringResource(Res.string.settings_section_sounds), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.settings_sounds_description),
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .toggleable(
                value = settings.enabled,
                role = Role.Switch,
                onValueChange = { onSettingsChange(settings.copy(enabled = it)) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.settings_sound_cues), color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.settings_disabled_default_p), color = colors.muted, fontSize = 11.sp)
        }
        Switch(checked = settings.enabled, onCheckedChange = null)
    }

    if (settings.enabled) {
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SoundCueSettingRow(
                label = stringResource(Res.string.day_start),
                detail = stringResource(Res.string.sound_day_start_detail),
                checked = settings.startCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(startCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.DAY_START) },
            )
            SettingsDivider()
            SoundCueSettingRow(
                label = stringResource(Res.string.sound_interval_label),
                detail = stringResource(Res.string.sound_interval_detail),
                checked = settings.intervalCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(intervalCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.INTERVAL) },
            )
            if (settings.intervalCueEnabled) {
                val choices = SoundSettings.INTERVAL_CHOICES
                val currentIndex = choices.indexOf(SoundSettings.snapIntervalMinutes(settings.intervalMinutes))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.sound_every), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    TimeButton(
                        label = "−",
                        enabled = currentIndex > 0,
                        onClickLabel = stringResource(Res.string.sound_interval_decrease),
                        valueDescription = stringResource(Res.string.sound_interval_value, settings.intervalMinutes.toString()),
                    ) {
                        onSettingsChange(settings.copy(intervalMinutes = choices[currentIndex - 1]))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(Res.string.interval_minutes, settings.intervalMinutes.toString()), color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    TimeButton(
                        label = "+",
                        enabled = currentIndex < choices.lastIndex,
                        onClickLabel = stringResource(Res.string.sound_interval_increase),
                        valueDescription = stringResource(Res.string.sound_interval_value, settings.intervalMinutes.toString()),
                    ) {
                        onSettingsChange(settings.copy(intervalMinutes = choices[currentIndex + 1]))
                    }
                }
            }
            SettingsDivider()
            SoundCueSettingRow(
                label = stringResource(Res.string.day_end),
                detail = stringResource(Res.string.sound_day_end_detail),
                checked = settings.endCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(endCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.DAY_END) },
            )
            SettingsDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.sound_volume), color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(Res.string.volume_percent, settings.volumePercent.toString()), color = colors.cloud, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                TimeButton(
                    label = "−",
                    enabled = settings.volumePercent > 10,
                    onClickLabel = stringResource(Res.string.volume_decrease),
                    valueDescription = stringResource(Res.string.volume_value, settings.volumePercent.toString()),
                ) {
                    onSettingsChange(settings.copy(volumePercent = settings.volumePercent - 10))
                }
                Spacer(Modifier.width(8.dp))
                TimeButton(
                    label = "+",
                    enabled = settings.volumePercent < 100,
                    onClickLabel = stringResource(Res.string.volume_increase),
                    valueDescription = stringResource(Res.string.volume_value, settings.volumePercent.toString()),
                ) {
                    onSettingsChange(settings.copy(volumePercent = settings.volumePercent + 10))
                }
            }
        }
    }
}

@Composable
private fun SoundCueSettingRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        PreviewSoundButton(onPreview)
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewSoundButton(onClick: () -> Unit) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = Modifier.minimumInteractiveComponentSize()
            .background(colors.mint.copy(alpha = .12f), RoundedCornerShape(9.dp))
            .border(1.dp, colors.mint.copy(alpha = .25f), RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Res.string.sound_preview), color = colors.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
    }
}

@Composable
private fun SettingsDivider() {
    val colors = LocalDayViewColors.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.overlay.copy(alpha = .06f)))
}

@Composable
private fun TimePreferenceRow(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(Res.string.modify_label, label),
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
                color = colors.cloud,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(Res.string.modify),
            color = colors.mint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
