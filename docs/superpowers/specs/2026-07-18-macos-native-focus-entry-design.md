# macOS Native — The focus entry and the élan (Path B, Phase 12b)

## Context

The shared "asymmetric focus lifecycle" — *entering is free, finishing is a choice, fleeing costs
a name* ([design](2026-07-18-asymmetric-focus-design.md)) — landed in `:core` and Compose, and
[phase 12a](2026-07-18-macos-native-focus-exit-design.md) ported its exit half to the native macOS
app: the closure ritual, the exit toll, and the open-detour banner.

This phase ports the other half — the entry and the élan:

- **Entering is still tolled natively.** Both Start affordances are `.disabled` while the
  intention field is empty, and there is no 5-minute preset. The mandatory text field is exactly
  the toll the shared design removed.
- **Overtime still reads as a break.** `MiniView.focusCard` hardcodes `"Break · …"` for the
  merged `"BREAK", "OVERTIME"` case, and the resume ritual renders
  `"\(pomodoroClock) left to stay on track."` — where `pomodoroClock` is `"+12 min"` past the
  term, producing "+12 min left to stay on track."

## The scope discovery: a break is an entry, not a relaunch

The parity checklist itemized this phase as four display-and-affordance rows. Reading the Compose
implementation shows one of them is structural.

**Compose has no "Relaunch" button.** Whenever no session is open — idle or on break alike — it
renders the full creation panel: intention, duration, and the 5-minute preset. Its own comment
states the intent: a break lasts up to an hour, and for all of it "resuming stays a conscious
choice: one that has to be a real choice, with an intention, a duration and the 5-minute preset,
rather than a single silent relaunch on the preferred duration"
(`DayViewTodayScreen.kt`, `FocusPanel`).

The native Relaunch is precisely that rejected silent relaunch: it restarts on the preferred
duration with the persisted intention, in one tap. Phase 12a made it *more* reliable by gating it
to `BREAK` — consolidating the behaviour the shared design refuses.

**Decision (approved):** strict parity. Relaunch is removed and a break offers the entry panel.
This is behaviour, not presentation, so the checklist's "behaviour never forks" rule governs. It
also simplifies the Swift status switch rather than complicating it, since `IDLE` and `BREAK`
converge on one branch.

## Goals

- Starting a focus costs nothing: no required intention, and a one-tap 5-minute preset.
- A break offers the same real choice as idle does.
- Overtime reads as overtime, in the mini window and in the resume ritual.

## Non-Goals

- **The last-closure chip** Compose shows above the entry panel (`FocusClosureChip`). It was never
  ported and is not a regression of this phase.
- **The overtime closure reminder** (one discreet notification at 2× planned). It needs the
  `UNUserNotificationCenter` plumbing deferred in 10b until the app is signed.
- **Removing Stop from the resume ritual during overtime.** Compose drops it there because its
  closure ritual is already unfolded below; the native ritual keeps both buttons (decided).
- **Inline closure during overtime.** The modal sheet from 12a stays the closure surface.
- French localization — native is hardcoded EN, and i18n is one cross-cutting phase near the end.

## Decisions (from brainstorming)

1. **Strict parity on the break** (above): the entry panel replaces Relaunch.
2. **Overtime keeps the modal**, behind a button relabelled **"Close this focus"** — named for
   what it does, so the label carries the invitation that crossing the term is meant to be. The
   native idiom chosen in 12a holds, and the mini window has no height for an unfolded ritual.
   Rejected: auto-opening the sheet at the term, which would interrupt exactly the hyperfocus the
   design exists to protect.

## Architecture

### `:core` — the bridge only; the controller does not change

`startPomodoro(minutes = state.pomodoroMinutes)` already does everything needed: it dropped its
blank-intention guard, clamps to 5–180 minutes, and snapshots `pomodoroSessionMinutes` for the
session so a preset never rewrites the preference.

**`DayViewSession` gains one action**, symmetric with the existing `startFocus`:

```kotlin
fun quickStartFocus(intention: String) {
    controller.setFocusIntention(intention)
    controller.startPomodoro(minutes = 5)
}
```

It takes the intention for the same reason `startFocus` does: the native intention field is Swift
local state pushed through the bridge, so a draft the user has typed but not submitted would
otherwise be lost. Passing a blank string is now legitimate.

**`TodaySnapshot` gains one field:**

```kotlin
val resumeRitualLine: String,  // "" when no session is open
```

