# macOS Native History Storage Implementation Plan (Phase 13a)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native macOS app archive each completed day to disk, instead of writing it into an in-memory store that dies with the process.

**Architecture:** `HistoryFileSystem` and `FileDayHistoryStore` move from `:shared` to `:core` and become public — the second `:shared`→`:core` migration, after the drift detectors. A new Okio-backed `HistoryFileSystem` lives in `:core` commonMain and takes its directory as a constructor argument, so `:core` gains no `expect`/`actual` and no new source set. `DayViewNative` builds the store and hands it to the controller, whose rollover archiving is already wired. One coupling comes with it: the archive is built from the same seeded state the presence loader fills, so that loader must stop discarding a stale day before archiving starts writing.

**Tech Stack:** Kotlin Multiplatform (`:core` commonMain / commonTest / jvmTest / macosMain), Okio, Kotlin/Native macOS target.

## Global Constraints

- **The shipping apps do not change behaviour.** The Compose/JVM and Android builds keep `createHistoryFileSystem()`, both `JvmHistoryFileSystem` copies, their directories, file names, encoding and I/O calls. This migration only makes code reachable from `:core`.
- **`:core` gains no `expect`/`actual` and no new source set.** It has none today; every platform capability arrives through an injected interface (`CalendarSource`, `FrontmostAppProvider`, `DockAttentionProvider`, `PresencePersistence`). The history directory is a constructor argument.
- **`FileFocusContributionStore` stays in `:shared`.** It depends on `fr.dayview.app.sync.FocusContributionMapper`; moving it would pull the sync subsystem into `:core` as a side effect of a history change.
- **No change to `DayHistoryRecord`, `DayHistoryCodec`, or the on-disk format**, and no change to when archiving happens — `maybeArchivePreviousDay()` already runs from `init` and from `tick` on a day-key change.
- `internal` → `public` is a required consequence of crossing the module boundary, as with the phase-10a detectors.
- ktlint is enforced (`./gradlew ktlintFormat` auto-fixes most findings). Full gate before each commit:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
- Both builds stay green at every task: `./gradlew :core:runMacNative` must succeed at the end of each one.
- Commit messages: English, no reference to Claude/Anthropic/an AI assistant, no reference to `docs/superpowers/`, no test-plan or verification section.

## File Structure

| File | Responsibility |
|---|---|
| `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt` (**modify**) | Gains the public `HistoryFileSystem` interface and `FileDayHistoryStore`, beside the existing interface and in-memory store |
| `core/src/commonMain/kotlin/fr/dayview/app/OkioHistoryFileSystem.kt` (**create**) | The one filesystem implementation `:core` owns: Okio, directory injected |
| `shared/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt` (**modify**) | Keeps the `expect`, the factories and `FileFocusContributionStore`; loses the two moved declarations |
| `shared/src/commonTest/kotlin/fr/dayview/app/FakeHistoryFileSystem.kt` (**create**) | The stub extracted so the contribution test keeps compiling |
| `shared/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt` (**delete**) | Moves to `:core` with the code it covers |
| `core/src/jvmTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt` (**create**) | The store and the Okio filesystem, against Okio's `FakeFileSystem` |
| `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt` (**modify**) | Return the stored presence raw, with the day it belongs to |
| `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (**modify**) | Restore the accumulators under the stored day, not today's |
| `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt` (**modify**) | The stale-day restore is cleared by the first tick |
| `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryArchivingTest.kt` (**create**) | The controller reaches a real store on rollover |
| `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt` (**modify**) | Builds the store, passes it and the stored day key to the controller and session |
| `gradle/libs.versions.toml`, `core/build.gradle.kts` (**modify**) | The `okio` library alias and the `:core` dependency |
| `docs/superpowers/macos-native-parity-checklist.md` (**modify**, Task 4) | Record what shipped and the deliberate duplication |

---

### Task 1: Move the store into `:core` and give it an Okio filesystem

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, `shared/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, `gradle/libs.versions.toml`, `core/build.gradle.kts`
- Create: `core/src/commonMain/kotlin/fr/dayview/app/OkioHistoryFileSystem.kt`, `shared/src/commonTest/kotlin/fr/dayview/app/FakeHistoryFileSystem.kt`, `core/src/jvmTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt`
- Delete: `shared/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt`

