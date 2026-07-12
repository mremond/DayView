# Adaptive scaling + font-size accessibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the countdown ring/counter grow to fill available screen space automatically, and add a persisted in-app slider that enlarges all text app-wide.

**Architecture:** Two independent mechanisms. (1) Space-filling: raise the ring size and counter-scale ceilings in `CountdownCircle`, driven by the existing `BoxWithConstraints`, with the ceiling math extracted into pure helpers for unit testing. (2) Font accessibility: a new `fontScale` preference wired through the same path as `themeMode`, applied once at the top of `DayViewApp` by overriding `LocalDensity`'s `fontScale` (scales only `.sp`, never `.dp`).

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material3), DataStore preferences, kotlin.test / Compose UI test (desktopTest).

## Global Constraints

- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- ktlint is enforced; run `./gradlew ktlintFormat` to auto-fix style.
- Commit messages describe the change only. **Never** add a "Generated with Claude Code" footer, `Co-Authored-By: Claude`, or any reference to Claude/Anthropic/an AI assistant. Do not reference internal working documents (`docs/superpowers/`).
- Scope is the shared Compose UI (Android + Linux/desktop). Do not touch the macOS native SwiftUI path.
- Compose-resources string formatting in this repo: use a single `%` (not `%%`); positional args like `%1$d`.
- Compose UI test gotcha: never assert `stringResource` text in `runComposeUiTest`; use `DayViewTestTags` and seeded state; test pure screens, not `DayViewApp`. `assertExists` is a member (no import).
- `fontScale` valid range is `1.0f..1.5f`, default `1.0f`. Coerce on write (controller), on snapshot (`coerced()`), and on read (store).

---

## Task 1: Countdown scaling helpers (pure, unit-tested)

Extract the ring-size and counter-scale ceiling math into pure functions so the new
ceilings are testable, then point `CountdownCircle` at them.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (lines 702, 705)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownScalingTest.kt`

**Interfaces:**
- Produces:
  - `internal fun countdownCircleSize(available: Dp, max: Dp = 720.dp): Dp`
  - `internal fun countdownCounterScale(circleSize: Dp): Float`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownScalingTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountdownScalingTest {
    @Test
    fun circleSizeClampsToCeiling() {
        assertEquals(720.dp, countdownCircleSize(available = 1200.dp))
        assertEquals(300.dp, countdownCircleSize(available = 300.dp))
    }

    @Test
    fun counterScaleTracksRingWithinBounds() {
        // Small ring floors at .72; matches today's compact/mini behaviour.
        assertEquals(0.72f, countdownCounterScale(200.dp))
        // At the reference 380.dp the scale is exactly 1.0.
        assertEquals(1.0f, countdownCounterScale(380.dp))
        // A large ring lifts the numerals above 1.0, capped at 1.4.
        assertEquals(1.4f, countdownCounterScale(720.dp))
        // Between the reference and the cap it scales linearly.
        assertTrue(countdownCounterScale(456.dp) in 1.19f..1.21f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CountdownScalingTest"`
Expected: FAIL — `countdownCircleSize` / `countdownCounterScale` unresolved.

- [ ] **Step 3: Write the helpers**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Diameter for the countdown ring given the available square, clamped to a sane
 * maximum so it fills large screens (Supernote, maximized desktop) without becoming
 * absurd on very large monitors.
 */
internal fun countdownCircleSize(available: Dp, max: Dp = 720.dp): Dp = minOf(available, max)

/**
 * Type scale for the counter numerals. Tracks the ring around a 380.dp reference so the
 * numerals keep their proportion: they never dwarf a small dial (mini/compact windows,
 * floored at .72) and grow with a large ring (capped at 1.4).
 */
internal fun countdownCounterScale(circleSize: Dp): Float =
    (circleSize / 380.dp).coerceIn(0.72f, 1.4f)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.CountdownScalingTest"`
Expected: PASS.

- [ ] **Step 5: Point `CountdownCircle` at the helpers**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, inside
`CountdownCircle`'s inner `BoxWithConstraints` (currently lines 702 & 705), replace:

