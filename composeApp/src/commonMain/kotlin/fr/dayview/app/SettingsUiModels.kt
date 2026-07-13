package fr.dayview.app

import fr.dayview.app.sync.SyncConfig
import fr.dayview.app.sync.SyncStatus

internal data class SettingsPlatformUiState(
    val monochromeMenuBarIcon: Boolean?,
    val launchAtLogin: Boolean?,
    val netTimeSupported: Boolean = false,
    val onGoalSupported: Boolean = false,
    val runningApps: () -> List<AppRef> = { emptyList() },
    val syncConfig: SyncConfig? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val syncHasKey: Boolean = false,
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
    val changeThemeMode: (ThemeMode) -> Unit = {},
    val changeFontScale: (Float) -> Unit = {},
    val openCategory: (SettingsCategory) -> Unit = {},
    val closeCategory: () -> Unit = {},
    val changeSyncConfig: (SyncConfig) -> Unit = {},
    val generateSyncKey: () -> String = { "" },
    val pasteSyncKey: (String) -> Unit = {},
    val syncNow: () -> Unit = {},
    val clearSyncKey: () -> Unit = {},
    val back: () -> Unit,
)

/** The categories to show on the landing list, in display order, for this platform. */
internal fun settingsCategoriesFor(platformState: SettingsPlatformUiState): List<SettingsCategory> = buildList {
    add(SettingsCategory.DAY)
    add(SettingsCategory.DISPLAY)
    add(SettingsCategory.SOUNDS)
    if (platformState.netTimeSupported) add(SettingsCategory.NET_TIME)
    if (platformState.onGoalSupported) add(SettingsCategory.ON_GOAL)
    add(SettingsCategory.SYNC)
}
