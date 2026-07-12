package fr.dayview.app

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
    val openCategory: (SettingsCategory) -> Unit = {},
    val closeCategory: () -> Unit = {},
    val back: () -> Unit,
)

/** The categories to show on the landing list, in display order, for this platform. */
internal fun settingsCategoriesFor(platformState: SettingsPlatformUiState): List<SettingsCategory> = buildList {
    add(SettingsCategory.DAY)
    add(SettingsCategory.DISPLAY)
    add(SettingsCategory.SOUNDS)
    if (platformState.netTimeSupported) add(SettingsCategory.NET_TIME)
    if (platformState.onGoalSupported) add(SettingsCategory.ON_GOAL)
}
