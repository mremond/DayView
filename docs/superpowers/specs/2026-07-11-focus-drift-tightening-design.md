# Focus-drift tightening — on-goal app allowlist (desktop macOS)

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
that set is positive presence; being outside it (sustained) is drift. This adds a
*presence* signal beside the existing *churn* signal; both feed the same, unchanged nudge.

This is entirely DayView observing the OS frontmost app. It needs no app cooperation, no
new transport, and no file format.

## Scope

- **macOS desktop only.** Detection and the nudge exist only there. Android and common code
  are unchanged except for the shared preference field that stores the app lists.
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

### 2. Detection — add sustained-off-goal beside churn

`FocusDriftDetector.observe(...)` gains the active goal's on-goal set. A new
**sustained-off-goal** rule runs alongside the existing churn rule:

- Track `offGoalSinceMillis`. Classify the frontmost app each tick:
  - **on-goal** (bundle id in the set) → reset the off-goal timer,
  - **off-goal** (non-blank, not DayView's own bundle, not in the set) → start / advance
    the timer,
  - **DayView or blank** → **neutral**: leave the timer unchanged (glancing at DayView to
    check the time does not excuse a drift already underway, nor does it start one).
- Fire when off-goal duration ≥ **T (default 2 min)**, respecting the existing
  `initialGraceMillis` (30 s) and `reminderCooldownMillis` (5 min).
- **Empty allowlist → the sustained-off-goal rule is skipped entirely**; only churn runs
  (today's behavior). This keeps the allowlist opt-in without a separate mode.
- The **churn rule is unchanged** and fires independently. Either rule returning `true`
  produces the existing notify + "REVENIR À L'ESSENTIEL" card — the output side is
  untouched.

### 3. Enumerating apps — `MacRunningApplicationsProvider` (new, desktopMain)

- Reads `NSWorkspace.sharedWorkspace.runningApplications`, filters to regular foreground
  apps (activation policy = regular, so daemons / agents do not clutter the picker),
  returns `[{ bundleId, localizedName }]`.
- Reuses the `ObjectiveCRuntime` JNA pattern already in `FocusDriftDetector.kt`, extended
  with the array-iteration / property selectors it needs. macOS-guarded; no-op elsewhere.

### 4. Settings UI

- A section under the goal lists the selected on-goal apps (display name) with a remove
  control, and an **"Add apps"** action opening a picker of currently-running apps
  (multi-select by name).
- **App icons are optional / deferred** to keep native scope down; names are cheap and
  sufficient for v1.
- The section is macOS-only (hidden / disabled on Android, where detection does not exist).

### 5. Wiring

- `Main.kt` passes the active goal's on-goal set into `focusDriftDetector.observe(...)`
  each tick, alongside the frontmost bundle id it already reads.
- No change to the nudge output path (notification + card) defined in the distraction-nudge
  spec.

## Data flow

```
focus session active (Pomodoro running)
  └─ each 1 s tick in Main.kt:
       frontmostBundleId  ← MacFrontmostApplicationProvider
       onGoalApps         ← DayViewUiState (active goal, from DayPreferences)
         └─ FocusDriftDetector.observe(isFocusActive, now, frontmostBundleId, onGoalApps)
              ├─ churn rule (unchanged)                → true?
              └─ sustained-off-goal rule (new, T=2min) → true?
                   └─ either true → notify + "REVENIR À L'ESSENTIEL" card
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
- **`DayPreferences`** round-trip of the goal-keyed `Map<GoalId, Set<AppRef>>`: save /
  load, stable id survives a rename, persists across a new preferences instance.
- **`MacRunningApplicationsProvider`**: macOS-guarded test asserting a non-empty result
  and no throw; no-op off macOS.

## Relationship to usage contribution

This is layer 1: OS-observed presence. It cannot tell **frontmost-but-idle** (an on-goal
app is in front but the user is not actually working in it). Catching that requires the app
to report its own activity — the heartbeat in the separate usage-contribution spec
(`docs/superpowers/specs/2026-07-11-usage-contribution-design.md`), which layers on top of
this detector once its limits show. The two are complementary; this spec does not depend on
it.
