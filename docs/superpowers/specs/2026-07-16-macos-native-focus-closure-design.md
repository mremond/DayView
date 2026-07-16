# macOS Native — Focus-closure ritual (Path B, Phase 5a)

## Context

The native SwiftUI macOS app (Path B; phases 1–4 merged) can start and stop a Focus, but
it has no **closure ritual**. On the Compose/JVM app, a finished Focus is closed with one of
three named outcomes — **Completed**, **Progressed**, or **Resume later** — which records the
session and keeps the clean-session ledger honest; "Resume later" retains the intention for the
next session, the other two clear it. The native app currently offers only a silent **Stop**
(an early abort that records no outcome).

Phase 5a brings that ritual to the native app: the shared `:core` session bridge exposes it,
and the main window (`RingView`) uses it during the break. It is deliberately split out from
the mini window (Phase 5b): the closure ritual is a **focus** capability, not a mini-window
one, and once it lives in `:core` both the main window and the future mini window use the same
path. Doing it first keeps the two windows consistent and keeps the mini-window increment
Swift-only.

## Goals

- Expose the existing controller closure (`DayViewController.closePomodoro(outcome)`) on the
  native `DayViewSession` bridge and on `TodayModel`.
- Give the native main window (`RingView`) the three-outcome closure ritual during the break,
  mirroring the JVM lifecycle and semantics.
- Keep the intention text field consistent with the persisted state after a closure (cleared
  on Completed/Progressed, retained on Resume later).

## Non-Goals (deferred)

- The mini always-on-top window (Phase 5b) — it will inherit this ritual.
- Foreground-presence / `sessionOffGoal` tracking (its own later phase).
- The focus **resume ritual** (the "still-active session found on relaunch" prompt) — a
  separate deferred item, distinct from the closure ritual.
- Localization of the new button labels beyond the native app's current hardcoded-English
  state (the native macOS UI has no i18n yet; French/other locales are a later cross-cutting
  phase).
- Any change to the mini window, Android, or the Compose/JVM app.

## Decisions (from brainstorming)

1. **Split from the mini window.** Phase 5a is the closure ritual (`:core` + `RingView`);
   Phase 5b is the mini window and inherits it.
2. **Bridge API: string outcome, symmetric.** `closeFocus(outcome: String)` taking
   `"COMPLETED"` / `"PROGRESSED"` / `"TO_RESUME"`, mapped defensively in Kotlin (unknown →
   `COMPLETED`). Symmetric with the snapshot's existing string convention (`pomodoroStatus`
   is already `"ACTIVE"`/`"BREAK"`).
3. **Closure appears during BREAK only.** During ACTIVE, Stop stays an outcome-less early
   abort (unchanged). This matches the JVM main window.

## Architecture

### Focus lifecycle (native, after this phase)

`RingView`'s focus section moves from two states to three, mirroring the JVM:

- **IDLE** → duration `Stepper` + **Start focus** (unchanged).
- **ACTIVE** → intention + live clock + **Stop** (early abort → `stopFocus`, no outcome
  recorded; unchanged behaviour).
- **BREAK** (`pomodoroStatus == "BREAK"`) → intention + break clock + a **"»" relaunch**
  (start the next session, keeping the current intention → `startFocus(intention:)`) +
  **Stop**, and below them the **closure ritual**: three buttons —
  **Completed** / **Progressed** / **Resume later** — each calling `closeFocus(outcome:)`.

The controller already implements `closePomodoro(outcome)` fully (records the session via
`recordClosingSession`, updates the clean-session ledger via `closedFocusLedger`, and keeps
the intention only when `outcome.keepsIntention`). This phase wires UI → bridge → that method;
no controller logic changes.

### `:core` bridge

Add to `DayViewSession` (`core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt`):

```kotlin
fun closeFocus(outcome: String) {
    controller.closePomodoro(
        when (outcome) {
            "PROGRESSED" -> FocusClosureOutcome.PROGRESSED
            "TO_RESUME" -> FocusClosureOutcome.TO_RESUME
            else -> FocusClosureOutcome.COMPLETED
        },
    )
}
```

The defensive `else -> COMPLETED` means a mistyped string degrades to the
intention-clearing "Completed" outcome rather than throwing across the FFI boundary.

Mirror it on `TodayModel` (`macos/DayView/TodayModel.swift`):

