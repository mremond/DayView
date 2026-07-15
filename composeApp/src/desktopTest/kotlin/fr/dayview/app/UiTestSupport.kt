@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.dayview.app.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

internal const val VISUAL_VIEWPORT_TAG = "visualViewport"

/**
 * A clipped, tagged viewport used by responsive UI tests. Only text scaling is
 * overridden: physical dp sizing stays deterministic on Linux and macOS.
 */
@Composable
internal fun VisualViewport(
    width: Dp,
    height: Dp,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
        LocalPreferenceFontScale provides fontScale,
    ) {
        Box(
            Modifier
                .requiredSize(width, height)
                .clipToBounds()
                .testTag(VISUAL_VIEWPORT_TAG),
        ) {
            content()
        }
    }
}

/** Saves an actual rendered PNG and rejects empty/monochrome captures. */
internal fun ComposeUiTest.captureVisual(name: String): File {
    waitForIdle()
    val image = onNodeWithTag(VISUAL_VIEWPORT_TAG, useUnmergedTree = true).captureToImage()
    val pixels = image.readArgbPixels()
    val distinctColors = pixels.asSequence().distinct().take(17).count()
    assertTrue(distinctColors >= 8, "Visual capture $name contains too little rendered content")

    val outputDirectory = File(
        System.getProperty("dayview.visualOutputDir", "build/reports/visual-tests"),
    ).apply { mkdirs() }
    val output = outputDirectory.resolve("${name.replace(Regex("[^a-zA-Z0-9._-]"), "-")}.png")
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    assertTrue(ImageIO.write(buffered, "png", output), "PNG writer unavailable")
    assertTrue(output.length() > 0L, "Visual capture $name was not written")
    return output
}

/** Ensures a critical control has a non-empty visible rectangle inside the viewport. */
internal fun SemanticsNodeInteraction.assertInsideVisualViewport(test: ComposeUiTest): SemanticsNodeInteraction {
    val viewport = test.onNodeWithTag(VISUAL_VIEWPORT_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val node = fetchSemanticsNode()
    val bounds = node.boundsInRoot
    val tolerance = 1f
    assertTrue(bounds.width > 0f && bounds.height > 0f, "Node has an empty visual bounds: $bounds")
    assertTrue(
        bounds.width >= node.size.width - tolerance && bounds.height >= node.size.height - tolerance,
        "Node is only partially visible: measured=${node.size}, visible=$bounds",
    )
    assertTrue(
        bounds.left >= viewport.left - tolerance &&
            bounds.right <= viewport.right + tolerance &&
            bounds.top >= viewport.top - tolerance &&
            bounds.bottom <= viewport.bottom + tolerance,
        "Node $bounds is clipped by viewport $viewport",
    )
    return this
}

private fun ImageBitmap.readArgbPixels(): IntArray = IntArray(width * height).also { readPixels(it) }

/**
 * A "now" that always falls inside the default 08:00–18:00 day window,
 * whatever the machine timezone, so day-progress labels ("IL RESTE") are
 * deterministic. Only the local date is taken from the system clock; it does
 * not affect the time-of-day assertions.
 */
internal fun midWindowNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    val localNow = Clock.System.now().toLocalDateTime(tz)
    return LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = 13,
        minute = 0,
    ).toInstant(tz)
}

/**
 * A "now" on a fixed mid-week day (Wednesday 2026-01-14, 13:00 local). Unlike
 * [midWindowNow] it does not track the system date, so tests that reason about
 * "yesterday" relative to the Monday→Sunday history week stay deterministic:
 * the previous day is always inside the same week, whatever day CI runs on.
 */
internal fun midWeekNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    return LocalDateTime(year = 2026, month = 1, day = 14, hour = 13, minute = 0).toInstant(tz)
}

/**
 * A "now" past the default 08:00–18:00 day window (19:00 local), so
 * [calculateDayProgress] reports the day as finished regardless of timezone.
 */
internal fun afterWindowNow(): Instant {
    val tz = TimeZone.currentSystemDefault()
    val localNow = Clock.System.now().toLocalDateTime(tz)
    return LocalDateTime(
        year = localNow.year,
        month = localNow.month,
        day = localNow.day,
        hour = 19,
        minute = 0,
    ).toInstant(tz)
}

/** Builds a controller from a seeded snapshot + fixed clock — the production path. */
internal fun seededController(
    snapshot: DayPreferencesSnapshot,
    now: Instant = midWindowNow(),
): DayViewController = DayViewController(
    preferences = InMemoryDayPreferences(snapshot),
    scope = CoroutineScope(Dispatchers.Unconfined),
    initialSnapshot = snapshot,
    initialNow = now,
)

