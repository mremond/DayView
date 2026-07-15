# Toast System & Centralized Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cross-platform toast system for transient feedback (confirmations, undo, transient errors, sync success) and a central error-reporting path that catches currently-swallowed errors and logs them.

**Architecture:** `core` gains a semantic, Compose-free event bus (`AppEventBus` emitting `AppEvent.Toast(kind, arg)`); the `DayViewController` posts confirmation events and supports undo of the last removed detour/obligation. `composeApp` owns all rendering and localization: a Material `SnackbarHostState` drives a fully custom `DayViewToast` renderer overlaid on `DayViewApp`, and a suspend mapping turns each `ToastKind` into a localized `ToastVisuals`. A `reportTransient` helper (built on an `expect`/`actual` `logError`) replaces silent `runCatching{}.getOrDefault` at one-off failure sites.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3 (`SnackbarHost`), kotlinx-coroutines (`SharedFlow`), Compose Resources (`getString`), kotlin.test + kotlinx-coroutines-test.

## Global Constraints

- JDK 21 for the build (`jvmToolchain(21)`); Robolectric needs 21.
- ktlint is enforced — run `./gradlew ktlintFormat` before checking; imports must stay alphabetically ordered.
- Every user-facing string lives in `composeApp/src/commonMain/composeResources/values/strings.xml` **and** `values-fr/strings.xml` with identical keys (EN/FR parity is verified).
- Compose Resources string formatting: use a single `%` for a literal percent; placeholders are `%1$s` etc.
- `core` is KMP with **jvm + macos-native** targets and has **no `jvmMain` source set**. Anything needing platform `expect`/`actual` (e.g. logging) goes in `composeApp` (which has `androidMain` + `desktopMain`), **not** `core`. `AppEventBus` uses only common `kotlinx.coroutines`, so it stays in `core`.
- UI tests: test pure screens/components, never `DayViewApp`. Never assert `stringResource` text (unresolved under `runComposeUiTest` on CI) — assert via `testTag`/seeded data. `assertExists`/`assertDoesNotExist` are members (no import).
- Full gate: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages: English, describe the change only; **no** reference to Claude/Anthropic/AI, no test-plan section, no reference to `docs/superpowers/`.
- Copy `local.properties` from the main repo into the worktree if Gradle reports "SDK location not found".

---

