# Sync Auto-Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a transient sync failure (network down, server error), `SyncCoordinator` automatically retries with exponential backoff until sync succeeds, so sync self-heals when the network returns.

**Architecture:** All changes live in `SyncCoordinator` (common code). After each sync run it classifies the outcome: a `SyncResult.Failed` whose cause is *not* `SyncAuthenticationException` schedules a retry on the coordinator's injected `CoroutineScope` with backoff 15s → 30s → 1m → 2m → 5m (cap, indefinite). Any other outcome cancels the pending retry and resets the backoff. Spec: `docs/superpowers/specs/2026-07-15-sync-auto-retry-design.md`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines 1.11.0 (`kotlinx-coroutines-test` with `runTest` virtual time), kotlin.test.

## Global Constraints

- No new `SyncStatus` values; no UI changes; no platform (Android/desktop) source changes.
- Retry state (`retryJob`, `retryAttempt`) is only mutated while holding the coordinator's existing `mutex` (all mutation happens inside `runOnce`).
- ktlint is enforced: run `./gradlew ktlintFormat` before each commit if in doubt.
- Full pre-commit gate: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest` must pass without errors before the final commit.
- Commit messages in English, no AI/Claude references, no "Verification" sections.

---

### Task 1: Retry scheduling on transient failure (fixed 15s delay)

Introduce the retry mechanism: a transient failure schedules a retry; an authentication failure or missing configuration does not. The delay is a constant 15s in this task; Task 2 adds the backoff progression.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: `SyncCoordinator` as it exists today (`syncNow()`, `runOnce()`, injected `scope: CoroutineScope`, `mutex`), `SyncResult.Failed(cause)`, `SyncAuthenticationException`.
- Produces: private `suspend fun updateRetrySchedule(result: SyncResult?)` called at the end of every `runOnce` path (including the not-configured early return, with `result = null`); private fields `retryJob: Job?` and `retryAttempt: Int`. Task 2 extends `updateRetrySchedule` with the delay table; keep the exact name.

- [ ] **Step 1: Write the failing tests**

In `SyncCoordinatorTest.kt`, add a fake transport next to the existing ones (after `AuthenticationFailureTransport`):

```kotlin
/** Fails the next [failuresRemaining] pulls with a connection-style error, then succeeds. */
private class FlakyTransport(var failuresRemaining: Int) : SyncTransport {
    var pulls = 0
    private var pushes = 0

    override suspend fun pull(): RemoteSnapshot? {
        pulls++
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("connection refused")
        }
        return null
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome {
        pushes++
        return PushOutcome.Applied("r$pushes")
    }

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}
```

Add a pull counter to the existing `AuthenticationFailureTransport` (it currently throws without counting):

```kotlin
private class AuthenticationFailureTransport : SyncTransport {
    var pulls = 0

    override suspend fun pull(): RemoteSnapshot? {
        pulls++
        throw SyncAuthenticationException()
    }

    override suspend fun push(payload: String, expectedRevision: String?): PushOutcome = error("not reached")

    override suspend fun putHistoryDay(opaqueKey: String, payload: String) = Unit

    override suspend fun getHistoryDay(opaqueKey: String): String? = null
}
```

Inside the `SyncCoordinatorTest` class, add a keystore helper (the existing tests inline this; the new tests need it repeatedly):

```kotlin
private fun configuredKeyStore() = InMemorySecureKeyStore().apply {
    storeKey(RawSyncKey.generate())
    storeConfig(SyncConfig("https://s", "u", "t"))
}
```

Then add the two tests. IMPORTANT: pass `backgroundScope` (not `this`) as the coordinator scope — a retry pending at test end would otherwise hang `runTest`; `backgroundScope` jobs are cancelled automatically. Use `testScheduler.advanceTimeBy(...)` + `testScheduler.runCurrent()` to fire due retries (`advanceTimeBy` stops *just before* tasks scheduled exactly at the target time; `runCurrent()` executes them).

```kotlin
@Test
fun transientFailureSchedulesAutomaticRetry() = runTest {
    val transport = FlakyTransport(failuresRemaining = 1)
    val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

    c.syncNow()
    assertEquals(SyncStatus.Failed, c.status.first())
    assertEquals(1, transport.pulls)

    // The retry fires 15s later and succeeds without any external trigger.
    testScheduler.advanceTimeBy(15.seconds)
    testScheduler.runCurrent()
    assertEquals(2, transport.pulls)
    assertEquals(SyncStatus.Ok, c.status.first())

    // Once healthy, nothing else is scheduled.
    testScheduler.advanceTimeBy(1.hours)
    testScheduler.runCurrent()
    assertEquals(2, transport.pulls)
}

