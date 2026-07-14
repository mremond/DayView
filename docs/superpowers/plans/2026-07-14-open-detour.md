# Open Detour (unknown-duration detour) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user start a detour whose duration is unknown — a count-up stopwatch, ended by an explicit stop, that becomes an ordinary `DetourEpisode` on stop.

**Architecture:** Mirror the proven `pomodoroEnd` running-session pattern with a new `openDetourStart: Instant?` (plus held `category`/`description`) on `DayViewUiState` and `DayPreferencesSnapshot`. Open detour and focus are mutually exclusive. On stop, elapsed minutes are handed to the existing `addDetour` path, so all detour storage/ring/history reuse is unchanged. State is persisted (DataStore) and propagated over sync exactly like `pomodoroEnd`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, AndroidX DataStore, kotlinx.serialization (sync).

## Global Constraints

- ktlint is enforced. Run `./gradlew ktlintCheck` (or `ktlintFormat`) before every commit.
- Full gate before finishing: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Compose UI tests run under `runComposeUiTest` (desktopTest): NEVER assert `stringResource` text — assert via test tags or controller state.
- Commit messages describe the change only — no reference to Claude/Anthropic/AI, no test-plan or verification section, no reference to `docs/superpowers/`.
- macOS **native** SwiftUI surface (`macos/`, `TodaySnapshot.kt`, `DayViewSession.kt`) is OUT OF SCOPE — this feature ships on the Compose surface (Android + desktop/Linux) only.
- Detour category/description are sanitized by existing `sanitizeDetourCategory` / `sanitizeDetourDescription`.

---

