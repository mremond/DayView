# Day Plan — Stage 4 (Swift-owned Focus and spoken cues) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The native app runs the Focus itself: planned Focus at each block, manual Focus, the plan taking over, Keep going and the closing ritual. It speaks the cues at block boundaries and shows the active area and its goal, and it stops using the Kotlin Focus and Cap.

**Architecture:** `DayPlanKit` gains the remaining pure pieces:
- persistable, day-scoped Focus engine state;
- a non-retroactive takeover;
- Keep going as a plan transformation;
- localized cue sentences;
- goal-deadline arithmetic.

`DayPlanStore` stores closed Focus records (schema V3). The app gets a `FocusModel`, which drives the engine, persists it, delivers cues and closes records, plus a `CueSpeaker` for speech or a chime. The Focus UI in the main window, mini window, menu bar and Focus menu moves from the Kotlin snapshot to `FocusModel`.

**Tech Stack:** Swift 6 (tools 6.0), SwiftData, Swift Testing, SwiftUI/AppKit (macOS 15), AVFoundation (`AVSpeechSynthesizer`), XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` (stage 4 of *Delivery stages*; sections *Focus: planned and manual*, *Spoken cues*, *Life areas* (active area display), *Ring and views*).

## Global Constraints

- `DayPlanKit` imports only `Foundation`; `DayPlanStore` only `Foundation`, `SwiftData`, `DayPlanKit`. Time logic takes `now` + `Calendar`; tests use the Europe/Paris gregorian calendar.
- CloudKit-compatible SwiftData models (defaults/optionals, no `.unique`, optional relationships), `cloudKitDatabase: .none`.
- Spec values:
  - Ending cue **2 min** before a block's end.
  - Unplanned cue for gaps ≥ **15 min**.
  - "Next:" names a block only if it starts within **15 min**.
  - Late cues dropped after **90 s**, with the stage-1 wake rule.
  - Keep going = **+15 min** (`FocusEngine.keepGoingExtension`, the single source), offered in the last **2 min** of a planned Focus or within **2 min** after a takeover.
  - Manual Focus **5…180 min**.
  - No cues for a manual Focus.
  - No cue repeated.
  - Voice is used only for plan boundaries.
- Cue sentences (en / fr), with times in the user's 12/24 h format:
  - Start: "9:00. Writing. Chapter 3." (intention omitted when empty) / "9:00. Écriture. Chapitre 3."
  - Takeover: "14:00. Health. Your Writing focus ends here." / "14:00. Santé. Votre focus Écriture s'arrête ici."
  - Ending: "Writing ends in 2 minutes. Next: Health at 13:00." / "… Next: a meeting." / "… Next: free time." / "Writing ends in 2 minutes. That's the end of today's plan."
    - fr: "Écriture se termine dans 2 minutes. Ensuite : Santé à 13:00." / "… Ensuite : une réunion." / "… Ensuite : du temps libre." / "Écriture se termine dans 2 minutes. C'est la fin du plan de la journée."
  - Resume: "Back to Writing until 12:00." / "Retour à Écriture jusqu'à 12:00."
  - Unplanned: "Unplanned time until 13:00." / "Temps non planifié jusqu'à 13:00."
  - Cues follow the system language: fr when the language code is "fr", else en.
- Speech: `AVSpeechSynthesizer` with the system default voice for the current language. Settings (General tab):
  - "Spoken cues" on/off (default on).
  - Volume.
  - "Use a chime instead of voice" (default off).
- Plan wins over a manual Focus. Stopping a planned Focus does not change the plan, and the next block still starts on time.
- The native app no longer calls `startFocus`, `quickStartFocus`, `closeFocus`, `changePomodoroDuration`, `setFocusIntention`, `setGoalTitle` or `setGoalDeadline` on the Kotlin session. The Kotlin Focus stays idle.
- The active area is the area of the running Focus. With no Focus, no area is active and no goal line is shown.
- UI strings en/fr via the `.strings` files and `L()`/`LF()`.
- Tests: `swift test --package-path macos/Packages/DayPlanKit` passes; the app builds with `xcodegen generate` + `xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -destination 'platform=macOS' build CODE_SIGNING_ALLOWED=NO`. No Kotlin change.
- **Launch checks capture only the DayView window** (`screencapture -l <windowid>`), never the screen or a region; no calendar/email/connector tools; never modify bundle `fr.dayview.app`; quit only the Debug app you launched.
- Commits in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **Relaunch in the middle of the day.** After a stopped block, or with closures pending, the stopped block does not restart, pending closures are still there and the running Focus resumes → test in Task 1 (encode, decode, advance).
2. **Next day.** A template block that ran yesterday starts again today, because block IDs equal template IDs since stage 3 → test in Task 1 (`beginDay`).
3. **A calendar change makes a block "running" during a manual Focus.**
   - The takeover is not retroactive: the manual record ends at the later of the block start and the previous advance.
   - The takeover cue is still spoken once (wake rule) → tests in Tasks 1 and 2.
4. **Keep going next to a meeting or near midnight.**
   - The extension is withheld before a meeting (stage 1).
   - A shifted block pushed past midnight does not reopen the manual Focus.
   - The new plan is what gets saved → tests in Task 1 (`KeepGoing.apply`).
5. **Waking mid-block after sleep.** Exactly one sentence is spoken. It is the takeover wording when a manual Focus was taken over in the same tick → test in Task 2 (`CueComposer`).

## Rulings in this plan (open to correction)

- **Day-scoped engine.** `FocusEngine` forgets started blocks when the day changes. Stage 3 made planned block IDs equal template IDs, so without this a block that ran last week would never start again.
- **Engine state** is saved as JSON in `UserDefaults` (local to this Mac; never synced). **Closed Focus records** go to SwiftData (schema V3), ready for stage 5's weekly summary.
- **Closing ritual.**
  - It is non-blocking: a card above the Focus panel (main and mini windows) with the intention and the three outcomes (Completed / Progressed / Resume later). It shows the oldest pending record.
  - The Kotlin early-exit "detour toll" is not carried over. Detours can still be captured with the Detour button.
  - A menu bar item "Close last focus…" opens the main window.
- **Kotlin-backed banners.** The Kotlin resume ritual and drift banner are removed from the native views, since the Kotlin Focus is idle and they can no longer appear. Drift returns per area in stage 5.
- **Ring.** No Swift Focus arcs are drawn on the main lane yet (stage 5 has the records). The plan lane's running highlight shows the planned Focus. Kotlin Focus arcs and session bands simply stay empty.
- **Main window goal.** The Cap editor in the main window is removed; goals are edited in Settings → Areas. During a Focus the main window shows the active area (colour, name), its goal and a deadline label:
  - "%lld days left" / "Due today" / "%lld days overdue"
  - fr: "%lld jours restants" / "Échéance aujourd'hui" / "%lld jours de retard"
- **Break.** After a manual Focus completes, the Focus panel shows "Break since 10:25" until another Focus starts. The menu bar title shows the remaining Focus time while a Focus runs, and the Kotlin `dayStatus` otherwise.
- **Manual Focus defaults.** The manual Focus area picker defaults to the last used area, and the duration stepper (5-minute steps) to the last used duration. The "5 min" quick start is kept.
- **No auto-accept delay.** The Kotlin session reads the calendar synchronously when it is created, so the first tick already has today's meetings when access is granted. No delay is added.

---

## File Structure

```
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  FocusEngine.swift   (modify) Codable, beginDay, non-retroactive takeover, public keepGoingExtension
  KeepGoing.swift     (new) KeepGoing.apply
  CueText.swift       (new) CueLanguage, CueText, CueComposer
  GoalDeadline.swift  (new) days left
