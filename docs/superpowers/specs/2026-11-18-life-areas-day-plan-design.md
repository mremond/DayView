# Life areas and a clock-fixed day plan (native macOS) — design

Date: 2026-11-18
Status: draft for review

## Intent

The user regularly drifts: they start the day meaning to do one thing, end up working (or on
something else), and lose track of time. DayView today shows the time left in the day, one
long-term goal (Cap), and a manually started Focus timer. That is not enough to pull them back.

This change makes the day a **plan of clock-fixed blocks**, each belonging to a **life area**
with its own main goal, and **speaks** at block boundaries so the user always hears where they
should be right now. It is the first feature built on the new Swift-native direction:
DayView is moving to a fully native Swift app for macOS and iOS; Android and the Kotlin code
will be dropped in stages. New concepts are written in Swift from the start.

## Success, and what failure looks like

- Success: when the user drifts, the next cue brings them back to the planned block; at the
  end of a week they can see how their planned time per area was actually spent.
- **Anti-goals (failure even if it works):** the user starts **ignoring the cues**, or
  **stops using the plan**. Every decision below favours sparse, always-meaningful cues and a
  morning check short enough to keep doing. DayView also measures both failure signals and
  shows them to the user (see *Follow-through signals*) rather than escalating.

## Goals

1. Several **life areas**, each with a name, a colour, a main goal (title + optional
   deadline, same semantics as today's Cap) and its own on-goal apps. Initial areas proposed
   by the user: Personal life, Writing, Work, Health.
2. A **weekly template**: per weekday, a list of clock-fixed blocks (start, end, area,
   optional intention).
3. A **morning check** that lays today's template around today's calendar meetings, shows
   the result, and lets the user adjust and accept it in one step.
4. **A block is a Focus.** When a block starts, a Focus for that block's area starts by
   itself and ends at the block's end. The user can also **start a Focus manually at any
   time** (gaps, or days without a plan).
5. **Spoken cues** at block boundaries, in the system language, using built-in voices.
6. **Per-area drift detection** using each area's on-goal apps.
7. Record Focus time per area and the follow-through signals; show a simple weekly summary.

## Non-goals (this project)

- The iOS app (but every logic decision must work on iOS — see *Constraints*).
- CloudKit sync (storage is designed so it can be switched on later as configuration).
- Porting detours, net time, history, must-dos, sync or settings away from Kotlin. Must-dos
  and Detours are unchanged.
- Any change to the Compose/JVM desktop app or the Android app. They keep working as they are.
- Drag-to-edit blocks directly on the ring.

## Constraints

- **Lives in the existing native app** (`macos/`, SwiftUI, macOS 15+, XcodeGen
  `project.yml`). The app keeps using `DayViewKit` (Kotlin/Native XCFramework) for everything
  not yet ported: day window, net time, calendar busy lane, settings.
- **iOS-ready logic.** On iOS an app cannot run timers or speak in the background. Therefore
  the cue schedule must be **computed in advance** from the accepted plan as a list of
  `(instant, cue)` entries, so that on iOS the same list becomes scheduled local notifications
  with spoken lines pre-rendered to audio files (`AVSpeechSynthesizer.write`). The macOS
  implementation consumes the same list with an in-process timer.
- **CloudKit-compatible SwiftData models**: every stored property optional or defaulted, no
  `@Attribute(.unique)`, relationships optional. Store is local-only for now.
- English and French UI and cue strings (String Catalog). Cues follow the system language.
- No reference to Claude/AI in code or commits; ktlint/test rules in CLAUDE.md still apply to
  any Kotlin touched.

## Approaches considered

- **A. A Swift package `DayPlanKit` next to the app (chosen).** Pure, testable logic
  (areas, template, fitting around meetings, overlap rule, cue schedule, drift
  classification) in a package with no UI; the app supplies storage, speech, calendar input
  and views. Reused unchanged by the future iOS app. Chosen by the user.
- B. Code directly in the app target. Faster to start but mixes the error-prone logic
  (fitting, overlaps, cue timing) with SwiftUI, hard to test, and would need extracting for
  iOS. Rejected.
- C. Put the new logic in the Kotlin core. Contradicts the move to Swift. Rejected.

Storage sub-choice: **SwiftData, local store now, CloudKit later** (chosen) over JSON files
(simpler now, migration later).

## Product behaviour

### Life areas

- Fields: name, colour (index into a fixed palette of ≥ 8 colours that read in light and
  dark), goal title, goal deadline (optional), on-goal apps (bundle id + display name), order.
- Seeded on first launch with the user's four areas: Personal life, Writing, Work, Health
  (French: Vie personnelle, Écriture, Travail, Santé), empty goals.
- **Cap migration:** if the Kotlin session has a non-empty Cap (`snapshot.goalTitle` /
  deadline), the first time areas are shown the app asks once "Which area does your current
  goal belong to?" and copies title + deadline there. From then on the native app reads and
  writes goals only through areas; it stops calling `setGoalTitle`/`setGoalDeadline`. The
  existing global on-goal app list (Kotlin `OnGoalApps`) is copied into that same area
  (builder's call), so drift detection works from day one; other areas start empty.
- **The active area** is the area of the running Focus (planned or manual). When no Focus
  runs, no area is active and the views show the day ring as today, without a goal line.
- Everywhere the Cap shows today (main window, mini window, menu bar), show the **active
  area's** name, colour and goal instead.
- Areas are edited in a new **Areas** settings tab (add, rename, recolour, reorder, delete,
  set goal, pick on-goal apps from installed apps, excluding DayView). Deleting an area that
  has template blocks asks for confirmation and deletes those blocks; past records keep the
  area's name and colour as a snapshot.

### Weekly template

- A **Week** settings tab: one column/list per weekday (Monday…Sunday). Each block: start,
  end (5-minute steps), area, optional intention. Blocks of one weekday may not overlap
  (validation error inline). Blocks must lie within the 24-hour day; they are *not* clipped to
  the DayView day window (the user may plan an evening Health block after the day end).
- "Copy this day to…" duplicates a weekday's blocks to chosen weekdays.
- Editing the template affects **only days not yet accepted** (today's accepted plan is
  untouched).

### Morning check

- Shown in the main window (as a sheet over the ring) the first time the window is shown on a
  day whose plan is not accepted yet, and on demand from the menu bar ("Plan today…").
- Content: today's template **fitted around today's meetings** (see *Fitting rules*), each
  adjusted block marked (e.g. "split around 10:00–10:30 meeting"), a one-line follow-through
  summary for the last 7 days, and two actions: **Adjust** (edit today's blocks inline:
  times, area, intention, add, delete — same editor as the template, today only) and
  **Start my day** (accept).
- **Skipping:** if the user has not accepted by the first block start, the fitted template is
  accepted automatically at that moment (so cues never silently disappear), and the day counts
  as *plan not reviewed* in the follow-through signals.
- A weekday with no template blocks produces an empty plan; the morning check then just says
  "No plan for today" with Adjust available. Manual Focus works as usual.
- After acceptance the user can still edit today's remaining blocks from "Plan today…"; the
  cue schedule is recomputed.
- **Calendar changes after acceptance (builder's call):** the accepted plan is stored
  *unfitted* (the user's accepted blocks, including today-only edits); fitting is applied
  live against the current meetings on every calendar refresh, and the cue schedule is
  recomputed. A meeting added, moved or cancelled later therefore reshapes the remaining day
  automatically, with the same runtime behaviour as any meeting (see *Meetings inside a
  block*). No new prompt is shown.

### Fitting rules (template → today's plan)

Input: template blocks for today's weekday, meeting intervals for today. Meetings are busy
intervals filtered exactly as the existing net-time code does (included calendars, declined
and tentative hidden, travel time added, events titled "focus" excluded as reserved goal
time — `BusyInterval.isFocusBlock()`), but queried over the **whole local day (00:00–24:00)**
and **independently of the net-time toggle**, so blocks outside the DayView day window are
fitted too. Only calendar access is required; without it there are no meetings and the
template is used as is. (Builder's calls.)

- A block overlapping one or more meetings is **cut around them**: the parts before, between
  and after meetings are kept as separate blocks of the same area and intention.
- A resulting part shorter than **10 minutes** is dropped.
- Blocks are never moved later or merged; fitting only removes time.
- Every block that was cut or dropped carries a note for the morning check.

### Focus: planned and manual

There is one timer concept, the Focus, now owned by Swift (`DayPlanKit`), not by the Kotlin
session. The native app stops calling `session.startFocus/stopFocus/closeFocus/
changePomodoroDuration`; the Kotlin Focus stays idle in the native app.

- **Planned Focus:** at a block's start the Focus starts with the block's area, intention and
  end time. It ends at the block's end with the existing closing ritual
  (`FocusClosureButtons`: completed / progressed / to resume). If the next block starts
  immediately, the ritual is offered non-blockingly while the next Focus already runs.
- **Manual Focus:** started from the main window/menu bar at any time: pick area (defaults to
  the last used), duration (as today, 5–180 min) and optional intention. Works like today's
  Focus, including the break state after it ends.
- **Overlap rule — the plan wins:** if a manual Focus is running when a planned block starts,
  the block's start cue says so ("14:00. Health. Your Writing focus ends here."), the manual
  Focus closes (closing ritual offered) and the planned Focus starts.
- **Meetings inside a block (builder's call):** a block cut by meetings stays **one
  Focus** that **pauses** for each meeting: no end cue or closing ritual before the meeting,
  no Focus time counted during it. At the meeting's end a **resume cue** is spoken
  ("Back to Writing until 12:00.") — the end of a meeting is a prime drift moment. One Focus
  record per block, with the meeting time excluded.
- **Keep going:** while a planned Focus is in its last 2 minutes, a **Keep going** action is
  shown (main window, mini window, menu bar). It extends the current Focus by **15 minutes**
  and shifts **all remaining blocks of today** 15 minutes later (re-fitting them around
  meetings with the same rules). It may be used repeatedly. Blocks pushed past midnight are
  dropped.
- **Keep going after a takeover (builder's call):** within 2 minutes after a plan-wins
  takeover, Keep going **reopens the interrupted manual Focus** for 15 minutes (its pending
  closing ritual is withdrawn), cancels the just-started planned Focus, and shifts the
  remaining plan — including the interrupted block — 15 minutes later.
- **Stop:** the user can stop any Focus early, as today. Stopping a planned Focus does not
  change the plan; the next block still starts on time.

### Spoken cues

Computed from today's accepted plan as an ordered list; recomputed whenever the plan changes.

| Cue | When | Example (en) |
|---|---|---|
| Block start | at a block's start | "9:00. Writing. Chapter 3." (area, then intention if any) |
| Block ending | 2 min before a block's end | "Writing ends in 2 minutes. Next: Health." / "…Next: free time." |
| Takeover | block start while a manual Focus runs | "14:00. Health. Your Writing focus ends here." |
| Resume | end of a meeting inside a block | "Back to Writing until 12:00." |
| Unplanned | start of a gap ≥ 15 min, once | "Unplanned time until 13:00." |

Definitions (builder's calls):
- A **gap** is time *between two blocks* that is neither block nor meeting; its length is
  counted without meeting time. No unplanned cue before the first block or after the last.
- The **"Next:"** part of the ending cue names the next block with its time if it starts
  within 15 minutes and no meeting comes first ("Next: Health at 13:00"); "Next: a meeting"
  if a meeting comes first; "Next: free time" if a gap follows; after the last block of the
  day: "That's the end of today's plan."
- An ending cue is not spoken when the block's end is a pause for a meeting (see *Meetings
  inside a block*).

Rules:
- No cue is ever repeated for the same event; there is no escalation.
- **Meetings:** a cue falling inside a meeting is held and spoken at the meeting's end only if
  it is still relevant (the block it announces is still running); otherwise dropped.
- **Lateness:** a cue more than 90 s late (sleep, app not running) is dropped, except that on
  wake/launch, if the current block started while DayView was not observing, its start cue
  is spoken once.
- No cues for manual Focus beyond today's end-of-Focus sound behaviour.
- Speech: `AVSpeechSynthesizer` with the system default voice for the current language;
  settings: cues on/off, volume, "Use a chime instead of voice". Default on.
- Times are spoken in the user's clock format (12/24 h, from the system).

### Per-area drift detection

- During a Focus, the frontmost app (NSWorkspace) is classified with the active area's
  on-goal apps: on-goal / off-goal / neutral (DayView itself and an empty app list are
  neutral), same semantics as today's Kotlin `OnGoalState`.
- Off-goal continuously for **3 minutes** → one drift notification ("You planned Writing")
  plus one Dock bounce (as the Compose app does). At most one drift reminder per 10 minutes.
  No spoken drift cue (voice is reserved for plan boundaries, to keep it meaningful).

### Follow-through signals and weekly summary

Recorded per day:
- **Plan reviewed:** accepted via Start my day (vs. auto-accepted or no plan).
- **Ignored cue:** a block-start or takeover cue after which the frontmost app stayed off-goal
  for the area for 5 minutes. Only measurable when the area has on-goal apps.
- **Focus records:** start, end, area (id + name/colour snapshot), intention, planned or
  manual, closure outcome.

Shown:
- In the morning check: "Last 7 days: plan reviewed 5/7 days · 3 cues ignored".
- A **This week** window (menu bar → "This week"): per area, planned time vs. Focus time,
  Monday to today; plus the two signals per day. Plain list, no charts.

### Ring and views

- Main ring: today's plan drawn as area-coloured segments on the existing ring (a band
  distinct from the calendar busy lane), the running block highlighted. Hover shows
  "Writing · 09:00–12:00 · Chapter 3".
- Main window, mini window and menu bar title: when a Focus runs, show area name (in its
  colour), intention, Focus clock, and the area goal; the menu bar title shows the Focus
  clock as today.

## Architecture

```
macos/Packages/DayPlanKit/          (new SwiftPM package, macOS 15 / iOS 18)
  Sources/DayPlanKit/               pure value types and functions, no UI, no SwiftData
    Area, TemplateBlock, PlannedBlock, DayPlan, FocusState, Cue, ...
    PlanFitter          template + meetings -> plan + notes
    FocusEngine         state machine: planned/manual Focus, overlap rule, keep going
    CueScheduler        plan (+ meetings) -> [ScheduledCue]; observe(now) -> due cues
    DriftClassifier     frontmost bundle id + area -> on/off/neutral; drift timer
    FollowThrough       signals and weekly summary computation
    CueText             localized cue sentences
  Sources/DayPlanStore/             SwiftData models + mapping to DayPlanKit types
  Tests/DayPlanKitTests/            Swift Testing
macos/DayView/                      app: wiring, views, speech, NSWorkspace, notifications
```

- All time logic takes `now` and a `Calendar`/`TimeZone` as parameters (testable, DST-safe).
- The app drives `FocusEngine` and `CueScheduler` from the existing 1-second timer in
  `TodayModel` (or a sibling `PlanModel` observable owning the Swift state).
- **Meeting input:** a small Kotlin addition exposes today's busy intervals (start/end epoch
  millis, after the existing filtering and travel time, focus-titled events excluded,
  queried over the whole local day regardless of the net-time toggle) on `TodaySnapshot`; the app maps them
  to `DateInterval`s via a `MeetingSource` protocol so a direct EventKit implementation can
  replace it when net time is ported.
- `project.yml` gains the `DayPlanKit` package dependency for the app target.

## Delivery stages

Each stage is independently mergeable and leaves the app working.

1. `DayPlanKit` domain + `PlanFitter` + `CueScheduler` + `FocusEngine` with tests; busy
   intervals exported on `TodaySnapshot`.
2. SwiftData store; Areas and Week settings tabs; Cap migration.
3. Morning check; plan band on the ring.
4. Swift-owned Focus (planned + manual, overlap, keep going, closing ritual) and spoken cues;
   native app stops using the Kotlin Focus.
5. Per-area drift detection; follow-through signals; This week window.

## Decided by the user

- Drop Android and Kotlin eventually; migrate to Swift in stages; new concepts in Swift.
- Timers are fixed to the clock.
- Plan source: weekly template + morning check around meetings.
- Life areas each have a main goal; examples Personal life, Writing, Work, Health.
- A block is the Focus; the user can also start a Focus whenever they want.
- Failure = ignoring the cues or no longer using the plan.
- Approach A (Swift package) with SwiftData.
- During the transition the user keeps using the Kotlin (Compose) app daily. The native
  app's Swift Focus does **not** write into Kotlin history or sync; that gap is accepted and
  no Kotlin bridge is built for it.

## My calls (open to correction)

- Plan wins over a manual Focus; Keep going = +15 min and shift the rest.
- Cues: start + 2-min-before-end + takeover + one unplanned-gap cue; held during meetings;
  no repeats; voice only for plan boundaries.
- Fitting only cuts blocks around meetings, drops parts < 10 min, never moves blocks.
- Auto-accept at the first block start if the morning check was skipped.
- Drift: 3 min off-goal → notification + Dock bounce, max one per 10 min.
- Seeding the four areas; one-time Cap-to-area question; global on-goal apps copied there.
- Accepted plan re-fitted live when the calendar changes.
- Meetings queried over the whole day, independent of the net-time toggle; "focus" events
  are not meetings.
- A block split by a meeting is one Focus that pauses, with a "Back to…" cue after the
  meeting.
- Gap and "Next:" wording rules; Keep going after a takeover reopens the manual Focus.

## Left to the builder

- Exact SwiftUI layout of the morning check, settings tabs and This week window, within the
  existing visual identity (`DayViewPalette`).
- The area colour palette values.
- How the plan band sits relative to the busy lane on the ring.
- Internal naming and file split inside `DayPlanKit`.