### Task 1: Core state, controller actions, elapsed formatter

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt` (add `formatElapsedClock`, refactor `formatBreakClock`)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (3 snapshot fields)
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (3 UiState fields + computed getters; `startOpenDetour`/`stopOpenDetour`; guard `startPomodoro`; thread `toSnapshot`/`coerced`/`toUiState`/`withPersisted`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt` (create)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PomodoroTest.kt` (add `formatElapsedClock` case)

**Interfaces:**
- Produces (consumed by all later tasks):
  - `DayViewUiState.openDetourStart: Instant?`, `.openDetourCategory: String`, `.openDetourDescription: String`
  - `DayViewUiState.openDetourRunning: Boolean` (== `openDetourStart != null`)
  - `DayViewUiState.openDetourElapsed: Duration`
  - `DayPreferencesSnapshot.openDetourStart/openDetourCategory/openDetourDescription`
  - `DayViewController.startOpenDetour(category: String, description: String = "")`
  - `DayViewController.stopOpenDetour()`
  - `fun formatElapsedClock(elapsed: Duration): String`
- Consumes: existing `sanitizeDetourCategory`, `sanitizeDetourDescription`, `addDetour(category, durationMinutes, description)`.

**Exclusivity invariant (decisive form):** `startOpenDetour` is refused whenever `pomodoroEnd != null` (a focus session is running OR on break — the user must close the break first). `startPomodoro` is refused whenever `openDetourStart != null`. This keeps the focus-panel slot unambiguous: at most one running clock.

- [ ] **Step 1: Write the failing core tests**

Create `core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OpenDetourTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun controller(snapshot: DayPreferencesSnapshot) = DayViewController(
        DefaultDayPreferences,
        CoroutineScope(Dispatchers.Unconfined),
        initialSnapshot = snapshot,
        initialNow = now,
    )

    @Test
    fun startBeginsRunningSessionWithSanitizedCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour("  Café\nen bas ", "discussion")
        assertEquals(now, c.state.openDetourStart)
        assertTrue(c.state.openDetourRunning)
        assertEquals("Café en bas", c.state.openDetourCategory)
        assertEquals("discussion", c.state.openDetourDescription)
    }

    @Test
    fun startIgnoresBlankCategory() {
        val c = controller(DayPreferencesSnapshot())
        c.startOpenDetour("   ", "x")
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun startRefusedWhileFocusRunning() {
        val c = controller(DayPreferencesSnapshot(pomodoroEnd = now + 25.minutes))
        c.startOpenDetour("Réunion", "")
        assertNull(c.state.openDetourStart)
    }

    @Test
    fun startPomodoroRefusedWhileOpenDetourRunning() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now, focusIntention = "Écrire"))
        c.startPomodoro()
        assertFalse(c.state.focusIsActive)
    }

    @Test
    fun stopRecordsEpisodeAndClears() {
        val c = controller(
            DayPreferencesSnapshot(openDetourStart = now - 15.minutes, openDetourCategory = "Réunion"),
        )
        c.stopOpenDetour()
        assertNull(c.state.openDetourStart)
        assertEquals("", c.state.openDetourCategory)
        val episode = c.state.detoursToday.single()
        assertEquals("Réunion", episode.category)
        assertEquals(now, episode.end)
        assertEquals(15.minutes, episode.end - episode.start)
    }

    @Test
    fun elapsedCountsUpFromStart() {
        val c = controller(DayPreferencesSnapshot(openDetourStart = now - 3.minutes))
        assertEquals(3.minutes, c.state.openDetourElapsed)
    }
}
```

Add to `core/src/commonTest/kotlin/fr/dayview/app/PomodoroTest.kt` (inside the existing `class PomodoroTest`, keep existing imports; add `import kotlin.time.Duration.Companion.seconds` if absent):

```kotlin
    @Test
    fun formatElapsedClockPadsMinutesAndSeconds() {
        assertEquals("01:05", formatElapsedClock(65.seconds))
        assertEquals("00:00", formatElapsedClock(kotlin.time.Duration.ZERO))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OpenDetourTest" --tests "fr.dayview.app.PomodoroTest"`
Expected: FAIL — unresolved references `openDetourStart`, `startOpenDetour`, `stopOpenDetour`, `openDetourRunning`, `openDetourElapsed`, `formatElapsedClock`.

- [ ] **Step 3: Add the elapsed formatter in `Pomodoro.kt`**

Replace the existing `formatBreakClock` (lines 63–68) with:

```kotlin
fun formatElapsedClock(elapsed: Duration): String {
    val elapsedMinutes = elapsed.inWholeMinutes
    val elapsedSeconds = elapsed.inWholeSeconds % 60L
    return "${elapsedMinutes.toString().padStart(2, '0')}:" +
        elapsedSeconds.toString().padStart(2, '0')
}

fun formatBreakClock(progress: PomodoroProgress): String = formatElapsedClock(progress.breakElapsed)
```

- [ ] **Step 4: Add the three snapshot fields in `DayPreferences.kt`**

In `DayPreferencesSnapshot` (after line 18, `focusIntention`), add:

```kotlin
    val openDetourStart: Instant? = null,
    val openDetourCategory: String = "",
    val openDetourDescription: String = "",
```

- [ ] **Step 5: Add UiState fields + computed getters in `DayViewController.kt`**

In `DayViewUiState` (after line 42, `focusIntention: String,`), add:

```kotlin
    val openDetourStart: Instant? = null,
    val openDetourCategory: String = "",
    val openDetourDescription: String = "",
```

In `DayViewUiState`, after the `focusIsActive` getter (line 76), add:

```kotlin
    val openDetourRunning: Boolean
        get() = openDetourStart != null

    val openDetourElapsed: Duration
        get() = openDetourStart?.let { (now - it).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
```

- [ ] **Step 6: Add the controller actions + guard `startPomodoro`**

In `DayViewController`, at the top of `startPomodoro()` (line 388, before the blank-intention check), add:

```kotlin
        if (state.openDetourStart != null) return
```

Immediately after `stopPomodoro()` (after line 397), add:

```kotlin
    fun startOpenDetour(
        category: String,
        description: String = "",
    ) {
        // Mutually exclusive with focus: a running or on-break pomodoro owns the panel slot.
        if (state.pomodoroEnd != null) return
        val clean = sanitizeDetourCategory(category)
        if (clean.isEmpty()) return
        state = state.copy(
            openDetourStart = state.now,
            openDetourCategory = clean,
            openDetourDescription = sanitizeDetourDescription(description),
        )
        persistState()
    }

    fun stopOpenDetour() {
        val start = state.openDetourStart ?: return
        val minutes = (state.now - start).inWholeMinutes.toInt().coerceAtLeast(1)
        val category = state.openDetourCategory
        val description = state.openDetourDescription
        // Clear the open state first (no persist yet); addDetour's own persist then writes the
        // whole snapshot — cleared open-detour fields plus the freshly appended episode — atomically.
        state = state.copy(openDetourStart = null, openDetourCategory = "", openDetourDescription = "")
        addDetour(category, minutes, description)
    }
```

- [ ] **Step 7: Thread the fields through the snapshot mappers**

In `DayViewUiState.toSnapshot()` (after `focusIntention = focusIntention,`, line 603), add:

```kotlin
    openDetourStart = openDetourStart,
    openDetourCategory = openDetourCategory,
    openDetourDescription = openDetourDescription,
```

In `DayPreferencesSnapshot.coerced()` (inside the `copy(...)`, after `focusIntention = focusIntention.take(100),`, line 629), add:

```kotlin
    openDetourCategory = sanitizeDetourCategory(openDetourCategory),
    openDetourDescription = sanitizeDetourDescription(openDetourDescription),
```

In `DayPreferencesSnapshot.toUiState()` (after `focusIntention = safe.focusIntention,`, line 661), add:

```kotlin
    openDetourStart = safe.openDetourStart,
    openDetourCategory = safe.openDetourCategory,
    openDetourDescription = safe.openDetourDescription,
```

In `DayViewUiState.withPersisted()` (after `focusIntention = safe.focusIntention,`, line 690), add:

```kotlin
    openDetourStart = safe.openDetourStart,
    openDetourCategory = safe.openDetourCategory,
    openDetourDescription = safe.openDetourDescription,
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OpenDetourTest" --tests "fr.dayview.app.PomodoroTest"`
Expected: PASS.

- [ ] **Step 9: Lint and commit**

Run: `./gradlew ktlintCheck`
Then:

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/Pomodoro.kt \
        core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        core/src/commonTest/kotlin/fr/dayview/app/OpenDetourTest.kt \
        core/src/commonTest/kotlin/fr/dayview/app/PomodoroTest.kt
git commit -m "Add open-ended detour state and controller actions"
```

---

### Task 2: DataStore persistence keys

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (keys, `persist`, `toSnapshot`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt` (extend the round-trip case)

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.openDetourStart/openDetourCategory/openDetourDescription` (Task 1).
- Produces: DataStore keys `detour_open_start`, `detour_open_category`, `detour_open_description`.

- [ ] **Step 1: Extend the failing round-trip test**

In `core/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt`, in `fallbackPreferencesRoundTripPersistedSnapshots`, add these fields to the `written` snapshot (after `focusIntention = "Terminer le test",`):

```kotlin
            openDetourStart = Instant.fromEpochMilliseconds(456L),
            openDetourCategory = "Réunion",
            openDetourDescription = "point équipe",
```

> Note: `DefaultDayPreferences` (in-memory) already round-trips any snapshot field, so this test also guards the `DayPreferencesSnapshot` data class. The DataStore-backed `DayPreferencesStore` has no unit harness in `commonTest` (DataStore needs a platform), matching how `pomodoro_end` is covered today; its key wiring below is guarded by the build plus mapper symmetry.

- [ ] **Step 2: Run the test to verify it still passes (data class carries the fields)**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: PASS (fields already exist on the data class from Task 1). This step confirms the snapshot round-trips; proceed to wire DataStore.

- [ ] **Step 3: Add the DataStore keys**

In `DayPreferenceKeys` (after `const val POMODORO_END = "pomodoro_end"`, line 29), add:

```kotlin
    const val OPEN_DETOUR_START = "detour_open_start"
    const val OPEN_DETOUR_CATEGORY = "detour_open_category"
    const val OPEN_DETOUR_DESCRIPTION = "detour_open_description"
```

After the `pomodoroEndKey` declaration (line 67), add:

```kotlin
private val openDetourStartKey = longPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_START)
private val openDetourCategoryKey = stringPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_CATEGORY)
private val openDetourDescriptionKey = stringPreferencesKey(DayPreferenceKeys.OPEN_DETOUR_DESCRIPTION)
```

- [ ] **Step 4: Write the keys in `persist`**

In `persist` (after `prefs[pomodoroEndKey] = ...`, line 108), add:

```kotlin
            prefs[openDetourStartKey] = snapshot.openDetourStart?.toEpochMilliseconds() ?: DayPreferenceKeys.NO_DEADLINE
            prefs[openDetourCategoryKey] = snapshot.openDetourCategory
            prefs[openDetourDescriptionKey] = snapshot.openDetourDescription