**Interfaces:**
- Consumes (pre-existing in `:core`): `DayHistoryStore`, `DayHistoryRecord`, `DayHistoryCodec.encode`/`decode`.
- Produces, for Task 2:
  - `interface HistoryFileSystem { fun read(name: String): String?; fun writeAtomic(name: String, text: String); fun list(): List<String> }` — public, `:core` commonMain
  - `class FileDayHistoryStore(fs: HistoryFileSystem) : DayHistoryStore` — public, `:core` commonMain
  - `class OkioHistoryFileSystem(fileSystem: FileSystem, dir: Path) : HistoryFileSystem` — public, `:core` commonMain

---

- [ ] **Step 1: Add the Okio dependency**

In `gradle/libs.versions.toml`, add a plain `okio` library alias next to the existing fake-filesystem one (the `okio` version is already declared at the top of the file):

```toml
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
```

In `core/build.gradle.kts`, add it to `commonMain`'s dependencies, after the DataStore line:

```kotlin
                implementation(libs.androidx.datastore.preferences.core)
                implementation(libs.okio)
```

Okio already reaches `:core` transitively through DataStore's `OkioStorage` — `macosDayPreferences()` uses `FileSystem.SYSTEM` today. Declaring it makes the dependency honest.

- [ ] **Step 2: Move the two declarations into `:core`**

In `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, add above the existing `interface DayHistoryStore`:

```kotlin
/**
 * Platform file access for the history directory. `name` is the bare `dayKey` string.
 * Public rather than internal: `:shared` implements it for the JVM and Android apps.
 */
interface HistoryFileSystem {
    fun read(name: String): String?

    fun writeAtomic(
        name: String,
        text: String,
    )

    fun list(): List<String>
}
```

and below `InMemoryDayHistoryStore`:

```kotlin
/**
 * File-per-day archive over a [HistoryFileSystem]. Writing is idempotent — an existing day is
 * never clobbered — because a stale day may be archived more than once (a cold launch and a
 * rollover tick can both reach it).
 */
class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore {
    override suspend fun write(record: DayHistoryRecord) {
        val name = record.dayKey.toString()
        if (fs.read(name) != null) return
        fs.writeAtomic(name, DayHistoryCodec.encode(record))
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? = fs.read(dayKey.toString())?.let { DayHistoryCodec.decode(it) }

    override suspend fun listDays(range: LongRange): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.filter { it in range }.sorted()

    override suspend fun listAllDays(): List<Long> = fs.list().mapNotNull { it.toLongOrNull() }.sorted()
}
```

Then in `shared/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, delete the now-duplicated `internal interface HistoryFileSystem { … }` block and the whole `internal class FileDayHistoryStore(…) { … }` block. Leave everything else in that file exactly as it is: the `expect fun createHistoryFileSystem()`, `createDayHistoryStore()`, `FileFocusContributionStore` and `createFocusContributionStore()`. Same package, so no import changes are needed anywhere.

- [ ] **Step 3: Extract the test stub `:shared` still needs**

`FakeHistoryFileSystem` is declared inside `shared/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt`, and `FileFocusContributionStoreTest` uses it. That test file is about to be deleted, so create `shared/src/commonTest/kotlin/fr/dayview/app/FakeHistoryFileSystem.kt`:

```kotlin
package fr.dayview.app

/** In-memory [HistoryFileSystem] for the contribution-store tests. */
internal class FakeHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()

    override fun read(name: String): String? = files[name]

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        files[name] = text
    }

    override fun list(): List<String> = files.keys.toList()
}
```

- [ ] **Step 4: Move the store's test to `:core`**

```bash
git rm shared/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt
```

Create `core/src/jvmTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt`. It lives in `jvmTest` rather than `commonTest` because it uses `okio-fakefilesystem`, which is a `:core` **jvmTest-only** dependency — adding it to the native test binary triggers a Kotlin/Native IR linker crash (documented in `core/build.gradle.kts`). This is the same placement as `DayPreferencesStoreTest`.

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDayHistoryStoreTest {
    private val dir = "/history".toPath()

    private fun newStore(fs: FakeFileSystem) = FileDayHistoryStore(OkioHistoryFileSystem(fs, dir))

    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), focusSessionIntervals = emptyList(),
        focusSessionRecords = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun writeThenReadRoundTripsThroughARealFilesystem() = runTest {
        val store = newStore(FakeFileSystem())
        store.write(record(100L))
        assertEquals(record(100L), store.read(100L))
    }

