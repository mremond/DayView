# Engaged time, deep focus and overtime — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore overtime for manual Focus, and engaged time and deep focus per Focus (ring, detail sheet, This week), in the native Swift Focus.

**Architecture:** Pure rules go into `DayPlanKit`:
- engine overtime;
- the overtime reminder;
- the presence accumulator;
- engaged time;
- week summary with deep.

`DayPlanStore` gains two entities (schema V5) written when a Focus ends. The app wires overtime into the Focus UI and notifications, feeds deep-focus presence from `DriftModel`'s sampler, and shows bright deep arcs, a detail sheet and the Deep figure.

**Tech Stack:** Swift 6 (tools 6.0), SwiftData, Swift Testing, SwiftUI/AppKit (macOS 15), UserNotifications, XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-engaged-time-overtime-design.md` (and the day-plan spec `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` for Focus/plan rules).

## Global Constraints

- `DayPlanKit` imports only `Foundation`. `DayPlanStore` imports only `Foundation`, `SwiftData` and `DayPlanKit`.
- Time logic takes `now` + `Calendar`. Tests use the Europe/Paris gregorian calendar.
- CloudKit-compatible SwiftData models with `cloudKitDatabase: .none`. **No change to existing @Model classes.** Only new entities, in V5.
- **Overtime applies to manual Focus only.**
  - A manual session is not finished at its term; it stays `current` until stopped or taken over.
  - Stop at or after the term → reason `.completed`; before the term → `.stopped`.
  - `actualEnd` = the stop time, and `focused` = `actualEnd − start`.
  - The break starts at the actual end.
  - Planned Focus ends at its block end (unchanged).
  - A block start takes over a manual Focus whether or not it is in overtime. The overtime up to the takeover counts: the stage 4 non-retroactive rule is kept, without the cap at the manual term.
- **Overtime reminder:**
  - fires once per session when `now ≥ term + planned duration`;
  - only if that threshold is crossed between two observations, and `now − threshold ≤ 90 s` (late-alert rule);
  - en "Still on %@? Close this focus when you're ready." / fr "Toujours sur %@ ? Clôturez ce focus quand vous êtes prêt.";
  - plus a soft chime (NSSound "Glass"), only when `cues.enabled`.
- **Deep focus:**
  - on-goal presence intervals with a **60 s bridge** (off-goal or neutral gaps shorter than 60 s don't break a stretch) and a **120 s minimum** stretch;
  - a sample gap above 5 s ends the open stretch at the last on-goal sample;
  - only when the area has on-goal apps.
- **Engaged** = `focused − overlap(detours, [start, actualEnd])`, never below 0. Detours come from the Kotlin snapshot (`TodaySnapshot.detours`: `startEpochMillis`/`endEpochMillis`).
- **This week:** "Planned %@ · Focus %@ · Deep %@" (the Deep part only for areas with measured deep time) / "Prévu %@ · Focus %@ · Profond %@". Focus = engaged.
- **Running clock in overtime:** "+12:34"; the menu bar title shows the same.
- UI strings in en/fr via `.strings` and `L()`/`LF()`.
- Tests: `swift test --package-path macos/Packages/DayPlanKit` passes; the app builds (`xcodegen generate` + `xcodebuild … build CODE_SIGNING_ALLOWED=NO`). No Kotlin change.
- **Launch checks:**
  - capture only DayView windows (`screencapture -l <windowid>`), never the screen or a region;
  - don't interact with other apps;
  - no calendar, email or connector tools;
  - never touch bundle `fr.dayview.app`;
  - never delete or reset the Debug app's store or defaults;
  - quit only the Debug app you launched.
- Commits in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **A manual Focus in overtime when a planned block starts.** The plan takes over. The interrupted record's `actualEnd` is the takeover time (past the term) and its reason is `.tookOver`. The takeover cue is spoken once → test in Task 1.
2. **A manual Focus restored after relaunch while in overtime.** It is still running, the overtime is counted, and the reminder doesn't fire late after a long sleep → tests in Task 1.
3. **Deep focus with brief interruptions:**
   - a 40 s off-goal blip inside a stretch doesn't split it;
   - a 90 s stretch isn't counted;
   - a sleep gap ends the stretch

   → tests in Task 2.
4. **Engaged with a detour that started before the Focus or is still running at its end.** Only the overlap is subtracted, and engaged is never negative → test in Task 2.
5. **Midnight with a manual Focus in overtime.** `beginDay` keeps a manual Focus (stage 4 rule), so the overtime continues and the record belongs to the day it started → test in Task 1.

## Rulings in this plan (open to correction)

- The overtime chime follows the cue setting (off when spoken cues are off). The notification is posted only when authorized; there is no Dock bounce.
- The Keep going offer is unchanged. It is for planned blocks and takeovers, never for manual overtime.
- Detours logged after the Focus ended don't change that Focus's stored engaged time; it is computed once, when the Focus ends.
- Deep stretches for the running Focus are kept in memory and drawn live. They are stored when the Focus ends.
- With no on-goal apps for the area, `deepMeasured = false`: the detail sheet and This week omit Deep for it.

---

## File Structure

```
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  FocusEngine.swift     (modify) manual overtime, stop semantics, takeover in overtime, overtime(at:)
  Overtime.swift        (new) OvertimeReminder
  Presence.swift        (new) PresenceAccumulator, EngagedTime
  WeekSummary.swift     (modify) FocusTime.deep, AreaWeekLine.deep
