# DataStore Preferences Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three hand-maintained `DayPreferences` backends (Android SharedPreferences, desktop `java.util.prefs`, no-op) with a single AndroidX DataStore-backed store shared in `commonMain`, migrating existing user data.

**Architecture:** One `DayPreferencesStore(dataStore: DataStore<Preferences>)` lives in `commonMain` and maps a `Preferences` blob to/from `DayPreferencesSnapshot`. The `DataStore` instance is constructed per platform (different file path + one-shot data migration) and injected. The interface goes async: a `Flow<DayPreferencesSnapshot>` replaces the old pull+`observe(callback)`, and a single atomic `suspend fun persist(snapshot)` replaces the six granular setters — collapsing multi-field writes (e.g. `closePomodoro`) into one `edit{}` so the controller's self-write reconciliation stays a clean no-op.

**Tech Stack:** Kotlin Multiplatform, AndroidX DataStore Preferences (`datastore-preferences-core`), okio (paths), kotlinx-coroutines, Compose Multiplatform, Robolectric (Android unit tests).

## Global Constraints

- DataStore artifact: `androidx.datastore:datastore-preferences-core:1.2.1` (KMP, targets android + jvm), declared in `commonMain` via the version catalog.
- Preserve **exact existing string keys** (`start_minutes`, `end_minutes`, `show_seconds`, `sound_enabled`, `sound_start`, `sound_interval`, `sound_end`, `sound_interval_minutes`, `sound_volume`, `goal_title`, `goal_deadline`, `pomodoro_minutes`, `pomodoro_end`, `focus_intention`, `monochrome_menu_bar_icon`) so migration is a value-for-value copy.
- Sentinel `NO_DEADLINE = -1L` for absent `goal_deadline` / `pomodoro_end`.
- No user-data reset: Android migrates from SharedPreferences file `dayview_preferences`; desktop migrates from `Preferences.userNodeForPackage(DesktopDayPreferences::class.java)`.
- Desktop-only key `monochrome_menu_bar_icon` is NOT part of `DayPreferencesSnapshot`; it stays a desktop-only accessor and must survive migration.
- All value clamping stays in `DayViewController` (`coerced()`), not in the store — the store persists/returns raw values.
- ktlint official style; `./gradlew ktlintCheck` must pass. JDK 21 toolchain (already configured).
- Widget/tile/alarm (`AppWidgetProvider.onUpdate`, `TileService`, `BroadcastReceiver.onReceive`) are non-coroutine; bridge one-shot reads with `runBlocking { store.snapshots.first() }`.

---

## File Structure

- `gradle/libs.versions.toml` — add datastore version + library alias.
- `composeApp/build.gradle.kts` — add dependency to `commonMain`.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` — new async interface + `DefaultDayPreferences` (in-memory).
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` — **new**: `DayPreferencesStore`, key constants, `Preferences.toSnapshot()`, `persist`.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` — inject `CoroutineScope`; persist via `persist(snapshot)`; add self-write guard.
- `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt` — becomes a factory building an Android `DataStore` with `SharedPreferencesMigration`, wrapping `DayPreferencesStore`.
- `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt` — factory building a JVM `DataStore` with a `java.util.prefs` `DataMigration`; keeps desktop-only monochrome accessors.
- `composeApp/src/androidMain/.../DayViewWidget.kt`, `DayViewFocusTileService.kt`, `FocusAlarm.kt`, `FocusNotification.kt`, `MainActivity.kt` — adopt new API.
- `composeApp/src/desktopMain/.../Main.kt` — collect `snapshots`; scope wiring.
- Tests: `DayPreferencesStoreTest` (commonTest), updated `DayViewControllerTest` fake, `AndroidDayPreferencesTest`, `DesktopDayPreferencesTest`, updated `DayPreferencesTest`.

---

### Task 1: Add DataStore dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

**Interfaces:**
- Produces: catalog alias `libs.androidx.datastore.preferences.core` available to `commonMain`.

- [ ] **Step 1: Add version and library to the catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
datastore = "1.2.1"
```
Under `[libraries]` add:
```toml
androidx-datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
```

