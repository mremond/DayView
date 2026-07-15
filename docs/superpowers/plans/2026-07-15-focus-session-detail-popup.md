# Focus-session detail pop-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user hover (desktop) or tap (Android) a focus session on the countdown ring and see its intention, time range, engaged time, and deep-focus time — on the today ring and the history day/week rings.

**Architecture:** Add a durable per-session `FocusSessionRecord` (start, end, intention, outcome), captured at session close, persisted in preferences + day history, and synced additively through both the history-record and focus-contribution channels. Engaged/deep-focus durations are derived at render time by clipping the already-kept day-wide interval lists to each session window. A faint "engaged band" is drawn on the ring as the hover/tap target, with the existing mint deep-focus arcs on top; hit-testing and the pop-up reuse the detour/busy interaction infrastructure.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime `Instant`, kotlinx-serialization (sync DTOs), AndroidX DataStore (preferences), JUnit/kotlin-test + Robolectric + Compose UI test.

## Global Constraints

- **Do NOT bump `HISTORY_SCHEMA_VERSION`** (currently `1` in `SyncDocument.kt`). It is mixed into the AES-GCM AAD; bumping breaks cross-version decryption. All new sync fields are additive with serialization defaults (`= emptyList()`); `SyncJson` already sets `ignoreUnknownKeys = true`.
- **English** for all commit messages and code comments. **Never** reference Claude/Anthropic/AI or add a co-author trailer in commits.
- **Never reference internal working documents** (this plan, the spec under `docs/superpowers/`) in commit messages.
- **i18n strings** live in `shared/src/commonMain/composeResources/values/strings.xml`; use single `%` positional args (`%1$s`), per this repo's Compose-resources convention.
- **ktlint is enforced.** Run `./gradlew ktlintFormat` before each commit.
- **Full gate before the final commit:** `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`.
- **Compose UI test rule:** never assert `stringResource` text (unresolved in `runComposeUiTest` on CI). Assert via test tags / seeded data. `assertExists` is a member (no import).
- Core pure-logic tests run under **`:core:jvmTest`** (commonTest). Sync/DTO and preference tests that touch the `shared` module run under `:shared:desktopTest` / `:shared:testAndroidHostTest`.

---

## File Structure

**Core (`core/src/commonMain/kotlin/fr/dayview/app/`)**
- Create `FocusSessionRecord.kt` — the model + `encodeFocusSessionRecords`/`decodeFocusSessionRecords` + `focusSessionBands`/`FocusSessionBand` + `engagedTimeForSession`/`deepFocusTimeForSession` + `focusSessionBandAtAngle` hit-test.
- Modify `DayPreferences.kt` — add `focusSessionRecords` + `focusSessionRecordsDayKey` fields to the snapshot.
- Modify `DayPreferencesStore.kt` — persist/restore the two new fields.
- Modify `DayHistoryRecord.kt` — add field, clip in `toHistoryRecord`, pass in `toFrozenUiState`.
- Modify `DayHistoryCodec.kt` — encode/decode the new field (back-compatible).
- Modify `DayViewController.kt` — state field + threading, capture on close, `focusSessionRecordsState`/`focusSessionBandsState`/derivation getters, write into `FocusContribution`.
- Modify `CleanFocusSessions.kt` — append a record in `closeFocusSnapshot`.
- Modify `FocusContributionStore.kt` — add `records` to `FocusContribution`, union in `withMergedFocus`.
- Modify `RingReadout.kt` — carry the session under the scrub angle.

**Sync (`shared/src/commonMain/kotlin/fr/dayview/app/sync/`)**
- Modify `DayHistoryRecordDto.kt` — add `FocusSessionRecordDto` + field.
- Modify `HistoryRecordMapper.kt` — map both directions.
- Modify `FocusContributionMapper.kt` — add `records` to DTO + map both directions.

**UI (`shared/src/commonMain/kotlin/fr/dayview/app/`)**
- Modify `DayViewTodayScreen.kt` — draw the band, hit-test, pop-up composable, scrub readout line.
- Modify `DayViewTestTags.kt` — add a tag for the session pop-up.
- Modify `composeResources/values/strings.xml` — new strings.
- Modify `App.kt`, `HistoryDayScreen.kt`, `HistoryWeekScreen.kt` — pass the new state through to `CountdownCircle`/`MiniRing` where focus arcs are already passed.

**Tests** — colocated: `core/src/commonTest/kotlin/fr/dayview/app/`, `core/src/jvmTest/...` (preferences store), `shared/src/desktopTest/...` (sync + UI).

---

## Task 1: `FocusSessionRecord` model + codec

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FocusSessionRecord.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusSessionRecordTest.kt`

**Interfaces:**
- Consumes: `FocusClosureOutcome` (from `Pomodoro.kt`), `Instant`.
- Produces:
  - `data class FocusSessionRecord(val start: Instant, val end: Instant, val intention: String, val outcome: FocusClosureOutcome)`
  - `fun encodeFocusSessionRecords(records: List<FocusSessionRecord>): String`
  - `fun decodeFocusSessionRecords(encoded: String): List<FocusSessionRecord>`

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/FocusSessionRecordTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusSessionRecordTest {
    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    @Test
    fun roundTripsRecordsIncludingSeparatorsInIntention() {
        val records = listOf(
            FocusSessionRecord(at(1_000), at(2_000), "Write, spec\nline two", FocusClosureOutcome.COMPLETED),
            FocusSessionRecord(at(3_000), at(4_000), "", FocusClosureOutcome.TO_RESUME),
        )
        val decoded = decodeFocusSessionRecords(encodeFocusSessionRecords(records))
        assertEquals(records, decoded)
    }

    @Test
    fun emptyEncodesAndDecodesToEmpty() {
        assertEquals(emptyList(), decodeFocusSessionRecords(encodeFocusSessionRecords(emptyList())))
        assertEquals(emptyList(), decodeFocusSessionRecords(""))
    }

    @Test
    fun skipsMalformedAndUnknownOutcomeLines() {
        // 4 fields required; bad instant, then unknown outcome name, then a good line.
        val blob = "@1\nxxx,2,COMPLETED,hi\n1,2,NOPE,hi\n5,6,COMPLETED,ok"
        val decoded = decodeFocusSessionRecords(blob)
        assertEquals(listOf(FocusSessionRecord(at(5), at(6), "ok", FocusClosureOutcome.COMPLETED)), decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusSessionRecordTest"`
