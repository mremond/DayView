# Android Power-Settings Shortcut Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Android-only "System" settings category with a button that deep-links the user to the best available OS power/background-management screen, so they can exempt DayView and let the widget refresh alarm fire.

**Architecture:** A pure Android builder produces an ordered list of power-screen intent candidates (Onyx App Freeze → AOSP battery-optimization list → app details); a thin launcher opens the first that resolves. A new `SettingsCategory.SYSTEM`, gated by a `powerManagementSupported` platform flag, surfaces a common `SystemSettingsScreen` whose button invokes an Android-provided callback wired in `MainActivity`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Android `AlarmManager`/`Settings` intents, Compose resources for i18n, kotlin.test.

## Global Constraints

- All UI strings live in Compose resources with both `values/strings.xml` (English) and `values-fr/strings.xml` (French) entries.
- No new Android manifest permission is added.
- Desktop must keep compiling and must NOT show the System category (Android-only).
- Commit messages: English, describe the change only; no test-plan/verification section; no reference to internal planning docs; no AI/Claude references.
- Lint gate before any commit that touches Kotlin: `./gradlew ktlintCheck` (use `ktlintFormat` to auto-fix).

---

### Task 1: Android power-settings intent candidates (pure builder)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/PowerSettingsTest.kt`

**Interfaces:**
- Produces:
  - `internal data class PowerSettingsTarget(val action: String, val data: String? = null)`
  - `internal fun powerSettingsCandidates(packageName: String): List<PowerSettingsTarget>`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/PowerSettingsTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PowerSettingsTest {
    @Test
    fun candidatesTryOnyxFreezeFirstThenBatteryOptThenAppDetails() {
        val candidates = powerSettingsCandidates("fr.dayview.app")

        assertEquals(
            listOf(
                PowerSettingsTarget("onyx.settings.action.APP_FREEZE_MANAGEMENT", null),
                PowerSettingsTarget("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null),
                PowerSettingsTarget("android.settings.APPLICATION_DETAILS_SETTINGS", "package:fr.dayview.app"),
            ),
            candidates,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PowerSettingsTest"`
Expected: FAIL — compilation error, unresolved reference `powerSettingsCandidates` / `PowerSettingsTarget`.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt`:

```kotlin
package fr.dayview.app

import android.provider.Settings

/** A candidate OS screen to open, tried in order by [openPowerManagementSettings]. */
internal data class PowerSettingsTarget(val action: String, val data: String? = null)

/**
 * The power/background-management screens to try, best first: Onyx's "App Freeze"
 * manager (the real lever on BOOX e-ink devices), then the AOSP battery-optimization
 * list, then the app's details page (always resolves, universal fallback).
 */
internal fun powerSettingsCandidates(packageName: String): List<PowerSettingsTarget> = listOf(
    PowerSettingsTarget("onyx.settings.action.APP_FREEZE_MANAGEMENT"),
    PowerSettingsTarget(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    PowerSettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName"),
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.PowerSettingsTest"`
Expected: PASS.

- [ ] **Step 5: Lint + commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt composeApp/src/androidUnitTest/kotlin/fr/dayview/app/PowerSettingsTest.kt
git commit -m "Add ordered power-settings intent candidates for Android"
```

---

### Task 2: "System" settings category, gating, and screen (common)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:20-27` (enum)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt` (state, actions, gating)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (new param + wiring)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (metadata + detail branch)
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/SystemSettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt`

**Interfaces:**
- Consumes: `SettingsAccentButton(text, onClick)`, `SettingsSectionHeader(title, description)` from `SettingsComponents.kt`.
- Produces:
  - `SettingsCategory.SYSTEM` (core enum value)
  - `SettingsPlatformUiState.powerManagementSupported: Boolean` (default `false`)
  - `SettingsScreenActions.openPowerSettings: () -> Unit` (default `{}`)
  - `DayViewApp(..., onOpenPowerSettings: (() -> Unit)? = null, ...)`
  - `SystemSettingsScreen(onOpenPowerSettings: () -> Unit)`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsCategoriesTest {
    private fun platformState(powerManagementSupported: Boolean) = SettingsPlatformUiState(
        monochromeMenuBarIcon = null,
        launchAtLogin = null,
        powerManagementSupported = powerManagementSupported,
    )

    @Test
    fun systemCategoryIsListedWhenPowerManagementSupported() {
        assertTrue(SettingsCategory.SYSTEM in settingsCategoriesFor(platformState(true)))
    }

    @Test
    fun systemCategoryIsHiddenWhenUnsupported() {
        assertFalse(SettingsCategory.SYSTEM in settingsCategoriesFor(platformState(false)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SettingsCategoriesTest"`
Expected: FAIL — compilation error, unresolved `SettingsCategory.SYSTEM` / `powerManagementSupported`.

- [ ] **Step 3a: Add the enum value**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`, add `SYSTEM` to the enum:

```kotlin
enum class SettingsCategory {
    DAY,
    DISPLAY,
    SOUNDS,
    NET_TIME,
    ON_GOAL,
    SYNC,
    SYSTEM,
}
```

- [ ] **Step 3b: Extend platform state, actions, and gating**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt`:

Add to `SettingsPlatformUiState` (after `syncHasKey`):

```kotlin
    val powerManagementSupported: Boolean = false,
```

Add to `SettingsScreenActions` (after `clearSyncKey`):

```kotlin
    val openPowerSettings: () -> Unit = {},
```

Append to `settingsCategoriesFor`'s `buildList` (after `add(SettingsCategory.SYNC)`):

```kotlin
    if (platformState.powerManagementSupported) add(SettingsCategory.SYSTEM)
```

- [ ] **Step 3c: Add strings (English then French)**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, add:

```xml
    <string name="settings_section_system">SYSTEM</string>
    <string name="settings_system_description">Let DayView refresh in the background so the widget stays up to date.</string>
    <string name="settings_summary_system">Background refresh</string>
    <string name="system_settings_body">Some devices (e-ink readers and others) limit background updates, which can leave the home-screen widget frozen on the previous day. Allow DayView to run in the background or at startup to keep the widget current.</string>
    <string name="system_settings_open">Open power settings</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, add:

```xml
    <string name="settings_section_system">SYSTÈME</string>
    <string name="settings_system_description">Laissez DayView se rafraîchir en arrière-plan pour garder le widget à jour.</string>
    <string name="settings_summary_system">Rafraîchissement en arrière-plan</string>
    <string name="system_settings_body">Certains appareils (liseuses e-ink et autres) limitent les mises à jour en arrière-plan, ce qui peut figer le widget sur l\'écran d\'accueil sur la journée précédente. Autorisez DayView à s\'exécuter en arrière-plan ou au démarrage pour garder le widget à jour.</string>
    <string name="system_settings_open">Ouvrir les réglages d\'énergie</string>
```

- [ ] **Step 3d: Add test tags**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`, add (near the other `Settings*` tags, e.g. after `SettingsOnGoalScreen`):

```kotlin
    const val SettingsSystemScreen = "settingsSystemScreen"
    const val SettingsOpenPowerSettings = "settingsOpenPowerSettings"
```

- [ ] **Step 3e: Create the screen**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/SystemSettingsScreen.kt` (idiomatic imports mirroring `SoundSettingsScreen.kt`):

```kotlin
package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.system_settings_body
import fr.dayview.app.generated.resources.system_settings_open
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SystemSettingsScreen(onOpenPowerSettings: () -> Unit) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SettingsSystemScreen)) {
        SettingsPanelCard {
            Text(
                stringResource(Res.string.system_settings_body),
                color = colors.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsAccentButton(
            text = stringResource(Res.string.system_settings_open),
            onClick = onOpenPowerSettings,
            modifier = Modifier.testTag(DayViewTestTags.SettingsOpenPowerSettings),
        )
    }
}
```

- [ ] **Step 3f: Wire the detail branch and category metadata**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt`:

Add the import for the new strings alongside the others:

```kotlin
import fr.dayview.app.generated.resources.settings_section_system
import fr.dayview.app.generated.resources.settings_summary_system
import fr.dayview.app.generated.resources.settings_system_description
```

Add a branch to `SettingsCategoryDetail`'s `when (category)`:

```kotlin
        SettingsCategory.SYSTEM -> SystemSettingsScreen(onOpenPowerSettings = actions.openPowerSettings)
```

Add branches to `categoryTitle`, `categoryDescription`, and `categorySummary`:

```kotlin
// in categoryTitle's when:
        SettingsCategory.SYSTEM -> Res.string.settings_section_system
// in categoryDescription's when:
        SettingsCategory.SYSTEM -> Res.string.settings_system_description
// in categorySummary's when:
    SettingsCategory.SYSTEM -> stringResource(Res.string.settings_summary_system)
```

- [ ] **Step 3g: Add the `DayViewApp` parameter and wire it**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`, add the parameter to `DayViewApp` (after `onRequestCalendarPermission`):

```kotlin
    onOpenPowerSettings: (() -> Unit)? = null,
```

At the `SettingsPlatformUiState(...)` construction site, add:

```kotlin
                                powerManagementSupported = onOpenPowerSettings != null,
```

At the `SettingsScreenActions(...)` construction site, add:

```kotlin
                                openPowerSettings = onOpenPowerSettings ?: {},
```

- [ ] **Step 4: Run test + full common build to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SettingsCategoriesTest"`
Expected: PASS.

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (all exhaustive `when`s handled, desktop still compiles).

- [ ] **Step 5: Lint + commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/SystemSettingsScreen.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
  composeApp/src/commonMain/composeResources/values/strings.xml \
  composeApp/src/commonMain/composeResources/values-fr/strings.xml \
  composeApp/src/desktopTest/kotlin/fr/dayview/app/SettingsCategoriesTest.kt
git commit -m "Add an Android-only System settings category for power management"
```

---

### Task 3: Launcher + MainActivity wiring + verification

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt` (add launcher)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt:78-94` (pass callback)

**Interfaces:**
- Consumes: `powerSettingsCandidates(packageName)` (Task 1); `DayViewApp(onOpenPowerSettings = ...)` (Task 2).
- Produces: `fun openPowerManagementSettings(context: Context)`

- [ ] **Step 1: Add the launcher**

Append to `composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt` (add imports `android.content.ActivityNotFoundException`, `android.content.Context`, `android.content.Intent`, `android.net.Uri`):

```kotlin
/**
 * Opens the first power/background-management screen from [powerSettingsCandidates] that
 * resolves on this device, so the user can exempt DayView from OEM background limits.
 * The app-details fallback always resolves, so this always lands somewhere.
 */
fun openPowerManagementSettings(context: Context) {
    val packageManager = context.packageManager
    for (target in powerSettingsCandidates(context.packageName)) {
        val intent = Intent(target.action).apply {
            target.data?.let { data = Uri.parse(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageManager.resolveActivity(intent, 0) == null) continue
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Resolved but could not launch; fall through to the next candidate.
        }
    }
}
```

- [ ] **Step 2: Wire it in MainActivity**

In `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt`, in the `DayViewApp(...)` call, add after `onRequestCalendarPermission = { ... }`:

```kotlin
                onOpenPowerSettings = { openPowerManagementSettings(this) },
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full gate (lint + all tests)**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest :core:jvmTest`
Expected: BUILD SUCCESSFUL, no stderr.

- [ ] **Step 5: Device verification**

Run: `./gradlew :composeApp:installDebug` then open the app → Settings → the "SYSTEM" category → tap "Open power settings". Confirm it opens a power/background-management screen (on the BOOX Palma 2, the Onyx App Freeze screen). Return home.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain/kotlin/fr/dayview/app/PowerSettings.kt composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt
git commit -m "Open the device power-management screen from System settings"
```

---

## Notes for the implementer

- The System category is intentionally Android-only: desktop passes no `onOpenPowerSettings`, so `powerManagementSupported` is false and `settingsCategoriesFor` omits it. Do not add a desktop callback.
- Do not add any manifest permission. The shortcut only *opens* settings screens; it never toggles them.
- French strings escape apostrophes as `\'` (Compose resources / Android XML). Keep that.
- The known-good `SettingsAccentButton` / `SettingsPanelCard` / `SettingsSectionHeader` are the only building blocks needed; do not introduce new component primitives.