@Test
fun authenticationFailureIsNotRetried() = runTest {
    val transport = AuthenticationFailureTransport()
    val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

    c.syncNow()
    assertEquals(SyncStatus.Failed, c.status.first())

    testScheduler.advanceTimeBy(1.hours)
    testScheduler.runCurrent()
    assertEquals(1, transport.pulls)
}
```

New imports needed in the test file:

```kotlin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
```

Also update the existing test `verifyReportsAuthenticationSeparately` if it passes `this` as scope — it can stay as is (auth failures schedule nothing), but any test using `FlakyTransport` MUST use `backgroundScope`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest" 2>&1 | tail -20`
Expected: `transientFailureSchedulesAutomaticRetry` FAILS (expected 2 pulls, got 1 — no retry exists yet). `authenticationFailureIsNotRetried` may already pass; that's fine.

- [ ] **Step 3: Implement retry scheduling in SyncCoordinator**

In `SyncCoordinator.kt`, add imports:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
```

Replace the stale constructor-parameter comment on `scope` (it says "Reserved for wiring app-level sync triggers ... in a later task."):

```kotlin
    // Hosts the auto-retry loop that re-runs sync after transient failures.
    private val scope: CoroutineScope,
```

Add fields after `private val mutex = Mutex()`:

```kotlin
    // Auto-retry state. Only mutated inside runOnce (which always holds [mutex]),
    // so no extra synchronization is needed.
    private var retryJob: Job? = null
    private var retryAttempt = 0
```

Wire `runOnce` so *every* exit path updates the schedule:

```kotlin
    private suspend fun runOnce(strategy: FirstSyncStrategy? = null) {
        val key = keyStore.loadKey()
        val config = keyStore.loadConfig()
        if (key == null || config == null) {
            _status.value = SyncStatus.NotConfigured
            updateRetrySchedule(result = null)
            return
        }
        // ... existing body unchanged ...
        val result = engine.sync(local, state, now(), strategy)
        _firstSyncChoicePending.value = result is SyncResult.FirstSyncChoiceNeeded
        _status.value =
            when (result) {
                // ... existing when unchanged ...
            }
        updateRetrySchedule(result)
    }
```

Add the scheduler below `runOnce`:

```kotlin
    /**
     * Schedules or clears the automatic retry after a sync run. A [SyncResult.Failed]
     * with any cause other than [SyncAuthenticationException] is treated as transient
     * (network down, server error, exhausted push-conflict retries) and schedules a
     * retry; every other outcome cancels the pending retry and resets the backoff —
     * retrying a bad token or an unresolved first-sync choice cannot help. Runs only
     * from [runOnce] (under [mutex]). When the currently running sync *is* the retry
     * job, it is never self-cancelled — only replaced or cleared.
     */
    private suspend fun updateRetrySchedule(result: SyncResult?) {
        val retryable = result is SyncResult.Failed && result.cause !is SyncAuthenticationException
        val previous = retryJob
        if (previous != null && previous !== coroutineContext[Job]) previous.cancel()
        if (retryable) {
            val backoff = 15.seconds
            retryAttempt++
            retryJob = scope.launch {
                delay(backoff)
                syncNow()
            }
        } else {
            retryAttempt = 0
            retryJob = null
        }
    }
```

Also extend the class KDoc (the paragraph about serialized triggers) with one sentence at the end:

```
 * After a transient failure ([SyncResult.Failed] not caused by authentication), the
 * coordinator retries on its own with exponential backoff, so sync recovers without
 * an external trigger once the network returns.
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all `SyncCoordinatorTest` tests pass (existing ones included — none of them assert on scheduling, and non-failed outcomes leave the schedule empty).

