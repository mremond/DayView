# Engaged Time Cross-Device Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make same-day focus intervals recorded on different devices converge to their union, without breaking the write-once history invariant, by syncing per-device focus contributions on a side channel and unioning them at read time.

**Architecture:** The write-once day record is untouched (first writer still wins calendar/detours/goal). A new per-`(day, deviceId)` focus contribution blob carries only that device's `focusPresence` + `focusSession`; each device writes only its own key, so nothing ever conflicts. A `focusContributions` manifest rides the (already CAS-merged) main sync document. On read, the history screens display `mergeIntervals(record.ownIntervals + Σ contributions(day))`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, AES-GCM / HMAC via `dev.whyoleg.cryptography`, Ktor client, kotlin.test.

## Global Constraints

- **Depends on Plan A** (`2026-07-14-mobile-engaged-time.md`) and on PR #77. Branch from the branch that already contains both (Plan A on top of `claude/focus-time-calculation-57422f`, or `main` once both have landed).
- **Write-once preserved.** Never overwrite an existing history record or an existing focus blob. Each device writes only `(day, self)`.
- **Provenance.** A device's own contribution `(day, self)` is written **only** from its own live archive — never derived from a record it downloaded. This guarantees a device never re-uploads another device's intervals as its own.
- **No double counting.** All unions go through `mergeIntervals` (Plan A, Task 1).
- **No manifest retention bound in v1** (matches `historyDays`, itself unbounded).
- **ktlint enforced; final gate:** `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- **Commit messages:** English, change-only, no AI/Claude references, no test-plan section.

---

### Task 1: `FocusContributionStore` + union helper (core)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/FocusContributionStore.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/FocusContributionStoreTest.kt`

**Interfaces:**
- Consumes: `FocusPresenceInterval`, `DayHistoryRecord`, `mergeIntervals` (Plan A).
- Produces:
  - `data class FocusContribution(dayKey: Long, deviceId: String, presence: List<FocusPresenceInterval>, session: List<FocusPresenceInterval>)`
  - `interface FocusContributionStore { suspend fun write(c: FocusContribution); suspend fun read(dayKey: Long, deviceId: String): FocusContribution?; suspend fun listForDay(dayKey: Long): List<FocusContribution>; suspend fun listKeys(): List<Pair<Long, String>> }`
  - `class InMemoryFocusContributionStore : FocusContributionStore`
  - `fun DayHistoryRecord.withMergedFocus(contributions: List<FocusContribution>): DayHistoryRecord`

- [ ] **Step 1: Write the failing test**

Create `core/src/commonTest/kotlin/fr/dayview/app/FocusContributionStoreTest.kt`:

```kotlin
package fr.dayview.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FocusContributionStoreTest {
    private fun at(s: Long) = Instant.fromEpochMilliseconds(s * 1000)
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(at(s), at(e))

    @Test
    fun storeKeepsContributionsPerDeviceAndListsThem() = runTest {
        val store = InMemoryFocusContributionStore()
        store.write(FocusContribution(20260, "aaa", listOf(iv(0, 10)), emptyList()))
        store.write(FocusContribution(20260, "bbb", emptyList(), listOf(iv(20, 30))))
        assertEquals(2, store.listForDay(20260).size)
        assertEquals(setOf(20260L to "aaa", 20260L to "bbb"), store.listKeys().toSet())
    }

    @Test
    fun withMergedFocusUnionsRecordAndContributions() {
        val record = sampleRecord(dayKey = 20260, session = listOf(iv(0, 10)))
        val merged = record.withMergedFocus(
            listOf(FocusContribution(20260, "bbb", emptyList(), listOf(iv(5, 30)))),
        )
        // 0-10 (own) unions with 5-30 (foreign) -> 0-30.
        assertEquals(listOf(iv(0, 30)), merged.focusSessionIntervals)
    }
}
```

> `sampleRecord(...)` — reuse the `DayHistoryRecord` fixture helper already used by `DayHistoryRecordTest.kt` (import it or replicate the minimal constructor call that file uses). Only `dayKey` and `focusSessionIntervals` matter here.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusContributionStoreTest"`
Expected: FAIL — types unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/commonMain/kotlin/fr/dayview/app/FocusContributionStore.kt`:

```kotlin
package fr.dayview.app

