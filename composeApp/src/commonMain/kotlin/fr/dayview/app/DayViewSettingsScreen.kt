package fr.dayview.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal data class SettingsPlatformUiState(
    val monochromeMenuBarIcon: Boolean?,
    val launchAtLogin: Boolean?,
)

internal data class SettingsScreenActions(
    val changeStartTime: (Int) -> Unit,
    val changeEndTime: (Int) -> Unit,
    val changeShowSeconds: (Boolean) -> Unit,
    val changeMonochromeMenuBarIcon: ((Boolean) -> Unit)?,
    val changeLaunchAtLogin: ((Boolean) -> Unit)?,
    val changeSoundSettings: (SoundSettings) -> Unit,
    val previewSound: (SoundCue) -> Unit,
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
                    "‹  AUJOURD’HUI",
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClick = actions.back)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("RÉGLAGES", color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            }

            Spacer(Modifier.height(48.dp))
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                Text("JOURNÉE", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Définissez la plage utilisée pour représenter le temps disponible chaque jour.",
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
                        label = "DÉBUT DE JOURNÉE",
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
                        label = "FIN DE JOURNÉE",
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
                Text("AFFICHAGE", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
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
                            "AFFICHER LES SECONDES",
                            color = colors.cloud,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Désactivez-les pour un affichage plus calme et moins de rafraîchissements.",
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
                                "ICÔNE DE BARRE DE MENU SANS COULEUR",
                                color = colors.cloud,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Utilise une icône monochrome plus discrète dans la barre de menu.",
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
                                "OUVRIR À LA CONNEXION",
                                color = colors.cloud,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Lance automatiquement DayView à l’ouverture de votre session.",
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
                Spacer(Modifier.height(12.dp))
                Text(
                    "Les changements sont enregistrés automatiquement et s’appliquent à tous les jours.",
                    color = colors.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
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
    Text("SONS", color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Des repères doux pour sentir le temps sans surveiller l’écran.",
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
            Text("REPÈRES SONORES", color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text("Désactivés par défaut.", color = colors.muted, fontSize = 11.sp)
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
                label = "DÉBUT DE JOURNÉE",
                detail = "Bol clair",
                checked = settings.startCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(startCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.DAY_START) },
            )
            SettingsDivider()
            SoundCueSettingRow(
                label = "REPÈRE INTERMÉDIAIRE",
                detail = "Tintement léger",
                checked = settings.intervalCueEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(intervalCueEnabled = it)) },
                onPreview = { onPreview(SoundCue.INTERVAL) },
            )
            if (settings.intervalCueEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TOUTES LES", color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    TimeButton(
                        label = "−",
                        enabled = settings.intervalMinutes > 30,
                        onClickLabel = "Diminuer l’intervalle des rappels de 30 minutes",
                        valueDescription = "Intervalle des rappels : ${settings.intervalMinutes} minutes",
                    ) {
                        onSettingsChange(settings.copy(intervalMinutes = settings.intervalMinutes - 30))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${settings.intervalMinutes} min", color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    TimeButton(
                        label = "+",
                        enabled = settings.intervalMinutes < 180,
                        onClickLabel = "Augmenter l’intervalle des rappels de 30 minutes",
                        valueDescription = "Intervalle des rappels : ${settings.intervalMinutes} minutes",
                    ) {
                        onSettingsChange(settings.copy(intervalMinutes = settings.intervalMinutes + 30))
                    }
                }
            }
            SettingsDivider()
            SoundCueSettingRow(
                label = "FIN DE JOURNÉE",
                detail = "Gong grave",
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
                    Text("VOLUME", color = colors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("${settings.volumePercent} %", color = colors.cloud, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                TimeButton(
                    label = "−",
                    enabled = settings.volumePercent > 10,
                    onClickLabel = "Diminuer le volume de 10 %",
                    valueDescription = "Volume : ${settings.volumePercent} %",
                ) {
                    onSettingsChange(settings.copy(volumePercent = settings.volumePercent - 10))
                }
                Spacer(Modifier.width(8.dp))
                TimeButton(
                    label = "+",
                    enabled = settings.volumePercent < 100,
                    onClickLabel = "Augmenter le volume de 10 %",
                    valueDescription = "Volume : ${settings.volumePercent} %",
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
        Text("ÉCOUTER", color = colors.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
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
                onClickLabel = "Modifier $label",
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
            "MODIFIER",
            color = colors.mint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