```kotlin
            val circleSize = minOf(maxWidth, maxHeight, 510.dp)
            // Shrink the counter type in step with the ring so the numerals keep their
            // proportion instead of dwarfing the dial in the mini and compact windows.
            val counterScale = (circleSize / 380.dp).coerceIn(.72f, 1f)
```

with:

```kotlin
            val circleSize = countdownCircleSize(minOf(maxWidth, maxHeight))
            // Scale the counter type in step with the ring so the numerals keep their
            // proportion: they shrink in the mini and compact windows and grow to fill a
            // large dial on Supernote / a maximized desktop window.
            val counterScale = countdownCounterScale(circleSize)
```

- [ ] **Step 6: Verify the module compiles and tests pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest`
Expected: PASS (BUILD SUCCESSFUL, no ktlint violations).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CountdownScalingTest.kt
git commit -m "Grow the countdown ring and counter to fill large screens"
```

---

## Task 2: Persist `fontScale` in preferences

Add the `fontScale` field to the snapshot and the DataStore, following the `themeMode` pattern.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Produces: `DayPreferencesSnapshot.fontScale: Float` (default `1.0f`); persisted under key `"font_scale"`, coerced to `1.0f..1.5f` on read.

- [ ] **Step 1: Write the failing test**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`:

```kotlin
    @Test
    fun fontScaleRoundTripsAndDefaultsToOne() = runTest {
        val store = newStore(FakeFileSystem())
        // Absent value falls back to the 1.0 default.
        assertEquals(1.0f, store.snapshots.first().fontScale)

        store.persist(DayPreferencesSnapshot(fontScale = 1.25f))
        assertEquals(1.25f, store.snapshots.first().fontScale)
    }

    @Test
    fun fontScaleIsCoercedIntoRangeOnRead() = runTest {
        val store = newStore(FakeFileSystem())
        store.persist(DayPreferencesSnapshot(fontScale = 9.0f))
        assertEquals(1.5f, store.snapshots.first().fontScale)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — `fontScale` is not a member of `DayPreferencesSnapshot`.

- [ ] **Step 3: Add the field to the snapshot**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`, add to
`DayPreferencesSnapshot` (after `themeMode`):

```kotlin
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
```

- [ ] **Step 4: Add the key and persist/read logic**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`:

Add the import (with the other preference-key imports at the top):

```kotlin
import androidx.datastore.preferences.core.floatPreferencesKey
```

Add the key constant to `DayPreferenceKeys` (after `THEME_MODE`):

```kotlin
    const val THEME_MODE = "theme_mode"
    const val FONT_SCALE = "font_scale"
```

Add the typed key (after `themeModeKey`):

```kotlin
private val themeModeKey = stringPreferencesKey(DayPreferenceKeys.THEME_MODE)
private val fontScaleKey = floatPreferencesKey(DayPreferenceKeys.FONT_SCALE)
```

In `persist(...)`, after the `themeModeKey` line:

```kotlin
            prefs[themeModeKey] = snapshot.themeMode.name
            prefs[fontScaleKey] = snapshot.fontScale
```

In `toSnapshot()`, after the `themeMode = ...` line:

```kotlin
        themeMode = this[themeModeKey]
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: defaults.themeMode,
        fontScale = (this[fontScaleKey] ?: defaults.fontScale).coerceIn(1.0f, 1.5f),
    )
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist a font-scale preference"
```

---

## Task 3: Thread `fontScale` through the controller and UI state

Expose `fontScale` on `DayViewUiState`, add `setFontScale`, and carry it through the
snapshot↔state mappings and coercion.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.fontScale` (Task 2).
- Produces:
  - `DayViewUiState.fontScale: Float`
  - `DayViewController.setFontScale(scale: Float)` — coerces to `1.0f..1.5f`, updates state, persists.

- [ ] **Step 1: Write the failing test**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`:

```kotlin
    @Test
    fun setFontScalePersistsAndCoerces() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 10_000L)

        controller.setFontScale(1.3f)
        assertEquals(1.3f, controller.state.fontScale)
        assertEquals(1.3f, preferences.current.fontScale)

        // Out-of-range input is clamped.
        controller.setFontScale(5.0f)
        assertEquals(1.5f, controller.state.fontScale)
        assertEquals(1.5f, preferences.current.fontScale)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `fontScale` / `setFontScale` unresolved.

- [ ] **Step 3: Add the state field**

In `DayViewController.kt`, add to `DayViewUiState` (after `themeMode`):

```kotlin
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
```

- [ ] **Step 4: Add the setter**

In `DayViewController.kt`, after `setThemeMode`:

```kotlin
    fun setFontScale(scale: Float) {
        state = state.copy(fontScale = scale.coerceIn(1.0f, 1.5f))
        persistState()
    }
```

- [ ] **Step 5: Carry it through the mappings and coercion**

In `DayViewController.kt`:

`toSnapshot()` — after `themeMode = themeMode,`:

```kotlin
    themeMode = themeMode,
    fontScale = fontScale,
).coerced()
```

`coerced()` — add `fontScale` to the returned `copy(...)`:

```kotlin
        recentDetourMotifs = recentDetourMotifs.take(MAX_RECENT_DETOUR_MOTIFS),
        fontScale = fontScale.coerceIn(1.0f, 1.5f),
    )