- [ ] **Step 2: Add the dependency to commonMain**

In `composeApp/build.gradle.kts`, inside `val commonMain by getting { dependencies { … } }`, add after `implementation(libs.kotlinx.datetime)`:
```kotlin
implementation(libs.androidx.datastore.preferences.core)
```

- [ ] **Step 3: Verify resolution**

Run: `./gradlew :composeApp:dependencies --configuration desktopRuntimeClasspath | grep -i datastore`
Expected: shows `androidx.datastore:datastore-preferences-core:1.2.1` and a transitive `com.squareup.okio:okio`.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "build: add datastore-preferences-core to commonMain"
```

---

### Task 2: Async interface + in-memory default

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot` (unchanged data class).
- Produces:
  - `interface DayPreferences { val snapshots: Flow<DayPreferencesSnapshot>; suspend fun persist(snapshot: DayPreferencesSnapshot) }`
  - `object DefaultDayPreferences : DayPreferences` backed by `MutableStateFlow`.

- [ ] **Step 1: Replace the test to match the async default**

Replace the entire body of `DayPreferencesTest.kt` with:
```kotlin
package fr.dayview.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DayPreferencesTest {
    @Test
    fun defaultExposesSafeDefaults() = runTest {
        assertEquals(DayPreferencesSnapshot(), DefaultDayPreferences.snapshots.first())
    }

    @Test
    fun defaultPersistsInMemory() = runTest {
        DefaultDayPreferences.persist(DayPreferencesSnapshot(startMinutes = 9 * 60, focusIntention = "x"))
        val stored = DefaultDayPreferences.snapshots.first()
        assertEquals(9 * 60, stored.startMinutes)
        assertEquals("x", stored.focusIntention)
    }
}
```

- [ ] **Step 2: Add the coroutines-test dependency to commonTest**

In `gradle/libs.versions.toml` `[libraries]` add:
```toml
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
```
If no `coroutines` version exists yet, add under `[versions]`: `coroutines = "1.10.2"`. In `composeApp/build.gradle.kts`, `val commonTest by getting { dependencies { … } }`, add:
```kotlin
implementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: FAIL — `DayPreferences` still has `load*`/`save*`, no `snapshots`/`persist`.

- [ ] **Step 4: Rewrite the interface and default**

Replace everything in `DayPreferences.kt` below the `DayPreferencesSnapshot` data class (keep the data class as-is) with:
```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface DayPreferences {
    val snapshots: Flow<DayPreferencesSnapshot>
    suspend fun persist(snapshot: DayPreferencesSnapshot)
}

