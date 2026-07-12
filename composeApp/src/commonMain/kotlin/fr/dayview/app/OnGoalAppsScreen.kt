package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.settings_add_app
import fr.dayview.app.generated.resources.settings_add_apps
import fr.dayview.app.generated.resources.settings_close
import fr.dayview.app.generated.resources.settings_no_apps_available
import fr.dayview.app.generated.resources.settings_no_apps_selected
import fr.dayview.app.generated.resources.settings_remove
import fr.dayview.app.generated.resources.settings_remove_app
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OnGoalAppsScreen(
    onGoalApps: Set<AppRef>,
    runningApps: () -> List<AppRef>,
    onOnGoalAppsChange: (Set<AppRef>) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsOnGoalScreen)) {
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
