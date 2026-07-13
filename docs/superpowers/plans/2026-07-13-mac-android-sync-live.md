# Mac/Android Sync — Going Live Implementation Plan (Plan 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the headless sync engine (Plan 1) into the running app so state actually synchronises between macOS and Android against a self-hosted endpoint, using a sync key the user configures through a simple settings screen.

**Architecture:** A `commonMain` `SyncCoordinator` owns the loop: it reads the local `DayPreferencesSnapshot`, loads the key from a platform `SecureKeyStore`, loads persisted `SyncConfig`/`SyncState`, runs `SyncEngine.sync`, applies the result back through `DayPreferences.persist`, and persists the new `SyncState`. It is triggered on app foreground/resume and after local writes (debounced), with a mutex that coalesces concurrent triggers. Platform seams (`SecureKeyStore`, the Ktor `HttpClient`) are constructed at each entry point and injected, mirroring how `DayPreferences` is already wired. A minimal compare-and-set endpoint runs on the maintainer's existing 24/7 server.

**Tech Stack:** Kotlin Multiplatform, Ktor client (OkHttp on Android, Java on desktop), kotlinx.serialization, androidx DataStore, androidx.security EncryptedSharedPreferences (Android key storage), Compose Multiplatform (settings screen).

## Global Constraints

- JDK 21 toolchain; `compileSdk 36`; Robolectric needs JDK 21.
- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`. Run `./gradlew ktlintFormat` to auto-fix.
- Commit messages in English; describe the change only; **never** reference Claude/Anthropic/AI, and **never** reference `docs/superpowers/` planning docs.
- Sync code lives in `fr.dayview.app.sync` (`composeApp/src/commonMain/kotlin/fr/dayview/app/sync/`); tests mirror under `commonTest`.
- Compose UI test guardrail (from the repo): NEVER assert `stringResource` text in `runComposeUiTest` — use `DayViewTestTags` and seeded data. Test pure screens, not `DayViewApp`.
- Secrets (sync key, endpoint token) MUST NOT be written into the plaintext `DayPreferences` DataStore. They live only in `SecureKeyStore`.
- Deployment (decided): the endpoint runs on the maintainer's existing 24/7 server over HTTPS + Bearer token. No LAN/mDNS/Tailscale in scope.
- Instants serialize as epoch-millis `Long`, `-1L` = absent (matches Plan 1 and `DayPreferencesStore`).

## Out of scope (deferred to Plan 3)

QR-code and recovery-phrase key provisioning (QR encode on desktop, camera scan on Android). Plan 2 provisions the key via a text field: "Generate" produces a base64 key to copy to the other device, or paste an existing one. This is enough to make sync fully functional; Plan 3 replaces the text field with QR + a 24-word backup phrase.

## Prerequisite from the Plan 1 final review

Task 1 below fixes the `recentDetourMotifs` recency/truncation gap identified in Plan 1's whole-branch review. This MUST land before the trigger wiring (Task 6), or live sync will reorder recent motifs alphabetically and tombstone genuinely-recent ones.

## File Structure

- `sync/RecentMotifs.kt` (or fold into `SyncMapper.kt`/`SyncMerge.kt`) — recency-preserving top-N truncation for `recentDetourMotifs`.
- `sync/SyncConfig.kt` — `SyncConfig(baseUrl, userId, token)` + serialization.
- `sync/SyncStateStore.kt` — persists `SyncState` (revision + base document) locally; `SyncConfigStore` persists `SyncConfig` in the secure store.
- `sync/SecureKeyStore.kt` — `interface SecureKeyStore`; platform factories `androidSecureKeyStore(context)` / `desktopSecureKeyStore()` in the respective source sets.
- `sync/SyncHttpClient.kt` — platform factory `createSyncHttpClient()` (`expect`/`actual`) returning a configured Ktor `HttpClient`.
- `sync/SyncCoordinator.kt` — the trigger/loop brain; `SyncStatus` for UI.
- `SyncSettingsScreen.kt` (commonMain) — settings UI; `SettingsCategory.SYNC` wiring.
- `androidMain/.../SecureKeyStore.android.kt`, `desktopMain/.../SecureKeyStore.desktop.kt`, and the `SyncHttpClient` actuals.
- `server/` (repo root, outside `composeApp`) — reference endpoint + README. Language is the maintainer's choice; a minimal reference is provided.

---

## Task 1: Fix `recentDetourMotifs` recency ordering and top-N truncation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/RecentMotifsTest.kt`