```

`toUiState()` — after `themeMode = safe.themeMode,`:

```kotlin
        themeMode = safe.themeMode,
        fontScale = safe.fontScale,
    )
```

`withPersisted()` — after `themeMode = safe.themeMode,`:

```kotlin
        themeMode = safe.themeMode,
        fontScale = safe.fontScale,
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Thread font scale through controller and UI state"
```

---

## Task 4: Apply the font scale globally in `DayViewApp`

Override `LocalDensity`'s `fontScale` once at the top so all `.sp` text enlarges while
`.dp` layout stays fixed.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (lines 44-46)

**Interfaces:**
- Consumes: `themeSnapshot.fontScale` (Task 2, already observed at App.kt:44).

- [ ] **Step 1: Add imports**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`, add with the existing imports:

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
```

- [ ] **Step 2: Wrap the content with the density override**

Replace (App.kt:45-46):

```kotlin
    DayViewTheme(themeMode = themeSnapshot.themeMode) { colors ->
        Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
```

with:

```kotlin
    DayViewTheme(themeMode = themeSnapshot.themeMode) { colors ->
        val baseDensity = LocalDensity.current
        // Override only fontScale (not density): every .sp grows, every .dp layout
        // measurement stays put. On Android baseDensity.fontScale already reflects the
        // OS font setting, so the in-app slider stacks on top of it; on desktop it is the
        // sole text-scale control.
        val scaledDensity = Density(baseDensity.density, baseDensity.fontScale * themeSnapshot.fontScale)
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Surface(modifier = Modifier.fillMaxSize(), color = colors.ink) {
```

Then close the new `CompositionLocalProvider` block. At the end of the file the braces
currently nest like this:

```kotlin
                )            // closes DayViewScreen(...)
            }                // closes the `else` block
        }                    // closes the Surface { ... } lambda   <-- add one `}` AFTER this line
    }                        // closes the DayViewTheme { colors -> ... } lambda
}                            // closes fun DayViewApp
```

Insert exactly one extra `}` on its own line immediately after the line that closes the
`Surface { ... }` lambda (the third-from-last `}`), to close the new
`CompositionLocalProvider`. After the edit, re-indent with `./gradlew ktlintFormat`.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS (BUILD SUCCESSFUL). If ktlint flags the added `}` indentation, run
`./gradlew ktlintFormat` and re-run.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Apply the font-scale preference app-wide via LocalDensity"
```

---

## Task 5: Font-size slider in Display settings

Add the settings action, test tag, strings, and a Material3 `Slider` row.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DisplaySettingsScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DisplaySettingsFontScaleTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState.fontScale` (Task 3), `DayViewController.setFontScale` (Task 3).
- Produces: `SettingsScreenActions.changeFontScale: (Float) -> Unit`; `DayViewTestTags.SettingsFontScale`.

- [ ] **Step 1: Add the test tag**

In `DayViewTestTags.kt`, after `SettingsThemeDark`:

```kotlin
    const val SettingsThemeDark = "settingsThemeDark"
    const val SettingsFontScale = "settingsFontScale"
