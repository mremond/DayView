package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.modify
import fr.dayview.app.generated.resources.modify_label
import fr.dayview.app.generated.resources.settings_back
import fr.dayview.app.generated.resources.sound_preview
import org.jetbrains.compose.resources.stringResource

private val PanelShape = RoundedCornerShape(18.dp)

/**
 * Shared top bar for the full-screen destinations reached from Today: a "‹ TODAY" back
 * control on the left and a right-aligned screen [title]. Settings and History use this so
 * their headers stay identical in colour, type, and spacing.
 */
@Composable
internal fun ScreenTopBar(
    title: String,
    backTestTag: String,
    onBack: () -> Unit,
) {
    val colors = LocalDayViewColors.current
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
                .testTag(backTestTag)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(title, color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
    }
}

@Composable
internal fun SettingsPanelCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = modifier.fillMaxWidth()
            .background(colors.panel, PanelShape)
            .border(1.dp, colors.overlay.copy(alpha = .06f), PanelShape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = modifier.fillMaxWidth()
            .background(colors.panel, PanelShape)
            .border(1.dp, colors.overlay.copy(alpha = .06f), PanelShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text(description, color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsSectionHeader(title: String, description: String) {
    val colors = LocalDayViewColors.current
    Text(title, color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Text(description, color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
internal fun SettingsStepper(
    label: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decreaseLabel: String,
    increaseLabel: String,
    valueDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        TimeButton(
            label = "−",
            enabled = canDecrease,
            onClickLabel = decreaseLabel,
            valueDescription = valueDescription,
            onClick = onDecrease,
        )
        Spacer(Modifier.width(10.dp))
        Text(valueText, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        TimeButton(
            label = "+",
            enabled = canIncrease,
            onClickLabel = increaseLabel,
            valueDescription = valueDescription,
            onClick = onIncrease,
        )
    }
}

@Composable
internal fun SettingsAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier.minimumInteractiveComponentSize()
            .background(colors.mint.copy(alpha = .12f), RoundedCornerShape(10.dp))
            .border(1.dp, colors.mint.copy(alpha = .25f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
    }
}

@Composable
internal fun SettingsDivider() {
    val colors = LocalDayViewColors.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.overlay.copy(alpha = .06f)))
}

@Composable
internal fun TimePreferenceRow(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
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
                formatWallClock(hour, minute, uses24Hour),
                color = colors.cloud,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.modify), color = colors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
internal fun PreviewSoundButton(onClick: () -> Unit) {
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
