# Daily Detours — Orbital Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user declare named detours (motif + approximate duration) and draw them as small colored bodies threaded on the day ring, with a per-source tally and an editable day list — per the spec in `docs/superpowers/specs/2026-07-12-daily-detours-orbital-viz-design.md`.

**Architecture:** Everything lives in `commonMain`, mirroring the focus-presence pattern: a pure domain file (`Detours.kt`) with model, serialization, aggregation and ring geometry; three new fields on `DayPreferencesSnapshot` persisted by `DayPreferencesStore` (day-keyed episodes + recent motifs); controller methods on `DayViewController`; rendering added to `CountdownCircle`; capture and list dialogs in a new `DetoursUi.kt`. No platform-specific code.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, `kotlin.time.Instant`/`Duration`, DataStore preferences, kotlin.test (commonTest).

## Global Constraints

- JDK 21 toolchain; Android SDK present. Tests + lint before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Commit messages in English; never reference Claude/Anthropic/AI; never reference docs under `docs/superpowers/`.
- Domain time is `kotlin.time.Instant` / `Duration`; epoch millis appear only inside encode/decode functions.
- All new code in `composeApp/src/commonMain` (tests in `composeApp/src/commonTest`); zero `androidMain`/`desktopMain` changes.
- User-facing strings live in `composeApp/src/commonMain/composeResources/values/strings.xml` (French default wording, typographic apostrophe `’`), consumed via `stringResource(Res.string.<key>, …)` with one import per key from `fr.dayview.app.generated.resources`. Parameters are `%1$s` **plain strings** (pass numbers as `.toString()`; a literal percent stays a single `%`, never `%%`). Number/clock formatters (`formatDurationHm`, `formatClockHm`) stay plain functions.
- ktlint is enforced — run `./gradlew ktlintFormat` if a check fails on formatting.
- New preference keys must not collide with existing ones in `DayPreferenceKeys`.

---