Expected: FAIL — `FocusSessionRecord` / `encodeFocusSessionRecords` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/commonMain/kotlin/fr/dayview/app/FocusSessionRecord.kt`. Mirror the `encodeDetours`/`decodeDetours` marker + Base64 pattern (intention is Base64'd so its commas/newlines survive):

```kotlin
package fr.dayview.app

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * One closed focus session: its window `[start, end]` (effective end, early stops honoured),
 * the intention that was active when it ran, and how it was closed. Engaged / deep-focus
 * durations are NOT stored — they are derived at render time from the day-wide interval lists.
 */
data class FocusSessionRecord(
    val start: Instant,
    val end: Instant,
    val intention: String,
    val outcome: FocusClosureOutcome,
)

private const val FOCUS_SESSION_RECORDS_MARKER = "@1"

/**
 * Serialize behind a version marker: one `start,end,outcome,intentionB64` line per record
 * (epoch millis; outcome by enum name; intention Base64 so its commas / newlines survive as a
 * safe last field). Empty list encodes to just the marker.
 */
@OptIn(ExperimentalEncodingApi::class)
fun encodeFocusSessionRecords(records: List<FocusSessionRecord>): String {
    val lines = records.joinToString("\n") {
        val intention = Base64.encode(it.intention.encodeToByteArray())
        "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},${it.outcome.name},$intention"
    }
    return if (lines.isEmpty()) FOCUS_SESSION_RECORDS_MARKER else "$FOCUS_SESSION_RECORDS_MARKER\n$lines"
}

