# macOS Native Attention Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise a drift reminder (Dock badge + single bounce + in-app banner) when the user drifts during a Focus, and show a resumption ritual when a still-active session is found after a relaunch or wake.

**Architecture:** `PresenceCoordinator` gains the two already-relocated detectors and reports `driftReminderAt`/`resumeRitualAt` (Task 1). `DayViewSession` latches them, drives a `DockAttentionProvider`, and exposes booleans + dismiss actions (Task 2). `NSAppDockAttention` implements the Dock affordances natively (Task 3). Swift renders the drift banner and the resume ritual, with the mini→main swap driven from `MiniView` (Task 4).

**Tech Stack:** Kotlin Multiplatform (`:core` common + macosMain), SwiftUI (macOS 15+).

## Global Constraints

- NO change to the detectors' algorithms — `FocusDriftDetector`/`FocusResumeDetector` (relocated to `:core` in 10a) are used exactly as-is, constructed with the injected `dayViewBundleId`.
- Ordering copied from JVM `Main.kt`: resume is checked FIRST; when a ritual fires it clears any pending drift reminder AND suppresses a drift nudge on that tick (`else if`); when the focus is not active both are cleared.
- The Dock badge label is exactly `"!"` (JVM `MacDockBadge.BADGE_TEXT`). The bounce is `requestUserAttention(NSInformationalRequest)`, fired **once per distinct reminder instant** (the JVM's `lastBouncedFor` dedupe), owned in Kotlin.
- The session latches `driftReminderId`/`resumeRitualId` as `Instant?` (booleans reach Swift); dismiss actions clear them; ending a focus clears both.
- The presence block stays gated on `focusActive || presenceWasActive`. Verified equivalent for both detectors: drift returns false and resets when inactive; every `FocusResumeDetector` firing case requires `isFocusActive`, and the closing-edge tick (which IS observed) sets `focusWasActive = false`, so a later fresh session cannot raise a spurious ritual.
- No notification API in this phase (deferred until signing).
- Main window only for in-app surfaces; the mini window gets NO overlay — it only performs the swap.
- Kotlin lint: `./gradlew ktlintCheck`. Commit messages English/imperative/change-only, no AI references; commits succeed unsigned.
- Headless GUI blocked — banner/ritual/Dock are a manual smoke test; report what was and wasn't verified.
- **Full gate** at the end: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`.
- **Before Task 1:** `git checkout -b claude/macos-native-attention-layer`.

## File map

- Modify: `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt` — detectors + 2 Result fields.
- Create: `core/src/commonMain/kotlin/fr/dayview/app/DockAttentionProvider.kt`.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` — latches, dock policy, dismiss actions.
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt` — 2 flags.
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt`, `DayViewSessionTest.kt`.
- Create: `core/src/macosMain/kotlin/fr/dayview/app/NSAppDockAttention.kt`; modify `DayViewNative.kt`.
- Modify: `macos/DayView/TodayModel.swift`, `RingView.swift`, `MiniView.swift`.

---

## Task 1: Wire the detectors into the coordinator (TDD)

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt`

**Interfaces:**
- Consumes: `FocusDriftDetector(dayViewBundleId = ...)`, `FocusResumeDetector()` (both in `:core` since 10a).
- Produces: `PresenceCoordinator.Result` gains `driftReminderAt: Instant?` and `resumeRitualAt: Instant?` (Task 2 consumes them).

- [ ] **Step 1: Write the failing tests**

Append to `PresenceCoordinatorTest`:

```kotlin
    @Test
    fun rapidAppSwitchingRaisesADriftReminder() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        // Past the 30s initial grace, then four distinct frontmost apps inside 45s.
        var fired: Instant? = null
        val apps = listOf("com.a", "com.b", "com.c", "com.d", "com.e")
        for (i in 0..70) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val bundle = if (i < 40) "com.other" else apps[(i - 40) / 2 % apps.size]
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = bundle,
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertTrue(fired != null, "rapid switching should raise a drift reminder")
    }

    @Test
    fun sustainedOffGoalRaisesADriftReminder() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        var fired: Instant? = null
        // One off-goal app held well past the 2-minute sustained rule.
        for (i in 0..200) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = "com.other",
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            if (r.driftReminderAt != null) fired = r.driftReminderAt
        }
        assertTrue(fired != null, "a sustained off-goal stretch should raise a drift reminder")
    }

    @Test
    fun noDriftReminderWhileStayingOnGoal() {
        val coordinator = PresenceCoordinator()
        val start = 1_699_956_000_000L
        val end = Instant.fromEpochMilliseconds(start + 25 * 60_000L)
        for (i in 0..200) {
            val now = Instant.fromEpochMilliseconds(start + i * 1000L)
            val r = coordinator.observe(
                now = now,
                isFocusActive = true,
                frontmostBundleId = "com.on.goal",
                onGoalBundleIds = setOf("com.on.goal"),
                pomodoroEnd = end,
                dayKey = dayKeyOf(now),
            )
            assertEquals(null, r.driftReminderAt)
        }
    }

    @Test
    fun aStillActiveSessionOnFirstObservationRaisesTheResumeRitual() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val r = coordinator.observe(
            now = now,
            isFocusActive = true,
            frontmostBundleId = "com.on.goal",
            onGoalBundleIds = setOf("com.on.goal"),
            pomodoroEnd = Instant.fromEpochMilliseconds(1_699_956_000_000L + 25 * 60_000L),
            dayKey = dayKeyOf(now),
        )
        assertEquals(now, r.resumeRitualAt)
        // The resume tick suppresses a drift nudge (JVM else-if ordering).
        assertEquals(null, r.driftReminderAt)
    }

    @Test
    fun noResumeRitualWhenIdle() {
        val coordinator = PresenceCoordinator()
        val now = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        val r = coordinator.observe(
            now = now,
            isFocusActive = false,
            frontmostBundleId = null,
            onGoalBundleIds = emptySet(),
            pomodoroEnd = null,
            dayKey = dayKeyOf(now),
        )
        assertEquals(null, r.resumeRitualAt)
        assertEquals(null, r.driftReminderAt)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.PresenceCoordinatorTest'`
Expected: FAIL to compile — `driftReminderAt`/`resumeRitualAt` unresolved.

- [ ] **Step 3: Add the detectors and the two Result fields**

In `core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt`:

1. Extend `Result`:

```kotlin
    data class Result(
        val presenceIntervals: List<FocusPresenceInterval>,
        val sessionIntervals: List<FocusPresenceInterval>,
        val sessionOffGoal: Duration,
        val driftReminderAt: Instant? = null,
        val resumeRitualAt: Instant? = null,
    )
```

2. Add the two detector fields next to the accumulators:

```kotlin
    private val driftDetector = FocusDriftDetector(dayViewBundleId = dayViewBundleId)
    private val resumeDetector = FocusResumeDetector()
```

(If `FocusDriftDetector`'s bundle-id parameter has a different name, use the declared one — check the class.)

3. In `observe`, compute the two signals with the JVM ordering, immediately after `val state = ...` / before the accumulator updates, and pass them to `Result`:

```kotlin
        // JVM Main.kt ordering: the resume ritual wins the tick and suppresses a drift nudge.
        val resumeAt = if (resumeDetector.observe(isFocusActive, now)) now else null
        val driftFired = resumeAt == null && driftDetector.observe(isFocusActive, now, frontmostBundleId, onGoalBundleIds)
        val driftAt = if (driftFired) now else null
```

and return `Result(presence, session, offGoal, driftAt, resumeAt)`.

**Important:** call `resumeDetector.observe(...)` unconditionally on every `observe` call (its internal `hasObserved`/`previousObservation` bookkeeping depends on being called each sampled tick), and keep `driftDetector.observe(...)` behind the `resumeAt == null` short-circuit so it is not consulted on a ritual tick — matching `Main.kt`'s `else if`.

- [ ] **Step 4: Run to verify pass, lint**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.PresenceCoordinatorTest'` then `./gradlew ktlintCheck`
Expected: PASS + BUILD SUCCESSFUL. If the drift tests don't fire, re-read `FocusDriftDetector`'s thresholds (30s grace, 4 switches / 45s window, 120s sustained) and adjust the TEST fixtures' timing — never the detector.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/PresenceCoordinator.kt core/src/commonTest/kotlin/fr/dayview/app/PresenceCoordinatorTest.kt
git commit -m "feat(core): raise drift and resume signals from the presence coordinator"
```

---

## Task 2: Session latches, Dock policy, snapshot flags, dismiss actions (TDD)

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/DockAttentionProvider.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt`
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt`

**Interfaces:**
- Consumes: `Result.driftReminderAt`/`resumeRitualAt` (Task 1).
- Produces: `DockAttentionProvider` (+ `NoopDockAttentionProvider`); snapshot `showDriftReminder`/`showResumeRitual`; session `dismissDriftReminder()`/`dismissResumeRitual()`; constructor param `dockAttention` (Task 3 injects the real one).

- [ ] **Step 1: The provider interface**

Create `core/src/commonMain/kotlin/fr/dayview/app/DockAttentionProvider.kt`:

```kotlin
package fr.dayview.app

/** Dock affordances for a pending drift reminder: a badge, and a single attention bounce. */
interface DockAttentionProvider {
    fun setBadge(visible: Boolean)
    fun bounceOnce()
}

object NoopDockAttentionProvider : DockAttentionProvider {
    override fun setBadge(visible: Boolean) = Unit
    override fun bounceOnce() = Unit
}
```

- [ ] **Step 2: Write the failing tests**

Append to `DayViewSessionTest` (a fake dock recorder plus the flag tests):

```kotlin
    private class FakeDock : DockAttentionProvider {
        var badge: Boolean? = null
        var bounces = 0
        override fun setBadge(visible: Boolean) { badge = visible }
        override fun bounceOnce() { bounces++ }
    }

    @Test
    fun aStillActiveSessionAtLaunchRaisesTheResumeRitual() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val controller = DayViewController(
            DefaultDayPreferences,
            backgroundScope,
            initialSnapshot = DayPreferencesSnapshot(
                startMinutes = 0,
                endMinutes = 1439,
                pomodoroMinutes = 25,
                pomodoroEnd = start + 20.minutes,
                focusIntention = "Ship it",
            ),
            initialNow = start,
        )
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = FakeFrontmostProvider(bundleId = "com.on.goal"),
            now = { clock },
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        clock += 1.seconds
        session.tick()
        runCurrent()

        assertTrue(seen.last().showResumeRitual, "a session already running at launch should raise the ritual")

        session.dismissResumeRitual()
        runCurrent()
        assertTrue(!seen.last().showResumeRitual)

        sub.cancel()
    }

    @Test
    fun driftReminderLatchesBadgesAndBouncesOnce() = runTest {
        val start = Instant.fromEpochMilliseconds(1_699_956_000_000L)
        var clock = start
        val dock = FakeDock()
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
        val provider = FakeFrontmostProvider(bundleId = "com.other")
        val session = DayViewSession(
            controller,
            backgroundScope,
            frontmostAppProvider = provider,
            now = { clock },
            dockAttention = dock,
        )
        val seen = mutableListOf<TodaySnapshot>()
        val sub = session.subscribe { seen.add(it) }
        session.startFocus("Ship it")
        runCurrent()

        // Sit off-goal well past the 2-minute sustained rule.
        repeat(200) { clock += 1.seconds; session.tick() }
        runCurrent()

        assertTrue(seen.last().showDriftReminder, "sustained off-goal should latch a drift reminder")
        assertEquals(true, dock.badge)
        assertEquals(1, dock.bounces, "exactly one bounce per reminder")

        // More off-goal ticks must not re-bounce for the same latched reminder.
        repeat(30) { clock += 1.seconds; session.tick() }
        assertEquals(1, dock.bounces)

        session.dismissDriftReminder()
        runCurrent()
        assertTrue(!seen.last().showDriftReminder)
        assertEquals(false, dock.badge)

        sub.cancel()
    }
```

Add the `kotlin.time.Duration.Companion.minutes` import if missing. If `DayPreferencesSnapshot` lacks a `pomodoroEnd`/`focusIntention`/`onGoalApps` constructor param, set it via the controller before constructing the session — check the real signature first and adapt the FIXTURE, not the production code.

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'`
Expected: FAIL to compile — `dockAttention`/`showDriftReminder`/`dismissDriftReminder` unresolved.

- [ ] **Step 4: Snapshot flags**

In `TodaySnapshot.kt`: add to the END of the constructor (after `focusTotalLabel`):

```kotlin
    val showDriftReminder: Boolean,
    val showResumeRitual: Boolean,
```

These are session-latched, not derivable from `DayViewUiState`, so `toTodaySnapshot` needs them passed in. Change its signature to accept them with defaults:

```kotlin
internal fun DayViewUiState.toTodaySnapshot(
    use24Hour: Boolean = true,
    showDriftReminder: Boolean = false,
    showResumeRitual: Boolean = false,
): TodaySnapshot {
```

and map them straight through at the end of the construction:

```kotlin
        showDriftReminder = showDriftReminder,
        showResumeRitual = showResumeRitual,
```

- [ ] **Step 5: Session latches, dock policy, dismiss actions**

In `DayViewSession.kt`:

1. Add a constructor param LAST:

```kotlin
    private val dockAttention: DockAttentionProvider = NoopDockAttentionProvider,
```

2. Add latch fields next to `presenceWasActive`:

```kotlin
    private var driftReminderId: Instant? = null
    private var resumeRitualId: Instant? = null
    private var lastBouncedFor: Instant? = null
```

3. Both snapshot emitters must carry the flags. Update `currentSnapshot()` and `subscribe`:

```kotlin
    fun currentSnapshot(): TodaySnapshot =
        controller.stateFlow.value.toTodaySnapshot(use24Hour, driftReminderId != null, resumeRitualId != null)

    fun subscribe(onEach: (TodaySnapshot) -> Unit): DayViewSubscription {
        val job = scope.launch {
            controller.stateFlow.collect {
                onEach(it.toTodaySnapshot(use24Hour, driftReminderId != null, resumeRitualId != null))
            }
        }
        return object : DayViewSubscription {
            override fun cancel() = job.cancel()
        }
    }
```

4. In `tick()`, inside the existing `if (focusActive || presenceWasActive) { ... }` block, after the three controller pushes, apply the latch + dock policy:

```kotlin
            // Latch the attention signals (a reminder persists until dismissed).
            result.resumeRitualAt?.let {
                resumeRitualId = it
                driftReminderId = null // the ritual supersedes a pending nudge (JVM parity)
            }
            result.driftReminderAt?.let { driftReminderId = it }
            if (!focusActive) {
                driftReminderId = null
                resumeRitualId = null
            }
            applyDockAttention()
```

and add the policy helper plus the dismiss actions (place them near the other public actions):

```kotlin
    private fun applyDockAttention() {
        val pending = driftReminderId
        dockAttention.setBadge(pending != null)
        // One bounce per distinct reminder — the JVM's lastBouncedFor dedupe.
        if (pending != null && pending != lastBouncedFor) {
            lastBouncedFor = pending
            dockAttention.bounceOnce()
        }
        if (pending == null) lastBouncedFor = null
    }

    fun dismissDriftReminder() {
        driftReminderId = null
        applyDockAttention()
        controller.touch()
    }

    fun dismissResumeRitual() {
        resumeRitualId = null
        controller.touch()
    }
```

**`controller.touch()` does not exist** — the dismiss actions must make the subscription re-emit so Swift sees the cleared flag. Do NOT add a controller method. Instead re-emit by re-running the controller's clock tick, which already publishes state:

```kotlin
    fun dismissDriftReminder() {
        driftReminderId = null
        applyDockAttention()
        controller.tick(now())
    }

    fun dismissResumeRitual() {
        resumeRitualId = null
        controller.tick(now())
    }
```

(`controller.tick` with the current instant is idempotent for state purposes and is exactly what the 1 Hz loop already does; it guarantees a fresh emission carrying the updated latch.)

- [ ] **Step 6: Run to verify pass, lint**

Run: `./gradlew :core:jvmTest --tests 'fr.dayview.app.DayViewSessionTest'` then `./gradlew ktlintCheck`
Expected: PASS (all, incl. the two new tests) + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/DockAttentionProvider.kt core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt core/src/commonMain/kotlin/fr/dayview/app/TodaySnapshot.kt core/src/commonTest/kotlin/fr/dayview/app/DayViewSessionTest.kt
git commit -m "feat(core): latch drift and resume state with dock badge and bounce policy"
```

---

## Task 3: Native Dock affordances + wiring

**Files:**
- Create: `core/src/macosMain/kotlin/fr/dayview/app/NSAppDockAttention.kt`
- Modify: `core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt`
- Modify: `macos/DayView/TodayModel.swift`

**Interfaces:**
- Consumes: `DockAttentionProvider` (Task 2); `DayViewSession(..., dockAttention)`.
- Produces: `NSAppDockAttention`; `TodayModel.dismissDriftReminder()`/`dismissResumeRitual()`.

- [ ] **Step 1: The native provider**

Create `core/src/macosMain/kotlin/fr/dayview/app/NSAppDockAttention.kt`:

```kotlin
package fr.dayview.app

import platform.AppKit.NSApplication
import platform.AppKit.NSInformationalRequest

/**
 * Dock affordances for a pending drift reminder. The badge label "!" matches the JVM
 * MacDockBadge; the informational request is the single bounce macOS plays only while
 * DayView is not frontmost — exactly the drift situation.
 */
class NSAppDockAttention : DockAttentionProvider {
    override fun setBadge(visible: Boolean) {
        val tile = NSApplication.sharedApplication.dockTile
        tile.badgeLabel = if (visible) "!" else null
        tile.display()
    }

    override fun bounceOnce() {
        NSApplication.sharedApplication.requestUserAttention(NSInformationalRequest)
    }
}
```

(Bounded binding adaptation, as in prior K/N AppKit interop: `sharedApplication` may need a `Companion` qualifier; `NSInformationalRequest` may be a nested enum entry or a `ULong` constant; `@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)` may be required. Verify names against the klib metadata rather than guessing; the semantics — `"!"` badge, informational bounce — are fixed.)

- [ ] **Step 2: Wire it**

In `DayViewNative.create()`, pass it to the session alongside the existing arguments:

```kotlin
            dockAttention = NSAppDockAttention(),
