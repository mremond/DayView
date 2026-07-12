# Day History & Week Overview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a faithful per-day snapshot of the ring and add a week overview (7 clickable mini-rings) that drills into a read-only replay of any past day.

**Architecture:** A new `commonMain` persistence layer stores one record per local day (`dayKey`), encoded in the existing hand-rolled line style, one file per day via a small `expect`/`actual` file-system seam (`appContext.filesDir/history` on Android, `~/.dayview/history` on desktop). The `DayViewController` archives the previous day's persisted state when it observes a day rollover. A pure `record.toFrozenUiState()` re-runs the existing projection so `MiniRing` (grid) and `CountdownCircle` (drill-in) render historical days with zero new drawing logic. Navigation extends the existing `DayViewDestination` enum.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `kotlin.io.encoding.Base64` (stdlib), `kotlin.test`, Compose UI test (`runComposeUiTest`, desktopTest).

## Global Constraints

- ktlint is enforced — run `./gradlew ktlintFormat` before each commit; the pre-commit gate is `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Both platforms (Android + Compose Desktop) are covered from `commonMain`; only file IO is `expect`/`actual`. There is NO native SwiftUI target.
- Commit messages/PRs: English, no Claude/Anthropic/AI reference, no "test plan"/"verification" section, no reference to `docs/superpowers/`.
- Compose UI test constraints (project convention): NEVER assert `stringResource` text — use test tags and seeded data. `assertExists` is a member (no import). Test pure screens, not `DayViewApp`. Use the existing `seededController(snapshot, now)` / `midWindowNow()` helpers in desktopTest.
- New user-facing test tags go in `DayViewTestTags` (commonMain) so production and tests share one source of truth.
- Follow existing serialization idioms (`encodeDetours`/`decodeDetours`, `encodeFocusPresence`/`decodeFocusPresence`). No JSON, no SQL, no new Gradle dependency.

---

## File Structure

**Create (commonMain):**
- `DayHistoryRecord.kt` — the archived record data class + `toFrozenUiState()`.
- `DayHistoryCodec.kt` — versioned line-based encode/decode (pure, defensive).
- `DayHistoryStore.kt` — `DayHistoryStore` interface, `HistoryFileSystem` interface, `FileDayHistoryStore`, `InMemoryDayHistoryStore`, `createDayHistoryStore()`, `expect fun createHistoryFileSystem()`.
- `HistoryWeekScreen.kt` — week grid of `MiniRing`.
- `MiniRing.kt` — lightweight Canvas ring.
- `HistoryDayScreen.kt` — read-only drill-in reusing `CountdownCircle`.

**Create (androidMain / desktopMain):**
- `DayHistoryStore.android.kt` / `DayHistoryStore.desktop.kt` — `actual fun createHistoryFileSystem()`.

**Modify (commonMain):**
- `DayViewController.kt` — add `history` param, `selectedHistoryDay`, `openHistory`/`openHistoryDay`/`closeHistory`, rollover archival.
- `DayViewController.kt` — add `HISTORY` to `DayViewDestination`.
- `App.kt` — route `HISTORY`, wire back handling, pass real store.
- `DayViewTodayScreen.kt` — add a history icon entry point.
- `DayViewTestTags.kt` — add history tags.

**Modify (androidMain / desktopMain):**
- `DayViewPreferences.kt` (android) / `Main.kt` (desktop) — construct the real store and pass to the controller.

**Test (commonTest / desktopTest):**
- `commonTest/DayHistoryCodecTest.kt`, `commonTest/DayHistoryRecordTest.kt`, `commonTest/FileDayHistoryStoreTest.kt`, `commonTest/DayHistoryRolloverTest.kt`
- `desktopTest/HistoryWeekScreenTest.kt`, `desktopTest/HistoryDayScreenTest.kt`, `desktopTest/HistoryNavigationTest.kt`

---

## Task 1: `DayHistoryRecord` + `toFrozenUiState()`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryRecordTest.kt`

**Interfaces:**
- Consumes: `DayViewUiState` (internal, `DayViewController.kt`), `BusyInterval`, `CalendarInfo`, `NetTimeSettings` (`CalendarSource.kt`), `FocusPresenceInterval` (`PresenceAccumulator.kt`), `DetourEpisode`, `dayKeyOf` (`Detours.kt`), `CleanSessionLedger` (`CleanFocusSessions.kt`).
- Produces:
  - `internal data class DayHistoryRecord(dayKey, startMinutes, endMinutes, focusIntention, busyIntervals, calendarNames, netTimeSettings, focusPresenceIntervals, detours, cleanSessions, pomodoroMinutes, pomodoroEnd, goalTitle, goalDeadline, goalStart)`
  - `internal fun DayHistoryRecord.toFrozenUiState(timeZone: TimeZone = TimeZone.currentSystemDefault()): DayViewUiState`
  - `internal fun DayViewUiState.toHistoryRecord(dayKey: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): DayHistoryRecord`

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant

class DayHistoryRecordTest {
    private val tz = TimeZone.UTC
    private val dayKey = LocalDate(2026, 5, 4).toEpochDays().toLong()

    private fun instantAt(hour: Int, minute: Int): Instant =
        LocalDate.fromEpochDays(dayKey.toInt()).atTime(LocalTime(hour, minute)).toInstant(tz)

    @Test
    fun frozenStateReprojectsBusyArcsOnTheRecordedDay() {
        val record = DayHistoryRecord(
            dayKey = dayKey,
            startMinutes = 8 * 60,
            endMinutes = 18 * 60,
            focusIntention = "Ship the plan",
            busyIntervals = listOf(
                BusyInterval(instantAt(9, 0), instantAt(10, 30), listOf("Standup"), "cal-a"),
            ),
            calendarNames = mapOf("cal-a" to "Work"),
            netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-a")),
            focusPresenceIntervals = emptyList(),
            detours = emptyList(),
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 2),
            pomodoroMinutes = 25,
            pomodoroEnd = null,
            goalTitle = "",
            goalDeadline = null,
            goalStart = null,
        )

        val state = record.toFrozenUiState(tz)

