# macOS Native Presence Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native macOS app's engaged-time presence survive a relaunch — persist the presence/session intervals at the JVM's cadence, and seed them back at launch so `PresenceCoordinator.restore()` genuinely reseeds the accumulators.

**Architecture:** A `PresencePersistence` seam in `:core` commonMain (the fourth use of the established provider-injection pattern, after `CalendarSource`, `FrontmostAppProvider`, `DockAttentionProvider`). `DayViewSession` calls it from the existing per-tick presence block on the JVM's cadence (structural change, else every 30 s). A `macosMain` implementation stores the intervals under macOS-only DataStore keys, outside the shared `DayPreferencesSnapshot`. `DayViewNative.create()` loads once and seeds the controller.

**Tech Stack:** Kotlin Multiplatform (`:core` commonMain / commonTest / macosMain), AndroidX DataStore Preferences (Okio storage on macOS), kotlinx-coroutines-test.

## Global Constraints

- **One DataStore per file.** `macosPreferences()` must create exactly one `PreferenceDataStoreFactory.create` for `dayview.preferences_pb` and hand that instance to both surfaces. A second instance over the same path is unsupported by DataStore and risks corrupting the file.
- **Do not touch the shared store's keys.** `focus_session_day` / `focus_session` belong to `DayPreferencesStore`; this phase neither reads them for seeding nor writes them. The session intervals use the macOS-only key `mac_focus_session`.
- **No change to `DayPreferencesSnapshot`, the shared store schema, or the sync payload.**
- **No change to the presence algorithms**, accumulator parameters, or the drift/resume detectors.
- **Save cadence matches the JVM** (`Main.kt`): write immediately on a structural change (a run opened or closed, i.e. a list's size changed), otherwise at most once every 30 s.
- **The drift/resume latches stay transient** — they are not persisted.
- Every existing call site and test of `DayViewSession` must keep compiling unchanged: the new constructor parameter is last and defaults to a no-op.
- ktlint is enforced. Before each commit run:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
- Commit messages: English, no reference to Claude/Anthropic/an AI assistant, no reference to `docs/superpowers/`, no test-plan or verification section.

## File Structure

| File | Responsibility |
|---|---|
| `core/src/commonMain/kotlin/fr/dayview/app/PresencePersistence.kt` (**create**) | The seam: `StoredPresence`, `PresencePersistence`, `NoopPresencePersistence` |
| `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (**modify**) | New constructor param; save-cadence bookkeeping in `tick()`; seed the "already on disk" baseline in `init` |
| `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (**modify**) | Fake persistence; cadence tests; restore-path test |
| `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt` (**create**) | DataStore-backed implementation + the key definitions |
| `core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt` (**modify**) | Build one DataStore, expose both surfaces via `MacosPreferences` |
| `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt` (**modify**) | Load once at launch, seed the controller, inject the persistence |
| `docs/superpowers/macos-native-parity-checklist.md` (**modify**) | Move the item from PORT to Done |

---

### Task 1: The `:core` persistence seam and save cadence

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/PresencePersistence.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `FocusPresenceInterval`, `encodeFocusPresence`/`decodeFocusPresence`, `dayKeyOf(Instant)`, `PresenceCoordinator.restore`, `DayViewController.initialFocusPresenceIntervals` / `initialFocusSessionIntervals` (already exist).
- Produces, for Task 2:
  - `data class StoredPresence(val dayKey: Long = -1L, val presence: List<FocusPresenceInterval> = emptyList(), val session: List<FocusPresenceInterval> = emptyList())`
  - `interface PresencePersistence { suspend fun load(): StoredPresence; suspend fun save(dayKey: Long, presence: List<FocusPresenceInterval>, session: List<FocusPresenceInterval>) }`
  - `object NoopPresencePersistence : PresencePersistence`
  - `DayViewSession(..., presencePersistence: PresencePersistence = NoopPresencePersistence)` — the **last** constructor parameter, after `dockAttention`.

---

- [ ] **Step 1: Write the failing tests**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`. Put the fake next to the existing `FakeFrontmostProvider` / `FakeDock` private classes, and the tests at the end of the class.

```kotlin
    private class FakePresencePersistence(
        private val stored: StoredPresence = StoredPresence(),
    ) : PresencePersistence {
        data class Save(
            val dayKey: Long,
            val presence: List<FocusPresenceInterval>,
            val session: List<FocusPresenceInterval>,
        )

        val saves = mutableListOf<Save>()

        override suspend fun load(): StoredPresence = stored

        override suspend fun save(
            dayKey: Long,
            presence: List<FocusPresenceInterval>,
            session: List<FocusPresenceInterval>,
        ) {
            saves.add(Save(dayKey, presence, session))
        }
    }

    @Test
    fun presenceSavesOnStructuralChangeThenThrottlesToThirtySeconds() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val persistence = FakePresencePersistence()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            presencePersistence = persistence,
        )
        session.startFocus("Ship it")
        runCurrent()
        assertEquals(0, persistence.saves.size, "nothing accrued yet, nothing to write")

        // The lenient session accumulator commits its first run once it reaches its
        // 60s minimum: the list goes from 0 to 1 entry — a structural change.
        repeat(61) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(1, persistence.saves.size, "a run opening writes immediately")
        assertEquals(dayKeyOf(start), persistence.saves.last().dayKey)
        assertEquals(1, persistence.saves.last().session.size)

        // Extending the same run only moves its end: throttled for 30s.
        repeat(29) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(1, persistence.saves.size, "extensions inside the 30s window are not written")

        clock += 1.seconds
        session.tick()
        runCurrent()
        assertEquals(2, persistence.saves.size, "30s after the last write, the extension is flushed")
    }

    @Test
    fun presenceIsNotSavedWhileIdle() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val persistence = FakePresencePersistence()
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            presencePersistence = persistence,
        )
        repeat(120) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()
        assertEquals(0, persistence.saves.size, "no focus, no presence, no writes")
    }

    @Test
    fun seededIntervalsRenderFromTheFirstSnapshot() = runTest {
        // Anchor to the local day so the seeded interval is inside the day window in
        // every timezone.
        val dayStart = dayWindow(Instant.fromEpochMilliseconds(1_699_956_000_000L), 0, 1439).first
        val now = dayStart + 3.hours
        val stored = listOf(FocusPresenceInterval(dayStart + 1.hours, dayStart + 90.minutes))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
            initialFocusPresenceIntervals = stored,
            initialFocusSessionIntervals = stored,
        )
        val session = DayViewSession(controller, backgroundScope)
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        runCurrent()

        assertTrue(seen.last().focusArcs.isNotEmpty(), "restored engaged arcs should render")
        assertTrue(
            seen.last().focusTotalLabel.startsWith("Focus "),
            "restored engaged total should render, was '${seen.last().focusTotalLabel}'",
        )

        sub.cancel()
    }
