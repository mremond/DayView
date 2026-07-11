# Focus-drift tightening & intense-focus arcs — on-goal app allowlist (desktop macOS)

## Context

DayView's focus-drift nudge (`FocusDriftDetector`, `desktopMain`) currently detects only
**switch churn**: it fires when the user changes frontmost app ≥ 4 times within a 45 s
window (after an initial grace, with a 5 min cooldown). See
[`FocusDriftDetector.kt`](../../../composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt)
and the nudge output design in
`docs/superpowers/specs/2026-07-11-distraction-nudge-notification-design.md`.

Two gaps follow from churn-only detection:

- It **misses calm, sustained distraction.** Sitting in one distracting app for 30 minutes
  produces no switches, so no nudge. The most common form of drift is invisible to it.
- It has **no notion of which app you should be in**, so it cannot say "you're not using
  the app you should".

## Goal

Give a goal a set of **on-goal apps**. During a focus session, the frontmost app being in
that set is positive presence; being outside it (sustained) is drift. The same on-goal
signal has two consumers:

- **Negative** — sustained off-goal drives the drift nudge (beside the existing *churn*
  signal; both feed the same, unchanged nudge).
- **Positive** — runs of on-goal presence are accumulated into **intense-focus intervals**
  and drawn as arcs on the day ring, with a "Focused today" total.

The "heartbeat" is DayView's own 1 s frontmost sample: a *positive heartbeat* is simply an
on-goal app being frontmost. This is entirely DayView observing the OS frontmost app — it
needs no app cooperation, no new transport, and no file format.

## Scope

- **macOS desktop only.** Frontmost observation — and therefore the nudge and the
  intense-focus arcs — exist only there. Android and common code are unchanged except for
  the shared preference fields (app lists and presence intervals). The arc-rendering and
  "Focused today" readout are gated to macOS.
- **Multi-goal management is out of scope.** DayView has a single global goal today. This
  spec makes storage and detection *goal-keyed* so several goals can each carry their own
  list later, but creating / switching / deleting goals is not built here.
- Not the heartbeat / usage-contribution mechanism — that is a separate spec (see
  Relationship to usage contribution).

## Design

### 1. Data model — durable, per goal

- Each goal owns its own on-goal app set: `onGoalApps: Set<AppRef>` where
  `AppRef = { bundleId, displayName }`. `bundleId` is the identity used for matching (the
  same key `FocusDriftDetector` already reads via `MacFrontmostApplicationProvider`);
  `displayName` is captured for the settings list.
- Persisted as a keyed map `Map<GoalId, Set<AppRef>>` in `DayPreferences` — one entry
  today, more as goals are added, with **no storage-shape migration** later.
- **Keyed by a stable goal id, not the title.** The current global goal gains a stable,
  persisted `id` now, so renaming the goal keeps its list and the association stays
  unambiguous once there are several goals.
- **Durable configuration, not session state.** The list is written on every edit and
  loaded at startup into `DayViewUiState`. It is reused by every future focus session and
  survives app restarts. It must not be implemented as a per-session or in-memory list.
- The detector uses the on-goal set for the **currently active goal** (today, the single
  global goal).

### 2. Shared classification

Each 1 s tick during a focus session, the frontmost bundle id is classified against the
active goal's on-goal set into one of three states, shared by both consumers below:

- **on-goal** — bundle id is in the set,
- **off-goal** — non-blank, not DayView's own bundle, not in the set,
- **neutral** — DayView's own bundle or blank (glancing at DayView to check the time is
  neither drift nor focus).

This classification is factored into a small pure function so the drift detector (§3) and
the presence accumulator (§4) consume the same result rather than each re-deriving it.

### 3. Detection — add sustained-off-goal beside churn

`FocusDriftDetector.observe(...)` gains the active goal's on-goal set. A new
**sustained-off-goal** rule runs alongside the existing churn rule, driven by the shared
classification:

- Track `offGoalSinceMillis`:
  - **on-goal** → reset the off-goal timer,
  - **off-goal** → start / advance the timer,
  - **neutral** → leave the timer unchanged (does not excuse a drift already underway, nor
    start one).
- Fire when off-goal duration ≥ **T (default 2 min)**, respecting the existing
  `initialGraceMillis` (30 s) and `reminderCooldownMillis` (5 min).
- **Empty allowlist → the sustained-off-goal rule is skipped entirely**; only churn runs
  (today's behavior). This keeps the allowlist opt-in without a separate mode.
- The **churn rule is unchanged** and fires independently. Either rule returning `true`
  produces the existing notify + "REVENIR À L'ESSENTIEL" card — the output side is
  untouched.

### 4. Presence accumulator — intense-focus intervals

A new pure accumulator builds today's `List<FocusPresenceInterval>` from the shared
classification, each tick during a focus session:

- **on-goal** → extend the current interval to now.
- **off-goal / neutral** → a gap. Brief gaps shorter than the **bridge threshold
  (~30 s)** are tolerated (the interval continues, so glancing at a notification does not
  shatter an arc); a gap ≥ the threshold **closes** the current interval.
- On close, an interval shorter than the **minimum length (~2 min)** is discarded (a
  10 s dip into an on-goal app is not "intense focus").

Because bridging enforces high on-goal occupancy, each surviving interval **is** an
intense-focus period by construction — that is what "good heartbeat frequency" means when
the heartbeat is a uniform 1 s frontmost sample.

**Persistence.** Today's accumulated intervals are durable (`DayPreferences`, keyed by
date). On startup they are reloaded if the stored date is still today, and reset on day
change — so arcs survive an app restart, consistent with the durability rule in §1. The
in-progress interval is persisted (or safely reconstructed) so a mid-session restart does
not drop the current stretch.