### Task 1: Semantic event model + bus (`core`)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/AppEvent.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/AppEventBusTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class ToastKind { DetourRemoved, ObligationRemoved, SyncSucceeded, SoundPreviewFailed, SaveFailed }`
  - `sealed interface AppEvent { data class Toast(val kind: ToastKind, val arg: String? = null) : AppEvent }`
  - `class AppEventBus { val events: Flow<AppEvent>; fun post(event: AppEvent) }`

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppEventBusTest {
    @Test
    fun postDeliversToActiveCollector() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        bus.post(AppEvent.Toast(ToastKind.SyncSucceeded))
        bus.post(AppEvent.Toast(ToastKind.DetourRemoved, arg = "Social"))
        runCurrent()

        assertEquals(
            listOf(
                AppEvent.Toast(ToastKind.SyncSucceeded),
                AppEvent.Toast(ToastKind.DetourRemoved, arg = "Social"),
            ),
            received,
        )
        job.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.AppEventBusTest"`
Expected: FAIL — unresolved references `AppEventBus`, `AppEvent`, `ToastKind`.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/commonMain/kotlin/fr/dayview/app/AppEvent.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Semantic, UI-agnostic toast triggers. The UI layer maps each to localized text. */
enum class ToastKind {
    DetourRemoved,
    ObligationRemoved,
    SyncSucceeded,
    SoundPreviewFailed,
    SaveFailed,
}

/** One-shot, app-wide events. Pure data — no Compose, no lambdas, no text. */
sealed interface AppEvent {
    data class Toast(val kind: ToastKind, val arg: String? = null) : AppEvent
}

/**
 * Single entry point for [AppEvent]s. Backed by a buffered [MutableSharedFlow] so
 * [post] never suspends and can be called from any thread or non-coroutine context.
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: Flow<AppEvent> = _events.asSharedFlow()

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.AppEventBusTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/AppEvent.kt core/src/commonTest/kotlin/fr/dayview/app/AppEventBusTest.kt
git commit -m "Add app event bus for transient toast events"
```

---

### Task 2: Controller undo + confirmation events (`core`)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (constructor at `:180-189`; `removeDetour` at `:542-546`; `removePlannedObligation` at `:577-582`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/AppEventAndUndoTest.kt`

**Interfaces:**
- Consumes: `AppEventBus`, `AppEvent.Toast`, `ToastKind` (Task 1).
- Produces:
  - `DayViewController` new constructor param `appEventBus: AppEventBus = AppEventBus()` (last param, defaulted — existing call sites unaffected).
  - `fun restoreLastRemovedDetour()`
  - `fun restoreLastRemovedObligation()`
  - `removeDetour(index)` now posts `AppEvent.Toast(ToastKind.DetourRemoved, category)`.
  - `removePlannedObligation(motif)` now posts `AppEvent.Toast(ToastKind.ObligationRemoved, motif)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppEventAndUndoTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun controller(bus: AppEventBus) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = DayPreferencesSnapshot(),
        initialNow = now,
        appEventBus = bus,
    )

    @Test
    fun removeDetourPostsToastAndRestoreReinserts() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        val c = controller(bus)
        c.addDetour("Social", durationMinutes = 15)
        assertEquals(1, c.state.detoursToday.size)

        c.removeDetour(0)
        runCurrent()
        assertTrue(c.state.detoursToday.isEmpty())
        assertEquals(AppEvent.Toast(ToastKind.DetourRemoved, "Social"), received.last())

        c.restoreLastRemovedDetour()
        assertEquals(1, c.state.detoursToday.size)
        assertEquals("Social", c.state.detoursToday.first().category)
        job.cancel()
    }

    @Test
    fun removeObligationPostsToastAndRestoreReinsertsAtIndex() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        val c = controller(bus)
        c.addPlannedObligation("Alpha")
        c.addPlannedObligation("Beta")

        c.removePlannedObligation("Alpha")
        runCurrent()
        assertEquals(listOf("Beta"), c.state.plannedObligationsToday)
        assertEquals(AppEvent.Toast(ToastKind.ObligationRemoved, "Alpha"), received.last())

        c.restoreLastRemovedObligation()
        assertEquals(listOf("Alpha", "Beta"), c.state.plannedObligationsToday)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.AppEventAndUndoTest"`
Expected: FAIL — `appEventBus` is not a constructor parameter; `restoreLastRemovedDetour` / `restoreLastRemovedObligation` unresolved.

- [ ] **Step 3a: Add the constructor parameter**

In `DayViewController.kt`, change the constructor tail (`:188`):

```kotlin
    private val onLocalWrite: () -> Unit = {},
    private val appEventBus: AppEventBus = AppEventBus(),
) {
```

- [ ] **Step 3b: Add undo state fields**

Immediately after `private var selfWritesInFlight = 0` (`:212`), add:

```kotlin
    // Last items removed via the UI, kept in memory only so a toast can offer a single
    // "undo" (never persisted — restoring re-runs the normal commit path).
    private var lastRemovedDetour: DetourEpisode? = null
    private var lastRemovedObligation: Pair<Int, String>? = null
```

- [ ] **Step 3c: Emit + capture on detour removal**

Replace `removeDetour` (`:542-546`) and add the restore function:

```kotlin
    fun removeDetour(index: Int) {
        val today = state.detoursToday
        if (index !in today.indices) return
        val removed = today[index]
        lastRemovedDetour = removed
        commitDetours(today.toMutableList().also { it.removeAt(index) })
        appEventBus.post(AppEvent.Toast(ToastKind.DetourRemoved, removed.category))
    }

    /** Reinsert the last detour removed via [removeDetour]; commitDetours re-sorts by start. */
    fun restoreLastRemovedDetour() {
        val removed = lastRemovedDetour ?: return
        lastRemovedDetour = null
        commitDetours(state.detoursToday + removed)
    }
```

- [ ] **Step 3d: Emit + capture on obligation removal**

Replace `removePlannedObligation` (`:577-582`) and add the restore function:

```kotlin
    fun removePlannedObligation(motif: String) {
        val today = state.plannedObligationsToday
        val index = today.indexOf(motif)
        if (index < 0) return
        lastRemovedObligation = index to motif
        commitPlannedObligations(
            removePlannedObligation(today, motif),
            state.plannedObligationsCompletedToday,
        )
        appEventBus.post(AppEvent.Toast(ToastKind.ObligationRemoved, motif))
    }

    /** Reinsert the last obligation removed via [removePlannedObligation] at its old index. */
    fun restoreLastRemovedObligation() {
        val (index, motif) = lastRemovedObligation ?: return
        lastRemovedObligation = null
        val active = state.plannedObligationsToday.toMutableList()
        active.add(index.coerceIn(0, active.size), motif)
        commitPlannedObligations(active, state.plannedObligationsCompletedToday)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.AppEventAndUndoTest"`
Expected: PASS.

- [ ] **Step 5: Run the core suite to confirm no regressions**

Run: `./gradlew :core:jvmTest`
Expected: PASS (existing `DayViewController` construction sites use the default `appEventBus`).

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonTest/kotlin/fr/dayview/app/AppEventAndUndoTest.kt
git commit -m "Emit toast events and support undo for removed detours and obligations"
```

---

### Task 3: Platform logging + transient error reporter (`composeApp`)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/Logging.kt`
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/Logging.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Logging.desktop.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/ErrorReporting.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/ErrorReportingTest.kt`

**Interfaces:**
- Consumes: `AppEventBus`, `AppEvent.Toast`, `ToastKind` (Task 1).
- Produces:
  - `internal expect fun logError(tag: String, message: String, throwable: Throwable?)`
  - `internal fun AppEventBus.reportTransient(area: String, error: Throwable, toast: ToastKind)`

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorReportingTest {
    @Test
    fun reportTransientLogsAndPostsToast() = runTest {
        val bus = AppEventBus()
        val received = mutableListOf<AppEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        runCurrent()

        bus.reportTransient("storage", RuntimeException("disk full"), ToastKind.SaveFailed)
        runCurrent()

        assertEquals(listOf(AppEvent.Toast(ToastKind.SaveFailed)), received)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ErrorReportingTest"`
Expected: FAIL — `reportTransient` unresolved.

- [ ] **Step 3a: Declare the expect logger**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/Logging.kt`:

```kotlin
package fr.dayview.app

/** Platform error log: android.util.Log on Android, stderr on desktop. */
internal expect fun logError(tag: String, message: String, throwable: Throwable?)
```

- [ ] **Step 3b: Android actual**

Create `composeApp/src/androidMain/kotlin/fr/dayview/app/Logging.android.kt`:

```kotlin
package fr.dayview.app

import android.util.Log

internal actual fun logError(tag: String, message: String, throwable: Throwable?) {
    Log.e("DayView/$tag", message, throwable)
}
```

- [ ] **Step 3c: Desktop actual**

Create `composeApp/src/desktopMain/kotlin/fr/dayview/app/Logging.desktop.kt`:

```kotlin
package fr.dayview.app

internal actual fun logError(tag: String, message: String, throwable: Throwable?) {
    System.err.println("DayView/$tag: $message")
    throwable?.printStackTrace()
}
```

- [ ] **Step 3d: The reporter**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/ErrorReporting.kt`:

```kotlin
package fr.dayview.app

/**
 * The single path for a one-off failure: log it for debugging and surface a transient
 * toast. Use in place of a silent `runCatching { … }.getOrDefault(…)` at action sites.
 * Persistent conditions (permission off, sync failed) keep their state/banner path.
 */
internal fun AppEventBus.reportTransient(area: String, error: Throwable, toast: ToastKind) {
    logError(area, error.message ?: error.toString(), error)
    post(AppEvent.Toast(toast))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.ErrorReportingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Logging.kt composeApp/src/androidMain/kotlin/fr/dayview/app/Logging.android.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/Logging.desktop.kt composeApp/src/commonMain/kotlin/fr/dayview/app/ErrorReporting.kt composeApp/src/commonTest/kotlin/fr/dayview/app/ErrorReportingTest.kt
git commit -m "Add platform logging and a transient error reporter"
```

---

### Task 4: Toast visuals + custom renderer (`composeApp`)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewToast.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add near `CalendarNotice`, `:17`)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DayViewToastTest.kt`

**Interfaces:**
- Consumes: `LocalDayViewColors` / `DayViewColors` (`colors.panel`, `colors.cloud`, `colors.mint`, `colors.red`, `colors.muted`).
- Produces:
  - `enum class ToastSeverity { Success, Error, Info }`
  - `class ToastVisuals(message, severity, actionLabelText, onAction, duration) : SnackbarVisuals`
  - `@Composable fun DayViewToast(visuals: ToastVisuals, onAction: () -> Unit, modifier: Modifier = Modifier)`
  - `DayViewTestTags.Toast = "toast"`, `DayViewTestTags.ToastAction = "toastAction"`

- [ ] **Step 1: Add the test tags**

In `DayViewTestTags.kt`, after `const val SyncNotice = "todaySyncNotice"` (`:18`):

```kotlin
    const val Toast = "toast"
    const val ToastAction = "toastAction"
```

- [ ] **Step 2: Write the failing test**

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DayViewToastTest {
    @Test
    fun rendersMessageAndActionInvokesCallback() = runComposeUiTest {
        var actioned = false
        setContent {
            DayViewTheme {
                DayViewToast(
                    visuals = ToastVisuals(
                        message = "Detour removed",
                        severity = ToastSeverity.Success,
                        actionLabelText = "UNDO",
                    ),
                    onAction = { actioned = true },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).performClick()
        assertTrue(actioned)
    }

    @Test
    fun rendersWithoutActionWhenNoActionLabel() = runComposeUiTest {
        setContent {
            DayViewTheme {
                DayViewToast(
                    visuals = ToastVisuals(message = "Up to date", severity = ToastSeverity.Success),
                    onAction = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
        onNodeWithTag(DayViewTestTags.ToastAction).assertDoesNotExist()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewToastTest"`
Expected: FAIL — `DayViewToast`, `ToastVisuals`, `ToastSeverity` unresolved.

- [ ] **Step 4: Write the implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewToast.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

enum class ToastSeverity { Success, Error, Info }

/**
 * Carries our severity + resolved text through Material's Snackbar queue. Built by
 * [toastVisualsFor] (Task 5) and handed to `SnackbarHostState.showSnackbar`.
 */
class ToastVisuals(
    override val message: String,
    val severity: ToastSeverity,
    val actionLabelText: String? = null,
    val onAction: (() -> Unit)? = null,
    override val duration: SnackbarDuration =
        if (actionLabelText != null) SnackbarDuration.Long else SnackbarDuration.Short,
) : SnackbarVisuals {
    override val actionLabel: String? get() = actionLabelText
    override val withDismissAction: Boolean get() = false
}

/** On-brand transient toast, matching the TodayNotices banner language. */
@Composable
fun DayViewToast(
    visuals: ToastVisuals,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val accent: Color = when (visuals.severity) {
        ToastSeverity.Success -> colors.mint
        ToastSeverity.Error -> colors.red
        ToastSeverity.Info -> colors.cloud
    }
    Row(
        modifier = modifier
            .testTag(DayViewTestTags.Toast)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = visuals.message
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        BasicText(
            text = visuals.message,
            style = TextStyle(color = colors.cloud, fontSize = 13.sp),
            modifier = Modifier.weight(1f),
        )
        val label = visuals.actionLabelText
        if (label != null) {
            BasicText(
                text = label,
                style = TextStyle(color = colors.mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                modifier = Modifier
                    .testTag(DayViewTestTags.ToastAction)
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onAction)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
```

Note: `testTag` is `androidx.compose.ui.platform.testTag`; add the import if the IDE doesn't. Match the existing import style in `DayViewTodayScreen.kt`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewToastTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewToast.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/DayViewToastTest.kt
git commit -m "Add custom toast renderer and visuals"
```

---

### Task 5: Localized mapping + toast host (`composeApp`)

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` (add after `notice_review_action`, `:33`)
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml` (mirror)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewToast.kt` (append the mapping + host)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DayViewToastHostTest.kt`

**Interfaces:**
- Consumes: `AppEvent.Toast`, `ToastKind` (Task 1); `ToastVisuals`, `ToastSeverity`, `DayViewToast` (Task 4).
- Produces:
  - `suspend fun toastVisualsFor(event: AppEvent.Toast, onUndoDetour: () -> Unit, onUndoObligation: () -> Unit): ToastVisuals`
  - `@Composable fun DayViewToastHost(hostState: SnackbarHostState, modifier: Modifier = Modifier)`

- [ ] **Step 1: Add EN strings**

In `values/strings.xml`, after `<string name="notice_review_action">Open settings</string>` (`:33`):

```xml

    <!-- Toasts (transient feedback) -->
    <string name="toast_detour_removed">Detour removed · %1$s</string>
    <string name="toast_obligation_removed">Obligation removed · %1$s</string>
    <string name="toast_synced">Up to date.</string>
    <string name="toast_sound_failed">Couldn’t play the sound.</string>
    <string name="toast_save_failed">Couldn’t save your changes.</string>
    <string name="toast_undo">UNDO</string>
```

- [ ] **Step 2: Add FR strings**

In `values-fr/strings.xml`, at the matching location (after the `notice_review_action` line):

```xml

    <!-- Toasts (transient feedback) -->
    <string name="toast_detour_removed">Détour supprimé · %1$s</string>
    <string name="toast_obligation_removed">Obligation supprimée · %1$s</string>
    <string name="toast_synced">À jour.</string>
    <string name="toast_sound_failed">Lecture du son impossible.</string>
    <string name="toast_save_failed">Enregistrement impossible.</string>
    <string name="toast_undo">ANNULER</string>
```

- [ ] **Step 3: Write the failing test**

```kotlin
package fr.dayview.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DayViewToastHostTest {
    @Test
    fun hostShowsToastPushedThroughState() = runComposeUiTest {
        setContent {
            DayViewTheme {
                val host = remember { SnackbarHostState() }
                DayViewToastHost(host)
                LaunchedEffect(Unit) {
                    host.showSnackbar(
                        ToastVisuals(message = "Up to date", severity = ToastSeverity.Success),
                    )
                }
            }
        }
        onNodeWithTag(DayViewTestTags.Toast).assertExists()
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewToastHostTest"`
Expected: FAIL — `DayViewToastHost` unresolved.

- [ ] **Step 5: Append mapping + host to `DayViewToast.kt`**

Add these imports to `DayViewToast.kt`:

```kotlin
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.toast_detour_removed
import fr.dayview.app.generated.resources.toast_obligation_removed
import fr.dayview.app.generated.resources.toast_save_failed
import fr.dayview.app.generated.resources.toast_sound_failed
import fr.dayview.app.generated.resources.toast_synced
import fr.dayview.app.generated.resources.toast_undo
import org.jetbrains.compose.resources.getString
```

Append:

```kotlin
/**
 * Maps a semantic [AppEvent.Toast] to a localized [ToastVisuals]. Suspend (not @Composable)
 * so it can run inside the event-collector coroutine; uses `getString`, not `stringResource`.
 * Undo callbacks are supplied by the caller (which holds the controller).
 */
suspend fun toastVisualsFor(
    event: AppEvent.Toast,
    onUndoDetour: () -> Unit,
    onUndoObligation: () -> Unit,
): ToastVisuals = when (event.kind) {
    ToastKind.DetourRemoved -> ToastVisuals(
        message = getString(Res.string.toast_detour_removed, event.arg ?: ""),
        severity = ToastSeverity.Success,
        actionLabelText = getString(Res.string.toast_undo),
        onAction = onUndoDetour,
    )
    ToastKind.ObligationRemoved -> ToastVisuals(
        message = getString(Res.string.toast_obligation_removed, event.arg ?: ""),
        severity = ToastSeverity.Success,
        actionLabelText = getString(Res.string.toast_undo),
        onAction = onUndoObligation,
    )
    ToastKind.SyncSucceeded -> ToastVisuals(
        message = getString(Res.string.toast_synced),
        severity = ToastSeverity.Success,
    )
    ToastKind.SoundPreviewFailed -> ToastVisuals(
        message = getString(Res.string.toast_sound_failed),
        severity = ToastSeverity.Error,
    )
    ToastKind.SaveFailed -> ToastVisuals(
        message = getString(Res.string.toast_save_failed),
        severity = ToastSeverity.Error,
    )
}

/** Overlay host: Material's queue/timing, our [DayViewToast] rendering. */
@Composable
fun DayViewToastHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier) { data ->
        (data.visuals as? ToastVisuals)?.let { visuals ->
            DayViewToast(visuals = visuals, onAction = { data.performAction() })
        }
    }
}
```

- [ ] **Step 6: Run test + verify EN/FR parity**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewToastHostTest"`
Expected: PASS.

Verify parity (must print nothing):
```bash
diff <(grep -oE 'name="toast_[a-z_]+"' composeApp/src/commonMain/composeResources/values/strings.xml | sort) \
     <(grep -oE 'name="toast_[a-z_]+"' composeApp/src/commonMain/composeResources/values-fr/strings.xml | sort)
```
Expected: no output (identical key sets).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewToast.kt composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-fr/strings.xml composeApp/src/desktopTest/kotlin/fr/dayview/app/DayViewToastHostTest.kt
git commit -m "Add localized toast mapping and overlay host"
```

---

### Task 6: Wire the bus + host into the app root (`composeApp`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (controller creation `:104-114`; the `Surface`/`when(state.destination)` block starting `:98` / `:273`)

**Interfaces:**
- Consumes: `AppEventBus` (Task 1); `DayViewController(appEventBus = …)`, `restoreLastRemovedDetour`, `restoreLastRemovedObligation` (Task 2); `toastVisualsFor`, `DayViewToastHost` (Task 5).
- Produces: a live toast surface on every screen of the main window; `appEventBus` in scope for Task 7.

This task is integration wiring; it is verified by the build gate plus a manual smoke test (no new unit test — `DayViewApp` is not unit-tested per the repo convention).

- [ ] **Step 1: Create the bus and pass it to the controller**

In `App.kt`, immediately before `val controller = remember(preferences) {` (`:104`):

```kotlin
                    val appEventBus = remember { AppEventBus() }
```

Add `appEventBus = appEventBus,` as the last argument of the `DayViewController(...)` call (after `onLocalWrite = { … },`, `:112`):

```kotlin
                            onLocalWrite = { localWriteSignal.tryEmit(Unit) },
                            appEventBus = appEventBus,
                        )
```

- [ ] **Step 2: Add the host state and event collector**

After `val state by controller.stateFlow.collectAsState()` (`:115`), add:

```kotlin
                    val toastHostState = remember { SnackbarHostState() }
                    LaunchedEffect(appEventBus, toastHostState) {
                        appEventBus.events.collect { event ->
                            if (event is AppEvent.Toast) {
                                val visuals = toastVisualsFor(
                                    event,
                                    onUndoDetour = { controller.restoreLastRemovedDetour() },
                                    onUndoObligation = { controller.restoreLastRemovedObligation() },
                                )
                                if (toastHostState.showSnackbar(visuals) == SnackbarResult.ActionPerformed) {
                                    visuals.onAction?.invoke()
                                }
                            }
                        }
                    }
```

- [ ] **Step 3: Overlay the host around the screen switch**

Wrap the `when (state.destination) { … }` block (`:273` onward) in a `Box`, and add the host as a bottom overlay. The block becomes:

```kotlin
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state.destination) {
                            // … existing SETTINGS / HISTORY / TODAY branches unchanged …
                        }
                        DayViewToastHost(
                            hostState = toastHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .safeDrawingPadding()
                                .padding(16.dp),
                        )
                    }
```

- [ ] **Step 4: Add imports**

Add to `App.kt` (keep alphabetical order):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.Alignment
```

- [ ] **Step 5: Build and run the gate**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual smoke test (desktop)**

Run: `./gradlew :composeApp:run`
Then: add a detour, open the detour list, remove it → a toast "Detour removed · <category>" appears at the bottom with an **UNDO** action; click UNDO → the detour reappears. Confirm the toast auto-dismisses after a few seconds when not actioned.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Show toasts at the app root and wire undo actions"
```

---

### Task 7: Route swallowed errors + manual-sync success (`composeApp`)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (sound preview `:291` and `:317-319`; keystore I/O launches `:335`, `:350`, `:357`, `:367-370`; calendar read effect around the existing `getOrDefault(emptyList())`; manual `syncNow` action)

**Interfaces:**
- Consumes: `appEventBus` (Task 6); `reportTransient`, `logError` (Task 3); `AppEvent.Toast`, `ToastKind` (Task 1); `SyncStatus` (existing).
- Produces: no new API — replaces silent failures with logged toasts and adds a manual-sync success toast.

Integration task; verified by the gate plus manual smoke tests.

- [ ] **Step 1: Guard the sound preview**

Both play sites currently call `soundPlayer.play(...)` unguarded. Wrap the Settings **preview** call (`:317-319`):

```kotlin
                                previewSound = { cue ->
                                    runCatching {
                                        soundPlayer.play(cue, controller.state.soundSettings.volumePercent / 100f)
                                    }.onFailure {
                                        appEventBus.reportTransient("sound", it, ToastKind.SoundPreviewFailed)
                                    }
                                },
```

Leave the scheduled-cue playback at `:291` unchanged (it is not a user action; wrapping it in `runCatching { … }` with only `logError("sound", …)` on failure is acceptable but optional — do NOT toast scheduled playback).

- [ ] **Step 2: Guard keystore writes**

Each `scope.launch(Dispatchers.IO) { secureKeyStore?.storeConfig(cfg) }` / `storeKey(key)` / the `clear()`+`reset()` block currently runs unguarded. Wrap each keystore write body. Example for `storeConfig` (`:335`):

```kotlin
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { secureKeyStore?.storeConfig(cfg) }
                                            .onFailure { appEventBus.reportTransient("storage", it, ToastKind.SaveFailed) }
                                    }
```

Apply the same wrapping to the `storeKey(key)` launches (`:350`, `:357`) and to the keystore write inside the clear block (`:367-370`), each with `area = "storage"` and `ToastKind.SaveFailed`.

- [ ] **Step 3: Log the swallowed calendar read**

Find the calendar read effect where the busy intervals are read with `runCatching { calendarSource.busyIntervals(...) }.getOrDefault(emptyList())` (persistent → banner via `netCalendarError`, do NOT toast). Add logging on failure by replacing that expression with a form that logs:

```kotlin
                                    val intervals = runCatching {
                                        calendarSource.busyIntervals(start, end, state.netTimeSettings.includedCalendarIds)
                                    }.onFailure { logError("calendar", "busyIntervals read failed", it) }
                                        .getOrDefault(emptyList())
```

(If a `NetTimeProbe`/`readError` structure from the earlier notices work is present, keep its existing behavior and only add the `logError` call.)

- [ ] **Step 4: Toast on successful manual sync**

The Settings action wires `syncNow = { launchSync() }`. Replace it with a manual variant that toasts on success (automatic/startup syncs stay silent). Change the `syncNow` action to:

```kotlin
                                syncNow = {
                                    scope.launch(Dispatchers.IO) {
                                        syncCoordinator?.syncNow()
                                        if (syncCoordinator?.status?.value == SyncStatus.Ok) {
                                            appEventBus.post(AppEvent.Toast(ToastKind.SyncSucceeded))
                                        }
                                    }
                                },
```

(Failures remain covered by the persistent sync banner; do not toast them.)

- [ ] **Step 5: Build and run the gate**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual smoke test (desktop)**

Run: `./gradlew :composeApp:run`
- Settings → Sounds → PREVIEW a cue with the audio device unavailable (or temporarily rename the sound asset) → an error toast "Couldn’t play the sound." appears; the app does not crash.
- Settings → Sync → SYNC NOW while configured and reachable → a "À jour." / "Up to date." success toast appears.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Surface sound, storage and sync outcomes through toasts and logs"
```

---

## Final verification

- [ ] Run the full gate: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest` → BUILD SUCCESSFUL.
- [ ] Confirm EN/FR parity across all new keys (the `diff` from Task 5, Step 6 prints nothing).
- [ ] Manual: detour remove → toast + working UNDO; manual sync → success toast; sound preview failure → error toast.

## Self-review notes (coverage)

- Toast roles — confirmations (`DetourRemoved`/`ObligationRemoved`), undo (Task 2 + Task 6 action wiring), transient errors (`SoundPreviewFailed`/`SaveFailed`, Task 7), sync success (`SyncSucceeded`, Task 7): covered.
- Error handling — central path (`reportTransient`, Task 3), catch swallowed sites (sound/storage/calendar/sync, Task 7), transient-vs-persistent split (calendar/sync stay banners + log, Task 7), logging (`logError` expect/actual, Task 3): covered.
- Rendering/a11y/placement (custom `DayViewToast`, `liveRegion`, bottom overlay with `safeDrawingPadding`, Material queue): Tasks 4–6.
- Out of scope (stacking, history, multi-undo, replacing banners): honored.