        // The frozen "now" lands on the recorded day, not today.
        assertEquals(dayKey, dayKeyOf(state.now, tz))
        // Busy projection runs through the existing pipeline and yields one arc.
        assertEquals(1, state.busyBlockArcsState.size)
        // The day is fully elapsed in the replay (now == window end).
        assertTrue(state.dayProgress.isFinished)
        assertEquals(2, state.cleanSessionsToday)
    }

    @Test
    fun recordRoundTripsThroughUiState() {
        val record = DayHistoryRecord(
            dayKey = dayKey,
            startMinutes = 9 * 60,
            endMinutes = 17 * 60,
            focusIntention = "Focus",
            busyIntervals = emptyList(),
            calendarNames = emptyMap(),
            netTimeSettings = NetTimeSettings(),
            focusPresenceIntervals = listOf(FocusPresenceInterval(instantAt(9, 0), instantAt(9, 30))),
            detours = listOf(DetourEpisode(instantAt(11, 0), instantAt(11, 15), "slack")),
            cleanSessions = CleanSessionLedger(dayKey = dayKey, cleanToday = 1),
            pomodoroMinutes = 30,
            pomodoroEnd = null,
            goalTitle = "Deliver",
            goalDeadline = instantAt(17, 0),
            goalStart = instantAt(9, 0),
        )

        val back = record.toFrozenUiState(tz).toHistoryRecord(dayKey, tz)

        assertEquals(record, back)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryRecordTest"`
Expected: FAIL — `DayHistoryRecord` / `toFrozenUiState` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package fr.dayview.app

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * A faithful, immutable snapshot of one local day's ring inputs. Holds exactly the
 * day-scoped fields of [DayViewUiState]; the ring is re-derived from these via the same
 * projection used live, so history renders identically without duplicated drawing logic.
 */
internal data class DayHistoryRecord(
    val dayKey: Long,
    val startMinutes: Int,
    val endMinutes: Int,
    val focusIntention: String,
    val busyIntervals: List<BusyInterval>,
    val calendarNames: Map<String, String>,
    val netTimeSettings: NetTimeSettings,
    val focusPresenceIntervals: List<FocusPresenceInterval>,
    val detours: List<DetourEpisode>,
    val cleanSessions: CleanSessionLedger,
    val pomodoroMinutes: Int,
    val pomodoroEnd: Instant?,
    val goalTitle: String,
    val goalDeadline: Instant?,
    val goalStart: Instant?,
)

/** The instant at the end of the record's day window, used as the frozen "now". */
private fun DayHistoryRecord.frozenNow(timeZone: TimeZone): Instant =
    LocalDate.fromEpochDays(dayKey.toInt())
        .atTime(LocalTime(endMinutes / 60, endMinutes % 60))
        .toInstant(timeZone)

/**
 * Rebuild a [DayViewUiState] pinned to the recorded day, with `now` at the day's end so
 * the ring reads as a completed day. `showSeconds = false` keeps the replay at minute
 * precision (deterministic). Only day-scoped fields are meaningful; everything else takes
 * its default.
 */
internal fun DayHistoryRecord.toFrozenUiState(
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayViewUiState = DayViewUiState(
    now = frozenNow(timeZone),
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    showSeconds = false,
    soundSettings = SoundSettings(),
    goalTitle = goalTitle,
    goalDeadlineText = "",
    goalDeadline = goalDeadline,
    goalStartText = "",
    goalStart = goalStart,
    pomodoroMinutes = pomodoroMinutes,
    pomodoroEnd = pomodoroEnd,
    focusIntention = focusIntention,
    netTimeSettings = netTimeSettings,
    availableCalendars = calendarNames.map { CalendarInfo(it.key, it.value) },
    busyIntervals = busyIntervals,
    focusPresenceIntervals = focusPresenceIntervals,
    detoursDayKey = dayKey,
    detours = detours,
    cleanSessions = cleanSessions,
)

/**
 * Extract the day-scoped subset of [DayViewUiState] for [dayKey]. Focus-presence
 * intervals are clipped to the record's day window so a running presence log doesn't
 * bleed into an archived day.
 */
internal fun DayViewUiState.toHistoryRecord(
    dayKey: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DayHistoryRecord {
    val windowStart = LocalDate.fromEpochDays(dayKey.toInt())
        .atTime(LocalTime(startMinutes / 60, startMinutes % 60))
        .toInstant(timeZone)
    val windowEnd = LocalDate.fromEpochDays(dayKey.toInt())
        .atTime(LocalTime(endMinutes / 60, endMinutes % 60))
        .toInstant(timeZone)
    return DayHistoryRecord(
        dayKey = dayKey,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        focusIntention = focusIntention,
        busyIntervals = busyIntervals,
        calendarNames = availableCalendars.associate { it.id to it.displayName },
        netTimeSettings = netTimeSettings,
        focusPresenceIntervals = focusPresenceIntervals.filter { it.end > windowStart && it.start < windowEnd },
        detours = detours,
        cleanSessions = cleanSessions,
        pomodoroMinutes = pomodoroMinutes,
        pomodoroEnd = pomodoroEnd,
        goalTitle = goalTitle,
        goalDeadline = goalDeadline,
        goalStart = goalStart,
    )
}
```

Note: the round-trip test uses records whose `focusPresenceIntervals` already sit inside the window and whose `availableCalendars` map is empty or exactly `calendarNames`, so `toHistoryRecord(toFrozenUiState())` is the identity. Keep the test data within the window.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryRecordTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryRecordTest.kt
git commit -m "Add DayHistoryRecord and frozen-state projection"
```

---

## Task 2: `DayHistoryCodec` (versioned, defensive)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryCodecTest.kt`

**Interfaces:**
- Consumes: `DayHistoryRecord` (Task 1); `encodeDetours`/`decodeDetours` (`Detours.kt`), `encodeFocusPresence`/`decodeFocusPresence` (`PresenceAccumulator.kt`).
- Produces:
  - `internal object DayHistoryCodec { const val VERSION_HEADER = "dayhistory v1"; fun encode(record: DayHistoryRecord): String; fun decode(text: String): DayHistoryRecord? }`

Format: line 1 is `dayhistory v1`; remaining lines are `key=value`. Every free-text or newline-bearing value (intention, busy list, presence list, detour list, calendar names, goal title, calendar id set) is Base64-encoded so it never collides with the `\n`/`=` structure. A missing header, unknown version, missing key, or unparseable number makes `decode` return `null` (treated downstream as an empty day).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DayHistoryCodecTest {
    private fun sample() = DayHistoryRecord(
        dayKey = 20_000L,
        startMinutes = 8 * 60,
        endMinutes = 18 * 60,
        focusIntention = "Line one\nwith a newline, and = signs",
        busyIntervals = listOf(
            BusyInterval(Instant.fromEpochMilliseconds(1_000L), Instant.fromEpochMilliseconds(2_000L), listOf("A"), "cal-a"),
        ),
        calendarNames = mapOf("cal-a" to "Work = life"),
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-a", "cal-b")),
        focusPresenceIntervals = listOf(FocusPresenceInterval(Instant.fromEpochMilliseconds(3_000L), Instant.fromEpochMilliseconds(4_000L))),
        detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(5_000L), Instant.fromEpochMilliseconds(6_000L), "slack")),
        cleanSessions = CleanSessionLedger(dayKey = 20_000L, cleanToday = 3, streakDays = 5, streakLastDayKey = 20_000L),
        pomodoroMinutes = 25,
        pomodoroEnd = Instant.fromEpochMilliseconds(7_000L),
        goalTitle = "Deliver",
        goalDeadline = Instant.fromEpochMilliseconds(8_000L),
        goalStart = Instant.fromEpochMilliseconds(500L),
    )

    @Test
    fun encodeThenDecodeIsLossless() {
        val record = sample()
        assertEquals(record, DayHistoryCodec.decode(DayHistoryCodec.encode(record)))
    }

    @Test
    fun encodedTextStartsWithVersionHeader() {
        assertEquals("dayhistory v1", DayHistoryCodec.encode(sample()).lineSequence().first())
    }

    @Test
    fun unknownVersionDecodesToNull() {
        assertNull(DayHistoryCodec.decode("dayhistory v999\ndayKey=1"))
    }

    @Test
    fun garbageDecodesToNull() {
        assertNull(DayHistoryCodec.decode("not a record at all"))
        assertNull(DayHistoryCodec.decode(""))
    }

    @Test
    fun missingRequiredKeyDecodesToNull() {
        // Header present but no dayKey line.
        assertNull(DayHistoryCodec.decode("dayhistory v1\nstart=480"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryCodecTest"`
Expected: FAIL — `DayHistoryCodec` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package fr.dayview.app

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

@OptIn(ExperimentalEncodingApi::class)
internal object DayHistoryCodec {
    const val VERSION_HEADER = "dayhistory v1"
    private const val NONE = -1L

    private fun enc(text: String): String = Base64.encode(text.encodeToByteArray())
    private fun dec(b64: String): String = Base64.decode(b64).decodeToString()

    private fun encodeCalendarNames(names: Map<String, String>): String =
        names.entries.joinToString("\n") { "${enc(it.key)},${enc(it.value)}" }

    private fun decodeCalendarNames(encoded: String): Map<String, String> =
        encoded.split("\n").mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split(",", limit = 2)
            if (parts.size != 2) null else dec(parts[0]) to dec(parts[1])
        }.toMap()

    fun encode(record: DayHistoryRecord): String = buildString {
        appendLine(VERSION_HEADER)
        appendLine("dayKey=${record.dayKey}")
        appendLine("start=${record.startMinutes}")
        appendLine("end=${record.endMinutes}")
        appendLine("intention=${enc(record.focusIntention)}")
        appendLine("netEnabled=${record.netTimeSettings.enabled}")
        appendLine("netCalendars=${enc(record.netTimeSettings.includedCalendarIds.joinToString("\n"))}")
        appendLine("busy=${enc(encodeBusyIntervals(record.busyIntervals))}")
        appendLine("calNames=${enc(encodeCalendarNames(record.calendarNames))}")
        appendLine("presence=${enc(encodeFocusPresence(record.focusPresenceIntervals))}")
        appendLine("detours=${enc(encodeDetours(record.detours))}")
        appendLine("cleanDay=${record.cleanSessions.dayKey}")
        appendLine("cleanToday=${record.cleanSessions.cleanToday}")
        appendLine("streakDays=${record.cleanSessions.streakDays}")
        appendLine("streakLastDay=${record.cleanSessions.streakLastDayKey}")
        appendLine("pomodoroMinutes=${record.pomodoroMinutes}")
        appendLine("pomodoroEnd=${record.pomodoroEnd?.toEpochMilliseconds() ?: NONE}")
        appendLine("goalTitle=${enc(record.goalTitle)}")
        appendLine("goalDeadline=${record.goalDeadline?.toEpochMilliseconds() ?: NONE}")
        appendLine("goalStart=${record.goalStart?.toEpochMilliseconds() ?: NONE}")
    }

    fun decode(text: String): DayHistoryRecord? {
        val lines = text.split("\n")
        if (lines.firstOrNull()?.trim() != VERSION_HEADER) return null
        val map = lines.drop(1).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()

        return try {
            fun req(key: String): String = map[key] ?: error("missing $key")
            fun instantOrNull(key: String): Instant? =
                req(key).toLong().takeIf { it != NONE }?.let { Instant.fromEpochMilliseconds(it) }

            DayHistoryRecord(
                dayKey = req("dayKey").toLong(),
                startMinutes = req("start").toInt(),
                endMinutes = req("end").toInt(),
                focusIntention = dec(req("intention")),
                busyIntervals = decodeBusyIntervals(dec(req("busy"))),
                calendarNames = decodeCalendarNames(dec(req("calNames"))),
                netTimeSettings = NetTimeSettings(
                    enabled = req("netEnabled").toBoolean(),
                    includedCalendarIds = dec(req("netCalendars")).split("\n").filter { it.isNotEmpty() }.toSet(),
                ),
                focusPresenceIntervals = decodeFocusPresence(dec(req("presence"))),
                detours = decodeDetours(dec(req("detours"))),
                cleanSessions = CleanSessionLedger(
                    dayKey = req("cleanDay").toLong(),
                    cleanToday = req("cleanToday").toInt(),
                    streakDays = req("streakDays").toInt(),
                    streakLastDayKey = req("streakLastDay").toLong(),
                ),
                pomodoroMinutes = req("pomodoroMinutes").toInt(),
                pomodoroEnd = instantOrNull("pomodoroEnd"),
                goalTitle = dec(req("goalTitle")),
                goalDeadline = instantOrNull("goalDeadline"),
                goalStart = instantOrNull("goalStart"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
```

This references two new busy-interval codec helpers. Add them to `CalendarNetTime.kt` (next to `BusyInterval`), mirroring `encodeDetours`:

```kotlin
/** One busy interval per line: startMillis,endMillis,base64(calendarId),base64(title0|title1|...). */
fun encodeBusyIntervals(intervals: List<BusyInterval>): String = intervals.joinToString("\n") {
    val ids = kotlin.io.encoding.Base64.encode(it.calendarId.encodeToByteArray())
    val titles = kotlin.io.encoding.Base64.encode(it.titles.joinToString("").encodeToByteArray())
    "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},$ids,$titles"
}

fun decodeBusyIntervals(encoded: String): List<BusyInterval> = encoded.split("\n").mapNotNull { line ->
    if (line.isEmpty()) return@mapNotNull null
    val parts = line.split(",", limit = 4)
    val start = parts.getOrNull(0)?.toLongOrNull()
    val end = parts.getOrNull(1)?.toLongOrNull()
    if (parts.size != 4 || start == null || end == null) return@mapNotNull null
    val calendarId = kotlin.io.encoding.Base64.decode(parts[2]).decodeToString()
    val titlesRaw = kotlin.io.encoding.Base64.decode(parts[3]).decodeToString()
    val titles = if (titlesRaw.isEmpty()) emptyList() else titlesRaw.split("")
    BusyInterval(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), titles, calendarId)
}
```

Add `@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)` at the top of `CalendarNetTime.kt` if not already present, or annotate the two functions.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryCodecTest.kt
git commit -m "Add versioned codec for day history records"
```

---

## Task 3: `DayHistoryStore` + file-system seam

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/DayHistoryStore.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DayHistoryStore.desktop.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt`

**Interfaces:**
- Consumes: `DayHistoryRecord` (Task 1), `DayHistoryCodec` (Task 2).
- Produces:
  - `internal interface DayHistoryStore { suspend fun write(record: DayHistoryRecord); suspend fun read(dayKey: Long): DayHistoryRecord?; suspend fun listDays(range: LongRange): List<Long> }`
  - `internal interface HistoryFileSystem { fun read(name: String): String?; fun writeAtomic(name: String, text: String); fun list(): List<String> }`
  - `internal class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore`
  - `internal class InMemoryDayHistoryStore : DayHistoryStore` (also usable as a test/fallback double)
  - `internal fun createDayHistoryStore(): DayHistoryStore`
  - `internal expect fun createHistoryFileSystem(): HistoryFileSystem?`

Write is idempotent: if a record already exists for `dayKey`, `write` leaves it untouched (never clobbers an earlier archive).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()
    override fun read(name: String): String? = files[name]
    override fun writeAtomic(name: String, text: String) { files[name] = text }
    override fun list(): List<String> = files.keys.toList()
}

class FileDayHistoryStoreTest {
    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun writeThenReadRoundTrips() = runTest {
        val store = FileDayHistoryStore(FakeHistoryFileSystem())
        store.write(record(100L))
        assertEquals(record(100L), store.read(100L))
    }

    @Test
    fun readMissingDayIsNull() = runTest {
        assertNull(FileDayHistoryStore(FakeHistoryFileSystem()).read(999L))
    }

    @Test
    fun corruptFileReadsAsNull() = runTest {
        val fs = FakeHistoryFileSystem().apply { files["7"] = "garbage" }
        assertNull(FileDayHistoryStore(fs).read(7L))
    }

    @Test
    fun listDaysFiltersToRangeAndSorts() = runTest {
        val fs = FakeHistoryFileSystem().apply {
            files["10"] = ""; files["20"] = ""; files["30"] = ""; files["not-a-number"] = ""
        }
        val store = FileDayHistoryStore(fs)
        assertEquals(listOf(10L, 20L), store.listDays(5L..25L))
    }

    @Test
    fun writeIsIdempotentAndDoesNotClobber() = runTest {
        val fs = FakeHistoryFileSystem()
        val store = FileDayHistoryStore(fs)
        store.write(record(100L))
        val first = fs.files["100"]
        // A second write for the same day (even with different content) must not overwrite.
        store.write(record(100L).copy(focusIntention = "changed"))
        assertEquals(first, fs.files["100"])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FileDayHistoryStoreTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Write the common implementation**

`DayHistoryStore.kt`:

```kotlin
package fr.dayview.app

internal interface DayHistoryStore {
    suspend fun write(record: DayHistoryRecord)
    suspend fun read(dayKey: Long): DayHistoryRecord?
    suspend fun listDays(range: LongRange): List<Long>
}

/** Platform file access for the history directory. `name` is the bare `dayKey` string. */
internal interface HistoryFileSystem {
    fun read(name: String): String?
    fun writeAtomic(name: String, text: String)
    fun list(): List<String>
}

/** Null when no writable location is available (e.g. Android before the app context is set). */
internal expect fun createHistoryFileSystem(): HistoryFileSystem?

internal class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore {
    override suspend fun write(record: DayHistoryRecord) {
        val name = record.dayKey.toString()
        if (fs.read(name) != null) return // idempotent: never clobber an earlier archive
        fs.writeAtomic(name, DayHistoryCodec.encode(record))
    }

    override suspend fun read(dayKey: Long): DayHistoryRecord? =
        fs.read(dayKey.toString())?.let { DayHistoryCodec.decode(it) }

    override suspend fun listDays(range: LongRange): List<Long> =
        fs.list().mapNotNull { it.toLongOrNull() }.filter { it in range }.sorted()
}

internal class InMemoryDayHistoryStore : DayHistoryStore {
    private val records = mutableMapOf<Long, DayHistoryRecord>()
    override suspend fun write(record: DayHistoryRecord) { records.putIfAbsent(record.dayKey, record) }
    override suspend fun read(dayKey: Long): DayHistoryRecord? = records[dayKey]
    override suspend fun listDays(range: LongRange): List<Long> =
        records.keys.filter { it in range }.sorted()
}

internal fun createDayHistoryStore(): DayHistoryStore =
    createHistoryFileSystem()?.let { FileDayHistoryStore(it) } ?: InMemoryDayHistoryStore()
```

- [ ] **Step 4: Write the Android actual**

`DayHistoryStore.android.kt` — reuse the same global `appContext` used by `createCalendarSource()`:

```kotlin
package fr.dayview.app

import java.io.File

internal actual fun createHistoryFileSystem(): HistoryFileSystem? =
    appContext?.let { JvmHistoryFileSystem(File(it.filesDir, "history")) }
```

- [ ] **Step 5: Write the desktop actual + shared JVM implementation**

Because Android and desktop are both JVM but share no intermediate source set, put the concrete `JvmHistoryFileSystem` in `DayHistoryStore.desktop.kt` AND `DayHistoryStore.android.kt` is only the factory above — so define `JvmHistoryFileSystem` once per set. To avoid duplication, place the class body in each platform file identically. Desktop file:

```kotlin
package fr.dayview.app

import java.io.File

internal actual fun createHistoryFileSystem(): HistoryFileSystem =
    JvmHistoryFileSystem(File(System.getProperty("user.home"), ".dayview/history"))

/** File-per-day store under [dir], with atomic writes (tmp + rename). */
internal class JvmHistoryFileSystem(private val dir: File) : HistoryFileSystem {
    override fun read(name: String): String? {
        val file = File(dir, name)
        return if (file.isFile) file.readText() else null
    }

    override fun writeAtomic(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    override fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name } ?: emptyList()
}
```

For the Android file, add the same `JvmHistoryFileSystem` class body (identical code) beneath the factory. (Both targets are JVM; a shared intermediate source set is out of scope for this plan, matching the existing per-platform duplication of `AndroidDataStore`/`DesktopPreferences`.)

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FileDayHistoryStoreTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt composeApp/src/androidMain/kotlin/fr/dayview/app/DayHistoryStore.android.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/DayHistoryStore.desktop.kt composeApp/src/commonTest/kotlin/fr/dayview/app/FileDayHistoryStoreTest.kt
git commit -m "Add file-per-day history store with atomic writes"
```

---

## Task 4: Archive the previous day on rollover

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryRolloverTest.kt`

**Interfaces:**
- Consumes: `DayHistoryStore`, `InMemoryDayHistoryStore` (Task 3), `toHistoryRecord` (Task 1), `dayKeyOf` (`Detours.kt`).
- Produces (on `DayViewController`):
  - new constructor param `history: DayHistoryStore = InMemoryDayHistoryStore()`
  - private `fun maybeArchivePreviousDay()` invoked from `init` and from `tick` when the day changes.

Archival key = the day the persisted day-scoped data belongs to: `listOf(state.detoursDayKey, state.cleanSessions.dayKey).filter { it != -1L }.maxOrNull()`. If non-null and `!= dayKeyOf(state.now)`, write `state.toHistoryRecord(thatKey)`. `write` is idempotent, so re-archiving is a no-op.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayHistoryRolloverTest {
    // Deterministic controller construction mirrors seededController but injects a store.
    private fun controllerWith(
        snapshot: DayPreferencesSnapshot,
        now: Instant,
        history: DayHistoryStore,
    ): DayViewController = DayViewController(
        preferences = DefaultDayPreferences,
        initial = snapshot,
        now = now,
        scope = TestScopeProvider.scope(),
        history = history,
    )

    @Test
    fun crossingMidnightArchivesThePreviousDay() = runTest {
        val history = InMemoryDayHistoryStore()
        val today = Instant.parse("2026-05-04T09:00:00Z")
        val yesterdayKey = dayKeyOf(Instant.parse("2026-05-03T12:00:00Z"))
        // Persisted state still carries yesterday's detours.
        val snapshot = DayPreferencesSnapshot(
            detoursDayKey = yesterdayKey,
            detours = listOf(DetourEpisode(Instant.parse("2026-05-03T11:00:00Z"), Instant.parse("2026-05-03T11:10:00Z"), "slack")),
        )
        controllerWith(snapshot, today, history)

        val archived = history.read(yesterdayKey)
        assertEquals(yesterdayKey, archived?.dayKey)
        assertEquals(1, archived?.detours?.size)
    }

    @Test
    fun noArchiveWhenPersistedDayIsToday() = runTest {
        val history = InMemoryDayHistoryStore()
        val today = Instant.parse("2026-05-04T09:00:00Z")
        val todayKey = dayKeyOf(today)
        val snapshot = DayPreferencesSnapshot(detoursDayKey = todayKey, detours = emptyList())
        controllerWith(snapshot, today, history)

        assertEquals(emptyList(), history.listDays(Long.MIN_VALUE..Long.MAX_VALUE))
    }
}
```

Note: if the desktopTest source set lacks a `TestScopeProvider`/injectable scope helper, reuse whatever `seededController` uses to build a `CoroutineScope` (inspect `seededController` in desktopTest and copy its scope construction into this test's `controllerWith`). The exact `DayViewController` constructor parameter list must match the real one — read it before writing this test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryRolloverTest"`
Expected: FAIL — `history` param unresolved.

- [ ] **Step 3: Modify `DayViewController`**

Add the constructor parameter (keep the default so all existing call sites and tests still compile):

```kotlin
class DayViewController(
    private val preferences: DayPreferences,
    initial: DayPreferencesSnapshot,
    now: Instant,
    private val scope: CoroutineScope,
    private val history: DayHistoryStore = InMemoryDayHistoryStore(),
) {
```

(Match the real existing parameter names/order; only add the `history` line.)

Add the archival helper and call it from `init` and `tick`:

```kotlin
    private fun persistedDayKey(state: DayViewUiState): Long? =
        listOf(state.detoursDayKey, state.cleanSessions.dayKey).filter { it != -1L }.maxOrNull()

    private fun maybeArchivePreviousDay() {
        val key = persistedDayKey(state) ?: return
        if (key == dayKeyOf(state.now)) return
        val record = state.toHistoryRecord(key)
        scope.launch { history.write(record) }
    }
```

In `init { ... }`, after the existing goal-backfill block, add:

```kotlin
        maybeArchivePreviousDay()
```

In `tick`, archive when the day boundary is crossed:

```kotlin
    fun tick(now: Instant) {
        val dayChanged = dayKeyOf(now) != dayKeyOf(state.now)
        state = state.copy(now = now)
        if (dayChanged) maybeArchivePreviousDay()
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayHistoryRolloverTest"`
Expected: PASS.

- [ ] **Step 5: Run the full suite to catch construction regressions**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS (the `history` default keeps existing `DayViewController(...)` call sites valid).

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayHistoryRolloverTest.kt
git commit -m "Archive the previous day's ring on rollover"
```

---

## Task 5: `MiniRing` composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/MiniRing.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryWeekScreenTest.kt` (added in Task 6; this task adds only the tag + composable, verified indirectly in Task 6)

**Interfaces:**
- Consumes: `DayProgress`, `BusyBlockArc`, `FocusArc` (existing projections), `LocalDayViewColors` (`DayViewTheme.kt`).
- Produces:
  - `internal fun MiniRing(progress: DayProgress, busyBlockArcs: List<BusyBlockArc>, focusArcs: List<FocusArc>, modifier: Modifier = Modifier)`
  - `DayViewTestTags.MiniRing = "historyMiniRing"` and `fun historyDayCell(dayKey: Long) = "historyDayCell_$dayKey"`

`MiniRing` draws the same three arc families as `CountdownCircle` (background track, busy lane, focus arcs, remaining sweep) at small size, with no center text and no pointer input. Factor the shared angle math by reusing the existing helpers in `DayProgress.kt` (`currentMomentAngleDegrees`) and the arc sweep values already carried by `BusyBlockArc`/`FocusArc`; do not duplicate geometry constants that already live in `CountdownCircle` — if a constant is private there, lift it to a shared internal `top-level val` in `DayProgress.kt` and reference it from both.

- [ ] **Step 1: Add the test tags**

In `DayViewTestTags.kt`, inside the object:

```kotlin
    const val MiniRing = "historyMiniRing"
    const val HistoryIcon = "historyIcon"
    const val HistoryBack = "historyBack"

    fun historyDayCell(dayKey: Long): String = "historyDayCell_$dayKey"
```

- [ ] **Step 2: Write the composable**

```kotlin
package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * A compact, non-interactive ring for the history grid. Mirrors [CountdownCircle]'s arc
 * families (track, busy lane, focus, remaining sweep) at small size, without center text
 * or hover handling. Fed by the same projections a live day uses.
 */
internal fun MiniRing(
    progress: DayProgress,
    busyBlockArcs: List<BusyBlockArc>,
    focusArcs: List<FocusArc>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Canvas(modifier = modifier.size(44.dp).testTag(DayViewTestTags.MiniRing)) {
        val stroke = Stroke(width = size.minDimension * 0.10f)
        val inset = stroke.width / 2f
        val arcSize = Size(size.minDimension - stroke.width, size.minDimension - stroke.width)
        val topLeft = Offset(inset, inset)

        // Background track.
        drawArc(colors.track, 0f, 360f, false, topLeft, arcSize, style = stroke)
        // Busy lane.
        for (arc in busyBlockArcs) {
            drawArc(colors.busy(arc.colorIndex), arc.startAngleDegrees, arc.sweepDegrees, false, topLeft, arcSize, style = stroke)
        }
        // Focus arcs.
        for (arc in focusArcs) {
            drawArc(colors.mint, arc.startAngleDegrees, arc.sweepDegrees, false, topLeft, arcSize, style = stroke)
        }
        // Remaining sweep (accent), anchored like the main ring.
        val accent = when {
            progress.isFinished -> colors.red
            progress.remainingRatio < 0.2f -> colors.amber
            else -> colors.mint
        }
        drawArc(accent, -90f, 360f * progress.remainingRatio, false, topLeft, arcSize, style = stroke)
    }
}
```

Note: read `CountdownCircle` to confirm the exact property names on `BusyBlockArc`/`FocusArc` (`startAngleDegrees`/`sweepDegrees` may differ — use the real names) and the exact `LocalDayViewColors` accessor for per-calendar busy color (`colors.busy(index)` vs a list). Match them precisely; adjust the code above to the real API before compiling.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. (Behavioral verification comes with the screen test in Task 6.)

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/MiniRing.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt
git commit -m "Add compact MiniRing for the history grid"
```

---

## Task 6: `HistoryWeekScreen`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryWeekScreen.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryWeekScreenTest.kt`

**Interfaces:**
- Consumes: `DayHistoryRecord.toFrozenUiState()` (Task 1), `MiniRing` (Task 5), `DayViewTestTags.historyDayCell` / `MiniRing` (Task 5).
- Produces:
  - `internal data class HistoryWeekDay(val dayKey: Long, val label: String, val record: DayHistoryRecord?)`
  - `internal fun weekDaysEndingAt(todayKey: Long): List<Long>` — the 7 Monday→Sunday keys of the week containing `todayKey` (or the most recent 7 days ending today — see decision below).
  - `internal fun HistoryWeekScreen(days: List<HistoryWeekDay>, onSelectDay: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier)`

Decision: the grid shows the **current calendar week, Monday→Sunday**. `weekDaysEndingAt` returns the 7 keys of that week. Days with `record == null` render a greyed, non-clickable `MiniRing`-less placeholder cell; days with a record render a clickable `MiniRing` tagged `historyDayCell(dayKey)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HistoryWeekScreenTest {
    private fun record(dayKey: Long) = DayHistoryRecord(
        dayKey = dayKey, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(), netTimeSettings = NetTimeSettings(),
        focusPresenceIntervals = emptyList(), detours = emptyList(), cleanSessions = CleanSessionLedger(),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun daysWithDataAreClickableAndGapsAreNot() = runComposeUiTest {
        var clicked: Long? = null
        val days = listOf(
            HistoryWeekDay(10L, "Mon", record(10L)),
            HistoryWeekDay(11L, "Tue", null), // gap
        )
        setContent {
            DayViewTheme {
                HistoryWeekScreen(days = days, onSelectDay = { clicked = it }, onBack = {})
            }
        }

        onNodeWithTag(DayViewTestTags.historyDayCell(10L)).assertHasClickAction().performClick()
        assertEquals(10L, clicked)
        onNodeWithTag(DayViewTestTags.historyDayCell(11L)).assertHasNoClickAction()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryWeekScreenTest"`
Expected: FAIL — `HistoryWeekScreen`/`HistoryWeekDay` unresolved.

- [ ] **Step 3: Write the screen**

```kotlin
package fr.dayview.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

internal data class HistoryWeekDay(val dayKey: Long, val label: String, val record: DayHistoryRecord?)

/** The 7 Monday→Sunday day keys of the calendar week containing [todayKey]. */
internal fun weekDaysEndingAt(todayKey: Long): List<Long> {
    val today = LocalDate.fromEpochDays(todayKey.toInt())
    val monday = today.toEpochDays() - (today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber)
    return (0..6).map { (monday + it).toLong() }
}

internal fun HistoryWeekScreen(
    days: List<HistoryWeekDay>,
    onSelectDay: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text("‹", modifier = Modifier.testTag(DayViewTestTags.HistoryBack).clickable { onBack() })
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (day in days) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.label)
                    val record = day.record
                    if (record != null) {
                        val state = remember(record) { record.toFrozenUiState() }
                        MiniRing(
                            progress = state.dayProgress,
                            busyBlockArcs = state.busyBlockArcsState,
                            focusArcs = state.focusArcsState,
                            modifier = Modifier
                                .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                                .clickable { onSelectDay(day.dayKey) },
                        )
                    } else {
                        // Greyed, non-clickable placeholder for a day with no capture.
                        MiniRing(
                            progress = calculateDayProgress(
                                LocalDate.fromEpochDays(day.dayKey.toInt()).toEpochDays().let {
                                    kotlin.time.Instant.fromEpochMilliseconds(0L)
                                },
                                480, 1080,
                            ),
                            busyBlockArcs = emptyList(),
                            focusArcs = emptyList(),
                            modifier = Modifier
                                .testTag(DayViewTestTags.historyDayCell(day.dayKey))
                                .alpha(0.3f),
                        )
                    }
                }
            }
        }
    }
}
```

Note: the placeholder's `progress` value is irrelevant (it's greyed at 0.3 alpha and non-clickable); simplify to `DayProgress(...)` with any valid empty value that `calculateDayProgress` or the `DayProgress` constructor accepts — read `DayProgress.kt` for the real constructor and pass a trivially-empty instance instead of the awkward expression above. The essential contract the test checks: the placeholder cell carries the `historyDayCell` tag but has **no** `clickable`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryWeekScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryWeekScreen.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryWeekScreenTest.kt
git commit -m "Add week overview grid of mini rings"
```

---

## Task 7: `HistoryDayScreen` (read-only drill-in)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryDayScreenTest.kt`

**Interfaces:**
- Consumes: `CountdownCircle` (`DayViewTodayScreen.kt`), `DayHistoryRecord.toFrozenUiState()` (Task 1), `detourBodies` (`Detours.kt`), `DayViewTestTags.Countdown`.
- Produces:
  - `internal fun HistoryDayScreen(record: DayHistoryRecord, onBack: () -> Unit, modifier: Modifier = Modifier)`

Reuses `CountdownCircle` read-only (no action callbacks — `onOpenDetourList = null`), fed from the record's frozen state. Reads the same projected values the live Today screen passes (`netTime`, `focusArcsState`, `busyBlockArcsState`, `detourBodiesState`, `dayWindow`, `cleanSessionsToday`, `streakDays`, goal presence).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class HistoryDayScreenTest {
    private val record = DayHistoryRecord(
        dayKey = 20_000L, startMinutes = 480, endMinutes = 1080, focusIntention = "",
        busyIntervals = emptyList(), calendarNames = emptyMap(),
        netTimeSettings = NetTimeSettings(), focusPresenceIntervals = emptyList(),
        detours = emptyList(), cleanSessions = CleanSessionLedger(dayKey = 20_000L),
        pomodoroMinutes = 25, pomodoroEnd = null, goalTitle = "", goalDeadline = null, goalStart = null,
    )

    @Test
    fun rendersTheRingForAPastDay() = runComposeUiTest {
        setContent { DayViewTheme { HistoryDayScreen(record = record, onBack = {}) } }
        // The ring composable is present (tag defined on CountdownCircle's root Box).
        onNodeWithTag(DayViewTestTags.Countdown).assertExists()
    }

    @Test
    fun backControlInvokesCallback() = runComposeUiTest {
        var backed = false
        setContent { DayViewTheme { HistoryDayScreen(record = record, onBack = { backed = true }) } }
        onNodeWithTag(DayViewTestTags.HistoryBack).performClick()
        assertTrue(backed)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryDayScreenTest"`
Expected: FAIL — `HistoryDayScreen` unresolved.

- [ ] **Step 3: Write the screen**

```kotlin
package fr.dayview.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Read-only replay of one archived day: the live ring, fed from the record's frozen
 * state, with no action panels (no starting focus or editing detours on a past day).
 */
internal fun HistoryDayScreen(
    record: DayHistoryRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(record) { record.toFrozenUiState() }
    val (windowStart, windowEnd) = state.dayWindow
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text("‹", modifier = Modifier.testTag(DayViewTestTags.HistoryBack).clickable { onBack() })
        CountdownCircle(
            progress = state.dayProgress,
            showSeconds = false,
            netTime = state.netTime,
            focusArcs = state.focusArcsState,
            focusedToday = state.focusedToday,
            windowStart = windowStart,
            windowEnd = windowEnd,
            detourBodies = state.detourBodiesState,
            detoursTotal = state.detoursTotal,
            busyBlockArcs = state.busyBlockArcsState,
            cleanSessionsToday = state.cleanSessionsToday,
            streakDays = state.streakDays,
            hasGoal = state.goalDeadline != null,
            onOpenDetourList = null,
        )
    }
}
```

Note: `focusedToday`, `detoursTotal`, `focusArcsState`, `detourBodiesState`, `streakDays` are computed properties on `DayViewUiState` — confirm their exact names against `DayViewController.kt` before compiling and match them. If a property the live Today screen passes to `CountdownCircle` has a different accessor, use the live screen (`DayViewScreen`) as the reference for the exact argument list.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryDayScreenTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryDayScreenTest.kt
git commit -m "Add read-only history day drill-in"
```

---

## Task 8: Navigation wiring + entry point

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (enum + nav state/actions + load days)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (route + back)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (history icon)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (pass real store)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewPreferences.kt` (pass real store)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HistoryNavigationTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces (on `DayViewController`):
  - `DayViewDestination.HISTORY`
  - `state.selectedHistoryDay: Long?`, `state.historyWeek: List<HistoryWeekDay>`
  - `fun openHistory()`, `fun openHistoryDay(dayKey: Long)`, `fun closeHistory()` (day → week → today)
  - `openHistory()` loads the current week's records via `history.listDays(...)` + `history.read(...)` into `state.historyWeek`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HistoryNavigationTest {
    @Test
    fun historyIconOpensWeekOverview() = runComposeUiTest {
        lateinit var controller: DayViewController
        setContent {
            val c = remember { seededController(DayPreferencesSnapshot(), midWindowNow()) }
            controller = c
            DayViewTheme { App(controller = c) } // or the smallest composable that hosts routing
        }
        onNodeWithTag(DayViewTestTags.HistoryIcon).performClick()
        assertEquals(DayViewDestination.HISTORY, controller.state.destination)
    }
}
```

Note: `App(controller = ...)` may not be directly test-hostable (per the UI-test gotchas, avoid `DayViewApp`). If so, test the controller transitions directly instead: call `controller.openHistory()` / `openHistoryDay(k)` / `closeHistory()` and assert `destination` and `selectedHistoryDay` — a pure controller test in `desktopTest` with no `setContent`. Prefer that form; keep the icon-click assertion only if the routing composable is cleanly hostable.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryNavigationTest"`
Expected: FAIL.

- [ ] **Step 3: Extend the controller**

Add `HISTORY` to the enum:

```kotlin
internal enum class DayViewDestination {
    TODAY,
    SETTINGS,
    HISTORY,
}
```

Add nav state fields to `DayViewUiState` (defaults keep existing constructions valid):

```kotlin
    val selectedHistoryDay: Long? = null,
    val historyWeek: List<HistoryWeekDay> = emptyList(),
```

Add actions on the controller:

```kotlin
    fun openHistory() {
        val todayKey = dayKeyOf(state.now)
        val keys = weekDaysEndingAt(todayKey)
        state = state.copy(destination = DayViewDestination.HISTORY, selectedHistoryDay = null)
        scope.launch {
            val present = history.listDays(keys.first()..keys.last()).toSet()
            val labels = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
            val days = keys.mapIndexed { i, key ->
                val record = if (key in present) history.read(key) else null
                HistoryWeekDay(key, labels[i], record)
            }
            state = state.copy(historyWeek = days)
        }
    }

    fun openHistoryDay(dayKey: Long) {
        state = state.copy(selectedHistoryDay = dayKey)
    }

    fun closeHistory() {
        state = if (state.selectedHistoryDay != null) {
            state.copy(selectedHistoryDay = null)
        } else {
            state.copy(destination = DayViewDestination.TODAY)
        }
    }
```

- [ ] **Step 4: Route in `App.kt`**

Extend the screen switch (currently `if (destination == SETTINGS) ... else DayViewScreen`) to a `when`:

```kotlin
                    when (state.destination) {
                        DayViewDestination.SETTINGS -> SettingsScreen(/* existing args */)
                        DayViewDestination.HISTORY -> {
                            val selected = state.selectedHistoryDay
                            val record = state.historyWeek.firstOrNull { it.dayKey == selected }?.record
                            if (selected != null && record != null) {
                                HistoryDayScreen(record = record, onBack = { controller.closeHistory() })
                            } else {
                                HistoryWeekScreen(
                                    days = state.historyWeek,
                                    onSelectDay = { controller.openHistoryDay(it) },
                                    onBack = { controller.closeHistory() },
                                )
                            }
                        }
                        DayViewDestination.TODAY -> DayViewScreen(/* existing args */)
                    }
```

Extend `PlatformBackHandler` to also handle HISTORY:

```kotlin
                    PlatformBackHandler(
                        enabled = state.destination == DayViewDestination.SETTINGS ||
                            state.destination == DayViewDestination.HISTORY,
                    ) {
                        when (state.destination) {
                            DayViewDestination.SETTINGS -> { /* existing settings-back logic */ }
                            DayViewDestination.HISTORY -> controller.closeHistory()
                            DayViewDestination.TODAY -> {}
                        }
                    }
```

- [ ] **Step 5: Add the history icon on the Today screen**

In `DayViewTodayScreen.kt`, near the existing settings entry point, add an icon button (mirror the existing settings icon's placement and style) tagged `DayViewTestTags.HistoryIcon`, invoking a new `onOpenHistory` action threaded through `DayViewScreenActions`. Wire `onOpenHistory = { controller.openHistory() }` at the `App.kt` call site alongside the other actions.

```kotlin
// In DayViewScreenActions (add a field):
val onOpenHistory: () -> Unit,
// In the Today screen header row, next to the settings icon:
IconButton(onClick = actions.onOpenHistory, modifier = Modifier.testTag(DayViewTestTags.HistoryIcon)) {
    Icon(Icons.Default.DateRange, contentDescription = null)
}
```

(Use whatever icon set/import the settings icon already uses; match its `contentDescription` convention — the project uses `stringResource` for descriptions, so reuse that pattern rather than a bare null if the settings icon does.)

- [ ] **Step 6: Pass the real store at both entry points**

Desktop `Main.kt` (near `val preferences = remember { desktopDayPreferences() }`):

```kotlin
    val history = remember { createDayHistoryStore() }
```

and add `history = history` to the `DayViewController(...)` construction.

Android `DayViewPreferences.kt` (where the controller/preferences are assembled): construct `createDayHistoryStore()` and pass it as the controller's `history` argument. (If the controller is built elsewhere on Android, thread the store to that site.)

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HistoryNavigationTest"`
Expected: PASS.

- [ ] **Step 8: Full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "Wire history week/day navigation and Today entry point"
```

---

## Task 9: Manual verification

**Files:** none (runtime verification).

- [ ] **Step 1: Run the desktop app**

Run: `./gradlew :composeApp:run`

- [ ] **Step 2: Verify the flow**

- Click the history icon on the Today screen → the week overview opens with 7 cells.
- Today's cell (and any prior day the app captured this week) shows a rendered `MiniRing`; days with no capture are greyed and non-clickable.
- Click a rendered day → the read-only ring for that day appears; back returns to the week; back again returns to Today.
- Quit and relaunch: confirm a file exists under `~/.dayview/history/` for at least one prior `dayKey`, and that reopening history still renders it.

- [ ] **Step 3: Confirm no console errors**

Check the run log for exceptions during navigation and archival.

---

## Self-Review Notes

- **Spec coverage:** storage approach A (file-per-day, hand-rolled encoding, `expect`/`actual`) → Tasks 2–3; raw-inputs archival + same projection → Tasks 1, 4; partial-snapshot semantics (archive whatever was persisted; empty cell otherwise) → Task 4 `persistedDayKey` + Task 6 null cells; pomodoro + global goal in record → Task 1 fields; week-first, Monday start, indefinite retention → Task 6 `weekDaysEndingAt`, no pruning; navigation via extended enum, history icon entry point → Task 8; defensive/atomic storage → Tasks 2–3; testing per project constraints → each task's tests.
- **Deferred (spec "out of scope"):** month/year views, summary index, backfill-from-calendar, editing past days — intentionally not implemented.
- **API-name caution:** Tasks 5 and 7 depend on the exact accessor names of `BusyBlockArc`/`FocusArc` (angle fields), `LocalDayViewColors` (per-calendar color accessor), and the computed properties on `DayViewUiState` (`focusedToday`, `detoursTotal`, `focusArcsState`, `detourBodiesState`, `streakDays`, `cleanSessionsToday`). Each of those steps instructs the implementer to read the live `DayViewScreen`/`CountdownCircle` and match names before compiling — resolve any mismatch there.
- **Constructor caution:** Task 4 adds a defaulted `history` param to `DayViewController`; the implementer must match the real existing parameter list. `seededController` in desktopTest may need a one-line update to pass a store only if a test wants to inspect archival (the default keeps all current call sites compiling).