```

- [ ] **Step 2: Add the action**

In `SettingsUiModels.kt`, add to `SettingsScreenActions` (after `changeThemeMode`):

```kotlin
    val changeThemeMode: (ThemeMode) -> Unit = {},
    val changeFontScale: (Float) -> Unit = {},
```

- [ ] **Step 3: Wire the action in App.kt**

In `App.kt`, in the `SettingsScreenActions(...)` block, after `changeThemeMode = { controller.setThemeMode(it) },`:

```kotlin
                        changeThemeMode = { controller.setThemeMode(it) },
                        changeFontScale = { controller.setFontScale(it) },
```

- [ ] **Step 4: Add strings (English)**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, after the
`settings_theme_dark` line:

```xml
    <string name="settings_font_size">TEXT SIZE</string>
    <string name="settings_font_size_description">Make all text larger for easier reading.</string>
    <string name="settings_font_size_value">%1$d%</string>
```

- [ ] **Step 5: Add strings (French)**

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, after the
`settings_theme_dark` line:

```xml
    <string name="settings_font_size">TAILLE DU TEXTE</string>
    <string name="settings_font_size_description">Agrandissez tout le texte pour une lecture plus confortable.</string>
    <string name="settings_font_size_value">%1$d%</string>
```

- [ ] **Step 6: Write the failing UI test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/DisplaySettingsFontScaleTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DisplaySettingsFontScaleTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sliderRendersAndReportsChanges() = runComposeUiTest {
        var reported: Float? = null
        setContent {
            DayViewTheme {
                DisplaySettingsScreen(
                    state = seededUiState(fontScale = 1.0f),
                    platformState = SettingsPlatformUiState(
                        monochromeMenuBarIcon = null,
                        launchAtLogin = null,
                    ),
                    actions = seededSettingsActions(changeFontScale = { reported = it }),
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SettingsFontScale).assertExists()
        onNodeWithTag(DayViewTestTags.SettingsFontScale)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }

        assertNotNull(reported)
        assertTrue(reported!! > 1.0f)
    }
}
```

Note: `seededUiState(...)` and `seededSettingsActions(...)` are helpers in
`composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`. Before writing the
test, open `UiTestSupport.kt` and use whatever seeding helpers already exist there for
`DayViewUiState` and `SettingsScreenActions`. If a helper does not expose the field you
need (`fontScale` / `changeFontScale`), add a defaulted parameter to it in this step
rather than inventing a new helper. If no such helpers exist, construct
`DayViewUiState(...)` and `SettingsScreenActions(...)` inline with all required fields
(most `SettingsScreenActions` members are defaulted; supply the non-defaulted ones as
`{}` / `{ _ -> }`).

- [ ] **Step 7: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DisplaySettingsFontScaleTest"`
Expected: FAIL — no node with tag `settingsFontScale` (slider not built yet).

- [ ] **Step 8: Build the slider row**

In `DisplaySettingsScreen.kt`:

Add imports (with the existing ones):

```kotlin
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
```
(`androidx.compose.ui.platform.testTag` is already imported — do not duplicate; add only
the ones missing.)

Add these resource imports (with the other `fr.dayview.app.generated.resources.*` imports):

```kotlin
import fr.dayview.app.generated.resources.settings_font_size
import fr.dayview.app.generated.resources.settings_font_size_description
import fr.dayview.app.generated.resources.settings_font_size_value
```

Add the composable at the bottom of the file:

```kotlin
@Composable
private fun FontSizeSelector(
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
) {
    val colors = LocalDayViewColors.current
    SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.settings_font_size),
                color = colors.cloud,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.settings_font_size_value, (fontScale * 100).roundToInt()),
                color = colors.mint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.settings_font_size_description),
            color = colors.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Slider(
            value = fontScale,
            onValueChange = onFontScaleChange,
            valueRange = 1.0f..1.5f,
            steps = 9,
            modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsFontScale),
        )
    }
}
```