```

- [ ] **Step 3: `TodayModel` passthroughs**

In `macos/DayView/TodayModel.swift`, add after the on-goal passthroughs:

```swift
    func dismissDriftReminder() { session.dismissDriftReminder() }
    func dismissResumeRitual() { session.dismissResumeRitual() }
```

- [ ] **Step 4: Native compile + build**

Run: `./gradlew :core:compileKotlinMacosArm64` (adapt binding names if needed), then `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches (no visible change yet — nothing renders the flags until Task 4). Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Commit**

```bash
git add core/src/macosMain/kotlin/fr/dayview/app/NSAppDockAttention.kt core/src/macosMain/kotlin/fr/dayview/app/DayViewNative.kt macos/DayView/TodayModel.swift
git commit -m "feat(core): dock badge and bounce for the native attention layer"
```

---

## Task 4: Swift — drift banner, resume ritual, mini→main swap

**Files:**
- Modify: `macos/DayView/RingView.swift`
- Modify: `macos/DayView/MiniView.swift`

**Interfaces:**
- Consumes: snapshot `showDriftReminder`/`showResumeRitual`, `focusIntention`, `pomodoroClock`; `TodayModel.dismissDriftReminder()`/`dismissResumeRitual()`/`stopFocus()`.

- [ ] **Step 1: The two overlays in `RingView`**

