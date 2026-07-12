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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import fr.dayview.app.generated.resources.settings_back
import fr.dayview.app.generated.resources.settings_day_description
import fr.dayview.app.generated.resources.settings_net_time_description
import fr.dayview.app.generated.resources.settings_on_goal_description
import fr.dayview.app.generated.resources.settings_section_day
import fr.dayview.app.generated.resources.settings_section_display
import fr.dayview.app.generated.resources.settings_section_net_time
import fr.dayview.app.generated.resources.settings_section_on_goal
import fr.dayview.app.generated.resources.settings_section_sounds
import fr.dayview.app.generated.resources.settings_show_seconds_description
import fr.dayview.app.generated.resources.settings_sounds_description
import fr.dayview.app.generated.resources.settings_summary_apps
import fr.dayview.app.generated.resources.settings_summary_day
import fr.dayview.app.generated.resources.settings_summary_no_apps
import fr.dayview.app.generated.resources.settings_summary_off
import fr.dayview.app.generated.resources.settings_summary_on
import fr.dayview.app.generated.resources.settings_summary_seconds_off
import fr.dayview.app.generated.resources.settings_summary_seconds_on
import fr.dayview.app.generated.resources.settings_summary_sounds_on
import fr.dayview.app.generated.resources.settings_title
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
            SettingsTopBar(
                onBack = if (state.settingsCategory == null) actions.back else actions.closeCategory,
            )

            Spacer(Modifier.height(48.dp))
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                val category = state.settingsCategory
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
                .testTag(DayViewTestTags.SettingsBack)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.settings_title), color = colors.cloud, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
    }
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
    },
)

@Composable
private fun categoryDescription(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.DAY -> Res.string.settings_day_description
        SettingsCategory.DISPLAY -> Res.string.settings_show_seconds_description
        SettingsCategory.SOUNDS -> Res.string.settings_sounds_description
        SettingsCategory.NET_TIME -> Res.string.settings_net_time_description
        SettingsCategory.ON_GOAL -> Res.string.settings_on_goal_description
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
        val start = "${p.startHour.toString().padStart(2, '0')}:${p.startMinute.toString().padStart(2, '0')}"
        val end = "${p.endHour.toString().padStart(2, '0')}:${p.endMinute.toString().padStart(2, '0')}"
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
}