Then call it inside `DisplaySettingsScreen`, immediately after the
`ThemeModeSelector(...)` block and its trailing `Spacer`:

```kotlin
        ThemeModeSelector(
            selected = state.themeMode,
            onSelect = actions.changeThemeMode,
        )
        Spacer(Modifier.height(12.dp))
        FontSizeSelector(
            fontScale = state.fontScale,
            onFontScaleChange = actions.changeFontScale,
        )
        Spacer(Modifier.height(12.dp))
```

- [ ] **Step 9: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DisplaySettingsFontScaleTest"`
Expected: PASS.

- [ ] **Step 10: Full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS (BUILD SUCCESSFUL, no ktlint violations, no stderr). Run `ktlintFormat`
first if needed.

- [ ] **Step 11: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DisplaySettingsScreen.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/DisplaySettingsFontScaleTest.kt
git commit -m "Add a text-size slider to Display settings"
```

---

## Task 6: Manual verification and counter-overflow check

The one empirically-verified piece (spec §5): confirm the countdown numerals do not clip
at max font scale, and add the exemption only if they do.

**Files (only if the exemption is needed):**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`

- [ ] **Step 1: Launch the desktop app**

Run: `./gradlew :composeApp:run`

- [ ] **Step 2: Exercise the slider**

Open Settings → Display. Drag the text-size slider to maximum (150%). Confirm all
Settings text enlarges. Return to the main view and confirm labels/panels text is larger.

- [ ] **Step 3: Check the countdown numerals at max scale**

On the main view at 150%, look at the large `HHhMM` countdown numerals inside the ring.
Confirm they are not clipped or overflowing the dial. Resize/maximize the window and
confirm the ring grows toward its larger ceiling.

- [ ] **Step 4: Add the exemption only if numerals clip**

If (and only if) the numerals clip at max scale, wrap the counter `Column` inside
`CountdownCircle` (the `Column(horizontalAlignment = Alignment.CenterHorizontally)` that
holds the numerals, ~line 892 in `DayViewTodayScreen.kt`) so it renders at the base
(unscaled-by-preference) font scale. The counter is sized to the ring via `counterScale`,
not a readability target, so exempting it is correct:

```kotlin
// Requires imports: androidx.compose.runtime.CompositionLocalProvider,
// androidx.compose.ui.platform.LocalDensity, androidx.compose.ui.unit.Density.
val counterDensity = LocalDensity.current
CompositionLocalProvider(
    // The ring already grows to fill space; keep the numerals sized to the ring rather
    // than to the accessibility text scale so they never overflow the dial.
    LocalDensity provides Density(counterDensity.density, 1f),
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // ...existing counter content unchanged...
    }
}
```

Note this pins the counter's `fontScale` to `1f`, which also drops the OS font setting for
the numerals — acceptable because the numerals are ring-proportional, not body text. If
preserving the OS scale matters, instead divide out only the preference multiplier by
threading `state.fontScale` into `CountdownCircle` and using
`Density(counterDensity.density, counterDensity.fontScale / fontScale)`. Prefer the simple
`1f` form unless verification shows the OS scale is wanted here.

- [ ] **Step 5: Re-run the gate if any code changed**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS. Skip if Step 4 made no changes.

- [ ] **Step 6: Commit (only if Step 4 changed code)**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Keep countdown numerals sized to the ring under large text scale"
```

---

## Self-review notes

- **Spec coverage:** §1 space-filling → Task 1; §2 persistence → Task 2; §3 controller/state → Task 3; §4 global density override → Task 4; §5 counter-overflow risk → Task 6; §6 Settings slider → Task 5; §7 strings → Task 5; Testing section → Tasks 1–3 (unit/persistence) + Task 5 (UI) + Task 6 (manual).
- **Types consistent:** `countdownCircleSize`/`countdownCounterScale`, `fontScale: Float`, `setFontScale`, `changeFontScale`, `SettingsFontScale` used identically across tasks.
- **Coercion in three places (write/coerced/read)** is intentional defense in depth, matching how the spec specifies bounds at controller, snapshot, and store.
