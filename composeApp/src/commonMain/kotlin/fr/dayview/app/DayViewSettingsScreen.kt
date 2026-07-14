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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.settings_autosave_note
import fr.dayview.app.generated.resources.settings_day_description
import fr.dayview.app.generated.resources.settings_display_description
import fr.dayview.app.generated.resources.settings_net_time_description
import fr.dayview.app.generated.resources.settings_on_goal_description
import fr.dayview.app.generated.resources.settings_section_day
import fr.dayview.app.generated.resources.settings_section_display
import fr.dayview.app.generated.resources.settings_section_net_time
import fr.dayview.app.generated.resources.settings_section_on_goal
import fr.dayview.app.generated.resources.settings_section_sounds
import fr.dayview.app.generated.resources.settings_section_sync
import fr.dayview.app.generated.resources.settings_section_system
import fr.dayview.app.generated.resources.settings_sounds_description
import fr.dayview.app.generated.resources.settings_summary_apps
import fr.dayview.app.generated.resources.settings_summary_day
import fr.dayview.app.generated.resources.settings_summary_no_apps
import fr.dayview.app.generated.resources.settings_summary_off
import fr.dayview.app.generated.resources.settings_summary_on
import fr.dayview.app.generated.resources.settings_summary_seconds_off
import fr.dayview.app.generated.resources.settings_summary_seconds_on
import fr.dayview.app.generated.resources.settings_summary_sounds_on
import fr.dayview.app.generated.resources.settings_summary_sync
import fr.dayview.app.generated.resources.settings_summary_system
import fr.dayview.app.generated.resources.settings_system_description
import fr.dayview.app.generated.resources.settings_title
import fr.dayview.app.generated.resources.sync_settings_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
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
            // Fall back to the landing list if the open category is no longer
            // supported (e.g. the last on-goal target app closed), so we never
            // render a stranded sub-screen for an unavailable category.
            val category = state.settingsCategory?.takeIf { it in settingsCategoriesFor(platformState) }
            SettingsTopBar(
                onBack = if (category == null) actions.back else actions.closeCategory,
            )

            Spacer(Modifier.height(48.dp))
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                if (category == null) {
                    SettingsCategoryList(state = state, platformState = platformState, actions = actions)
                } else {
                    SettingsCategoryDetail(category = category, state = state, platformState = platformState, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    ScreenTopBar(
        title = stringResource(Res.string.settings_title),
        backTestTag = DayViewTestTags.SettingsBack,
        onBack = onBack,
    )
}

@Composable
private fun SettingsCategoryList(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
    settingsCategoriesFor(platformState).forEachIndexed { index, category ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        SettingsCategoryRow(category = category, state = state, platformState = platformState, onOpen = actions.openCategory)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(Res.string.settings_autosave_note),
        color = colors.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun SettingsCategoryDetail(
    category: SettingsCategory,
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    SettingsSectionHeader(title = categoryTitle(category), description = categoryDescription(category))
    Spacer(Modifier.height(18.dp))
    when (category) {
        SettingsCategory.DAY -> DaySettingsScreen(state = state, actions = actions)
        SettingsCategory.DISPLAY -> DisplaySettingsScreen(state = state, platformState = platformState, actions = actions)
        SettingsCategory.SOUNDS -> SoundSettingsScreen(
            settings = state.soundSettings,
            onSettingsChange = actions.changeSoundSettings,
            onPreview = actions.previewSound,
        )
        SettingsCategory.NET_TIME -> NetTimeSettingsScreen(
            settings = state.netTimeSettings,
            calendars = state.availableCalendars,
            hasPermission = state.netCalendarPermission,
            onSettingsChange = actions.changeNetTimeSettings,
            onRequestPermission = actions.requestCalendarPermission,
        )
        SettingsCategory.ON_GOAL -> OnGoalAppsScreen(
            onGoalApps = state.onGoalApps,
            runningApps = platformState.runningApps,
            onOnGoalAppsChange = actions.changeOnGoalApps,
        )
        SettingsCategory.SYNC -> SyncSettingsScreen(
            config = platformState.syncConfig,
            status = platformState.syncStatus,
            hasKey = platformState.syncHasKey,
            onConfigChange = actions.changeSyncConfig,
            onGenerateKey = actions.generateSyncKey,
            onPasteKey = actions.pasteSyncKey,
            onSyncNow = actions.syncNow,
            onClear = actions.clearSyncKey,
        )
        SettingsCategory.SYSTEM -> SystemSettingsScreen(onOpenPowerSettings = actions.openPowerSettings)
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    onOpen: (SettingsCategory) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = { onOpen(category) })
            .testTag(DayViewTestTags.settingsCategoryRow(category))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(categoryTitle(category), color = colors.cloud, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(4.dp))
            Text(categorySummary(category, state, platformState), color = colors.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text("›", color = colors.muted, fontSize = 18.sp)
    }
}

@Composable
private fun categoryTitle(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.DAY -> Res.string.settings_section_day
        SettingsCategory.DISPLAY -> Res.string.settings_section_display
        SettingsCategory.SOUNDS -> Res.string.settings_section_sounds
        SettingsCategory.NET_TIME -> Res.string.settings_section_net_time
        SettingsCategory.ON_GOAL -> Res.string.settings_section_on_goal
        SettingsCategory.SYNC -> Res.string.settings_section_sync
        SettingsCategory.SYSTEM -> Res.string.settings_section_system
    },
)

@Composable
private fun categoryDescription(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.DAY -> Res.string.settings_day_description
        SettingsCategory.DISPLAY -> Res.string.settings_display_description
        SettingsCategory.SOUNDS -> Res.string.settings_sounds_description
        SettingsCategory.NET_TIME -> Res.string.settings_net_time_description
        SettingsCategory.ON_GOAL -> Res.string.settings_on_goal_description
        SettingsCategory.SYNC -> Res.string.sync_settings_description
        SettingsCategory.SYSTEM -> Res.string.settings_system_description
    },
)

@Composable
private fun categorySummary(
    category: SettingsCategory,
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
): String = when (category) {
    SettingsCategory.DAY -> {
        val p = state.dayProgress
        val uses24Hour = LocalUses24HourClock.current
        val start = formatWallClock(p.startHour, p.startMinute, uses24Hour)
        val end = formatWallClock(p.endHour, p.endMinute, uses24Hour)
        stringResource(Res.string.settings_summary_day, start, end)
    }
    SettingsCategory.DISPLAY ->
        if (state.showSeconds) {
            stringResource(Res.string.settings_summary_seconds_on)
        } else {
            stringResource(Res.string.settings_summary_seconds_off)
        }
    SettingsCategory.SOUNDS ->
        if (state.soundSettings.enabled) {
            stringResource(Res.string.settings_summary_sounds_on, state.soundSettings.intervalMinutes.toString())
        } else {
            stringResource(Res.string.settings_summary_off)
        }
    SettingsCategory.NET_TIME ->
        if (state.netTimeSettings.enabled) {
            stringResource(Res.string.settings_summary_on)
        } else {
            stringResource(Res.string.settings_summary_off)
        }
    SettingsCategory.ON_GOAL ->
        if (state.onGoalApps.isEmpty()) {
            stringResource(Res.string.settings_summary_no_apps)
        } else {
            stringResource(Res.string.settings_summary_apps, state.onGoalApps.size.toString())
        }
    SettingsCategory.SYNC ->
        if (platformState.syncHasKey && platformState.syncConfig != null) {
            stringResource(Res.string.settings_summary_on)
        } else {
            stringResource(Res.string.settings_summary_sync)
        }
    SettingsCategory.SYSTEM -> stringResource(Res.string.settings_summary_system)
}
