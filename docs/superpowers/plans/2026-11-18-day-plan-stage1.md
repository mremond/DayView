# Day Plan — Stage 1 (DayPlanKit domain) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure Swift domain for clock-fixed day plans (template → plan, fitting around meetings, cue schedule and delivery, Focus engine with plan-wins and Keep going), plus a Kotlin export of today's meetings, wired into the native app build without any UI change.

**Architecture:** A new SwiftPM package `macos/Packages/DayPlanKit` holds value types and pure functions only (no UI, no SwiftData, no AVFoundation). Every time-dependent function takes `now: Date` and a `Calendar` parameter. The Kotlin `DayViewSession` gains one method that returns today's meetings over the whole local day; the app maps it to `[DateInterval]` through a `MeetingSource` protocol defined in `DayPlanKit`.

**Tech Stack:** Swift 6 (tools 6.0), Swift Testing (`import Testing`), SwiftPM; Kotlin Multiplatform (`core`, kotlin.test, kotlinx-datetime); XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` (stage 1 of *Delivery stages*).

## Global Constraints

- Package platforms: `.macOS(.v15)`, `.iOS(.v18)`. No dependency outside the Swift toolchain / Foundation.
- `DayPlanKit` imports only `Foundation`. No SwiftUI, SwiftData, AVFoundation, AppKit.
- All instants are `Date`; all local-day logic goes through an injected `Calendar` (with its `timeZone`). Tests use a fixed `Calendar(identifier: .gregorian)` with `TimeZone(identifier: "Europe/Paris")`.
- Spec values: fitting drops parts shorter than **10 min**; ending cue **2 min** before a block's end; unplanned cue only for gaps ≥ **15 min** (meeting time excluded); "Next: block" only if that block starts within **15 min**; late-cue cutoff **90 s**; Keep going = **+15 min**, offered in the last **2 min** of a planned Focus or within **2 min** after a takeover; manual Focus duration clamped to **5…180 min**; blocks pushed past the next local midnight are dropped.
- Kotlin: ktlint clean; `./gradlew ktlintCheck :core:jvmTest` passes. Swift: `swift test --package-path macos/Packages/DayPlanKit` passes.
- Commit messages in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **DST days** (Europe/Paris 2026-03-29, 02:00–03:00 does not exist): a template block at 02:30 must land on the next valid time, not crash or land on the wrong day → test in Task 3.
2. **App launched mid-block / after sleep**: exactly one start cue for the current block, no burst of stale cues → test in Task 5.
3. **Block entirely covered by a meeting**: dropped, no Focus, no cues, no "Next:" pointing at it → tests in Tasks 3 and 4.
4. **Back-to-back blocks**: ending cue of A says "Next: B", B's Focus starts at the same instant A's ends, A's closing ritual is queued (not blocking) → tests in Tasks 4 and 6.
5. **User stops a planned Focus early**: the same block does not restart on the next tick; the next block still starts → test in Task 6.

---

## File Structure

```
core/src/commonMain/kotlin/fr/dayview/app/DayMeetings.kt          (new) full-day meeting probe
core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt       (modify) cache + expose meetings
core/src/commonTest/kotlin/fr/dayview/app/DayMeetingsTest.kt      (new)

macos/Packages/DayPlanKit/Package.swift
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  Identifiers.swift      AreaID, BlockID
  LocalDay.swift         LocalDay + start/next-midnight helpers
  PlanModel.swift        TemplateBlock, PlannedBlock, DayPlan (+ instantiate, keepingGoing, shiftingFrom)
  PlanFitter.swift       FittedBlock, PlanFitter
  Cues.swift             CueKind, NextUp, ScheduledCue, CueScheduler.schedule
  CueDelivery.swift      CueDelivery (lateness, wake rule, no repeats)
  FocusEngine.swift      FocusSession, FocusRecord, FocusEvent, KeepGoingOffer, FocusEngine
  MeetingSource.swift    MeetingSource protocol
macos/Packages/DayPlanKit/Tests/DayPlanKitTests/
  Fixtures.swift, PlanModelTests.swift, PlanFitterTests.swift, CueSchedulerTests.swift,
  CueDeliveryTests.swift, FocusEngineTests.swift, KeepGoingTests.swift