In `macos/DayView/RingView.swift`:

1. Add the environment values needed for the swap/activation, next to the existing `@Environment`:

```swift
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
```

2. Add two computed overlay views (place them near `closureSection`):

```swift
    // Drift nudge: an amber panel restating the intention, dismissable in one click.
    private var driftBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("BACK TO THE ESSENTIAL")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.amber)
            Text(model.snapshot.focusIntention.isEmpty ? "One thing at a time." : model.snapshot.focusIntention)
                .foregroundStyle(palette.cloud)
            HStack {
                Spacer()
                Button("BACK AT IT") { model.dismissDriftReminder() }
                    .buttonStyle(.bordered)
                    .tint(palette.amber)
            }
        }
        .dayViewPanel(palette)
    }

    // Resumption ritual: a still-running session found at launch or after a wake.
    private var resumeRitual: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("YOUR RESUME POINT")
                .font(.caption2).bold().kerning(1.2).foregroundStyle(palette.mint)
            Text(model.snapshot.focusIntention.isEmpty ? "One thing at a time." : model.snapshot.focusIntention)
                .foregroundStyle(palette.cloud)
            if !model.snapshot.pomodoroClock.isEmpty {
                Text("\(model.snapshot.pomodoroClock) left to stay on track.")
                    .font(.caption).foregroundStyle(palette.muted)
            }
            HStack {
                Spacer()
                Button("Stop") { model.stopFocus(); model.dismissResumeRitual() }
                    .buttonStyle(.bordered)
                    .tint(palette.red)
                Button("Resume") { model.dismissResumeRitual() }
                    .buttonStyle(.bordered)
                    .tint(palette.mint)
            }
        }
        .dayViewPanel(palette)
    }
```