```swift
func closeFocus(_ outcome: String) { session.closeFocus(outcome: outcome) }
```

No new `TodaySnapshot` field is needed: the UI keys the closure buttons off the existing
`pomodoroStatus == "BREAK"`, and relaunch reuses the existing `focusIntention` field.

### Native `RingView` UI

The break branch of the focus section renders the three outcome buttons in JVM order and
colour intent: **Completed** (accent/green), **Progressed** (amber), **Resume later** (muted),
alongside the existing Stop and a new "»" relaunch affordance. Exact SwiftUI control choices
(button styles, layout) are the plan's concern; the requirement is the three outcomes, the
relaunch, and Stop are all reachable during the break and call the right bridge methods.

### Intention re-sync (correctness detail)

`RingView` currently seeds its local `intention` `@State` **once** (guarded by `seeded`), so
after a `Completed`/`Progressed` closure clears the intention in the snapshot, the text field
would still show the stale text. The implementation must re-sync the `intention` field to the
snapshot when a focus closes (ACTIVE/BREAK → IDLE transition): the snapshot is the source of
truth, so the field shows cleared text after Completed/Progressed and the retained text after
Resume later. (The seed-once guard exists to avoid clobbering mid-typing; re-syncing on the
close transition is a distinct, additive trigger.)

## Data flow

```
RingView break state
  Completed/Progressed/Resume later -> TodayModel.closeFocus(outcome)
    -> DayViewSession.closeFocus(String) -> controller.closePomodoro(FocusClosureOutcome)
    -> controller.stateFlow emits new state (pomodoroEnd=null, intention kept/cleared)
    -> DayViewSession subscription -> TodayModel.snapshot updates
    -> RingView re-renders (focus section returns to IDLE; intention field re-synced)
  "»" relaunch -> TodayModel.startFocus(intention: snapshot.focusIntention)
  Stop -> TodayModel.stopFocus()   (unchanged early abort)
```

## Known limitation (documented, not a regression)

The native controller has no foreground-presence tracking yet, so `sessionOffGoal` is always
`Duration.ZERO`. `closePomodoro` therefore evaluates the clean-session ledger with no off-goal
time for native sessions until presence tracking lands (a later phase). This reflects the
native app's current capabilities and does not change any existing native behaviour.

## Testing / done criteria

- **`:core` unit test** (`core/src/commonTest/.../DayViewSessionTest.kt`, runs under
  `:core:jvmTest`, following the existing pattern there — a `DayViewController` with an
  injected `initialNow`, a `DayViewSession` wrapping it, `runCurrent()` between steps). The
  bridge's `closeFocus` delegates to the already-tested `closePomodoro`, so this test pins the
  bridge's string→outcome mapping and the intention behaviour, not the ledger internals:
  - start a focus (`session.startFocus("Ship it")`), optionally advance with
    `controller.tick(...)`, then `session.closeFocus("COMPLETED")` → the latest emitted
    snapshot has `pomodoroStatus == "IDLE"` and `focusIntention == ""`;
  - a fresh session + `session.closeFocus("TO_RESUME")` → `pomodoroStatus == "IDLE"` and
    `focusIntention == "Ship it"` (retained).
- **Native build/launch:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **` and the
  app launches; the focus section shows Start when idle and, during a break, the three
  outcome buttons. As in earlier phases, headless SwiftUI/AppKit GUI driving is blocked by the
  sandbox's Accessibility/Screen-Recording permissions — the actual button clicks are a
  **manual smoke test**, called out explicitly. What can be observed automatically: the build
  succeeds and the app launches; what needs a human: clicking an outcome ends the session and
  the intention clears (Completed/Progressed) or is retained (Resume later).

## Risks

- **Intention re-sync** — the seed-once guard must not swallow the post-closure re-sync;
  getting the trigger wrong leaves a stale intention in the field. Covered by the re-sync
  requirement above and a manual smoke check.
- **Stringly-typed bridge** — a typo'd outcome string silently becomes "Completed". Mitigated
  by the small, fixed set of call sites (three buttons) and the defensive default; the `:core`
  test pins the two intention-affecting outcomes.

## Roadmap after this phase (context only)

Phase 5b: the mini always-on-top window (compact ring + goal + focus, inheriting this closure
ritual). Then foreground-presence / net-time arcs (native EventKit), settings, the focus
resume ritual, packaging/CI cutover, and the macOS Widget.
