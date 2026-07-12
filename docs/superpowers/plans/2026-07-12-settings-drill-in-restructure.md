# Settings Drill-In Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 825-line scrolling Settings screen with a drill-in list (a category landing screen that opens focused sub-screens) and split the monolith into focused files with shared, deduplicated composables.

**Architecture:** A new `settingsCategory` axis is added to `DayViewUiState`; `null` shows the category landing list, a value shows that sub-screen. The hardware/menu back handler decides depth (close category → return to list → return to Today). Shared composables (panel card, toggle row, section header, stepper, accent button) are extracted, then the screen is split into one file per category plus a scaffold that routes on `settingsCategory`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Material3), Compose resources for i18n, `runComposeUiTest` (desktopTest) + kotlin.test (commonTest).

## Global Constraints

- JDK 21 toolchain; commands assume the Android SDK is configured (`local.properties`).
- Commit messages: English, imperative, describe the change only. **Never** reference Claude/Anthropic/AI, never add a Co-Authored-By trailer, never add a test-plan or verification section, never reference `docs/superpowers/`.
- ktlint is enforced: `./gradlew ktlintCheck` must pass (use `ktlintFormat` to auto-fix).
- Full gate before declaring done: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- UI-test assertions must query by tag or seeded data, never by async `stringResource` text (it times out under `runComposeUiTest`).
- Test tags live in `DayViewTestTags` (commonMain) so production and tests share one source of truth; the object is `@Suppress("ktlint:standard:property-naming")` and uses PascalCase constants.
- Behavior is preserved exactly — no new settings, no control changes, only structure + navigation.
- New files stay in package `fr.dayview.app` under `composeApp/src/commonMain/kotlin/fr/dayview/app/` (flat, no subpackage).

---

## File Structure

**Created:**
- `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt` — `SettingsCategory` enum, `SettingsPlatformUiState`, `SettingsScreenActions`, and the pure `settingsCategoriesFor(...)` + landing-summary helpers (moved out of the screen file).
- `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt` — shared composables: `SettingsPanelCard`, `SettingsToggleRow`, `SettingsSectionHeader`, `SettingsStepper`, `SettingsAccentButton`, plus the relocated `SettingsDivider`, `TimePreferenceRow`, `PreviewSoundButton`.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DaySettingsScreen.kt`, `DisplaySettingsScreen.kt`, `SoundSettingsScreen.kt`, `NetTimeSettingsScreen.kt`, `OnGoalAppsScreen.kt` — one sub-screen composable per category.

**Modified:**
- `DayViewController.kt` — `SettingsCategory`-aware nav (methods + `openSettings` reset).
- `DayViewUiState` (in `DayViewController.kt`) — new `settingsCategory` field.
- `App.kt` — back-handler depth logic; pass new nav actions + category state.
- `DayViewSettingsScreen.kt` — shrinks to the scaffold (top bar + landing list + router); most content moves out.
- `DayViewTestTags.kt` — new row/sub-screen tags.
- `UiTestSupport.kt` — `noopSettingsActions` gains the new nav callbacks.
- `SettingsScreenTest.kt` — migrated to drill-in.
- `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-fr/strings.xml` — landing-summary strings.

**Test files:**
- `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` — nav tests (append).
- `composeApp/src/commonTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt` — new, for `settingsCategoriesFor`.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsDrillInTest.kt` — new, drill-in nav.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt` — migrated.

---

## Task 1: Navigation state + controller

Add the `settingsCategory` axis and controller navigation. Pure logic, fully unit-tested at the controller level. No UI changes yet — the existing monolithic screen ignores the new field, so the build and all existing tests stay green.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt:111`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Produces:
  - `enum class SettingsCategory { DAY, DISPLAY, SOUNDS, NET_TIME, ON_GOAL }`
  - `DayViewUiState.settingsCategory: SettingsCategory?` (default `null`)
  - `DayViewController.openSettings()` — now also sets `settingsCategory = null`
  - `DayViewController.openSettingsCategory(category: SettingsCategory)`
  - `DayViewController.closeSettingsCategory()`