    @Test
    fun readMissingDayIsNull() = runTest {
        assertNull(newStore(FakeFileSystem()).read(999L))
    }

    @Test
    fun corruptFileReadsAsNull() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        OkioHistoryFileSystem(fs, dir).writeAtomic("7", "garbage")
        assertNull(store.read(7L))
    }

    @Test
    fun writeIsIdempotentAndDoesNotClobber() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(100L))
        val first = fs.read(dir / "100") { readUtf8() }
        // A stale day can be archived twice (cold launch, then a rollover tick): the first
        // record must win, even when the second carries different content.
        store.write(record(100L).copy(focusIntention = "changed"))
        assertEquals(first, fs.read(dir / "100") { readUtf8() })
    }

    @Test
    fun listDaysFiltersToRangeAndSorts() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(10L))
        store.write(record(20L))
        store.write(record(30L))
        assertEquals(listOf(10L, 20L), store.listDays(5L..25L))
    }

    @Test
    fun listAllDaysReturnsEveryArchivedDaySorted() = runTest {
        val store = newStore(FakeFileSystem())
        store.write(record(20200))
        store.write(record(20100))
        store.write(record(20300))
        assertEquals(listOf(20100L, 20200L, 20300L), store.listAllDays())
    }

    @Test
    fun temporaryFilesAreNeitherLeftBehindNorListedAsDays() = runTest {
        val fs = FakeFileSystem()
        val store = newStore(fs)
        store.write(record(42L))
        // The atomic write must move its scratch file, not copy it.
        assertTrue(fs.list(dir).none { it.name.endsWith(".tmp") }, "a .tmp file was left behind")

        // A leftover from an interrupted write must never read as an archived day.
        fs.write(dir / "99.tmp") { writeUtf8("interrupted") }
        assertEquals(listOf(42L), store.listAllDays())
    }

    @Test
    fun writingCreatesTheDirectoryWhenItDoesNotExist() = runTest {
        val fs = FakeFileSystem()
        // No createDirectories call: the store is the first thing to touch this path.
        newStore(fs).write(record(7L))
        assertEquals(record(7L), newStore(fs).read(7L))
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.FileDayHistoryStoreTest'`

Expected: compilation failure — `Unresolved reference: OkioHistoryFileSystem`.

- [ ] **Step 6: Write the Okio filesystem**

Create `core/src/commonMain/kotlin/fr/dayview/app/OkioHistoryFileSystem.kt`:

```kotlin
package fr.dayview.app

import okio.FileSystem
import okio.Path

/**
 * The [HistoryFileSystem] `:core` owns, over Okio — which runs on the JVM, Android and
 * Kotlin/Native alike, so one implementation serves every target. The directory is a
 * constructor argument rather than something this class discovers: each app knows where its
 * own history lives, and injecting it keeps `:core` free of expect/actual.
 */
class OkioHistoryFileSystem(
    private val fileSystem: FileSystem,
    private val dir: Path,
) : HistoryFileSystem {
    override fun read(name: String): String? {
        val path = dir / name
        if (!fileSystem.exists(path)) return null
        return fileSystem.read(path) { readUtf8() }
    }

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        fileSystem.createDirectories(dir)
        val tmp = dir / "$name.tmp"
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.atomicMove(tmp, dir / name)
    }

    override fun list(): List<String> = if (fileSystem.exists(dir)) {
        fileSystem.list(dir).map { it.name }.filterNot { it.endsWith(".tmp") }
    } else {
        emptyList()
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.FileDayHistoryStoreTest'`

Expected: PASS, all eight tests.

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr. `:shared`'s remaining history tests passing unchanged is the evidence that the shipping apps' behaviour did not move.

- [ ] **Step 9: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`. (It launches the app; quit it — this is build verification.)

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts \
        core/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt \
        core/src/commonMain/kotlin/fr/dayview/app/OkioHistoryFileSystem.kt \
        core/src/jvmTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt \
        shared/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt \
        shared/src/commonTest/kotlin/fr/dayview/app/FakeHistoryFileSystem.kt \
        shared/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt
git commit -m "refactor(core): make the day archive reachable from the core module

The file-per-day store and the filesystem it writes through move into the
core module, where every platform can reach them, and gain an Okio-backed
implementation that takes its directory as an argument. The desktop and
Android apps keep their own filesystem and their own paths."
```

---

### Task 2: Stop the archive from losing the day's engaged time

**Files:**
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt`, `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes (pre-existing): `StoredPresence(dayKey, presence, session)`, `PresencePersistence.load()`, `PresenceCoordinator.restore(presence, session, dayKey)`, `PresenceAccumulator.restore(intervals, dayKey)`.
- Produces, for Task 3: `DayViewSession(…, restoreDayKey: Long = -1L)` — the last constructor parameter, after `presencePersistence`.

**Why this task exists.** Phase 11 chose to apply the day-staleness rule at *read* time: `MacosPresencePersistence.load()` returns an empty `StoredPresence()` when the stored day is not today. That was safe precisely because native archiving went nowhere — and its spec said so, recording that "when native history archiving lands, the seeding should switch to the JVM's shape."

Task 3 makes archiving real, and that turns the read-time filter into data loss: on a cold launch the next day, the controller is seeded with empty presence, and `maybeArchivePreviousDay()` builds the previous day's record from that state (`toHistoryRecord` copies `focusPresenceIntervals`/`focusSessionIntervals` straight out of it). The archive would be written without the day's mint arcs or its focus total — losing them at the very moment we start keeping them.

**The shape to copy** is the JVM's, in `shared/src/desktopMain/kotlin/fr/dayview/app/Main.kt`: load the intervals raw, and restore the accumulator with the **stored** day key, not today's. The accumulators then clear themselves on the first tick, because `PresenceAccumulator.observe` resets when the day key it is given differs from the one it was restored with.

**The trap:** seeding yesterday's intervals under *today's* key would make yesterday's arcs render as today's — exactly what the read-time filter was preventing. The stored day key must travel with the intervals.

---

- [ ] **Step 1: Write the failing test**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`, at the end of the class. The file already has a `FakePresencePersistence` and the `now = { clock }` seam.

```kotlin
    @Test
    fun staleIntervalsStayAvailableToTheArchiveWithoutRenderingAsToday() = runTest {
        // A relaunch the day after a session. The intervals must survive in state — that is
        // what lets the controller's archival capture the day that ended with its engaged
        // time intact — while contributing nothing to today.
        val dayStart = dayWindow(Instant.fromEpochMilliseconds(1_700_042_400_000L), 0, 1439).first
        val now = dayStart + 10.hours
        val yesterdayKey = dayKeyOf(now) - 1
        val stale = listOf(FocusPresenceInterval(dayStart - 3.hours, dayStart - 2.hours))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = now,
            initialFocusPresenceIntervals = stale,
            initialFocusSessionIntervals = stale,
        )
        val session = DayViewSession(controller, backgroundScope, now = { now }, restoreDayKey = yesterdayKey)

        assertEquals(stale, controller.stateFlow.value.focusPresenceIntervals, "the archive still needs them")

        // Every projection clips to the day window, so a closed day's intervals draw nothing.
        val snapshot = session.currentSnapshot()
        assertTrue(snapshot.focusArcs.isEmpty(), "yesterday's intervals must not draw as today's arcs")
        assertEquals("", snapshot.focusTotalLabel)
    }

    @Test
    fun aNewSessionDoesNotInheritThePreviousDaysIntervals() = runTest {
        // What restoreDayKey actually buys: the accumulator holds the seeded intervals under
        // the day they belong to, so the first tick of a new day's session discards them
        // instead of committing them as today's runs. Seeded under today's key they would be
        // kept, and yesterday's engaged time would reappear inside today's window.
        val dayStart = dayWindow(Instant.fromEpochMilliseconds(1_700_042_400_000L), 0, 1439).first
        var clock = dayStart + 10.hours
        val yesterdayKey = dayKeyOf(clock) - 1
        val stale = listOf(FocusPresenceInterval(dayStart - 3.hours, dayStart - 2.hours))
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                onGoalApps = setOf(AppRef("com.on.goal", "On Goal")),
            ),
            initialNow = clock,
            initialFocusPresenceIntervals = stale,
            initialFocusSessionIntervals = stale,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
            restoreDayKey = yesterdayKey,
        )
        session.startFocus("Ship it")

        repeat(180) {
            clock += 1.seconds
            session.tick()
        }
        runCurrent()

        val accrued = controller.stateFlow.value.focusPresenceIntervals
        assertTrue(accrued.isNotEmpty(), "today's own presence should have accrued")
        assertTrue(
            stale.none { it in accrued },
            "the previous day's intervals must not survive into today's, was $accrued",
        )
    }