object DefaultDayPreferences : DayPreferences {
    private val state = MutableStateFlow(DayPreferencesSnapshot())
    override val snapshots: Flow<DayPreferencesSnapshot> = state.asStateFlow()
    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: PASS. (Other modules won't compile yet — that's fixed in later tasks; run only this test class.)

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt \
        gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "feat: make DayPreferences a Flow + suspend persist interface"
```

---

### Task 3: Common DayPreferencesStore

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `DataStore<Preferences>` (from `androidx.datastore`), `DayPreferencesSnapshot`, `SoundSettings`.
- Produces: `class DayPreferencesStore(dataStore: DataStore<Preferences>) : DayPreferences`. Public key constants object `DayPreferenceKeys` with the string names for reuse by platform migrations.

- [ ] **Step 1: Write the failing round-trip test**

Create `DayPreferencesStoreTest.kt`:
```kotlin
package fr.dayview.app

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class DayPreferencesStoreTest {
    private fun newStore(): DayPreferencesStore {
        val fs = FakeFileSystem()
        val store = PreferenceDataStoreFactory.createWithPath(
            fileSystem = fs,
        ) { "/prefs.preferences_pb".toPath() }
        return DayPreferencesStore(store)
    }

    @Test
    fun persistThenReadRoundTrips() = runTest {
        val store = newStore()
        val snapshot = DayPreferencesSnapshot(
            startMinutes = 9 * 60,
            endMinutes = 17 * 60,
            showSeconds = false,
            soundSettings = SoundSettings(enabled = true, volumePercent = 55),
            goalTitle = "Ship it",
            goalDeadlineMillis = 123_456_789L,
            pomodoroMinutes = 45,
            pomodoroEndMillis = null,
            focusIntention = "Focus",
        )
        store.persist(snapshot)
        assertEquals(snapshot, store.snapshots.first())
    }

    @Test
    fun missingValuesFallBackToDefaults() = runTest {
        val store = newStore()
        assertEquals(DayPreferencesSnapshot(), store.snapshots.first())
    }
}
```
Add the okio fake-filesystem test dependency: in the catalog `[libraries]` add
`okio-fakefilesystem = { module = "com.squareup.okio:okio-fakefilesystem", version.ref = "okio" }`
with `okio = "3.9.1"` under `[versions]`, and `implementation(libs.okio.fakefilesystem)` in `commonTest`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — `DayPreferencesStore` does not exist.

- [ ] **Step 3: Implement the store**

Create `DayPreferencesStore.kt`:
```kotlin
package fr.dayview.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object DayPreferenceKeys {
    const val START = "start_minutes"
    const val END = "end_minutes"
    const val SHOW_SECONDS = "show_seconds"
    const val SOUND_ENABLED = "sound_enabled"
    const val SOUND_START = "sound_start"
    const val SOUND_INTERVAL = "sound_interval"
    const val SOUND_END = "sound_end"
    const val SOUND_INTERVAL_MINUTES = "sound_interval_minutes"
    const val SOUND_VOLUME = "sound_volume"
    const val GOAL_TITLE = "goal_title"
    const val GOAL_DEADLINE = "goal_deadline"
    const val POMODORO_MINUTES = "pomodoro_minutes"
    const val POMODORO_END = "pomodoro_end"
    const val FOCUS_INTENTION = "focus_intention"
    const val NO_DEADLINE = -1L
}

private val startKey = intPreferencesKey(DayPreferenceKeys.START)
private val endKey = intPreferencesKey(DayPreferenceKeys.END)
private val showSecondsKey = booleanPreferencesKey(DayPreferenceKeys.SHOW_SECONDS)
private val soundEnabledKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_ENABLED)
private val soundStartKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_START)
private val soundIntervalKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_INTERVAL)
private val soundEndKey = booleanPreferencesKey(DayPreferenceKeys.SOUND_END)
private val soundIntervalMinutesKey = intPreferencesKey(DayPreferenceKeys.SOUND_INTERVAL_MINUTES)
private val soundVolumeKey = intPreferencesKey(DayPreferenceKeys.SOUND_VOLUME)
private val goalTitleKey = stringPreferencesKey(DayPreferenceKeys.GOAL_TITLE)
private val goalDeadlineKey = longPreferencesKey(DayPreferenceKeys.GOAL_DEADLINE)
private val pomodoroMinutesKey = intPreferencesKey(DayPreferenceKeys.POMODORO_MINUTES)
private val pomodoroEndKey = longPreferencesKey(DayPreferenceKeys.POMODORO_END)
private val focusIntentionKey = stringPreferencesKey(DayPreferenceKeys.FOCUS_INTENTION)

class DayPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : DayPreferences {
    override val snapshots: Flow<DayPreferencesSnapshot> = dataStore.data.map { it.toSnapshot() }

    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        dataStore.edit { prefs ->
            prefs[startKey] = snapshot.startMinutes
            prefs[endKey] = snapshot.endMinutes
            prefs[showSecondsKey] = snapshot.showSeconds
            prefs[soundEnabledKey] = snapshot.soundSettings.enabled
            prefs[soundStartKey] = snapshot.soundSettings.startCueEnabled
            prefs[soundIntervalKey] = snapshot.soundSettings.intervalCueEnabled
            prefs[soundEndKey] = snapshot.soundSettings.endCueEnabled
            prefs[soundIntervalMinutesKey] = snapshot.soundSettings.intervalMinutes
            prefs[soundVolumeKey] = snapshot.soundSettings.volumePercent
            prefs[goalTitleKey] = snapshot.goalTitle
            prefs[goalDeadlineKey] = snapshot.goalDeadlineMillis ?: DayPreferenceKeys.NO_DEADLINE
            prefs[pomodoroMinutesKey] = snapshot.pomodoroMinutes
            prefs[pomodoroEndKey] = snapshot.pomodoroEndMillis ?: DayPreferenceKeys.NO_DEADLINE
            prefs[focusIntentionKey] = snapshot.focusIntention
        }
    }
}