```

The file already imports `kotlin.time.Duration.Companion.hours`, `.minutes`, `.seconds`, `Instant`, `assertEquals`, `assertTrue`, `runCurrent`, `runTest`. Add no imports unless the compiler asks for one.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: compilation failure — `Unresolved reference: StoredPresence` / `PresencePersistence` / no parameter `presencePersistence`.

- [ ] **Step 3: Create the seam**

Create `core/src/commonMain/kotlin/fr/dayview/app/PresencePersistence.kt`:

```kotlin
package fr.dayview.app

/**
 * Day-scoped presence storage. [dayKey] is -1 when nothing is stored; implementations apply
 * the staleness rule at read time, returning empty lists for a day that is not today, so a
 * stale write can never resurrect yesterday's arcs.
 */
data class StoredPresence(
    val dayKey: Long = -1L,
    val presence: List<FocusPresenceInterval> = emptyList(),
    val session: List<FocusPresenceInterval> = emptyList(),
)

/**
 * Persistence seam for the high-frequency focus-presence lists, which live outside the shared
 * [DayPreferencesSnapshot] (the Compose/JVM app keeps them out of it for the same reason).
 * Injected into [DayViewSession]; the no-op default leaves every other call site in-memory.
 */
interface PresencePersistence {
    suspend fun load(): StoredPresence

    suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    )
}

object NoopPresencePersistence : PresencePersistence {
    override suspend fun load(): StoredPresence = StoredPresence()

    override suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    ) = Unit
}
```

- [ ] **Step 4: Wire the session**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`:

1. Add the import for the 30-second throttle, next to the existing `kotlin.time` imports:

```kotlin
import kotlin.time.Duration.Companion.seconds
```

2. Add the constructor parameter as the **last** one, after `dockAttention`:

```kotlin
    private val dockAttention: DockAttentionProvider = NoopDockAttentionProvider,
    private val presencePersistence: PresencePersistence = NoopPresencePersistence,
) {
```

3. Add the bookkeeping fields next to the existing private fields (before `init` — properties declared after it are not yet initialized when it runs):

```kotlin
    // What the store already holds, and when it was last written. Comparing against the
    // persisted lists (rather than the previous tick) means a skipped write is retried on
    // the next tick instead of being lost.
    private var savedPresence: List<FocusPresenceInterval> = emptyList()
    private var savedSession: List<FocusPresenceInterval> = emptyList()
    private var lastPresenceSave: Instant = Instant.DISTANT_PAST
```

4. In `init`, record the seeded lists as the on-disk baseline so a launch does not immediately rewrite what it just read:

```kotlin
    init {
        refreshCalendar()
        run {
            val state = controller.stateFlow.value
            presence.restore(state.focusPresenceIntervals, state.focusSessionIntervals, dayKeyOf(state.now))
            savedPresence = state.focusPresenceIntervals
            savedSession = state.focusSessionIntervals
        }
    }
```

5. In `tick()`, immediately after `controller.setSessionOffGoal(result.sessionOffGoal)`:

```kotlin
        persistPresenceIfDue(state.now, dayKeyOf(state.now), result.presenceIntervals, result.sessionIntervals)
```

6. Add the method, next to `applyDockAttention()`:

```kotlin
    /**
     * JVM cadence (Main.kt): write on a structural change — a run opened or closed, so a
     * list's size moved — otherwise at most every 30s, since extending an open run only
     * moves its end. The write is launched into the session scope so the 1 Hz tick never
     * blocks on disk.
     */
    private fun persistPresenceIfDue(
        now: Instant,
        dayKey: Long,
        presenceIntervals: List<FocusPresenceInterval>,
        sessionIntervals: List<FocusPresenceInterval>,
    ) {
        if (presenceIntervals == savedPresence && sessionIntervals == savedSession) return
        val structural =
            presenceIntervals.size != savedPresence.size || sessionIntervals.size != savedSession.size
        if (!structural && now - lastPresenceSave < 30.seconds) return
        savedPresence = presenceIntervals
        savedSession = sessionIntervals
        lastPresenceSave = now
        scope.launch { presencePersistence.save(dayKey, presenceIntervals, sessionIntervals) }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: PASS, including the three new tests.

- [ ] **Step 6: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/PresencePersistence.kt \
        core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): persistence seam for focus presence intervals

Adds a PresencePersistence seam and drives it from the session's per-tick
presence block on the desktop cadence: write on a structural change, else at
most every 30 seconds. Defaults to a no-op, so in-memory callers are unchanged."
```

---

### Task 2: The macOS store, launch seeding, and the checklist

