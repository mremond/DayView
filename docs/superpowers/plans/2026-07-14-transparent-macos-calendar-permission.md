# Transparent macOS Calendar Permission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On macOS, request calendar access automatically when Net Time is enabled so the redundant "Grant calendar access" click disappears, and route a genuine denial to a System Settings link instead of a dead-end button.

**Architecture:** Surface EventKit's real tri-state authorization (`GRANTED` / `DENIED` / `NOT_DETERMINED`) to shared code. When Net Time is enabled and status is undetermined, the background probe issues the request once (silent if the grant already exists, normal OS popup only for a true first grant). A denied status drives a Settings-link branch in the Net Time settings card. Android is untouched: its not-granted state maps to `NOT_DETERMINED`, so it keeps its existing explicit-button flow, and its `CalendarSource.requestPermission()` stays a no-op so the auto-request never fires an Android dialog.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Swift EventKit helper (unchanged), JUnit (`:core:jvmTest`, `:composeApp:desktopTest`, `:composeApp:testDebugUnitTest`).

## Global Constraints

- ktlint is enforced; run `./gradlew ktlintCheck` before every commit (or `ktlintFormat` to auto-fix).
- Full gate before finishing: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages describe the change only — no reference to Claude/Anthropic/AI, no test-plan or verification section, no reference to `docs/superpowers/`.
- Compose UI test harness rules: never assert `stringResource` text (unresolved on CI) — use test tags / seeded data; `assertExists` is a member (no import).
- New user-facing strings must be added to both `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-fr/strings.xml`.
- The Swift EventKit helper (`scripts/MacEventKitHelper.swift`) is out of scope — it already emits `GRANTED` / `DENIED` / `NOTDETERMINED`.
- Android request flow is out of scope and must stay behavior-identical.

---

### Task 1: Tri-state authorization in core

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/CalendarModel.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class CalendarAuthStatus { GRANTED, DENIED, NOT_DETERMINED }`
  - `CalendarSource.authorizationStatus(): CalendarAuthStatus` (interface method with a default body).
  - `fun shouldAutoRequestCalendarAccess(status: CalendarAuthStatus, netTimeEnabled: Boolean, alreadyRequested: Boolean): Boolean`

- [ ] **Step 1: Write the failing tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt` (inside the existing test class):

```kotlin
@Test
fun authorizationStatusDefaultsToGrantedWhenPermitted() {
    val source = object : CalendarSource {
        override fun isSupported() = true
        override fun hasPermission() = true
        override fun requestPermission() = Unit
        override fun availableCalendars(): List<CalendarInfo> = emptyList()
        override fun busyIntervals(
            windowStart: kotlin.time.Instant,
            windowEnd: kotlin.time.Instant,
            includedCalendarIds: Set<String>,
        ): List<BusyInterval> = emptyList()
    }
    assertEquals(CalendarAuthStatus.GRANTED, source.authorizationStatus())
}

@Test
fun authorizationStatusDefaultsToNotDeterminedWhenNotPermitted() {
    assertEquals(CalendarAuthStatus.NOT_DETERMINED, NoopCalendarSource.authorizationStatus())
}

@Test
fun autoRequestsOnlyWhenEnabledUndeterminedAndNotYetRequested() {
    assertTrue(shouldAutoRequestCalendarAccess(CalendarAuthStatus.NOT_DETERMINED, netTimeEnabled = true, alreadyRequested = false))
    assertFalse(shouldAutoRequestCalendarAccess(CalendarAuthStatus.NOT_DETERMINED, netTimeEnabled = true, alreadyRequested = true))
    assertFalse(shouldAutoRequestCalendarAccess(CalendarAuthStatus.NOT_DETERMINED, netTimeEnabled = false, alreadyRequested = false))
    assertFalse(shouldAutoRequestCalendarAccess(CalendarAuthStatus.GRANTED, netTimeEnabled = true, alreadyRequested = false))
    assertFalse(shouldAutoRequestCalendarAccess(CalendarAuthStatus.DENIED, netTimeEnabled = true, alreadyRequested = false))
}
```

