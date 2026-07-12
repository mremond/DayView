# Theme Mode Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted in-app Light / Dark / System theme toggle and make the desktop title bar and Android status bar follow the chosen theme.

**Architecture:** A `ThemeMode` enum is persisted in DataStore and threaded through `DayViewTheme`, which resolves a concrete `isDark`. Platform chrome (Android status bar via an `expect`/`actual` composable, desktop macOS window appearance via `Main.kt`) is synced to that same `isDark`. A new Settings section drives the mode through the existing controller/persist round-trip.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material3), AndroidX DataStore Preferences, Compose resources (i18n), kotlin.test.

## Global Constraints

- ktlint is enforced: run `./gradlew ktlintCheck` (or `ktlintFormat`) before committing. No errors or stderr.
- Full pre-commit check: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages describe the change only. **Never** reference Claude, Anthropic, or an AI assistant, and never add a "Generated with" footer or `Co-Authored-By` trailer. Do not reference internal planning docs.
- All commit messages in English.
- Compose UI tests: **never** assert `stringResource` text (unresolved under `runComposeUiTest` on CI). Use test tags + seeded data. `assertExists` is a member — no import. Test pure screens, not `DayViewApp`.
- Default `ThemeMode` is `SYSTEM`; absent/unknown persisted value must read back as `SYSTEM` (preserves current behavior, no migration).

---

### Task 1: `ThemeMode` enum + resolver

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/ThemeMode.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/ThemeModeTest.kt`

**Interfaces:**
- Produces: `enum class ThemeMode { SYSTEM, LIGHT, DARK }` and `fun ThemeMode.resolveIsDark(systemDark: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/ThemeModeTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun systemFollowsSystemFlag() {
        assertEquals(true, ThemeMode.SYSTEM.resolveIsDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveIsDark(systemDark = false))
    }

    @Test
    fun lightIsAlwaysLight() {
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemDark = true))
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemDark = false))
    }

    @Test
    fun darkIsAlwaysDark() {
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemDark = true))
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemDark = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ThemeModeTest"`
Expected: FAIL — unresolved reference `ThemeMode`.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/ThemeMode.kt`:

```kotlin
package fr.dayview.app

/** How the app chooses between the light and dark palettes. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Resolves the concrete dark flag, given the current OS dark-mode signal. */
fun ThemeMode.resolveIsDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ThemeModeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/ThemeMode.kt composeApp/src/commonTest/kotlin/fr/dayview/app/ThemeModeTest.kt
git commit -m "feat: add ThemeMode enum and dark-mode resolver"
```

---

### Task 2: Persist `themeMode` in preferences

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (add field to `DayPreferencesSnapshot`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (key + persist + read)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt` (add round-trip + fallback)

**Interfaces:**
- Consumes: `ThemeMode` (Task 1).
- Produces: `DayPreferencesSnapshot.themeMode: ThemeMode` (default `ThemeMode.SYSTEM`); persisted under key `"theme_mode"` as `enum.name`.

- [ ] **Step 1: Write the failing tests**