/** Inverse of [encodeFocusSessionRecords]; blank / malformed / unknown-outcome lines are skipped. */
@OptIn(ExperimentalEncodingApi::class)
fun decodeFocusSessionRecords(encoded: String): List<FocusSessionRecord> {
    if (encoded.isBlank()) return emptyList()
    val lines = encoded.split("\n")
    val bodyLines = if (lines.firstOrNull() == FOCUS_SESSION_RECORDS_MARKER) lines.drop(1) else lines
    return bodyLines.mapNotNull { line ->
        val parts = line.split(",", limit = 4)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        val outcome = parts.getOrNull(2)?.let { name -> FocusClosureOutcome.entries.firstOrNull { it.name == name } }
        val intention = parts.getOrNull(3)?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }
        if (start != null && end != null && outcome != null && intention != null) {
            FocusSessionRecord(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), intention, outcome)
        } else {
            null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusSessionRecordTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusSessionRecord.kt core/src/commonTest/kotlin/fr/dayview/app/FocusSessionRecordTest.kt
git commit -m "Add FocusSessionRecord model and text codec"
```

---

## Task 2: Per-session derivation + ring band projection + hit-test

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/FocusSessionRecord.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusSessionRecordTest.kt`

**Interfaces:**
- Consumes: `FocusSessionRecord`, `FocusPresenceInterval`, `focusedTime` (in `CalendarNetTime.kt`), `FocusArc`/`focusArcs` angle convention, `arcContainsAngle`/`angularDistanceToArc` (in `CalendarNetTime.kt`).
- Produces:
  - `fun engagedTimeForSession(record, sessionIntervals): Duration`
  - `fun deepFocusTimeForSession(record, presenceIntervals): Duration`
  - `data class FocusSessionBand(val startAngleDegrees: Float, val sweepDegrees: Float, val record: FocusSessionRecord)`
  - `fun focusSessionBands(windowStart, windowEnd, records): List<FocusSessionBand>`
  - `fun focusSessionBandAtAngle(bands, angleDegrees): FocusSessionBand?`

- [ ] **Step 1: Write the failing test** (append to `FocusSessionRecordTest.kt`)

```kotlin
    @Test
    fun engagedAndDeepFocusClipToTheSessionWindow() {
        val rec = FocusSessionRecord(at(0), at(1_000), "x", FocusClosureOutcome.COMPLETED)
        val session = listOf(FocusPresenceInterval(at(0), at(600)), FocusPresenceInterval(at(2_000), at(3_000)))
        val presence = listOf(FocusPresenceInterval(at(100), at(400)))
        assertEquals(600, engagedTimeForSession(rec, session).inWholeMilliseconds)
        assertEquals(300, deepFocusTimeForSession(rec, presence).inWholeMilliseconds)
    }

    @Test
    fun bandSpansWholeSessionWindowAndIsHitByItsAngle() {
        val ws = at(0); val we = at(4_000)
        val rec = FocusSessionRecord(at(1_000), at(2_000), "x", FocusClosureOutcome.COMPLETED)
        val bands = focusSessionBands(ws, we, listOf(rec))
        assertEquals(1, bands.size)
        val mid = bands[0].startAngleDegrees + bands[0].sweepDegrees / 2f
        assertEquals(rec, focusSessionBandAtAngle(bands, mid)?.record)
        // An angle far from the band misses.
        assertEquals(null, focusSessionBandAtAngle(bands, bands[0].startAngleDegrees + 180f))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusSessionRecordTest"`
Expected: FAIL — `engagedTimeForSession` etc. unresolved.

- [ ] **Step 3: Write minimal implementation** (append to `FocusSessionRecord.kt`; add imports `kotlin.time.Duration`)

```kotlin
/** Engaged time inside this session: the day-wide engaged intervals clipped to its window. */
fun engagedTimeForSession(
    record: FocusSessionRecord,
    sessionIntervals: List<FocusPresenceInterval>,
): Duration = focusedTime(record.start, record.end, sessionIntervals)

/** Deep-focus time inside this session: the day-wide presence intervals clipped to its window. */
fun deepFocusTimeForSession(
    record: FocusSessionRecord,
    presenceIntervals: List<FocusPresenceInterval>,
): Duration = focusedTime(record.start, record.end, presenceIntervals)

/** Angular tolerance around a session band for hover / scrub picking. */
private const val FOCUS_SESSION_ANGLE_TOLERANCE_DEGREES = 4f

/** A closed session projected as a ring band spanning its window (`-90° = window start`). */
data class FocusSessionBand(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val record: FocusSessionRecord,
)

/** Project records to bands over the day window; records fully outside the window are dropped. */
fun focusSessionBands(
    windowStart: Instant,
    windowEnd: Instant,
    records: List<FocusSessionRecord>,
): List<FocusSessionBand> {
    val total = windowEnd - windowStart
    if (total <= kotlin.time.Duration.ZERO) return emptyList()
    return records.sortedBy { it.start }.mapNotNull { record ->
        val start = record.start.coerceIn(windowStart, windowEnd)
        val end = record.end.coerceIn(windowStart, windowEnd)
        if (end <= start) return@mapNotNull null
        val fStart = ((start - windowStart) / total).toFloat()
        val fEnd = ((end - windowStart) / total).toFloat()
        FocusSessionBand(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            record = record,
        )
    }
}

/** The band containing [angleDegrees] (nearest within tolerance), radius-independent. Null if none. */
fun focusSessionBandAtAngle(bands: List<FocusSessionBand>, angleDegrees: Float): FocusSessionBand? = bands
    .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    ?.takeIf {
        angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= FOCUS_SESSION_ANGLE_TOLERANCE_DEGREES
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusSessionRecordTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusSessionRecord.kt core/src/commonTest/kotlin/fr/dayview/app/FocusSessionRecordTest.kt
git commit -m "Derive per-session engaged/deep-focus and project session bands"
```

---

## Task 3: Persist `focusSessionRecords` in preferences

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt:8-38`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt`
- Test: `core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `encodeFocusSessionRecords`/`decodeFocusSessionRecords` (Task 1).
- Produces: `DayPreferencesSnapshot.focusSessionRecords: List<FocusSessionRecord>` and `DayPreferencesSnapshot.focusSessionRecordsDayKey: Long`.

- [ ] **Step 1: Write the failing test**

Open `core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt`, find an existing persist/reload test to copy its harness (it constructs a `DayPreferencesStore` over a temp DataStore). Add:

```kotlin
    @Test
    fun persistsAndReloadsFocusSessionRecords() = runTest {
        val store = newStore() // reuse the file's existing store-construction helper/pattern
        val records = listOf(
            FocusSessionRecord(
                Instant.fromEpochMilliseconds(1_000),
                Instant.fromEpochMilliseconds(2_000),
                "ship it",
                FocusClosureOutcome.COMPLETED,
            ),
        )
        store.persist(DayPreferencesSnapshot(focusSessionRecords = records, focusSessionRecordsDayKey = 42L))
        val reloaded = store.snapshots.first()
        assertEquals(records, reloaded.focusSessionRecords)
        assertEquals(42L, reloaded.focusSessionRecordsDayKey)
    }
```

> If `newStore()` isn't the helper name in this file, match whatever the existing tests use to build the store; keep everything else identical.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL — `focusSessionRecords` is not a member of `DayPreferencesSnapshot`.

- [ ] **Step 3: Write minimal implementation**

In `DayPreferences.kt`, add to `DayPreferencesSnapshot` (next to `focusSessionIntervals`):

```kotlin
    val focusSessionRecordsDayKey: Long = -1L,
    val focusSessionRecords: List<FocusSessionRecord> = emptyList(),
```

In `DayPreferencesStore.kt`:
- Add keys to `DayPreferenceKeys`:
```kotlin
    const val FOCUS_SESSION_RECORDS_DAY = "focus_session_records_day"
    const val FOCUS_SESSION_RECORDS = "focus_session_records"
```
- Add the backing keys near `focusSessionKey`:
```kotlin
private val focusSessionRecordsDayPrefKey = longPreferencesKey(DayPreferenceKeys.FOCUS_SESSION_RECORDS_DAY)
private val focusSessionRecordsKey = stringPreferencesKey(DayPreferenceKeys.FOCUS_SESSION_RECORDS)
```
- In `persist(...)`, after the `focusSessionKey` line:
```kotlin
            prefs[focusSessionRecordsDayPrefKey] = snapshot.focusSessionRecordsDayKey
            prefs[focusSessionRecordsKey] = encodeFocusSessionRecords(snapshot.focusSessionRecords)
```
- In `toSnapshot()`, after `focusSessionIntervals = ...`:
```kotlin
        focusSessionRecordsDayKey = this[focusSessionRecordsDayPrefKey] ?: defaults.focusSessionRecordsDayKey,
        focusSessionRecords = decodeFocusSessionRecords(this[focusSessionRecordsKey].orEmpty()),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt core/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt core/src/jvmTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "Persist focus session records in day preferences"
```

---

## Task 4: Add `focusSessionRecords` to `DayHistoryRecord` + codec

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt:15-110`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRecordTest.kt`

**Interfaces:**
- Consumes: `FocusSessionRecord`, `encodeFocusSessionRecords`/`decodeFocusSessionRecords`.
- Produces: `DayHistoryRecord.focusSessionRecords: List<FocusSessionRecord>` (threaded through `toHistoryRecord`, `toFrozenUiState`).

- [ ] **Step 1: Write the failing test**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRecordTest.kt` (a helper `sampleRecord()` likely exists; if not, copy an existing full-record construction from the file). Two tests:

```kotlin
    @Test
    fun codecRoundTripsFocusSessionRecords() {
        val record = sampleRecord().copy(
            focusSessionRecords = listOf(
                FocusSessionRecord(
                    Instant.fromEpochMilliseconds(10),
                    Instant.fromEpochMilliseconds(20),
                    "do the thing",
                    FocusClosureOutcome.COMPLETED,
                ),
            ),
        )
        val decoded = DayHistoryCodec.decode(DayHistoryCodec.encode(record))
        assertEquals(record.focusSessionRecords, decoded?.focusSessionRecords)
    }

    @Test
    fun decodesLegacyBlobWithoutSessionRecordsAsEmpty() {
        // Encode a record, then strip the new line to simulate an older-format blob.
        val encoded = DayHistoryCodec.encode(sampleRecord())
        val legacy = encoded.lineSequence().filterNot { it.startsWith("sessionRecords=") }.joinToString("\n")
        assertEquals(emptyList(), DayHistoryCodec.decode(legacy)?.focusSessionRecords)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayHistoryRecordTest"`
Expected: FAIL — `focusSessionRecords` not a member of `DayHistoryRecord`.

- [ ] **Step 3: Write minimal implementation**

In `DayHistoryRecord.kt`:
- Add to the data class (after `focusSessionIntervals`):
```kotlin
    val focusSessionRecords: List<FocusSessionRecord>,
```
- In `toFrozenUiState`, after `focusSessionIntervals = focusSessionIntervals,` add:
```kotlin
    focusSessionRecords = focusSessionRecords,
```
- In `toHistoryRecord`, after the `focusSessionIntervals = ...filter{...}` line, add a window-clipped copy (drop records whose window falls fully outside):
```kotlin
        focusSessionRecords = focusSessionRecords.filter { it.end > windowStart && it.start < windowEnd },
```
> Note: `DayViewUiState.focusSessionRecords` is added in Task 5. Until then this file won't compile against the UI state — that's expected; Task 5 lands the state field. If implementing strictly per-task, add a temporary `emptyList()` here and replace it in Task 5. Prefer doing Task 5's state-field edit together if compiling between tasks.

In `DayHistoryCodec.kt`:
- In `encode(...)`, after the `session=` line:
```kotlin
        appendLine("sessionRecords=${enc(encodeFocusSessionRecords(record.focusSessionRecords))}")
```
- In `decode(...)`, after `focusSessionIntervals = ...`:
```kotlin
                focusSessionRecords = map["sessionRecords"]?.let { decodeFocusSessionRecords(dec(it)) } ?: emptyList(),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayHistoryRecordTest"`
Expected: PASS. (Fix any other `DayHistoryRecord(...)` constructor call sites the compiler flags by adding `focusSessionRecords = emptyList()`.)

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt core/src/commonMain/kotlin/fr/dayview/app/DayHistoryCodec.kt core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRecordTest.kt
git commit -m "Carry focus session records through day history and its codec"
```

---

## Task 5: Thread `focusSessionRecords` through the controller + capture on close

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state class ~40-72; getters ~122-138; `stopPomodoro` ~450; `closePomodoro` ~508; `toSnapshot` ~748; `toUiState` ~810; `withPersisted` ~848; init seed ~199)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt` (or the existing focus/pomodoro controller test file)

**Interfaces:**
- Consumes: `FocusSessionRecord`, `focusSessionBands`, `engagedTimeForSession`, `deepFocusTimeForSession`, `dayKeyOf`, `focusIntentionAfterClosure`.
- Produces on `DayViewUiState`: `focusSessionRecords: List<FocusSessionRecord>`, `focusSessionRecordsDayKey: Long`, `focusSessionRecordsToday: List<FocusSessionRecord>`, `focusSessionBandsState: List<FocusSessionBand>`.

- [ ] **Step 1: Write the failing test**

Find the controller test file that already exercises `startPomodoro`/`closePomodoro` (search `closePomodoro` under `core/src/commonTest`). Add a test using the same construction helper those tests use:

```kotlin
    @Test
    fun closingASessionRecordsItWithThePreClosureIntention() {
        val controller = newController() // reuse this file's controller factory
        controller.setFocusIntention("write the plan")
        controller.startPomodoro()
        controller.closePomodoro(FocusClosureOutcome.COMPLETED)

        val records = controller.state.focusSessionRecordsToday
        assertEquals(1, records.size)
        assertEquals("write the plan", records[0].intention)
        assertEquals(FocusClosureOutcome.COMPLETED, records[0].outcome)
        // COMPLETED clears the live intention but the record keeps it.
        assertEquals("", controller.state.focusIntention)
    }
```

> Match `newController()` / `setFocusIntention()` to the real helper and setter names used elsewhere in that test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL — `focusSessionRecordsToday` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `DayViewUiState` (add near `focusSessionDayKey`):
```kotlin
    val focusSessionRecordsDayKey: Long = -1L,
    val focusSessionRecords: List<FocusSessionRecord> = emptyList(),
```

Add getters in `DayViewUiState` (near `sessionFocusedToday`, ~138):
```kotlin
    /** Session records of the current local day; stale storage from a previous day reads as empty. */
    val focusSessionRecordsToday: List<FocusSessionRecord>
        get() = if (focusSessionRecordsDayKey == dayKeyOf(dayNow)) focusSessionRecords else emptyList()

    val focusSessionBandsState: List<FocusSessionBand>
        get() {
            val (start, end) = dayWindow
            return focusSessionBands(start, end, focusSessionRecordsToday)
        }
```

Add a private helper on the controller to append a record (place near `appendEngagedSession`):
```kotlin
    /** Append a record for the closing session, keyed to today; resets the list on day rollover. */
    private fun recordClosingSession(stopInstant: Instant, outcome: FocusClosureOutcome) {
        val end = state.pomodoroEnd ?: return
        val start = end - state.pomodoroMinutes.minutes
        val effectiveEnd = minOf(stopInstant, end)
        if (effectiveEnd <= start) return
        val today = dayKeyOf(state.now)
        val existing = if (state.focusSessionRecordsDayKey == today) state.focusSessionRecords else emptyList()
        val record = FocusSessionRecord(start, effectiveEnd, state.focusIntention, outcome)
        state = state.copy(
            focusSessionRecords = existing + record,
            focusSessionRecordsDayKey = today,
        )
    }
```

Call it in `stopPomodoro` (before clearing `pomodoroEnd`):
```kotlin
    fun stopPomodoro() {
        appendEngagedSession(state.now)
        recordClosingSession(state.now, FocusClosureOutcome.COMPLETED)
        state = state.copy(pomodoroEnd = null)
        persistState()
    }
```
And in `closePomodoro` (right after `appendEngagedSession(state.now)`, before the intention is recomputed):
```kotlin
        recordClosingSession(state.now, outcome)
```
> `recordClosingSession` reads `state.focusIntention` and `state.pomodoroEnd`, so it MUST run before the `state = state.copy(pomodoroEnd = null, focusIntention = updatedIntention, ...)` line.

Thread the fields through the three mappers:
- `toSnapshot()` — add `focusSessionRecordsDayKey = focusSessionRecordsDayKey,` and `focusSessionRecords = focusSessionRecords,`.
- `toUiState()` — add `focusSessionRecordsDayKey = safe.focusSessionRecordsDayKey,` and `focusSessionRecords = safe.focusSessionRecords,`.
- `withPersisted()` — add `focusSessionRecordsDayKey = safe.focusSessionRecordsDayKey,` and `focusSessionRecords = safe.focusSessionRecords,`.

Finally, in Task 4's `toHistoryRecord`, replace any temporary `emptyList()` with the real `focusSessionRecords` (now that the state field exists).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS (whole core suite green).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonMain/kotlin/fr/dayview/app/DayHistoryRecord.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "Capture a focus session record on close and expose session bands"
```

---

## Task 6: Capture a record on the desktop mini-window close path

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt:124-145`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt`

**Interfaces:**
- Consumes: `FocusSessionRecord`, `dayKeyOf`, `focusIntentionAfterClosure`, `DayPreferencesSnapshot.focusSessionRecords`.
- Produces: `closeFocusSnapshot` now appends a `FocusSessionRecord` to the returned snapshot.

- [ ] **Step 1: Write the failing test** (append to `CleanFocusSessionsTest.kt`)

```kotlin
    @Test
    fun closeFocusSnapshotRecordsTheSession() {
        val now = Instant.fromEpochMilliseconds(100_000_000)
        val snapshot = DayPreferencesSnapshot(
            pomodoroMinutes = 25,
            pomodoroEnd = now, // ends at now → window [now-25m, now]
            focusIntention = "mini window work",
        )
        val closed = closeFocusSnapshot(snapshot, now, Duration.ZERO, FocusClosureOutcome.COMPLETED)
        assertEquals(1, closed.focusSessionRecords.size)
        assertEquals("mini window work", closed.focusSessionRecords[0].intention)
        assertEquals(dayKeyOf(now), closed.focusSessionRecordsDayKey)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: FAIL — `focusSessionRecords` empty (size 0).

- [ ] **Step 3: Write minimal implementation**

In `closeFocusSnapshot`, build the record from the pre-closure snapshot and append it. Replace the `return snapshot.copy(...)` block:

```kotlin
    val newRecords = snapshot.pomodoroEnd?.let { end ->
        val start = end - snapshot.pomodoroMinutes.minutes
        val effectiveEnd = minOf(now, end)
        if (effectiveEnd <= start) {
            null
        } else {
            FocusSessionRecord(start, effectiveEnd, snapshot.focusIntention, outcome)
        }
    }
    val existingRecords = if (snapshot.focusSessionRecordsDayKey == dayKey) snapshot.focusSessionRecords else emptyList()
    return snapshot.copy(
        pomodoroEnd = null,
        focusIntention = focusIntentionAfterClosure(snapshot.focusIntention, outcome),
        focusSessionRecords = if (newRecords != null) existingRecords + newRecords else existingRecords,
        focusSessionRecordsDayKey = dayKey,
        cleanSessions = closedFocusLedger(
            cleanSessions = snapshot.cleanSessions,
            dayKey = dayKey,
            pomodoroEnd = snapshot.pomodoroEnd,
            pomodoroMinutes = snapshot.pomodoroMinutes,
            sessionOffGoal = sessionOffGoal,
            detoursToday = detoursToday,
            outcome = outcome,
        ),
    )
```

(`dayKey` is already computed at the top of the function as `dayKeyOf(now)`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.CleanFocusSessionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/CleanFocusSessions.kt core/src/commonTest/kotlin/fr/dayview/app/CleanFocusSessionsTest.kt
git commit -m "Record the session on the desktop mini-window close path"
```

---

## Task 7: Merge session records across devices (contribution store)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/FocusContributionStore.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt:271-285` (`maybeArchivePreviousDay`)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusContributionStoreTest.kt`

**Interfaces:**
- Consumes: `FocusSessionRecord`.
- Produces: `FocusContribution.records: List<FocusSessionRecord>`; `DayHistoryRecord.withMergedFocus` now also unions `focusSessionRecords`.

- [ ] **Step 1: Write the failing test** (append to `FocusContributionStoreTest.kt`)

```kotlin
    @Test
    fun withMergedFocusUnionsSessionRecordsFromContributions() {
        val base = sampleRecord().copy(
            focusSessionRecords = listOf(
                FocusSessionRecord(at(1_000), at(2_000), "local", FocusClosureOutcome.COMPLETED),
            ),
        )
        val other = FocusContribution(
            dayKey = base.dayKey,
            deviceId = "other",
            presence = emptyList(),
            session = emptyList(),
            records = listOf(FocusSessionRecord(at(5_000), at(6_000), "remote", FocusClosureOutcome.COMPLETED)),
        )
        val merged = base.withMergedFocus(listOf(other))
        assertEquals(listOf("local", "remote"), merged.focusSessionRecords.map { it.intention })
    }
```

> Add `private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)` and a `sampleRecord()` if the file lacks them (copy from `DayHistoryRecordTest`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusContributionStoreTest"`
Expected: FAIL — `records` not a parameter of `FocusContribution`.

- [ ] **Step 3: Write minimal implementation**

In `FocusContributionStore.kt`:
- Add to `FocusContribution`:
```kotlin
    val records: List<FocusSessionRecord> = emptyList(),
```
- Extend `withMergedFocus` to union records (sorted by start; per-device windows are disjoint, so no coalescing):
```kotlin
fun DayHistoryRecord.withMergedFocus(contributions: List<FocusContribution>): DayHistoryRecord = copy(
    focusPresenceIntervals = mergeIntervals(focusPresenceIntervals + contributions.flatMap { it.presence }),
    focusSessionIntervals = mergeIntervals(focusSessionIntervals + contributions.flatMap { it.session }),
    focusSessionRecords = (focusSessionRecords + contributions.flatMap { it.records }).sortedBy { it.start },
)
```

In `DayViewController.maybeArchivePreviousDay`, pass records into the written contribution:
```kotlin
                contributions.write(
                    FocusContribution(key, self, record.focusPresenceIntervals, record.focusSessionIntervals, record.focusSessionRecords),
                )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusContributionStore.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonTest/kotlin/fr/dayview/app/FocusContributionStoreTest.kt
git commit -m "Union focus session records across device contributions"
```

---

## Task 8: Sync — history-record DTO

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/sync/DayHistoryRecordDto.kt`
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/sync/HistoryRecordMapper.kt`
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/sync/HistoryRecordMapperTest.kt` (create if absent; otherwise the existing sync mapper test)

**Interfaces:**
- Consumes: `FocusSessionRecord`, `FocusClosureOutcome`.
- Produces: `FocusSessionRecordDto(start, end, intention, outcome)`; `DayHistoryRecordDto.focusSessionRecords: List<FocusSessionRecordDto> = emptyList()`.

- [ ] **Step 1: Write the failing test**

Create/extend `shared/src/desktopTest/kotlin/fr/dayview/app/sync/HistoryRecordMapperTest.kt`:

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusClosureOutcome
import fr.dayview.app.FocusSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HistoryRecordMapperTest {
    @Test
    fun roundTripsFocusSessionRecordsThroughJson() {
        val record = sampleHistoryRecord().copy( // reuse existing helper, or build a minimal record inline
            focusSessionRecords = listOf(
                FocusSessionRecord(Instant.fromEpochMilliseconds(1), Instant.fromEpochMilliseconds(2), "x", FocusClosureOutcome.COMPLETED),
            ),
        )
        val json = HistoryRecordMapper.serialize(record)
        assertEquals(record.focusSessionRecords, HistoryRecordMapper.deserialize(json)?.focusSessionRecords)
    }

    @Test
    fun decodesPayloadMissingSessionRecordsAsEmpty() {
        val json = HistoryRecordMapper.serialize(sampleHistoryRecord())
        // JSON without the key must still decode (ignoreUnknownKeys + default).
        val stripped = json.replace(Regex(",\"focusSessionRecords\":\\[[^]]*]"), "")
        assertEquals(emptyList(), HistoryRecordMapper.deserialize(stripped)?.focusSessionRecords)
    }
}
```

> If `HistoryRecordMapper.serialize/deserialize` aren't public, use the existing test's entry points. Provide `sampleHistoryRecord()` by copying a full `DayHistoryRecord(...)` construction from `core` tests (include `focusSessionRecords = emptyList()`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.sync.HistoryRecordMapperTest"`
Expected: FAIL — `focusSessionRecords` not a member of the DTO / record mapping.

- [ ] **Step 3: Write minimal implementation**

In `DayHistoryRecordDto.kt`, add the DTO and field:
```kotlin
@Serializable
data class FocusSessionRecordDto(val start: Long, val end: Long, val intention: String, val outcome: String)
```
Add to `DayHistoryRecordDto` (after `focusSession`):
```kotlin
    val focusSessionRecords: List<FocusSessionRecordDto> = emptyList(),
```

In `HistoryRecordMapper.kt`:
- In `toDto(...)`, after the `focusSession = ...` line:
```kotlin
        focusSessionRecords = r.focusSessionRecords.map { FocusSessionRecordDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.intention, it.outcome.name) },
```
- In `toRecord(...)`, after `focusSessionIntervals = ...`:
```kotlin
        focusSessionRecords = d.focusSessionRecords.mapNotNull { dto ->
            val outcome = FocusClosureOutcome.entries.firstOrNull { it.name == dto.outcome } ?: return@mapNotNull null
            FocusSessionRecord(Instant.fromEpochMilliseconds(dto.start), Instant.fromEpochMilliseconds(dto.end), dto.intention, outcome)
        },
```
- Add imports: `import fr.dayview.app.FocusClosureOutcome` and `import fr.dayview.app.FocusSessionRecord`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.sync.HistoryRecordMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/fr/dayview/app/sync/DayHistoryRecordDto.kt shared/src/commonMain/kotlin/fr/dayview/app/sync/HistoryRecordMapper.kt shared/src/desktopTest/kotlin/fr/dayview/app/sync/HistoryRecordMapperTest.kt
git commit -m "Sync focus session records in the history record DTO"
```

---

## Task 9: Sync — focus-contribution DTO

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionMapper.kt`
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/sync/FocusContributionMapperTest.kt` (create if absent)

**Interfaces:**
- Consumes: `FocusSessionRecordDto` (Task 8), `FocusContribution.records` (Task 7).
- Produces: `FocusContributionDto.records: List<FocusSessionRecordDto> = emptyList()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusClosureOutcome
import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusContributionMapperTest {
    @Test
    fun roundTripsRecords() {
        val c = FocusContribution(
            dayKey = 5L, deviceId = "d", presence = emptyList(), session = emptyList(),
            records = listOf(FocusSessionRecord(Instant.fromEpochMilliseconds(1), Instant.fromEpochMilliseconds(2), "x", FocusClosureOutcome.COMPLETED)),
        )
        val decoded = FocusContributionMapper.deserialize(FocusContributionMapper.serialize(c))
        assertEquals(c.records, decoded?.records)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.sync.FocusContributionMapperTest"`
Expected: FAIL — `records` not on `FocusContributionDto` / not mapped.

- [ ] **Step 3: Write minimal implementation**

In `FocusContributionMapper.kt`:
- Add to `FocusContributionDto` (after `session`):
```kotlin
    val records: List<FocusSessionRecordDto> = emptyList(),
```
- Add mapping helpers and thread them:
```kotlin
    private fun List<FocusSessionRecord>.toRecordDtos() =
        map { FocusSessionRecordDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.intention, it.outcome.name) }

    private fun List<FocusSessionRecordDto>.toRecords() = mapNotNull { dto ->
        val outcome = FocusClosureOutcome.entries.firstOrNull { it.name == dto.outcome } ?: return@mapNotNull null
        FocusSessionRecord(Instant.fromEpochMilliseconds(dto.start), Instant.fromEpochMilliseconds(dto.end), dto.intention, outcome)
    }
```
- In `serialize`, add `c.records.toRecordDtos()` as the new constructor argument.
- In `deserialize`, add `d.records.toRecords()` as the new `FocusContribution` argument.
- Add imports: `import fr.dayview.app.FocusClosureOutcome`, `import fr.dayview.app.FocusSessionRecord`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.sync.FocusContributionMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionMapper.kt shared/src/desktopTest/kotlin/fr/dayview/app/sync/FocusContributionMapperTest.kt
git commit -m "Sync focus session records in the focus contribution DTO"
```

---

## Task 10: `RingReadout` carries the session under the scrub angle

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/RingReadout.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/RingReadoutTest.kt` if it exists under core, else `shared/src/desktopTest/kotlin/fr/dayview/app/RingReadoutTest.kt`

> `RingReadout.kt` is in `shared/commonMain`. Put the test where the existing `ringReadoutAt` test lives; if none exists, create `shared/src/desktopTest/.../RingReadoutTest.kt`.

**Interfaces:**
- Consumes: `FocusSessionBand`, `focusSessionBandAtAngle`.
- Produces: `RingReadout.session: FocusSessionRecord?`; `ringReadoutAt(..., focusSessionBands: List<FocusSessionBand>, ...)` gains the parameter.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun readoutReportsTheSessionUnderTheAngle() {
        val ws = Instant.fromEpochMilliseconds(0); val we = Instant.fromEpochMilliseconds(4_000)
        val rec = FocusSessionRecord(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(2_000), "x", FocusClosureOutcome.COMPLETED)
        val bands = focusSessionBands(ws, we, listOf(rec))
        val mid = bands[0].startAngleDegrees + bands[0].sweepDegrees / 2f
        val readout = ringReadoutAt(mid, ws, we, emptyList(), emptyList(), emptyList(), bands, null)
        assertEquals(rec, readout.session)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.RingReadoutTest"`
Expected: FAIL — `ringReadoutAt` has no such parameter / `RingReadout.session` unresolved.

- [ ] **Step 3: Write minimal implementation**

In `RingReadout.kt`:
- Add to the `RingReadout` data class: `val session: FocusSessionRecord?,`.
- Add a parameter to `ringReadoutAt` (after `focusArcs: List<FocusArc>`): `focusSessionBands: List<FocusSessionBand>,`.
- Compute and set it:
```kotlin
    val session = focusSessionBandAtAngle(focusSessionBands, angleDegrees)?.record
```
```kotlin
    return RingReadout(
        time = angleToInstant(angleDegrees, windowStart, windowEnd),
        isNow = isNow,
        busy = busy,
        detour = detour,
        focus = focus,
        session = session,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.RingReadoutTest"`
Expected: PASS. (Update the existing `ringReadoutAt` call site in `DayViewTodayScreen.kt` ~1510 to pass `focusSessionBands` — Task 11 does this; if the module won't compile now, pass `emptyList()` there temporarily.)

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/fr/dayview/app/RingReadout.kt shared/src/desktopTest/kotlin/fr/dayview/app/RingReadoutTest.kt
git commit -m "Report the focus session under the ring scrub angle"
```

---

## Task 11: Draw the band, hit-test, and pop-up on the ring

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`CountdownCircle` ~936-1527)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/FocusSessionPopupTest.kt` (create)

**Interfaces:**
- Consumes: `FocusSessionBand`, `focusSessionBandAtAngle`, `engagedTimeForSession`, `deepFocusTimeForSession`, `RingReadout.session`, existing `formatClockHm`/`formatDurationHm`, `LocalUses24HourClock`, `LocalDayViewColors`.
- Produces: a `CountdownCircle` that renders the faint engaged band and shows a hover/tap pop-up with intention, time range, engaged, deep-focus, and outcome status. New `CountdownCircle` params: `focusSessionBands: List<FocusSessionBand> = emptyList()`.

- [ ] **Step 1: Add strings** (no test yet — resource step for the composable)

In `strings.xml`, add:
```xml
    <string name="focus_session_intention_empty">No focus intention set</string>
    <string name="focus_session_engaged">Engaged %1$s</string>
    <string name="focus_session_deep_focus">Deep focus %1$s</string>
    <string name="focus_session_outcome_completed">Completed</string>
    <string name="focus_session_outcome_stopped">Stopped early</string>
```

In `DayViewTestTags.kt`, add:
```kotlin
    const val FocusSessionPopup = "focusSessionPopup"
```

- [ ] **Step 2: Write the failing UI test**

Create `shared/src/desktopTest/kotlin/fr/dayview/app/FocusSessionPopupTest.kt`. Follow the repo's Compose-UI-test conventions (test the pop-up composable directly with seeded data; assert via tag, never `stringResource`). Extract the pop-up body into a testable composable `FocusSessionReadoutDetails` (mirrors `DetourReadoutDetails`):

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FocusSessionPopupTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun showsSessionReadoutForARecord() {
        val record = FocusSessionRecord(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(25 * 60_000),
            "write the plan",
            FocusClosureOutcome.COMPLETED,
        )
        rule.setContent {
            DayViewTheme {
                FocusSessionReadoutHost( // tiny test wrapper Box{ Column{ FocusSessionReadoutDetails(...) } } tagged FocusSessionPopup
                    record = record,
                    engaged = 25.minutes,
                    deepFocus = 18.minutes,
                    uses24Hour = true,
                )
            }
        }
        rule.onNodeWithTag(DayViewTestTags.FocusSessionPopup).assertIsDisplayed()
    }
}
```

> Match `DayViewTheme` usage and rule imports to the existing desktopTest files (e.g. the detour/busy pop-up tests). If a `@Rule` needs `@get:Rule val rule = createComposeRule()`, copy the exact preamble from a sibling test.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.FocusSessionPopupTest"`
Expected: FAIL — `FocusSessionReadoutDetails`/`FocusSessionReadoutHost` unresolved.

- [ ] **Step 4: Write minimal implementation**

In `DayViewTodayScreen.kt`:

1. Add the pop-up body composable next to `DetourReadoutDetails`:
```kotlin
/**
 * Body of a focus-session detail pop-up: the intention (mint, or a muted placeholder when
 * blank), the clock range, engaged time, deep-focus time (hidden when zero, e.g. a day with
 * no presence tracking), and how the session closed. Flows into the caller's Column.
 */
@Composable
internal fun ColumnScope.FocusSessionReadoutDetails(
    record: FocusSessionRecord,
    engaged: Duration,
    deepFocus: Duration,
    uses24Hour: Boolean,
) {
    val colors = LocalDayViewColors.current
    val intention = record.intention.trim()
    Text(
        if (intention.isEmpty()) stringResource(Res.string.focus_session_intention_empty) else intention,
        color = if (intention.isEmpty()) colors.muted else colors.mint,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
    Text(
        stringResource(Res.string.busy_time_range, formatClockHm(record.start, use24Hour = uses24Hour), formatClockHm(record.end, use24Hour = uses24Hour)),
        color = colors.muted,
        fontSize = 11.sp,
    )
    Text(stringResource(Res.string.focus_session_engaged, formatDurationHm(engaged)), color = colors.muted, fontSize = 11.sp)
    if (deepFocus > Duration.ZERO) {
        Text(stringResource(Res.string.focus_session_deep_focus, formatDurationHm(deepFocus)), color = colors.muted, fontSize = 11.sp)
    }
    Text(
        stringResource(
            if (record.outcome == FocusClosureOutcome.COMPLETED) Res.string.focus_session_outcome_completed else Res.string.focus_session_outcome_stopped,
        ),
        color = colors.muted,
        fontSize = 11.sp,
    )
}

/** Test-only host that renders the details inside a tagged box. */
@Composable
internal fun FocusSessionReadoutHost(
    record: FocusSessionRecord,
    engaged: Duration,
    deepFocus: Duration,
    uses24Hour: Boolean,
) {
    Box(Modifier.testTag(DayViewTestTags.FocusSessionPopup)) {
        Column { FocusSessionReadoutDetails(record, engaged, deepFocus, uses24Hour) }
    }
}
```

2. Add `focusSessionBands: List<FocusSessionBand> = emptyList()` to the `CountdownCircle` signature.

3. Draw the faint band **before** the `focusArcs.forEach` block (so mint arcs sit on top). Use the same `inset`/`arcSize`:
```kotlin
                    focusSessionBands.forEach { band ->
                        drawArc(
                            color = colors.mint.copy(alpha = .18f),
                            startAngle = band.startAngleDegrees,
                            sweepAngle = band.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth * .5f, cap = StrokeCap.Round),
                        )
                    }
```

4. Add hover state near `hoveredBusy`/`hoveredDetour`:
```kotlin
    var hoveredSession by remember { mutableStateOf<HoveredFocusSession?>(null) }
```
Define `internal data class HoveredFocusSession(val record: FocusSessionRecord, val engaged: Duration, val deepFocus: Duration, val position: Offset)` near `HoveredBusyArc`.

5. In the mouse pointer loop (the `circleModifier` `awaitPointerEventScope`), extend the guard condition so the block runs when `focusSessionBands` is non-empty too, and on `Move`/`Enter`, when no detour/busy is hit, hit-test the session band by angle:
```kotlin
                                val bandAngle = normalizeRingAngle(
                                    Math.toDegrees(atan2((position.y - size.height / 2f).toDouble(), (position.x - size.width / 2f).toDouble())).toFloat(),
                                )
                                val band = focusSessionBandAtAngle(focusSessionBands, bandAngle)
                                hoveredSession = band?.let {
                                    HoveredFocusSession(
                                        it.record,
                                        engagedTimeForSession(it.record, sessionIntervalsForDerivation),
                                        deepFocusTimeForSession(it.record, presenceIntervalsForDerivation),
                                        position,
                                    )
                                }
```
To supply `sessionIntervalsForDerivation`/`presenceIntervalsForDerivation`, add two `CountdownCircle` params `focusSessionIntervals: List<FocusPresenceInterval> = emptyList()` and `focusPresenceIntervals: List<FocusPresenceInterval> = emptyList()` and reference them here. Clear `hoveredSession = null` in the `Exit`/null-position branch alongside the others. Also include `focusSessionBands` in the `pointerInput(...)` key list so the gesture restarts when bands change.

6. Render the hover tooltip near the `hoveredDetour?.let { ... }` block:
```kotlin
                hoveredSession?.let { hovered ->
                    HoverTooltip(position = hovered.position, colors = colors) {
                        FocusSessionReadoutDetails(hovered.record, hovered.engaged, hovered.deepFocus, uses24Hour)
                    }
                }
```

7. For touch: pass `focusSessionBands` into `ringReadoutAt(...)` at ~1510 and add a session line to `RingScrubReadout` (after the `if (readout.focus)` block):
```kotlin
            readout.session?.let { record ->
                Text(
                    if (record.intention.isBlank()) stringResource(Res.string.focus_session_intention_empty) else record.intention.trim(),
                    color = colors.mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
```
Add `focusSessionBands` to the scrub `pointerInput(...)` key list.

- [ ] **Step 5: Run test + full shared UI suite**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.FocusSessionPopupTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt shared/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt shared/src/commonMain/composeResources/values/strings.xml shared/src/desktopTest/kotlin/fr/dayview/app/FocusSessionPopupTest.kt
git commit -m "Draw focus session band and show its detail pop-up on the ring"
```

---

## Task 12: Wire session bands from state into every ring surface

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt:305-375` (both `CountdownCircle` call sites)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/App.kt:86-197` (pass initial records into the controller if the desktop seeds them)
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt`, `HistoryWeekScreen.kt`, `MiniRing.kt` if they forward `focusArcs` to `CountdownCircle`/their ring
- Test: covered by the existing desktop UI smoke tests + the full gate

**Interfaces:**
- Consumes: `DayViewUiState.focusSessionBandsState`, `focusSessionIntervals`, `focusPresenceIntervals`.
- Produces: every `CountdownCircle` invocation passes `focusSessionBands`, `focusSessionIntervals`, `focusPresenceIntervals`.

- [ ] **Step 1: Wire the today ring**

At each `CountdownCircle(...)` call in `DayViewTodayScreen.kt` that already passes `focusArcs = state.focusArcsState`, add:
```kotlin
                            focusSessionBands = state.focusSessionBandsState,
                            focusSessionIntervals = state.focusSessionIntervals,
                            focusPresenceIntervals = state.focusPresenceIntervals,
```
For the history-day path (`HistoryDayScreen.kt`) the ring is built from a frozen `DayViewUiState`; pass the same three from that frozen state.

- [ ] **Step 2: Build to verify wiring compiles**

Run: `./gradlew :shared:desktopTest`
Expected: PASS (existing UI tests still green; no new behavior asserted here beyond compilation + smoke).

- [ ] **Step 3: Manually confirm derivation getters exist for the frozen state**

`focusSessionBandsState` is defined on `DayViewUiState` (Task 5), so both the live and frozen (`toFrozenUiState`) states expose it. Confirm `MiniRing` — if it renders session content — either receives bands or is intentionally left arc-only (the mini ring shows arcs only today; leaving it arc-only is acceptable and in-scope only if it currently shows focus arcs). If `MiniRing` is not changed, note it in the commit body.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat
git add shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt shared/src/commonMain/kotlin/fr/dayview/app/HistoryDayScreen.kt shared/src/commonMain/kotlin/fr/dayview/app/HistoryWeekScreen.kt shared/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "Pass focus session bands to the today and history rings"
```

---

## Task 13: Full gate + manual verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no stderr, all suites green.

- [ ] **Step 2: Manually verify on desktop**

Run: `./gradlew :shared:run`. Set an intention, start a short focus session (drop `pomodoroMinutes` if needed), let it complete or stop it. Confirm: a faint band appears on the ring over the session span; hovering it shows a pop-up with the intention, the time range, "Engaged …", "Deep focus …" (if presence was tracked), and a status line. Open History and confirm the same on the day/week rings.

- [ ] **Step 3: Commit (if any lint/format touch-ups were needed)**

```bash
git status --short   # expect clean; commit only if ktlintFormat changed files
```

---

## Self-Review

**Spec coverage:**
- §1 data model → Task 1 (model+codec), Task 3 (prefs), Task 4 (history), Task 5 (state). ✓
- §2 capture (closePomodoro/stopPomodoro, pre-clear intention, effective end, rollover, closeFocusSnapshot) → Task 5 + Task 6. ✓
- §3 derivation (engaged/deep via `focusedTime`) → Task 2. ✓
- §4 ring rendering + hit-test (band, RingReadout, hover/tap) → Task 2 (band+hit-test), Task 10 (readout), Task 11 (draw+hover+tap). ✓
- §5 pop-up content (intention/placeholder, time range, engaged, deep-focus hidden-when-zero, outcome status, i18n) → Task 11. ✓
- §6 surfaces + sync (both channels, no version bump, defaults) → Task 7 (contribution merge), Task 8 (history DTO), Task 9 (contribution DTO), Task 12 (surfaces). ✓
- §7 testing (codec round-trip, capture, derivation, disjoint/empty-presence, DTO round-trips incl. absent field, UI tag test) → Tasks 1,2,4,5,6,8,9,11. ✓
- Scope guards (no cleanliness, no backfill, band tunable) → honored; no cleanliness field added, capture only affects sessions closed after ship. ✓

**Placeholder scan:** No TBD/TODO. The only deferred specifics are test-harness helper names (`newStore`, `newController`, `sampleRecord`, `sampleHistoryRecord`) which are explicitly flagged to match the file's existing helpers — code is otherwise complete.

**Type consistency:** `FocusSessionRecord(start, end, intention, outcome)` used identically across all tasks. `FocusSessionBand(startAngleDegrees, sweepDegrees, record)` consistent in Tasks 2/10/11. `focusSessionBandAtAngle`, `engagedTimeForSession`, `deepFocusTimeForSession` names match between definition (Task 2) and use (Tasks 10/11). DTO names `FocusSessionRecordDto`, field `focusSessionRecords`/`records` consistent across Tasks 8/9. `outcome` serialized by `.name`, decoded by `FocusClosureOutcome.entries.firstOrNull { it.name == ... }` in every mapper.