private fun Preferences.toSnapshot(): DayPreferencesSnapshot {
    val defaults = DayPreferencesSnapshot()
    return DayPreferencesSnapshot(
        startMinutes = this[startKey] ?: defaults.startMinutes,
        endMinutes = this[endKey] ?: defaults.endMinutes,
        showSeconds = this[showSecondsKey] ?: defaults.showSeconds,
        soundSettings = SoundSettings(
            enabled = this[soundEnabledKey] ?: false,
            startCueEnabled = this[soundStartKey] ?: true,
            intervalCueEnabled = this[soundIntervalKey] ?: true,
            endCueEnabled = this[soundEndKey] ?: true,
            intervalMinutes = this[soundIntervalMinutesKey] ?: 30,
            volumePercent = this[soundVolumeKey] ?: 40,
        ),
        goalTitle = this[goalTitleKey] ?: defaults.goalTitle,
        goalDeadlineMillis = this[goalDeadlineKey]?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE },
        pomodoroMinutes = this[pomodoroMinutesKey] ?: defaults.pomodoroMinutes,
        pomodoroEndMillis = this[pomodoroEndKey]?.takeUnless { it == DayPreferenceKeys.NO_DEADLINE },
        focusIntention = this[focusIntentionKey] ?: defaults.focusIntention,
    )
}
```
Note: the default `SoundSettings()` field defaults must match the literals above (`enabled=false, start/interval/end=true, intervalMinutes=30, volumePercent=40`). Verify against `SoundAlerts.kt` before implementing; if they differ, use `SoundSettings()`'s actual defaults.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt \
        gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "feat: add common DataStore-backed DayPreferencesStore"
```

---

### Task 4: Controller adopts persist + self-write guard

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (construction site)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: `DayPreferences.persist(snapshot)`, `DayPreferences.snapshots`.
- Produces: `DayViewController(preferences, scope: CoroutineScope, initialSnapshot: DayPreferencesSnapshot, initialNowMillis)`; still exposes `state`, all `setX`, and `onPreferencesChanged(snapshot)`.

Design of the guard: every mutation builds the new `state`, then calls `persistState()` which snapshots the persisted fields, stores them in `lastPersisted`, and launches `scope.launch { preferences.persist(it) }`. `onPreferencesChanged` skips reconciliation when the incoming snapshot's persisted fields equal `lastPersisted` (its own echo).

- [ ] **Step 1: Update the test fake and add a guard test**

In `DayViewControllerTest.kt`, replace the `InMemoryDayPreferences` class with:
```kotlin
private class InMemoryDayPreferences(
    initial: DayPreferencesSnapshot = DayPreferencesSnapshot(),
) : DayPreferences {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val snapshots = state
    override suspend fun persist(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
    // Test helper mirroring an external writer (tile/widget).
    fun emitExternal(snapshot: DayPreferencesSnapshot) {
        state.value = snapshot
    }
}
```
Update each test's construction to `DayViewController(preferences, scope, preferences.snapshots.value, initialNowMillis = …)` using a `TestScope`. Replace `preferences.observe(controller::onPreferencesChanged)` call sites with collecting the flow in the test scope:
```kotlin
val job = scope.launch { preferences.snapshots.collect(controller::onPreferencesChanged) }
```
and `preferences.savePomodoro(50, …)` style external writes with `preferences.emitExternal(current.copy(pomodoroEndMillis = …, focusIntention = …))`.