Add two tests to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`:

```kotlin
    @Test
    fun themeModeRoundTrips() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(themeMode = ThemeMode.DARK)
        store.persist(snapshot)
        assertEquals(ThemeMode.DARK, store.snapshots.first().themeMode)
    }

    @Test
    fun missingThemeModeFallsBackToSystem() = runTest {
        val store = newStore(FakeFileSystem())
        assertEquals(ThemeMode.SYSTEM, store.snapshots.first().themeMode)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — unresolved reference `themeMode`.

- [ ] **Step 3: Add the field to the snapshot**

In `DayPreferences.kt`, add to `DayPreferencesSnapshot` (after `onGoalApps`):

```kotlin
    val onGoalApps: Set<AppRef> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
```

- [ ] **Step 4: Persist and read the field**

In `DayPreferencesStore.kt`:

Add the key constant to `DayPreferenceKeys` (after `ON_GOAL_APPS`):

```kotlin
    const val ON_GOAL_APPS = "on_goal_apps"
    const val THEME_MODE = "theme_mode"
```

Add the typed key (after `onGoalAppsKey`):

```kotlin
private val themeModeKey = stringPreferencesKey(DayPreferenceKeys.THEME_MODE)
```

In `persist(...)`, after the `onGoalAppsKey` line:

```kotlin
            prefs[onGoalAppsKey] = encodeAppRefs(snapshot.onGoalApps)
            prefs[themeModeKey] = snapshot.themeMode.name
```

In `toSnapshot()`, after the `onGoalApps = ...` line:

```kotlin
        onGoalApps = decodeAppRefs(this[onGoalAppsKey].orEmpty()),
        themeMode = this[themeModeKey]
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: defaults.themeMode,
    )
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "feat: persist theme mode preference"
```

---

### Task 3: Thread `themeMode` through the controller

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state field, mappings, setter)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` (add setter test)

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.themeMode` (Task 2).
- Produces: `DayViewUiState.themeMode: ThemeMode`; `DayViewController.setThemeMode(mode: ThemeMode)` (updates state, persists).

- [ ] **Step 1: Write the failing test**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`:

```kotlin
    @Test
    fun setThemeModeUpdatesStateAndPersists() = runTest {
        val prefs = InMemoryDayPreferences(DayPreferencesSnapshot())
        val controller = DayViewController(
            preferences = prefs,
            scope = CoroutineScope(Dispatchers.Unconfined),
            initialSnapshot = DayPreferencesSnapshot(),
        )
        controller.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, controller.state.themeMode)
        assertEquals(ThemeMode.LIGHT, prefs.snapshots.first().themeMode)
    }
```

If the test file lacks the needed imports, add: `import kotlinx.coroutines.CoroutineScope`, `import kotlinx.coroutines.Dispatchers`, `import kotlinx.coroutines.flow.first`, `import kotlinx.coroutines.test.runTest`, `import kotlin.test.assertEquals` (skip any already present).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — unresolved reference `themeMode` / `setThemeMode`.

- [ ] **Step 3: Add `themeMode` to `DayViewUiState`**

In `DayViewController.kt`, add to the `DayViewUiState` data class (after `destination`, keeping it a constructor property — place it before `destination` to avoid trailing-default ordering issues):

```kotlin
    val onGoalApps: Set<AppRef> = emptySet(),
    val focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
    val lastFocusClosure: FocusClosureOutcome? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val destination: DayViewDestination = DayViewDestination.TODAY,
) {
```

- [ ] **Step 4: Add the setter**

In `DayViewController.kt`, after `setShowSeconds`:

```kotlin
    fun setThemeMode(mode: ThemeMode) {
        state = state.copy(themeMode = mode)
        persistState()
    }
```

- [ ] **Step 5: Map the field in all three converters**

In `toSnapshot()`, add after `onGoalApps = onGoalApps,`:

```kotlin
    onGoalApps = onGoalApps,
    themeMode = themeMode,
).coerced()
```

In `toUiState()`, add after `onGoalApps = safe.onGoalApps,`:

```kotlin
        onGoalApps = safe.onGoalApps,
        themeMode = safe.themeMode,
    )
}
```

In `withPersisted()`, add `themeMode = safe.themeMode,` inside the `copy(...)` (after `onGoalApps` if present there, otherwise alongside the other persisted fields):

```kotlin
        themeMode = safe.themeMode,
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: expose theme mode through the controller"
```

---

### Task 4: Resolve the palette from `themeMode` + platform chrome hook

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt` (param + invoke hook)
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/PlatformThemeChrome.kt` (expect)
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/PlatformThemeChrome.desktop.kt` (no-op actual)
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/PlatformThemeChrome.android.kt` (status bar actual)

**Interfaces:**
- Consumes: `ThemeMode.resolveIsDark` (Task 1).
- Produces: `@Composable fun DayViewTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable (DayViewColors) -> Unit)`; `@Composable expect fun PlatformThemeChrome(isDark: Boolean)`.

- [ ] **Step 1: Add the `expect` declaration**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/PlatformThemeChrome.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.Composable

/**
 * Syncs platform window chrome (Android status/navigation bars) to the resolved
 * theme. Desktop is a no-op here — the desktop window appearance is handled in Main.
 */
@Composable
expect fun PlatformThemeChrome(isDark: Boolean)
```

