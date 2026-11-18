# Day Plan — Stage 3 (morning check, plan band on the ring) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each day, show a morning check that lays today's template around today's meetings and lets the user adjust and accept it (or auto-accept it at the first block start), store the accepted plan, and draw today's plan as area-coloured segments on the main ring.

**Architecture:** Pure rules go into `DayPlanKit`: converting between minute-of-day blocks and a dated plan, today's plan state, auto-accept, morning-check policy, the review summary and ring arcs. `DayPlanStore` gains an accepted-plan record behind a versioned schema. The app adds one `TodayPlanModel`, which recomputes today's fitted plan on a 1-second timer. The block editor is extracted from the Week tab so the morning check can reuse it, and the main ring draws a plan lane.

**Tech Stack:** Swift 6 (tools 6.0), SwiftData, Swift Testing, SwiftUI (macOS 15), XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` (stage 3 of *Delivery stages*; sections *Morning check*, *Fitting rules*, *Ring and views*, *Follow-through signals* — only the "plan reviewed" signal).

## Global Constraints

- Package platforms `.macOS(.v15)`, `.iOS(.v18)`. `DayPlanKit` imports only `Foundation`; `DayPlanStore` imports `Foundation`, `SwiftData`, `DayPlanKit` only.
- **CloudKit-compatible SwiftData models**: every stored property optional or defaulted, no `@Attribute(.unique)`, relationships optional; local store `cloudKitDatabase: .none`.
- All time logic takes `now` and a `Calendar` parameter; tests use `Calendar(identifier: .gregorian)` with `TimeZone(identifier: "Europe/Paris")`.
- Fitting rules are stage 1's `PlanFitter.fit` unchanged: parts shorter than **10 min** dropped; blocks never moved or merged.
- **Accepted plan stored unfitted**; fitting is applied live against current meetings. Editing the template affects only days not yet accepted.
- **Skipping:** not accepted by the first (non-dropped) block start → accepted automatically at that moment, counted as *plan not reviewed*.
- A weekday with no blocks → the morning check says "No plan for today", with Adjust available.
- Morning check shown as a sheet over the main window the first time the main window is shown on a day whose plan is not accepted, and on demand from the menu bar ("Plan today…").
- Ring: plan drawn as area-coloured segments on a lane distinct from the busy lane; the running block highlighted; hover shows "Writing · 09:00–12:00 · Chapter 3" (area · time range · intention, the intention part omitted when empty).
- UI strings in English and French via the existing `Localizable.strings` and `L()` / `LF()`; times in the user's 12/24 h format.
- Tests: `swift test --package-path macos/Packages/DayPlanKit` passes; app builds with `xcodegen generate` + `xcodebuild -project macos/DayView.xcodeproj -scheme DayView -configuration Debug -destination 'platform=macOS' build CODE_SIGNING_ALLOWED=NO`. No Kotlin change.
- Commit messages in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **App opened after the first block has started, plan never accepted**: the plan is accepted automatically on the first tick, `reviewed == false`, the morning check is not shown automatically (it is accepted) → tests in Task 1 (`autoAcceptDue`, `MorningCheckPolicy`).
2. **Meeting added or cancelled after acceptance**: the stored accepted plan is unchanged; the fitted plan and ring reshape → test in Task 1 (`TodayPlanner.state` with accepted plan + different meetings).
3. **Template edited after acceptance**: today keeps the accepted blocks; tomorrow uses the new template → test in Task 1.
4. **Midnight passes with the app open**: the new day gets its own proposal, and the morning check is eligible again on the next window show → tests in Task 1 (`MorningCheckPolicy`) and Task 3 (model uses `LocalDay(containing: now)` on every tick).
5. **Blocks outside the DayView day window, and DST days**: arcs are clipped to the window; blocks fully outside are not drawn; a 02:30 block on 2026-03-29 converts to minutes and back without shifting days → tests in Task 1.

## Rulings in this plan (open to correction)

- The morning check sheet has a third action, **Not now** (also Escape), which closes it. It does not reopen automatically that day; "Plan today…" reopens it. Without it the sheet would block the main window until accepted.
- Once the plan is accepted (by the user or automatically), the sheet's primary button is **Done**: it saves edits and never changes `reviewed`.
- After acceptance, blocks that have already ended are shown read-only in the editor. Other blocks, and new ones, are editable.
- The follow-through line shows only the "plan reviewed" count ("Last 7 days: plan reviewed 5/7 days"). Ignored cues are added in stage 5 with that signal. The 7 days are the 7 days before today. The line is hidden until at least one of those days has a stored plan.
- `DayPlan.instantiate` now gives each planned block its template block's ID, so the proposal keeps stable IDs across ticks. A template block occurs once per day, so IDs stay unique within a day.
- Plan lane on the **main ring only** (the spec says "Main ring"); the mini window is unchanged.
- The plan lane sits inside the busy lane (radius `radius - lineWidth * 1.75`), thinner than the busy lane.
- The morning check is only for today. A plan accepted for a past day is never changed.

---

## File Structure

```
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  PlanModel.swift        (modify) instantiate keeps template IDs; DayPlan <-> minute blocks
  TodayPlanner.swift     (new) AcceptedPlan, TodayPlanState, TodayPlanner, MorningCheckPolicy, ReviewSummary
  PlanRing.swift         (new) PlanArc, PlanRing (arcs + hit test)