Add a new test asserting a self-write is not double-applied and an in-flight draft survives (port the existing draft-preservation test to the async fake, driving with `runTest` + `advanceUntilIdle()`).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — constructor signature and `persist` don't exist yet.

- [ ] **Step 3: Rewrite the controller persistence**

Change the constructor and persistence. Replace the class header:
```kotlin
internal class DayViewController(
    private val preferences: DayPreferences,
    private val scope: CoroutineScope,
    initialSnapshot: DayPreferencesSnapshot,
    initialNowMillis: Long,
) {
    var state: DayViewUiState by mutableStateOf(initialSnapshot.toUiState(initialNowMillis))
        private set

    private var lastPersisted: DayPreferencesSnapshot = initialSnapshot.coerced()

    private fun persistState() {
        val snapshot = state.toSnapshot()
        lastPersisted = snapshot
        scope.launch { preferences.persist(snapshot) }
    }
```
Replace every `preferences.saveX(...)` call in the setters with a single trailing `persistState()`. For example `setStartMinutes`:
```kotlin
fun setStartMinutes(minutes: Int) {
    val updated = minutes.coerceIn(0, state.endMinutes - 30)
    state = state.copy(startMinutes = updated)
    persistState()
}
```
Apply the same pattern to `setEndMinutes`, `setShowSeconds`, `setSoundSettings`, `setGoalTitle`, `commitGoalDeadline`, `setFocusIntention`, `changePomodoroDuration`, `startPomodoro`, `stopPomodoro`, and `closePomodoro` (whose two-save comment block is deleted — persistence is now a single atomic `persistState()`).

Update `onPreferencesChanged` with the guard:
```kotlin
fun onPreferencesChanged(snapshot: DayPreferencesSnapshot) {
    if (snapshot.coerced() == lastPersisted) return
    state = state.withPersisted(snapshot)
}
```
Add a private `DayViewUiState.toSnapshot()`:
```kotlin
private fun DayViewUiState.toSnapshot() = DayPreferencesSnapshot(
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    showSeconds = showSeconds,
    soundSettings = soundSettings,
    goalTitle = goalTitle,
    goalDeadlineMillis = goalDeadlineMillis,
    pomodoroMinutes = pomodoroMinutes,
    pomodoroEndMillis = pomodoroEndMillis,
    focusIntention = focusIntention,
).coerced()
```
Add imports `kotlinx.coroutines.CoroutineScope` and `kotlinx.coroutines.launch`.

- [ ] **Step 4: Update the App.kt construction and collection**

In `App.kt`, where the controller is built, use `rememberCoroutineScope()` and collect on first composition:
```kotlin
val scope = rememberCoroutineScope()
val initialSnapshot = remember { runBlocking { preferences.snapshots.first() } }
val controller = remember(preferences) {
    DayViewController(preferences, scope, initialSnapshot, Clock.System.now().toEpochMilliseconds())
}
LaunchedEffect(preferences) {
    preferences.snapshots.collect(controller::onPreferencesChanged)
}
```
(If `App.kt` already collects `observe(...)`, replace that block. Keep the existing `state`/recomposition wiring that reads `controller.state`.)

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: controller persists atomic snapshots with self-write guard"
```

---

### Task 5: Android DataStore + SharedPreferences migration

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- Test: `composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt`

**Interfaces:**
- Produces: `fun androidDayPreferences(context: Context): DayPreferences` returning a `DayPreferencesStore` over an Android-file DataStore that migrates the `dayview_preferences` SharedPreferences on first read.

- [ ] **Step 1: Write the failing migration test**

Replace `AndroidDayPreferencesTest.kt` with a Robolectric test that seeds SharedPreferences then asserts values surface through `snapshots`:
```kotlin
package fr.dayview.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidDayPreferencesTest {
    @Test
    fun migratesLegacySharedPreferences() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE).edit()
            .putInt("start_minutes", 9 * 60)
            .putString("goal_title", "Legacy goal")
            .apply()

        val prefs = androidDayPreferences(context)
        val snapshot = prefs.snapshots.first()

        assertEquals(9 * 60, snapshot.startMinutes)
        assertEquals("Legacy goal", snapshot.goalTitle)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.AndroidDayPreferencesTest"`