/** One device's focus intervals for one day — the unit that syncs on the side channel. */
data class FocusContribution(
    val dayKey: Long,
    val deviceId: String,
    val presence: List<FocusPresenceInterval>,
    val session: List<FocusPresenceInterval>,
)

/**
 * Local store of focus contributions, keyed by (dayKey, deviceId). Holds this device's own
 * archived contributions plus any downloaded from other devices. Unlike [DayHistoryStore] this
 * is not write-once: re-writing the same (day, device) key replaces it (idempotent for sync).
 */
interface FocusContributionStore {
    suspend fun write(contribution: FocusContribution)
    suspend fun read(dayKey: Long, deviceId: String): FocusContribution?
    suspend fun listForDay(dayKey: Long): List<FocusContribution>
    suspend fun listKeys(): List<Pair<Long, String>>
}

class InMemoryFocusContributionStore : FocusContributionStore {
    private val byKey = mutableMapOf<Pair<Long, String>, FocusContribution>()

    override suspend fun write(contribution: FocusContribution) {
        byKey[contribution.dayKey to contribution.deviceId] = contribution
    }

    override suspend fun read(dayKey: Long, deviceId: String): FocusContribution? = byKey[dayKey to deviceId]

    override suspend fun listForDay(dayKey: Long): List<FocusContribution> = byKey.values.filter { it.dayKey == dayKey }

    override suspend fun listKeys(): List<Pair<Long, String>> = byKey.keys.toList()
}

/**
 * Display record whose focus intervals are the coalesced union of the record's own intervals
 * and every contribution for that day. Legacy days / no-sync setups (no contributions) render
 * unchanged, since the union of the record with an empty set is the record itself.
 */