### 5. Rendering — intense-focus arcs + "Focused today"

- The intervals project to arcs on the day ring via the **same math as `busyArcs`**
  (`CalendarNetTime.kt`), drawn with a **positive accent**, visually distinct from the gray
  calendar-busy arcs, layered on the black day circle.
- **Graded shading by occupancy is optional / deferred**; v1 draws a solid accent arc per
  interval.
- Summing the interval durations yields a **"Focused today"** readout (via
  `formatDurationHm`), delivering focus accounting from OS presence — no app contribution.
- Busy arcs (calendar) and focus arcs (on-goal presence) are independent layers and may
  both show; they must remain visually distinguishable.

### 6. Enumerating apps — `MacRunningApplicationsProvider` (new, desktopMain)

- Reads `NSWorkspace.sharedWorkspace.runningApplications`, filters to regular foreground
  apps (activation policy = regular, so daemons / agents do not clutter the picker),
  returns `[{ bundleId, localizedName }]`.
- Reuses the `ObjectiveCRuntime` JNA pattern already in `FocusDriftDetector.kt`, extended
  with the array-iteration / property selectors it needs. macOS-guarded; no-op elsewhere.

### 7. Settings UI

- A section under the goal lists the selected on-goal apps (display name) with a remove
  control, and an **"Add apps"** action opening a picker of currently-running apps
  (multi-select by name).
- **App icons are optional / deferred** to keep native scope down; names are cheap and
  sufficient for v1.
- The section is macOS-only (hidden / disabled on Android, where detection does not exist).

### 8. Wiring

- Each tick during a focus session, `Main.kt` reads the frontmost bundle id (as today) and
  the active goal's on-goal set, computes the shared classification (§2), and feeds it to
  both the drift detector (§3) and the presence accumulator (§4).
- No change to the nudge output path (notification + card) defined in the distraction-nudge
  spec.
- The presence intervals flow into `DayViewUiState` for rendering (§5), the same way busy
  arcs already do.

## Data flow

```
focus session active (Pomodoro running)
  └─ each 1 s tick in Main.kt:
       frontmostBundleId  ← MacFrontmostApplicationProvider
       onGoalApps         ← DayViewUiState (active goal, from DayPreferences)
       classification     ← classify(frontmostBundleId, onGoalApps)   (§2: on/off/neutral)
         ├─ FocusDriftDetector.observe(..., classification)
         │    ├─ churn rule (unchanged)                → true?
         │    └─ sustained-off-goal rule (new, T=2min) → true?
         │         └─ either true → notify + "REVENIR À L'ESSENTIEL" card
         └─ PresenceAccumulator.observe(now, classification)
              └─ on-goal runs (bridge ~30s, min ~2min) → today's FocusPresenceIntervals
                   ├─ persisted (DayPreferences, keyed by date)
                   └─ intense-focus arcs on the ring + "Focused today"
```

## Error handling

- Not macOS: `MacRunningApplicationsProvider` and the detector's native reads are no-ops /
  return empty, consistent with `MacFrontmostApplicationProvider`.
- Native failure enumerating running apps → empty list (picker shows nothing rather than
  crashing), swallowed via `runCatching`.
- Empty / missing on-goal set → sustained rule inactive; churn behavior preserved.
- Null / blank frontmost bundle id → neutral (timer unchanged), as today.
- A native failure must never break the observation loop or suppress the card.

## Testing

- **`FocusDriftDetector` sustained-off-goal** (pure logic, injected frontmost sequences +
  on-goal set): off-goal reaching T fires; on-goal resets the timer; DayView / blank is
  neutral (does not reset, does not start); empty allowlist disables the rule; grace and
  cooldown respected; churn still fires independently. Parallel to the existing
  `FocusDriftDetectorTest`.
- **Shared classification** (pure function): on-goal / off-goal / neutral for the relevant
  bundle-id cases (in set, not in set, DayView's own, blank).
- **`PresenceAccumulator`** (pure logic, injected classification + timestamps): on-goal run
  builds one interval; a sub-bridge blip is tolerated; a gap ≥ bridge closes it; a
  sub-minimum interval is discarded; multiple intervals across the session; day rollover
  resets. Reuses the net-time test style.
- **`DayPreferences`** round-trips: the goal-keyed `Map<GoalId, Set<AppRef>>` (stable id
  survives a rename, persists across a new instance) and the date-keyed presence intervals
  (reloaded same-day, reset on day change).
- **Arc projection** of presence intervals to expected angles/sweeps (parallel to the
  busy-arc tests).
- **`MacRunningApplicationsProvider`**: macOS-guarded test asserting a non-empty result
  and no throw; no-op off macOS.

## Relationship to usage contribution

This is layer 1: OS-observed presence. The heartbeat here is DayView's own 1 s frontmost
sample, so both the drift nudge **and** the intense-focus arcs / "Focused today" total are
produced with no app cooperation. In particular, the on-goal intense-focus arcs are
delivered **here**, frontmost-derived — not by the usage-contribution layer.

The one thing OS observation cannot tell is **frontmost-but-idle**: an on-goal app is in
front but the user is not actually working in it. Catching that requires the app to report
its own activity — the app-contributed heartbeat in the separate usage-contribution spec
(`docs/superpowers/specs/2026-07-11-usage-contribution-design.md`). That heartbeat is
demoted to this single future job: refining intensity by distinguishing active from idle
on-goal time. The two are complementary; this spec does not depend on it.