Ensure these imports exist at the top of the file: `kotlin.test.assertEquals`, `kotlin.test.assertTrue`, `kotlin.test.assertFalse`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `CalendarAuthStatus` / `authorizationStatus` / `shouldAutoRequestCalendarAccess` unresolved.

- [ ] **Step 3: Add the enum, interface default, and helper**

In `core/src/commonMain/kotlin/fr/dayview/app/CalendarModel.kt`, add the enum near the top (after the `NetTimeSettings` data class):

```kotlin
/** Tri-state calendar authorization, mirroring EventKit's granted / denied / not-determined. */
enum class CalendarAuthStatus { GRANTED, DENIED, NOT_DETERMINED }
```

Add to the `CalendarSource` interface (below `requestPermission()`):

```kotlin
/**
 * Autorisation d'accès au calendrier en trois états. Par défaut dérivée de [hasPermission] :
 * accordée, sinon indéterminée (l'appelant peut encore demander). Le bureau macOS surcharge
 * cette valeur pour distinguer un refus explicite.
 */
fun authorizationStatus(): CalendarAuthStatus =
    if (hasPermission()) CalendarAuthStatus.GRANTED else CalendarAuthStatus.NOT_DETERMINED
```

Add as a top-level function at the end of the file:

```kotlin
/**
 * Décide si le sondage doit déclencher une demande d'accès automatiquement : uniquement quand
 * Net Time est actif, que le statut est indéterminé, et qu'aucune demande n'a déjà été émise
 * dans cette session (verrou qui évite les demandes concurrentes du sondage répété).
 */
fun shouldAutoRequestCalendarAccess(
    status: CalendarAuthStatus,
    netTimeEnabled: Boolean,
    alreadyRequested: Boolean,
): Boolean =
    netTimeEnabled && status == CalendarAuthStatus.NOT_DETERMINED && !alreadyRequested
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/CalendarModel.kt core/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "Add tri-state calendar authorization to CalendarSource"
```

---

### Task 2: Desktop source maps the helper's real value

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/CalendarAuthStatusMappingTest.kt` (create)

**Interfaces:**
- Consumes: `CalendarAuthStatus` (Task 1).
- Produces: `internal fun parseCalendarAuthStatus(reply: String?): CalendarAuthStatus` (top-level in `CalendarSource.desktop.kt`); `MacEventKitCalendarSource.authorizationStatus()` override; `hasPermission()` delegates to `authorizationStatus() == GRANTED`.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/CalendarAuthStatusMappingTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarAuthStatusMappingTest {
    @Test
    fun mapsHelperReplyToAuthStatus() {
        assertEquals(CalendarAuthStatus.GRANTED, parseCalendarAuthStatus("GRANTED"))
        assertEquals(CalendarAuthStatus.NOT_DETERMINED, parseCalendarAuthStatus("NOTDETERMINED"))
        assertEquals(CalendarAuthStatus.DENIED, parseCalendarAuthStatus("DENIED"))
        assertEquals(CalendarAuthStatus.DENIED, parseCalendarAuthStatus(null))
        assertEquals(CalendarAuthStatus.DENIED, parseCalendarAuthStatus("garbage"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarAuthStatusMappingTest"`
Expected: FAIL — `parseCalendarAuthStatus` unresolved.

- [ ] **Step 3: Add the mapping and override**

In `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt`, add a top-level function (below the `private val isMacOS` line, above `actual fun createCalendarSource`):

```kotlin
/** Traduit la réponse « PERMISSION » de l'accessoire EventKit en statut tri-état. */
internal fun parseCalendarAuthStatus(reply: String?): CalendarAuthStatus = when (reply) {
    "GRANTED" -> CalendarAuthStatus.GRANTED
    "NOTDETERMINED" -> CalendarAuthStatus.NOT_DETERMINED
    else -> CalendarAuthStatus.DENIED
}
```