/** A full [SettingsScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopSettingsActions(
    changeStartTime: (Int) -> Unit = {},
    changeEndTime: (Int) -> Unit = {},
    changeShowSeconds: (Boolean) -> Unit = {},
    changeSoundSettings: (SoundSettings) -> Unit = {},
    previewSound: (SoundCue) -> Unit = {},
    changeThemeMode: (ThemeMode) -> Unit = {},
    changeFontScale: (Float) -> Unit = {},
    openCategory: (SettingsCategory) -> Unit = {},
    closeCategory: () -> Unit = {},
    back: () -> Unit = {},
): SettingsScreenActions = SettingsScreenActions(
    changeStartTime = changeStartTime,
    changeEndTime = changeEndTime,
    changeShowSeconds = changeShowSeconds,
    changeMonochromeMenuBarIcon = null,
    changeLaunchAtLogin = null,
    changeSoundSettings = changeSoundSettings,
    previewSound = previewSound,
    changeThemeMode = changeThemeMode,
    changeFontScale = changeFontScale,
    openCategory = openCategory,
    closeCategory = closeCategory,
    back = back,
)

/** A full [DayViewScreenActions] of no-ops with the relevant callbacks overridable. */
internal fun noopDayViewActions(
    openSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    openMiniWindow: (() -> Unit)? = null,
    changeFocusIntention: (String) -> Unit = {},
    changePomodoroDuration: (Int) -> Unit = {},
    startPomodoro: () -> Unit = {},
    stopPomodoro: () -> Unit = {},
    closePomodoro: (FocusClosureOutcome) -> Unit = {},
    openNetTimeSettings: () -> Unit = {},
    openSyncSettings: () -> Unit = {},
): DayViewScreenActions = DayViewScreenActions(
    openSettings = openSettings,
    onOpenHistory = onOpenHistory,
    openMiniWindow = openMiniWindow,
    changeGoalTitle = {},
    changeGoalDeadline = {},
    commitGoalDeadline = {},
    changeGoalStart = {},
    commitGoalStart = {},
    changeFocusIntention = changeFocusIntention,
    changePomodoroDuration = changePomodoroDuration,
    startPomodoro = startPomodoro,
    stopPomodoro = stopPomodoro,
    closePomodoro = closePomodoro,
    addDetour = { _, _, _ -> },
    updateDetour = { _, _ -> },
    removeDetour = {},
    addDetourEpisode = {},
    startOpenDetour = { _, _ -> },
    stopOpenDetour = {},
    forgetDetourCategory = {},
    addPlannedObligation = {},
    removePlannedObligation = {},
    completePlannedObligation = {},
    openNetTimeSettings = openNetTimeSettings,
    openSyncSettings = openSyncSettings,
)

internal fun noReminders(): FocusReminderUiState = FocusReminderUiState(
    showDriftReminder = false,
    dismissDriftReminder = {},
    showResumeRitual = false,
    dismissResumeRitual = {},
)

/** Renders [DayViewScreen] forced into the wide layout so Goal/Focus panels render inline. */
@Composable
internal fun WideDayView(
    state: DayViewUiState,
    actions: DayViewScreenActions,
    reminders: FocusReminderUiState = noReminders(),
    syncStatus: SyncStatus = SyncStatus.Idle,
) {
    DayViewTheme {
        Box(Modifier.requiredSize(1000.dp, 720.dp)) {
            DayViewScreen(state = state, actions = actions, reminders = reminders, syncStatus = syncStatus)
        }
    }
}

/** Wires a [DayViewScreenActions] bundle straight to the controller (as App.kt does). */
internal fun controllerDayViewActions(controller: DayViewController): DayViewScreenActions = DayViewScreenActions(
    openSettings = { controller.openSettings() },
    onOpenHistory = { controller.openHistory() },
    openMiniWindow = null,
    changeGoalTitle = { controller.setGoalTitle(it) },
    changeGoalDeadline = { controller.setGoalDeadlineText(it) },
    commitGoalDeadline = { controller.commitGoalDeadline() },
    changeGoalStart = { controller.setGoalStartText(it) },
    commitGoalStart = { controller.commitGoalStart() },
    changeFocusIntention = { controller.setFocusIntention(it) },
    changePomodoroDuration = { controller.changePomodoroDuration(it) },
    startPomodoro = { controller.startPomodoro() },
    stopPomodoro = { controller.stopPomodoro() },
    closePomodoro = { controller.closePomodoro(it) },
    addDetour = { category, durationMinutes, description -> controller.addDetour(category, durationMinutes, description) },
    updateDetour = { index, episode -> controller.updateDetour(index, episode) },
    removeDetour = { controller.removeDetour(it) },
    addDetourEpisode = { controller.addDetourEpisode(it) },
    startOpenDetour = { category, description -> controller.startOpenDetour(category, description) },
    stopOpenDetour = { controller.stopOpenDetour() },
    forgetDetourCategory = { controller.forgetRecentDetourCategory(it) },
    addPlannedObligation = { controller.addPlannedObligation(it) },
    removePlannedObligation = { controller.removePlannedObligation(it) },
    completePlannedObligation = controller::completePlannedObligation,
)