3. Insert them at the TOP of the main `VStack(spacing: 28)` in `body` (before `ringSection`), so an attention moment reads first:

```swift
                if model.snapshot.showResumeRitual {
                    resumeRitual
                } else if model.snapshot.showDriftReminder {
                    driftBanner
                }
```

4. Bring the window forward when a ritual appears — add to the `ScrollView` (alongside the existing `.background`/`.sheet` modifiers):

```swift
        .onChange(of: model.snapshot.showResumeRitual) { _, showing in
            // The ritual is deliberately interruptive: surface the window it lives in.
            if showing { NSApplication.shared.activate(ignoringOtherApps: true) }
        }
```

(Add `import AppKit` at the top of the file if it is not already imported.)

- [ ] **Step 2: The mini→main swap in `MiniView`**

The windows are mutually exclusive (Phase 5b), so when the mini is showing, `RingView` is not rendered and cannot react. `MiniView` performs the swap; it shows NO overlay itself.

In `macos/DayView/MiniView.swift`, add to the root view (alongside the existing `.sheet`/`.background` modifiers):

```swift
        .onChange(of: model.snapshot.showResumeRitual) { _, showing in
            // A ritual while in mini mode restores full mode (JVM parity), where the
            // ritual panel is rendered.
            if showing {
                openWindow(id: "main")
                dismissWindow(id: "mini")
            }
        }
```