In `MacEventKitCalendarSource`, replace the existing `hasPermission()` with an `authorizationStatus()` override plus a delegating `hasPermission()`:

```kotlin
override fun hasPermission(): Boolean = authorizationStatus() == CalendarAuthStatus.GRANTED

override fun authorizationStatus(): CalendarAuthStatus =
    parseCalendarAuthStatus(command("PERMISSION").firstOrNull())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarAuthStatusMappingTest"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/CalendarAuthStatusMappingTest.kt
git commit -m "Map macOS EventKit helper reply to tri-state authorization"
```

---

### Task 3: Controller carries the denied state

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state field at line ~48; `updateNetTimeData` at line ~603)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `DayViewUiState.netCalendarPermissionDenied: Boolean = false`
  - `updateNetTimeData(hasPermission: Boolean, busyIntervals: List<BusyInterval>, availableCalendars: List<CalendarInfo>, readError: Boolean = false, permissionDenied: Boolean = false)` — new trailing param.

- [ ] **Step 1: Write the failing test**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`:

```kotlin
@Test
fun updateNetTimeDataFlagsDeniedPermission() {
    val controller = DayViewController()
    controller.updateNetTimeData(
        hasPermission = false,
        busyIntervals = emptyList(),
        availableCalendars = emptyList(),
        permissionDenied = true,
    )
    assertTrue(controller.state.netCalendarPermissionDenied)

    controller.updateNetTimeData(
        hasPermission = true,
        busyIntervals = emptyList(),
        availableCalendars = emptyList(),
    )
    assertFalse(controller.state.netCalendarPermissionDenied)
}
```

Ensure `kotlin.test.assertTrue` and `kotlin.test.assertFalse` are imported (they are used elsewhere in this file — reuse).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `netCalendarPermissionDenied` / `permissionDenied` unresolved.

- [ ] **Step 3: Add the state field and parameter**

In `DayViewController.kt`, add to `DayViewUiState` right after `val netCalendarPermission: Boolean = false,` (line ~48):

```kotlin
    val netCalendarPermissionDenied: Boolean = false,