Expected: FAIL — `androidDayPreferences` does not exist.

- [ ] **Step 3: Implement the Android factory**

Add the Android datastore-preferences artifact (needed for `SharedPreferencesMigration`): in the catalog add
`androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }`
and in `composeApp/build.gradle.kts` `androidMain` dependencies add `implementation(libs.androidx.datastore.preferences)`.

Replace `AndroidDayPreferences.kt` with:
```kotlin
package fr.dayview.app

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.SharedPreferencesMigration as preferencesSharedPreferencesMigration
import java.io.File

fun androidDayPreferences(context: Context): DayPreferences {
    val appContext = context.applicationContext
    val dataStore = PreferenceDataStoreFactory.create(
        migrations = listOf(
            preferencesSharedPreferencesMigration(appContext, "dayview_preferences"),
        ),
    ) {
        File(appContext.filesDir, "datastore/dayview.preferences_pb")
    }
    return DayPreferencesStore(dataStore)
}
```
Note: use the correct import — AndroidX exposes `androidx.datastore.preferences.SharedPreferencesMigration(context, name)` (a factory returning a `DataMigration<Preferences>`). Verify the exact symbol via the resolved dependency; adjust the import if the class path differs. Keep the `filesDir/datastore/…` path.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.AndroidDayPreferencesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt \
        composeApp/src/androidUnitTest/kotlin/fr/dayview/app/AndroidDayPreferencesTest.kt \
        gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "feat(android): DataStore preferences with SharedPreferences migration"
```

---

### Task 6: Desktop DataStore + java.util.prefs migration

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt`

**Interfaces:**
- Produces: `class DesktopDayPreferences(dataStore, legacy: Preferences)` implementing `DayPreferences` + desktop-only `loadMonochromeMenuBarIcon()/saveMonochromeMenuBarIcon()`. Factory `fun desktopDayPreferences(): DesktopDayPreferences`.

- [ ] **Step 1: Write the failing migration test**

Replace `DesktopDayPreferencesTest.kt` with a temp-dir DataStore + seeded `java.util.prefs` node test asserting the one-shot migration copies known keys, and a separate assertion that `monochrome_menu_bar_icon` survives. Use `java.util.prefs.Preferences` under a test-only node and `PreferenceDataStoreFactory.create { File(tmpDir, "dayview.preferences_pb") }`. Assert `store.snapshots.first().startMinutes` equals the seeded legacy value.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: FAIL — new factory/migration absent.

- [ ] **Step 3: Implement the desktop factory + migration**

Rewrite `DesktopDayPreferences.kt` to build a JVM DataStore at a user-config path (e.g. `System.getProperty("user.home")/.config/DayView/dayview.preferences_pb`, respecting existing behaviour) with a `DataMigration<Preferences>` that, when the DataStore is empty and the legacy `Preferences` node has keys, copies each known key into the store and returns it. Keep `monochrome_menu_bar_icon` accessors reading/writing the DataStore via a desktop-only boolean key (added to `DayPreferenceKeys` usage in desktop code, not the common snapshot). Preserve the `DayPreferencesStore` delegation for `snapshots`/`persist`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt
git commit -m "feat(desktop): DataStore preferences with java.util.prefs migration"
```

---

### Task 7: Rewire consumers to the async API

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewWidget.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewFocusTileService.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/FocusAlarm.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/FocusNotification.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`
- Test: existing `FocusAlarmTest.kt` updated as needed.

**Interfaces:**
- Consumes: `androidDayPreferences(context)`, `desktopDayPreferences()`, `store.snapshots`, `store.persist(...)`.

- [ ] **Step 1: Replace synchronous reads at the three Android system edges**