macos/Packages/DayPlanKit/Sources/DayPlanStore/
  Records.swift          (modify) AcceptedPlanRecord; versioned schema
  Schema.swift           (new) DayPlanSchemaV1, DayPlanSchemaV2, DayPlanMigrationPlan
  PlanStore.swift        (modify) accepted-plan API; containers use the migration plan
macos/Packages/DayPlanKit/Tests/DayPlanKitTests/
  DayMinutesTests.swift, TodayPlannerTests.swift, PlanRingTests.swift   (new)
  PlanModelTests.swift   (modify if needed for ID preservation)
macos/Packages/DayPlanKit/Tests/DayPlanStoreTests/AcceptedPlanTests.swift (new)
macos/DayView/PlanModel.swift          (modify) accepted-plan pass-throughs
macos/DayView/TodayPlanModel.swift     (new) today's state, tick, auto-accept, sheet presentation
macos/DayView/BlockListEditor.swift    (new) extracted from WeekSettingsView
macos/DayView/WeekSettingsView.swift   (modify) uses BlockListEditor
macos/DayView/MorningCheckSheet.swift  (new)
macos/DayView/DayViewApp.swift, MenuBarContent.swift, RingView.swift, DayRingCanvas.swift (modify)
macos/DayView/{en,fr}.lproj/Localizable.strings (modify)
```

---

### Task 1: DayPlanKit — today's plan rules and ring arcs

**Files:** see File Structure, `DayPlanKit` lines.

**Interfaces:**
- Consumes: stage 1 `DayPlan`, `PlannedBlock`, `TemplateBlock`, `LocalDay`, `PlanFitter.fit`, `FittedBlock` (`parts`, `start`, `end`, `isDropped`, `isRunning(at:)`, `isPaused(at:)`, `notes`), `AreaID`, `BlockID`.
- Produces:
  - `DayPlan.instantiate(template:day:calendar:)`: same behaviour, but `PlannedBlock.id == TemplateBlock.id`.
  - `public static func DayPlan.fromMinuteBlocks(_ blocks: [TemplateBlock], day: LocalDay, calendar: Calendar) -> DayPlan`: keeps IDs, areas and intentions. Minute → instant uses the same rule as `instantiate` (`.nextTime` across a DST gap; 1440 → next midnight). Ignores `weekdayISO`. `instantiate` becomes "filter by weekday, then `fromMinuteBlocks`".
  - `public func DayPlan.minuteBlocks(calendar: Calendar) -> [TemplateBlock]`: the inverse, for editing. `weekdayISO = day.weekdayISO(in:)`; minutes from wall-clock hour and minute; an end at the next midnight → 1440.
  - `public struct AcceptedPlan: Sendable, Hashable, Codable { public var plan: DayPlan; public var reviewed: Bool }`
  - `public struct TodayPlanState: Sendable, Hashable { public let day: LocalDay; public let plan: DayPlan; public let accepted: AcceptedPlan?; public let fitted: [FittedBlock] }`, plus `var isAccepted: Bool` and `var firstStart: Date?` (earliest start among non-dropped fitted blocks).
  - `public enum TodayPlanner`:
    - `static func state(day: LocalDay, accepted: AcceptedPlan?, template: [TemplateBlock], meetings: [DateInterval], calendar: Calendar) -> TodayPlanState`. `plan = accepted?.plan ?? DayPlan.instantiate(template:day:calendar:)`; `fitted = PlanFitter.fit(plan, meetings:)`.
    - `static func autoAcceptDue(_ state: TodayPlanState, now: Date) -> Bool`: `!isAccepted && firstStart != nil && now >= firstStart`.
  - `public enum MorningCheckPolicy { static func shouldPresentOnWindowShow(today: LocalDay, lastShown: LocalDay?, isAccepted: Bool) -> Bool }`: `!isAccepted && lastShown != today`.
  - `public enum ReviewSummary { static func lastSevenDays(before today: LocalDay, history: [LocalDay: Bool], calendar: Calendar) -> (reviewed: Int, days: Int)? }`: looks at the 7 days before `today`. Returns nil when none of them is in `history`. Otherwise `reviewed` = count of `true` among them, and `days = 7`.
  - `public struct PlanArc: Sendable, Hashable { public let blockID: BlockID; public let areaID: AreaID; public let startAngleDegrees: Double; public let sweepDegrees: Double }`
  - `public enum PlanRing`:
    - `static func arcs(_ fitted: [FittedBlock], windowStart: Date, windowEnd: Date) -> [PlanArc]`: one arc per fitted part, clipped to the window. Parts fully outside are skipped. Angle convention as the Kotlin ring: −90° at the window start, clockwise, `360 * fraction` of the window.
    - `static func arcIndex(at angleDegrees: Double, in arcs: [PlanArc], tolerance: Double = 4) -> Int?`: same wraparound rule as the app's `focusSessionIndex`. 0 inside the sweep; otherwise distance to the nearest end, which must be ≤ tolerance; nearest wins.

- [ ] **Step 1: Write the failing tests**
  - `DayMinutesTests.roundTrip`: blocks 540–720 and 1380–1440 on 2026-11-18 → `fromMinuteBlocks` then `minuteBlocks` gives the same minutes, IDs, areas and intentions; the 1440 end maps to 2026-11-19 00:00.
  - `DayMinutesTests.dstGap`: 2026-03-29 block 150–240 → start is 03:00 local, and `minuteBlocks` gives start 180, end 240 on weekday 7.
  - `DayMinutesTests.instantiateKeepsTemplateIDs`: the planned block's id equals its template block's id.
  - `TodayPlannerTests.proposalFromTemplate`: no accepted plan → plan = Wednesday template blocks; `isAccepted == false`.
  - `TodayPlannerTests.acceptedWinsOverTemplate`: accepted plan [09:00–10:00] while the template has [14:00–15:00] → `plan.blocks` is the accepted one.
  - `TodayPlannerTests.meetingsReshapeAcceptedPlan`: accepted 09:00–12:00. With meeting 10:00–10:30 there are 2 parts. With no meetings there is 1 part. `accepted` is identical in both states.
  - `TodayPlannerTests.autoAccept`: with a first block at 09:00, `autoAcceptDue` is false at 08:59, true at 09:00, and true at 15:00. False when accepted. False when the plan is empty. When the first block is dropped by a meeting, `firstStart` is the second block's start.
  - `TodayPlannerTests.morningCheckPolicy`: true for (today, nil, false); false when lastShown == today; false when accepted; true for (today, yesterday, false).
  - `TodayPlannerTests.reviewSummary`: today 2026-11-18. With history {11-17: true, 11-16: false, 11-12: true, 11-10: true, 11-18: true} → (2, 7), so 11-10 and today are excluded. Empty history → nil.
  - `PlanRingTests.arcAngles`: window 08:00–20:00. Part 08:00–11:00 → start −90, sweep 90. Part 14:00–20:00 → start 90, sweep 180.
  - `PlanRingTests.clipping`: window 08:00–20:00. Part 06:00–09:00 → start −90, sweep 30. Part 21:00–22:00 → no arc. Split block (2 parts) → 2 arcs with the same blockID.
  - `PlanRingTests.hitTest`: arcs at −90…0 and 90…270. Angle −45 → 0; 180 → 1; 2 → 0 (within tolerance of the end at 0°); 45 → nil; wraparound: −100 (≡ 260°) → 1.

- [ ] **Step 2: Run, verify failure**: `swift test --package-path macos/Packages/DayPlanKit` → fails to build (`TodayPlanner`, `PlanRing`, `fromMinuteBlocks` missing).
- [ ] **Step 3: Implement** to the interfaces. Adjust stage 1 tests only if they assumed fresh IDs from `instantiate`.
- [ ] **Step 4: Run, verify pass**: all tests pass, output clean.
- [ ] **Step 5: Commit**: `git commit -m "Add today's plan rules and ring arcs to DayPlanKit"`

---

### Task 2: DayPlanStore — accepted plans, versioned schema

**Files:** `Sources/DayPlanStore/Schema.swift` (new), `Records.swift`, `PlanStore.swift` (modify), `Tests/DayPlanStoreTests/AcceptedPlanTests.swift` (new).

**Interfaces:**
- Consumes: Task 1's `AcceptedPlan`, `DayPlan`, `LocalDay`.
- Produces:
  - `@Model final class AcceptedPlanRecord`: `dayKey: String = ""` (`"YYYY-MM-DD"`, zero-padded), `reviewed: Bool = false`, `acceptedAt: Date = .distantPast`, `planData: Data = Data()` (JSON of `DayPlan`).
  - `enum DayPlanSchemaV1: VersionedSchema` (version 1.0.0, models `[AreaRecord, TemplateBlockRecord]`, exactly as shipped in stage 2). `enum DayPlanSchemaV2: VersionedSchema` (2.0.0, adds `AcceptedPlanRecord`). `enum DayPlanMigrationPlan: SchemaMigrationPlan` with a lightweight V1→V2 stage. The model classes stay the same Swift types: V1 and V2 list them; nested copies are not needed while V1→V2 only adds an entity. Both `makeContainer(inMemory:)` and `makeContainer(url:)` use `Schema(versionedSchema: DayPlanSchemaV2.self)` and `migrationPlan: DayPlanMigrationPlan.self`.
  - On `PlanStore`:
    - `func acceptedPlan(for day: LocalDay) -> AcceptedPlan?`: nil when absent or undecodable. If several records share a day, the latest `acceptedAt` wins.
    - `func saveAccepted(_ plan: DayPlan, reviewed: Bool, at: Date) throws`: upsert by `plan.day`, through the existing `commit()` (rollback on failure).
    - `func reviewHistory(from: LocalDay, through: LocalDay) -> [LocalDay: Bool]`: inclusive range, compared on `dayKey`.

- [ ] **Step 1: Write the failing tests** (`@MainActor`, in-memory containers, unique defaults suites):
  - `acceptedRoundTrip`: save a plan of 2 blocks, reviewed true → `acceptedPlan(for:)` equals it.
  - `saveAcceptedUpserts`: save twice for the same day (second time 1 block, reviewed false) → one record; values from the second save.
  - `otherDayUntouched`: accepted plans for 11-17 and 11-18 → each day returns its own.
  - `reviewHistoryRange`: 11-10 true, 11-15 false, 11-18 true; range 11-11…11-17 → `[11-15: false]`.
  - `migratesStage2Store`: create a file container on a temp URL with **only** `Schema([AreaRecord.self, TemplateBlockRecord.self])` (the stage 2 shape), save an area, close it. Open with `PlanStore.makeContainer(url:)` → the area is still there and `saveAccepted` works. Remove the temp dir.
- [ ] **Step 2: Run, verify failure** (missing API).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit**: `git commit -m "Store accepted day plans behind a versioned schema"`

---

### Task 3: App — TodayPlanModel and a reusable block editor

**Files:** `macos/DayView/TodayPlanModel.swift`, `BlockListEditor.swift` (new); `PlanModel.swift`, `WeekSettingsView.swift`, `DayViewApp.swift` (modify).

**Interfaces:**
- Consumes: Tasks 1–2; `PlanModel` (`areas`, `template`, `isPersistent`); `TodayModel.meetingSource` (`MeetingSource.meetings(on:)`).
- Produces:
  - `PlanModel` pass-throughs: `acceptedPlan(for:)`, `saveAccepted(_:reviewed:at:) -> Bool`, `reviewHistory(from:through:)`.
  - `@MainActor final class TodayPlanModel: ObservableObject`:
    - `init(plan: PlanModel, meetings: MeetingSource, calendar: Calendar = .autoupdatingCurrent, defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init)`.
    - `@Published private(set) var state: TodayPlanState`; `@Published var isMorningCheckPresented = false`.
    - `func tick()`: recomputes `TodayPlanner.state` for `LocalDay(containing: now())` and assigns it only if changed. If `autoAcceptDue`, calls `saveAccepted(state.plan, reviewed: false, at: now)` and recomputes.
    - Owns a 1-second `Timer` on `RunLoop.main` `.common`, and also ticks when `plan.$template` / `plan.$areas` change.
    - `func mainWindowShown()`: uses `MorningCheckPolicy` with `lastShown` from defaults key `"dayplan.morningCheckShownDay"` (`"YYYY-MM-DD"`). If true, presents the sheet and stores today.
    - `func presentMorningCheck()`: always presents.
    - `func accept(_ blocks: [TemplateBlock])`: `DayPlan.fromMinuteBlocks(blocks, day: state.day, ...)`, then `saveAccepted(reviewed: true)`, then closes the sheet. `func saveEdits(_ blocks: [TemplateBlock])`: when already accepted, keeps the stored `reviewed`.
    - `var reviewSummary: (reviewed: Int, days: Int)?` via `ReviewSummary` and `plan.reviewHistory`.
  - `BlockListEditor`: the Week tab's row list extracted unchanged in behaviour.
    - Init: `blocks: Binding<[TemplateBlock]>`, `areas: [Area]`, `weekdayISO: Int`, `isLocked: (BlockID) -> Bool` (locked rows render disabled; Week passes `{ _ in false }`).
    - Keeps the rows, cached time labels, red invalid rows + message, "Add block" rule, and empty-day text supplied by the caller.
    - `WeekSettingsView` keeps its draft, save-when-valid, area-pruning and Copy popover, and uses `BlockListEditor` for the rows.
  - `DayViewApp` creates `TodayPlanModel` once (it needs `PlanModel` and `TodayModel.meetingSource`) and passes it to the main window root and the menu bar.
- [ ] **Step 1: Implement**; the Week tab must look and behave as before.
- [ ] **Step 2: Build and package tests** (commands in Global Constraints). Expected: build succeeds with no new warnings in touched files; tests pass.
- [ ] **Step 3: Launch check**: the Week tab still edits, validates and copies; report what was seen.
- [ ] **Step 4: Commit**: `git commit -m "Add today's plan model and share the block editor"`

---

### Task 4: App — morning check sheet

**Files:** `macos/DayView/MorningCheckSheet.swift` (new); `DayViewApp.swift` (`MainWindowRoot`), `MenuBarContent.swift`, `{en,fr}.lproj/Localizable.strings` (modify).

**Interfaces:**
- Consumes: Task 3 `TodayPlanModel`, `BlockListEditor`; `PlanModel.areas`; `DayViewPalette.areaColor`.
- Produces:
  - `MainWindowRoot` calls `todayPlan.mainWindowShown()` on appear, and attaches `.sheet(isPresented: $todayPlan.isMorningCheckPresented) { MorningCheckSheet(...) }`.
  - Menu bar: a "Plan today…" item that opens the main window and calls `presentMorningCheck()`.
  - `MorningCheckSheet` content:
    - Title: today's date, formatted long with the weekday.
    - Fitted blocks in time order. Each shows colour dot, area name, fitted start–end (dropped: planned start–end, struck through), intention, and one note line per `FitNote`:
      - `.splitAround(m)` → "Split around the %@ meeting" / "Coupé autour de la réunion de %@" (`%@` = "10:00–10:30").
      - `.trimmed` → "Shortened by a meeting" / "Raccourci par une réunion".
      - `.dropped` → "Covered by meetings" / "Couvert par des réunions".
    - Empty plan → "No plan for today" / "Pas de plan aujourd'hui".
    - The follow-through line when `reviewSummary` is non-nil: "Last 7 days: plan reviewed %lld/%lld days" / "7 derniers jours : plan revu %lld/%lld jours".
    - Buttons:
      - **Adjust** / **Ajuster**: toggles an inline `BlockListEditor` over a draft = `state.plan.minuteBlocks`. Rows whose original end ≤ now are locked, but only once accepted.
      - Primary: **Start my day** / **Commencer ma journée** before acceptance → `accept(draft)`. **Done** / **Terminé** after acceptance → `saveEdits(draft)`. Disabled while the draft is invalid (`TemplateRules.isValidDay`).
      - **Not now** / **Plus tard** (`.cancelAction`) → closes.
  - When a plan is auto-accepted while the sheet is open, the primary button switches to Done.
- [ ] **Step 1: Implement** the view, wiring and strings.
- [ ] **Step 2: Build and package tests.**
- [ ] **Step 3: Launch check**: delete `dayplan.morningCheckShownDay` (`defaults delete fr.dayview.app.debug dayplan.morningCheckShownDay`), make sure today's weekday has template blocks (add some in the Week tab if needed), then relaunch.
  - The sheet appears with the blocks and notes; Adjust edits a block; Start my day closes it.
  - Reopen via the menu bar "Plan today…": the button says Done.
  - Relaunch: no automatic sheet.
  - Report what was seen, with screenshots.
- [ ] **Step 4: Commit**: `git commit -m "Add the morning check for today's plan"`

---

### Task 5: App — plan lane on the main ring

**Files:** `macos/DayView/DayRingCanvas.swift`, `RingView.swift` (modify).

**Interfaces:**
- Consumes: Task 1 `PlanRing.arcs` / `arcIndex`, `FittedBlock.isRunning(at:)` / `isPaused(at:)`; Task 3 `TodayPlanModel.state`; `TodayModel.snapshot.startMinutes` / `endMinutes` (window = today's local midnight + those minutes); `PlanModel.areas`; `DayViewPalette.areaColor`.
- Produces:
  - `DayRingCanvas` gets `var planArcs: [PlanArcStyle] = []`, where `struct PlanArcStyle { arc: PlanArc; colorIndex: Int; isRunning: Bool }`, and `static let planRadiusFactor: CGFloat = 1.75`.
  - The canvas draws the plan lane after the busy lane, at radius `radius - lineWidth * planRadiusFactor`:
    - Normal: core width `lineWidth * 0.3`, opacity 0.55.
    - Running: width `lineWidth * 0.42`, opacity 0.95, plus a glow stroke at `lineWidth * 0.7`, opacity 0.16.
    - Paused (inside a meeting): normal style.
  - `RingView` passes the arcs only on the main ring, and only when the plan has non-dropped blocks. `MiniView` passes nothing.
  - Hover: when the pointer is within `bandHalf` of the plan radius, the label is "<Area name> · <fitted start>–<fitted end>" plus " · <intention>" when the intention isn't empty. Times use the user's 12/24 h `Date.FormatStyle` (`.dateTime.hour().minute()`). Hit-testing order: detour, Focus sessions, busy, then plan.
- [ ] **Step 1: Implement.**
- [ ] **Step 2: Build and package tests.**
- [ ] **Step 3: Launch check**: with today's accepted plan, the ring shows coloured segments inside the busy lane, the running block is brighter, and hovering a segment shows the label. Screenshot and report.
- [ ] **Step 4: Commit**: `git commit -m "Draw today's plan on the main ring"`
