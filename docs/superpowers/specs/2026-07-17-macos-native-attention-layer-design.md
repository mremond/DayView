# macOS Native — Attention layer: drift nudges, Dock affordances, resume ritual (Path B, Phase 10b)

## Context

Phase 10a gave the native macOS app a live frontmost-app feed: while a Focus runs, the session
samples the frontmost application once a second, classifies it against the user's on-goal apps,
and accumulates engaged time. It also relocated the two pure detectors — `FocusDriftDetector`
and `FocusResumeDetector` — into `:core`, ready to be wired.

This phase adds the **attention layer** the JVM app has: when the user drifts (rapid app
switching, or a sustained stretch in a non-on-goal app), DayView raises a reminder of the
intention; and when a still-active session is found after a relaunch or a wake from sleep, a
resumption ritual brings the intention and remaining time back to the foreground.

## Goals

- Wire `FocusDriftDetector` and `FocusResumeDetector` into the existing per-tick presence loop.
- Surface both as latched snapshot flags with dismiss actions.
- Dock affordances: a badge while a drift reminder is pending, and a single Dock bounce per
  reminder.
- In-app surfaces in the main window: a dismissable drift banner, and a resume ritual that
  brings the window to the front.

## Non-Goals (deferred)

- **The `UNUserNotificationCenter` banner** — its own follow-up. macOS 15's notification API
  requires `requestAuthorization`, and delivery is unreliable for unsigned/ad-hoc dev builds, so
  it is deferred until the packaging phase provides a signed app. (The JVM posts via the
  deprecated `NSUserNotification`, which needs no authorization; that API is not the right
  choice for a new native app.) Recorded on the parity checklist as a tracked item.
- The focus-session detail hover popup (separate checklist item).
- Mini-window attention surfaces (the JVM mini has none).
- Any change to the detectors' algorithms — they are used exactly as relocated.

## Decisions (from brainstorming)

1. **Dock + in-app first; the notification is a later, signing-dependent follow-up.** Everything
   in 10b works in an unsigned dev build with no permission prompt, so the smoke test is
   conclusive.
2. **Drift and resume ship together** — both detectors hang off the same per-tick frontmost feed
   and the wiring is nearly identical.

## Architecture

### `:core` — detector wiring and latched flags

`PresenceCoordinator` gains the two detectors and returns two more fields:

```kotlin
data class Result(
    val presenceIntervals: List<FocusPresenceInterval>,
    val sessionIntervals: List<FocusPresenceInterval>,
    val sessionOffGoal: Duration,
    val driftReminderAt: Instant?,   // non-null on the tick a drift reminder fires
    val resumeRitualAt: Instant?,    // non-null on the tick a resume ritual should show
)
```

- `driftReminderAt` = `now` when `FocusDriftDetector.observe(isFocusActive, now,
  frontmostBundleId, onGoalBundleIds)` returns true, else null. The detector already implements
  the 4-switches-in-45s rule, the 2-minute sustained-off-goal rule, the 30s initial grace and
  the 5-minute reminder cooldown; it is constructed with the injected `dayViewBundleId` (10a) so
  dev builds classify DayView itself correctly.
- `resumeRitualAt` = `now` when `FocusResumeDetector.observe(isFocusActive, now)` returns true —
  a still-active session observed for the first time (launch), or resumed after a ≥15s gap
  between observations (wake from sleep).

Ordering copied from the JVM `Main.kt`: the resume check runs first, and when a ritual fires the
drift reminder is cleared and no drift nudge is raised on that tick; when the focus is not
active, both are cleared.

**`DayViewSession`** latches the two states (a reminder persists until dismissed) as
`driftReminderId: Instant?` / `resumeRitualId: Instant?`, exposes them on the snapshot as
booleans, and adds dismiss actions:

```kotlin
val showDriftReminder: Boolean,   // driftReminderId != null
val showResumeRitual: Boolean,    // resumeRitualId != null

fun dismissDriftReminder()
fun dismissResumeRitual()
```

Ending a focus clears both. The session holds the **instants**, not just booleans, because the
Dock bounce is deduplicated by reminder identity (below).

### Dock affordances

A `DockAttentionProvider` interface in `:core` commonMain (the `FrontmostAppProvider` pattern):

```kotlin
interface DockAttentionProvider {
    fun setBadge(visible: Boolean)
    fun bounceOnce()
}
object NoopDockAttentionProvider : DockAttentionProvider { /* no-ops */ }
```

`NSAppDockAttention` in `macosMain`:
- `setBadge(visible)` → `NSApp.dockTile.badgeLabel = if (visible) "!" else null`, then
  `dockTile.display()`. The label is **`"!"`**, matching the JVM's `MacDockBadge.BADGE_TEXT`.
- `bounceOnce()` → `NSApp.requestUserAttention(NSInformationalRequest)` — the single
  informational bounce (macOS only animates while DayView is not frontmost, which is exactly the
  drift situation), mirroring the JVM's `MacDockBouncer`.

