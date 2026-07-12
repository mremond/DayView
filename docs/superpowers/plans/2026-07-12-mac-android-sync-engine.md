# Mac/Android Sync — Headless Engine Implementation Plan (Plan 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the headless, fully-tested sync engine that merges a `DayPreferencesSnapshot` across devices through an opaque, end-to-end-encrypted transport — with no UI and no live app wiring yet.

**Architecture:** A pure `commonMain` core: a versioned `SyncDocument` with per-field last-writer-wins and id-keyed list union (`SyncMerge`), a mapper to/from the existing `DayPreferencesSnapshot` that preserves device-local fields (`SyncMapper`), an AES-256-GCM `PayloadCodec` behind which the plaintext never leaves the client, a Ktor `HttpSyncTransport` with optimistic concurrency, and a `SyncEngine` running the pull→merge→push retry loop. Every layer is injected, so all of it is testable with fakes in `commonTest`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization (JSON), Ktor client, cryptography-kotlin (whyoleg) for AES-GCM, kotlinx-datetime, kotlinx-coroutines.

## Global Constraints

- JDK 21 toolchain; `compileSdk 36`; Robolectric needs JDK 21 (from CLAUDE.md).
- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- ktlint is enforced; run `./gradlew ktlintFormat` to auto-fix before committing.
- Commit messages in English; describe the change only; **never** reference Claude/Anthropic/AI, and **never** reference `docs/superpowers/` planning docs.
- New sync code lives in package `fr.dayview.app.sync`, under `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/` (tests under `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/`).
- Instants are serialized as epoch-millis `Long`; `-1L` means "absent" (mirrors the existing `NO_DEADLINE` convention in `DayPreferencesStore`).
- Compose UI test gotchas do not apply here — this plan adds **no** Compose UI.
- Test guardrail from the repo: no assertions on `stringResource` text; use seeded data.

## Synced vs. device-local fields (authoritative for this plan)

Synced (go into `SyncDocument`): `startMinutes`, `endMinutes`, `showSeconds`, `soundSettings`, `goalTitle`, `goalDeadline`, `goalStart`, `pomodoroMinutes`, `pomodoroEnd`, `focusIntention`, `themeMode`, `netTimeSettings.enabled`, `detoursDayKey`+`detours`, `plannedObligationsDayKey`+`plannedObligations`, `recentDetourMotifs`, `cleanSessions`.

Device-local (**never** serialized; preserved from the current local snapshot on apply): `netTimeSettings.includedCalendarIds`, `onGoalApps`, `fontScale`. (Desktop-only `focusPresence` and the monochrome icon already live outside the snapshot.)

## File Structure

- `sync/Stamp.kt` — `Stamp`, `Versioned<T>`, `SyncItem<T>`, `DayScoped<T>` (versioning primitives).
- `sync/SyncDocument.kt` — `@Serializable SyncDocument` + serializable value DTOs + `SyncJson` (configured `Json`).
- `sync/SyncMapper.kt` — `buildDocument(...)` and `applyDocument(...)` between snapshot and document; owns device-local preservation and tombstone diffing.
- `sync/SyncMerge.kt` — `SyncDocument.merge(remote)` and pure merge helpers.
- `sync/PayloadCodec.kt` — `PayloadCodec` interface + `RawSyncKey`.
- `sync/Aes256GcmCodec.kt` — cryptography-kotlin implementation.
- `sync/SyncTransport.kt` — `RemoteSnapshot`, `PushOutcome`, `SyncTransport`.
- `sync/HttpSyncTransport.kt` — Ktor implementation.
- `sync/SyncEngine.kt` — pull→merge→push loop; `SyncState`, `SyncResult`.

---

## Task 1: Build setup — serialization, Ktor, crypto dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

**Interfaces:**
- Consumes: nothing.
- Produces: version-catalog accessors `libs.kotlinx.serialization.json`, `libs.ktor.client.core`, `libs.ktor.client.contentNegotiation`, `libs.ktor.serialization.json`, `libs.ktor.client.okhttp`, `libs.ktor.client.java`, `libs.ktor.client.mock`, `libs.cryptography.core`, `libs.cryptography.provider.jdk`; the `kotlinSerialization` plugin applied to `composeApp`.