`"+N min past the term — closing stays a choice."` in `OVERTIME`, `"MM:SS left to stay on track."`
in `ACTIVE`, `""` in `BREAK`/`IDLE`. Both strings mirror Compose's `focus_resume_overtime` and
`focus_resume_time_left`, and both reuse the existing `formatOvertimeLabel` / `formatPomodoroClock`
helpers.

This is the fix for the ritual's wrong copy *and* its cause: `RingView` composes the sentence in
Swift from `pomodoroClock`, which is exactly what the Kotlin-computed-label convention forbids.
A label assembled per view cannot know that `pomodoroClock` changes meaning past the term.

### Swift — free entry

- `RingView.focusSection`: drop `.disabled(intention.isEmpty)` from Start, and add a secondary
  **"5 min"** button beside it calling `quickStartFocus`.
- `MiniView.intentionSheet`: drop `.disabled(draftIntention.isEmpty)` from Start, and add the same
  **"5 min"** button.

### Swift — the break becomes an entry

`IDLE` and `BREAK` render the same thing, so the two status switches collapse a branch each:

- `RingView.focusSection`: the `"BREAK"` case disappears; `"BREAK"` falls to the entry branch with
  `"IDLE"`. The duration stepper, today `.disabled(status != "IDLE")`, must also accept `"BREAK"` —
  no session is running, so nothing is being changed underneath.
- `MiniView.focusCard`: `"BREAK"` falls to the entry branch, which opens the intention sheet.

**A consequence to handle, not a separate concern.** `RingView.onReceive` re-syncs the intention
field from persisted state on the `non-IDLE → IDLE` transition — that is what clears it after
`Completed` and keeps it after `Resume later`. A closure now lands in `BREAK`, not `IDLE`, so that
transition would no longer fire and the field would keep the closed session's text. The condition
becomes "a session just ended": the previous status was `ACTIVE` or `OVERTIME` and the current one
is not.

### Swift — the élan reads as the élan

- `RingView.focusSection`: `"OVERTIME"` takes its own branch, offering one button, **"Close this
  focus"**, presenting the 12a sheet.
- `MiniView.focusCard`: the status row for `ACTIVE` and `OVERTIME` renders `snapshot.focusLine`
  instead of composing `"Focus · …"` / `"Break · …"` by hand. `focusLine` is already computed in
  Kotlin and already correct for overtime (`"Focus · <intention> · +12 min"`), so the hardcoded
  break text disappears by construction rather than by being edited.
- `RingView.resumeRitual`: renders `snapshot.resumeRitualLine`.

## Data flow

```
IDLE or BREAK  -> entry panel: intention (optional), duration, Start, "5 min"
                   Start   -> startFocus(intention)       -> preferred duration
                   "5 min" -> quickStartFocus(intention)  -> 5 min, preference untouched
ACTIVE         -> "Stop focus"        -> 12a closure sheet (toll applies)
OVERTIME       -> "Close this focus"  -> 12a closure sheet (toll lifted; free)
closure        -> BREAK -> entry panel again; the intention field re-syncs on the
                  ACTIVE/OVERTIME -> other transition
```

## Testing / done criteria

- **`:core:jvmTest`** (extending `DayViewSessionTest` and `TodaySnapshotTest`):
  - `quickStartFocus` starts a five-minute session and leaves `pomodoroMinutes` unchanged;
  - `quickStartFocus` applies the intention it is given, including a blank one;
  - `startFocus` with a blank intention starts a session (free entry — this is the guard whose
    removal the native UI is finally honouring);
  - `resumeRitualLine` reads `"+N min past the term — closing stays a choice."` in overtime,
    `"MM:SS left to stay on track."` while active, and `""` on a break and when idle.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test, the
  only end-to-end check of the Swift half:
  1. With the intention field empty, **Start focus** works.
  2. **5 min** starts a five-minute session; afterwards the duration stepper still shows the
     preferred duration, not 5.
  3. Let a session pass its term → the panel shows `+N min` and a **Close this focus** button; the
     mini window's row reads `Focus · … · +N min`, not `Break · …`.
  4. Close the session → the panel offers the full entry — intention, duration, Start, 5 min — not
     a Relaunch button, and the intention field reflects the outcome (cleared by Completed, kept by
     Resume later).
  5. Quit and relaunch mid-session past the term → the resume ritual reads
     `+N min past the term — closing stays a choice.`

## Roadmap after this phase (context only)

Per the parity checklist: the drift notification banner and menu-bar-only ritual surfacing (both
10b follow-ups, awaiting signing), the focus-session detail popup, must-dos, hero quotes, keyboard
shortcuts, sounds, history archiving, day-over/upcoming, sync, i18n, and the packaging/CI cutover.