**Files:**
- Create: `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Modify: `docs/superpowers/macos-native-parity-checklist.md`

**Interfaces:**
- Consumes: everything Task 1 produced, plus `DayPreferencesStore(dataStore)`, `encodeFocusPresence`/`decodeFocusPresence`, `dayKeyOf(Instant)`.
- Produces: `MacosPreferences(val dayPreferences: DayPreferences, val presencePersistence: PresencePersistence)` and `fun macosPreferences(): MacosPreferences`, replacing `macosDayPreferences()`.

`macosMain` has no unit tests in this project (it is Kotlin/Native with platform bindings); verification is the native build plus the manual smoke test, as in every prior native phase.

**A deliberate deviation from the spec, for the reviewer:** the spec's key table lists four keys, including `mac_focus_session_day`. This task writes **three** — `focus_presence_day`, `focus_presence`, `mac_focus_session` — because both lists are written in one atomic `edit`, so their day keys can never differ and a second day key would be a value that is written but never able to disagree. `focus_presence_day` gates both lists on read. The spec's substantive requirement is unchanged: the shared store's `focus_session_day`/`focus_session` are neither read nor written here.

---

- [ ] **Step 1: Create the store**

Create `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt`:

```kotlin
package fr.dayview.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

// The presence pair reuses the Compose/JVM app's key names: DayPreferencesStore has no such
// keys, so there is no collision, and a file written by either app stays readable by the
// other. The session list deliberately does NOT reuse the JVM's "focus_session": that is
// already the shared store's own key, written from focusSessionDayKey on every persist —
// and that field stays -1 natively (derivesEngagedFromSessions is false), so sharing it
// would clobber the day key and make the intervals load as stale.
private val presenceDayKey = longPreferencesKey("focus_presence_day")
private val presenceKey = stringPreferencesKey("focus_presence")
private val sessionKey = stringPreferencesKey("mac_focus_session")

/**
 * [PresencePersistence] over the macOS DataStore, using the shared interval codecs. Both
 * lists are written in one atomic edit under a single day key, so they cannot disagree
 * about which day they belong to.
 */
class MacosPresencePersistence(
    private val dataStore: DataStore<Preferences>,
) : PresencePersistence {
    override suspend fun load(): StoredPresence {
        val prefs = dataStore.data.first()
        val day = prefs[presenceDayKey] ?: -1L
        // Staleness applied at read time: yesterday's arcs can never resurrect.
        if (day != dayKeyOf(Clock.System.now())) return StoredPresence()
        return StoredPresence(
            dayKey = day,
            presence = decodeFocusPresence(prefs[presenceKey].orEmpty()),
            session = decodeFocusPresence(prefs[sessionKey].orEmpty()),
        )
    }

    override suspend fun save(
        dayKey: Long,
        presence: List<FocusPresenceInterval>,
        session: List<FocusPresenceInterval>,
    ) {
        dataStore.edit {
            it[presenceDayKey] = dayKey
            it[presenceKey] = encodeFocusPresence(presence)
            it[sessionKey] = encodeFocusPresence(session)
        }
    }
}
```

- [ ] **Step 2: Expose one DataStore to both surfaces**

Replace the body of `core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt` with:

```kotlin
package fr.dayview.app

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/** The two macOS preference surfaces, both backed by the same DataStore over one file. */
class MacosPreferences(
    val dayPreferences: DayPreferences,
    val presencePersistence: PresencePersistence,
)

/**
 * File-backed preferences for the native macOS app, stored under
 * ~/Library/Application Support/DayView. Reuses the shared [DayPreferencesStore] encoding.
 *
 * Creates exactly ONE DataStore for the file and hands it to both surfaces: DataStore
 * requires a single instance per file, and a second one over the same path risks
 * corrupting it.
 */