In `DayViewWidget.kt`, `DayViewFocusTileService.kt`, `FocusAlarm.kt`, `FocusNotification.kt`, replace `AndroidDayPreferences(context, …).loadX()` / `.snapshot()` reads with:
```kotlin
val snapshot = runBlocking { androidDayPreferences(context).snapshots.first() }
```
and any write (e.g. tile starting a focus) with:
```kotlin
runBlocking {
    val prefs = androidDayPreferences(context)
    val current = prefs.snapshots.first()
    prefs.persist(current.copy(focusIntention = …, pomodoroEndMillis = …))
}
```
Add imports `kotlinx.coroutines.runBlocking`, `kotlinx.coroutines.flow.first`. Note widget refresh is now driven by DataStore emissions rather than `refreshWidgets()`; if the widget must repaint on write, keep an explicit `DayViewWidget.updateAll(context)` call after the persist.

- [ ] **Step 2: Update MainActivity focus-alarm wiring**

In `MainActivity.kt`, replace preference reads with `androidDayPreferences(this)` and read initial state via `runBlocking { …snapshots.first() }` at startup only; pass the same `DayPreferences` into `DayViewApp(preferences = …)`.

- [ ] **Step 3: Update desktop Main.kt**

In `Main.kt`, build `val preferences = remember { desktopDayPreferences() }`, get initial snapshot via `runBlocking { preferences.snapshots.first() }`, and collect updates with `LaunchedEffect(preferences) { preferences.snapshots.collect { preferenceSnapshot = it } }`. Replace `preferences.saveMonochromeMenuBarIcon(...)` / `loadMonochromeMenuBarIcon()` calls with the retained desktop-only accessors (now suspend or wrapped in the UI scope with `scope.launch`).

- [ ] **Step 4: Compile all targets**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL (no references to removed `load*`/`save*`/`observe`).

- [ ] **Step 5: Run the affected Android tests**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.FocusAlarmTest"`
Expected: PASS (update the test's preference construction to `androidDayPreferences(context)` if it referenced the old class).

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain composeApp/src/desktopMain composeApp/src/androidUnitTest
git commit -m "refactor: rewire preference consumers to async DataStore API"
```

---

### Task 8: Full verification and cleanup

**Files:**
- Modify: any file still referencing removed symbols.
- Modify: `docs/superpowers/specs/2026-07-11-datastore-preferences-migration-design.md` (mark implemented; note `persist(snapshot)` supersedes the granular-setter sketch).

- [ ] **Step 1: ktlint format + check**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full test + build matrix (mirrors CI)**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest :composeApp:assembleDebug :composeApp:assembleRelease`
Expected: BUILD SUCCESSFUL, 0 test failures.

- [ ] **Step 3: Verify desktop packaging still resolves okio**

Run: `./gradlew :composeApp:createDistributable`
Expected: BUILD SUCCESSFUL (confirms the DataStore/okio deps package into the desktop image).

- [ ] **Step 4: Delete dead code**

Grep for leftovers and remove any: `grep -rn "loadStartMinutes\|saveDayRange\|fun snapshot()\|fun observe(" composeApp/src` should return nothing except intended call sites. Remove unused imports.

- [ ] **Step 5: Update the spec note and commit**

```bash
git add -A
git commit -m "docs: mark DataStore migration implemented; note persist() interface"
```

---

## Self-Review Notes

- **Spec coverage:** dependency (T1), async interface (T2), common store (T3), reactive/atomic controller with self-write guard + closePomodoro atomicity (T4), Android migration (T5), desktop migration incl. monochrome-only key (T6), three runBlocking edges + desktop collect (T7), packaging/okio risk + verification (T8). All spec sections mapped.
- **Type consistency:** `persist(snapshot)` / `snapshots: Flow<DayPreferencesSnapshot>` used identically across T2–T7; `DayPreferenceKeys` string constants shared by store (T3) and platform migrations (T5/T6); controller constructor `(preferences, scope, initialSnapshot, initialNowMillis)` consistent between T4 and its consumers in T7.
- **Verify-before-implement flags:** T3 `SoundSettings()` default literals, T5 `SharedPreferencesMigration` exact import path — both call out to confirm against the resolved dependency before coding.