```

Add `kotlin.time.Duration.Companion.hours` to the imports if the compiler asks for it. `FakeFrontmostProvider` is an existing private class in this test file.

**Note for the implementer:** the first test deliberately does **not** assert that a tick clears `focusPresenceIntervals`. It does not, and it does not need to: `PresenceCoordinator.observe` only forwards to the accumulator while a focus is active or has just ended, so an idle tick leaves the seeded intervals in place — and `focusArcsState`/`focusedToday` clip to the day window, so a closed day's intervals are invisible anyway. The JVM app has the identical structure. What `restoreDayKey` protects against is the *second* test's scenario.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: compilation failure — no parameter `restoreDayKey`.

- [ ] **Step 3: Let the session restore under the stored day**

In `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`, add a final constructor parameter after `presencePersistence`:

```kotlin
    private val presencePersistence: PresencePersistence = NoopPresencePersistence,
    // The day the seeded presence intervals belong to. -1 means "no stored day" and falls back
    // to the current one. Restoring yesterday's intervals under today's key would make them
    // render as today's arcs; the accumulators clear them on the first tick of the new day.
    private val restoreDayKey: Long = -1L,
) {
```

and in `init`, replace:

```kotlin
            presence.restore(state.focusPresenceIntervals, state.focusSessionIntervals, dayKeyOf(state.now))
```

with:

```kotlin
            presence.restore(
                state.focusPresenceIntervals,
                state.focusSessionIntervals,
                restoreDayKey.takeIf { it >= 0 } ?: dayKeyOf(state.now),
            )
```

- [ ] **Step 4: Stop filtering at read time**

In `core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt`, replace the body of `load()`:

```kotlin
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
```

with:

```kotlin
    override suspend fun load(): StoredPresence {
        val prefs = dataStore.data.first()
        // Returned raw, with the day they belong to: the desktop app's shape. Discarding a
        // stale day here would be simpler, but the controller archives the previous day from
        // this same seeded state, so filtering would write an archive with no engaged time.
        // Staleness is handled downstream — the accumulators reset on a day-key change.
        return StoredPresence(
            dayKey = prefs[presenceDayKey] ?: -1L,
            presence = decodeFocusPresence(prefs[presenceKey].orEmpty()),
            session = decodeFocusPresence(prefs[sessionKey].orEmpty()),
        )
    }
```

The `Clock` import becomes unused — remove it.

- [ ] **Step 5: Pass the stored day through the native graph**

In `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`, add to the `DayViewSession(...)` call, after `presencePersistence = preferences.presencePersistence,`:

```kotlin
            restoreDayKey = stored.dayKey,
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`

Expected: PASS, including the existing presence tests — they construct `DayViewSession` without `restoreDayKey`, so the `-1L` default keeps them on today's key.

- [ ] **Step 7: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 8: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 9: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/MacosPresencePersistence.kt \
        core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt \
        core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "fix(macos): keep the day's engaged time available to the archive

Stored presence was discarded at load when it belonged to an earlier day,
which was harmless while nothing archived it. It is now returned with the
day it belongs to, and the accumulators clear it on the first tick of the
new day — so the record written for the day that ended still carries its
engaged time."
```

---

### Task 3: Give the native app its archive

**Files:**
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryArchivingTest.kt` (**create**)

**Interfaces:**
- Consumes from Task 1: `FileDayHistoryStore(fs)`, `OkioHistoryFileSystem(fileSystem, dir)`, `HistoryFileSystem`.
- Consumes (pre-existing): `DayViewController(preferences, scope, initialSnapshot, …, history = …)`, `DayViewController.tick(now)`, `DayHistoryCodec.decode`.
- Produces: nothing for later tasks.

**Context:** the controller archives from `init` (a cold launch on a later day) and from `tick` when the day key changes (an app left open across midnight). Both already work; they have simply been writing into the default `InMemoryDayHistoryStore`. This task supplies a real one — and adds the `:core` test that would have caught the gap.

---

- [ ] **Step 1: Write the test**

Create `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryArchivingTest.kt`. `:core`'s controller tests live in per-subject files (`PomodoroTest`, `DetoursTest`, `FocusArcsTest`…), so archiving gets its own rather than being appended to `DayViewCoreTest`, which covers the primitives-only facade.

This is a `commonTest` (no Okio — that dependency is jvmTest-only in `:core`), so it uses a small stub. The Okio implementation is covered by Task 1's tests; what is under test here is that the controller reaches a real store at all.

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private class RecordingHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()

    override fun read(name: String): String? = files[name]

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        files[name] = text
    }

    override fun list(): List<String> = files.keys.toList()
}

class DayHistoryArchivingTest {
    @Test
    fun crossingMidnightArchivesTheDayThatEnded() = runTest {
        val fs = RecordingHistoryFileSystem()
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
            history = FileDayHistoryStore(fs),
        )
        // Give the day something worth archiving, so the record cannot be mistaken for the
        // empty one an unconfigured day would produce.
        controller.setGoalTitle("Ship it")
        controller.addDetour("email", 15, "inbox")
        val endedDayKey = dayKeyOf(start)

        controller.tick(start + 1.days)
        runCurrent()

        val archived = fs.files[endedDayKey.toString()]
        assertNotNull(archived, "the day that ended should have been archived")
        val record = DayHistoryCodec.decode(archived)
        assertEquals(endedDayKey, record?.dayKey)
        assertEquals("Ship it", record?.goalTitle)
        assertEquals(1, record?.detours?.size, "the day's detours belong in its record")
    }

    @Test
    fun theDayInProgressIsNeverArchived() = runTest {
        val fs = RecordingHistoryFileSystem()
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(startMinutes = 0, endMinutes = 1439),
            initialNow = start,
            history = FileDayHistoryStore(fs),
        )
        controller.setGoalTitle("Ship it")

        controller.tick(start + 1.hours)
        runCurrent()

        assertEquals(emptyMap(), fs.files, "today is still in progress and must not be archived")
    }
}
```

Add `kotlin.time.Duration.Companion.hours` to the imports if the compiler asks for it.

- [ ] **Step 2: Run the tests and prove they are not vacuous**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayHistoryArchivingTest'`

Expected: PASS — and that is not a mistake. These tests pass as soon as a real store is injected, which the test itself does; they exist to pin an archiving contract `:core` never asserted. Prove `crossingMidnightArchivesTheDayThatEnded` genuinely exercises the path by temporarily changing `history = FileDayHistoryStore(fs)` to `history = InMemoryDayHistoryStore()`: it must then FAIL on the `assertNotNull`. Restore the line afterwards and report both results.

- [ ] **Step 3: Build the store in the native entry point**

In `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`, add these imports next to the existing Okio and Foundation ones:

```kotlin
import okio.FileSystem
import okio.Path.Companion.toPath
```

Then inside `create()`, immediately after the `val stored = runBlocking { … }` line that loads the presence, insert:

```kotlin
        // The archive lives beside the preferences file. maybeArchivePreviousDay already runs
        // from the controller's init and from tick on a day change; until now both wrote into
        // the default in-memory store and were lost at quit.
        val history = FileDayHistoryStore(
            OkioHistoryFileSystem(
                FileSystem.SYSTEM,
                "${NSHomeDirectory()}/Library/Application Support/DayView/history".toPath(),
            ),
        )
```

and add the argument to the `DayViewController(...)` call, after `initialSnapshot`:

```kotlin
            history = history,
```

- [ ] **Step 4: Verify the native build**

Run: `./gradlew :core:runMacNative`

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, no ktlint findings, no stderr.

- [ ] **Step 6: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt \
        core/src/commonTest/kotlin/fr/dayview/app/DayHistoryArchivingTest.kt
git commit -m "fix(macos): archive the day instead of discarding it

The native app built its controller without a history store, so it fell back
to the in-memory default: every rollover wrote the day that ended into a map
that died with the process. It now writes to a file per day under the
application support directory."
```

---

### Task 4: The parity checklist

**Files:**
- Modify: `docs/superpowers/macos-native-parity-checklist.md`

**Interfaces:** none — documentation only.

---

- [ ] **Step 1: Replace the History table's archiving row**

In `docs/superpowers/macos-native-parity-checklist.md`, in the History table, replace:

```markdown
| Day-rollover archiving wired natively | **PORT** | ⚠️ Verify first: `DayViewNative.create()` passes no history store — native rollover may not archive today. Behavior-layer item. When this lands, presence seeding must switch to the JVM's shape at the same time: seed the stored intervals raw and let the accumulators reset themselves on the day-key change, otherwise the archived record for the previous day carries no presence intervals |
```

(Both halves of that row are done: the store is wired in Task 3, and the presence-seeding switch it demanded is Task 2.)

with:

```markdown
| Week screen (mini rings) + day screen (date label, back nav) | **PORT** | 13b. The week-grid logic (`HistoryWeek.kt`) is already in `:core`; the storage landed in 13a |
```

and delete the table's existing week/day row, which the replacement above supersedes:

```markdown
| Week screen (mini rings) + day screen (date label, back nav) | **PORT** | After the visual pass (reuses ring rendering) |
```

- [ ] **Step 2: Record what shipped**

Add a row at the end of the "Done" table:

```markdown
| History storage: `HistoryFileSystem`/`FileDayHistoryStore` moved to `:core` with an Okio implementation, native archiving wired (it was writing to an in-memory store and losing every day), presence seeded raw so the archive keeps the day's engaged time | 13a |
```

- [ ] **Step 3: Record the deliberate duplication**

Add this line to the Parking Lot:

```markdown
- Two `HistoryFileSystem` implementations coexist: `JvmHistoryFileSystem` (`:shared`, desktop + Android, itself duplicated across the two source sets) and `OkioHistoryFileSystem` (`:core`). Okio runs on every target, so one could serve all three — but converging them rewrites the shipping apps' storage I/O, which 13a deliberately did not touch. Revisit after cutover.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/macos-native-parity-checklist.md
git commit -m "docs: record the native history archive against the parity checklist"
```

- [ ] **Step 5: Manual smoke test (run by the maintainer)**

Archiving is invisible in the native UI until 13b lands, so this is verified on disk.

1. Run the native app for a while today, then quit it.
2. On the following day, launch it again.
3. Confirm a file appears in `~/Library/Application Support/DayView/history/` named with the previous day's epoch-day number (a five-digit number — `date -j +%s` divided by 86400 gives today's), and that it contains that day's goal title and detours.
4. If a focus session ran that day, confirm the record's `presence=` and `session=` lines are not empty — that is the Task 2 fix, and the part no automated test can prove end to end.
5. Confirm no `.tmp` file is left in that directory.

---

## Notes for the reviewer

- **This is the project's second `:shared`→`:core` migration**, after the drift/resume detectors in phase 10a, and the pattern the sync phase will reuse at a larger scale. The shape to check is that nothing platform-specific followed the move: `:core` still has no `expect`/`actual` and no `jvmMain`/`androidMain` source set.
- **`FileFocusContributionStore` staying behind is deliberate**, not an oversight: it depends on `fr.dayview.app.sync.FocusContributionMapper`, so moving it would drag the sync subsystem into `:core` as a side effect of a history change.
- **`atomicMove` differs from the JVM implementation it mirrors.** `File.renameTo` returns `false` on failure and `JvmHistoryFileSystem` retries after deleting the target; Okio's `atomicMove` throws. `FileDayHistoryStore.write` returns early when the target exists, so the overwrite path is unreachable through the store — but the native app now depends on this implementation, and the difference deserves a look rather than an assumption.
- **Task 3's tests pass on first run by design.** They are not a TDD red step: they pin a contract `:core` never asserted. The step therefore requires proving the archiving test fails with the in-memory store injected, which is the only way to show it is not vacuous.
- **Task 2 was missed by the spec and added during the plan's self-review.** Phase 11 recorded the requirement on the parity checklist — "when native history archiving lands, presence seeding must switch to the JVM's shape at the same time" — and the spec for this phase did not carry it. Without Task 2, Task 3 would start writing archives with no engaged time in them, losing the day's mint arcs at the moment we begin keeping the rest. Worth confirming that Task 2 genuinely lands before Task 3, and that the accumulators do clear on the first tick rather than rendering yesterday's arcs as today's.