- [ ] **Step 2: Add the desktop no-op actual**

Create `composeApp/src/desktopMain/kotlin/fr/dayview/app/PlatformThemeChrome.desktop.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformThemeChrome(isDark: Boolean) = Unit
```

- [ ] **Step 3: Add the Android status-bar actual**

Create `composeApp/src/androidMain/kotlin/fr/dayview/app/PlatformThemeChrome.android.kt`:

```kotlin
package fr.dayview.app

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val InkDark = Color(0xFF0B0D12)
private val InkLight = Color(0xFFF4F2EC)

@Composable
actual fun PlatformThemeChrome(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context as? Activity ?: return
    val window = activity.window
    val ink = (if (isDark) InkDark else InkLight).toArgb()
    SideEffect {
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = ink
            window.navigationBarColor = ink
        }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}
```

Note: `androidx.core.view.WindowCompat` comes from `androidx.core:core-ktx`, already on the Android classpath via `activity-compose`. If the build reports it unresolved, add `implementation("androidx.core:core-ktx:<current>")` to the Android dependencies in `composeApp/build.gradle.kts` matching the version already used elsewhere.

- [ ] **Step 4: Wire the param and hook into `DayViewTheme`**

In `DayViewTheme.kt`, replace the function signature and the `isDark` line, and invoke the hook. The new function body:

```kotlin
@Composable
internal fun DayViewTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable (DayViewColors) -> Unit,
) {
    val isDark = themeMode.resolveIsDark(isSystemInDarkTheme())
    val colors = if (isDark) DarkDayViewColors else LightDayViewColors
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    } else {
        lightColorScheme(
            primary = colors.mint,
            background = colors.ink,
            surface = colors.panel,
            onBackground = colors.cloud,
            onSurface = colors.cloud,
            error = colors.red,
        )
    }
    PlatformThemeChrome(isDark = isDark)
    CompositionLocalProvider(LocalDayViewColors provides colors) {
        MaterialTheme(colorScheme = colorScheme) {
            content(colors)
        }
    }
}
```

(The `content` parameter keeps its existing trailing-lambda position, so all `DayViewTheme { ... }` call sites still compile.)

- [ ] **Step 5: Verify the whole module compiles on both targets**

Run: `./gradlew ktlintCheck :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (all `actual`s resolve; existing `DayViewTheme { }` calls still valid).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt composeApp/src/commonMain/kotlin/fr/dayview/app/PlatformThemeChrome.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/PlatformThemeChrome.desktop.kt composeApp/src/androidMain/kotlin/fr/dayview/app/PlatformThemeChrome.android.kt
git commit -m "feat: resolve palette from theme mode and sync Android status bar"
```

---