- [ ] **Step 5: Run the full pre-commit gate and commit**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. If ktlint complains, run `./gradlew ktlintFormat` and re-check.

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt
git commit -m "Retry sync automatically after a transient failure"
```

---

### Task 2: Exponential backoff progression, cap, and reset

Grow the fixed 15s delay into the 15s → 30s → 1m → 2m → 5m (cap) sequence, reset it on success, and verify a manual sync replaces a pending retry.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: `updateRetrySchedule(result: SyncResult?)`, `retryJob`, `retryAttempt`, and `FlakyTransport(failuresRemaining)` with its `pulls` counter — all from Task 1.
- Produces: private `val retryDelays: List<Duration>` inside `SyncCoordinator`; final behavior of `updateRetrySchedule`. Nothing outside the class changes.

- [ ] **Step 1: Write the failing tests**

Add to `SyncCoordinatorTest`:

```kotlin
@Test
fun backoffGrowsToFiveMinuteCap() = runTest {
    val transport = FlakyTransport(failuresRemaining = Int.MAX_VALUE)
    val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

    c.syncNow() // failure #1 schedules the first retry
    var pulls = 1
    assertEquals(pulls, transport.pulls)

    val expectedDelays = listOf(15.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 5.minutes, 5.minutes)
    for (expected in expectedDelays) {
        // Just before the deadline nothing has fired…
        testScheduler.advanceTimeBy(expected - 1.seconds)
        testScheduler.runCurrent()
        assertEquals(pulls, transport.pulls)
        // …and at the deadline exactly one retry runs.
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()
        pulls++
        assertEquals(pulls, transport.pulls)
    }
}

@Test
fun successResetsBackoffProgression() = runTest {
    val transport = FlakyTransport(failuresRemaining = 2)
    val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

    c.syncNow() // failure #1
    testScheduler.advanceTimeBy(15.seconds)
    testScheduler.runCurrent() // failure #2 → next delay would be 30s
    testScheduler.advanceTimeBy(30.seconds)
    testScheduler.runCurrent() // success
    assertEquals(SyncStatus.Ok, c.status.first())
    assertEquals(3, transport.pulls)

    // A fresh failure streak starts back at 15s, not at the next step.
    transport.failuresRemaining = 1
    c.syncNow() // failure
    assertEquals(4, transport.pulls)
    testScheduler.advanceTimeBy(15.seconds)
    testScheduler.runCurrent() // retry succeeds
    assertEquals(5, transport.pulls)
    assertEquals(SyncStatus.Ok, c.status.first())
}

@Test
fun manualSyncReplacesPendingRetry() = runTest {
    val transport = FlakyTransport(failuresRemaining = 1)
    val c = coordinator(configuredKeyStore(), FakePrefs(DayPreferencesSnapshot()), transport, backgroundScope)

    c.syncNow() // fails, retry pending at +15s
    assertEquals(1, transport.pulls)

    c.syncNow() // manual trigger succeeds and cancels the pending retry
    assertEquals(2, transport.pulls)
    assertEquals(SyncStatus.Ok, c.status.first())

    testScheduler.advanceTimeBy(1.hours)
    testScheduler.runCurrent()
    assertEquals(2, transport.pulls) // the 15s retry never fired
}
```

New import in the test file:

```kotlin
import kotlin.time.Duration.Companion.minutes
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest" 2>&1 | tail -20`
Expected: `backoffGrowsToFiveMinuteCap` FAILS (second retry fires at 15s, not 30s — delay is still constant). `successResetsBackoffProgression` and `manualSyncReplacesPendingRetry` should already pass with the Task 1 implementation; if they fail, fix the implementation, not the tests.

- [ ] **Step 3: Implement the backoff table**

In `SyncCoordinator.kt`, add imports:

```kotlin
import kotlin.time.Duration.Companion.minutes
```

Add the delay table next to the retry fields:

```kotlin
    private val retryDelays = listOf(15.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes)
```

In `updateRetrySchedule`, replace `val backoff = 15.seconds` with:

```kotlin
            val backoff = retryDelays[minOf(retryAttempt, retryDelays.lastIndex)]
```

(`retryAttempt` is 0 on the first failure of a streak and is incremented right after, so consecutive failures walk the table and stay at 5m.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.SyncCoordinatorTest" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run the full pre-commit gate**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, no errors on stderr. If ktlint complains, run `./gradlew ktlintFormat` and re-check.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/SyncCoordinator.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/sync/SyncCoordinatorTest.kt
git commit -m "Grow sync retry delay with exponential backoff capped at 5 minutes"
```