**Interfaces:**
- Consumes: `SyncDocument`, `SyncItem`, `Stamp` (Plan 1).
- Produces: after merge+apply, `recentDetourMotifs` is the non-deleted motifs ordered **most-recent-first by `Stamp`**, truncated to `MAX_RECENT_DETOUR_MOTIFS` (the existing cap in `Detours.kt`), and items beyond the cap become tombstones (bounding growth). `applyDocument` returns them most-recent-first, not alphabetically.

Context: Plan 1's `applyDocument` currently does `document.recentDetourMotifs.filterNot { it.deleted }.map { it.value }`, which inherits the merge's `sortedBy { it.id }` (alphabetical) order and never truncates. Check the exact cap name in `composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt` (referenced as `MAX_RECENT_DETOUR_MOTIFS`).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentMotifsTest {
    private fun item(v: String, at: Long) = SyncItem(v, v, false, Stamp(at, "a"))

    @Test
    fun applyReturnsMotifsMostRecentFirst() {
        val doc = sampleDocument().copy(
            recentDetourMotifs = listOf(item("apple", 100), item("zebra", 300), item("mango", 200)),
        )
        val result = applyDocument(doc, DayPreferencesSnapshot())
        assertEquals(listOf("zebra", "mango", "apple"), result.recentDetourMotifs) // by stamp desc, not alphabetical
    }

    @Test
    fun applyTruncatesToCapKeepingMostRecent() {
        val many = (1..(MAX_RECENT_DETOUR_MOTIFS + 3)).map { item("m$it", it.toLong()) }
        val doc = sampleDocument().copy(recentDetourMotifs = many)
        val result = applyDocument(doc, DayPreferencesSnapshot())
        assertEquals(MAX_RECENT_DETOUR_MOTIFS, result.recentDetourMotifs.size)
        assertEquals("m${MAX_RECENT_DETOUR_MOTIFS + 3}", result.recentDetourMotifs.first()) // newest kept
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.RecentMotifsTest"`
Expected: FAIL — order is alphabetical and/or not truncated.

- [ ] **Step 3: Write minimal implementation**

In `SyncMapper.kt`, replace the `recentDetourMotifs` line inside `applyDocument`:

```kotlin
recentDetourMotifs = document.recentDetourMotifs
    .filterNot { it.deleted }
    .sortedByDescending { it.stamp.at }
    .take(MAX_RECENT_DETOUR_MOTIFS)
    .map { it.value },
```

Add `import fr.dayview.app.MAX_RECENT_DETOUR_MOTIFS` (confirm the symbol's package/visibility in `Detours.kt`; if it is `private`, promote it to `internal` there).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.RecentMotifsTest"`
Expected: PASS.

- [ ] **Step 5: Bound tombstone growth in the document**

So the document itself does not accumulate unbounded tombstones, cap what `buildDocument` keeps. In `SyncMapper.kt`, after building `recentDetourMotifs` items, keep only the newest `MAX_RECENT_DETOUR_MOTIFS` live items plus tombstones for the rest that were previously present, and drop tombstones older than the newest live item. Add a focused test `buildBoundsRecentMotifTombstones` asserting the built document's `recentDetourMotifs` size stays `<= 2 * MAX_RECENT_DETOUR_MOTIFS`. Implement the cap in the `buildItems` call site for recent motifs only (leave day-scoped collections unchanged — they self-GC on day rollover).

- [ ] **Step 6: Run tests and commit**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.RecentMotifsTest" --tests "fr.dayview.app.sync.SyncMapperTest" --tests "fr.dayview.app.sync.SyncMergeTest"`
Expected: PASS (existing mapper/merge tests still green).

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMapper.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncMerge.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/RecentMotifsTest.kt composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt
git commit -m "Preserve recency order and bound recent-motif entries in sync"
```

---

## Task 2: SyncConfig and SyncState persistence

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncConfig.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncStateStore.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncStateStoreTest.kt`

**Interfaces:**
- Consumes: `SyncState`, `SyncDocument` (Plan 1), `SyncJson`.
- Produces:
  - `@Serializable data class SyncConfig(val baseUrl: String, val userId: String, val token: String)`.
  - `interface SyncStatePersistence { suspend fun load(): SyncState; suspend fun save(state: SyncState) }` — persists `SyncState(baseRevision, baseDocument)` as JSON. Default when nothing stored: `SyncState(null, null)`.
  - `class FileSyncStatePersistence(private val read: suspend () -> String?, private val write: suspend (String) -> Unit) : SyncStatePersistence` — I/O injected as lambdas so it is testable with an in-memory backing in `commonTest` and backed by a real file/DataStore at the platform entry.
  - `@Serializable private data class StoredState(val baseRevision: String?, val baseDocument: SyncDocument?)`.

Rationale: `SyncState.baseDocument` must survive app restarts, or `buildDocument` re-stamps every field as fresh on the next launch and local data would wrongly "win" the merge after every restart. It contains plaintext user state — same sensitivity as the existing DataStore — so a plain local file is acceptable; it is NOT a secret store.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncStateStoreTest {
    private var backing: String? = null
    private fun store() = FileSyncStatePersistence(read = { backing }, write = { backing = it })

    @Test
    fun loadsDefaultWhenEmpty() = runTest {
        val state = store().load()
        assertNull(state.baseRevision)
        assertNull(state.baseDocument)
    }

    @Test
    fun roundTripsRevisionAndDocument() = runTest {
        val s = store()
        s.save(SyncState("r5", sampleDocument()))
        val loaded = store().load()
        assertEquals("r5", loaded.baseRevision)
        assertEquals(sampleDocument(), loaded.baseDocument)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncStateStoreTest"`
Expected: FAIL — `SyncConfig`/`FileSyncStatePersistence` unresolved.

- [ ] **Step 3: Write minimal implementation**

`SyncConfig.kt`:

```kotlin
package fr.dayview.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncConfig(val baseUrl: String, val userId: String, val token: String)
```

`SyncStateStore.kt`:

```kotlin
package fr.dayview.app.sync

import kotlinx.serialization.Serializable

interface SyncStatePersistence {
    suspend fun load(): SyncState
    suspend fun save(state: SyncState)
}

@Serializable
private data class StoredState(val baseRevision: String? = null, val baseDocument: SyncDocument? = null)

class FileSyncStatePersistence(
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
) : SyncStatePersistence {
    override suspend fun load(): SyncState {
        val text = read() ?: return SyncState(null, null)
        val stored = SyncJson.decodeFromString<StoredState>(text)
        return SyncState(stored.baseRevision, stored.baseDocument)
    }

    override suspend fun save(state: SyncState) {
        write(SyncJson.encodeToString(StoredState(state.baseRevision, state.baseDocument)))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncStateStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncConfig.kt composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncStateStore.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncStateStoreTest.kt
git commit -m "Add sync config and sync-state persistence"
```

---

## Task 3: SecureKeyStore (interface + platform factories)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SecureKeyStore.kt`
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/sync/SecureKeyStore.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/sync/SecureKeyStore.desktop.kt`
- Modify: `gradle/libs.versions.toml`, `composeApp/build.gradle.kts` (add `androidx.security:security-crypto`)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SecureKeyStoreContractTest.kt` (against an in-memory impl)

**Interfaces:**
- Consumes: `RawSyncKey`, `SyncConfig` (Tasks 2 / Plan 1).
- Produces:
  - `interface SecureKeyStore { fun loadKey(): RawSyncKey?; fun storeKey(key: RawSyncKey); fun loadConfig(): SyncConfig?; fun storeConfig(config: SyncConfig); fun clear() }`.
  - `class InMemorySecureKeyStore : SecureKeyStore` (commonMain, for tests and the Compose default).
  - Android: `fun androidSecureKeyStore(context: Context): SecureKeyStore` backed by `EncryptedSharedPreferences` (key + config JSON stored base64).
  - Desktop: `fun desktopSecureKeyStore(file: File = File(System.getProperty("user.home"), ".dayview/sync.secret")): SecureKeyStore`, a `600`-permission file holding key + config JSON.

**Verification note:** the interface contract is unit-tested against `InMemorySecureKeyStore` in `commonTest`. The Android actual is smoke-tested in `androidUnitTest` under Robolectric if `EncryptedSharedPreferences` initialises there; if it does not (it needs the Android Keystore, often unavailable under Robolectric), gate that test with an availability check and rely on manual on-device verification (documented in the task report). The desktop actual is unit-tested in `desktopTest` against a temp file.

- [ ] **Step 1: Write the failing contract test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureKeyStoreContractTest {
    @Test
    fun storesAndLoadsKeyAndConfig() {
        val store = InMemorySecureKeyStore()
        assertNull(store.loadKey())
        val key = RawSyncKey.generate()
        store.storeKey(key)
        store.storeConfig(SyncConfig("https://s", "u", "t"))
        assertEquals(key.bytes.toList(), store.loadKey()!!.bytes.toList())
        assertEquals(SyncConfig("https://s", "u", "t"), store.loadConfig())
    }

    @Test
    fun clearRemovesEverything() {
        val store = InMemorySecureKeyStore()
        store.storeKey(RawSyncKey.generate())
        store.clear()
        assertNull(store.loadKey())
        assertNull(store.loadConfig())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SecureKeyStoreContractTest"`
Expected: FAIL — `SecureKeyStore`/`InMemorySecureKeyStore` unresolved.

- [ ] **Step 3: Write commonMain interface + in-memory impl**

```kotlin
package fr.dayview.app.sync

interface SecureKeyStore {
    fun loadKey(): RawSyncKey?
    fun storeKey(key: RawSyncKey)
    fun loadConfig(): SyncConfig?
    fun storeConfig(config: SyncConfig)
    fun clear()
}

class InMemorySecureKeyStore : SecureKeyStore {
    private var key: RawSyncKey? = null
    private var config: SyncConfig? = null
    override fun loadKey() = key
    override fun storeKey(key: RawSyncKey) { this.key = key }
    override fun loadConfig() = config
    override fun storeConfig(config: SyncConfig) { this.config = config }
    override fun clear() { key = null; config = null }
}
```

- [ ] **Step 4: Add the dependency**

In `gradle/libs.versions.toml`: `securityCrypto = "1.1.0-beta01"` under `[versions]`; `androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }` under `[libraries]`. In `composeApp/build.gradle.kts`, add `implementation(libs.androidx.security.crypto)` to `androidMain.dependencies`.

- [ ] **Step 5: Write the Android actual**

`SecureKeyStore.android.kt` — back it with `EncryptedSharedPreferences.create(...)` using a `MasterKey` (AES256_GCM). Store the key as Base64 under `"sync_key"` and the config JSON under `"sync_config"`. Provide `fun androidSecureKeyStore(context: Context): SecureKeyStore`. (Complete code: build the `MasterKey`, open `EncryptedSharedPreferences`, implement the five methods reading/writing the two string entries; `loadKey` decodes Base64 to `RawSyncKey`, returns null if absent; `loadConfig` `SyncJson.decodeFromString` or null.)

- [ ] **Step 6: Write the desktop actual**

`SecureKeyStore.desktop.kt` — `fun desktopSecureKeyStore(file: File = ...): SecureKeyStore`. Store a small JSON `{keyB64, config}` in `file`; on first write, `file.parentFile.mkdirs()` and set POSIX permissions `600` via `Files.setPosixFilePermissions` (guarded for non-POSIX filesystems). Document in the file header that this is on-disk-at-rest and weaker than a hardware keystore; the macOS Keychain migration is deferred. Add a `desktopTest` `SecureKeyStoreDesktopTest` that round-trips key+config through a temp file.

- [ ] **Step 7: Run tests, lint, commit**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SecureKeyStore.kt composeApp/src/androidMain/kotlin/fr/dayview/app/sync/SecureKeyStore.android.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/sync/SecureKeyStore.desktop.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SecureKeyStoreContractTest.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/sync/SecureKeyStoreDesktopTest.kt
git commit -m "Add secure key store for sync credentials"
```

---

## Task 4: Sync HTTP client factory (expect/actual)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncHttpClient.kt`
- Create: `composeApp/src/androidMain/kotlin/fr/dayview/app/sync/SyncHttpClient.android.kt`
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/sync/SyncHttpClient.desktop.kt`

**Interfaces:**
- Produces: `expect fun createSyncHttpClient(): HttpClient` — installs `ContentNegotiation { json(SyncJson) }`; Android uses the OkHttp engine, desktop the Java engine (both already on the classpath from Plan 1 Task 1). No test (thin platform factory; exercised end-to-end by Task 5's coordinator tests using `MockEngine`, and by manual run).

- [ ] **Step 1: Write the expect declaration + actuals**

commonMain:

```kotlin
package fr.dayview.app.sync

import io.ktor.client.HttpClient

expect fun createSyncHttpClient(): HttpClient
```

Android actual (`SyncHttpClient.android.kt`): `import io.ktor.client.engine.okhttp.OkHttp` → `actual fun createSyncHttpClient() = HttpClient(OkHttp) { install(ContentNegotiation) { json(SyncJson) } }`.

Desktop actual (`SyncHttpClient.desktop.kt`): `import io.ktor.client.engine.java.Java` → `actual fun createSyncHttpClient() = HttpClient(Java) { install(ContentNegotiation) { json(SyncJson) } }`.

- [ ] **Step 2: Verify compilation on both targets**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncHttpClient.kt composeApp/src/androidMain/kotlin/fr/dayview/app/sync/SyncHttpClient.android.kt composeApp/src/desktopMain/kotlin/fr/dayview/app/sync/SyncHttpClient.desktop.kt
git commit -m "Add platform Ktor client factory for sync"
```

---

## Task 5: SyncCoordinator — the loop brain

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DayPreferences`, `SecureKeyStore`, `SyncStatePersistence`, `SyncTransport` factory, `PayloadCodec` factory, `SyncEngine`.
- Produces:
  - `enum class SyncStatus { Idle, Syncing, Ok, KeyError, Failed, NotConfigured }`.
  - `class SyncCoordinator(deviceId, keyStore, statePersistence, preferences, transportFactory: (SyncConfig) -> SyncTransport, codecFactory: (RawSyncKey) -> PayloadCodec, scope, now: () -> Long)` with `val status: StateFlow<SyncStatus>` and `suspend fun syncNow()`.
  - `syncNow()`: if key or config missing → `NotConfigured`, return. Otherwise: build engine, load `SyncState`, read `preferences.snapshots.first()`, run `engine.sync`; on `Applied` → `preferences.persist(result.snapshot)` and `statePersistence.save(result.state)`, status `Ok`; on `UpToDate` → `Ok`; on `KeyError` → `KeyError`; on `Failed` → `Failed`. A `Mutex` serialises calls; a trigger arriving while a sync runs sets a "re-run once after" flag (coalescing) rather than queuing many.

**Verification:** fully unit-tested in `commonTest` with a fake `SecureKeyStore` (`InMemorySecureKeyStore`), an in-memory `SyncStatePersistence`, a fake `DayPreferences`, and the identity `PlainCodec` + a `FakeTransport` (reuse the pattern from `SyncEngineTest`).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakePrefs(initial: DayPreferencesSnapshot) : DayPreferences {
    val state = MutableStateFlow(initial)
    override val snapshots = state
    override suspend fun persist(snapshot: DayPreferencesSnapshot) { state.value = snapshot }
}

private class OneShotTransport : SyncTransport {
    var pushes = 0
    override suspend fun pull(): RemoteSnapshot? = null
    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++; return PushOutcome.Applied("r1")
    }
}

class SyncCoordinatorTest {
    private fun coordinator(keyStore: SecureKeyStore, prefs: DayPreferences, transport: SyncTransport, scope: kotlinx.coroutines.CoroutineScope) =
        SyncCoordinator(
            deviceId = "a", keyStore = keyStore,
            statePersistence = FileSyncStatePersistence(read = { null }, write = {}),
            preferences = prefs,
            transportFactory = { transport }, codecFactory = { PlainCodec },
            scope = scope, now = { 1000L },
        )

    @Test
    fun notConfiguredWhenNoKey() = runTest {
        val c = coordinator(InMemorySecureKeyStore(), FakePrefs(DayPreferencesSnapshot()), OneShotTransport(), this)
        c.syncNow()
        assertEquals(SyncStatus.NotConfigured, c.status.first())
    }

    @Test
    fun syncsAndPersistsWhenConfigured() = runTest {
        val ks = InMemorySecureKeyStore().apply {
            storeKey(RawSyncKey.generate()); storeConfig(SyncConfig("https://s", "u", "t"))
        }
        val prefs = FakePrefs(DayPreferencesSnapshot(startMinutes = 501))
        val transport = OneShotTransport()
        val c = coordinator(ks, prefs, transport, this)
        c.syncNow()
        assertEquals(SyncStatus.Ok, c.status.first())
        assertEquals(1, transport.pushes)
    }
}
```

`PlainCodec` is the identity codec already defined in `SyncEngineTest.kt`; move it (and reuse) into a small shared test helper file `SyncTestCodecs.kt` under `commonTest` so both tests use it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest"`
Expected: FAIL — `SyncCoordinator` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncStatus { Idle, Syncing, Ok, KeyError, Failed, NotConfigured }

class SyncCoordinator(
    private val deviceId: String,
    private val keyStore: SecureKeyStore,
    private val statePersistence: SyncStatePersistence,
    private val preferences: DayPreferences,
    private val transportFactory: (SyncConfig) -> SyncTransport,
    private val codecFactory: (RawSyncKey) -> PayloadCodec,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _status = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var rerunRequested = false

    suspend fun syncNow() {
        if (mutex.isLocked) { rerunRequested = true; return }
        mutex.withLock {
            do {
                rerunRequested = false
                runOnce()
            } while (rerunRequested)
        }
    }

    private suspend fun runOnce() {
        val key = keyStore.loadKey()
        val config = keyStore.loadConfig()
        if (key == null || config == null) { _status.value = SyncStatus.NotConfigured; return }
        _status.value = SyncStatus.Syncing
        val engine = SyncEngine(transportFactory(config), codecFactory(key), deviceId)
        val local = preferences.snapshots.first()
        val state = statePersistence.load()
        _status.value = when (val result = engine.sync(local, state, now())) {
            is SyncResult.Applied -> {
                preferences.persist(result.snapshot)
                statePersistence.save(result.state)
                SyncStatus.Ok
            }
            SyncResult.UpToDate -> SyncStatus.Ok
            SyncResult.KeyError -> SyncStatus.KeyError
            is SyncResult.Failed -> SyncStatus.Failed
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncTestCodecs.kt
git commit -m "Add sync coordinator with coalescing trigger loop"
```

---

## Task 6: Trigger wiring into the app (foreground/resume + debounced write)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncTriggerEffect.kt` (expect) + `.android.kt` / `.desktop.kt` actuals
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/MainActivity.kt`, `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (construct `SecureKeyStore` + `SyncCoordinator`, pass into `DayViewApp`)

**Interfaces:**
- `DayViewApp(preferences, ..., syncCoordinator: SyncCoordinator? = null)` — nullable so the Compose default/preview path stays coordinator-free.
- Consumes: `SyncCoordinator` (Task 5), the lifecycle idiom from `RefreshClockOnResumeEffect` (§3/§4 of the wiring map).

**This is an integration task — verification is by running the app, not a unit test.** The pure trigger-coalescing logic is already covered in Task 5.

- [ ] **Step 1: Add a foreground/resume trigger effect**

Create `SyncTriggerEffect.kt`: `@Composable expect fun SyncOnResumeEffect(onResume: () -> Unit)`. Android actual mirrors `RefreshClockOnResumeEffect.android.kt` (collect `lifecycle.eventFlow`, filter `ON_RESUME`, call `onResume`). Desktop actual: no lifecycle hook exists (see wiring map §4) — implement as a `LaunchedEffect(Unit) { while (true) { onResume(); delay(5.minutes) } }` periodic trigger, OR hook window focus in `Main.kt`. Keep it a no-op `= Unit` if a coordinator isn't wired.

- [ ] **Step 2: Wire triggers in App.kt**

Where `DayViewApp` composes, if `syncCoordinator != null`:
- `SyncOnResumeEffect { scope.launch { syncCoordinator.syncNow() } }`.
- Debounced write trigger: after `preferences.snapshots.collect { ... }` folds a change, restart a debounce and call `syncNow()` — implement as a `LaunchedEffect` collecting a `MutableSharedFlow` that `DayViewController.persistState()` emits to, `.debounce(3.seconds)` then `syncNow()`. Add the emit hook to the controller (a `onLocalWrite: () -> Unit = {}` callback passed into the controller, invoked at the end of `persistState`).
- One initial `LaunchedEffect(syncCoordinator) { syncCoordinator.syncNow() }` on startup.

- [ ] **Step 3: Construct the coordinator at each entry point**

- `MainActivity.kt`: build `androidSecureKeyStore(applicationContext)`, a `FileSyncStatePersistence` reading/writing `File(filesDir, "sync/state.json")`, and `SyncCoordinator(deviceId = <stable per-install id>, ...)` with `transportFactory = { HttpSyncTransport(createSyncHttpClient(), it.baseUrl, it.userId, it.token) }`, `codecFactory = { Aes256GcmCodec(it) }`; pass into `DayViewApp`. A stable `deviceId` can be a random UUID persisted once in the secure store or a plain prefs entry.
- `Main.kt`: same with `desktopSecureKeyStore()` and `File(System.getProperty("user.home"), ".dayview/sync-state.json")`.

- [ ] **Step 4: Verify by running the app**

Manual verification (record results in the task report):
- Desktop: launch with a key+config configured pointing at a test endpoint (Task 8), change the day start, confirm a `PUT` is observed and the status becomes `Ok`.
- Confirm a second desktop instance (or the reference endpoint's stored blob) reflects the change; apply a change there and confirm the first instance pulls it on next resume/trigger.
- Confirm no plaintext leaves the client (inspect the stored blob is opaque).
Run: `./gradlew :composeApp:run` (desktop). For Android: `./gradlew :composeApp:installDebug` and drive on a device/emulator.

- [ ] **Step 5: Full gate + commit**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "Trigger state sync on resume and after local writes"
```

---

## Task 7: Sync settings screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt`
- Modify: `DayViewController.kt` (`SettingsCategory.SYNC`), `DayViewSettingsScreen.kt` (detail dispatch + title/description/summary), `SettingsUiModels.kt` (`settingsCategoriesFor`), `DayViewTestTags.kt`, `composeResources/values*/strings.*`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncSettingsScreenTest.kt` (tag/callback based, per repo convention)

**Interfaces:**
- `@Composable internal fun SyncSettingsScreen(config: SyncConfig?, status: SyncStatus, hasKey: Boolean, onConfigChange: (SyncConfig) -> Unit, onGenerateKey: () -> String, onPasteKey: (String) -> Unit, onSyncNow: () -> Unit, onClear: () -> Unit)` — pure/stateless, mirrors `NetTimeSettingsScreen`.
- Fields: server URL, user id, token (masked), a "Generate key" button (returns Base64 to display for copying), a "Paste key" field, a "Sync now" button, and a status line. Add `SettingsCategory.SYNC` and register it in `settingsCategoriesFor(...)`.

**Verification:** pure-screen Compose test using `DayViewTestTags` (assert the "Sync now" tag invokes `onSyncNow`, the URL field invokes `onConfigChange`), plus a `SettingsCategoriesTest` update asserting `SYNC` appears. No `stringResource` assertions.

- [ ] **Step 1: Add test tags + a failing screen test**

Add `SYNC_SETTINGS_SYNC_NOW`, `SYNC_SETTINGS_URL`, etc. to `DayViewTestTags.kt`. Write `SyncSettingsScreenTest` that renders `SyncSettingsScreen(...)` with seeded values and asserts: clicking the sync-now tag calls `onSyncNow`; typing in the URL tag calls `onConfigChange`.

- [ ] **Step 2: Run — fails (screen doesn't exist)**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the screen + category wiring**

Build `SyncSettingsScreen` from `SettingsComponents` (`SettingsPanelCard`, `SettingsToggleRow`/text fields, `SettingsAccentButton`, `SettingsDivider`). Add `SettingsCategory.SYNC` to the enum, the `when` arms in `DayViewSettingsScreen.kt` (detail dispatch + `categoryTitle`/`categoryDescription`/`categorySummary`), the entry in `settingsCategoriesFor(...)`, and new string resources in every `values*/strings` file. Wire the screen's callbacks in `App.kt`'s settings-actions block to the `SecureKeyStore`/`SyncCoordinator` (store config/key, call `syncNow`).

- [ ] **Step 4: Run — passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest" --tests "fr.dayview.app.SettingsCategoriesTest"`
Expected: PASS.

- [ ] **Step 5: Full gate + commit**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`

```bash
git add -A
git commit -m "Add sync settings screen for endpoint and key configuration"
```

---

## Task 8: Reference endpoint and deployment note

**Files:**
- Create: `server/README.md`, `server/sync-endpoint.<lang>` (reference implementation)

**Interfaces:** implements the exact protocol `HttpSyncTransport` speaks: `GET /sync/{userId}` (Bearer auth) → `200 {revision, payload}` or `204`; `PUT /sync/{userId}` with `If-Match: <rev>` or `If-None-Match: *`, body `{payload}` → `200 {revision}` or `412 {revision, payload}` on precondition failure. Stores an opaque blob per user with a monotonically increasing revision; never inspects the payload.

**This is server-side, outside the app build — verified by curl, not the Gradle suite.**

- [ ] **Step 1: Write a minimal reference implementation**

Provide one small, dependency-light server (language is the maintainer's choice — a ~100-line Ktor/Node/Python file). It must: authenticate the Bearer token; store `{userId → (revision, payload)}` (in-memory + a file, or SQLite); implement compare-and-set on `If-Match`/`If-None-Match`; return `412` with the current `{revision, payload}` on mismatch. Bind it behind the maintainer's existing TLS reverse proxy.

- [ ] **Step 2: Document deployment + a curl smoke test**

`server/README.md`: how to run behind the existing 24/7 server's TLS proxy, how to set the Bearer token, and a curl sequence proving: first `PUT` with `If-None-Match: *` → 200; second `PUT` with a stale `If-Match` → 412 returning current; `GET` → 200 with the stored blob.

- [ ] **Step 3: Smoke-test against the real deployment**

Run the curl sequence from Step 2 against the deployed endpoint; paste results in the task report. Then point a desktop instance's Sync settings at it and confirm a real round-trip (ties back to Task 6 manual verification).

- [ ] **Step 4: Commit**

```bash
git add server/
git commit -m "Add reference sync endpoint and deployment notes"
```

---

## Self-Review (completed)

- **Spec coverage vs. design doc:** §6 E2EE key storage → Task 3 (`SecureKeyStore`); §7 endpoint/deployment on existing server → Task 8; §8 triggers (resume + debounced write, coalescing mutex) → Tasks 5–6; local `SyncState`/`SyncConfig` persistence → Task 2; the Plan 1 final-review `recentDetourMotifs` fix → Task 1. Key provisioning UX is deliberately the text-field form here; QR + recovery phrase is Plan 3.
- **Testable core vs. integration:** Tasks 1, 2, 3 (contract), 5, 7 are unit-tested in the Gradle suite. Tasks 4, 6, 8 are platform/integration/server and are verified by compilation + running the app + curl, as each task states explicitly (not hidden).
- **Type consistency:** `SyncStatus`, `SyncCoordinator`, `SyncConfig`, `SecureKeyStore`, `SyncStatePersistence` names are used identically across Tasks 2/3/5/6/7. `createSyncHttpClient`/`Aes256GcmCodec`/`HttpSyncTransport`/`SyncEngine` match Plan 1 signatures.
- **Secret handling:** the sync key and token never enter the `DayPreferences` DataStore (Global Constraints); only `SecureKeyStore` holds them. `SyncState.baseDocument` (plaintext user state) is stored locally like the existing DataStore, which is consistent with current sensitivity.

## Plan 3 preview (not part of this plan)

Replace Task 7's text-field key entry with real provisioning UX: generate a random `RawSyncKey`, render it as a QR code (and a 24-word recovery phrase) on one device, scan/enter it on the other; camera permission + scanner on Android, QR rendering on desktop. Plus: `KeyError` recovery flow in the UI, and the AAD/schema forward-migration path flagged in Plan 1's review (before any `SYNC_SCHEMA_VERSION` bump).