fun macosPreferences(): MacosPreferences {
    val dir = "${NSHomeDirectory()}/Library/Application Support/DayView".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = dir / "dayview.preferences_pb"
    val storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = { path },
    )
    val dataStore = PreferenceDataStoreFactory.create(
        storage = storage,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )
    return MacosPreferences(
        dayPreferences = DayPreferencesStore(dataStore),
        presencePersistence = MacosPresencePersistence(dataStore),
    )
}
```

`macosDayPreferences()` is removed, not kept as a wrapper: leaving it would let a future call site build a second DataStore over the same file. `DayViewNative` is its only caller and is updated in the next step.

- [ ] **Step 3: Seed the controller at launch**

In `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`, replace the preferences/controller/session construction inside `create()`:

```kotlin
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val preferences = macosPreferences()
        // Read the persisted presence once, before the controller exists: seeding it is what
        // turns DayViewSession's PresenceCoordinator.restore call into a real reseed, so a
        // relaunch mid-session continues the run instead of restarting it.
        val stored = runBlocking { preferences.presencePersistence.load() }
        val controller = DayViewController(
            preferences.dayPreferences,
            scope,
            initialSnapshot = runBlocking { preferences.dayPreferences.snapshots.first() },
            initialFocusPresenceIntervals = stored.presence,
            initialFocusSessionIntervals = stored.session,
        )
        val source = EventKitCalendarSource()
        val session = DayViewSession(
            controller,
            scope,
            source,
            use24Hour = systemUses24HourClock(),
            frontmostAppProvider = NSWorkspaceFrontmostProvider(),
            dockAttention = NSAppDockAttention(),
            // The Debug build ships under a distinct bundle id (fr.dayview.app.debug) so it can
            // coexist with the shipping Compose app; deriving from the running bundle (rather
            // than the DAYVIEW_BUNDLE_ID default) keeps DayView itself classified NEUTRAL in
            // every config instead of only in Release.
            dayViewBundleId = NSBundle.mainBundle.bundleIdentifier ?: DAYVIEW_BUNDLE_ID,
            presencePersistence = preferences.presencePersistence,
        )
```

Leave the rest of `create()` (the `onPermissionChange` hook and the `return`) untouched.

- [ ] **Step 4: Build for the native target**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 6: Update the parity checklist**

In `docs/superpowers/macos-native-parity-checklist.md`:

1. Delete the paragraph beginning `**New (from the 10a review):** native presence persistence —` (line 60 at the time of writing).
2. Add a row at the end of the "Done" table:

```markdown
| Presence persistence: intervals written on the desktop cadence, seeded at launch so a relaunch mid-session continues the run | 11 |
```

- [ ] **Step 7: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt \
        core/src/macosMain/kotlin/fr/dayview/app/MacosDayPreferences.kt \
        core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt \
        docs/superpowers/macos-native-parity-checklist.md
git commit -m "feat(macos): persist focus presence across relaunches

Stores the presence and engaged-session intervals in the macOS preferences
file under keys of their own, and seeds them back into the controller at
launch so the accumulators resume the day's run rather than restarting it."
```

- [ ] **Step 8: Manual smoke test (run by the maintainer)**

1. Configure an on-goal app in Settings and start a Focus.
2. Stay in that app until mint arcs and a `Focus H h MM` total appear on the dial.
3. **Quit the app and relaunch it.**
4. Expected: the arcs and the total are still there, and the total keeps growing from where it was instead of restarting at zero.
5. Also confirm no spurious behaviour at launch: the resume ritual appearing for a still-active session is correct; arcs from a *previous* day must not appear.

---

## Notes for the reviewer

- **The staleness rule is applied at load, so a stale day yields empty lists.** The Compose/JVM app instead passes the previous day's intervals through to the controller unfiltered, which lets `maybeArchivePreviousDay()` capture them in the history record. That is deliberately not replicated here: `DayViewNative.create()` passes no history store, so the native archival path writes to the default `InMemoryDayHistoryStore` and discards the record anyway. When native history archiving lands (its own parity-checklist item), the seeding should switch to the JVM's shape — seed raw, and let the accumulators reset themselves on the day-key change.
- **`persistPresenceIfDue` compares against the last persisted lists**, not the previous tick, which is a small strengthening of the JVM's rule: a write skipped for any reason is retried on the next tick rather than lost. Intervals only grow, so it triggers on exactly the same events.