### Task 5: Feed the mode into the theme and wire the Settings action (App.kt)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (add `changeThemeMode` to `SettingsScreenActions`, defaulted)

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.themeMode` (Task 2), `DayViewController.setThemeMode` (Task 3), `DayViewTheme(themeMode = ...)` (Task 4).
- Produces: `SettingsScreenActions.changeThemeMode: (ThemeMode) -> Unit` (default `{}`).

- [ ] **Step 1: Add the action field**

In `DayViewSettingsScreen.kt`, add to `SettingsScreenActions` (after `changeOnGoalApps`, before `back`):

```kotlin
    val changeOnGoalApps: (Set<AppRef>) -> Unit = {},
    val changeThemeMode: (ThemeMode) -> Unit = {},
    val back: () -> Unit,
)
```

- [ ] **Step 2: Lift the theme-mode observation above `DayViewTheme`**

In `App.kt`, add imports:

```kotlin
import androidx.compose.runtime.collectAsState
```

Replace the opening of `DayViewApp`'s body — the `DayViewTheme { colors ->` line — with an observation of the persisted mode that drives the theme. Insert immediately inside `DayViewApp(...) {` and before `DayViewTheme`:

```kotlin
    val initialThemeSnapshot = remember(preferences) { runBlocking { preferences.snapshots.first() } }
    val themeSnapshot by preferences.snapshots.collectAsState(initial = initialThemeSnapshot)
    DayViewTheme(themeMode = themeSnapshot.themeMode) { colors ->
        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
```

(The existing `initialSnapshot`/`controller` lines inside `Surface` stay as they are; they already re-read `preferences`, so `state.themeMode` reflects changes for the Settings selection.)

- [ ] **Step 3: Wire the action to the controller**

In `App.kt`, in the `SettingsScreenActions(...)` construction, add after `changeOnGoalApps = { controller.setOnGoalApps(it) },`:

```kotlin
                        changeOnGoalApps = { controller.setOnGoalApps(it) },
                        changeThemeMode = { controller.setThemeMode(it) },
                        back = { controller.openToday() },
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew ktlintCheck :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt
git commit -m "feat: drive app theme from persisted mode and wire settings action"
```

---

### Task 6: Settings APPEARANCE section (UI + strings + test)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt`
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (add param to `noopSettingsActions`)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.themeMode` (Task 3), `SettingsScreenActions.changeThemeMode` (Task 5).
- Produces: `DayViewTestTags.SettingsThemeMode` + per-segment tags; an `AppearancePanel` composable.

- [ ] **Step 1: Add test tags**

In `DayViewTestTags.kt`, add:

```kotlin
    const val SettingsSounds = "settingsSounds"
    const val SettingsThemeMode = "settingsThemeMode"
    const val SettingsThemeSystem = "settingsThemeSystem"
    const val SettingsThemeLight = "settingsThemeLight"
    const val SettingsThemeDark = "settingsThemeDark"
```

- [ ] **Step 2: Add English strings**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, add near the other `settings_section_*` entries:

```xml
    <string name="settings_section_appearance">APPEARANCE</string>
    <string name="settings_appearance_description">Match your system, or lock the app to light or dark.</string>
    <string name="settings_theme_system">System</string>
    <string name="settings_theme_light">Light</string>
    <string name="settings_theme_dark">Dark</string>
```

- [ ] **Step 3: Add French strings**

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, add the matching entries:

```xml
    <string name="settings_section_appearance">APPARENCE</string>
    <string name="settings_appearance_description">Suivez le système, ou forcez le mode clair ou sombre.</string>
    <string name="settings_theme_system">Système</string>
    <string name="settings_theme_light">Clair</string>
    <string name="settings_theme_dark">Sombre</string>
```

- [ ] **Step 4: Add the `AppearancePanel` composable and render it**

In `DayViewSettingsScreen.kt`, add these resource imports alongside the other `fr.dayview.app.generated.resources.*` imports:

```kotlin
import fr.dayview.app.generated.resources.settings_appearance_description
import fr.dayview.app.generated.resources.settings_section_appearance
import fr.dayview.app.generated.resources.settings_theme_dark
import fr.dayview.app.generated.resources.settings_theme_light
import fr.dayview.app.generated.resources.settings_theme_system
```

Add the panel composable at the end of the file (top-level, `private`):

```kotlin
@Composable
private fun AppearancePanel(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(
        stringResource(Res.string.settings_section_appearance),
        color = colors.mint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(Res.string.settings_appearance_description),
        color = colors.muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .testTag(DayViewTestTags.SettingsThemeMode)
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeModeSegment(
            label = stringResource(Res.string.settings_theme_system),
            tag = DayViewTestTags.SettingsThemeSystem,
            active = selected == ThemeMode.SYSTEM,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ThemeMode.SYSTEM) },
        )
        ThemeModeSegment(
            label = stringResource(Res.string.settings_theme_light),
            tag = DayViewTestTags.SettingsThemeLight,
            active = selected == ThemeMode.LIGHT,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ThemeMode.LIGHT) },
        )
        ThemeModeSegment(
            label = stringResource(Res.string.settings_theme_dark),
            tag = DayViewTestTags.SettingsThemeDark,
            active = selected == ThemeMode.DARK,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(ThemeMode.DARK) },
        )
    }
}