`MiniView` already has `@Environment(\.openWindow)` and `@Environment(\.dismissWindow)` from Phase 5b — reuse them; do not add duplicates.

- [ ] **Step 3: Build and launch**

Run: `./gradlew :core:runMacNative`
Expected: `** BUILD SUCCEEDED **`; the app launches unchanged when no attention state is pending.

- [ ] **Step 4: Verify + manual smoke**

Automatic: build, launch, process alive (`pgrep -f 'Debug/DayView.app'`). The **manual smoke test** (GUI + Dock behaviour blocked in-sandbox) — report as not-verified:

1. Configure an on-goal app in Settings; start a Focus; switch to a different app and stay there past 2 minutes (or switch rapidly between four apps within 45s) → the Dock icon bounces **once** and shows a "!" badge, and the drift banner appears at the top of the window.
2. "BACK AT IT" clears the banner and the badge; staying off-goal does not immediately re-bounce (5-minute cooldown).
3. Quit and relaunch while a Focus is still running → the resume ritual appears, the window comes to the front; **Resume** dismisses and the session continues; **Stop** ends it.
4. Repeat (3) while the **mini** window is showing → it swaps back to the main window and shows the ritual there.
5. Fronting DayView itself during a Focus must not trigger drift (10a's bundle-id fix).

Close: `pkill -f 'macos/build/Build/Products/Debug/DayView.app' || true`.

- [ ] **Step 5: Full gate + commit**

Run the full gate (`./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`), then:

```bash
git add macos/DayView/RingView.swift macos/DayView/MiniView.swift
git commit -m "feat(macos): drift banner and resumption ritual in the native app"
```

---

## Self-Review Notes

- **Spec coverage:** detector wiring + JVM else-if ordering → Task 1 (5 tests: rapid-switch drift, sustained-off-goal drift, no-drift-when-on-goal, launch ritual + same-tick drift suppression, idle produces neither); latched flags + dismiss actions + snapshot booleans → Task 2 (2 tests incl. the badge/bounce-once assertions); Dock badge `"!"` + informational bounce + dedupe by reminder instant → Task 2 `applyDockAttention` and Task 3 `NSAppDockAttention`; drift banner + resume ritual copy → Task 4 Step 1; window-to-front and the mini→main swap → Task 4 Steps 1.4/2; notification explicitly absent.
- **Type consistency:** `Result(..., driftReminderAt, resumeRitualAt)` ↔ the session's latches; `DockAttentionProvider(setBadge/bounceOnce)` ↔ `FakeDock` ↔ `NSAppDockAttention`; `toTodaySnapshot(use24Hour, showDriftReminder, showResumeRitual)` updated at BOTH emitter call sites (`currentSnapshot` and `subscribe`) — missing one would make the flag invisible to Swift; `dockAttention` added last with a default so existing call sites/tests are unaffected.
- **No placeholders:** every step carries complete code; the `controller.touch()` dead end is called out explicitly with the re-emit approach that replaces it, and the K/N binding note bounds the permitted adaptation.
- **YAGNI:** no notification, no mini overlay, no session-detail popup, no new persistence.
- **Known nuance:** the presence block's `focusActive || presenceWasActive` gate is preserved; the plan's Global Constraints record why it is behaviourally safe for both detectors (drift resets when inactive; every resume-firing case requires an active focus, and the observed closing-edge tick clears `focusWasActive`, so a later fresh session cannot raise a spurious ritual).