fun DayHistoryRecord.withMergedFocus(contributions: List<FocusContribution>): DayHistoryRecord = copy(
    focusPresenceIntervals = mergeIntervals(focusPresenceIntervals + contributions.flatMap { it.presence }),
    focusSessionIntervals = mergeIntervals(focusSessionIntervals + contributions.flatMap { it.session }),
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.FocusContributionStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/FocusContributionStore.kt core/src/commonTest/kotlin/fr/dayview/app/FocusContributionStoreTest.kt
git commit -m "Add focus contribution store and union helper"
```

---

### Task 2: Controller — write own contribution at archive, union at read

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRolloverTest.kt`

**Interfaces:**
- Consumes: `FocusContributionStore`, `FocusContribution`, `withMergedFocus` (Task 1).
- Produces: two new ctor params `focusContributions: FocusContributionStore? = null` and `deviceId: String? = null`; own contribution written in `maybeArchivePreviousDay`; `openHistory` merges foreign contributions into each read record.

- [ ] **Step 1: Write the failing test**

Add to `core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRolloverTest.kt`:

```kotlin
    @Test
    fun archiveWritesOwnFocusContributionAndReadUnionsForeign() = runTest {
        val contributions = InMemoryFocusContributionStore()
        val history = InMemoryDayHistoryStore()
        // Build a controller with history + contributions + deviceId = "self", drive one
        // focus day, then roll over so the day archives (reuse this file's rollover helper).
        val controller = rolloverController(history = history, focusContributions = contributions, deviceId = "self")
        // ... drive a session on day D and advance to D+1 (existing helper) ...
        val d = archivedDayKey(controller) // the day that just archived (helper)
        // Own contribution recorded:
        assertEquals("self", contributions.listForDay(d).single().deviceId)
        // Simulate a foreign contribution, then open history and read the merged record:
        contributions.write(FocusContribution(d, "other", emptyList(), listOf(foreignSession())))
        controller.openHistory()
        val cell = controller.stateFlow.value.historyWeek.first { it.dayKey == d }
        // Displayed session intervals include the foreign contribution.
        assertTrue(cell.record!!.focusSessionIntervals.isNotEmpty())
    }
```

> Reuse the helpers `DayHistoryRolloverTest.kt` already defines for building a controller and forcing a rollover; add the smallest `deviceId`/`focusContributions` plumbing to that helper. `foreignSession()`/`archivedDayKey(...)` are test-local conveniences — keep them trivial.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayHistoryRolloverTest"`
Expected: FAIL — ctor params unknown / contribution not written.

- [ ] **Step 3a: Add ctor params**

In `DayViewController(...)` add (after `history: DayHistoryStore = ...`):

```kotlin
    private val focusContributions: FocusContributionStore? = null,
    private val deviceId: String? = null,
```

- [ ] **Step 3b: Write the own contribution at archive**

In `maybeArchivePreviousDay()`, after the existing `scope.launch { history.write(record) }`, extend the launch body so it also records this device's own contribution (self-provenance — this record was just built from live local state):

```kotlin
    private fun maybeArchivePreviousDay() {
        val key = persistedDayKey(state) ?: return
        if (key == dayKeyOf(state.now)) return
        val record = state.toHistoryRecord(key)
        val self = deviceId
        val contributions = focusContributions
        scope.launch {
            history.write(record)
            if (self != null && contributions != null) {
                contributions.write(
                    FocusContribution(key, self, record.focusPresenceIntervals, record.focusSessionIntervals),
                )
            }
        }
    }
```

- [ ] **Step 3c: Union foreign contributions on read**

In `openHistory()`, where the week cells read stored records (`key in present -> history.read(key)`), merge contributions. Replace that arm and the surrounding record resolution so each non-today stored record is unioned:

```kotlin
            val contributions = focusContributions
            val days = keys.map { key ->
                val record = when {
                    key == todayKey -> todayRecord
                    key in present -> history.read(key)?.let { r ->
                        if (contributions != null) r.withMergedFocus(contributions.listForDay(key)) else r
                    }
                    else -> null
                }
                HistoryWeekDay(key, record, now = if (key == todayKey) state.now else null)
            }
```

> Today's live cell is not unioned — today has no archived contributions yet; its engaged figure is the local live one (see Plan/spec non-goal on live cross-device merge).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DayHistoryRolloverTest"` then `./gradlew :core:jvmTest`
Expected: PASS (no regressions).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add core/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt core/src/commonTest/kotlin/fr/dayview/app/DayHistoryRolloverTest.kt
git commit -m "Write own focus contribution at archive and union on read"
```

---

### Task 3: Focus blob key, codec AAD, DTO + mapper (sync)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HistoryKey.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HistoryBlobCodec.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionMapper.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionMapperTest.kt`

**Interfaces:**
- Consumes: `FocusContribution` (core), `PresenceDto`, `HISTORY_SCHEMA_VERSION`, `SyncJson`, `RawSyncKey`.
- Produces:
  - `HistoryKey.opaqueFocusKey(dayKey: Long, deviceId: String): String`
  - `HistoryBlobCodec.encryptFocus(dayKey, deviceId, plaintext)` / `decryptFocus(dayKey, deviceId, ciphertext)`
  - `FocusContributionDto` + `FocusContributionMapper.serialize/deserialize`

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionMapperTest.kt`:

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FocusContributionMapperTest {
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))

    @Test
    fun roundTripsThroughJson() {
        val c = FocusContribution(20260, "dev1", listOf(iv(1, 2)), listOf(iv(3, 4), iv(5, 6)))
        val back = FocusContributionMapper.deserialize(FocusContributionMapper.serialize(c))
        assertEquals(c, back)
    }

    @Test
    fun malformedJsonDeserializesToNull() {
        assertNull(FocusContributionMapper.deserialize("{ not json"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.FocusContributionMapperTest"`
Expected: FAIL — `FocusContributionMapper` unresolved.

- [ ] **Step 3a: Add the focus index key**

In `HistoryKey.kt`, add a second derived key and method (mirroring `indexKey`/`opaqueKey`):

```kotlin
    private val focusIndexKey = run {
        val root = hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, rawKey.bytes)
        val derived = root.signatureGenerator().generateSignatureBlocking("dayview-focus-index-v1".encodeToByteArray())
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, derived)
    }

    fun opaqueFocusKey(dayKey: Long, deviceId: String): String =
        focusIndexKey.signatureGenerator().generateSignatureBlocking("$dayKey:$deviceId".encodeToByteArray()).toHex()
```

> `rawKey` is the ctor param already captured for `indexKey`; keep the same reference. `toHex()` already exists in this file.

- [ ] **Step 3b: Add focus AAD encrypt/decrypt**

In `HistoryBlobCodec.kt`, add:

```kotlin
    private fun focusAad(dayKey: Long, deviceId: String) =
        "dayview-focus-v$HISTORY_SCHEMA_VERSION:$dayKey:$deviceId".encodeToByteArray()

    suspend fun encryptFocus(dayKey: Long, deviceId: String, plaintext: String): String =
        Base64.encode(cipher.encrypt(plaintext.encodeToByteArray(), focusAad(dayKey, deviceId)))

    suspend fun decryptFocus(dayKey: Long, deviceId: String, ciphertext: String): String = try {
        cipher.decrypt(Base64.decode(ciphertext), focusAad(dayKey, deviceId)).decodeToString()
    } catch (e: Exception) {
        throw SyncKeyMismatchException(e)
    }
```

- [ ] **Step 3c: Add the DTO + mapper**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionMapper.kt`:

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class FocusContributionDto(
    val schemaVersion: Int,
    val dayKey: Long,
    val deviceId: String,
    val presence: List<PresenceDto>,
    val session: List<PresenceDto>,
)

object FocusContributionMapper {
    private fun List<FocusPresenceInterval>.toDto() = map { PresenceDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) }
    private fun List<PresenceDto>.toIntervals() = map { FocusPresenceInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end)) }

    fun serialize(c: FocusContribution): String = SyncJson.encodeToString(
        FocusContributionDto(HISTORY_SCHEMA_VERSION, c.dayKey, c.deviceId, c.presence.toDto(), c.session.toDto()),
    )

    fun deserialize(json: String): FocusContribution? = try {
        val d = SyncJson.decodeFromString<FocusContributionDto>(json)
        FocusContribution(d.dayKey, d.deviceId, d.presence.toIntervals(), d.session.toIntervals())
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.FocusContributionMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HistoryKey.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/HistoryBlobCodec.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionMapper.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionMapperTest.kt
git commit -m "Add focus contribution blob key, codec, and mapper"
```

---

### Task 4: `focusContributions` manifest in the sync document

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt`

**Interfaces:**
- Produces: `SyncDocument.focusContributions: List<String>` (entries `"dayKey:deviceId"`), merged by union like `historyDays`, seeded from base in `buildDocument`.

- [ ] **Step 1: Write the failing test**

Add to `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt`:

```kotlin
    @Test
    fun focusContributionsMergeByUnion() {
        val a = baseDocument().copy(focusContributions = listOf("20260:aaa"))
        val b = baseDocument().copy(focusContributions = listOf("20260:bbb", "20260:aaa"))
        assertEquals(listOf("20260:aaa", "20260:bbb"), a.merge(b).focusContributions)
    }
```

> `baseDocument()` — reuse the minimal `SyncDocument` factory the other tests in this file already use.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.SyncMergeTest"`
Expected: FAIL — `focusContributions` unknown on `SyncDocument`.

- [ ] **Step 3a: Add the field**

In `SyncDocument.kt`, add to `data class SyncDocument` (after `historyDays`):

```kotlin
    val focusContributions: List<String> = emptyList(),
```

- [ ] **Step 3b: Merge it**

In `SyncMerge.kt`, inside `copy(...)`, add (after the `historyDays = ...` line):

```kotlin
        focusContributions = (focusContributions + remote.focusContributions).distinct().sorted(),
```

- [ ] **Step 3c: Seed it in buildDocument**

In `SyncMapper.kt`, in `buildDocument(...)`'s returned `SyncDocument(...)`, add (after `historyDays = base?.historyDays ?: emptyList(),`):

```kotlin
        focusContributions = base?.focusContributions ?: emptyList(),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.SyncMergeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncDocument.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncMergeTest.kt
git commit -m "Carry focus contributions manifest in the sync document"
```

---

### Task 5: `FocusContributionSync` + engine wiring

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionSync.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncEngine.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionSyncTest.kt`

**Interfaces:**
- Consumes: `DayHistoryStore`, `FocusContributionStore`, `FocusContribution`, `SyncTransport`, `HistoryBlobCodec` (`encryptFocus`/`decryptFocus`), `HistoryKey` (`opaqueFocusKey`), `FocusContributionMapper`.
- Produces: `FocusContributionSync(history, contributions, transport, blobCodec, keyIndex, deviceId, maxPerCycle = 50).reconcile(knownManifest: List<String>): List<String>`.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionSyncTest.kt` (mirror `HistorySyncTest.kt`'s fake transport):

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.InMemoryFocusContributionStore
import fr.dayview.app.InMemoryDayHistoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FocusContributionSyncTest {
    private fun iv(s: Long, e: Long) = FocusPresenceInterval(Instant.fromEpochMilliseconds(s), Instant.fromEpochMilliseconds(e))
    private val key = RawSyncKey(ByteArray(32) { 7 })

    @Test
    fun uploadsOwnContributionsAndAddsThemToManifest() = runTest {
        val transport = FakeFocusTransport() // reuse/adapt HistorySyncTest's fake, exposing focus blobs
        val store = InMemoryFocusContributionStore().apply {
            write(FocusContribution(20260, "self", emptyList(), listOf(iv(0, 10))))
        }
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        val manifest = sync.reconcile(emptyList())
        assertEquals(listOf("20260:self"), manifest)
    }

    @Test
    fun downloadsForeignContributionsIntoTheStore() = runTest {
        val transport = FakeFocusTransport()
        // Seed the server with device "other"'s blob under opaqueFocusKey(20260, "other").
        val other = FocusContribution(20260, "other", emptyList(), listOf(iv(20, 30)))
        transport.seedFocus(HistoryKey(key).opaqueFocusKey(20260, "other"), HistoryBlobCodec(key).encryptFocus(20260, "other", FocusContributionMapper.serialize(other)))
        val store = InMemoryFocusContributionStore()
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        sync.reconcile(listOf("20260:other"))
        assertEquals(other, store.read(20260, "other"))
    }

    @Test
    fun skipsDownloadOfOwnDeviceEntries() = runTest {
        val transport = FakeFocusTransport()
        val store = InMemoryFocusContributionStore()
        val sync = FocusContributionSync(store, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId = "self")
        sync.reconcile(listOf("20260:self")) // our own entry, nothing to fetch
        assertTrue(store.listKeys().isEmpty())
    }
}
```

> `FakeFocusTransport` — a `SyncTransport` fake exposing `putHistoryDay`/`getHistoryDay` over an in-memory map plus a `seedFocus(key, blob)` helper. `HistorySyncTest.kt` already has a fake of exactly this shape for history blobs; copy it and rename, or extend it so both tests share it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.FocusContributionSyncTest"`
Expected: FAIL — `FocusContributionSync` unresolved.

- [ ] **Step 3a: Write the sync class**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionSync.kt`:

```kotlin
package fr.dayview.app.sync

import fr.dayview.app.FocusContributionStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-device focus side channel. Uploads this device's own contributions and downloads
 * others', keeping the write-once history record untouched. Entries are "dayKey:deviceId".
 */
class FocusContributionSync(
    private val store: FocusContributionStore,
    private val transport: SyncTransport,
    private val blobCodec: HistoryBlobCodec,
    private val keyIndex: HistoryKey,
    private val deviceId: String,
    private val maxPerCycle: Int = 50,
) {
    private fun entry(dayKey: Long, device: String) = "$dayKey:$device"

    private fun parse(entry: String): Pair<Long, String>? {
        val i = entry.indexOf(':')
        if (i <= 0) return null
        val day = entry.substring(0, i).toLongOrNull() ?: return null
        return day to entry.substring(i + 1)
    }

    suspend fun reconcile(knownManifest: List<String>): List<String> {
        val known = knownManifest.toSet()

        // Download foreign entries we don't yet have locally.
        for (item in knownManifest.mapNotNull(::parse).filter { it.second != deviceId }.sortedBy { it.first }.take(maxPerCycle)) {
            val (day, device) = item
            if (store.read(day, device) != null) continue
            try {
                val blob = transport.getHistoryDay(keyIndex.opaqueFocusKey(day, device)) ?: continue
                val contribution = FocusContributionMapper.deserialize(blobCodec.decryptFocus(day, device, blob)) ?: continue
                store.write(contribution)
            } catch (e: SyncKeyMismatchException) {
                // wrong key for this blob — skip
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // transient — retried next cycle
            }
        }

        // Upload our own contributions the manifest doesn't know yet.
        val uploaded = mutableSetOf<String>()
        val ownKeys = store.listKeys().filter { it.second == deviceId }.map { entry(it.first, it.second) }
        for (e in (ownKeys.toSet() - known).sorted().take(maxPerCycle)) {
            val (day, device) = parse(e) ?: continue
            val contribution = store.read(day, device) ?: continue
            try {
                val blob = blobCodec.encryptFocus(day, device, FocusContributionMapper.serialize(contribution))
                transport.putHistoryDay(keyIndex.opaqueFocusKey(day, device), blob)
                uploaded += e
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                // transient — not added to the manifest, retried next cycle
            }
        }

        return (known + uploaded).sorted()
    }
}
```

- [ ] **Step 3b: Wire it into the engine**

In `SyncEngine.kt`, add a ctor param (after `historySync`):

```kotlin
    private val focusSync: FocusContributionSync? = null,
```

In `sync(...)`, right after the `historySync` reconcile block, add:

```kotlin
                if (focusSync != null) {
                    merged = merged.copy(focusContributions = focusSync.reconcile(merged.focusContributions))
                }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.FocusContributionSyncTest"` then `./gradlew :composeApp:testDebugUnitTest --tests "fr.dayview.app.sync.SyncEngineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/FocusContributionSync.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncEngine.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/FocusContributionSyncTest.kt
git commit -m "Sync per-device focus contributions in the engine"
```

---

### Task 6: File-backed store + platform wiring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt` (add `FileFocusContributionStore` reusing `HistoryFileSystem`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt` (construct `FocusContributionSync`, pass to engine)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewPreferences.kt` (expose a process-wide `FocusContributionStore`)
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt` and `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (pass the store + deviceId into `DayViewApp`/controller and `SyncCoordinator`)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (accept and forward `focusContributions` + `deviceId` to the controller)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/FileFocusContributionStoreTest.kt` (only if a fake `HistoryFileSystem` already exists to reuse)

**Interfaces:**
- Consumes: `HistoryFileSystem`, `createHistoryFileSystem()`, `FocusContributionMapper`, `SyncCoordinator` fields.
- Produces: `createFocusContributionStore(): FocusContributionStore`; `SyncCoordinator(... focusContributionStore = ...)`; `DayViewApp(..., focusContributions = ..., deviceId = ...)`.

- [ ] **Step 1: Add the file-backed store (reuse the history filesystem)**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, add below `FileDayHistoryStore`:

```kotlin
/**
 * Focus contributions on the same directory as the history archive, under `focus_<day>_<device>`
 * filenames. [FileDayHistoryStore] ignores these (its keys are bare numeric dayKeys), so the two
 * families coexist in one directory without a second platform filesystem.
 */
internal class FileFocusContributionStore(private val fs: HistoryFileSystem) : FocusContributionStore {
    private fun name(dayKey: Long, deviceId: String) = "focus_${dayKey}_$deviceId"

    override suspend fun write(contribution: FocusContribution) {
        fs.writeAtomic(name(contribution.dayKey, contribution.deviceId), FocusContributionMapperBridge.serialize(contribution))
    }

    override suspend fun read(dayKey: Long, deviceId: String): FocusContribution? =
        fs.read(name(dayKey, deviceId))?.let { FocusContributionMapperBridge.deserialize(it) }

    override suspend fun listForDay(dayKey: Long): List<FocusContribution> =
        keys().filter { it.first == dayKey }.mapNotNull { read(it.first, it.second) }

    override suspend fun listKeys(): List<Pair<Long, String>> = keys()

    private fun keys(): List<Pair<Long, String>> = fs.list().mapNotNull { n ->
        if (!n.startsWith("focus_")) return@mapNotNull null
        val rest = n.removePrefix("focus_")
        val i = rest.indexOf('_')
        if (i <= 0) return@mapNotNull null
        val day = rest.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        day to rest.substring(i + 1)
    }
}

internal fun createFocusContributionStore(): FocusContributionStore =
    createHistoryFileSystem()?.let { FileFocusContributionStore(it) } ?: InMemoryFocusContributionStore()
```

> `FocusContributionMapperBridge` — the serialize/deserialize used here lives in `composeApp/.../sync/FocusContributionMapper.kt` (Task 3). Since `DayHistoryStore.kt` is not in the `sync` package, either import `fr.dayview.app.sync.FocusContributionMapper` directly (preferred — no bridge needed; drop the `Bridge` name and call `FocusContributionMapper.serialize/deserialize`) or move the file store into the `sync` package. Choose the import; it is the smallest change.

- [ ] **Step 2: Expose the store on Android**

In `DayViewPreferences.kt`, mirror `history()` with a process-wide focus store:

```kotlin
    private var focusStoreInstance: FocusContributionStore? = null

    internal fun focusContributions(): FocusContributionStore = focusStoreInstance ?: synchronized(this) {
        focusStoreInstance ?: createFocusContributionStore().also { focusStoreInstance = it }
    }
```

Also null it out in `resetForTest()` next to `historyStoreInstance = null`:

```kotlin
            focusStoreInstance = null
```

- [ ] **Step 3: Thread store + deviceId through App**

In `App.kt`, add params (near `history: DayHistoryStore`):

```kotlin
    focusContributions: FocusContributionStore? = null,
    deviceId: String? = null,
```

Pass them into the `remember { DayViewController(...) }` construction:

```kotlin
                            focusContributions = focusContributions,
                            deviceId = deviceId,
```

- [ ] **Step 4: Construct `FocusContributionSync` in the coordinator**

In `SyncCoordinator.kt`, add a ctor field `private val focusContributionStore: FocusContributionStore? = null,` (next to `historyStore`), then in `runOnce()` after `historySync`:

```kotlin
        val focusSync = focusContributionStore?.let {
            FocusContributionSync(it, transport, HistoryBlobCodec(key), HistoryKey(key), deviceId)
        }
        val engine = SyncEngine(transport, codecFactory(key), deviceId, historySync = historySync, focusSync = focusSync)
```

- [ ] **Step 5: Wire the platforms**

Android — in `MainActivity.kt`: pass `focusContributionStore = DayViewPreferences.focusContributions()` into the `SyncCoordinator(...)` call, and add to the `DayViewApp(...)` call:

```kotlin
                focusContributions = DayViewPreferences.focusContributions(),
                deviceId = deviceId,
```

(`deviceId` is already computed above the `setContent` block.)

Desktop — in `Main.kt`: build a `deviceId` from the desktop key store the same way (`keyStore.deviceIdOrCreate { ... }` — reuse the desktop equivalent already used for sync), create `val focusStore = createFocusContributionStore()`, pass `focusContributionStore = focusStore` into `SyncCoordinator(...)`, and `focusContributions = focusStore, deviceId = deviceId` into `App(...)`. Desktop keeps `derivesEngagedFromSessions` off (Plan A) — it still archives its own contributions and unions foreign ones on read.

- [ ] **Step 6: Build and run the suites**

Run: `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS on both platforms.

- [ ] **Step 7: Commit**

```bash
./gradlew ktlintFormat
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt composeApp/src/androidMain/kotlin/fr/dayview/app/DayViewPreferences.kt composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "Wire focus contribution store and sync across platforms"
```

---

### Task 7: Full gate + convergence check

- [ ] **Step 1: Run the complete gate**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint errors, no failures, no stderr.

- [ ] **Step 2: Two-device convergence smoke**

Configure sync on a desktop and an Android device with the same recovery phrase. On day D, do a focus session on each. After both roll over to D+1 and a sync cycle runs on each, open history for day D on both: the engaged figure should equal the **union** of the two sessions on both devices, and on a freshly-synced third device.

- [ ] **Step 3: Commit any fixups**

```bash
git add -A
git commit -m "Tidy focus contribution wiring" # only if ktlintFormat changed files
```

## Self-Review Notes (author)

- **Spec coverage:** side channel + manifest + `FocusContributionSync` (§3) → Tasks 3–6; read-time union + `mergeIntervals` (§4) → Tasks 1–2; provenance → Task 2 (own written only at archive) and Task 5 (upload filters `deviceId == self`). No manifest bound (Decisions) → honoured.
- **Type consistency:** `FocusContribution(dayKey, deviceId, presence, session)`, `opaqueFocusKey(dayKey, deviceId)`, `encryptFocus/decryptFocus(dayKey, deviceId, ...)`, manifest entries `"dayKey:deviceId"`, and `FocusContributionSync(store, transport, blobCodec, keyIndex, deviceId, maxPerCycle)` are used identically across tasks.
- **Write-once safety:** the local history store and the server history PUT are never overwritten; contributions use disjoint `(day, device)` keys and `focus_*` filenames the record store ignores.
- **Open follow-up (not blocking):** manifest retention bound; possible migration of desktop engaged persistence onto the shared snapshot. Deferred by design.