### Task 1: Detour domain — model, sanitization, serialization, recents, day key

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`
- Create: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes: `kotlin.time.Instant`/`Duration`, `kotlinx.datetime` (already dependencies).
- Produces (used by Tasks 2–7):
  - `data class DetourEpisode(val start: Instant, val end: Instant, val motif: String)` with `val duration: Duration`
  - `fun sanitizeDetourMotif(raw: String): String`
  - `fun encodeDetours(episodes: List<DetourEpisode>): String` / `fun decodeDetours(encoded: String): List<DetourEpisode>`
  - `fun encodeRecentDetourMotifs(motifs: List<String>): String` / `fun decodeRecentDetourMotifs(encoded: String): List<String>`
  - `fun pushRecentDetourMotif(recents: List<String>, motif: String): List<String>` and `const val MAX_RECENT_DETOUR_MOTIFS = 10`
  - `fun dayKeyOf(now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long`

- [ ] **Step 1: Write the failing tests**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun t(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

class DetoursTest {
    @Test
    fun sanitizeStripsNewlinesTrimsAndBounds() {
        assertEquals("appel urgent", sanitizeDetourMotif("  appel\nurgent \r"))
        assertEquals(60, sanitizeDetourMotif("x".repeat(80)).length)
    }

    @Test
    fun detoursEncodeDecodeRoundTripsMotifsWithCommas() {
        val episodes = listOf(
            DetourEpisode(t(1_000L), t(2_000L), "Appel, urgent"),
            DetourEpisode(t(3_000L), t(4_000L), "Slack"),
        )
        assertEquals(episodes, decodeDetours(encodeDetours(episodes)))
    }

    @Test
    fun decodeSkipsMalformedBlankAndEmptyMotifLines() {
        val encoded = "not a line\n1000,2000,ok\n5000,4000,inverted\n1000,2000,\n\n"
        assertEquals(listOf(DetourEpisode(t(1_000L), t(2_000L), "ok")), decodeDetours(encoded))
    }

    @Test
    fun recentMotifsEncodeDecodeRoundTrips() {
        val motifs = listOf("Appel, urgent", "Slack")
        assertEquals(motifs, decodeRecentDetourMotifs(encodeRecentDetourMotifs(motifs)))
        assertEquals(emptyList(), decodeRecentDetourMotifs(""))
    }

    @Test
    fun pushRecentDedupesCaseInsensitivelyAndCaps() {
        val once = pushRecentDetourMotif(listOf("Slack", "Appels"), "slack")
        assertEquals(listOf("slack", "Appels"), once)
        var recents = emptyList<String>()
        repeat(15) { recents = pushRecentDetourMotif(recents, "motif $it") }
        assertEquals(MAX_RECENT_DETOUR_MOTIFS, recents.size)
        assertEquals("motif 14", recents.first())
    }

    @Test
    fun pushRecentIgnoresBlankMotifs() {
        assertEquals(listOf("Slack"), pushRecentDetourMotif(listOf("Slack"), "  \n"))
    }

    @Test
    fun dayKeyMatchesLocalCalendarDay() {
        val zone = TimeZone.of("Europe/Paris")
        // 2026-07-12 08:00 and 17:00 Paris are the same day; 2026-07-13 01:00 is the next.
        val morning = Instant.parse("2026-07-12T06:00:00Z")
        val evening = Instant.parse("2026-07-12T15:00:00Z")
        val nextDay = Instant.parse("2026-07-12T23:30:00Z")
        assertEquals(dayKeyOf(morning, zone), dayKeyOf(evening, zone))
        assertTrue(dayKeyOf(nextDay, zone) > dayKeyOf(evening, zone))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL to compile — `DetourEpisode`, `sanitizeDetourMotif`, etc. are unresolved.

- [ ] **Step 3: Write the implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt`:

```kotlin
package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** A hand-declared detour: a named stretch of time spent off the path. */
data class DetourEpisode(val start: Instant, val end: Instant, val motif: String) {
    val duration: Duration get() = end - start
}

const val MAX_RECENT_DETOUR_MOTIFS = 10

/** Single-line, trimmed, bounded motif; every capture and edit feeds through this. */
fun sanitizeDetourMotif(raw: String): String = raw.replace("\n", " ").replace("\r", " ").trim().take(60)

/** Serialize episodes to one `start,end,motif` line each (epoch millis, motif last). */
fun encodeDetours(episodes: List<DetourEpisode>): String = episodes.joinToString("\n") {
    "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},${sanitizeDetourMotif(it.motif)}"
}

/**
 * Inverse of [encodeDetours]. The motif is the third field and the split is bounded, so
 * commas inside motifs survive; blank, malformed, inverted or motif-less lines are skipped.
 */
fun decodeDetours(encoded: String): List<DetourEpisode> = encoded.split("\n").mapNotNull { line ->
    val parts = line.split(",", limit = 3)
    val start = parts.getOrNull(0)?.toLongOrNull()
    val end = parts.getOrNull(1)?.toLongOrNull()
    val motif = parts.getOrNull(2)?.let(::sanitizeDetourMotif)
    if (parts.size == 3 && start != null && end != null && end > start && !motif.isNullOrEmpty()) {
        DetourEpisode(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), motif)
    } else {
        null
    }
}

/** One motif per line; motifs are single-line by construction. */
fun encodeRecentDetourMotifs(motifs: List<String>): String = motifs.joinToString("\n")

/** Inverse of [encodeRecentDetourMotifs]; drops blank lines. */
fun decodeRecentDetourMotifs(encoded: String): List<String> = encoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/** Most-recent-first suggestion list: case-insensitive dedupe, capped. */
fun pushRecentDetourMotif(recents: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty()) return recents
    return (listOf(clean) + recents.filter { it.lowercase() != clean.lowercase() })
        .take(MAX_RECENT_DETOUR_MOTIFS)
}

/** Same day-key convention as the desktop presence loop: local epoch days. */
fun dayKeyOf(
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long = now.toLocalDateTime(timeZone).date.toEpochDays().toLong()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "feat: add detour episode model with day-scoped encoding"
```

---

### Task 2: Detour aggregation and ring geometry

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt` (append)
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt` (append)

**Interfaces:**
- Consumes: `DetourEpisode`, `sanitizeDetourMotif` (Task 1); `angleToInstant` convention from `CalendarNetTime.kt` (`-90f` = window start).
- Produces (used by Tasks 4–7):
  - `data class DetourSource(val label: String, val colorIndex: Int, val total: Duration)`
  - `fun detourSources(episodes: List<DetourEpisode>): List<DetourSource>` — heaviest first
  - `fun detoursTotal(episodes: List<DetourEpisode>): Duration`
  - `data class DetourBody(val angleDegrees: Float, val sizeFraction: Float, val colorIndex: Int, val motif: String, val start: Instant, val end: Instant)`
  - `fun detourBodies(windowStart: Instant, windowEnd: Instant, episodes: List<DetourEpisode>): List<DetourBody>`
  - `fun hitTestDetourBody(x: Float, y: Float, width: Int, height: Int, bodies: List<DetourBody>): DetourBody?`
  - `fun detourEpisodeAt(dayReference: Instant, startMinutesOfDay: Int, durationMinutes: Int, motif: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): DetourEpisode`

- [ ] **Step 1: Write the failing tests**

Append to `DetoursTest.kt` (inside `class DetoursTest`):

```kotlin
    @Test
    fun sourcesAggregateByNormalizedMotifHeaviestFirst() {
        val episodes = listOf(
            DetourEpisode(t(0L), t(1_200_000L), "Slack"), // 20 min
            DetourEpisode(t(2_000_000L), t(3_200_000L), "Appels"), // 20 min
            DetourEpisode(t(4_000_000L), t(4_900_000L), "slack"), // 15 min
        )
        val sources = detourSources(episodes)
        assertEquals(listOf("Slack", "Appels"), sources.map { it.label })
        assertEquals(listOf(0, 1), sources.map { it.colorIndex })
        assertEquals(35, sources.first().total.inWholeMinutes)
    }

    @Test
    fun sourceColorFollowsEarliestEpisodeAfterRetroactiveInsert() {
        val base = listOf(DetourEpisode(t(5_000_000L), t(6_000_000L), "Slack"))
        val withEarlier = base + DetourEpisode(t(0L), t(1_000_000L), "Appels")
        // "Appels" now starts the day, so it takes color 0 even though captured later.
        val sources = detourSources(withEarlier)
        assertEquals(0, sources.first { it.label == "Appels" }.colorIndex)
        assertEquals(1, sources.first { it.label == "Slack" }.colorIndex)
    }

    @Test
    fun detoursTotalSumsDurations() {
        val episodes = listOf(
            DetourEpisode(t(0L), t(600_000L), "a"), // 10 min
            DetourEpisode(t(0L), t(1_200_000L), "b"), // 20 min
        )
        assertEquals(30, detoursTotal(episodes).inWholeMinutes)
    }

    @Test
    fun bodiesSitAtTheEpisodeMidpointAngle() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        // 0 → 1 h episode: midpoint 30 min = 5 % of the window → -90° + 18°.
        val body = detourBodies(start, end, listOf(DetourEpisode(t(0L), t(3_600_000L), "Slack"))).single()
        assertEquals(-72f, body.angleDegrees, absoluteTolerance = .01f)
    }

    @Test
    fun bodySizeFractionClampsBetween5And60Minutes() {
        val start = t(0L)
        val end = t(36_000_000L)
        fun sizeOf(minutes: Long): Float = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(7_200_000L), t(7_200_000L + minutes * 60_000L), "x")),
        ).single().sizeFraction
        assertEquals(0f, sizeOf(5))
        assertEquals(1f, sizeOf(60))
        assertEquals(1f, sizeOf(90))
        assertEquals(.4909f, sizeOf(32), absoluteTolerance = .01f) // (32 − 5) / 55
    }

    @Test
    fun bodiesOutsideTheWindowAreDropped() {
        val start = t(10_000_000L)
        val end = t(20_000_000L)
        val before = DetourEpisode(t(0L), t(1_000_000L), "early")
        assertEquals(emptyList(), detourBodies(start, end, listOf(before)))
    }

    @Test
    fun hitTestFindsTheBodyUnderThePointer() {
        val body = DetourBody(
            angleDegrees = -90f,
            sizeFraction = 1f,
            colorIndex = 0,
            motif = "Slack",
            start = t(0L),
            end = t(1L),
        )
        // Top of a 400×400 dial, on the ring radius.
        assertEquals(body, hitTestDetourBody(200f, 25f, 400, 400, listOf(body)))
        // Center of the dial: not on the ring.
        assertEquals(null, hitTestDetourBody(200f, 200f, 400, 400, listOf(body)))
        // Bottom of the dial: on the ring but 180° away.
        assertEquals(null, hitTestDetourBody(200f, 378f, 400, 400, listOf(body)))
    }

    @Test
    fun detourEpisodeAtBuildsOnTheSameLocalDay() {
        val zone = TimeZone.of("Europe/Paris")
        val reference = Instant.parse("2026-07-12T10:00:00Z")
        val episode = detourEpisodeAt(reference, 9 * 60 + 30, 45, " appel ", zone)
        assertEquals("appel", episode.motif)
        assertEquals(45, episode.duration.inWholeMinutes)
        assertEquals("09:30", formatClockHm(episode.start, zone))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL to compile — `detourSources`, `DetourBody`, etc. are unresolved.

- [ ] **Step 3: Write the implementation**

Append to `Detours.kt` (add the new imports to the existing import block):

```kotlin
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.time.Duration.Companion.minutes
```

```kotlin
/** A distraction source: episodes grouped by normalized motif. */
data class DetourSource(val label: String, val colorIndex: Int, val total: Duration)

private fun sourceKey(motif: String): String = sanitizeDetourMotif(motif).lowercase()

/**
 * Per-source cumulated durations, heaviest first. Color indices follow the chronological
 * order of each source's earliest episode, so they stay stable across retroactive edits.
 * The label keeps the casing of the source's earliest episode.
 */
fun detourSources(episodes: List<DetourEpisode>): List<DetourSource> {
    val colorBySource = LinkedHashMap<String, Int>()
    val labelBySource = LinkedHashMap<String, String>()
    val totalBySource = LinkedHashMap<String, Duration>()
    for (episode in episodes.sortedBy { it.start }) {
        val key = sourceKey(episode.motif)
        if (key.isEmpty()) continue
        colorBySource.getOrPut(key) { colorBySource.size }
        labelBySource.getOrPut(key) { sanitizeDetourMotif(episode.motif) }
        totalBySource[key] = (totalBySource[key] ?: Duration.ZERO) + episode.duration
    }
    return colorBySource.keys.map { key ->
        DetourSource(
            label = labelBySource.getValue(key),
            colorIndex = colorBySource.getValue(key),
            total = totalBySource.getValue(key),
        )
    }.sortedByDescending { it.total }
}

/** Total declared detour time (raw durations; episodes are day-scoped by construction). */
fun detoursTotal(episodes: List<DetourEpisode>): Duration = episodes.fold(Duration.ZERO) { acc, episode -> acc + episode.duration }

/** A detour episode projected on the ring, ready to draw. */
data class DetourBody(
    val angleDegrees: Float,
    val sizeFraction: Float,
    val colorIndex: Int,
    val motif: String,
    val start: Instant,
    val end: Instant,
)

private val MIN_BODY_DURATION = 5.minutes
private val MAX_BODY_DURATION = 60.minutes

/**
 * Project episodes to bodies threaded on the ring: angle at the episode midpoint
 * (same `-90° = window start` convention as [busyArcs]), size fraction 0..1 from the
 * duration clamped to [5 min, 60 min]. Episodes whose midpoint falls outside the
 * window are dropped.
 */
fun detourBodies(
    windowStart: Instant,
    windowEnd: Instant,
    episodes: List<DetourEpisode>,
): List<DetourBody> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorBySource = detourSources(episodes).associate { sourceKey(it.label) to it.colorIndex }
    return episodes.sortedBy { it.start }.mapNotNull { episode ->
        val colorIndex = colorBySource[sourceKey(episode.motif)] ?: return@mapNotNull null
        val midpoint = episode.start + episode.duration / 2
        if (midpoint < windowStart || midpoint > windowEnd) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
        val sizeFraction = ((episode.duration - MIN_BODY_DURATION) / (MAX_BODY_DURATION - MIN_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
        DetourBody(
            angleDegrees = -90f + fraction * 360f,
            sizeFraction = sizeFraction,
            colorIndex = colorIndex,
            motif = sanitizeDetourMotif(episode.motif),
            start = episode.start,
            end = episode.end,
        )
    }
}

private fun angularDistance(a: Float, b: Float): Float {
    val d = (((a - b) % 360f) + 360f) % 360f
    return minOf(d, 360f - d)
}

/**
 * The body under the pointer, or null. Bodies are centered on the ring circle; the
 * radial band matches the busy-arc hit test and the angular tolerance grows with the
 * body size so small bodies stay hoverable.
 */
fun hitTestDetourBody(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    bodies: List<DetourBody>,
): DetourBody? {
    val dx = x - width / 2f
    val dy = y - height / 2f
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.78f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return bodies
        .minByOrNull { angularDistance(it.angleDegrees, angle) }
        ?.takeIf { angularDistance(it.angleDegrees, angle) <= 7f + 5f * it.sizeFraction }
}

/** Build an episode on the same local day as [dayReference], for the list editor. */
fun detourEpisodeAt(
    dayReference: Instant,
    startMinutesOfDay: Int,
    durationMinutes: Int,
    motif: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DetourEpisode {
    val local = dayReference.toLocalDateTime(timeZone)
    val safeMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 59)
    val start = LocalDateTime(
        year = local.year,
        month = local.month,
        day = local.day,
        hour = safeMinutes / 60,
        minute = safeMinutes % 60,
    ).toInstant(timeZone)
    return DetourEpisode(
        start = start,
        end = start + durationMinutes.coerceIn(1, 12 * 60).minutes,
        motif = sanitizeDetourMotif(motif),
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DetoursTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "feat: aggregate detour sources and project ring bodies"
```

---

### Task 3: Persist detours in the day preferences

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` (snapshot fields)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt` (keys + persist + read)
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt` (round-trip test)

**Interfaces:**
- Consumes: `DetourEpisode`, `encodeDetours`, `decodeDetours`, `encodeRecentDetourMotifs`, `decodeRecentDetourMotifs` (Task 1).
- Produces (used by Task 4): `DayPreferencesSnapshot.detoursDayKey: Long` (default `-1L`), `.detours: List<DetourEpisode>` (default empty), `.recentDetourMotifs: List<String>` (default empty).

- [ ] **Step 1: Write the failing test**

Append to `DayPreferencesStoreTest.kt` (inside the class):

```kotlin
    @Test
    fun detoursRoundTrip() = runTest {
        val store = newStore(FakeFileSystem())
        val snapshot = DayPreferencesSnapshot(
            detoursDayKey = 20_646L,
            detours = listOf(
                DetourEpisode(
                    Instant.fromEpochMilliseconds(1_000L),
                    Instant.fromEpochMilliseconds(2_000L),
                    "Appel, urgent",
                ),
            ),
            recentDetourMotifs = listOf("Appel, urgent", "Slack"),
        )
        store.persist(snapshot)
        assertEquals(snapshot, store.snapshots.first())
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: FAIL to compile — `detoursDayKey` is not a `DayPreferencesSnapshot` parameter.

- [ ] **Step 3: Add the snapshot fields**

In `DayPreferences.kt`, extend `DayPreferencesSnapshot` (after `onGoalApps`):

```kotlin
data class DayPreferencesSnapshot(
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 18 * 60,
    val showSeconds: Boolean = true,
    val soundSettings: SoundSettings = SoundSettings(),
    val goalTitle: String = "",
    val goalDeadline: Instant? = null,
    val goalStart: Instant? = null,
    val pomodoroMinutes: Int = 25,
    val pomodoroEnd: Instant? = null,
    val focusIntention: String = "",
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val onGoalApps: Set<AppRef> = emptySet(),
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val recentDetourMotifs: List<String> = emptyList(),
)
```

- [ ] **Step 4: Add the store keys and wiring**

In `DayPreferencesStore.kt`:

Add to `DayPreferenceKeys` (before `NO_DEADLINE`):

```kotlin
    const val DETOURS_DAY = "detours_day"
    const val DETOURS = "detours"
    const val DETOUR_RECENT_MOTIFS = "detour_recent_motifs"
```

Add the key vals (after `onGoalAppsKey`):

```kotlin
private val detoursDayKey = longPreferencesKey(DayPreferenceKeys.DETOURS_DAY)
private val detoursKey = stringPreferencesKey(DayPreferenceKeys.DETOURS)
private val detourRecentMotifsKey = stringPreferencesKey(DayPreferenceKeys.DETOUR_RECENT_MOTIFS)
```

In `persist`, after the `onGoalAppsKey` line:

```kotlin
            prefs[detoursDayKey] = snapshot.detoursDayKey
            prefs[detoursKey] = encodeDetours(snapshot.detours)
            prefs[detourRecentMotifsKey] = encodeRecentDetourMotifs(snapshot.recentDetourMotifs)
```

In `Preferences.toSnapshot()`, after the `onGoalApps` line:

```kotlin
        detoursDayKey = this[detoursDayKey] ?: -1L,
        detours = decodeDetours(this[detoursKey].orEmpty()),
        recentDetourMotifs = decodeRecentDetourMotifs(this[detourRecentMotifsKey].orEmpty()),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesStoreTest"`
Expected: BUILD SUCCESSFUL, all tests pass (including the existing default-values test, since all new fields default to empty/-1).

- [ ] **Step 6: Lint, full suites, commit**

```bash
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesStoreTest.kt
git commit -m "feat: persist detours in day preferences"
```

---

### Task 4: Controller — detour state, capture, edit, day rollover

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt`

**Interfaces:**
- Consumes: snapshot fields (Task 3); `dayKeyOf`, `pushRecentDetourMotif`, `sanitizeDetourMotif`, `detourBodies`, `detourSources`, `detoursTotal` (Tasks 1–2).
- Produces (used by Tasks 5–7):
  - `DayViewUiState.detoursDayKey/detours/recentDetourMotifs` (persisted fields)
  - `DayViewUiState.detoursToday: List<DetourEpisode>` — empty when the stored day key is stale
  - `DayViewUiState.detourBodiesState: List<DetourBody>`, `.detourSourcesState: List<DetourSource>`, `.detoursTotalToday: Duration`
  - `DayViewController.addDetour(motif: String, durationMinutes: Int)`
  - `DayViewController.updateDetour(index: Int, episode: DetourEpisode)` — index into `detoursToday`
  - `DayViewController.removeDetour(index: Int)`
  - `DayViewController.addDetourEpisode(episode: DetourEpisode)` — retroactive add, pushes recents

- [ ] **Step 1: Write the failing tests**

Append to `DayViewControllerTest.kt` (inside the class; `t` and `testController` already exist at file scope). Add `import kotlin.time.Duration.Companion.minutes` and `import kotlin.test.assertTrue` to the imports if absent:

```kotlin
    @Test
    fun addDetourStoresAnEpisodeEndingNowAndPersistsIt() {
        val preferences = InMemoryDayPreferences()
        val now = 1_800_000_000_000L // fixed instant well inside a day
        val controller = testController(preferences, now)

        controller.addDetour(" appel\nurgent ", 30)

        val stored = preferences.current
        assertEquals(dayKeyOf(t(now)), stored.detoursDayKey)
        val episode = stored.detours.single()
        assertEquals("appel urgent", episode.motif)
        assertEquals(t(now), episode.end)
        assertEquals(30, episode.duration.inWholeMinutes)
        assertEquals(listOf("appel urgent"), stored.recentDetourMotifs)
    }

    @Test
    fun addDetourIgnoresBlankMotifs() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 1_800_000_000_000L)
        controller.addDetour("   ", 15)
        assertEquals(emptyList(), controller.state.detoursToday)
    }

    @Test
    fun addDetourOnANewDayReplacesTheStaleList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences(
            DayPreferencesSnapshot(
                detoursDayKey = dayKeyOf(t(now)) - 1L,
                detours = listOf(DetourEpisode(t(1_000L), t(2_000L), "hier")),
            ),
        )
        val controller = testController(preferences, now)

        assertEquals(emptyList(), controller.state.detoursToday)
        controller.addDetour("Slack", 15)
        assertEquals(listOf("Slack"), controller.state.detoursToday.map { it.motif })
        assertEquals(1, preferences.current.detours.size)
    }

    @Test
    fun capturingTheSameMotifTwiceKeepsOneRecentEntry() {
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, 1_800_000_000_000L)
        controller.addDetour("Slack", 15)
        controller.addDetour("slack", 15)
        assertEquals(listOf("slack"), controller.state.recentDetourMotifs)
        assertEquals(2, controller.state.detoursToday.size)
    }

    @Test
    fun updateAndRemoveDetourEditTodayList() {
        val now = 1_800_000_000_000L
        val preferences = InMemoryDayPreferences()
        val controller = testController(preferences, now)
        controller.addDetour("Slack", 15)
        controller.addDetourEpisode(DetourEpisode(t(now - 3_600_000L), t(now - 1_800_000L), "Appels"))

        // Episodes are kept sorted by start: "Appels" (1 h ago) precedes "Slack" (15 min ago).
        assertEquals(listOf("Appels", "Slack"), controller.state.detoursToday.map { it.motif })

        controller.updateDetour(0, DetourEpisode(t(now - 3_600_000L), t(now - 900_000L), "Réunion"))
        assertEquals(listOf("Réunion", "Slack"), controller.state.detoursToday.map { it.motif })
        assertEquals(45, controller.state.detoursToday.first().duration.inWholeMinutes)

        controller.removeDetour(1)
        assertEquals(listOf("Réunion"), preferences.current.detours.map { it.motif })
    }

    @Test
    fun invalidDetourEditsAreRejected() {
        val now = 1_800_000_000_000L
        val controller = testController(InMemoryDayPreferences(), now)
        controller.addDetour("Slack", 15)

        controller.updateDetour(5, DetourEpisode(t(1L), t(2L), "hors limites"))
        controller.updateDetour(0, DetourEpisode(t(2_000L), t(1_000L), "inversé"))
        controller.updateDetour(0, DetourEpisode(t(1_000L), t(2_000L), "  "))

        assertEquals(listOf("Slack"), controller.state.detoursToday.map { it.motif })
        assertTrue(controller.state.detoursTotalToday.inWholeMinutes == 15L)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: FAIL to compile — `addDetour`, `detoursToday`, etc. are unresolved.

- [ ] **Step 3: Implement the controller changes**

In `DayViewController.kt`:

Add to `DayViewUiState` constructor (after `lastFocusClosure`, before `destination`):

```kotlin
    val detoursDayKey: Long = -1L,
    val detours: List<DetourEpisode> = emptyList(),
    val recentDetourMotifs: List<String> = emptyList(),
```

Add derived properties to `DayViewUiState` (after `focusedToday`):

```kotlin
    /** Episodes of the current local day; stale storage from a previous day reads as empty. */
    val detoursToday: List<DetourEpisode>
        get() = if (detoursDayKey == dayKeyOf(dayNow)) detours else emptyList()

    val detourBodiesState: List<DetourBody>
        get() {
            val (start, end) = dayWindow
            return detourBodies(start, end, detoursToday)
        }

    val detourSourcesState: List<DetourSource>
        get() = detourSources(detoursToday)

    val detoursTotalToday: Duration
        get() = detoursTotal(detoursToday)
```

Add methods to `DayViewController` (after `setFocusPresenceIntervals`):

```kotlin
    /** Quick capture: the episode ends now and starts [durationMinutes] earlier. */
    fun addDetour(motif: String, durationMinutes: Int) {
        val clean = sanitizeDetourMotif(motif)
        if (clean.isEmpty()) return
        val end = state.now
        val windowStart = state.dayWindow.first
        var start = end - durationMinutes.coerceIn(1, 12 * 60).minutes
        if (start < windowStart && end > windowStart) start = windowStart
        commitDetours(state.detoursToday + DetourEpisode(start, end, clean), pushMotif = clean)
    }

    /** Retroactive add from the list editor; also feeds the suggestions. */
    fun addDetourEpisode(episode: DetourEpisode) {
        val clean = episode.copy(motif = sanitizeDetourMotif(episode.motif))
        if (clean.motif.isEmpty() || clean.end <= clean.start) return
        commitDetours(state.detoursToday + clean, pushMotif = clean.motif)
    }

    /** Replace the episode at [index] of [DayViewUiState.detoursToday]. */
    fun updateDetour(
        index: Int,
        episode: DetourEpisode,
    ) {
        val today = state.detoursToday
        if (index !in today.indices) return
        val clean = episode.copy(motif = sanitizeDetourMotif(episode.motif))
        if (clean.motif.isEmpty() || clean.end <= clean.start) return
        commitDetours(today.toMutableList().also { it[index] = clean })
    }

    fun removeDetour(index: Int) {
        val today = state.detoursToday
        if (index !in today.indices) return
        commitDetours(today.toMutableList().also { it.removeAt(index) })
    }

    private fun commitDetours(
        episodes: List<DetourEpisode>,
        pushMotif: String? = null,
    ) {
        state = state.copy(
            detoursDayKey = dayKeyOf(state.now),
            detours = episodes.sortedBy { it.start },
            recentDetourMotifs = pushMotif
                ?.let { pushRecentDetourMotif(state.recentDetourMotifs, it) }
                ?: state.recentDetourMotifs,
        )
        persistState()
    }
```

Thread the three fields through the three snapshot mappers at the bottom of the file:

In `DayViewUiState.toSnapshot()` (after `onGoalApps = onGoalApps,`):

```kotlin
    detoursDayKey = detoursDayKey,
    detours = detours,
    recentDetourMotifs = recentDetourMotifs,
```

In `DayPreferencesSnapshot.coerced()` add to the `copy(...)`:

```kotlin
        detours = detours.map { it.copy(motif = sanitizeDetourMotif(it.motif)) },
        recentDetourMotifs = recentDetourMotifs.take(MAX_RECENT_DETOUR_MOTIFS),
```

In `DayPreferencesSnapshot.toUiState()` (after `onGoalApps = safe.onGoalApps,`):

```kotlin
        detoursDayKey = safe.detoursDayKey,
        detours = safe.detours,
        recentDetourMotifs = safe.recentDetourMotifs,
```

In `DayViewUiState.withPersisted()` (after `onGoalApps = safe.onGoalApps,`):

```kotlin
        detoursDayKey = safe.detoursDayKey,
        detours = safe.detours,
        recentDetourMotifs = safe.recentDetourMotifs,
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayViewControllerTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Lint, full suites, commit**

```bash
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt composeApp/src/commonTest/kotlin/fr/dayview/app/DayViewControllerTest.kt
git commit -m "feat: manage day detours from the controller"
```

---

### Task 5: Ring rendering — palette, bodies, sun halo, total line, hover

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt` (palette)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (`CountdownCircle` + call sites)

**Interfaces:**
- Consumes: `DetourBody`, `hitTestDetourBody`, `formatDurationHm`, `formatClockHm` (Tasks 1–2); `DayViewUiState.detourBodiesState/.detoursTotalToday` (Task 4).
- Produces: `DayViewColors.detours: List<Color>`; `CountdownCircle` params `detourBodies: List<DetourBody> = emptyList()`, `detoursTotal: Duration = Duration.ZERO`, `hasGoal: Boolean = false`. Defaults keep the mini window (`DayViewMiniApp`) and any other caller unchanged.

- [ ] **Step 1: Add the palette to the theme**

In `DayViewTheme.kt`, add a `detours` field to `DayViewColors`:

```kotlin
internal data class DayViewColors(
    val ink: Color,
    val panel: Color,
    val cloud: Color,
    val muted: Color,
    val mint: Color,
    val amber: Color,
    val red: Color,
    val glow: Color,
    val overlay: Color,
    val detours: List<Color>,
)
```

Add to `DarkDayViewColors`:

```kotlin
    detours = listOf(
        Color(0xFFFFB86B), // amber
        Color(0xFFE7CE6B), // gold
        Color(0xFFF2856D), // coral
        Color(0xFFE58FB6), // rose
        Color(0xFFB48EE0), // plum
        Color(0xFFD9B08C), // sand
    ),
```

Add to `LightDayViewColors`:

```kotlin
    detours = listOf(
        Color(0xFFB76218), // amber
        Color(0xFF8F7A1C), // gold
        Color(0xFFB0492F), // coral
        Color(0xFFA34D74), // rose
        Color(0xFF6E4AA3), // plum
        Color(0xFF8A6844), // sand
    ),
```

- [ ] **Step 2: Add the string resources**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, before `</resources>`:

```xml
    <!-- Detours -->
    <string name="detours_today">Détours %1$s</string>
    <string name="detour_time_range">%1$s – %2$s · %3$s</string>
```

And in `DayViewTodayScreen.kt`, add the key imports (the `Res` object and `stringResource` are already imported there):

```kotlin
import fr.dayview.app.generated.resources.detour_time_range
import fr.dayview.app.generated.resources.detours_today
```

- [ ] **Step 3: Extend `CountdownCircle`**

In `DayViewTodayScreen.kt`, change the `CountdownCircle` signature:

```kotlin
@Composable
internal fun CountdownCircle(
    progress: DayProgress,
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
    busyArcs: List<BusyArc> = emptyList(),
    netTime: NetTime? = null,
    focusArcs: List<FocusArc> = emptyList(),
    focusedToday: Duration = Duration.ZERO,
    windowStart: Instant = Instant.fromEpochMilliseconds(0L),
    windowEnd: Instant = Instant.fromEpochMilliseconds(0L),
    detourBodies: List<DetourBody> = emptyList(),
    detoursTotal: Duration = Duration.ZERO,
    hasGoal: Boolean = false,
) {
```

(The new parameters go **after** `windowEnd` so existing call sites — including the mini window — keep compiling unchanged.)

Add the hover state next to `hoveredBusy`:

```kotlin
    var hoveredDetour by remember { mutableStateOf<HoveredDetourBody?>(null) }
```

Replace the `circleModifier` gate so bodies are also hit-tested (detour hit wins over the arc underneath it):

```kotlin
            val circleModifier = if (busyArcs.isEmpty() && detourBodies.isEmpty()) {
                Modifier.size(circleSize)
            } else {
                Modifier.size(circleSize).pointerInput(busyArcs, detourBodies) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val position = event.changes.firstOrNull()?.position
                            if (event.type == PointerEventType.Exit || position == null) {
                                hoveredBusy = null
                                hoveredDetour = null
                            } else if (
                                event.type == PointerEventType.Move ||
                                event.type == PointerEventType.Enter
                            ) {
                                val body = hitTestDetourBody(position.x, position.y, size.width, size.height, detourBodies)
                                hoveredDetour = body?.let { HoveredDetourBody(it, position) }
                                hoveredBusy = if (body != null) {
                                    null
                                } else {
                                    hitTestBusyArc(position, size.width, size.height, busyArcs)
                                        ?.let { HoveredBusyArc(it, position) }
                                }
                            }
                        }
                    }
                }
            }
```

Inside the `Canvas` block, immediately after the first background `drawArc` (the 360° track), draw the sun halo:

```kotlin
                    if (hasGoal) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(colors.amber.copy(alpha = .10f), Color.Transparent),
                                center = center,
                                radius = size.minDimension * .30f,
                            ),
                            radius = size.minDimension * .30f,
                            center = center,
                        )
                    }
```

At the end of the `Canvas` block (after the `if (animatedRemaining > 0f) { ... }` block, so bodies draw above the arcs and marker), draw the bodies:

```kotlin
                    detourBodies.forEach { body ->
                        val angleRadians = Math.toRadians(body.angleDegrees.toDouble())
                        val arcRadius = arcSize.width / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val bodyCenter = center + Offset(
                            x = (kotlin.math.cos(angleRadians) * arcRadius).toFloat(),
                            y = (kotlin.math.sin(angleRadians) * arcRadius).toFloat(),
                        )
                        val color = colors.detours[body.colorIndex % colors.detours.size]
                        val radius = strokeWidth * (.3f + .38f * body.sizeFraction)
                        drawCircle(color = color.copy(alpha = .25f), radius = radius * 1.45f, center = bodyCenter)
                        drawCircle(color = color, radius = radius, center = bodyCenter)
                    }
```

In the center `Column`, after the `if (focusedToday > Duration.ZERO) { ... }` block, add the total line:

```kotlin
                        if (detoursTotal > Duration.ZERO) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(Res.string.detours_today, formatDurationHm(detoursTotal)),
                                color = colors.amber,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = .5.sp,
                            )
                        }
```

After the existing `hoveredBusy?.let { ... }` overlay, add the detour tooltip:

```kotlin
                hoveredDetour?.let { hovered ->
                    val body = hovered.body
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(
                                    hovered.position.x.roundToInt() + 14,
                                    hovered.position.y.roundToInt() + 14,
                                )
                            }
                            .background(colors.panel, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Text(body.motif, color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    Res.string.detour_time_range,
                                    formatClockHm(body.start),
                                    formatClockHm(body.end),
                                    formatDurationHm(body.end - body.start),
                                ),
                                color = colors.muted,
                                fontSize = 11.sp,
                                letterSpacing = .5.sp,
                            )
                        }
                    }
                }
```

Add the holder data class next to `HoveredBusyArc`:

```kotlin
private data class HoveredDetourBody(val body: DetourBody, val position: Offset)
```

- [ ] **Step 4: Feed the new params at both call sites**

In `DayViewScreen` (`DayViewTodayScreen.kt`), both `CountdownCircle(...)` calls (wide and compact) gain, after `windowEnd = state.dayWindow.second,`:

```kotlin
                            detourBodies = state.detourBodiesState,
                            detoursTotal = state.detoursTotalToday,
                            hasGoal = state.goalTitle.isNotBlank() || state.goalDeadline != null,
```

(Adjust indentation to each call site; the compact call uses one level less.)

- [ ] **Step 5: Build, lint, verify visually**

```bash
./gradlew ktlintCheck :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL (rendering has no unit harness; the pure geometry was tested in Task 2).

Then run the desktop app and check with a goal set: the halo is visible but subtle, and no bodies appear yet (capture UI lands in Task 6):

```bash
./gradlew :composeApp:run
```

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTheme.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/composeResources/values/strings.xml
git commit -m "feat: draw detour bodies and totals on the day ring"
```

---

### Task 6: Capture UI — tally row, capture dialog, wiring

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (actions + layout insertion)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (wire actions)

**Interfaces:**
- Consumes: `DetourSource`, `formatDurationHm` (Task 2); controller methods (Task 4); `GoalTextField`, `FocusActionButton`, `LocalDayViewColors` (existing).
- Produces:
  - `DetourRow(sources: List<DetourSource>, onOpenList: () -> Unit, onCapture: () -> Unit)` — tally (top 3 + `+n`) + `+ DÉTOUR` affordance
  - `DetourCaptureDialog(recentMotifs: List<String>, onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit)`
  - `DetourChip(label: String, selected: Boolean, onClick: () -> Unit)` (internal, reused by Task 7)
  - `DayViewScreenActions` gains `addDetour: (String, Int) -> Unit`, `updateDetour: (Int, DetourEpisode) -> Unit`, `removeDetour: (Int) -> Unit`, `addDetourEpisode: (DetourEpisode) -> Unit`

- [ ] **Step 1: Add the capture string resources**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, extend the `<!-- Detours -->` block added in Task 5:

```xml
    <string name="detour_add_button">+ DÉTOUR</string>
    <string name="detour_capture_open_label">Déclarer un détour</string>
    <string name="detour_list_open_label">Détours du jour</string>
    <string name="detour_section">DÉTOUR</string>
    <string name="detour_capture_prompt">Qu’est-ce qui vous a détourné du chemin ?</string>
    <string name="detour_motif_label">Motif du détour</string>
    <string name="detour_motif_placeholder">Ex. appel imprévu</string>
    <string name="detour_duration_section">DURÉE APPROXIMATIVE</string>
    <string name="detour_minutes_chip">%1$s min</string>
    <string name="detour_source_total">%1$s %2$s</string>
    <string name="detour_overflow">+%1$s</string>
    <string name="detour_cancel_button">ANNULER</string>
    <string name="detour_confirm_button">AJOUTER</string>
```

- [ ] **Step 2: Create `DetoursUi.kt` with the row, chip and capture dialog**

```kotlin
package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_add_button
import fr.dayview.app.generated.resources.detour_cancel_button
import fr.dayview.app.generated.resources.detour_capture_open_label
import fr.dayview.app.generated.resources.detour_capture_prompt
import fr.dayview.app.generated.resources.detour_confirm_button
import fr.dayview.app.generated.resources.detour_duration_section
import fr.dayview.app.generated.resources.detour_list_open_label
import fr.dayview.app.generated.resources.detour_minutes_chip
import fr.dayview.app.generated.resources.detour_motif_label
import fr.dayview.app.generated.resources.detour_motif_placeholder
import fr.dayview.app.generated.resources.detour_overflow
import fr.dayview.app.generated.resources.detour_section
import fr.dayview.app.generated.resources.detour_source_total
import org.jetbrains.compose.resources.stringResource

/** Per-source tally under the dial plus the capture affordance. */
@Composable
internal fun DetourRow(
    sources: List<DetourSource>,
    onOpenList: () -> Unit,
    onCapture: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sources.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_list_open_label), onClick = onOpenList)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                sources.take(3).forEachIndexed { index, source ->
                    if (index > 0) Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(7.dp)
                            .background(colors.detours[source.colorIndex % colors.detours.size], CircleShape),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(Res.string.detour_source_total, source.label, formatDurationHm(source.total)),
                        color = colors.muted,
                        fontSize = 11.sp,
                    )
                }
                if (sources.size > 3) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.detour_overflow, (sources.size - 3).toString()),
                        color = colors.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            stringResource(Res.string.detour_add_button),
            color = colors.muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_capture_open_label), onClick = onCapture)
                .padding(vertical = 8.dp, horizontal = 6.dp),
        )
    }
}

/** Small selectable pill used for suggestions and duration picks. */
@Composable
internal fun DetourChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Text(
        label,
        color = if (selected) colors.ink else colors.cloud,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) colors.amber else colors.overlay.copy(alpha = .07f),
                RoundedCornerShape(9.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val DETOUR_DURATION_CHOICES = listOf(5, 15, 30, 45, 60)

/** Quick capture: required motif, recent-motif suggestions, quick duration picks. */
@Composable
internal fun DetourCaptureDialog(
    recentMotifs: List<String>,
    onConfirm: (motif: String, durationMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var motif by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableIntStateOf(15) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Text(stringResource(Res.string.detour_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.detour_capture_prompt),
                color = colors.cloud,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            GoalTextField(
                value = motif,
                semanticLabel = stringResource(Res.string.detour_motif_label),
                placeholder = stringResource(Res.string.detour_motif_placeholder),
                onValueChange = { motif = it },
            )
            if (recentMotifs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    recentMotifs.take(6).forEachIndexed { index, recent ->
                        if (index > 0) Spacer(Modifier.width(7.dp))
                        DetourChip(recent, selected = recent == motif) { motif = recent }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(Res.string.detour_duration_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Row {
                DETOUR_DURATION_CHOICES.forEachIndexed { index, minutes ->
                    if (index > 0) Spacer(Modifier.width(7.dp))
                    DetourChip(
                        stringResource(Res.string.detour_minutes_chip, minutes.toString()),
                        selected = minutes == durationMinutes,
                    ) { durationMinutes = minutes }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
                FocusActionButton(
                    stringResource(Res.string.detour_confirm_button),
                    colors.amber,
                    modifier = Modifier.weight(1f),
                    enabled = motif.isNotBlank(),
                    filled = true,
                    onClick = { onConfirm(motif, durationMinutes) },
                )
            }
        }
    }
}
```

- [ ] **Step 3: Extend the actions and insert the row in both layouts**

In `DayViewTodayScreen.kt`, extend `DayViewScreenActions`:

```kotlin
internal data class DayViewScreenActions(
    val openSettings: () -> Unit,
    val openMiniWindow: (() -> Unit)?,
    val changeGoalTitle: (String) -> Unit,
    val changeGoalDeadline: (String) -> Unit,
    val commitGoalDeadline: () -> Unit,
    val changeGoalStart: (String) -> Unit,
    val commitGoalStart: () -> Unit,
    val changeFocusIntention: (String) -> Unit,
    val changePomodoroDuration: (Int) -> Unit,
    val startPomodoro: () -> Unit,
    val stopPomodoro: () -> Unit,
    val closePomodoro: (FocusClosureOutcome) -> Unit,
    val addDetour: (String, Int) -> Unit,
    val updateDetour: (Int, DetourEpisode) -> Unit,
    val removeDetour: (Int) -> Unit,
    val addDetourEpisode: (DetourEpisode) -> Unit,
)
```

In `DayViewScreen`, add dialog state at the top of the composable (next to `val progress = state.dayProgress`):

```kotlin
    var showDetourCapture by remember { mutableStateOf(false) }
    var showDetourList by remember { mutableStateOf(false) }
```

Insert the row in the **wide** layout, right after the `CountdownCircle(...)` call and before `Spacer(Modifier.height(12.dp))` + `GlobalGoalPanel`:

```kotlin
                        Spacer(Modifier.height(6.dp))
                        DetourRow(
                            sources = state.detourSourcesState,
                            onOpenList = { showDetourList = true },
                            onCapture = { showDetourCapture = true },
                        )
```

Insert in the **compact** layout, right after its `CountdownCircle(...)` call and before `Spacer(Modifier.height(12.dp))` + `CompactTodayContent`:

```kotlin
                Spacer(Modifier.height(6.dp))
                DetourRow(
                    sources = state.detourSourcesState,
                    onOpenList = { showDetourList = true },
                    onCapture = { showDetourCapture = true },
                )
```

At the end of `DayViewScreen`'s `BoxWithConstraints` body (after the closing `else` block containing the compact layout), add the dialog hosts (the list dialog arrives in Task 7 — for now only capture):

```kotlin
        if (showDetourCapture) {
            DetourCaptureDialog(
                recentMotifs = state.recentDetourMotifs,
                onConfirm = { motif, durationMinutes ->
                    actions.addDetour(motif, durationMinutes)
                    showDetourCapture = false
                },
                onDismiss = { showDetourCapture = false },
            )
        }
```

For this task, `showDetourList` toggles state but shows nothing yet — the variable is written by `onOpenList` and read by nothing until Task 7 adds the dialog host. That compiles cleanly; leave it as is.

- [ ] **Step 4: Wire the actions in `App.kt`**

In the `DayViewScreenActions(...)` construction inside `App.kt`, after `closePomodoro = { ... },`:

```kotlin
                        addDetour = { motif, durationMinutes -> controller.addDetour(motif, durationMinutes) },
                        updateDetour = { index, episode -> controller.updateDetour(index, episode) },
                        removeDetour = { controller.removeDetour(it) },
                        addDetourEpisode = { controller.addDetourEpisode(it) },
```

- [ ] **Step 5: Build, lint, verify by hand**

```bash
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
./gradlew :composeApp:run
```

Manual check on desktop: `+ DÉTOUR` under the dial opens the dialog; adding "appel client" for 30 min draws an amber body on the ring at the last half hour, shows "Détours 30 min" under the countdown and "appel client 30 min" in the tally; hovering the body shows the tooltip; restarting the app restores everything.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt composeApp/src/commonMain/composeResources/values/strings.xml
git commit -m "feat: capture detours from the today screen"
```

---

### Task 7: Retouch — the day's detours list dialog

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt` (append the list dialog)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (host the dialog)

**Interfaces:**
- Consumes: `detourEpisodeAt`, `formatClockHm`, `formatDurationHm` (Tasks 1–2); `DetourChip`, actions (Task 6); `TimeButton` (existing in `DayViewTodayScreen.kt`, already `internal`).
- Produces: `DetourListDialog(episodes: List<DetourEpisode>, now: Instant, onUpdate: (Int, DetourEpisode) -> Unit, onRemove: (Int) -> Unit, onAdd: (DetourEpisode) -> Unit, onDismiss: () -> Unit)`.

- [ ] **Step 1: Add the list string resources**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, extend the `<!-- Detours -->` block:

```xml
    <string name="detour_list_title">DÉTOURS DU JOUR</string>
    <string name="detour_list_empty">Aucun détour déclaré aujourd’hui.</string>
    <string name="detour_edit_row_label">Modifier le détour</string>
    <string name="detour_close_button">FERMER</string>
    <string name="detour_list_add_button">AJOUTER UN DÉTOUR</string>
    <string name="detour_start_section">DÉBUT</string>
    <string name="detour_duration_label">DURÉE</string>
    <string name="detour_start_decrease">Avancer le début de 5 minutes</string>
    <string name="detour_start_increase">Retarder le début de 5 minutes</string>
    <string name="detour_start_value">Début : %1$s</string>
    <string name="detour_duration_decrease">Diminuer la durée de 5 minutes</string>
    <string name="detour_duration_increase">Augmenter la durée de 5 minutes</string>
    <string name="detour_duration_value">Durée : %1$s minutes</string>
    <string name="detour_delete_button">SUPPRIMER</string>
    <string name="detour_save_button">ENREGISTRER</string>
```

- [ ] **Step 2: Append the list dialog to `DetoursUi.kt`**

Add these imports to the file's import block (each in its sorted group):

```kotlin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import fr.dayview.app.generated.resources.detour_close_button
import fr.dayview.app.generated.resources.detour_delete_button
import fr.dayview.app.generated.resources.detour_duration_decrease
import fr.dayview.app.generated.resources.detour_duration_increase
import fr.dayview.app.generated.resources.detour_duration_label
import fr.dayview.app.generated.resources.detour_duration_value
import fr.dayview.app.generated.resources.detour_edit_row_label
import fr.dayview.app.generated.resources.detour_list_add_button
import fr.dayview.app.generated.resources.detour_list_empty
import fr.dayview.app.generated.resources.detour_list_title
import fr.dayview.app.generated.resources.detour_save_button
import fr.dayview.app.generated.resources.detour_start_decrease
import fr.dayview.app.generated.resources.detour_start_increase
import fr.dayview.app.generated.resources.detour_start_section
import fr.dayview.app.generated.resources.detour_start_value
import fr.dayview.app.generated.resources.detour_time_range
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
```

Append:

```kotlin
/** Editing target inside the list dialog: an existing row or a new episode. */
private sealed interface DetourEdit {
    data class Existing(val index: Int, val episode: DetourEpisode) : DetourEdit

    data object New : DetourEdit
}

/** The day's detours: chronological rows, tap to edit, retroactive add. */
@Composable
internal fun DetourListDialog(
    episodes: List<DetourEpisode>,
    now: Instant,
    onUpdate: (Int, DetourEpisode) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (DetourEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var edit by remember { mutableStateOf<DetourEdit?>(null) }
    val sources = detourSources(episodes)
    val colorOf: (DetourEpisode) -> androidx.compose.ui.graphics.Color = { episode ->
        val label = sanitizeDetourMotif(episode.motif).lowercase()
        val index = sources.firstOrNull { it.label.lowercase() == label }?.colorIndex ?: 0
        colors.detours[index % colors.detours.size]
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Text(stringResource(Res.string.detour_list_title), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(12.dp))
            when (val current = edit) {
                null -> {
                    if (episodes.isEmpty()) {
                        Text(stringResource(Res.string.detour_list_empty), color = colors.muted, fontSize = 13.sp)
                    } else {
                        Column(
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        ) {
                            episodes.forEachIndexed { index, episode ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_edit_row_label)) {
                                            edit = DetourEdit.Existing(index, episode)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                ) {
                                    Box(Modifier.size(8.dp).background(colorOf(episode), CircleShape))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(episode.motif, color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            stringResource(
                                                Res.string.detour_time_range,
                                                formatClockHm(episode.start),
                                                formatClockHm(episode.end),
                                                formatDurationHm(episode.duration),
                                            ),
                                            color = colors.muted,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        FocusActionButton(stringResource(Res.string.detour_close_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
                        FocusActionButton(
                            stringResource(Res.string.detour_list_add_button),
                            colors.amber,
                            modifier = Modifier.weight(1f),
                            filled = true,
                            onClick = { edit = DetourEdit.New },
                        )
                    }
                }
                else -> DetourEditForm(
                    initial = (current as? DetourEdit.Existing)?.episode,
                    now = now,
                    onDelete = (current as? DetourEdit.Existing)?.let { existing ->
                        {
                            onRemove(existing.index)
                            edit = null
                        }
                    },
                    onCancel = { edit = null },
                    onSave = { episode ->
                        when (current) {
                            is DetourEdit.Existing -> onUpdate(current.index, episode)
                            DetourEdit.New -> onAdd(episode)
                        }
                        edit = null
                    },
                )
            }
        }
    }
}

/** Motif + start time + duration form shared by edit and retroactive add. */
@Composable
private fun DetourEditForm(
    initial: DetourEpisode?,
    now: Instant,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: (DetourEpisode) -> Unit,
) {
    val colors = LocalDayViewColors.current
    val timeZone = TimeZone.currentSystemDefault()
    val initialStart = (initial?.start ?: now).toLocalDateTime(timeZone)
    var motif by remember { mutableStateOf(initial?.motif.orEmpty()) }
    var startMinutes by remember {
        mutableIntStateOf(
            (initialStart.hour * 60 + initialStart.minute - if (initial == null) 15 else 0).coerceAtLeast(0),
        )
    }
    var durationMinutes by remember {
        mutableIntStateOf(initial?.duration?.inWholeMinutes?.toInt() ?: 15)
    }
    GoalTextField(
        value = motif,
        semanticLabel = stringResource(Res.string.detour_motif_label),
        placeholder = stringResource(Res.string.detour_motif_placeholder),
        onValueChange = { motif = it },
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.detour_start_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = startMinutes >= 5,
                    onClickLabel = stringResource(Res.string.detour_start_decrease),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes)),
                ) { startMinutes = (startMinutes - 5).coerceAtLeast(0) }
                Spacer(Modifier.width(10.dp))
                Text(formatMinutesOfDay(startMinutes), color = colors.cloud, fontSize = 17.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = startMinutes <= 23 * 60 + 54,
                    onClickLabel = stringResource(Res.string.detour_start_increase),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes)),
                ) { startMinutes = (startMinutes + 5).coerceAtMost(23 * 60 + 59) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.detour_duration_label), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = durationMinutes > 5,
                    onClickLabel = stringResource(Res.string.detour_duration_decrease),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                ) { durationMinutes = (durationMinutes - 5).coerceAtLeast(5) }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(Res.string.detour_minutes_chip, durationMinutes.toString()),
                    color = colors.cloud,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = durationMinutes < 12 * 60,
                    onClickLabel = stringResource(Res.string.detour_duration_increase),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                ) { durationMinutes = (durationMinutes + 5).coerceAtMost(12 * 60) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (onDelete != null) {
            FocusActionButton(stringResource(Res.string.detour_delete_button), colors.red, modifier = Modifier.weight(1f), onClick = onDelete)
        }
        FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onCancel)
        FocusActionButton(
            stringResource(Res.string.detour_save_button),
            colors.amber,
            modifier = Modifier.weight(1f),
            enabled = motif.isNotBlank(),
            filled = true,
            onClick = { onSave(detourEpisodeAt(now, startMinutes, durationMinutes, motif)) },
        )
    }
}

private fun formatMinutesOfDay(minutes: Int): String {
    val safe = minutes.coerceIn(0, 23 * 60 + 59)
    return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}
```

- [ ] **Step 3: Host the dialog in `DayViewScreen`**

In `DayViewTodayScreen.kt`, next to the `DetourCaptureDialog` host added in Task 6, add:

```kotlin
        if (showDetourList) {
            DetourListDialog(
                episodes = state.detoursToday,
                now = state.now,
                onUpdate = actions.updateDetour,
                onRemove = actions.removeDetour,
                onAdd = actions.addDetourEpisode,
                onDismiss = { showDetourList = false },
            )
        }
```

- [ ] **Step 4: Build, lint, verify by hand**

```bash
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
./gradlew :composeApp:run
```

Manual check on desktop: capture two detours, click the tally → the list shows both chronologically; edit one (motif + start + duration) → the ring, tally and totals update immediately; delete one; add a forgotten one retroactively; relaunch → everything persisted.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DetoursUi.kt composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonMain/composeResources/values/strings.xml
git commit -m "feat: edit the day's detours from a list dialog"
```

---

### Task 8: Final verification, Android pass, README

**Files:**
- Modify: `README.md` (feature section)

**Interfaces:**
- Consumes: everything above.
- Produces: documented, fully verified feature.

- [ ] **Step 1: Full suites and lint**

```bash
./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest
```
Expected: BUILD SUCCESSFUL, zero failures, no stderr noise.

- [ ] **Step 2: Android build**

```bash
./gradlew :composeApp:assembleDebug
```
Expected: BUILD SUCCESSFUL (all detour code is `commonMain`; this catches any Android-side compile issue). If a device is connected, `./gradlew :composeApp:installDebug` and check capture, tally tap → list, and persistence.

- [ ] **Step 3: Desktop end-to-end pass**

```bash
./gradlew :composeApp:run
```
Walk the whole flow once: goal set → halo; capture from `+ DÉTOUR`; suggestion chips appear on the second capture; bodies on the ring with hover tooltips; totals under the countdown; tally under the dial; edit/delete/retroactive add from the list; relaunch → state restored; light and dark themes both legible.

- [ ] **Step 4: Document in the README**

In `README.md`, insert a new section between **Net time** and **Focus**:

```markdown
## Detours

Detours make visible what pulled you off the path, without losing sight of the goal. A
detour is declared by hand — a motif (“unexpected call”) and an approximate duration —
from the **+ Détour** affordance under the dial; recent motifs are suggested as one-tap
chips. Each episode is drawn as a small colored body threaded on the ring at the time it
happened (size reflects duration, one color per source), with a per-source tally under
the dial and a daily total below the countdown. Hovering a body shows its motif and
times; tapping the tally opens the day’s list, where episodes can be renamed, adjusted,
deleted, or added after the fact. When a goal is set, a soft halo at the center of the
dial keeps it framed as the fixed point of the day. Detours are purely informational —
they never change the countdown or the net time — and are stored locally for the current
day only.
```

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: document day detours in the README"
```

---

## Self-Review Notes

- **Spec coverage:** model + serialization (Task 1), source aggregation/colors/geometry (Task 2), persistence (Task 3), controller with rollover and recents (Task 4), rendering with halo/bodies/totals/hover (Task 5), capture (Task 6), retouch list (Task 7), degenerate cases (defaults keep mini window and no-goal rendering working), out-of-scope items untouched.
- **Deviation from spec, deliberate:** the spec's "halo behind the center goal title" is adapted to a halo at the dial center — in the real layout the goal lives in a panel, not the ring center; the dial center is the orbit's focal point.
- **Type consistency:** `DetourEpisode(start, end, motif)` everywhere; controller indices always refer to `detoursToday` (sorted by start), and the list dialog receives that same list.
- **i18n:** aligned with the string-resources refactor (#37) — every user-facing string added by Tasks 5–7 lives in `strings.xml` and is consumed via `stringResource(...)`; number/clock formatters stay plain functions, per the file's own convention.