```

- [ ] **Step 5: Read the keys in `toSnapshot`**

In `Preferences.toSnapshot()` (after the `pomodoroEnd = ...` block, line 156), add:

```kotlin
        openDetourStart = this[openDetourStartKey]
            ?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE }
            ?.let(Instant::fromEpochMilliseconds),
        openDetourCategory = this[openDetourCategoryKey] ?: defaults.openDetourCategory,
        openDetourDescription = this[openDetourDescriptionKey] ?: defaults.openDetourDescription,
```

- [ ] **Step 6: Run the test + lint**

Run: `./gradlew ktlintCheck :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: PASS, no lint errors.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt
git commit -m "Persist open detour state in DataStore"
```

---

### Task 3: Sync propagation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt` (DTO + document field)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt` (build/apply)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt` (merge)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncMapperOpenDetourTest.kt` (create)

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.openDetourStart/openDetourCategory/openDetourDescription`.
- Produces: `SyncDocument.openDetour: Versioned<OpenDetourDto>`, `OpenDetourDto(start: Long, category: String, description: String)`.

> The new document field MUST carry a default so decoding a document from a peer that predates it does not throw (like `plannedObligationsCompleted`).

- [ ] **Step 1: Write the failing round-trip test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncMapperOpenDetourTest.kt`:

```kotlin
package fr.dayview.app