- [ ] **Step 1: Add versions and libraries to the catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
ktor = "3.3.1"
serialization = "1.9.0"
cryptography = "0.5.0"
```

Add under `[libraries]`:

```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-java = { module = "io.ktor:ktor-client-java", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
cryptography-core = { module = "dev.whyoleg.cryptography:cryptography-core", version.ref = "cryptography" }
cryptography-provider-jdk = { module = "dev.whyoleg.cryptography:cryptography-provider-jdk", version.ref = "cryptography" }
```

Add under `[plugins]`:

```toml
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Apply the serialization plugin and wire dependencies**

In `composeApp/build.gradle.kts`, add to the `plugins { }` block:

```kotlin
alias(libs.plugins.kotlinSerialization)
```

In `sourceSets`, add to `commonMain.dependencies`:

```kotlin
implementation(libs.kotlinx.serialization.json)
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.content.negotiation)
implementation(libs.ktor.serialization.json)
implementation(libs.cryptography.core)
implementation(libs.cryptography.provider.jdk)
```

Add to `commonTest.dependencies`:

```kotlin
implementation(libs.ktor.client.mock)
```

Add to `androidMain.dependencies`:

```kotlin
implementation(libs.ktor.client.okhttp)
```

Add to `desktopMain.dependencies`:

```kotlin
implementation(libs.ktor.client.java)
```

- [ ] **Step 3: Verify the project still builds and resolves**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. Dependencies resolve; no source changes yet.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "Add serialization, Ktor, and crypto dependencies for state sync"
```

---

## Task 2: Versioning primitives

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Stamp.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/StampTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class Stamp(val at: Long, val by: String)` with `fun Stamp.wins(other: Stamp): Boolean`.
  - `data class Versioned<T>(val value: T, val stamp: Stamp)`.
  - `data class SyncItem<T>(val id: String, val value: T, val deleted: Boolean, val stamp: Stamp)`.
  - `data class DayScoped<T>(val dayKey: Long, val items: List<SyncItem<T>>)`.
  - All four `@Serializable`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StampTest {
    @Test
    fun laterTimestampWins() {
        assertTrue(Stamp(20, "a").wins(Stamp(10, "b")))
        assertFalse(Stamp(10, "a").wins(Stamp(20, "b")))
    }

    @Test
    fun equalTimestampBreaksTieByDeviceIdLexicographically() {
        assertTrue(Stamp(10, "b").wins(Stamp(10, "a")))
        assertFalse(Stamp(10, "a").wins(Stamp(10, "b")))
    }

    @Test
    fun identicalStampDoesNotWin() {
        assertFalse(Stamp(10, "a").wins(Stamp(10, "a")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.StampTest"`
Expected: FAIL — `Stamp` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class Stamp(val at: Long, val by: String)

/** True when this stamp should overwrite [other]: newer wins, ties break by higher deviceId. */
fun Stamp.wins(other: Stamp): Boolean = when {
    at != other.at -> at > other.at
    else -> by > other.by
}

@Serializable
data class Versioned<T>(val value: T, val stamp: Stamp)

@Serializable
data class SyncItem<T>(
    val id: String,
    val value: T,
    val deleted: Boolean,
    val stamp: Stamp,
)

@Serializable
data class DayScoped<T>(val dayKey: Long, val items: List<SyncItem<T>>)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.StampTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Stamp.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/StampTest.kt
git commit -m "Add versioning primitives for sync merge"
```

---

## Task 3: SyncDocument schema and JSON round-trip

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncDocumentJsonTest.kt`

**Interfaces:**
- Consumes: `Stamp`, `Versioned`, `SyncItem`, `DayScoped` (Task 2).
- Produces:
  - Serializable DTOs: `DayWindow(start: Int, end: Int)`, `SoundDto(enabled, startCue, intervalCue, endCue, intervalMinutes, volumePercent)`, `GoalDto(title: String, deadline: Long, start: Long)` (`-1L` = absent), `PomodoroDto(minutes: Int, end: Long)`, `CleanDto(dayKey: Long, cleanToday: Int, streakDays: Int, streakLastDayKey: Long)`.
  - `@Serializable data class SyncDocument(schemaVersion, dayWindow, showSeconds, sound, goal, pomodoro, focusIntention, themeMode, netTimeEnabled, detours: DayScoped<DetourDto>, plannedObligations: DayScoped<String>, recentDetourMotifs: List<SyncItem<String>>, cleanSessions: Versioned<CleanDto>)` with `DetourDto(start: Long, end: Long, motif: String)`.
  - `const val SYNC_SCHEMA_VERSION = 1`.
  - `val SyncJson: Json`.
  - `fun SyncDocument.encodeToString(): String` and `fun decodeSyncDocument(text: String): SyncDocument`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDocumentJsonTest {
    @Test
    fun roundTripsThroughJson() {
        val doc = sampleDocument()
        assertEquals(doc, decodeSyncDocument(doc.encodeToString()))
    }
}

/** Shared fixture reused by later tests. */
fun sampleDocument(deviceId: String = "dev-a", at: Long = 1000): SyncDocument {
    val s = Stamp(at, deviceId)
    return SyncDocument(
        schemaVersion = SYNC_SCHEMA_VERSION,
        dayWindow = Versioned(DayWindow(480, 1080), s),
        showSeconds = Versioned(true, s),
        sound = Versioned(SoundDto(false, true, true, true, 30, 40), s),
        goal = Versioned(GoalDto("Ship", 2000L, 1500L), s),
        pomodoro = Versioned(PomodoroDto(25, -1L), s),
        focusIntention = Versioned("write", s),
        themeMode = Versioned("SYSTEM", s),
        netTimeEnabled = Versioned(false, s),
        detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "coffee"), false, s))),
        plannedObligations = DayScoped(19000, listOf(SyncItem("call", "call", false, s))),
        recentDetourMotifs = listOf(SyncItem("coffee", "coffee", false, s)),
        cleanSessions = Versioned(CleanDto(19000, 2, 5, 18999), s),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncDocumentJsonTest"`
Expected: FAIL — `SyncDocument` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SYNC_SCHEMA_VERSION = 1

@Serializable data class DayWindow(val start: Int, val end: Int)

@Serializable
data class SoundDto(
    val enabled: Boolean,
    val startCue: Boolean,
    val intervalCue: Boolean,
    val endCue: Boolean,
    val intervalMinutes: Int,
    val volumePercent: Int,
)

@Serializable data class GoalDto(val title: String, val deadline: Long, val start: Long)

@Serializable data class PomodoroDto(val minutes: Int, val end: Long)

@Serializable data class DetourDto(val start: Long, val end: Long, val motif: String)

@Serializable
data class CleanDto(
    val dayKey: Long,
    val cleanToday: Int,
    val streakDays: Int,
    val streakLastDayKey: Long,
)

@Serializable
data class SyncDocument(
    val schemaVersion: Int,
    val dayWindow: Versioned<DayWindow>,
    val showSeconds: Versioned<Boolean>,
    val sound: Versioned<SoundDto>,
    val goal: Versioned<GoalDto>,
    val pomodoro: Versioned<PomodoroDto>,
    val focusIntention: Versioned<String>,
    val themeMode: Versioned<String>,
    val netTimeEnabled: Versioned<Boolean>,
    val detours: DayScoped<DetourDto>,
    val plannedObligations: DayScoped<String>,
    val recentDetourMotifs: List<SyncItem<String>>,
    val cleanSessions: Versioned<CleanDto>,
)

val SyncJson: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

fun SyncDocument.encodeToString(): String = SyncJson.encodeToString(this)

fun decodeSyncDocument(text: String): SyncDocument = SyncJson.decodeFromString(text)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncDocumentJsonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncDocumentJsonTest.kt
git commit -m "Add serializable SyncDocument schema"
```

---

## Task 4: Mapper — snapshot ⇄ document with device-local preservation and tombstones

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMapperTest.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot`, `SoundSettings`, `NetTimeSettings`, `DetourEpisode`, `CleanSessionLedger`, `ThemeMode` (existing); `SyncDocument` DTOs (Task 3).
- Produces:
  - `fun detourKey(e: DetourDto): String` = `"${e.start}|${e.end}|${e.motif}"`.
  - `fun buildDocument(snapshot: DayPreferencesSnapshot, base: SyncDocument?, deviceId: String, now: Long): SyncDocument` — stamps changed fields with `Stamp(now, deviceId)`, carries unchanged stamps from `base`, and emits `deleted=true` tombstones for keyed items present in `base` but absent in `snapshot` (same dayKey only).
  - `fun applyDocument(document: SyncDocument, local: DayPreferencesSnapshot): DayPreferencesSnapshot` — writes synced fields, **preserves** `local.netTimeSettings.includedCalendarIds`, `local.onGoalApps`, `local.fontScale`, drops tombstoned items.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncMapperTest {
    private val base = DayPreferencesSnapshot(
        startMinutes = 480, endMinutes = 1080,
        netTimeSettings = NetTimeSettings(enabled = true, includedCalendarIds = setOf("cal-1")),
        onGoalApps = emptySet(), fontScale = 1.3f,
    )

    @Test
    fun buildStampsAllFieldsOnFirstBuild() {
        val doc = buildDocument(base, base = null, deviceId = "a", now = 100)
        assertEquals(480, doc.dayWindow.value.start)
        assertEquals(Stamp(100, "a"), doc.dayWindow.stamp)
        // device-local calendar ids are NOT in the document (only the enabled toggle is)
        assertTrue(doc.netTimeEnabled.value)
    }

    @Test
    fun buildKeepsBaseStampWhenFieldUnchanged() {
        val first = buildDocument(base, base = null, deviceId = "a", now = 100)
        val second = buildDocument(base, base = first, deviceId = "a", now = 200)
        assertEquals(Stamp(100, "a"), second.dayWindow.stamp) // unchanged → old stamp kept
    }

    @Test
    fun buildRestampsChangedField() {
        val first = buildDocument(base, base = null, deviceId = "a", now = 100)
        val changed = base.copy(startMinutes = 500)
        val second = buildDocument(changed, base = first, deviceId = "a", now = 200)
        assertEquals(Stamp(200, "a"), second.dayWindow.stamp)
    }

    @Test
    fun buildEmitsTombstoneForRemovedDetour() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "coffee")),
        )
        val first = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val removed = withDetour.copy(detours = emptyList())
        val second = buildDocument(removed, base = first, deviceId = "a", now = 200)
        val item = second.detours.items.single()
        assertTrue(item.deleted)
    }

    @Test
    fun applyPreservesDeviceLocalFields() {
        val doc = buildDocument(base.copy(startMinutes = 500), base = null, deviceId = "a", now = 100)
        val local = base.copy(startMinutes = 999) // remote should overwrite this…
        val result = applyDocument(doc, local)
        assertEquals(500, result.startMinutes)                       // synced field applied
        assertEquals(setOf("cal-1"), result.netTimeSettings.includedCalendarIds) // preserved
        assertEquals(1.3f, result.fontScale)                          // preserved
    }

    @Test
    fun applyDropsTombstonedDetours() {
        val withDetour = base.copy(
            detoursDayKey = 19000,
            detours = listOf(DetourEpisode(Instant.fromEpochMilliseconds(10), Instant.fromEpochMilliseconds(20), "coffee")),
        )
        val first = buildDocument(withDetour, base = null, deviceId = "a", now = 100)
        val second = buildDocument(withDetour.copy(detours = emptyList()), base = first, deviceId = "a", now = 200)
        assertTrue(applyDocument(second, withDetour).detours.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncMapperTest"`
Expected: FAIL — `buildDocument` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

import kotlin.time.Instant

private const val NO_INSTANT = -1L

fun detourKey(e: DetourDto): String = "${e.start}|${e.end}|${e.motif}"

private fun Instant?.toMillisOrAbsent(): Long = this?.toEpochMilliseconds() ?: NO_INSTANT
private fun Long.toInstantOrNull(): Instant? = if (this == NO_INSTANT) null else Instant.fromEpochMilliseconds(this)

/** Keep [current]'s stamp if the value is unchanged from [base]; otherwise stamp with [now]. */
private fun <T> restamp(current: T, base: Versioned<T>?, now: Long, by: String): Versioned<T> =
    if (base != null && base.value == current) base else Versioned(current, Stamp(now, by))

fun buildDocument(
    snapshot: DayPreferencesSnapshot,
    base: SyncDocument?,
    deviceId: String,
    now: Long,
): SyncDocument {
    val fresh = Stamp(now, deviceId)
    return SyncDocument(
        schemaVersion = SYNC_SCHEMA_VERSION,
        dayWindow = restamp(DayWindow(snapshot.startMinutes, snapshot.endMinutes), base?.dayWindow, now, deviceId),
        showSeconds = restamp(snapshot.showSeconds, base?.showSeconds, now, deviceId),
        sound = restamp(snapshot.soundSettings.toDto(), base?.sound, now, deviceId),
        goal = restamp(
            GoalDto(snapshot.goalTitle, snapshot.goalDeadline.toMillisOrAbsent(), snapshot.goalStart.toMillisOrAbsent()),
            base?.goal, now, deviceId,
        ),
        pomodoro = restamp(PomodoroDto(snapshot.pomodoroMinutes, snapshot.pomodoroEnd.toMillisOrAbsent()), base?.pomodoro, now, deviceId),
        focusIntention = restamp(snapshot.focusIntention, base?.focusIntention, now, deviceId),
        themeMode = restamp(snapshot.themeMode.name, base?.themeMode, now, deviceId),
        netTimeEnabled = restamp(snapshot.netTimeSettings.enabled, base?.netTimeEnabled, now, deviceId),
        detours = buildDayScoped(
            dayKey = snapshot.detoursDayKey,
            values = snapshot.detours.map { DetourDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.motif) },
            keyOf = ::detourKey,
            base = base?.detours,
            fresh = fresh,
        ),
        plannedObligations = buildDayScoped(
            dayKey = snapshot.plannedObligationsDayKey,
            values = snapshot.plannedObligations,
            keyOf = { it },
            base = base?.plannedObligations,
            fresh = fresh,
        ),
        recentDetourMotifs = buildItems(snapshot.recentDetourMotifs, { it }, base?.recentDetourMotifs, fresh),
        cleanSessions = restamp(snapshot.cleanSessions.toDto(), base?.cleanSessions, now, deviceId),
    )
}

private fun <T> buildDayScoped(
    dayKey: Long,
    values: List<T>,
    keyOf: (T) -> String,
    base: DayScoped<T>?,
    fresh: Stamp,
): DayScoped<T> {
    // Only diff against base when it describes the same day; a new day starts clean.
    val sameDayBase = base?.takeIf { it.dayKey == dayKey }
    return DayScoped(dayKey, buildItems(values, keyOf, sameDayBase?.items, fresh))
}

private fun <T> buildItems(
    values: List<T>,
    keyOf: (T) -> String,
    baseItems: List<SyncItem<T>>?,
    fresh: Stamp,
): List<SyncItem<T>> {
    val baseByKey = baseItems.orEmpty().associateBy { it.id }
    val present = values.map { v ->
        val key = keyOf(v)
        val prior = baseByKey[key]
        if (prior != null && !prior.deleted && prior.value == v) prior
        else SyncItem(key, v, deleted = false, stamp = fresh)
    }
    val presentKeys = present.map { it.id }.toSet()
    // Carry already-deleted entries forward unchanged; only newly-absent entries get a
    // fresh tombstone. Restamping on every rebuild would drop tombstones and resurrect deletes.
    val tombstones = baseByKey.values
        .filter { it.id !in presentKeys }
        .map { if (it.deleted) it else it.copy(deleted = true, stamp = fresh) }
    return present + tombstones
}

fun applyDocument(document: SyncDocument, local: DayPreferencesSnapshot): DayPreferencesSnapshot =
    local.copy(
        startMinutes = document.dayWindow.value.start,
        endMinutes = document.dayWindow.value.end,
        showSeconds = document.showSeconds.value,
        soundSettings = document.sound.value.toSettings(),
        goalTitle = document.goal.value.title,
        goalDeadline = document.goal.value.deadline.toInstantOrNull(),
        goalStart = document.goal.value.start.toInstantOrNull(),
        pomodoroMinutes = document.pomodoro.value.minutes,
        pomodoroEnd = document.pomodoro.value.end.toInstantOrNull(),
        focusIntention = document.focusIntention.value,
        themeMode = ThemeMode.entries.firstOrNull { it.name == document.themeMode.value } ?: local.themeMode,
        // preserve device-local calendar ids; only the enabled toggle is synced
        netTimeSettings = local.netTimeSettings.copy(enabled = document.netTimeEnabled.value),
        detoursDayKey = document.detours.dayKey,
        detours = document.detours.items.filterNot { it.deleted }
            .map { DetourEpisode(Instant.fromEpochMilliseconds(it.value.start), Instant.fromEpochMilliseconds(it.value.end), it.value.motif) },
        plannedObligationsDayKey = document.plannedObligations.dayKey,
        plannedObligations = document.plannedObligations.items.filterNot { it.deleted }.map { it.value },
        recentDetourMotifs = document.recentDetourMotifs.filterNot { it.deleted }.map { it.value },
        cleanSessions = document.cleanSessions.value.toLedger(),
        // onGoalApps and fontScale are left untouched by copy() → preserved
    )

private fun SoundSettings.toDto() = SoundDto(enabled, startCueEnabled, intervalCueEnabled, endCueEnabled, intervalMinutes, volumePercent)
private fun SoundDto.toSettings() = SoundSettings(enabled, startCue, intervalCue, endCue, intervalMinutes, volumePercent)
private fun CleanSessionLedger.toDto() = CleanDto(dayKey, cleanToday, streakDays, streakLastDayKey)
private fun CleanDto.toLedger() = CleanSessionLedger(dayKey, cleanToday, streakDays, streakLastDayKey)
```

Note: verify the exact `SoundSettings` and `CleanSessionLedger` constructor parameter order against `SettingsUiModels.kt`/`CleanFocusSessions.kt` before running; adjust the `toDto`/`toSettings`/`toLedger` mappers if names differ.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMapperTest.kt
git commit -m "Add snapshot-to-document mapper with device-local preservation"
```

---

## Task 5: Merge — pure LWW + id-keyed union

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt`

**Interfaces:**
- Consumes: everything in Tasks 2–3; `sampleDocument()` fixture (Task 3).
- Produces: `fun SyncDocument.merge(remote: SyncDocument?): SyncDocument`. Merge is commutative and idempotent for a fixed pair. `cleanSessions` merges component-wise: `streak*` from the entry with the greater `streakLastDayKey`; `cleanToday` = `max` at equal `dayKey`, else the value of the greater `dayKey`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncMergeTest {
    @Test
    fun nullRemoteReturnsLocal() {
        val local = sampleDocument()
        assertEquals(local, local.merge(null))
    }

    @Test
    fun newerFieldWins() {
        val a = sampleDocument(deviceId = "a", at = 100).let { it.copy(goal = Versioned(it.goal.value.copy(title = "old"), Stamp(100, "a"))) }
        val b = sampleDocument(deviceId = "b", at = 200).let { it.copy(goal = Versioned(it.goal.value.copy(title = "new"), Stamp(200, "b"))) }
        assertEquals("new", a.merge(b).goal.value.title)
        assertEquals("new", b.merge(a).goal.value.title) // commutative
    }

    @Test
    fun sameDayDetoursUnionByKey() {
        val s = Stamp(100, "a")
        val a = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "coffee"), false, s))))
        val b = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k2", DetourDto(30, 40, "call"), false, Stamp(150, "b")))))
        assertEquals(2, a.merge(b).detours.items.count { !it.deleted })
    }

    @Test
    fun newerDayReplacesOlderDayWholesale() {
        val old = sampleDocument().copy(detours = DayScoped(19000, listOf(SyncItem("k1", DetourDto(10, 20, "x"), false, Stamp(100, "a")))))
        val new = sampleDocument().copy(detours = DayScoped(19001, emptyList()))
        assertEquals(19001, old.merge(new).detours.dayKey)
        assertEquals(0, old.merge(new).detours.items.size)
    }

    @Test
    fun tombstoneWinsOverStaleAdd() {
        val add = SyncItem("k1", DetourDto(10, 20, "x"), false, Stamp(100, "a"))
        val del = SyncItem("k1", DetourDto(10, 20, "x"), true, Stamp(200, "b"))
        val a = sampleDocument().copy(detours = DayScoped(19000, listOf(add)))
        val b = sampleDocument().copy(detours = DayScoped(19000, listOf(del)))
        assertEquals(true, a.merge(b).detours.items.single().deleted)
    }

    @Test
    fun cleanTodayTakesMaxAtEqualDay() {
        val a = sampleDocument().copy(cleanSessions = Versioned(CleanDto(19000, 3, 5, 18999), Stamp(100, "a")))
        val b = sampleDocument().copy(cleanSessions = Versioned(CleanDto(19000, 1, 7, 19000), Stamp(200, "b")))
        val merged = a.merge(b).cleanSessions.value
        assertEquals(3, merged.cleanToday)          // max
        assertEquals(7, merged.streakDays)          // from greater streakLastDayKey
        assertEquals(19000, merged.streakLastDayKey)
    }

    @Test
    fun mergeIsIdempotent() {
        val a = sampleDocument(deviceId = "a", at = 100)
        val b = sampleDocument(deviceId = "b", at = 200)
        val once = a.merge(b)
        assertEquals(once, once.merge(b))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncMergeTest"`
Expected: FAIL — `merge` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

fun SyncDocument.merge(remote: SyncDocument?): SyncDocument {
    if (remote == null) return this
    return copy(
        schemaVersion = maxOf(schemaVersion, remote.schemaVersion),
        dayWindow = pick(dayWindow, remote.dayWindow),
        showSeconds = pick(showSeconds, remote.showSeconds),
        sound = pick(sound, remote.sound),
        goal = pick(goal, remote.goal),
        pomodoro = pick(pomodoro, remote.pomodoro),
        focusIntention = pick(focusIntention, remote.focusIntention),
        themeMode = pick(themeMode, remote.themeMode),
        netTimeEnabled = pick(netTimeEnabled, remote.netTimeEnabled),
        detours = mergeDayScoped(detours, remote.detours),
        plannedObligations = mergeDayScoped(plannedObligations, remote.plannedObligations),
        recentDetourMotifs = mergeItems(recentDetourMotifs, remote.recentDetourMotifs),
        cleanSessions = mergeClean(cleanSessions, remote.cleanSessions),
    )
}

private fun <T> pick(a: Versioned<T>, b: Versioned<T>): Versioned<T> = if (b.stamp.wins(a.stamp)) b else a

private fun <T> mergeDayScoped(a: DayScoped<T>, b: DayScoped<T>): DayScoped<T> = when {
    a.dayKey > b.dayKey -> a
    b.dayKey > a.dayKey -> b
    else -> DayScoped(a.dayKey, mergeItems(a.items, b.items))
}

private fun <T> mergeItems(a: List<SyncItem<T>>, b: List<SyncItem<T>>): List<SyncItem<T>> =
    (a + b).groupBy { it.id }
        .map { (_, group) -> group.reduce { x, y -> if (y.stamp.wins(x.stamp)) y else x } }
        .sortedBy { it.id } // canonical order so merge is structurally commutative (devices converge)

private fun mergeClean(a: Versioned<CleanDto>, b: Versioned<CleanDto>): Versioned<CleanDto> {
    val streakDays = when {
        a.value.streakLastDayKey > b.value.streakLastDayKey -> a.value.streakDays
        b.value.streakLastDayKey > a.value.streakLastDayKey -> b.value.streakDays
        else -> maxOf(a.value.streakDays, b.value.streakDays) // symmetric tie-break
    }
    val streakLastDayKey = maxOf(a.value.streakLastDayKey, b.value.streakLastDayKey)
    val cleanToday = when {
        a.value.dayKey > b.value.dayKey -> a.value.cleanToday
        b.value.dayKey > a.value.dayKey -> b.value.cleanToday
        else -> maxOf(a.value.cleanToday, b.value.cleanToday)
    }
    val dayKey = maxOf(a.value.dayKey, b.value.dayKey)
    val merged = CleanDto(dayKey, cleanToday, streakDays, streakLastDayKey)
    val stamp = if (b.stamp.wins(a.stamp)) b.stamp else a.stamp
    return Versioned(merged, stamp)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncMergeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt
git commit -m "Add pure per-field merge for sync documents"
```

---

## Task 6: PayloadCodec — AES-256-GCM end-to-end encryption

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/PayloadCodec.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Aes256GcmCodec.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/Aes256GcmCodecTest.kt`

**Interfaces:**
- Consumes: cryptography-kotlin (`CryptographyProvider`, `AES.GCM`).
- Produces:
  - `class RawSyncKey(val bytes: ByteArray)` with `require(bytes.size == 32)`; `companion object { fun generate(): RawSyncKey; const val SIZE_BYTES = 32 }`.
  - `interface PayloadCodec { suspend fun encrypt(plaintext: String): String; suspend fun decrypt(ciphertext: String): String }` — throws `SyncKeyMismatchException` when authentication fails.
  - `class SyncKeyMismatchException(cause: Throwable?) : Exception(cause)`.
  - `class Aes256GcmCodec(key: RawSyncKey) : PayloadCodec`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class Aes256GcmCodecTest {
    @Test
    fun roundTripsPlaintext() = runTest {
        val codec = Aes256GcmCodec(RawSyncKey.generate())
        val text = sampleDocument().encodeToString()
        assertEquals(text, codec.decrypt(codec.encrypt(text)))
    }

    @Test
    fun encryptUsesFreshNonceEachTime() = runTest {
        val codec = Aes256GcmCodec(RawSyncKey.generate())
        assertNotEquals(codec.encrypt("hello"), codec.encrypt("hello"))
    }

    @Test
    fun wrongKeyFailsAuthentication() = runTest {
        val cipher = Aes256GcmCodec(RawSyncKey.generate()).encrypt("secret")
        val other = Aes256GcmCodec(RawSyncKey.generate())
        assertFailsWith<SyncKeyMismatchException> { other.decrypt(cipher) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.Aes256GcmCodecTest"`
Expected: FAIL — `Aes256GcmCodec` unresolved.

- [ ] **Step 3: Write minimal implementation**

`PayloadCodec.kt`:

```kotlin
package fr.dayview.app.sync

import dev.whyoleg.cryptography.random.CryptographyRandom

interface PayloadCodec {
    suspend fun encrypt(plaintext: String): String
    suspend fun decrypt(ciphertext: String): String
}

class SyncKeyMismatchException(cause: Throwable?) : Exception("Sync key missing or invalid", cause)

class RawSyncKey(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE_BYTES) { "SyncKey must be $SIZE_BYTES bytes" }
    }

    companion object {
        const val SIZE_BYTES = 32

        fun generate(): RawSyncKey = RawSyncKey(CryptographyRandom.nextBytes(SIZE_BYTES))
    }
}
```

`Aes256GcmCodec.kt`:

```kotlin
package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class Aes256GcmCodec(key: RawSyncKey) : PayloadCodec {
    private val aad = "dayview-sync-v$SYNC_SCHEMA_VERSION".encodeToByteArray()
    private val gcm = CryptographyProvider.Default.get(AES.GCM)
    private val cipherKey = gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.bytes)

    override suspend fun encrypt(plaintext: String): String {
        // The library prepends a fresh random nonce to the returned ciphertext.
        val out = cipherKey.cipher().encrypt(plaintext.encodeToByteArray(), aad)
        return Base64.encode(out)
    }

    override suspend fun decrypt(ciphertext: String): String = try {
        cipherKey.cipher().decrypt(Base64.decode(ciphertext), aad).decodeToString()
    } catch (e: Exception) {
        throw SyncKeyMismatchException(e)
    }
}
```

Note: confirm the exact cryptography-kotlin 0.5.0 `cipher()`/`encrypt`/`decrypt` signatures for the associated-data (`aad`) parameter position at implementation time; the interface and tests are the contract, adapt the two calls if the API differs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.Aes256GcmCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/PayloadCodec.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Aes256GcmCodec.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/Aes256GcmCodecTest.kt
git commit -m "Add AES-256-GCM end-to-end payload codec"
```

---

## Task 7: SyncTransport contract + HTTP implementation

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncTransport.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HttpSyncTransport.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/HttpSyncTransportTest.kt`

**Interfaces:**
- Consumes: Ktor client + `MockEngine` (test).
- Produces:
  - `data class RemoteSnapshot(val payload: String, val revision: String)`.
  - `sealed interface PushOutcome { data class Applied(val revision: String); data class Rejected(val current: RemoteSnapshot) }`.
  - `interface SyncTransport { suspend fun pull(): RemoteSnapshot?; suspend fun push(payload: String, expectedRevision: String?): PushOutcome }`.
  - `class HttpSyncTransport(client: HttpClient, baseUrl: String, userId: String, token: String) : SyncTransport`. Wire protocol: `GET /sync/{userId}` → 200 `{revision,payload}` / 204 empty; `PUT /sync/{userId}` with `If-Match: <expected>` or `If-None-Match: *`, body `{payload}` → 200 `{revision}` / 412 `{revision,payload}`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSyncTransportTest {
    private fun transport(engine: MockEngine) = HttpSyncTransport(
        client = HttpClient(engine) { install(ContentNegotiation) { json(SyncJson) } },
        baseUrl = "https://sync.example",
        userId = "u1",
        token = "tok",
    )

    @Test
    fun pullReturnsNullOn204() = runTest {
        val t = transport(MockEngine { respond("", HttpStatusCode.NoContent) })
        assertNull(t.pull())
    }

    @Test
    fun pullParsesBodyAndRevision() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r7","payload":"blob"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val snap = t.pull()
        assertEquals(RemoteSnapshot("blob", "r7"), snap)
    }

    @Test
    fun pushAppliedOn200() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r8"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        assertEquals(PushOutcome.Applied("r8"), t.push("blob", expectedRevision = "r7"))
    }

    @Test
    fun pushRejectedOn412ReturnsCurrent() = runTest {
        val t = transport(
            MockEngine {
                respond(
                    """{"revision":"r9","payload":"newer"}""",
                    HttpStatusCode.PreconditionFailed,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val outcome = t.push("blob", expectedRevision = "r7")
        assertTrue(outcome is PushOutcome.Rejected)
        assertEquals(RemoteSnapshot("newer", "r9"), (outcome as PushOutcome.Rejected).current)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.HttpSyncTransportTest"`
Expected: FAIL — `HttpSyncTransport` unresolved.

- [ ] **Step 3: Write minimal implementation**

`SyncTransport.kt`:

```kotlin
package fr.dayview.app.sync

data class RemoteSnapshot(val payload: String, val revision: String)

sealed interface PushOutcome {
    data class Applied(val revision: String) : PushOutcome
    data class Rejected(val current: RemoteSnapshot) : PushOutcome
}

interface SyncTransport {
    suspend fun pull(): RemoteSnapshot?
    suspend fun push(payload: String, expectedRevision: String?): PushOutcome
}
```

`HttpSyncTransport.kt`:

```kotlin
package fr.dayview.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable private data class RemoteBody(val revision: String, val payload: String? = null)
@Serializable private data class PushBody(val payload: String)

class HttpSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val userId: String,
    private val token: String,
) : SyncTransport {
    private val endpoint get() = "$baseUrl/sync/$userId"

    override suspend fun pull(): RemoteSnapshot? {
        val response = client.get(endpoint) { bearerAuth(token) }
        if (response.status == HttpStatusCode.NoContent) return null
        val body: RemoteBody = response.body()
        return RemoteSnapshot(payload = body.payload.orEmpty(), revision = body.revision)
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        val response = client.put(endpoint) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            if (expectedRevision == null) header(HttpHeaders.IfNoneMatch, "*")
            else header(HttpHeaders.IfMatch, expectedRevision)
            setBody(PushBody(payload))
        }
        return if (response.status == HttpStatusCode.PreconditionFailed) {
            val body: RemoteBody = response.body()
            PushOutcome.Rejected(RemoteSnapshot(body.payload.orEmpty(), body.revision))
        } else {
            PushOutcome.Applied(response.body<RemoteBody>().revision)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.HttpSyncTransportTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncTransport.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HttpSyncTransport.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/HttpSyncTransportTest.kt
git commit -m "Add SyncTransport contract and Ktor HTTP implementation"
```

---

## Task 8: SyncEngine — pull→merge→push retry loop

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncEngine.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `SyncTransport`, `PayloadCodec`, `SyncDocument`, `buildDocument`/`applyDocument`, `merge`.
- Produces:
  - `data class SyncState(val baseRevision: String?, val baseDocument: SyncDocument?)`.
  - `sealed interface SyncResult { data object UpToDate; data class Applied(val snapshot: DayPreferencesSnapshot, val state: SyncState); data object KeyError; data class Failed(val cause: Throwable) }`.
  - `class SyncEngine(transport, codec, deviceId, maxRetries = 3)` with `suspend fun sync(local: DayPreferencesSnapshot, state: SyncState, now: Long): SyncResult`. On `Applied`, the returned `snapshot` is `applyDocument(mergedAgainstRemote, local)` and `state.baseDocument` is the merged document. Decrypt failure → `KeyError` (never pushes plaintext). Transport exception → `Failed`. `Rejected` re-merges against the newer remote up to `maxRetries`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeTransport(
    var remote: RemoteSnapshot?,
    private val rejectFirst: Boolean = false,
) : SyncTransport {
    var pushes = 0
    override suspend fun pull() = remote
    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        if (rejectFirst && pushes == 1) return PushOutcome.Rejected(remote!!)
        remote = RemoteSnapshot(payload, "r${pushes + 1}")
        return PushOutcome.Applied("r${pushes + 1}")
    }
}

/** Identity codec: keeps the loop logic under test independent of real crypto. */
private object PlainCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext
    override suspend fun decrypt(ciphertext: String) = ciphertext
}

private object FailKeyCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext
    override suspend fun decrypt(ciphertext: String): String = throw SyncKeyMismatchException(null)
}

class SyncEngineTest {
    private val local = DayPreferencesSnapshot(startMinutes = 500)

    @Test
    fun firstSyncPushesAndReturnsApplied() = runTest {
        val transport = FakeTransport(remote = null)
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")
        val result = engine.sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Applied>(result)
        assertEquals(1, transport.pushes)
        assertEquals(500, result.snapshot.startMinutes)
    }

    @Test
    fun rejectedPushRetriesAgainstNewerRemote() = runTest {
        val transport = FakeTransport(remote = null, rejectFirst = true)
        val engine = SyncEngine(transport, PlainCodec, deviceId = "a")
        val result = engine.sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Applied>(result)
        assertTrue(transport.pushes >= 2)
    }

    @Test
    fun decryptFailureReturnsKeyError() = runTest {
        val transport = FakeTransport(remote = RemoteSnapshot("cipher", "r1"))
        val engine = SyncEngine(transport, FailKeyCodec, deviceId = "a")
        assertIs<SyncResult.KeyError>(engine.sync(local, SyncState(null, null), now = 100))
        assertEquals(0, transport.pushes) // never pushed
    }

    @Test
    fun transportErrorReturnsFailed() = runTest {
        val throwing = object : SyncTransport {
            override suspend fun pull(): RemoteSnapshot? = throw RuntimeException("network")
            override suspend fun push(payload: String, expectedRevision: String?) = throw RuntimeException("network")
        }
        val result = SyncEngine(throwing, PlainCodec, deviceId = "a").sync(local, SyncState(null, null), now = 100)
        assertIs<SyncResult.Failed>(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncEngineTest"`
Expected: FAIL — `SyncEngine` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

data class SyncState(val baseRevision: String?, val baseDocument: SyncDocument?)

sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Applied(val snapshot: DayPreferencesSnapshot, val state: SyncState) : SyncResult
    data object KeyError : SyncResult
    data class Failed(val cause: Throwable) : SyncResult
}

class SyncEngine(
    private val transport: SyncTransport,
    private val codec: PayloadCodec,
    private val deviceId: String,
    private val maxRetries: Int = 3,
) {
    suspend fun sync(local: DayPreferencesSnapshot, state: SyncState, now: Long): SyncResult {
        val localDoc = buildDocument(local, state.baseDocument, deviceId, now)
        var expected = state.baseRevision
        try {
            repeat(maxRetries) {
                val remote = transport.pull()
                val remoteDoc = remote?.let {
                    try {
                        decodeSyncDocument(codec.decrypt(it.payload))
                    } catch (e: SyncKeyMismatchException) {
                        return SyncResult.KeyError
                    }
                }
                val merged = localDoc.merge(remoteDoc)
                val payload = codec.encrypt(merged.encodeToString())
                when (val outcome = transport.push(payload, remote?.revision ?: expected)) {
                    is PushOutcome.Applied ->
                        return SyncResult.Applied(
                            snapshot = applyDocument(merged, local),
                            state = SyncState(outcome.revision, merged),
                        )
                    is PushOutcome.Rejected -> expected = outcome.current.revision
                }
            }
        } catch (e: SyncKeyMismatchException) {
            return SyncResult.KeyError
        } catch (e: Throwable) {
            return SyncResult.Failed(e)
        }
        return SyncResult.Failed(IllegalStateException("Exhausted $maxRetries sync retries"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncEngineTest"`
Expected: PASS.

- [ ] **Step 5: Full suite + lint, then commit**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no stderr.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncEngine.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncEngineTest.kt
git commit -m "Add sync engine pull-merge-push retry loop"
```

---

## Self-Review (completed)

- **Spec coverage:** §2 scope → Task 4 mapper (synced/local split table encoded in `buildDocument`/`applyDocument`). §4 schema → Task 3. §5 merge rules (LWW, day-scoped union, tombstones, cleanSessions max) → Task 5. §6 E2EE cipher/round-trip/wrong-key → Task 6 (key provisioning UI + `SecureKeyStore` deferred to Plan 2). §7 HTTP protocol/etag/412 → Task 7. §8 engine loop/retries/KeyError → Task 8. §9 edge cases (first sync, day rollover, offline, key mismatch, local preservation) → covered by Tasks 4/5/8 tests.
- **Deferred to Plan 2 (by design, this is the headless plan):** `SecureKeyStore` expect/actual, `SyncConfig`/`SyncState` persistence, recovery-phrase/QR key provisioning UI, live triggers (foreground/resume + debounced write), wiring `SyncEngine` into `DayViewController`, and standing up the real endpoint. §11 future items (observe/HLC/passphrase/rotation) remain future.
- **Placeholder scan:** two "confirm the exact API" notes (Tasks 4 and 6) point at real external-signature checks with the tests as the contract — not deferred work. No TODO/TBD steps.
- **Type consistency:** `Stamp.wins`, `Versioned`, `SyncItem`, `DayScoped` used identically across Tasks 2/3/5/8; `RemoteSnapshot`/`PushOutcome` identical across Tasks 7/8; `SyncDocument` DTO names identical across Tasks 3/4/5.

## Plan 2 preview (not part of this plan)

After Plan 1 lands: `SecureKeyStore` (`expect`/`actual` for Android Keystore + desktop encrypted file), `SyncConfig`/`SyncState` local persistence (outside the synced DataStore), recovery-phrase + QR key provisioning UI, `SyncEngine` wiring into `DayViewController` with foreground/resume and debounced-write triggers plus a coalescing mutex, an error surface for `KeyError`, and the self-hosted endpoint. Open decision to settle first: key-provisioning UX (QR + backup phrase vs. one of the two).