- [ ] **Step 1: Write the failing controller tests**

Append to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` (inside the `DayViewControllerTest` class):

```kotlin
    @Test
    fun openingSettingsStartsOnTheCategoryList() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettingsCategory(SettingsCategory.SOUNDS)

        controller.openSettings()

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals(null, controller.state.settingsCategory)
    }

    @Test
    fun openingACategoryDrillsIn() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettings()

        controller.openSettingsCategory(SettingsCategory.DAY)

        assertEquals(SettingsCategory.DAY, controller.state.settingsCategory)
    }

    @Test
    fun closingACategoryReturnsToTheList() {
        val controller = testController(InMemoryDayPreferences(), 1_000L)
        controller.openSettings()
        controller.openSettingsCategory(SettingsCategory.DAY)

        controller.closeSettingsCategory()

        assertEquals(DayViewDestination.SETTINGS, controller.state.destination)
        assertEquals(null, controller.state.settingsCategory)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `SettingsCategory` / `openSettingsCategory` / `settingsCategory` unresolved (compile error).

- [ ] **Step 3: Add the enum and state field**

In `DayViewController.kt`, add the enum next to `DayViewDestination` (after line 17):

```kotlin
internal enum class SettingsCategory {
    DAY,
    DISPLAY,
    SOUNDS,
    NET_TIME,
    ON_GOAL,
}
```

Add the field to `DayViewUiState` (alongside `destination` at line 40):

```kotlin
    val destination: DayViewDestination = DayViewDestination.TODAY,
    val settingsCategory: SettingsCategory? = null,
```

- [ ] **Step 4: Add the controller navigation methods**

In `DayViewController.kt`, replace the existing `openSettings()` (lines 133-135) and add the two new methods:

```kotlin
    fun openSettings() {
        state = state.copy(destination = DayViewDestination.SETTINGS, settingsCategory = null)
    }

    fun openSettingsCategory(category: SettingsCategory) {
        state = state.copy(settingsCategory = category)
    }

    fun closeSettingsCategory() {
        state = state.copy(settingsCategory = null)
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS (all tests, including the three new ones).

- [ ] **Step 6: Wire the back handler to decide depth**

In `App.kt`, replace the `PlatformBackHandler` block at line 111-113:

```kotlin
            PlatformBackHandler(enabled = state.destination == DayViewDestination.SETTINGS) {
                if (state.settingsCategory != null) {
                    controller.closeSettingsCategory()
                } else {
                    controller.openToday()
                }
            }
```

- [ ] **Step 7: Verify the full build + lint compiles**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no ktlint violations. (The desktop screen still renders the old single scroll — unchanged behavior.)

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: add settings category navigation state"
```

---

## Task 2: Extract shared Settings composables

Pull the repeated boilerplate out of `DayViewSettingsScreen.kt` into `SettingsComponents.kt` and rewire the existing screen to use them. The UI stays pixel-identical, so the migrated safety net is the existing `SettingsScreenTest` staying green — no behavior change.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt`
- Verify with: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt` (unchanged, must stay green)

**Interfaces:**
- Produces (all `@Composable internal`):
  - `SettingsPanelCard(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), content: @Composable ColumnScope.() -> Unit)`
  - `SettingsToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier)`
  - `SettingsSectionHeader(title: String, description: String)`
  - `SettingsStepper(label: String, valueText: String, canDecrease: Boolean, canIncrease: Boolean, decreaseLabel: String, increaseLabel: String, valueDescription: String, onDecrease: () -> Unit, onIncrease: () -> Unit, modifier: Modifier = Modifier)`
  - `SettingsAccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - Relocated as-is: `SettingsDivider()`, `TimePreferenceRow(...)`, `PreviewSoundButton(onClick: () -> Unit)`

- [ ] **Step 1: Create `SettingsComponents.kt` with the shared composables**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt`:

```kotlin
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.modify
import fr.dayview.app.generated.resources.modify_label
import fr.dayview.app.generated.resources.sound_preview
import org.jetbrains.compose.resources.stringResource

private val PanelShape = RoundedCornerShape(18.dp)

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
                "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}",
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
```

- [ ] **Step 2: Remove the now-duplicated definitions from `DayViewSettingsScreen.kt`**

Delete these composables from `DayViewSettingsScreen.kt` (they now live in `SettingsComponents.kt`): `SettingsDivider` (lines ~782-786), `TimePreferenceRow` (lines ~788-825), `PreviewSoundButton` (lines ~767-780). Leave the rest for now.

- [ ] **Step 3: Rewire the Display toggles to `SettingsToggleRow`**

In `SettingsScreen`, replace the three hand-rolled toggle `Row`s (show-seconds, monochrome, launch-at-login — lines ~204-314) with `SettingsToggleRow`. The show-seconds one must keep its test tag:

```kotlin
                SettingsToggleRow(
                    title = stringResource(Res.string.settings_show_seconds),
                    description = stringResource(Res.string.settings_show_seconds_description),
                    checked = state.showSeconds,
                    onCheckedChange = actions.changeShowSeconds,
                    modifier = Modifier.testTag(DayViewTestTags.SettingsShowSeconds),
                )
                if (
                    platformState.monochromeMenuBarIcon != null &&
                    actions.changeMonochromeMenuBarIcon != null
                ) {
                    Spacer(Modifier.height(12.dp))
                    SettingsToggleRow(
                        title = stringResource(Res.string.settings_monochrome_icon),
                        description = stringResource(Res.string.settings_monochrome_icon_description),
                        checked = platformState.monochromeMenuBarIcon,
                        onCheckedChange = actions.changeMonochromeMenuBarIcon,
                    )
                }
                if (platformState.launchAtLogin != null && actions.changeLaunchAtLogin != null) {
                    Spacer(Modifier.height(12.dp))
                    SettingsToggleRow(
                        title = stringResource(Res.string.settings_launch_at_login),
                        description = stringResource(Res.string.settings_launch_at_login_description),
                        checked = platformState.launchAtLogin,
                        onCheckedChange = actions.changeLaunchAtLogin,
                    )
                }
```

- [ ] **Step 4: Rewire the Day / Net-time / Sound panels and steppers to the shared pieces**

In the Day section, wrap the two `TimePreferenceRow`s in `SettingsPanelCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp))`. In `NetTimeSettingsPanel` and `SoundSettingsPanel`, replace each `Text(section) + Spacer + Text(description)` header pair with `SettingsSectionHeader(...)`, each enable `Row` with `SettingsToggleRow(...)`, each hand-rolled panel `Column`/`Row` container with `SettingsPanelCard`, each mint pill (`settings_grant_calendar_access`) with `SettingsAccentButton`, and the interval + volume `−/value/+` rows with `SettingsStepper`. Preserve the `SettingsSounds` test tag on the sound section's `SettingsSectionHeader` wrapper — keep the existing `Modifier.testTag(DayViewTestTags.SettingsSounds)` on the outermost element of `SoundSettingsPanel`. The `SoundCueSettingRow` composable stays local for now. Do not change any strings, spacings, or callback wiring.

- [ ] **Step 5: Verify build, lint, and the existing UI tests all pass**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL. `SettingsScreenTest` (all four tests) passes unchanged — this proves the UI is behaviorally identical.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsComponents.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt
git commit -m "refactor: extract shared Settings composables"
```

---

## Task 3: Drill-in behavior — landing list, router, sub-screen scaffold

Turn the single scroll into a landing list + routed sub-screens. This is the behavioral heart of the change: nav actions, the router, the `settingsCategoriesFor` filter, summary strings, new tags, and migrated + new UI tests all land together so the suite is green at the end.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DaySettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DisplaySettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/SoundSettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/NetTimeSettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalAppsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt:157`
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt`
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsScreenTest.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsDrillInTest.kt`

**Interfaces:**
- Consumes (from Task 1): `SettingsCategory`, `DayViewUiState.settingsCategory`, `openSettingsCategory`, `closeSettingsCategory`. (from Task 2): `SettingsPanelCard`, `SettingsToggleRow`, `SettingsSectionHeader`, `SettingsStepper`, `SettingsAccentButton`, `SettingsDivider`, `TimePreferenceRow`, `PreviewSoundButton`.
- Produces:
  - `settingsCategoriesFor(platformState: SettingsPlatformUiState): List<SettingsCategory>`
  - `SettingsScreenActions.openCategory: (SettingsCategory) -> Unit`
  - `SettingsScreenActions.closeCategory: () -> Unit`
  - Sub-screen composables `DaySettingsScreen`, `DisplaySettingsScreen`, `SoundSettingsScreen`, `NetTimeSettingsScreen`, `OnGoalAppsScreen` (each `@Composable internal`, taking the state/actions slices they need).
  - New tags on `DayViewTestTags`: `SettingsCategoryRow(category)` helper + `SettingsDayScreen`, `SettingsDisplayScreen`, `SettingsSoundsScreen`, `SettingsNetTimeScreen`, `SettingsOnGoalScreen`.

- [ ] **Step 1: Add landing-summary strings**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, add after the settings block:

```xml
    <!-- Settings — category list summaries -->
    <string name="settings_summary_day">%1$s – %2$s</string>
    <string name="settings_summary_on">On</string>
    <string name="settings_summary_off">Off</string>
    <string name="settings_summary_seconds_on">Seconds shown</string>
    <string name="settings_summary_seconds_off">Seconds hidden</string>
    <string name="settings_summary_sounds_on">On · every %1$s min</string>
    <string name="settings_summary_apps">%1$s apps</string>
    <string name="settings_summary_no_apps">None selected</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, add the French equivalents:

```xml
    <!-- Settings — category list summaries -->
    <string name="settings_summary_day">%1$s – %2$s</string>
    <string name="settings_summary_on">Activé</string>
    <string name="settings_summary_off">Désactivé</string>
    <string name="settings_summary_seconds_on">Secondes affichées</string>
    <string name="settings_summary_seconds_off">Secondes masquées</string>
    <string name="settings_summary_sounds_on">Activé · toutes les %1$s min</string>
    <string name="settings_summary_apps">%1$s applis</string>
    <string name="settings_summary_no_apps">Aucune</string>
```

- [ ] **Step 2: Write the failing `settingsCategoriesFor` test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCategoriesTest {
    @Test
    fun androidLikePlatformOmitsDesktopOnlyCategories() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = null,
            launchAtLogin = null,
            netTimeSupported = true,
            onGoalSupported = false,
        )

        assertEquals(
            listOf(
                SettingsCategory.DAY,
                SettingsCategory.DISPLAY,
                SettingsCategory.SOUNDS,
                SettingsCategory.NET_TIME,
            ),
            settingsCategoriesFor(platform),
        )
    }

    @Test
    fun desktopPlatformIncludesOnGoalWhenSupported() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = true,
            launchAtLogin = true,
            netTimeSupported = true,
            onGoalSupported = true,
        )

        assertEquals(
            listOf(
                SettingsCategory.DAY,
                SettingsCategory.DISPLAY,
                SettingsCategory.SOUNDS,
                SettingsCategory.NET_TIME,
                SettingsCategory.ON_GOAL,
            ),
            settingsCategoriesFor(platform),
        )
    }

    @Test
    fun dayDisplaySoundsAreAlwaysPresent() {
        val platform = SettingsPlatformUiState(
            monochromeMenuBarIcon = null,
            launchAtLogin = null,
            netTimeSupported = false,
            onGoalSupported = false,
        )

        assertEquals(
            listOf(SettingsCategory.DAY, SettingsCategory.DISPLAY, SettingsCategory.SOUNDS),
            settingsCategoriesFor(platform),
        )
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.SettingsCategoriesTest"`
Expected: FAIL — `settingsCategoriesFor` unresolved (compile error).

- [ ] **Step 4: Create `SettingsUiModels.kt` with the models + filter**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt`, moving `SettingsPlatformUiState` and `SettingsScreenActions` out of `DayViewSettingsScreen.kt` and adding the two nav callbacks and the filter:

```kotlin
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
internal fun settingsCategoriesFor(platformState: SettingsPlatformUiState): List<SettingsCategory> =
    buildList {
        add(SettingsCategory.DAY)
        add(SettingsCategory.DISPLAY)
        add(SettingsCategory.SOUNDS)
        if (platformState.netTimeSupported) add(SettingsCategory.NET_TIME)
        if (platformState.onGoalSupported) add(SettingsCategory.ON_GOAL)
    }
```

Delete the two moved `data class` declarations from `DayViewSettingsScreen.kt` (lines ~94-114).

- [ ] **Step 5: Run the filter test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.SettingsCategoriesTest"`
Expected: PASS.

- [ ] **Step 6: Add the new test tags (and retire the old sound-panel tag)**

In `DayViewTestTags.kt`, add inside the object:

```kotlin
    const val SettingsDayScreen = "settingsDayScreen"
    const val SettingsDisplayScreen = "settingsDisplayScreen"
    const val SettingsSoundsScreen = "settingsSoundsScreen"
    const val SettingsNetTimeScreen = "settingsNetTimeScreen"
    const val SettingsOnGoalScreen = "settingsOnGoalScreen"

    fun settingsCategoryRow(category: SettingsCategory): String = "settingsCategoryRow_${category.name}"
```

Remove the now-superseded `const val SettingsSounds = "settingsSounds"` line — the sound section becomes the `SettingsSoundsScreen`-tagged sub-screen in Step 7, and its only consumer (`rendersSoundPanel`) is replaced in Step 11. Its single usage in `DayViewSettingsScreen.kt` disappears when the panel body moves in Step 7.

- [ ] **Step 7: Move each section into its own sub-screen file**

For each category, create a file whose composable renders the section body (moved from `DayViewSettingsScreen.kt` / the existing panels), wrapped in a `Column` tagged with the sub-screen tag. Each takes only the state/actions it needs. Example — `DisplaySettingsScreen.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.settings_launch_at_login
import fr.dayview.app.generated.resources.settings_launch_at_login_description
import fr.dayview.app.generated.resources.settings_monochrome_icon
import fr.dayview.app.generated.resources.settings_monochrome_icon_description
import fr.dayview.app.generated.resources.settings_show_seconds
import fr.dayview.app.generated.resources.settings_show_seconds_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DisplaySettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsDisplayScreen)) {
        SettingsToggleRow(
            title = stringResource(Res.string.settings_show_seconds),
            description = stringResource(Res.string.settings_show_seconds_description),
            checked = state.showSeconds,
            onCheckedChange = actions.changeShowSeconds,
            modifier = Modifier.testTag(DayViewTestTags.SettingsShowSeconds),
        )
        if (platformState.monochromeMenuBarIcon != null && actions.changeMonochromeMenuBarIcon != null) {
            Spacer(Modifier.height(12.dp))
            SettingsToggleRow(
                title = stringResource(Res.string.settings_monochrome_icon),
                description = stringResource(Res.string.settings_monochrome_icon_description),
                checked = platformState.monochromeMenuBarIcon,
                onCheckedChange = actions.changeMonochromeMenuBarIcon,
            )
        }
        if (platformState.launchAtLogin != null && actions.changeLaunchAtLogin != null) {
            Spacer(Modifier.height(12.dp))
            SettingsToggleRow(
                title = stringResource(Res.string.settings_launch_at_login),
                description = stringResource(Res.string.settings_launch_at_login_description),
                checked = platformState.launchAtLogin,
                onCheckedChange = actions.changeLaunchAtLogin,
            )
        }
    }
}
```

`DaySettingsScreen.kt` is reassembled from the old inline Day section — full code:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.day_end
import fr.dayview.app.generated.resources.day_start
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DaySettingsScreen(
    state: DayViewUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
    val progress = state.dayProgress
    val timePicker = rememberTimePickerLauncher()
    SettingsPanelCard(
        modifier = Modifier.testTag(DayViewTestTags.SettingsDayScreen),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TimePreferenceRow(
            label = stringResource(Res.string.day_start),
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
            label = stringResource(Res.string.day_end),
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
}
```

Apply the same wrapping pattern to the remaining three, moving their existing bodies verbatim (only wrapping in the tagged `Column`, dropping the `SettingsSectionHeader` the scaffold now renders, keeping all strings/spacings/callbacks):
- `SoundSettingsScreen.kt` — `SoundSettingsScreen(settings, onSettingsChange, onPreview)`: the body of the old `SoundSettingsPanel` (minus its `SettingsSectionHeader`, which the scaffold now renders), plus the local `SoundCueSettingRow`, tagged `SettingsSoundsScreen`.
- `NetTimeSettingsScreen.kt` — `NetTimeSettingsScreen(settings, calendars, hasPermission, onSettingsChange, onRequestPermission)`: the old `NetTimeSettingsPanel` body (minus header) + local `NetTimeCalendarRow`, tagged `SettingsNetTimeScreen`.
- `OnGoalAppsScreen.kt` — `OnGoalAppsScreen(onGoalApps, runningApps, onOnGoalAppsChange)`: the old `OnGoalAppsPanel` body (minus header) + local `OnGoalAppRow`, tagged `SettingsOnGoalScreen`.

Delete the moved `NetTimeSettingsPanel`, `NetTimeCalendarRow`, `OnGoalAppsPanel`, `OnGoalAppRow`, `SoundSettingsPanel`, `SoundCueSettingRow` from `DayViewSettingsScreen.kt`.

- [ ] **Step 8: Rewrite `DayViewSettingsScreen.kt` as the scaffold + landing list + router**

Replace the remaining `SettingsScreen` body so it renders the top bar, then either the landing list (when `state.settingsCategory == null`) or the selected sub-screen wrapped in a `SettingsSectionHeader`. Key structure (keep the existing outer `Box`/gradient/`safeDrawingPadding` and the top `Row` with the `SettingsBack` tag):

```kotlin
@Composable
internal fun SettingsScreen(
    state: DayViewUiState,
    platformState: SettingsPlatformUiState,
    actions: SettingsScreenActions,
) {
    val colors = LocalDayViewColors.current
    Box(/* unchanged gradient background + safeDrawingPadding */) {
        Column(/* unchanged scroll + padding */, horizontalAlignment = Alignment.CenterHorizontally) {
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
```

Add these private helpers in the same file:
- `SettingsTopBar(onBack)` — the existing back `Text` (tagged `SettingsBack`, same styles/strings) + spacer + title `Text`, unchanged from the current top `Row`.
- `SettingsCategoryList(...)` — `settingsCategoriesFor(platformState).forEachIndexed { i, cat -> if (i > 0) Spacer(Modifier.height(12.dp)); SettingsCategoryRow(cat, state, platformState, actions.openCategory) }`, followed by a `Spacer(12.dp)` and the `settings_autosave_note` footer `Text` (same style as today).
- `SettingsCategoryDetail(...)` — `SettingsSectionHeader(categoryTitle(category), categoryDescription(category))`, `Spacer(Modifier.height(18.dp))`, then a `when (category)` dispatching to the Step-7 sub-screen composable (passing the state/actions slices each needs).

`SettingsCategoryRow` and the title/description/summary mapping — full code:

```kotlin
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
        if (state.showSeconds) stringResource(Res.string.settings_summary_seconds_on)
        else stringResource(Res.string.settings_summary_seconds_off)
    SettingsCategory.SOUNDS ->
        if (state.soundSettings.enabled) {
            stringResource(Res.string.settings_summary_sounds_on, state.soundSettings.intervalMinutes.toString())
        } else {
            stringResource(Res.string.settings_summary_off)
        }
    SettingsCategory.NET_TIME ->
        if (state.netTimeSettings.enabled) stringResource(Res.string.settings_summary_on)
        else stringResource(Res.string.settings_summary_off)
    SettingsCategory.ON_GOAL ->
        if (state.onGoalApps.isEmpty()) stringResource(Res.string.settings_summary_no_apps)
        else stringResource(Res.string.settings_summary_apps, state.onGoalApps.size.toString())
}
```

The `Res.string.*` imports for these (`settings_section_*`, `settings_*_description`, `settings_summary_*`, `settings_autosave_note`) stay in `DayViewSettingsScreen.kt`; drop imports for strings that moved entirely into sub-screens.

- [ ] **Step 9: Update the `App.kt` call site**

In `App.kt`, add the two nav callbacks to the `SettingsScreenActions(...)` built at line 167:

```kotlin
                        openCategory = { controller.openSettingsCategory(it) },
                        closeCategory = { controller.closeSettingsCategory() },
```

- [ ] **Step 10: Extend the test helper with the new callbacks**

In `UiTestSupport.kt`, add parameters to `noopSettingsActions` and pass them through:

```kotlin
internal fun noopSettingsActions(
    changeStartTime: (Int) -> Unit = {},
    changeEndTime: (Int) -> Unit = {},
    changeShowSeconds: (Boolean) -> Unit = {},
    changeSoundSettings: (SoundSettings) -> Unit = {},
    previewSound: (SoundCue) -> Unit = {},
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
    openCategory = openCategory,
    closeCategory = closeCategory,
    back = back,
)
```

- [ ] **Step 11: Migrate `SettingsScreenTest` to drill-in**

The landing list no longer shows day-range / show-seconds / sound panel directly. Rewrite the three affected tests to render controller-backed and drill in first; keep `backLinkInvokesCallback` (it fires `actions.back` from the landing list) but switch it to the controller-backed pattern for consistency. Replace the file body's test methods with:

```kotlin
    @Test
    fun landingListShowsSupportedCategories() = runComposeUiTest {
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DAY)).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertExists()
    }

    @Test
    fun drillingIntoDayShowsSeededRange() = runComposeUiTest {
        val snapshot = DayPreferencesSnapshot(startMinutes = 8 * 60, endMinutes = 18 * 60, showSeconds = true)
        setContent {
            val controller = remember { seededController(snapshot) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(openCategory = { controller.openSettingsCategory(it) }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DAY)).performClick()
        onNodeWithText("08:00").assertExists()
        onNodeWithText("18:00").assertExists()
    }

    @Test
    fun togglingShowSecondsInDisplayInvokesCallback() = runComposeUiTest {
        var recorded: Boolean? = null
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot(showSeconds = true)) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(
                        changeShowSeconds = { recorded = it },
                        openCategory = { controller.openSettingsCategory(it) },
                    ),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.DISPLAY)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsShowSeconds).performClick()
        assertEquals(false, recorded)
    }

    @Test
    fun backLinkFromListInvokesCallback() = runComposeUiTest {
        var backCalled = false
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(back = { backCalled = true }),
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        assertTrue(backCalled)
    }
```

Note: rendering `controller.state` inside `setContent` (not captured in `remember`) lets recomposition observe the drill-in state change after `performClick`.

- [ ] **Step 12: Add the drill-in navigation test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsDrillInTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsDrillInTest {
    private val platform = SettingsPlatformUiState(monochromeMenuBarIcon = null, launchAtLogin = null)

    @Test
    fun drillingIntoSoundsShowsTheSubScreenThenBackReturnsToList() = runComposeUiTest {
        setContent {
            val controller = remember { seededController(DayPreferencesSnapshot()) }
            DayViewTheme {
                SettingsScreen(
                    state = controller.state,
                    platformState = platform,
                    actions = noopSettingsActions(
                        openCategory = { controller.openSettingsCategory(it) },
                        closeCategory = { controller.closeSettingsCategory() },
                    ),
                )
            }
        }
        // Landing → Sounds sub-screen.
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).performClick()
        onNodeWithTag(DayViewTestTags.SettingsSoundsScreen).assertExists()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertDoesNotExist()
        // Back → landing list.
        onNodeWithTag(DayViewTestTags.SettingsBack).performClick()
        onNodeWithTag(DayViewTestTags.settingsCategoryRow(SettingsCategory.SOUNDS)).assertExists()
    }
}
```

- [ ] **Step 13: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL — all migrated + new tests pass, no ktlint violations.

- [ ] **Step 14: Manually verify the drill-in on desktop**

Run: `./gradlew :composeApp:run`. Open Settings from Today. Confirm: the landing list shows Day / Display / Sounds / Net time (+ Goal apps if apps are running) with live summaries; clicking a row opens its sub-screen; the back arrow returns to the list; back again returns to Today. Toggle show-seconds, change day times, adjust volume — confirm each still persists and applies.

- [ ] **Step 15: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/ \
        composeApp/src/commonMain/composeResources/ \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/
git commit -m "feat: restructure Settings as a drill-in category list"
```

---

## Task 4: Verification sweep + docs

Final consistency pass: confirm the monolith is genuinely reduced, no dead code remains, and the whole gate is green on a clean build.

**Files:**
- Modify (if needed): any stray imports in `DayViewSettingsScreen.kt`.

- [ ] **Step 1: Confirm no orphaned/duplicate declarations remain**

Run: `grep -rn "fun SoundSettingsPanel\|fun NetTimeSettingsPanel\|fun OnGoalAppsPanel\|data class SettingsPlatformUiState\|data class SettingsScreenActions" composeApp/src/commonMain/kotlin/fr/dayview/app`
Expected: no matches in `DayViewSettingsScreen.kt` (models live in `SettingsUiModels.kt`; panels are gone/renamed to sub-screens).

- [ ] **Step 2: Confirm the file sizes dropped**

Run: `wc -l composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt composeApp/src/commonMain/kotlin/fr/dayview/app/Settings*.kt composeApp/src/commonMain/kotlin/fr/dayview/app/*SettingsScreen.kt`
Expected: `DayViewSettingsScreen.kt` is now the scaffold only (well under ~250 lines); the rest are focused files.

- [ ] **Step 3: Run the full clean gate**

Run: `./gradlew clean ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL with no errors and no stderr warnings from ktlint.

- [ ] **Step 4: Commit (only if Step 1-3 required edits; otherwise skip)**

```bash
git add -A
git commit -m "chore: tidy Settings restructure leftovers"
```

---

## Self-Review Notes

- **Spec coverage:** navigation model → Task 1; live value summaries + landing list + router → Task 3 (Steps 1, 8); file split + shared components → Tasks 2 & 4; edge-case fallback (unsupported category) is handled because `settingsCategoriesFor` gates the list and the `when` router only receives categories the user could open — a category whose flag flips off simply disappears from the list on recomposition, and `settingsCategory` is reset on every `openSettings`; deep-link safety → Task 1 Step 4 (`openSettings` resets category); testing (controller nav, pure filter, drill-in UI, migrated existing tests) → Tasks 1 & 3.
- **Deviations from spec (intentional):** flat files in `fr.dayview.app` instead of a `settings/` subpackage (matches the repo's flat convention, avoids test import churn — spec updated to match). Value summaries are formatted inline in `@Composable` helpers rather than pure functions, because they require `stringResource`; the pure, unit-tested logic is `settingsCategoriesFor`.