The **session** owns the policy: it calls `setBadge(true/false)` when the latched drift state
flips, and `bounceOnce()` only when `driftReminderId` takes a *new* value — the JVM's
`lastBouncedFor` dedupe, kept in Kotlin so Swift needs no state.

### In-app surfaces (Swift, main window only)

Both are overlays on the today content, styled with the existing `dayViewPanel`:

- **Drift banner** (`showDriftReminder`): an amber-headed panel — "BACK TO THE ESSENTIAL", then
  the focus intention, or "One thing at a time." when the intention is blank (the JVM's
  `FocusNudgeCopy.body` fallback), and a "BACK AT IT" button calling `dismissDriftReminder()`.
- **Resume ritual** (`showResumeRitual`): "YOUR RESUME POINT", the intention, and
  "<clock> left to stay on track." (reusing the snapshot's `pomodoroClock`), with **Resume**
  (calls `dismissResumeRitual()`; the session continues untouched) and **Stop** (`stopFocus()`,
  which also clears the ritual). This is deliberately interruptive: it is the moment the app
  reminds you a session is still running.

  **On appearance it also restores full mode**, matching the JVM (`Main.kt` sets
  `isMiniWindowVisible = false; isWindowVisible = true` alongside the ritual): perform the
  Phase 5b exclusivity swap — `openWindow(id: "main")` + `dismissWindow(id: "mini")` — then
  `NSApplication.shared.activate(ignoringOtherApps: true)` to bring it forward (the JVM's
  `window.toFront()` + `requestFocus()`). So a ritual fired while the mini window is showing
  swaps back to the main window rather than surfacing behind it.

Copy is hardcoded English, matching the rest of the native UI.

## Data flow

```
DayViewSession.tick()  [existing 10a presence path]
  -> PresenceCoordinator.observe(...) -> Result(+ driftReminderAt, resumeRitualAt)
  -> session latches driftReminderId / resumeRitualId (resume clears drift; focus end clears both)
  -> dock.setBadge(drift != null); dock.bounceOnce() when driftReminderId is NEW
  -> snapshot.showDriftReminder / showResumeRitual
  -> RingView overlays: drift banner / resume ritual (+ window to front)
dismiss actions -> session clears the latch -> badge off
```

## Testing / done criteria

- **`:core:jvmTest`** (extending the 10a coordinator/session tests, using the clock seam and the
  fake frontmost provider):
  - four frontmost switches within 45s during an active focus → `showDriftReminder` true;
  - a sustained non-on-goal stretch past 2 minutes → true;
  - the 5-minute cooldown suppresses an immediate second reminder;
  - `dismissDriftReminder()` clears it; ending the focus clears both flags;
  - a session already active when the session is constructed → `showResumeRitual` true; a resume
    ritual clears a pending drift reminder and suppresses a drift nudge on that same tick;
  - a fake `DockAttentionProvider` records exactly one `bounceOnce()` per distinct reminder and
    `setBadge(true)`/`setBadge(false)` on the transitions.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test: start
  a Focus with an on-goal app configured; switch rapidly between other apps (or sit in a
  non-on-goal app for 2 minutes) → the Dock icon bounces once and shows a "!" badge, and the
  drift banner appears in the window; "BACK AT IT" clears both. Quit and relaunch mid-session →
  the resume ritual appears and the window comes to the front; Resume continues, Stop ends.
  Also trigger a ritual while the **mini** window is showing (relaunch in mini mode) and confirm
  it swaps back to the main window rather than appearing behind it.

## Risks

- **Dock APIs while the app is frontmost** — `requestUserAttention` is a no-op when DayView is
  already active, so the bounce is only observable when the user has genuinely drifted away.
  Testing it requires being in another app; called out in the smoke test.
- **Latch vs. detector cooldown** — the detector has its own 5-minute cooldown; the session's
  latch persists a fired reminder until dismissed. A user who never dismisses keeps the banner
  and badge up; the JVM behaves the same way (`focusDriftReminderId` stays set). No divergence,
  but worth stating.
- **Resume ritual on every wake** — `FocusResumeDetector`'s ≥15s observation-gap rule fires
  after any sleep/suspend during an active session. That is the intended JVM behaviour; the
  ritual is dismissable in one click.
- **Window-to-front from a background app** — `activate(ignoringOtherApps: true)` steals focus.
  Correct for this ritual, but it must fire only on the ritual's appearance, never on a redraw;
  the latch (a one-shot id) guarantees that.

## Known limitations

- **Menu-bar-only residency with both windows closed.** A ritual can latch while neither the
  main nor the mini window is open (e.g. the user closed both and stays in the menu bar). The
  latch persists until dismissed and is surfaced the next time a window is opened, but nothing
  proactively brings a window forward to show it in that state — the JVM sets
  `isWindowVisible = true` on the same event, which this native port does not yet replicate.
  Tracked as a parity follow-up.

## Roadmap after this phase (context only)

The `UNUserNotificationCenter` nudge banner (after signing), native presence persistence (the
10a follow-up), the focus-session detail popup, must-dos, sounds, history, day-over/upcoming,
sync, i18n, and the packaging/CI cutover.
