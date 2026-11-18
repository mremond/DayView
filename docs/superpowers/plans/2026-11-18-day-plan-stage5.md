# Day Plan — Stage 5 (per-area drift, follow-through signals, This week) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During a Focus, detect drift away from the active area's on-goal apps, with a notification and a Dock bounce. Record ignored cues, show both follow-through signals in the morning check, and add a "This week" window with planned versus Focus time per area.

**Architecture:** Pure rules go into `DayPlanKit`:
- app classification and the drift/ignored-cue state machine (`DriftMonitor`);
- the review summary extended with ignored cues;
- the week summary.

`DayPlanStore` gains ignored-cue records (schema V4) and range queries. The app gets a `DriftModel`, which samples the frontmost app each second during a Focus, plus the This week window and the morning-check line. It also draws Swift Focus arcs on the ring and cleans up the stage 4 leftovers.

**Tech Stack:** Swift 6 (tools 6.0), SwiftData, Swift Testing, SwiftUI/AppKit (macOS 15), UserNotifications, XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` (stage 5 of *Delivery stages*; sections *Per-area drift detection*, *Follow-through signals and weekly summary*).

## Global Constraints

- `DayPlanKit` imports only `Foundation`; `DayPlanStore` only `Foundation`, `SwiftData`, `DayPlanKit`. Time logic takes `now` + `Calendar`; tests use the Europe/Paris gregorian calendar.
- CloudKit-compatible SwiftData models; `cloudKitDatabase: .none`.
- **Classification.** During a Focus the frontmost app is on-goal, off-goal or neutral for the active area. DayView itself (`fr.dayview.app`, `fr.dayview.app.debug`) is neutral, and so is any app when the area has no on-goal apps.
- **Drift.** Off-goal continuously for **3 minutes** → one drift notification ("You planned Writing" / "Vous aviez prévu Écriture") and one Dock bounce. At most one drift reminder per **10 minutes**. No spoken drift cue.
- **Ignored cue.** A block-start or takeover cue after which the frontmost app stayed off-goal for the area for **5 minutes**. Only measurable when the area has on-goal apps.
- **Morning check line:** "Last 7 days: plan reviewed 5/7 days · 3 cues ignored" / "7 derniers jours : plan revu 5/7 jours · 3 annonces ignorées".
- **This week window** (menu bar → "This week" / "Cette semaine"):
  - per area, planned time vs. Focus time, from Monday to today;
  - the two signals for each day;
  - a plain list, no charts.
- UI strings in en/fr via `.strings` and `L()`/`LF()`.
- Tests: `swift test --package-path macos/Packages/DayPlanKit` passes; the app builds (`xcodegen generate` + `xcodebuild … build CODE_SIGNING_ALLOWED=NO`). No Kotlin change.
- **Launch checks:**
  - capture only DayView windows (`screencapture -l <windowid>`), never the screen or a region;
  - no calendar, email or connector tools;
  - never touch bundle `fr.dayview.app`;
  - never delete or reset the Debug app's store or defaults;
  - quit only the Debug app you launched.
- Commits in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **DayView itself frontmost, or an area with no on-goal apps.** Classified neutral: no drift reminder and no ignored cue, however long it lasts → test in Task 1.
2. **Back on-goal briefly between two off-goal stretches.** Off-goal 3 min → reminder. On-goal 10 s, then off-goal 3 min → no second reminder until 10 min after the first → test in Task 1.
3. **Sleep gap.** A gap of more than 5 s between samples (sleep, app not ticking) breaks "continuously". Two off-goal samples 2 hours apart are not 2 hours of drift → test in Task 1.
4. **Focus ends while off-goal.** The off-goal streak and any open ignored-cue watch are discarded; nothing fires after the Focus → test in Task 1.
5. **Week summary edge cases.** Correct in all of these:
   - on a Monday;
   - in a week with the 2026-03-29 DST change;
   - a day without an accepted plan;
   - unclosed (pending) records and the running Focus counted as Focus time.

   → tests in Task 1.

## Rulings in this plan (open to correction)

- **Continuously off-goal.** Samples arrive about once a second. A gap of more than **5 s** between samples, a neutral sample or an on-goal sample resets the streak.
- **Ignored cue window.**
  - It starts when a block-start or takeover cue is delivered while cues are enabled (spoken or chimed). With cues off, the user heard nothing.
  - It counts as ignored only if **every** sample in the 5 minutes from the cue is off-goal (a neutral or on-goal sample, or the Focus ending, clears it).
  - It is checked against the area of the Focus that the cue started.
- **Planned time** in This week = the accepted plan's block durations per area, unfitted. Meetings of past days are not stored, so planned time may exceed what the calendar allowed. Days with no accepted plan count 0 planned.
- **Focus time** = closed records' focused time + unclosed (pending) records + the running Focus's elapsed time. Unclosed records are not expired; they stay until the user closes them. Detour time is not subtracted (detours stay in Kotlin).
- **Notification.**
  - Authorization is requested the first time a Focus starts.
  - If it is denied or unavailable (the Debug build is unsigned), the reminder is an in-app banner in the main window ("You planned Writing", with a dismiss button) plus the Dock bounce.
  - The banner is shown in every case.
- **Ring.** Today's Swift Focus spans (closed, pending and running) are drawn as session bands on the main lane: start to actual end, dim mint, as the Kotlin bands were. Planned Focus pauses for meetings are not cut out.
- **Settings.**
  - The General tab's global "On-goal apps" section (Kotlin) is removed; on-goal apps are set per area.
  - The cue speaker stops queued speech when cues are turned off, and the Test button no longer flips the stored setting.

---

## File Structure

```
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  Drift.swift          (new) AppClassification, AppClassifier, DriftMonitor, DriftEvent
  WeekSummary.swift    (new) WeekSummary, AreaWeekLine, DaySignals
  TodayPlanner.swift   (modify) ReviewSummary gains ignored cues