macos/Packages/DayPlanKit/Sources/DayPlanStore/
  Records.swift, Schema.swift, PlanStore.swift (modify) FocusMetricsEntry, FocusPresenceEntry, V5, API
macos/DayView/FocusModel.swift, FocusPanel.swift, MenuBarContent.swift, DayViewApp.swift (modify) overtime UI + reminder
macos/DayView/DriftModel.swift, RingView.swift, DayRingCanvas.swift, WeekView.swift, PlanModel.swift (modify) deep tracking, arcs, This week
macos/DayView/FocusDetailSheet.swift (new)
macos/DayView/{en,fr}.lproj/Localizable.strings (modify)
```

---

### Task 1: DayPlanKit — manual overtime and the overtime reminder

**Interfaces:**
- Consumes: `FocusEngine`, `FocusSession`, `FocusRecord`, `FocusEndReason`, stage-4 `beginDay`, `focusedSoFar(at:)`, the non-retroactive takeover.
- Produces:
  - In `FocusEngine`:
    - a manual session is no longer finished at its term;
    - `stop` on a manual session finishes it at `now` with `.completed` if `now ≥ session.end`, else `.stopped`;
    - `breakStartedAt` = the actual end of a manual session that ends by stop (completed or stopped) — **not** on a takeover, matching stage 1, where a takeover starts no break;
    - takeover applies to a manual `current` regardless of term, with `actualEnd = max(blockStart, lastAdvance ?? blockStart, manual.start)`, no cap at `manual.end`.
  - `public func overtime(at now: Date) -> TimeInterval?`: for a manual `current` with `now > end`, returns `now − end`; else nil.
  - `focusedSoFar(at:)` for manual = `now − start`, no longer capped at `end`.
  - `public struct OvertimeReminder: Sendable, Codable` with `public mutating func observe(now: Date, session: FocusSession?) -> Bool`:
    - when the session ID changes: reset the fired flag and the previous observation;
    - fire once when a manual session's threshold `end + (end − start)` lies in `(previous, now]` and `now − threshold ≤ 90`;
    - nil or planned sessions never fire.
- [ ] **Step 1: Failing tests** (`OvertimeTests`; update existing engine tests that assert completion at the term, listing them in the report):
  - `manualContinuesPastTerm`: start 25 min at 10:00 → advance at 10:30: no `.ended`, `current` still set, `overtime(at: 10:30) == 300`.
  - `stopInOvertimeCompletes`: stop at 10:40 → record `.completed`, `actualEnd` 10:40, `focused == 2400`, `breakStartedAt == 10:40`.
  - `stopBeforeTermStops`: stop at 10:10 → `.stopped`, `focused == 600`, `breakStartedAt == 10:10`.
  - `takeoverInOvertime` (Review Focus 1): manual 10:00–10:25, block 11:00–12:00, ticks every second → at 11:00, `.tookOver` with interrupted `actualEnd == 11:00`, reason `.tookOver`, `focused == 3600`; no break.
  - `restoredInOvertime` (Review Focus 2): encode and decode a manual Focus started at 10:00 for 25 min, advance at 11:00 → still running, `overtime == 2100`.
  - `midnightKeepsOvertimeManual` (Review Focus 5): manual 23:30–23:55, `beginDay(next day)` at 00:10 → still current, `overtime == 900`.
  - `reminderFiresOnceAtDouble`: session 10:00–10:25 observed every 1 s → true only at 10:50:00.
  - `reminderLateIsSilent`: observations at 10:40 and 12:00 → false (threshold 10:50 crossed but 70 min late).
  - `reminderIgnoresPlanned`: never fires for a planned session.
  - `reminderNewSessionResets`.
- [ ] **Step 2–4: run fail / implement / run pass** (`swift test --package-path macos/Packages/DayPlanKit`, clean output).
- [ ] **Step 5: Commit** `git commit -m "Keep a manual Focus open in overtime until it is closed"`

---

### Task 2: DayPlanKit — deep-focus presence, engaged time, week with deep

**Interfaces:**
- Produces:
  - `public struct PresenceAccumulator: Sendable` with constants `bridge = 60`, `minimum = 120`, `maxSampleGap = 5`, `init()`, and these methods:
    - `mutating func sample(onGoal: Bool, at now: Date)`;
    - `mutating func finish(at now: Date) -> [DateInterval]`: closes any open stretch at the last on-goal sample and returns all stretches ≥ 120 s, sorted;
    - `var stretches: [DateInterval]`: the closed ones so far, for live drawing;
    - `func live(at now: Date) -> [DateInterval]`: closed stretches plus the open one up to its last on-goal sample, unfiltered by the minimum, for the ring.

    The stretch rules:
    - a stretch opens on the first on-goal sample;
    - it extends with on-goal samples;
    - a non-on-goal sample keeps it open unless `now − lastOnGoal > 60`, in which case it closes at `lastOnGoal`;
    - a sample gap above 5 s closes it at `lastOnGoal`.
  - `public enum EngagedTime { static func compute(focused: TimeInterval, window: DateInterval, detours: [DateInterval]) -> TimeInterval }`: `max(0, focused − |union(detours) ∩ window|)`.
  - `FocusTime` gains `deep: TimeInterval?` (default nil) and `AreaWeekLine` gains `deep: TimeInterval?`. Deep is the sum of non-nil deeps per area, nil if none was measured. `seconds` keeps meaning the engaged Focus time.
- [ ] **Step 1: Failing tests** (`PresenceTests`, `EngagedTimeTests`, `WeekSummaryTests` additions):
  - `continuousStretch`: on-goal every 1 s for 10:00–10:05 → `[10:00, 10:05]`.
  - `blipBridged` (Review Focus 3): on-goal 10:00–10:03, off-goal 10:03:01–10:03:40, on-goal to 10:06 → one stretch `[10:00, 10:06]`.
  - `longBreakSplits`: off-goal for 70 s → two stretches.
  - `shortStretchDropped`: 90 s on-goal → `[]` after `finish`.
  - `sleepGapEnds`: on-goal to 10:04, next sample 11:00 on-goal → the first stretch ends 10:04.
  - `engaged`: focused 3600, window 10:00–11:00, detours `[09:50–10:10, 10:30–10:40, 10:35–10:45]` → 3600 − 600 − 900 = 2100. A detour covering the whole window → 0.
  - Week deep: an area with a deep 1200 + nil → deep 1200; an area with only nils → nil.
- [ ] **Step 2–4.**
- [ ] **Step 5: Commit** `git commit -m "Add deep-focus presence and engaged time rules"`

---

### Task 3: DayPlanStore — Focus metrics and deep stretches (schema V5)

**Interfaces:**
- Produces:
  - `@Model final class FocusMetricsEntry` (all fields defaulted, no unique):
    - `sessionID: UUID`
    - `dayKey: String`
    - `areaID: UUID`
    - `engagedSeconds: Double`
    - `deepSeconds: Double`
    - `deepMeasured: Bool`
  - `@Model final class FocusPresenceEntry`: `sessionID: UUID`, `dayKey: String`, `start: Date`, `end: Date` (defaults).
  - `DayPlanSchemaV5` (5.0.0) adds both, with a lightweight V4→V5 stage. Containers use V5.
  - `public struct FocusMetrics: Sendable, Hashable { sessionID: UUID; areaID: AreaID; day: LocalDay; engaged: TimeInterval; deep: TimeInterval?; deepStretches: [DateInterval] }`. `deep` is nil when not measured.
  - `PlanStore.saveFocusMetrics(_ m: FocusMetrics) throws`: upserts by `sessionID` and replaces that session's presence entries; derives everything before mutating; goes through `commit()`.
  - `PlanStore.focusMetrics(from: LocalDay, through: LocalDay) -> [UUID: FocusMetrics]`: range predicate on `dayKey`.
- [ ] **Step 1: Failing tests** (`FocusMetricsTests`):
  - round trip with 2 stretches;
  - an upsert replaces the stretches;
  - range;
  - `deep == nil` round trip;
  - `migratesStage5Store`: a V4 store with an area, an accepted plan, a closed Focus and an ignored cue reopens through `makeContainer(url:)` and accepts `saveFocusMetrics`.
- [ ] **Step 2–4.**
- [ ] **Step 5: Commit** `git commit -m "Store Focus metrics and deep-focus stretches"`

---

### Task 4: App — overtime in the Focus UI and the overtime reminder

**Files:** `FocusModel.swift`, `FocusPanel.swift`, `MenuBarContent.swift`, `DayViewApp.swift`, `CueSpeaker.swift` (chime helper), strings.

**Interfaces:**
- Consumes: Task 1 (`overtime(at:)`, `OvertimeReminder`, stop semantics).
- Produces:
  - **`FocusModel`:**
    - persists an `OvertimeReminder` alongside the engine, in the same JSON or a sibling key `"focus.overtimeReminder"`;
    - observes it each tick;
    - on fire, calls `onOvertimeReminder?(session)`;
    - exposes `func overtime(at:) -> TimeInterval?`.
  - **Overtime display:**
    - Running panel: the clock shows "+m:ss" / "+h:mm:ss" with the overtime label "In overtime" / "En dépassement", and the stop button reads "Close this focus" / "Clôturer ce focus" (add it if no key remains).
    - Menu bar title: "+m:ss" in overtime.
    - Mini window: the same compact display.
  - **Reminder wiring:** `DayViewApp` sets `onOvertimeReminder`:
    - it posts a `UNNotification` (fixed ID `"dayview.overtime"`) with the Global Constraints text and the area name, if authorized;
    - it plays the chime through a `CueSpeaker.chime()` helper that respects `cues.enabled` (a private play path, not flipping settings).
  - The menu bar "Keep going" stays absent for manual overtime.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + package tests.**
- [ ] **Step 3: Launch check:** start a 5-min manual Focus and wait past the term (about 5 min). The panel shows "+0:xx · In overtime"; Stop → the closing card. Window-only capture.
- [ ] **Step 4: Commit** `git commit -m "Show manual Focus overtime and suggest closing at double time"`

---

### Task 5: App — deep-focus tracking, engaged metrics, ring arcs, detail sheet, This week

**Files:** `DriftModel.swift`, `FocusModel.swift`, `PlanModel.swift`, `RingView.swift`, `DayRingCanvas.swift`, `WeekView.swift`, `FocusDetailSheet.swift` (new), strings.

**Interfaces:**
- Consumes: Tasks 2–3; `TodayModel.snapshot.detours` (`startEpochMillis`/`endEpochMillis`); `FocusModel` events (`.ended`, `.tookOver`) via a new `onFocusEnded: ((FocusRecord) -> Void)?` hook fired after the record is queued.
- Produces:
  - **`DriftModel` presence:**
    - keeps a `PresenceAccumulator` for the current session ID and feeds it `onGoal: classification == .onGoal` on each sample;
    - resets it when the session changes;
    - exposes `liveDeepStretches(at:) -> [DateInterval]` for the running Focus;
    - measured = the area has on-goal apps at Focus start.
  - **On Focus end** (`onFocusEnded`), the app computes and stores `FocusMetrics` via `PlanModel.saveFocusMetrics`:
    - `engaged = EngagedTime.compute(focused: record.focused, window: [start, actualEnd], detours: snapshot detours)`;
    - `deepStretches = accumulator.finish(at: actualEnd)` when measured, and `deep` = their total duration;
    - `deep` stays nil when not measured.
  - **Ring:**
    - bright mint arcs (canvas style of the former Kotlin `focusArcs`: mint 0.55, width ×0.5) for today's stored stretches plus live ones, over the existing dim bands;
    - clicking a Focus band opens `FocusDetailSheet`: area (colour + name), intention, time range, "Engaged %@" / "Engagé %@", "Deep focus %@" / "Focus profond %@" (omitted when not measured), outcome ("Completed" / "Progressed" / "Resume later" / "Not closed yet" / "Pas encore clôturé"), and a Close button;
    - hover is unchanged.
  - **`WeekView`:**
    - Focus = engaged, from metrics when present, else `focused`;
    - the running session uses `focusedSoFar − today's detour overlap`, with deep from the live stretches when measured;
    - the line uses the Global Constraints format.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + tests.**
- [ ] **Step 3: Launch check:** the main window shows the dim bands. Clicking a band opens the detail sheet if pointer automation inside DayView's own window is possible; otherwise say it was not exercised. Window-only capture.
- [ ] **Step 4: Commit** `git commit -m "Restore engaged time and deep focus for the Swift Focus"`
