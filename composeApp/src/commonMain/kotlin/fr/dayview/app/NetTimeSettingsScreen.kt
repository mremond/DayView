package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.calendar_no_name
import fr.dayview.app.generated.resources.settings_calendar_permission_prompt
import fr.dayview.app.generated.resources.settings_calendars
import fr.dayview.app.generated.resources.settings_disabled_default_m
import fr.dayview.app.generated.resources.settings_grant_calendar_access
import fr.dayview.app.generated.resources.settings_net_time_footnote
import fr.dayview.app.generated.resources.settings_net_time_toggle
import fr.dayview.app.generated.resources.settings_no_calendars
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NetTimeSettingsScreen(
    settings: NetTimeSettings,
    calendars: List<CalendarInfo>,
    hasPermission: Boolean,
    onSettingsChange: (NetTimeSettings) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsNetTimeScreen)) {
        SettingsToggleRow(
            title = stringResource(Res.string.settings_net_time_toggle),
            description = stringResource(Res.string.settings_disabled_default_m),
            checked = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
        )

        if (settings.enabled) {
            Spacer(Modifier.height(10.dp))
            if (!hasPermission) {
                SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
                    Text(
                        stringResource(Res.string.settings_calendar_permission_prompt),
                        color = colors.cloud,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingsAccentButton(
                        text = stringResource(Res.string.settings_grant_calendar_access),
                        onClick = onRequestPermission,
                    )
                }
            } else {
                SettingsPanelCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
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