```

Change the `updateNetTimeData` signature (line ~603) to add the trailing parameter:

```kotlin
    fun updateNetTimeData(
        hasPermission: Boolean,
        busyIntervals: List<BusyInterval>,
        availableCalendars: List<CalendarInfo>,
        readError: Boolean = false,
        permissionDenied: Boolean = false,
    ) {
```

In that function's `state = state.copy(...)` block, add the field alongside `netCalendarPermission = hasPermission,`:

```kotlin
            netCalendarPermissionDenied = permissionDenied,
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Track denied calendar permission in controller state"
```

---

### Task 4: Three-state Net Time settings card

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/NetTimeSettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (call site line ~147; `SettingsCategoryDetail`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt` (`SettingsScreenActions`, line ~18)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/NetTimeSettingsScreenTest.kt` (create)

**Interfaces:**
- Consumes: `state.netCalendarPermission`, `state.netCalendarPermissionDenied` (Task 3).
- Produces:
  - `NetTimeSettingsScreen(..., hasPermission: Boolean, permissionDenied: Boolean, onSettingsChange, onRequestPermission, onOpenCalendarSettings: () -> Unit)`
  - `SettingsScreenActions.openCalendarSettings: () -> Unit = {}`
  - `DayViewTestTags.SettingsOpenCalendarSettings = "settingsOpenCalendarSettings"`

- [ ] **Step 1: Add the test tag**

In `DayViewTestTags.kt`, add near `SettingsNetTimeScreen`:

```kotlin
    const val SettingsOpenCalendarSettings = "settingsOpenCalendarSettings"
```

- [ ] **Step 2: Add the strings (both locales)**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, near the other `settings_calendar_*` entries:

```xml
    <string name="settings_calendar_permission_denied">Calendar access is turned off. Enable it in System Settings to use Net Time.</string>
    <string name="settings_open_calendar_settings">OPEN SYSTEM SETTINGS</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, the same keys:

```xml
    <string name="settings_calendar_permission_denied">L'accès au calendrier est désactivé. Activez-le dans les Réglages Système pour utiliser Net Time.</string>
    <string name="settings_open_calendar_settings">OUVRIR LES RÉGLAGES SYSTÈME</string>
```

- [ ] **Step 3: Write the failing Compose test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/NetTimeSettingsScreenTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NetTimeSettingsScreenTest {
    @Test
    fun deniedStateShowsOpenSettingsButton() = runComposeUiTest {
        setContent {
            DayViewTheme {
                NetTimeSettingsScreen(
                    settings = NetTimeSettings(enabled = true),
                    calendars = emptyList(),
                    hasPermission = false,
                    permissionDenied = true,
                    onSettingsChange = {},
                    onRequestPermission = {},
                    onOpenCalendarSettings = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsOpenCalendarSettings).assertExists()
    }

    @Test
    fun grantedStateShowsCalendarList() = runComposeUiTest {
        setContent {
            DayViewTheme {
                NetTimeSettingsScreen(
                    settings = NetTimeSettings(enabled = true),
                    calendars = listOf(CalendarInfo(id = "1", displayName = "Work")),
                    hasPermission = true,
                    permissionDenied = false,
                    onSettingsChange = {},
                    onRequestPermission = {},
                    onOpenCalendarSettings = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SettingsNetTimeScreen).assertExists()
        onNodeWithTag(DayViewTestTags.SettingsOpenCalendarSettings).assertDoesNotExist()
    }
}
```

Note: `DayViewTheme` is the wrapper used by the other `desktopTest` screen tests (e.g. `HistoryDayScreenTest.kt`) and provides `LocalDayViewColors`, which this screen reads — keep it.

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.NetTimeSettingsScreenTest"`
Expected: FAIL — `permissionDenied` / `onOpenCalendarSettings` params and the tag don't exist yet.

- [ ] **Step 5: Rework the screen into three states**

In `NetTimeSettingsScreen.kt`, update the signature and the permission block. New signature:

```kotlin
@Composable
internal fun NetTimeSettingsScreen(
    settings: NetTimeSettings,
    calendars: List<CalendarInfo>,
    hasPermission: Boolean,
    permissionDenied: Boolean,
    onSettingsChange: (NetTimeSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
) {
```

Replace the `if (!hasPermission) { ... } else { ... }` block (the permission card / calendar list) with:

```kotlin
            when {
                hasPermission -> {
                    SettingsPanelCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            stringResource(Res.string.settings_calendars),
                            color = colors.muted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        if (calendars.isEmpty()) {
                            Text(
                                stringResource(Res.string.settings_no_calendars),
                                color = colors.muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        } else {
                            calendars.forEachIndexed { index, calendar ->
                                if (index > 0) SettingsDivider()
                                val included = settings.includedCalendarIds.isEmpty() ||
                                    calendar.id in settings.includedCalendarIds
                                NetTimeCalendarRow(
                                    name = calendar.displayName.ifBlank { stringResource(Res.string.calendar_no_name) },
                                    checked = included,
                                    onCheckedChange = { checked ->
                                        onSettingsChange(
                                            settings.copy(
                                                includedCalendarIds = nextIncludedCalendars(
                                                    allIds = calendars.map { it.id },
                                                    current = settings.includedCalendarIds,
                                                    toggledId = calendar.id,
                                                    include = checked,
                                                ),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                permissionDenied -> {
                    SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
                        Text(
                            stringResource(Res.string.settings_calendar_permission_denied),
                            color = colors.cloud,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingsAccentButton(
                            text = stringResource(Res.string.settings_open_calendar_settings),
                            onClick = onOpenCalendarSettings,
                            modifier = Modifier.testTag(DayViewTestTags.SettingsOpenCalendarSettings),
                        )
                    }
                }
                else -> {
                    SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
                        Text(
                            stringResource(Res.string.settings_calendar_permission_prompt),
                            color = colors.cloud,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingsAccentButton(
                            text = stringResource(Res.string.settings_grant_calendar_access),
                            onClick = onRequestPermission,
                        )
                    }
                }
            }
```

`androidx.compose.ui.platform.testTag` is already imported in this file, and `SettingsAccentButton` already accepts a `modifier: Modifier = Modifier` parameter (see `SettingsComponents.kt:160`), so `modifier = Modifier.testTag(...)` works directly — no change to `SettingsAccentButton` needed.

- [ ] **Step 6: Wire the new params at the call site**

In `SettingsUiModels.kt`, add to `SettingsScreenActions` (near `requestCalendarPermission`):

```kotlin
    val openCalendarSettings: () -> Unit = {},
```

In `DayViewSettingsScreen.kt`, update the `SettingsCategory.NET_TIME` branch (line ~147):

```kotlin
        SettingsCategory.NET_TIME -> NetTimeSettingsScreen(
            settings = state.netTimeSettings,
            calendars = state.availableCalendars,
            hasPermission = state.netCalendarPermission,
            permissionDenied = state.netCalendarPermissionDenied,
            onSettingsChange = actions.changeNetTimeSettings,
            onRequestPermission = actions.requestCalendarPermission,
            onOpenCalendarSettings = actions.openCalendarSettings,
        )
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.NetTimeSettingsScreenTest"`
Expected: PASS.

- [ ] **Step 8: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/commonMain/kotlin/fr/dayview/app/NetTimeSettingsScreen.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/SettingsUiModels.kt \
  composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
  composeApp/src/commonMain/composeResources/values/strings.xml \
  composeApp/src/commonMain/composeResources/values-fr/strings.xml \
  composeApp/src/desktopTest/kotlin/fr/dayview/app/NetTimeSettingsScreenTest.kt
git commit -m "Add denied-state System Settings link to Net Time settings"
```

---

### Task 5: Auto-request in the probe and platform wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (`NetTimeProbe` ~line 42; `DayViewApp` params ~line 51; probe effect ~line 175; actions block ~line 310)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (`DayViewApp(...)` call ~line 378)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt` (`DayViewApp(...)` call ~line 79)

**Interfaces:**
- Consumes: `CalendarAuthStatus`, `shouldAutoRequestCalendarAccess` (Task 1); `CalendarSource.authorizationStatus()` (Task 2); `updateNetTimeData(..., permissionDenied)` (Task 3); `SettingsScreenActions.openCalendarSettings` (Task 4).
- Produces: `DayViewApp(..., onOpenCalendarSettings: (() -> Unit)? = null)`.

- [ ] **Step 1: Extend `NetTimeProbe` to carry status**

In `App.kt`, replace the `NetTimeProbe` data class (line ~42) with:

```kotlin
/** Result of one calendar read cycle, carried from the background probe to the controller. */
private data class NetTimeProbe(
    val permission: Boolean,
    val denied: Boolean = false,
    val requested: Boolean = false,
    val busy: List<BusyInterval> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val readError: Boolean = false,
)
```

- [ ] **Step 2: Add the `onOpenCalendarSettings` parameter**

In `DayViewApp`'s parameter list, add after `onRequestCalendarPermission` (line ~60):

```kotlin
    onOpenCalendarSettings: (() -> Unit)? = null,
```

- [ ] **Step 3: Add the auto-request latch and rewrite the probe**

Add the latch near the other calendar state (below `var calendarChangeProbe by remember { mutableIntStateOf(0) }`, ~line 129):

```kotlin
                    var calendarAccessRequested by remember { mutableStateOf(false) }
```

Replace the probe `LaunchedEffect` body (lines ~182-209, the `withContext(Dispatchers.Default) { ... }` producing `probe` and the trailing `controller.updateNetTimeData(...)`) with:

```kotlin
                        if (!state.netTimeSettings.enabled) {
                            calendarAccessRequested = false
                        }
                        val alreadyRequested = calendarAccessRequested
                        val probe = withContext(Dispatchers.Default) {
                            if (!state.netTimeSettings.enabled) {
                                NetTimeProbe(permission = false)
                            } else {
                                var status = runCatching { calendarSource.authorizationStatus() }
                                    .getOrDefault(CalendarAuthStatus.DENIED)
                                var requested = false
                                if (shouldAutoRequestCalendarAccess(status, netTimeEnabled = true, alreadyRequested)) {
                                    runCatching { calendarSource.requestPermission() }
                                    status = runCatching { calendarSource.authorizationStatus() }
                                        .getOrDefault(status)
                                    requested = true
                                }
                                if (status == CalendarAuthStatus.GRANTED) {
                                    val (start, end) = dayWindow(state.now, state.startMinutes, state.endMinutes)
                                    // Distinguish "no events" from "the read failed": a provider or
                                    // permission-revocation error must surface on Today rather than
                                    // silently reading as an empty busy layer.
                                    val intervalsResult = runCatching {
                                        calendarSource.busyIntervals(start, end, state.netTimeSettings.includedCalendarIds)
                                    }
                                    val available = runCatching { calendarSource.availableCalendars() }
                                        .getOrDefault(emptyList())
                                    NetTimeProbe(
                                        permission = true,
                                        requested = requested,
                                        busy = intervalsResult.getOrDefault(emptyList()),
                                        calendars = available,
                                        readError = intervalsResult.isFailure,
                                    )
                                } else {
                                    NetTimeProbe(
                                        permission = false,
                                        denied = status == CalendarAuthStatus.DENIED,
                                        requested = requested,
                                    )
                                }
                            }
                        }
                        if (probe.requested) calendarAccessRequested = true
                        controller.updateNetTimeData(
                            probe.permission,
                            probe.busy,
                            probe.calendars,
                            probe.readError,
                            probe.denied,
                        )
```

- [ ] **Step 4: Wire the `openCalendarSettings` action**

In the `SettingsScreenActions(...)` construction (line ~310), add:

```kotlin
                                openCalendarSettings = onOpenCalendarSettings ?: {},
```

- [ ] **Step 5: Provide the desktop hook (open System Settings)**

In `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`, in the `DayViewApp(...)` call (line ~378), add:

```kotlin
                onOpenCalendarSettings = {
                    runCatching {
                        ProcessBuilder(
                            "open",
                            "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars",
                        ).start()
                    }
                },
```

- [ ] **Step 6: Provide the Android hook (app details settings)**

In `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt`, in the `DayViewApp(...)` call (line ~79), add:

```kotlin
                onOpenCalendarSettings = {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
```

- [ ] **Step 7: Build both targets and run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests pass, no stderr.

- [ ] **Step 8: Manual verification (macOS desktop)**

Run: `./gradlew :composeApp:run`
- With calendar access already granted at the OS level: open Settings → Net Time, enable it. Expected: calendars appear with **no** "Grant calendar access" click and no OS popup.
- Toggle Net Time off and on again. Expected: still no dead-end button; calendars stay visible.
- (If a denied state is reachable on your machine) Expected: the card shows "Calendar access is turned off" with an **Open System Settings** button that opens Privacy → Calendars.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
  composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt \
  composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt
git commit -m "Auto-request macOS calendar access when Net Time is enabled"
```

---

## Notes on approach vs. spec

The spec proposed an `expect/actual openCalendarPrivacySettings()`. This plan instead uses the codebase's established **platform-hook** convention (`onOpenCalendarSettings: (() -> Unit)?` passed into `DayViewApp`, mirroring `onOpenPowerSettings`), which is the pattern already used for every other platform capability. The spec's default `authorizationStatus()` was tightened from `GRANTED/DENIED` to `GRANTED/NOT_DETERMINED` so Android's not-yet-granted state keeps its existing "Grant calendar access" button (Android never reaches the denied branch, so its wiring in Step 6 is a forward-looking safety net). Both refinements preserve the spec's intent and the "Android unchanged" constraint.