import fr.dayview.app.sync.applyDocument
import fr.dayview.app.sync.buildDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SyncMapperOpenDetourTest {
    @Test
    fun openDetourSurvivesBuildAndApplyRoundTrip() {
        val snapshot = DayPreferencesSnapshot(
            openDetourStart = Instant.fromEpochMilliseconds(789L),
            openDetourCategory = "Réunion",
            openDetourDescription = "point équipe",
        )
        val document = buildDocument(snapshot, base = null, deviceId = "device-a", now = 1_000L)
        val restored = applyDocument(document, DayPreferencesSnapshot())
        assertEquals(Instant.fromEpochMilliseconds(789L), restored.openDetourStart)
        assertEquals("Réunion", restored.openDetourCategory)
        assertEquals("point équipe", restored.openDetourDescription)
    }

    @Test
    fun nullOpenDetourStartRoundTripsAsNull() {
        val document = buildDocument(DayPreferencesSnapshot(), base = null, deviceId = "device-a", now = 1_000L)
        val restored = applyDocument(document, DayPreferencesSnapshot())
        assertNull(restored.openDetourStart)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncMapperOpenDetourTest"`
Expected: FAIL — `buildDocument`/`applyDocument` do not set open-detour fields; `SyncDocument.openDetour` does not exist.

- [ ] **Step 3: Add the DTO and document field in `SyncDocument.kt`**

After `@Serializable data class PomodoroDto(val minutes: Int, val end: Long)` (line 22), add:

```kotlin
@Serializable data class OpenDetourDto(val start: Long, val category: String, val description: String)
```

In `SyncDocument` (after `val pomodoro: Versioned<PomodoroDto>,`, line 42), add — with a default so older peers decode:

```kotlin
    val openDetour: Versioned<OpenDetourDto> = Versioned(OpenDetourDto(-1L, "", ""), Stamp(0L, "")),
```

Add the import at the top of the file if not already present: `import fr.dayview.app.sync.Stamp` is same-package, so no import needed; `Stamp` is already in this package.

- [ ] **Step 4: Build and apply the field in `SyncMapper.kt`**

In `buildDocument` (after the `pomodoro = restamp(...)` line, line 39), add:

```kotlin
        openDetour = restamp(
            OpenDetourDto(snapshot.openDetourStart.toMillisOrAbsent(), snapshot.openDetourCategory, snapshot.openDetourDescription),
            base?.openDetour,
            now,
            deviceId,
        ),
```

In `applyDocument`'s `local.copy(...)` (after `pomodoroEnd = document.pomodoro.value.end.toInstantOrNull(),`, line 148), add:

```kotlin
    openDetourStart = document.openDetour.value.start.toInstantOrNull(),
    openDetourCategory = document.openDetour.value.category,
    openDetourDescription = document.openDetour.value.description,
```

- [ ] **Step 5: Merge the field in `SyncMerge.kt`**

In `SyncDocument.merge`'s `copy(...)` (after `pomodoro = pick(pomodoro, remote.pomodoro),`, line 11), add:

```kotlin
        openDetour = pick(openDetour, remote.openDetour),
```

- [ ] **Step 6: Run the test + lint**

Run: `./gradlew ktlintCheck :composeApp:desktopTest --tests "fr.dayview.app.SyncMapperOpenDetourTest"`
Expected: PASS, no lint errors.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncMapperOpenDetourTest.kt
git commit -m "Propagate open detour state over sync"
```

---

### Task 4: Wire actions through the UI action layer

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`DayViewScreenActions`: add two callbacks)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (construct the two callbacks)
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt` (`noopDayViewActions`, `controllerDayViewActions`)

**Interfaces:**
- Produces: `DayViewScreenActions.startOpenDetour: (String, String) -> Unit`, `DayViewScreenActions.stopOpenDetour: () -> Unit`.
- Consumes: `DayViewController.startOpenDetour`, `stopOpenDetour` (Task 1).

> This task only threads the callbacks; there is no new user-visible behavior yet, so its "test" is that the existing suite still compiles and passes.

- [ ] **Step 1: Add the callbacks to `DayViewScreenActions`**

In `DayViewTodayScreen.kt`, in `data class DayViewScreenActions` (after `val addDetourEpisode: (DetourEpisode) -> Unit,`, line 199), add:

```kotlin
    val startOpenDetour: (String, String) -> Unit,
    val stopOpenDetour: () -> Unit,
```

- [ ] **Step 2: Construct them in `App.kt`**

In `App.kt`, in the `DayViewScreenActions(...)` constructor (after `addDetourEpisode = { controller.addDetourEpisode(it) },`, line 396), add:

```kotlin
                                startOpenDetour = { category, description -> controller.startOpenDetour(category, description) },
                                stopOpenDetour = { controller.stopOpenDetour() },
```

- [ ] **Step 3: Update the test-support factories in `UiTestSupport.kt`**

In `noopDayViewActions`'s `DayViewScreenActions(...)` (after `addDetourEpisode = {},`), add:

```kotlin
    startOpenDetour = { _, _ -> },
    stopOpenDetour = {},
```

In `controllerDayViewActions` (line 156), add to its `DayViewScreenActions(...)` the two callbacks wired to the controller (place them alongside the other detour callbacks):

```kotlin
    startOpenDetour = { category, description -> controller.startOpenDetour(category, description) },
    stopOpenDetour = { controller.stopOpenDetour() },
```

- [ ] **Step 4: Verify the suite compiles and passes**

Run: `./gradlew ktlintCheck :composeApp:desktopTest`
Expected: PASS (no behavior change; all call sites of `DayViewScreenActions` now supply the new fields).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/UiTestSupport.kt
git commit -m "Thread open detour actions through the UI action layer"
```

---

### Task 5: "Démarrer" button in the detour capture dialog

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add `DetourStartOpen`)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` (+ `values-fr/strings.xml`) (`detour_start_open_button`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (`DetourCaptureDialog`/`DetourCaptureContent`: optional `onStart`, third button)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (pass `onStart` to the capture dialog only)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt` (add a "Démarrer" case)

**Interfaces:**
- Produces: `DetourCaptureContent(..., onStart: ((String, String) -> Unit)? = null)` — renders the START button only when non-null.
- Consumes: `DayViewScreenActions.startOpenDetour` (Task 4), `DayViewTestTags.DetourStartOpen`.

- [ ] **Step 1: Add the test tag and strings**

In `DayViewTestTags.kt` (after `const val DetourConfirm = "detourConfirm"`, line 42), add:

```kotlin
    const val DetourStartOpen = "detourStartOpen"
```

In `composeApp/src/commonMain/composeResources/values/strings.xml` (near `detour_confirm_button`, line 261), add:

```xml
    <string name="detour_start_open_button">START</string>
```

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml` (near `detour_confirm_button`, line 260), add:

```xml
    <string name="detour_start_open_button">DÉMARRER</string>
```

- [ ] **Step 2: Write the failing UI test**

In `composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt`, add a test that types a category and clicks START, asserting the `onStart` callback fires. Mirror the file's existing `DetourCaptureContent(...)` call shape (see its other tests); pass a non-null `onStart`:

```kotlin
    @Test
    fun startButtonFiresOnStartWithCategoryAndDescription() = runComposeUiTest {
        var started: Pair<String, String>? = null
        setContent {
            DetourCaptureContent(
                recentCategories = emptyList(),
                now = midWindowNow(),
                onConfirm = { _, _, _, _ -> },
                onForget = {},
                onDismiss = {},
                onStart = { category, description -> started = category to description },
            )
        }
        onNodeWithTag(DayViewTestTags.DetourCategoryField).performTextInput("Réunion")
        onNodeWithTag(DayViewTestTags.DetourStartOpen).performClick()
        assertEquals("Réunion" to "", started)
    }
```

The sibling tests already import `performTextInput`, `performClick`, `onNodeWithTag`, `assertEquals`, and use the `midWindowNow()` helper — no new imports needed.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: FAIL — `onStart` is not a parameter of `DetourCaptureContent`; tag `detourStartOpen` not found.

- [ ] **Step 4: Add `onStart` to the dialog and content signatures**

In `DetoursUi.kt`, `DetourCaptureDialog` (line 225) — add the parameter and forward it:

```kotlin
internal fun DetourCaptureDialog(
    recentCategories: List<String>,
    now: Instant,
    onConfirm: (category: String, description: String, durationMinutes: Int, startMinutesOfDay: Int?) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String = "",
    initialDescription: String = "",
    onStart: ((category: String, description: String) -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        DetourCaptureContent(recentCategories, now, onConfirm, onForget, onDismiss, initialCategory, initialDescription, onStart)
    }
}
```

In `DetourCaptureContent` (line 244), add the same trailing parameter:

```kotlin
    initialCategory: String = "",
    initialDescription: String = "",
    onStart: ((category: String, description: String) -> Unit)? = null,
) {
```

- [ ] **Step 5: Render the START button**

In `DetourCaptureContent`, replace the final button `Row` (lines 389–399) with a version that inserts START between Cancel and Add when `onStart != null`:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
            if (onStart != null) {
                FocusActionButton(
                    stringResource(Res.string.detour_start_open_button),
                    colors.amber,
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.DetourStartOpen),
                    enabled = category.isNotBlank(),
                    onClick = { onStart(category, description) },
                )
            }
            FocusActionButton(
                stringResource(Res.string.detour_confirm_button),
                colors.amber,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.DetourConfirm),
                enabled = category.isNotBlank(),
                filled = true,
                onClick = { onConfirm(category, description, durationMinutes, if (startPinned) startMinutes else null) },
            )
        }