@Composable
private fun ThemeModeSegment(
    label: String,
    tag: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier
            .testTag(tag)
            .background(
                if (active) colors.mint.copy(alpha = .16f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) colors.mint else colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .6.sp,
        )
    }
}
```

Add the required imports for these composables (some may already be present — skip duplicates):

```kotlin
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
```

Render the panel inside `SettingsScreen`, in the inner `Column(... widthIn(max = 560.dp))`, immediately after the DISPLAY section's show-seconds/menubar/login block and before `Spacer(Modifier.height(24.dp))` + `SoundSettingsPanel`. Insert:

```kotlin
                Spacer(Modifier.height(24.dp))
                AppearancePanel(
                    selected = state.themeMode,
                    onSelect = actions.changeThemeMode,
                )
```

- [ ] **Step 5: Add the overridable callback to the test helper**

In `UiTestSupport.kt`, add a parameter to `noopSettingsActions` and pass it through:

```kotlin
internal fun noopSettingsActions(
    changeStartTime: (Int) -> Unit = {},
    changeEndTime: (Int) -> Unit = {},
    changeShowSeconds: (Boolean) -> Unit = {},
    changeSoundSettings: (SoundSettings) -> Unit = {},
    previewSound: (SoundCue) -> Unit = {},
    changeThemeMode: (ThemeMode) -> Unit = {},
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
    back = back,
)
```

- [ ] **Step 6: Write the failing UI tests**

Add to `SettingsScreenTest.kt`:

```kotlin
    @Test
    fun rendersAppearanceSelector() = runComposeUiTest {
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot(themeMode = ThemeMode.DARK)).state }
            DayViewTheme {
                SettingsScreen(state = state, platformState = platform, actions = noopSettingsActions())
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsThemeMode).assertExists()
        onNodeWithTag(DayViewTestTags.SettingsThemeDark).assertExists()
    }

    @Test
    fun tappingLightSegmentInvokesCallback() = runComposeUiTest {
        var recorded: ThemeMode? = null
        setContent {
            val state = remember { seededController(DayPreferencesSnapshot(themeMode = ThemeMode.SYSTEM)).state }
            DayViewTheme {
                SettingsScreen(
                    state = state,
                    platformState = platform,
                    actions = noopSettingsActions(changeThemeMode = { recorded = it }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsThemeLight).performClick()
        assertEquals(ThemeMode.LIGHT, recorded)
    }
```

- [ ] **Step 7: Run tests to verify they fail, then pass after the code above**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SettingsScreenTest"`
Expected: the two new tests PASS (and all pre-existing `SettingsScreenTest` cases still PASS). If they fail to compile first, that confirms the red state; the Step-4/5 code turns them green.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt
git commit -m "feat: add appearance selector to settings"
```

---

### Task 7: Sync the desktop macOS title bar to the theme

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.themeMode` (Task 2), `ThemeMode.resolveIsDark` (Task 1).

- [ ] **Step 1: Request per-window appearance support at startup**

In `Main.kt`, in `main()`, add alongside the existing `apple.awt.application.name` property (before `runApplication()`):

```kotlin
    System.setProperty("apple.awt.application.name", "DayView")
    // Let the JVM honor an explicit light/dark window appearance we set below,
    // instead of always following the OS. "system" is the neutral starting point.
    System.setProperty("apple.awt.application.appearance", "system")
    runApplication()
```

- [ ] **Step 2: Drive the appearance from the resolved theme**

In `Main.kt`, add imports:

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
```

In `runApplication()`'s `application { ... }` scope, after `preferenceSnapshot` is available (e.g. just before the `Tray(...)` call), add:

```kotlin
    val appearanceIsDark = preferenceSnapshot.themeMode.resolveIsDark(isSystemInDarkTheme())
    LaunchedEffect(appearanceIsDark) {
        System.setProperty(
            "apple.awt.application.appearance",
            if (appearanceIsDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
```

- [ ] **Step 3: Compile the desktop target**

Run: `./gradlew ktlintCheck :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification (required — this is the empirically-verified piece)**

Run: `./gradlew :composeApp:run`

With the **macOS system appearance set to Light**:
1. Open Settings → APPEARANCE, choose **Dark**. Confirm the window content turns dark AND the window title bar (and traffic-light region) renders in the dark appearance, not light grey.
2. Choose **Light** — content and title bar both light.
3. Choose **System** — both follow the OS.
Repeat with the OS in Dark mode.

**If the title bar does NOT re-tint live** with the `System.setProperty` route, replace the `LaunchedEffect` body with a per-window call inside each `Window { ... }` block (where `window` is in scope), setting the root-pane client property, e.g.:

```kotlin
LaunchedEffect(appearanceIsDark) {
    window.rootPane.putClientProperty(
        "apple.awt.windowAppearance",
        if (appearanceIsDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
    )
}
```

Apply to both the main `Window` and the mini `Window`. Re-run and re-verify. Keep whichever route demonstrably re-tints the title bar; document the choice in the commit message.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "feat: sync desktop title bar appearance with theme mode"
```

---

### Task 8: Full verification pass

- [ ] **Step 1: Run the complete check**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no failing tests, no stderr.

- [ ] **Step 2: Manual Android verification**

Run: `./gradlew :composeApp:installDebug` and open the app on a device/emulator. With the OS in Light mode, set APPEARANCE → Dark: confirm the app content and the **status bar** turn dark (status-bar icons switch to light). Set → Light: status bar light with dark icons. Set → System: follows the OS.

- [ ] **Step 3: Final commit if any fixups were needed**

```bash
git add -A
git commit -m "chore: finalize theme mode toggle"
```

---

## Self-Review

**Spec coverage:**
- `ThemeMode` model + resolver → Task 1. ✓
- Persistence (snapshot field, key, name-based storage, SYSTEM fallback) → Task 2. ✓
- Controller/UI state + `setThemeMode` → Task 3. ✓
- `DayViewTheme(themeMode)` resolving `isDark` → Task 4. ✓
- Android status bar via `PlatformThemeChrome` expect/actual → Task 4. ✓
- Feeding mode into theme + Settings action wiring → Task 5. ✓
- Settings APPEARANCE selector + en/fr strings + test tag → Task 6. ✓
- Desktop macOS title bar (Approach A, native appearance sync, empirically verified) → Task 7. ✓
- Unit (resolver), persistence round-trip + fallback, Compose UI (tag + seeded, no stringResource asserts), manual desktop + Android verification → Tasks 1, 2, 6, 7, 8. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. The one deferred item (exact macOS appearance call) is handled as a concrete primary implementation plus a concrete documented fallback with a verification gate — not a placeholder.

**Type consistency:** `ThemeMode` / `resolveIsDark(systemDark)` / `themeMode` / `setThemeMode(mode)` / `PlatformThemeChrome(isDark)` / `changeThemeMode` / `SettingsThemeMode` used consistently across all tasks.