macos/Packages/DayPlanKit/Sources/DayPlanStore/
  Records.swift, Schema.swift, PlanStore.swift (modify) IgnoredCueEntry, V4, range queries
macos/DayView/DriftModel.swift        (new)
macos/DayView/WeekView.swift          (new) This week window
macos/DayView/FocusModel.swift, TodayPlanModel.swift, MorningCheckSheet.swift, MenuBarContent.swift,
  DayViewApp.swift, RingView.swift, DayRingCanvas.swift, SettingsView.swift, CueSpeaker.swift (modify)
macos/DayView/{en,fr}.lproj/Localizable.strings (modify)
```

---

### Task 1: DayPlanKit — classification, drift monitor, week summary

**Interfaces:**
- Consumes: `Area` (`id`, `onGoalApps: [OnGoalApp]`), `AreaID`, `DayPlan`, `AcceptedPlan`, `LocalDay`, `ScheduledCue`.
- Produces:
  - `public enum AppClassification: Sendable, Hashable { case onGoal, offGoal, neutral }`.
  - `public enum AppClassifier { static let dayViewBundleIDs: Set<String> = ["fr.dayview.app", "fr.dayview.app.debug"]; static func classify(bundleID: String?, area: Area) -> AppClassification }`:
    - nil bundle ID, a DayView ID, or an area with no apps → `.neutral`;
    - a bundle ID in the area's apps → `.onGoal`;
    - else `.offGoal`.
  - `public enum DriftEvent: Sendable, Hashable { case driftReminder(areaID: AreaID); case cueIgnored(areaID: AreaID, cueAt: Date) }`.
  - `public struct DriftMonitor: Sendable` with constants:
    - `driftThreshold = 180`
    - `reminderSpacing = 600`
    - `ignoredWindow = 300`
    - `maxSampleGap = 5`

    Methods:
    - `mutating func sample(_ c: AppClassification, areaID: AreaID?, at now: Date) -> [DriftEvent]`. `areaID` nil means no Focus. On the first sample with no Focus or a different area, everything resets (streak and watch). When the streak reaches 180 s and the last reminder was ≥ 600 s ago (or never), it emits `.driftReminder`.
    - `mutating func cueDelivered(areaID: AreaID, measurable: Bool, at: Date)`. Opens a watch when `measurable` (the area has on-goal apps). The watch resolves to `.cueIgnored` on the first sample at or after `at + 300` if every sample since `at` was `.offGoal` with no gap above 5 s. Otherwise it closes silently.
  - `ReviewSummary.lastSevenDays(before:history:ignored: [LocalDay: Int], calendar:) -> (reviewed: Int, days: Int, ignored: Int)?`. Same rule as before. `ignored` is the sum over the same 7 days. It returns non-nil if any of those days has review history **or** ignored entries. The stage 3 overload is removed and its call site updated.
  - Week types:
    - `public struct AreaWeekLine: Sendable, Hashable { areaID: AreaID; planned: TimeInterval; focused: TimeInterval }`
    - `public struct DaySignals: Sendable, Hashable { day: LocalDay; review: ReviewState; ignoredCues: Int }`, with `public enum ReviewState { case reviewed, autoAccepted, noPlan }`
    - `public struct WeekSummary: Sendable, Hashable { days: [LocalDay]; areas: [AreaWeekLine]; signals: [DaySignals] }`
    - `public struct FocusTime: Sendable, Hashable { day: LocalDay; areaID: AreaID; seconds: TimeInterval }`
  - `public static func WeekSummary.compute(today: LocalDay, calendar: Calendar, accepted: [LocalDay: AcceptedPlan], focus: [FocusTime], ignored: [LocalDay: Int], areaOrder: [AreaID]) -> WeekSummary`:
    - `days` = ISO Monday of today's week through today.
    - `areas`: one line per area in `areaOrder`, then any other area seen in plans or focus, in first-seen order. Lines with planned == 0 and focused == 0 are dropped.
    - Planned = the sum of accepted block durations for days in range.
    - `review`: reviewed, auto-accepted (accepted with reviewed false), or noPlan.
- [ ] **Step 1: Failing tests** (`DriftTests`, `WeekSummaryTests`, updated `TodayPlannerTests`):
  - `classify`: DayView IDs → neutral; an area with no apps → neutral even for Safari; on-goal; off-goal; nil → neutral.
  - `driftAfterThreeMinutes`: off-goal samples every 1 s from 10:00 → no event through 10:02:59; `.driftReminder` at 10:03:00; none at 10:03:01.
  - `reminderSpacing` (Review Focus 2): first reminder at 10:03. On-goal at 10:03:10, off-goal again from 10:03:20 → the streak reaches 180 s at 10:06:20 but there is no reminder (less than 10 min). Continuous off-goal → the next reminder at exactly 10:13:00.
  - `neutralNeverDrifts` (Review Focus 1): 20 min of `.neutral` → no events.
  - `sleepGapBreaksStreak` (Review Focus 3): off-goal at 10:00:00 and 10:00:01, next sample at 12:00:00 → no reminder until 12:03:00.
  - `focusEndResets` (Review Focus 4): off-goal for 170 s, then a sample with `areaID: nil`, then off-goal again with an area → the reminder only after 180 s more.
  - `cueIgnored`: cue at 14:00 (measurable); off-goal every second until 14:05:00 → `.cueIgnored(areaID, 14:00)` at 14:05:00, once.
  - `cueNotIgnoredWhenOnGoal`: same, but on-goal at 14:02 → no event.
  - `cueNotMeasurable`: `measurable: false` → no event.
  - `reviewSummaryWithIgnored`: history {11-17: true, 11-16: false} and ignored {11-17: 2, 11-12: 1} → (1, 7, 3). Ignored only {11-15: 1} → (0, 7, 1).
  - Week summary:
    - `weekFromMonday`: today Wed 2026-11-18 → days 11-16…11-18.
    - On Monday 11-16 → one day.
    - `plannedAndFocus`: accepted Mon (Writing 09:00–12:00 reviewed) and Tue (Writing 09:00–10:00, Health 18:00–19:00, auto). Focus Mon Writing 2 h 30 m, Tue Health 50 m, Wed Writing 20 m → Writing planned 4 h / focused 2 h 50 m; Health 1 h / 50 m. Signals: Mon reviewed, Tue autoAccepted, Wed noPlan.
    - `dstWeek`: today Wed 2026-04-01 → days 03-30…04-01 (the DST change on 03-29 is in the previous week, so the computation must not break). Today Sun 2026-03-29 → 03-23…03-29, 7 days.
- [ ] **Step 2: Run, verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**: `git commit -m "Add drift detection and weekly summary rules"`

---

### Task 2: DayPlanStore — ignored cues (schema V4) and range queries

**Interfaces:**
- Produces:
  - `@Model final class IgnoredCueEntry`: `id: UUID`, `dayKey: String = ""`, `at: Date = .distantPast`, `areaID: UUID`, with defaults.
  - `DayPlanSchemaV4` (4.0.0) adds it, with a lightweight V3→V4 stage. Containers use V4.
  - `PlanStore`:
    - `saveIgnoredCue(areaID: AreaID, at: Date, calendar: Calendar) throws` (dayKey from the local day of `at`);
    - `ignoredCues(from: LocalDay, through: LocalDay) -> [LocalDay: Int]`;
    - `acceptedPlans(from:through:) -> [LocalDay: AcceptedPlan]`;
    - `closedFocus(from:through:) -> [ClosedFocus]`.

    All use predicates on `dayKey` (zero-padded ISO keys compare in date order).
- [ ] **Step 1: Failing tests** (`SignalsStoreTests`):
  - Ignored cues counted per day, with the range inclusive.
  - `acceptedPlans` range.
  - `closedFocus` range.
  - `migratesStage4Store`: a temp-URL store opened with `Schema(versionedSchema: DayPlanSchemaV3.self)` holding an area, an accepted plan and a closed Focus survives reopening through `makeContainer(url:)`, and `saveIgnoredCue` works afterwards.
- [ ] **Step 2–4.**
- [ ] **Step 5: Commit**: `git commit -m "Store ignored cues and add range queries"`

---

### Task 3: App — DriftModel

**Files:** `macos/DayView/DriftModel.swift` (new); `FocusModel.swift`, `PlanModel.swift`, `CueSpeaker.swift`, `DayViewApp.swift`, `RingView.swift`, strings (modify).

**Interfaces:**
- Consumes: Task 1 `AppClassifier`/`DriftMonitor`; Task 2 `saveIgnoredCue`; `FocusModel.current`/`activeArea`; the cue pipeline in `DayViewApp` (`focus.onCues`); `CueSpeaker.isEnabled`.
- Produces:
  - `@MainActor final class DriftModel: ObservableObject`, with `init(focus: FocusModel, plan: PlanModel, calendar: Calendar = .autoupdatingCurrent, now: @escaping () -> Date = Date.init)` and `@Published private(set) var reminderAreaID: AreaID?`.
    - Each second (its own timer) it samples `NSWorkspace.shared.frontmostApplication?.bundleIdentifier`, classifies against `focus.activeArea`, and feeds `DriftMonitor.sample` with `areaID: focus.current?.areaID`.
    - On `.driftReminder` it:
      - sets `reminderAreaID`;
      - posts a `UNUserNotification` (title "You planned %@" / "Vous aviez prévu %@", body the intention if any);
      - calls `NSApp.requestUserAttention(.informationalRequest)`.
    - On `.cueIgnored` it calls `plan.saveIgnoredCue(...)`.
    - `dismissReminder()` clears the banner. The banner clears by itself when the Focus ends or the area changes.
    - `cueDelivered(_ cues: [ScheduledCue], cuesEnabled: Bool)`: for each `.blockStart` cue, if `cuesEnabled`, calls `monitor.cueDelivered(areaID: cue.block!.areaID, measurable: area has on-goal apps, at: now)`.
  - In the `onCues` wiring in `DayViewApp`, after composing sentences, the delivered cues (including supplemented takeover starts) go to `drift.cueDelivered(due, cuesEnabled: speaker.isEnabled)`.
  - `FocusModel.startManual` and the first planned start request notification authorization once (`UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])`). Failures are ignored.
  - Main window: a drift banner above the ring section while `reminderAreaID != nil`, reading "You planned %@" with a "Dismiss" / "Ignorer" button (reuse an existing key if one exists).
  - `CueSpeaker` cleanups:
    - turning `isEnabled` off, or turning chime on, calls `synthesizer.stopSpeaking(at: .immediate)`;
    - `test()` plays through a private unguarded `play(_:)` path and no longer flips `cues.enabled`.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + package tests.**
- [ ] **Step 3: Launch check** (window-only capture): start a manual Focus in an area with one on-goal app.
  - It's acceptable to leave Finder frontmost for 3 minutes, but only if this can be done without touching the user's other apps. Otherwise say it was not exercised.
  - Report the banner if it was seen, or what was not exercised.
- [ ] **Step 4: Commit**: `git commit -m "Detect drift per area and record ignored cues"`

---

### Task 4: App — This week window and the morning-check line

**Files:** `macos/DayView/WeekView.swift` (new); `TodayPlanModel.swift`, `MorningCheckSheet.swift`, `MenuBarContent.swift`, `DayViewApp.swift`, `PlanModel.swift`, strings (modify).

**Interfaces:**
- Consumes: Task 1 `WeekSummary.compute`, `FocusTime`, the new `ReviewSummary`; Task 2 range queries; `FocusModel` (`pendingClosures`, `current`).
- Produces:
  - `TodayPlanModel.reviewSummary` returns `(reviewed, days, ignored)` using `plan.ignoredCues(from:through:)`. The morning check shows the exact line from Global Constraints. The ignored part ("· 3 cues ignored") is always shown once the line shows, including "· 0 cues ignored"; use "1 cue ignored" / "1 annonce ignorée" for one.
  - `Window("This week", id: "week")` in `DayViewApp`, with a menu bar item "This week" / "Cette semaine" that opens it.
  - `WeekView` computes `WeekSummary` on appear and each minute:
    - focus = `plan.closedFocus(from: monday, through: today)` plus `focus.pendingClosures`, plus the running `current` (elapsed to now);
    - all as `FocusTime` by the local day of the session start.
  - `WeekView` shows:
    - a header "Monday 16 – Wednesday 18 November" (formatted dates);
    - per area: colour dot, name, "Planned 4:00 · Focus 2:50" / "Prévu 4:00 · Focus 2:50";
    - per day: date, "Plan reviewed" / "Plan revu", "Auto-accepted" / "Accepté automatiquement" or "No plan" / "Pas de plan", and "%lld cues ignored" / "%lld annonces ignorées" (singular forms as above);
    - an empty state "Nothing yet this week" / "Rien encore cette semaine".
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + tests.**
- [ ] **Step 3: Launch check:** open This week from the menu bar via the Debug app's menu (if the menu bar can't be automated, open it with a temporary debug-only path and remove that before committing, or say it was not exercised). Window-only capture.
- [ ] **Step 4: Commit**: `git commit -m "Add the This week window and ignored cues to the morning check"`

---

### Task 5: App — Swift Focus arcs on the ring; settings cleanup

**Files:** `RingView.swift`, `DayRingCanvas.swift`, `SettingsView.swift`, `TodayModel.swift`, strings (modify).

**Interfaces:**
- Consumes: `PlanRing.window`, the arc helpers in `PlanRing` (generalize with `public static func arcs(intervals: [DateInterval], windowStart:windowEnd:) -> [(start: Double, sweep: Double)]` or similar if needed; update the PlanRing tests); `FocusModel` (`pendingClosures`, `current`); `plan.closedFocus(on: today)`.
- Produces:
  - Today's Swift Focus spans as session bands on the main lane, in the canvas's existing `focusSessionBands` style (mint 0.18, width ×0.5). They replace the Kotlin `focusSessionBands`/`focusArcs` on the main ring. Kotlin `focusSessions` hover/tap detail is removed from the main ring; hover on a Swift band shows "<Area> · 09:00–10:15".
  - The General tab's "On-goal apps" section is removed, with the `TodayModel` wrappers left unused (`addOnGoalApp`, `removeOnGoalApp`, `onGoalApps`, `runningApps`, `onGoalBundleIds`) and their strings, if unused. `CapMigration` still reads `today.onGoalApps()`, so keep that one wrapper.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + tests.**
- [ ] **Step 3: Launch check:** the main window shows today's Swift Focus bands (the Debug store has a closed record from stage 4 testing). Window-only capture.
- [ ] **Step 4: Commit**: `git commit -m "Draw Swift Focus on the ring and drop the global on-goal list"`