macos/Packages/DayPlanKit/Sources/DayPlanStore/
  Records.swift, Schema.swift, PlanStore.swift (modify) FocusRecordEntry, V3, record API
macos/Packages/DayPlanKit/Tests/... (new test files per task)
macos/DayView/FocusModel.swift       (new) engine driver, persistence, records, Keep going, cue delivery
macos/DayView/CueSpeaker.swift       (new) speech / chime + settings
macos/DayView/FocusPanel.swift       (new) shared Focus panel, closure card, Keep going button, active-area goal
macos/DayView/RingView.swift, MiniView.swift, MenuBarContent.swift, DayViewApp.swift, SettingsView.swift (modify)
macos/DayView/FocusClosureSheet.swift (delete when unused)
macos/DayView/{en,fr}.lproj/Localizable.strings (modify)
```

---

### Task 1: DayPlanKit — persistable, day-scoped engine; non-retroactive takeover; Keep going; deadlines

**Interfaces:**
- Consumes: stage 1–3 `FocusEngine`, `DayPlan.keepingGoing(blockID:by:calendar:)`, `DayPlan.shiftingFrom(blockID:by:calendar:)`, `PlanFitter.fit`, `LocalDay`.
- Produces:
  - `FocusEngine: Codable` (all state, including started blocks, current parts, last takeover and break start; the private takeover tuple becomes a private Codable struct). `public static let keepGoingExtension: TimeInterval = 900` (public; the private duplicate is removed).
  - `public mutating func beginDay(_ day: LocalDay)`: when `day` differs from the stored day, clears started blocks and `lastTakeover`. Current, pending and break state are kept. The engine stores the day (Codable).
  - Non-retroactive takeover: the engine records `lastAdvance: Date?` on every `advance`. In a takeover, `actualEnd = min(max(blockStart, lastAdvance ?? blockStart, manual.start), manual.end)`.
  - `public enum KeepGoing { static func apply(_ offer: KeepGoingOffer, to plan: DayPlan, meetings: [DateInterval], calendar: Calendar) -> (plan: DayPlan, reopenUntil: Date?) }`:
    - `.extendBlock(id)` → `(plan.keepingGoing(blockID: id, by: FocusEngine.keepGoingExtension, …), nil)`.
    - `.reopenInterrupted(_, id)` → `shiftingFrom`, refit; `reopenUntil` = the shifted block's fitted start, or nil when it was dropped or removed.
  - `public enum GoalDeadline { static func daysLeft(from now: Date, to deadline: Date, calendar: Calendar) -> Int }`: whole calendar days between the local days of `now` and `deadline` (negative when overdue, 0 when due today).
- [ ] **Step 1: Failing tests** (`FocusPersistenceTests`, `KeepGoingApplyTests`, `GoalDeadlineTests`):
  - `roundTripMidBlock`: start the planned block at 09:00, stop at 09:30, advance to 09:31, encode/decode, advance to 09:32 → no `.started` event; `pendingClosures.count == 1`.
  - `roundTripRunningManual`: start a 25-min manual Focus at 10:00, encode/decode → `current` is equal, and advancing to 10:25 ends it `.completed`.
  - `beginDayRestartsSameBlockID`:
    - A day-1 plan with block B (template ID) runs to completion.
    - `beginDay(day2)` is called, and a day-2 plan has the same B ID at 09:00 the next day.
    - Advancing to day 2 09:00 → `.started` for B.
    - Without `beginDay` → no start. This documents why the call is needed.
  - `takeoverNotRetroactive`:
    - Manual Focus 10:00–11:00. Advance at 10:20 with a plan that has no block.
    - At 10:21 the plan suddenly has a block 10:00–12:00 → `.tookOver` with `interrupted.actualEnd == 10:20` (the last advance), not 10:00.
    - `focused == 20 min`.
  - `takeoverAtBlockStartUnchanged`: a manual Focus with a block at 14:00 seen at the 14:00 tick (previous advance 13:59:59) → `actualEnd == 14:00` (stage 1 behaviour).
  - `keepGoingExtend`: block 09:00–10:00 followed by 10:00–11:00 → the first ends 10:15 and the second runs 10:15–11:15; `reopenUntil == nil`.
  - `keepGoingReopen`: block 14:00–15:00 → shifted to 14:15–15:15; `reopenUntil == 14:15`. Block 23:30–24:00 → dropped past midnight; `reopenUntil == nil`.
  - `keepGoingReopenAroundMeeting`: block 14:00–15:00 and meeting 14:10–14:30 → shifted 14:15–15:15, refitted first part 14:30–… → `reopenUntil == 14:30`.
  - `daysLeft`:
    - 2026-11-18 10:00 → 2026-11-20 08:00 == 2.
    - Same day 23:00 == 0.
    - 2026-11-17 == −1.
    - Across the 2026-03-29 DST change: 03-28 12:00 → 03-30 12:00 == 2.
- [ ] **Step 2: Run, verify failure.** `swift test --package-path macos/Packages/DayPlanKit` → fails to build.
- [ ] **Step 3: Implement.** Stage 1 engine tests must keep passing unchanged, except tests that relied on a retroactive takeover, if any (report them).
- [ ] **Step 4: Run, verify pass**, with clean output.
- [ ] **Step 5: Commit** `git commit -m "Persist and day-scope the Focus engine, and add Keep going and deadline rules"`

---

### Task 2: DayPlanKit — cue sentences

**Interfaces:**
- Consumes: `ScheduledCue`, `CueKind`, `NextUp`, `FocusEvent`, `AreaID`.
- Produces:
  - `public enum CueLanguage { case en, fr; public init(languageCode: String?) }`: "fr…" → `.fr`, anything else → `.en`.
  - `public struct CueText { public init(language: CueLanguage, areaName: @escaping (AreaID) -> String, time: @escaping (Date) -> String) }` with `public func sentence(for cue: ScheduledCue, takeoverOf interrupted: AreaID?) -> String`. It uses the exact sentences from Global Constraints; the intention is trimmed and its part omitted when empty.
  - `public enum CueComposer { static func sentences(due: [ScheduledCue], events: [FocusEvent], text: CueText) -> [String] }`. A `.blockStart` cue whose block ID matches a `.tookOver(_, by:)` event's planned block (`by.origin == .planned(id)`) in `events` gets the takeover wording, with the interrupted session's area. Order follows `due`.
- [ ] **Step 1: Failing tests** (`CueTextTests`), en and fr, times from a fixed formatter (`"HH:mm"` in Paris):
  - start with an intention;
  - start with a blank intention (no trailing part);
  - takeover;
  - ending with each `NextUp` (`.block` → "Next: Health at 13:00.", `.meeting`, `.freeTime`, `.endOfPlan`);
  - resume;
  - unplanned.
  - `CueLanguage(languageCode:)`: "fr", "fr-CA" → `.fr`; "en", "de", nil → `.en`.
  - `CueComposer`: due `[blockStart(H)]` with event `.tookOver(interrupted: writing manual, by: planned(H))` → the takeover sentence. With no event → the start sentence. Two due cues → two sentences in order.
- [ ] **Step 2–4: Run fail / implement / run pass.**
- [ ] **Step 5: Commit** `git commit -m "Add localized cue sentences"`

---

### Task 3: DayPlanStore — closed Focus records (schema V3)

**Interfaces:**
- Consumes: `FocusRecord`, `ClosureOutcome`, `LocalDay.isoKey`, `Area`.
- Produces:
  - `@Model final class FocusRecordEntry` (defaults everywhere, no unique), with fields:
    - `id: UUID`, `dayKey: String`, `areaID: UUID`, `areaName: String`, `colorIndex: Int`
    - `intention: String`, `start: Date`, `end: Date`, `focusedSeconds: Double`
    - `isPlanned: Bool`, `blockID: UUID?`, `reason: String` ("completed" / "stopped" / "tookOver")
    - `outcome: String` (`ClosureOutcome.rawValue`)
  - `DayPlanSchemaV3` (3.0.0) adds it, and the migration plan gains a lightweight V2→V3 stage. Containers use V3.
  - `public struct ClosedFocus: Sendable, Hashable { id, day: LocalDay, areaID: AreaID, areaName: String, colorIndex: Int, intention: String, start: Date, end: Date, focused: TimeInterval, isPlanned: Bool, outcome: ClosureOutcome }`, in DayPlanStore.
  - `PlanStore.saveClosedFocus(_ record: FocusRecord, outcome: ClosureOutcome, intention: String, area: Area, calendar: Calendar) throws`:
    - Upserts by `record.session.id`.
    - `day` = the local day of `session.start`; `end` = `actualEnd`.
    - Encode/derive everything before mutating, and save through `commit()`.
  - `PlanStore.closedFocus(on day: LocalDay) -> [ClosedFocus]`, sorted by start, fetched with a predicate.
- [ ] **Step 1: Failing tests** (`ClosedFocusTests`):
  - Round trip.
  - Upsert by ID: saving twice → one entry with the second outcome.
  - Day filter.
  - `migratesStage3Store`: open a temp-URL store with `Schema(versionedSchema: DayPlanSchemaV2.self)`, save an area and an accepted plan, release it, reopen via `PlanStore.makeContainer(url:)` → both survive, and `saveClosedFocus` works.
- [ ] **Step 2–4.**
- [ ] **Step 5: Commit** `git commit -m "Store closed Focus records"`

---

### Task 4: App — FocusModel (engine, persistence, Keep going, closures)

**Files:** `macos/DayView/FocusModel.swift` (new); `PlanModel.swift`, `TodayPlanModel.swift`, `DayViewApp.swift` (modify).

**Interfaces:**
- Consumes: Tasks 1–3; `TodayPlanModel` (`state`, and saving a modified accepted plan through `PlanModel.saveAccepted`); `PlanModel.areas`; `MeetingSource`.
- Produces:
  - `@MainActor final class FocusModel: ObservableObject`:
    - `init(todayPlan: TodayPlanModel, plan: PlanModel, meetings: MeetingSource, calendar: Calendar = .autoupdatingCurrent, defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init)`.
    - Published state: `current: FocusSession?`, `pendingClosures: [FocusRecord]`, `breakStartedAt: Date?`, `keepGoing: KeepGoingOffer?`, `lastEvents: [FocusEvent]` (the events of the last tick, for cues).
    - It loads the engine from defaults key `"focus.engineState"` (JSON; corrupt → fresh engine). It saves after every state-changing call.
    - `tick()`, on its own 1-second timer, after `todayPlan` has ticked:
      - `beginDay(state.day)`;
      - `advance(to: now, plan: state.fitted)`;
      - update the published state and `keepGoing` (`keepGoingOffer(now:plan:meetings:)`);
      - save if changed;
      - hand `(due cues, events)` to the cue pipeline (Task 5 plugs in through a `var onCues: (([ScheduledCue], [FocusEvent], TodayPlanState) -> Void)?`). Due cues come from a `CueDelivery` kept in the model, over `CueScheduler.schedule(state.fitted, meetings:)`, recomputed when `state` changes.
    - `startManual(areaID:intention:minutes:)` stores the last area ID and minutes in defaults (`"focus.lastAreaID"`, `"focus.lastMinutes"`). `quickStart(intention:)` = 5 minutes with the last area. `stop()`.
    - `keepGoingNow()`:
      - `KeepGoing.apply(offer, to: state.plan, …)`;
      - save the new plan as accepted (keeping `reviewed`) via a new `TodayPlanModel.replaceAccepted(_ plan: DayPlan) -> Bool`;
      - re-tick `todayPlan`;
      - when `reopenUntil` is non-nil, call `engine.reopenInterrupted(until:)`;
      - save.
    - `close(_ recordID: UUID, outcome: ClosureOutcome, intention: String)` → `engine.close`, then `plan.saveClosedFocus(...)` with the area snapshot from `plan.areas`, using the area's ID and a placeholder name when the area was deleted. The engine is saved.
    - `activeArea: Area?` = the area of `current`.
  - `DayViewApp` creates `FocusModel` with the other models.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + package tests.**
- [ ] **Step 3: Commit** `git commit -m "Run the Focus from Swift with persistence and Keep going"`

---

### Task 5: App — spoken cues and settings

**Files:** `macos/DayView/CueSpeaker.swift` (new); `FocusModel.swift`, `SettingsView.swift`, `{en,fr}.lproj/Localizable.strings` (modify).

**Interfaces:**
- Consumes: Task 2 `CueText`/`CueComposer`/`CueLanguage`; Task 4's `onCues`.
- Produces:
  - `@MainActor final class CueSpeaker: ObservableObject`:
    - `@AppStorage`-backed settings: `"cues.enabled"` (true), `"cues.volume"` (0.8), `"cues.chime"` (false).
    - `func speak(_ sentences: [String])`: nothing when disabled. With chime: one `NSSound(named: "Glass")` play for the batch, at the volume. Otherwise one `AVSpeechUtterance` per sentence, with voice `AVSpeechSynthesisVoice(language: Locale.current.identifier)` (falling back to the language code), the volume set, and queued in order.
  - Wiring: `FocusModel.onCues` builds a `CueText`:
    - language from `Locale.current.language.languageCode?.identifier`;
    - area names from `plan.areas`;
    - time via `Date.FormatStyle` `.dateTime.hour().minute()`.
    - It calls `CueComposer.sentences` and then `speaker.speak`.
  - The General tab gains a "Spoken cues" section:
    - toggle "Spoken cues" / "Annonces vocales";
    - slider "Volume" / "Volume";
    - toggle "Use a chime instead of voice" / "Utiliser un carillon plutôt que la voix";
    - button "Test" / "Tester", which speaks "Back to Writing until 12:00." using the first area's name and the current time + 1 h.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + tests.**
- [ ] **Step 3: Launch check.** Settings → General → Test speaks (report: audible? the subagent can't hear, so report that `AVSpeechSynthesizer` delegate `didFinish` fired, logged with NSLog in a Debug-only check, or that no error was raised). Capture the window only.
- [ ] **Step 4: Commit** `git commit -m "Speak plan cues with voice or chime"`

---

### Task 6: App — Focus UI moves to Swift

**Files:** `macos/DayView/FocusPanel.swift` (new); `RingView.swift`, `MiniView.swift`, `MenuBarContent.swift`, `DayViewApp.swift` (modify); `FocusClosureSheet.swift` (delete if unused); strings.

**Interfaces:**
- Consumes: `FocusModel` (all of Task 4), `PlanModel.areas`, `DayViewPalette.areaColor`, `GoalDeadline.daysLeft`, `TodayModel` (detours, must-dos, ring, history: unchanged).
- Produces:
  - **`FocusPanel`** (main window; a compact variant for the mini window):
    - **Idle:** area picker (last used), intention field, duration stepper 5…180 in steps of 5 (last used), "Start focus" and "5 min" buttons.
    - **Running:** colour dot + area name, intention, remaining clock (`h:mm:ss` / `m:ss`), "Stop focus", and "Keep going (+15 min)" / "Continuer (+15 min)" when `keepGoing != nil`. A planned Focus that is paused for a meeting shows "Paused for a meeting" / "En pause pendant une réunion".
    - **Break:** "Break since %@" / "Pause depuis %@".
    - **Closure card** above the panel when `pendingClosures` is non-empty, showing the oldest record:
      - "Close: Writing · 09:00–12:00" / "Clôturer : Écriture · 09:00–12:00";
      - an intention field;
      - Completed / Progressed / Resume later (reuse the existing keys);
      - each button calls `close(...)`.
  - **Active-area goal:** replaces `goalSection`. Only during a Focus: area name in its colour, goal title, and the deadline label (strings in the Rulings). The ring's `hasGoal` = the active area has a goal title or deadline.
  - **Mini window:** a compact `FocusPanel` and the goal card from the active area ("No goal yet" when an area is active with an empty goal; hidden when no Focus).
  - **Menu bar:**
    - Title: remaining Focus clock while a Focus runs, else `snapshot.dayStatus`.
    - Items: the Focus line (area · intention · clock), "Keep going (+15 min)" when offered, "Stop focus", and "Close last focus…" (opens the main window) when closures are pending.
    - The Kotlin `focusLine` and `goalTimeLabel` items are removed.
  - **Focus menu commands:** Start focus (⌘↩) starts a manual Focus with the panel's current values (last used area/minutes/intention from defaults); Shorter/Longer change the stored duration by ±5 within 5…180; all are disabled while a Focus runs.
  - **Removed from the native app:**
    - calls to `startFocus`, `quickStartFocus`, `closeFocus`, `changePomodoroDuration`, `setFocusIntention`, `setGoalTitle`, `setGoalDeadline`, `dismissResumeRitual`, `dismissDriftReminder`;
    - the `resumeRitual` / `driftBanner` views;
    - their `TodayModel` wrappers (delete unused wrappers);
    - `FocusClosureSheet` if unused.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build + tests.** `grep -n "startFocus\|closeFocus\|changePomodoroDuration\|setGoalTitle\|setGoalDeadline\|quickStartFocus\|setFocusIntention" macos/DayView/*.swift` → no matches.
- [ ] **Step 3: Launch check** (window-only captures):
  - Start a 5-min manual Focus: the panel runs, the menu bar title shows the clock, and the active area's goal shows.
  - Stop it: the closure card appears; Completed closes it.
  - Relaunch during a running manual Focus: it is still running.
  - Report what was seen.
- [ ] **Step 4: Commit** `git commit -m "Move the Focus UI to the Swift engine"`