macos/project.yml                                                  (modify) add package
macos/DayView/SessionMeetingSource.swift                           (new) adapter over DayViewSession
macos/DayView/TodayModel.swift                                     (modify) expose meetingSource
.claude/CLAUDE.md                                                  (modify) add swift test command
```

---

### Task 1: Kotlin — today's meetings over the whole local day

**Files:**
- Create: `core/src/commonMain/kotlin/fr/dayview/app/DayMeetings.kt`
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/DayViewSession.kt` (`refreshCalendar()` at ~line 98; new field + method)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DayMeetingsTest.kt`

**Interfaces:**
- Produces:
  - `fun localDayBounds(now: Instant, timeZone: TimeZone): Pair<Instant, Instant>` — local midnight of `now`'s day and the next local midnight (`LocalDate.atStartOfDayIn`).
  - `fun probeDayMeetings(source: CalendarSource, includedCalendarIds: Set<String>, now: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<BusyInterval>` — empty when `hasPermission()` is false or throws, or `busyIntervals` throws; otherwise `mergeBusyIntervals(source.busyIntervals(dayStart, dayEnd, ids).filterNot { it.isFocusBlock() })`. Does **not** look at `NetTimeSettings.enabled`.
  - `DayViewSession.dayMeetingsEpochMillis(): LongArray` — flat `[start0, end0, start1, end1, …]` of the last probe (primitives-only facade, like the other session methods).

- [ ] **Step 1: Write failing tests** in `DayMeetingsTest.kt` (reuse the `FakeCalendarSource` shape from `NetTimeProbeTest.kt`, recording the window it was asked for):
  - `queriesWholeLocalDayRegardlessOfNetTime`: now = 2026-11-18T10:00 Europe/Paris → asked window is `2026-11-17T23:00Z … 2026-11-18T23:00Z`.
  - `excludesFocusTitledEventsAndMerges`: busy = [09:00–10:00 "Standup", 09:30–10:30 "Review", 14:00–15:00 "Focus writing"] → result = one interval 09:00–10:30.
  - `emptyWithoutPermission` and `emptyWhenReadThrows`.
  - `localDayBoundsOnDstSpringDay`: 2026-03-29 Paris → bounds are 23 hours apart.
  - In `DayViewSessionTest.kt`: `dayMeetingsExposedEvenWhenNetTimeDisabled` — session with a fake source granting permission and one meeting, net time disabled → `dayMeetingsEpochMillis()` has size 2 with that meeting's millis.
- [ ] **Step 2: Run** `./gradlew :core:jvmTest --tests '*DayMeetings*' --tests '*DayViewSessionTest*'` → FAIL (unresolved references).
- [ ] **Step 3: Implement** `DayMeetings.kt` and, in `DayViewSession`, a `private var dayMeetings: List<BusyInterval> = emptyList()` refreshed at the end of `refreshCalendar()` with `probeDayMeetings(calendarSource, state.netTimeSettings.includedCalendarIds, state.now)`, plus `dayMeetingsEpochMillis()`.
- [ ] **Step 4: Run** `./gradlew ktlintCheck :core:jvmTest` → PASS.
- [ ] **Step 5: Rebuild the framework** `./gradlew :core:syncXCFramework` → succeeds; `macos/Packages/DayViewKit/DayViewKit.xcframework` updated.
- [ ] **Step 6: Commit** `git commit -m "Expose today's full-day meetings to the native app"`

---

### Task 2: DayPlanKit package, identifiers, LocalDay, plan model

**Files:**
- Create: `macos/Packages/DayPlanKit/Package.swift`, `Sources/DayPlanKit/{Identifiers,LocalDay,PlanModel,MeetingSource}.swift`, `Tests/DayPlanKitTests/{Fixtures,PlanModelTests}.swift`
- Modify: `.claude/CLAUDE.md` — add `swift test --package-path macos/Packages/DayPlanKit` to the *Tests and lint* command block.

**Interfaces:**
- Produces (all `public`, `Sendable`, `Hashable`, `Codable` where value types):
  - `struct AreaID { let raw: UUID }`, `struct BlockID { let raw: UUID }`, each with `init(_ raw: UUID = UUID())`.
  - `struct LocalDay { let year: Int; let month: Int; let day: Int }` with `init(containing: Date, calendar: Calendar)`, `func start(in: Calendar) -> Date`, `func nextMidnight(in: Calendar) -> Date`, `func weekdayISO(in: Calendar) -> Int` (1 = Monday … 7 = Sunday).
  - `struct TemplateBlock { id: BlockID; weekdayISO: Int; startMinute: Int; endMinute: Int; areaID: AreaID; intention: String }` (minutes from local midnight, 0…1440).
  - `struct PlannedBlock { id: BlockID; start: Date; end: Date; areaID: AreaID; intention: String }`.
  - `struct DayPlan { day: LocalDay; blocks: [PlannedBlock] }` (blocks always sorted by `start`), with:
    - `static func instantiate(template: [TemplateBlock], day: LocalDay, calendar: Calendar) -> DayPlan` — keeps blocks of `day.weekdayISO`; each gets a **new** `BlockID`; times via `calendar.date(bySettingHour:minute:second:of:matchingPolicy: .nextTime)`; `endMinute == 1440` maps to `nextMidnight`.
    - `func keepingGoing(blockID: BlockID, by: TimeInterval, calendar: Calendar) -> DayPlan` — extends that block's `end` by `by` and shifts every block with `start >= ` the block's *old* `end` by `by`.
    - `func shiftingFrom(blockID: BlockID, by: TimeInterval, calendar: Calendar) -> DayPlan` — shifts that block and every later block by `by`.
    - Both drop any block whose shifted `end` is after `day.nextMidnight(in:)`.
  - `protocol MeetingSource { func meetings(on day: LocalDay) -> [DateInterval] }`.
  - Test fixtures: `let paris: Calendar`, `func at(_ hhmm: String, on: LocalDay = .nov18) -> Date`, `extension LocalDay { static let nov18 = LocalDay(year: 2026, month: 11, day: 18) }` (a Wednesday).

- [ ] **Step 1: Write failing tests** in `PlanModelTests.swift`:
  - `instantiateKeepsOnlyTodaysWeekday`: template Wed 09:00–12:00 Writing + Thu 09:00–10:00 → plan for `.nov18` has 1 block, `start == at("09:00")`, `end == at("12:00")`.
  - `instantiateEndOfDay`: Wed 22:00–24:00 → `end == LocalDay.nov18.nextMidnight(in: paris)`.
  - `instantiateOnDstGap`: Sun 2026-03-29 block 02:30–04:00 → `start` is 03:00 local, `end` 04:00.
  - `keepingGoingExtendsAndShiftsLater`: blocks A 09–10, B 10–11, C 14–15 → keepingGoing(A, 15 min) gives A 09:00–10:15, B 10:15–11:15, C 14:15–15:15.
  - `keepingGoingDropsPastMidnight`: A 22:00–23:50, B 23:50–24:00 → keepingGoing(A, 15 min) drops B.
  - `shiftingFromMovesBlockAndLater`: A 09–10, B 10–11 → shiftingFrom(B, 15 min) leaves A, B = 10:15–11:15.
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit` → FAIL (types missing).
- [ ] **Step 3: Implement** the files listed above.
- [ ] **Step 4: Run** `swift test --package-path macos/Packages/DayPlanKit` → PASS.
- [ ] **Step 5: Commit** `git commit -m "Add DayPlanKit package with day plan model"`

---

### Task 3: PlanFitter — cut blocks around meetings

**Files:**
- Create: `Sources/DayPlanKit/PlanFitter.swift`, `Tests/DayPlanKitTests/PlanFitterTests.swift`

**Interfaces:**
- Consumes: `DayPlan`, `PlannedBlock` (Task 2).
- Produces:
  - `enum FitNote: Hashable, Sendable { case splitAround(DateInterval); case trimmed; case dropped }`
  - `struct FittedBlock: Hashable, Sendable { let block: PlannedBlock; let parts: [DateInterval]; let notes: [FitNote] }` with `var isDropped: Bool { parts.isEmpty }`, `var start: Date?` (first part start), `var end: Date?` (last part end), `func isRunning(at: Date) -> Bool` (`start <= now < end`), `func isPaused(at: Date) -> Bool` (running and inside no part).
  - `enum PlanFitter { static func fit(_ plan: DayPlan, meetings: [DateInterval], minimumPart: TimeInterval = 600) -> [FittedBlock] }` — one `FittedBlock` per plan block, same order, dropped ones included.

Algorithm: merge overlapping/touching meetings; for each block subtract the merged meetings from `[start, end)`; discard pieces `< minimumPart`; note `splitAround(meeting)` for each meeting strictly inside the block with pieces kept on both sides, `trimmed` when a meeting cuts the start or end (or a short piece was discarded), `dropped` when no piece remains.

- [ ] **Step 1: Write failing tests**:
  - `noMeetingsKeepsBlock`: one part = the block, no notes.
  - `meetingInsideSplits`: Writing 09:00–12:00, meeting 10:00–10:30 → parts [09:00–10:00, 10:30–12:00], notes == [.splitAround(10:00–10:30)].
  - `meetingAtEdgeTrims`: 09:00–12:00, meeting 11:30–13:00 → parts [09:00–11:30], notes == [.trimmed].
  - `shortPieceDropped`: 09:00–10:00, meeting 09:05–10:00 → `isDropped`, notes contain `.dropped`.
  - `fullyCoveredDropped`: 09:00–10:00, meeting 08:00–11:00 → `isDropped`.
  - `overlappingMeetingsMergedFirst`: meetings 10:00–10:30 and 10:20–10:45 → parts [09:00–10:00, 10:45–12:00].
  - `pausedBetweenParts`: for the split block, `isRunning(at: 10:15)` true and `isPaused(at: 10:15)` true; `isPaused(at: 09:30)` false.
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit --filter PlanFitterTests` → FAIL.
- [ ] **Step 3: Implement** `PlanFitter.swift`.
- [ ] **Step 4: Run** the full package tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Fit day plan blocks around meetings"`

---

### Task 4: CueScheduler — the day's cue list

**Files:**
- Create: `Sources/DayPlanKit/Cues.swift`, `Tests/DayPlanKitTests/CueSchedulerTests.swift`

**Interfaces:**
- Consumes: `FittedBlock` (Task 3).
- Produces:
  - `enum NextUp: Hashable, Sendable { case block(areaID: AreaID, start: Date); case meeting; case freeTime; case endOfPlan }`
  - `enum CueKind: Hashable, Sendable { case blockStart; case blockEnding(next: NextUp); case resume(until: Date); case unplanned(until: Date) }`
  - `struct ScheduledCue: Hashable, Sendable { let at: Date; let kind: CueKind; let block: PlannedBlock? }` (`block` nil only for `.unplanned`).
  - `enum CueScheduler { static func schedule(_ fitted: [FittedBlock], meetings: [DateInterval], endingLead: TimeInterval = 120, gapThreshold: TimeInterval = 900, nextBlockHorizon: TimeInterval = 900) -> [ScheduledCue] }` — sorted by `at`, dropped blocks ignored.

Rules (from the spec): per kept block, `.blockStart` at `start`; `.resume(until: end)` at each part start after the first; `.blockEnding` at `end - endingLead`. `NextUp` for block A with next kept block B: a meeting starting in `[A.end, B.start)` → `.meeting`; else `B.start - A.end <= nextBlockHorizon` → `.block`; else `.freeTime`; no B → `.endOfPlan` (a meeting after the last block does not change `.endOfPlan`). `.unplanned(until: B.start)` between consecutive kept blocks when the gap minus meeting time is `>= gapThreshold`, at the first instant after `A.end` not inside a meeting. No unplanned cue before the first block or after the last.

- [ ] **Step 1: Write failing tests** (all times on `.nov18`):
  - `backToBackBlocks`: A 09–10 Writing, B 10–11 Health → [start A 09:00, ending A 09:58 next .block(Health, 10:00), start B 10:00, ending B 10:58 next .endOfPlan].
  - `splitBlockHasResumeNotEarlyEnding`: Writing 09–12 split by meeting 10:00–10:30 → [start 09:00, resume(until 12:00) at 10:30, ending 11:58]; no cue at 09:58.
  - `meetingBeforeNextBlock`: A 09–10, meeting 10:00–10:30, B 10:30–11:00 → A's ending next == `.meeting`; no unplanned cue.
  - `gapCountsWithoutMeetingTime`: A 09–10, meeting 10:00–10:50, B 11:00–12:00 → free gap 10 min → no unplanned; A next == `.meeting`.
  - `longGapUnplanned`: A 09–10, B 13–14 → A next == `.freeTime`; `.unplanned(until 13:00)` at 10:00.
  - `droppedBlockIgnored`: A 09–10, B dropped, C 10:05–11 → A next == `.block(C.area, 10:05)`.
  - `noCueInsideMeetings` (property over all fixtures above): no `at` lies strictly inside a merged meeting.
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit --filter CueSchedulerTests` → FAIL.
- [ ] **Step 3: Implement** `Cues.swift`.
- [ ] **Step 4: Run** full package tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Schedule spoken cues from the fitted day plan"`

---

### Task 5: CueDelivery — what to speak now

**Files:**
- Create: `Sources/DayPlanKit/CueDelivery.swift`, `Tests/DayPlanKitTests/CueDeliveryTests.swift`

**Interfaces:**
- Consumes: `ScheduledCue`, `FittedBlock`.
- Produces: `struct CueDelivery: Sendable { init(maxLateness: TimeInterval = 90); mutating func due(_ schedule: [ScheduledCue], fitted: [FittedBlock], now: Date) -> [ScheduledCue] }`.

Rules: keeps `lastObserved: Date?` and a set of already-delivered cues (`ScheduledCue` is `Hashable`). Candidates: cues with `lastObserved < at <= now` (all `at <= now` when `lastObserved` is nil). Deliver candidates with `now - at <= maxLateness`, in order. **Wake rule:** if a block is running at `now` and no delivered cue this call belongs to it, and the latest candidate `.blockStart`/`.resume` for that block was dropped as late, deliver that one cue. Never deliver a cue already in the delivered set. `now <= lastObserved` (clock went back) delivers nothing.

- [ ] **Step 1: Write failing tests**:
  - `deliversOnCrossing`: schedule start A 09:00 → `due(now: 08:59:59)` = [], `due(now: 09:00:01)` = [start A], `due(now: 09:00:02)` = [].
  - `dropsLateCue`: lastObserved 09:00:00, ending cue at 09:58, `now` = 10:30 with no block running → [].
  - `launchMidBlockSpeaksStartOnce`: first call at 10:15 inside A 09–12 → [start A]; next call 10:15:01 → [].
  - `wakeAfterResumePrefersResume`: A split, resume at 10:30; lastObserved 09:10, now 10:45 → [resume A].
  - `noBurstAfterSleep`: lastObserved 08:00, now 13:00 inside C with A, B finished → exactly [start C].
  - `clockBackwardsDeliversNothing`.
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit --filter CueDeliveryTests` → FAIL.
- [ ] **Step 3: Implement** `CueDelivery.swift`.
- [ ] **Step 4: Run** full package tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Deliver due cues with lateness and wake rules"`

---

### Task 6: FocusEngine — planned and manual Focus, plan wins

**Files:**
- Create: `Sources/DayPlanKit/FocusEngine.swift`, `Tests/DayPlanKitTests/FocusEngineTests.swift`

**Interfaces:**
- Consumes: `FittedBlock`, `AreaID`, `BlockID`.
- Produces:
  - `enum FocusOrigin: Hashable, Codable, Sendable { case planned(BlockID); case manual }`
  - `struct FocusSession: Hashable, Codable, Sendable { let id: UUID; let areaID: AreaID; let intention: String; let origin: FocusOrigin; let start: Date; var end: Date }`
  - `enum FocusEndReason: Hashable, Codable, Sendable { case completed, stopped, tookOver }`
  - `struct FocusRecord: Hashable, Codable, Sendable { let session: FocusSession; let actualEnd: Date; let focused: TimeInterval; let reason: FocusEndReason }` — `focused` excludes paused time (time outside the block's parts) for planned sessions.
  - `enum FocusEvent: Hashable, Sendable { case started(FocusSession); case tookOver(interrupted: FocusRecord, by: FocusSession); case ended(FocusRecord); case reopened(FocusSession, discardedRecordID: UUID) }`
  - `enum ClosureOutcome: String, Codable, Sendable { case completed, progressed, toResume }`
  - `struct FocusEngine: Sendable` with
    - `private(set) var current: FocusSession?`
    - `private(set) var pendingClosures: [FocusRecord]` (closing ritual queue; appended for every `.ended` and `.tookOver`)
    - `private(set) var breakStartedAt: Date?` (set when a manual Focus completes; cleared when any Focus starts)
    - `mutating func advance(to now: Date, plan: [FittedBlock]) -> [FocusEvent]`
    - `mutating func startManual(areaID: AreaID, intention: String, minutes: Int, now: Date) -> [FocusEvent]` (minutes clamped 5…180; ends any running Focus as `.stopped` first)
    - `mutating func stop(now: Date, plan: [FittedBlock]) -> [FocusEvent]`
    - `mutating func close(recordID: UUID) -> FocusRecord?` (removes from `pendingClosures`; outcome is persisted by the caller)

`advance` rules: running block R = first kept block with `isRunning(at: now)` whose id has not been started before (engine keeps `startedBlockIDs`) or is the current planned one. A current planned session takes `end` = its block's current `end`; it ends `.completed` at block end when `now >= end`, or `.stopped` at `now` if its block left the plan. A manual session ends `.completed` at its `end` when `now >= end` (sets `breakStartedAt`). If R exists and is not current: a running manual Focus ends `.tookOver` at `now` (event `.tookOver`), then the planned session starts with `start = now` (so a launch mid-block counts only observed time). Order of events within one call: endings before starts. A record's identity is its `session.id` (used by `close(recordID:)` and `.reopened`).

- [ ] **Step 1: Write failing tests**:
  - `plannedStartsAndEnds`: A 09–10 → advance 09:00 → [.started(planned A, area A)]; advance 10:00 → [.ended(reason .completed, actualEnd 10:00, focused 3600)].
  - `backToBack`: A 09–10, B 10–11 → advance 10:00 → [.ended(A), .started(B)]; `pendingClosures.count == 1`.
  - `splitBlockFocusedExcludesMeeting`: A 09–12 split by 10:00–10:30 → ended record `focused == 9000`.
  - `manualThenBreak`: startManual 25 min at 14:00 → advance 14:25 → .ended(.completed); `breakStartedAt == 14:25`.
  - `manualClampsDuration`: minutes 2 → end = start + 5 min; minutes 500 → +180 min.
  - `planWinsOverManual`: manual 13:30 for 60 min, block Health 14:00–15:00 → advance 14:00 → [.tookOver(interrupted reason .tookOver actualEnd 14:00, by planned Health)].
  - `stoppedBlockDoesNotRestart`: A 09–10, B 10–11 → advance 09:00, stop 09:20, advance 09:21 → [], `current == nil`; advance 10:00 → [.started(B)].
  - `launchMidBlockStartsNow`: first advance 10:15 in A 09–12 → started session `start == 10:15`.
  - `blockRemovedEndsAsStopped`: started A; advance with A absent → .ended(reason .stopped).
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit --filter FocusEngineTests` → FAIL.
- [ ] **Step 3: Implement** `FocusEngine.swift`.
- [ ] **Step 4: Run** full package tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Add Focus engine for planned and manual sessions"`

---

### Task 7: Keep going — extend a block, or reopen after a takeover

**Files:**
- Modify: `Sources/DayPlanKit/FocusEngine.swift`
- Test: `Tests/DayPlanKitTests/KeepGoingTests.swift`

**Interfaces:**
- Consumes: `FocusEngine` (Task 6), `DayPlan.keepingGoing` / `shiftingFrom` (Task 2).
- Produces:
  - `enum KeepGoingOffer: Hashable, Sendable { case extendBlock(BlockID); case reopenInterrupted(FocusRecord, blockID: BlockID) }`
  - `func keepGoingOffer(now: Date, plan: [FittedBlock], window: TimeInterval = 120) -> KeepGoingOffer?` — `.extendBlock` when the current planned session's block ends within `window` (and `now < end`); `.reopenInterrupted` when the last takeover happened within `window` and its planned session is still current (takes precedence).
  - `mutating func reopenInterrupted(until: Date, now: Date) -> [FocusEvent]` — discards the current planned session without a record, removes its block from `startedBlockIDs`, removes the interrupted record from `pendingClosures`, and makes the interrupted session current again with `end = until`; returns `[.reopened(session, discardedRecordID:)]`.
  - Caller contract (documented on the offer type): for `.extendBlock(id)` apply `plan.keepingGoing(blockID: id, by: 900, calendar:)`; for `.reopenInterrupted(_, blockID)` apply `plan.shiftingFrom(blockID:, by: 900, calendar:)`, refit, then call `reopenInterrupted(until:)` with the shifted block's new start.

- [ ] **Step 1: Write failing tests**:
  - `offerInLastTwoMinutes`: A 09–10 running → offer at 09:57 == nil; at 09:58 == `.extendBlock(A)`.
  - `extendedBlockKeepsSameSession`: apply `keepingGoing(A, 900)`, refit, advance 10:00 → [] and `current.end == 10:15`; advance 10:15 → .ended.
  - `reopenAfterTakeover`: manual 13:30–14:30, Health 14:00–15:00 → advance 14:00 (takeover); offer at 14:01 == `.reopenInterrupted(record, Health)`; shift plan from Health by 900, refit, `reopenInterrupted(until: 14:15, now: 14:01)` → [.reopened(manual, discardedRecordID: record.session.id)], `pendingClosures` empty, `current.origin == .manual`; advance 14:15 → [.tookOver(…, by Health starting 14:15)].
  - `noReopenAfterWindow`: offer at 14:03 after a 14:00 takeover == nil (or `.extendBlock` only if in Health's last 2 min).
- [ ] **Step 2: Run** `swift test --package-path macos/Packages/DayPlanKit --filter KeepGoingTests` → FAIL.
- [ ] **Step 3: Implement** in `FocusEngine.swift`.
- [ ] **Step 4: Run** full package tests → PASS.
- [ ] **Step 5: Commit** `git commit -m "Support Keep going for planned blocks and takeovers"`

---

### Task 8: Wire DayPlanKit and the meeting adapter into the native app

**Files:**
- Modify: `macos/project.yml` (add `DayPlanKit: path: Packages/DayPlanKit` under `packages`, and the `DayPlanKit` product to the `DayView` target dependencies)
- Create: `macos/DayView/SessionMeetingSource.swift`
- Modify: `macos/DayView/TodayModel.swift` (expose `var meetingSource: MeetingSource`)

**Interfaces:**
- Consumes: `MeetingSource`, `LocalDay` (Task 2); `DayViewSession.dayMeetingsEpochMillis()` (Task 1, seen in Swift as returning `KotlinLongArray`).
- Produces: `final class SessionMeetingSource: MeetingSource` — `init(session: DayViewSession, calendar: Calendar = .current)`; converts pairs to `DateInterval`, keeps only intervals intersecting `day`, clips them to `[day.start, day.nextMidnight)`.

- [ ] **Step 1: Implement** the adapter and the `TodayModel` property (no UI uses it yet; the conversion is small enough to verify by build, the clipping rule is covered once stage 3 uses it).
- [ ] **Step 2: Build** `./gradlew :core:syncXCFramework && (cd macos && xcodegen generate && xcodebuild -project DayView.xcodeproj -scheme DayView -configuration Debug -derivedDataPath build build)` → `** BUILD SUCCEEDED **`.
- [ ] **Step 3: Run all checks** `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest && swift test --package-path macos/Packages/DayPlanKit` → all PASS, no stderr errors.
- [ ] **Step 4: Commit** `git commit -m "Link DayPlanKit into the native macOS app"`