```

- [ ] **Step 6: Pass `onStart` from the capture dialog callsite only**

In `DayViewTodayScreen.kt`, in the `if (showDetourCapture)` block (line 369), add `onStart` to that `DetourCaptureDialog(...)` (after `onDismiss = { showDetourCapture = false },`, line 383):

```kotlin
                onStart = { category, description ->
                    actions.startOpenDetour(category, description)
                    showDetourCapture = false
                },
```

Leave the obligation-completion `DetourCaptureDialog` (line 387) unchanged — obligations are retroactive, so it keeps no START button.

- [ ] **Step 7: Run the test + lint**

Run: `./gradlew ktlintCheck :composeApp:desktopTest --tests "fr.dayview.app.DetourCaptureTest"`
Expected: PASS, no lint errors.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/DetourCaptureTest.kt
git commit -m "Add Start button for open detours in the capture dialog"
```

---

### Task 6: Running open-detour panel in the focus slot

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` (add `OpenDetourStop`)
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` (+ `values-fr/strings.xml`) (`open_detour_status`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (holder + `openDetourPanelState` + `OpenDetourPanel` + both call sites + `SidePanel` params)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/OpenDetourPanelTest.kt` (create)

**Interfaces:**
- Consumes: `DayViewUiState.openDetourStart/openDetourElapsed/openDetourCategory/openDetourDescription`, `DayViewScreenActions.stopOpenDetour`, `formatElapsedClock`, existing `FocusStopRoundButton`.
- Produces: `OpenDetourPanelState(elapsed: Duration, category: String, description: String)`, `DayViewUiState.openDetourPanelState: OpenDetourPanelState?`, `DayViewTestTags.OpenDetourStop`.

**UI priority in the focus slot:** open detour running → `OpenDetourPanel`; else pomodoro `IDLE` → focus entry (compact) / creation panel (side); else → `FocusPanel`.

- [ ] **Step 1: Add the test tag and strings**

In `DayViewTestTags.kt` (after the `DetourStartOpen` line from Task 5), add:

```kotlin
    const val OpenDetourStop = "openDetourStop"
```

In `values/strings.xml` (near `detour_section`, line 247), add:

```xml
    <string name="open_detour_status">IN PROGRESS</string>
```

In `values-fr/strings.xml` (near `detour_section`, line 246), add:

```xml
    <string name="open_detour_status">EN COURS</string>
```

- [ ] **Step 2: Write the failing UI test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/OpenDetourPanelTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTestApi::class)
class OpenDetourPanelTest {
    @Test
    fun runningOpenDetourShowsPanelAndStopRecordsEpisode() = runComposeUiTest {
        val now = midWindowNow()
        val snapshot = DayPreferencesSnapshot(
            openDetourStart = now - 12.minutes,
            openDetourCategory = "Réunion",
        )
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(snapshot, now) }
            controller = c
            WideDayView(state = c.state, actions = controllerDayViewActions(c))
        }
        assertTrue(controller.state.openDetourRunning)
        onNodeWithTag(DayViewTestTags.OpenDetourStop).performClick()
        assertNull(controller.state.openDetourStart)
        assertFalse(controller.state.openDetourRunning)
        assertTrue(controller.state.detoursToday.isNotEmpty())
    }
}
```

> `WideDayView`, `midWindowNow`, `seededController`, `controllerDayViewActions` are the same helpers used by `FocusFlowTest`. If `FocusFlowTest` reads `controller.state` after a click without an explicit recomposition step, follow that same pattern here (the Unconfined dispatcher applies controller state synchronously).

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OpenDetourPanelTest"`
Expected: FAIL — tag `openDetourStop` not found (no panel rendered yet).

- [ ] **Step 4: Add the holder, the state extension, and the panel composable**

In `DayViewTodayScreen.kt`, near the other internal UI holders (e.g. after `FocusReminderUiState`, line 211), add:

```kotlin
internal data class OpenDetourPanelState(
    val elapsed: Duration,
    val category: String,
    val description: String,
)

internal val DayViewUiState.openDetourPanelState: OpenDetourPanelState?
    get() = openDetourStart?.let {
        OpenDetourPanelState(openDetourElapsed, openDetourCategory, openDetourDescription)
    }
```

Add the panel composable next to `FocusPanel` (e.g. after `FocusPanel` ends, line 1776):

```kotlin
@Composable
private fun OpenDetourPanel(
    state: OpenDetourPanelState,
    onStop: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.detour_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.open_detour_status), color = colors.mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(state.category, color = colors.cloud, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
        if (state.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.description, color = colors.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatElapsedClock(state.elapsed), color = colors.cloud, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.weight(1f))
            FocusStopRoundButton(onStop, Modifier.testTag(DayViewTestTags.OpenDetourStop))
        }
    }
}
```

- [ ] **Step 5: Render the panel in the compact layout**

In `CompactTodayContent`, replace the `if (pomodoro.status == PomodoroStatus.IDLE) { ... } else { FocusPanel(...) }` block (lines 470–490) with:

```kotlin
        val openDetour = state.openDetourPanelState
        if (openDetour != null) {
            OpenDetourPanel(openDetour, actions.stopOpenDetour)
        } else if (pomodoro.status == PomodoroStatus.IDLE) {
            FocusEntryButton(
                lastClosure = state.lastFocusClosure,
                onClick = { openSheet = CompactSheet.FOCUS },
            )
        } else {
            FocusPanel(
                progress = pomodoro,
                intention = state.focusIntention,
                lastClosure = state.lastFocusClosure,
                onIntentionChange = actions.changeFocusIntention,
                showDriftReminder = reminders.showDriftReminder,
                onDismissDriftReminder = reminders.dismissDriftReminder,
                showResumeRitual = reminders.showResumeRitual,
                onDismissResumeRitual = reminders.dismissResumeRitual,
                onDurationChange = actions.changePomodoroDuration,
                onStart = actions.startPomodoro,
                onStop = actions.stopPomodoro,
                onClose = actions.closePomodoro,
            )
        }
```

- [ ] **Step 6: Render the panel in `SidePanel`**

Add two parameters to `SidePanel` (line 1498), after `onPomodoroClose: (FocusClosureOutcome) -> Unit,` (line 1511):

```kotlin
    openDetour: OpenDetourPanelState?,
    onStopOpenDetour: () -> Unit,
```

Replace the `FocusPanel(...)` call inside `SidePanel` (lines 1546–1559) with:

```kotlin
        if (openDetour != null) {
            OpenDetourPanel(openDetour, onStopOpenDetour)
        } else {
            FocusPanel(
                progress = pomodoro,
                intention = focusIntention,
                lastClosure = lastFocusClosure,
                onIntentionChange = onFocusIntentionChange,
                showDriftReminder = showFocusDriftReminder,
                onDismissDriftReminder = onDismissFocusDriftReminder,
                showResumeRitual = showFocusResumeRitual,
                onDismissResumeRitual = onDismissFocusResumeRitual,
                onDurationChange = onPomodoroDurationChange,
                onStart = onPomodoroStart,
                onStop = onPomodoroStop,
                onClose = onPomodoroClose,
            )
        }
```

At the `SidePanel(...)` call site (line 312), pass the two new arguments (place them after `onPomodoroClose = ...`, line ~325):

```kotlin
                        openDetour = state.openDetourPanelState,
                        onStopOpenDetour = actions.stopOpenDetour,
```

- [ ] **Step 7: Run the test + lint**

Run: `./gradlew ktlintCheck :composeApp:desktopTest --tests "fr.dayview.app.OpenDetourPanelTest"`
Expected: PASS, no lint errors.

- [ ] **Step 8: Full gate + commit**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS, no errors or stderr.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/OpenDetourPanelTest.kt
git commit -m "Show a running open-detour panel in the focus slot"
```

---

## Notes for the implementer

- **Android cross-process writers** (`DayViewFocusTileService`, `FocusNotification`, widget) mutate the snapshot with `.copy(pomodoroEnd = ...)`, which preserves the new open-detour fields untouched (they default and copy through). No change needed there.
- **Day rollover:** open-detour state is not day-scoped, so a detour running across midnight keeps counting; on stop, `addDetour` floors its start at the start of the local day. This is acceptable and intentional (matches quick-capture flooring).
- **No Android alarm:** an open detour has no end instant, so `onFocusAlarmChange` is never involved. Do not wire it in the `startOpenDetour`/`stopOpenDetour` action callbacks.
