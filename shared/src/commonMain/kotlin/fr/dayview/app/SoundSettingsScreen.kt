package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_end
import fr.dayview.app.generated.resources.day_start
import fr.dayview.app.generated.resources.interval_minutes
import fr.dayview.app.generated.resources.settings_disabled_default_p
import fr.dayview.app.generated.resources.settings_sound_cues
import fr.dayview.app.generated.resources.sound_day_end_detail
import fr.dayview.app.generated.resources.sound_day_start_detail
import fr.dayview.app.generated.resources.sound_every
import fr.dayview.app.generated.resources.sound_interval_decrease
import fr.dayview.app.generated.resources.sound_interval_detail
import fr.dayview.app.generated.resources.sound_interval_increase
import fr.dayview.app.generated.resources.sound_interval_label
import fr.dayview.app.generated.resources.sound_interval_value
import fr.dayview.app.generated.resources.sound_volume
import fr.dayview.app.generated.resources.volume_decrease
import fr.dayview.app.generated.resources.volume_increase
import fr.dayview.app.generated.resources.volume_percent
import fr.dayview.app.generated.resources.volume_value
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SoundSettingsScreen(
    settings: SoundSettings,
    onSettingsChange: (SoundSettings) -> Unit,
    onPreview: (SoundCue) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsSoundsScreen)) {
        SettingsToggleRow(
            title = stringResource(Res.string.settings_sound_cues),
            description = stringResource(Res.string.settings_disabled_default_p),
            checked = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
        )

        if (settings.enabled) {
            Spacer(Modifier.height(10.dp))
            SettingsPanelCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
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
                    SettingsStepper(
                        label = stringResource(Res.string.sound_every),
                        valueText = stringResource(Res.string.interval_minutes, settings.intervalMinutes.toString()),
                        canDecrease = currentIndex > 0,
                        canIncrease = currentIndex < choices.lastIndex,
                        decreaseLabel = stringResource(Res.string.sound_interval_decrease),
                        increaseLabel = stringResource(Res.string.sound_interval_increase),
                        valueDescription = stringResource(Res.string.sound_interval_value, settings.intervalMinutes.toString()),
                        onDecrease = { onSettingsChange(settings.copy(intervalMinutes = choices[currentIndex - 1])) },
                        onIncrease = { onSettingsChange(settings.copy(intervalMinutes = choices[currentIndex + 1])) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
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
