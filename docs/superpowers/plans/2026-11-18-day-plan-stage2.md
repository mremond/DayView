# Day Plan — Stage 2 (areas, weekly template, store, Cap migration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user define life areas (name, colour, goal, on-goal apps) and a weekly template of clock-fixed blocks in two new Settings tabs, persisted with SwiftData, with a one-time question that moves the current Cap goal and on-goal apps into an area.

**Architecture:** Pure rules (area value type, seeds, template validation, copy-day, Cap migration decision) go into `DayPlanKit`. A new target `DayPlanStore` in the same package holds the SwiftData models and a `PlanStore` facade that maps them to `DayPlanKit` types. The app owns one `PlanModel` (`ObservableObject`) over `PlanStore`, and the Settings window becomes a `TabView` with General (the existing form), Areas and Week tabs.

**Tech Stack:** Swift 6 (tools 6.0), SwiftData, Swift Testing, SwiftUI (macOS 15), XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-18-life-areas-day-plan-design.md` (stage 2 of *Delivery stages*; sections *Life areas*, *Weekly template*, *Constraints*).

## Global Constraints

- Package platforms stay `.macOS(.v15)`, `.iOS(.v18)`. `DayPlanKit` imports only `Foundation`. `DayPlanStore` imports `Foundation`, `SwiftData`, `DayPlanKit` — no SwiftUI/AppKit.
- **CloudKit-compatible SwiftData models**: every stored property optional or defaulted, no `@Attribute(.unique)`, relationships optional. Store is local-only: `ModelConfiguration(..., cloudKitDatabase: .none)`.
- Seeded areas, in order: Personal life, Writing, Work, Health (French: Vie personnelle, Écriture, Travail, Santé), empty goals.
- Area colour = index into a fixed palette of **8** colours with a light and a dark variant.
- Template blocks: start/end in **5-minute steps**, within **0…1440** (24:00 allowed as an end), start < end; blocks of one weekday may not overlap (touching is allowed); weekdays are ISO 1 (Monday) … 7 (Sunday); blocks are *not* clipped to the DayView day window.
- Deleting an area that has template blocks asks for confirmation and deletes those blocks.
- On-goal app picker lists installed apps, **excluding DayView** (`fr.dayview.app`, `fr.dayview.app.debug`).
- UI strings in English and French via the existing `en.lproj` / `fr.lproj` `Localizable.strings` and `L()` / `LF()` helpers.
- Tests: `swift test --package-path macos/Packages/DayPlanKit` passes; the app builds with `xcodegen generate` + `xcodebuild -project macos/DayView.xcodeproj -scheme DayView build CODE_SIGNING_ALLOWED=NO`. No Kotlin change in this stage.
- Commit messages in English, no AI references, no reference to `docs/superpowers/`.

## Review Focus

1. **Relaunch after the user edited or deleted areas**: the four seeds must not come back → test in Task 2.
2. **Deleting an area with blocks**: its blocks disappear from the template, other areas' blocks stay → test in Task 2.
3. **Overlapping or out-of-range blocks typed in the Week tab**: shown as an inline error and never written to the store → tests in Task 1, store guard tested in Task 2.
4. **"Copy this day to…" onto a weekday that already has blocks**: the target day is replaced (no overlaps created), the copies get new IDs, the source day is unchanged → test in Task 1.
5. **Cap question skipped, or Cap empty**: asked at most once ever; not asked when there is nothing to move; apps already in the area are not duplicated → tests in Tasks 1 and 2.

## Rulings in this plan (open to correction)

- The ring's Cap editor and the goal shown in the main/mini/menu-bar views stay on the Kotlin Cap until stage 4: an *active area* only exists once the Focus is owned by Swift. Stage 2 copies the Cap into an area; the switch of display/editing to the active area happens in stage 4.
- The General tab's global on-goal apps section stays until stage 5 (the Kotlin Focus drift detection still reads it).
- Seed names are stored in the language active at first launch; they are user data afterwards, not re-localized.
- "Copy this day to…" **replaces** the target days' blocks.
- The Cap question is also asked when the Cap is empty but global on-goal apps exist (so they still move to an area).
- The last remaining area cannot be deleted (a manual Focus needs an area).
- Seeded / question-asked flags live in `UserDefaults`; on-goal apps are stored as JSON `Data` on the area record (avoids composite attributes under CloudKit).
- An invalid Week-tab edit stays on screen with its error and is not saved; the store keeps the last valid day.
- Localization stays in `.strings` files (the app's existing mechanism), not a String Catalog.

---

## File Structure

```
macos/Packages/DayPlanKit/Package.swift                         (modify) add DayPlanStore target + tests
macos/Packages/DayPlanKit/Sources/DayPlanKit/
  Area.swift            Area, OnGoalApp, AreaPalette, AreaSeed           (new)
  TemplateRules.swift   validation, snapping, copy-day                   (new)
  CapMigration.swift    when to ask, how to apply                        (new)
macos/Packages/DayPlanKit/Sources/DayPlanStore/
  Records.swift         AreaRecord, TemplateBlockRecord (@Model)          (new)
  PlanStore.swift       PlanStore facade, container factory, flags        (new)
macos/Packages/DayPlanKit/Tests/DayPlanKitTests/
  AreaTests.swift, TemplateRulesTests.swift, CapMigrationTests.swift     (new)
macos/Packages/DayPlanKit/Tests/DayPlanStoreTests/PlanStoreTests.swift  (new)
macos/project.yml                                                (modify) link DayPlanStore product
macos/DayView/PlanModel.swift          app-side ObservableObject over PlanStore   (new)
macos/DayView/InstalledApps.swift      scan installed .app bundles                (new)
macos/DayView/DayViewPalette.swift     (modify) 8 area colours, light + dark
macos/DayView/SettingsView.swift       (modify) TabView: General / Areas / Week
macos/DayView/AreasSettingsView.swift  (new)
macos/DayView/WeekSettingsView.swift   (new)
macos/DayView/CapMigrationSheet.swift  (new)
macos/DayView/DayViewApp.swift         (modify) create PlanModel, pass to Settings
macos/DayView/{en,fr}.lproj/Localizable.strings  (modify)
```

---

### Task 1: DayPlanKit — areas, template rules, Cap migration rules

**Files:**
- Create: `Sources/DayPlanKit/Area.swift`, `Sources/DayPlanKit/TemplateRules.swift`, `Sources/DayPlanKit/CapMigration.swift`
- Test: `Tests/DayPlanKitTests/AreaTests.swift`, `TemplateRulesTests.swift`, `CapMigrationTests.swift`
(paths relative to `macos/Packages/DayPlanKit/`)

**Interfaces:**
- Consumes: `AreaID`, `BlockID`, `TemplateBlock` (stage 1, `Identifiers.swift`, `PlanModel.swift`).
- Produces:
  - `public struct OnGoalApp: Sendable, Hashable, Codable { public var bundleID: String; public var displayName: String; public init(bundleID:displayName:) }`
  - `public struct Area: Sendable, Hashable, Codable, Identifiable { public var id: AreaID; public var name: String; public var colorIndex: Int; public var goalTitle: String; public var goalDeadline: Date?; public var onGoalApps: [OnGoalApp]; public var order: Int }` with a memberwise public init where `goalTitle = ""`, `goalDeadline = nil`, `onGoalApps = []` default.
  - `public enum AreaPalette { public static let count = 8; public static func normalized(_ index: Int) -> Int }` (positive modulo).
  - `public enum AreaSeed { public static func defaults(names: [String]) -> [Area] }` — precondition `names.count == 4`; area `i` gets `colorIndex = i`, `order = i`, empty goal, no apps, a fresh `AreaID`.
  - `public enum TemplateRules`:
    - `static let step = 5`
    - `static func snapped(_ minute: Int) -> Int` — nearest multiple of 5 (ties round up), clamped to 0…1440.
    - `static func isWellFormed(_ block: TemplateBlock) -> Bool` — weekday 1…7, `0 <= start < end <= 1440`, both multiples of 5.
    - `static func overlappingIDs(_ blocks: [TemplateBlock]) -> Set<BlockID>` — IDs of every block that overlaps another block **of the same weekday**; `a.end == b.start` is not an overlap.
    - `static func isValidDay(_ blocks: [TemplateBlock]) -> Bool` — all well-formed and `overlappingIDs` empty.
    - `static func copyingDay(_ source: Int, to targets: Set<Int>, in template: [TemplateBlock]) -> [TemplateBlock]` — returns the whole template with each target weekday's blocks replaced by copies of the source day's (new `BlockID`s, same times/area/intention); `source` in `targets` is ignored; other days untouched.
  - `public enum CapMigration`:
    - `static func needsQuestion(goalTitle: String, goalDeadline: Date?, onGoalApps: [OnGoalApp], alreadyAsked: Bool) -> Bool` — `!alreadyAsked && (trimmed title non-empty || deadline != nil || !apps.isEmpty)`.
    - `static func applying(goalTitle: String, goalDeadline: Date?, onGoalApps: [OnGoalApp], to area: Area) -> Area` — sets `goalTitle` (trimmed) and `goalDeadline`; `onGoalApps` = area's existing apps followed by new ones whose `bundleID` is not already present.

- [ ] **Step 1: Write the failing tests** (Swift Testing, `@testable import DayPlanKit`)
  - `AreaTests.seedsAreFourInOrder`: `AreaSeed.defaults(names: ["Personal life","Writing","Work","Health"])` → names in that order, `colorIndex == [0,1,2,3]`, `order == [0,1,2,3]`, all `goalTitle == ""`, `goalDeadline == nil`, `onGoalApps.isEmpty`, 4 distinct ids.
  - `AreaTests.paletteNormalizes`: `normalized(9) == 1`, `normalized(-1) == 7`.
  - `TemplateRulesTests.snapsToFiveMinutes`: `snapped(62) == 60`, `snapped(63) == 65`, `snapped(-4) == 0`, `snapped(1443) == 1440`.
  - `TemplateRulesTests.wellFormed`: 540–720 Monday ✓; 1380–1440 ✓; 720–720 ✗; 720–540 ✗; 541–600 ✗; 1380–1445 ✗; weekday 0 ✗, 8 ✗.
  - `TemplateRulesTests.overlapsSameWeekdayOnly`: Mon 540–720 (A), Mon 700–800 (B), Mon 800–900 (C, touching B), Tue 540–720 (D) → `{A, B}`.
  - `TemplateRulesTests.validDay`: [A, C] valid; [A, B] invalid; [one ill-formed block] invalid.
  - `TemplateRulesTests.copyDayReplacesTargets`: template Mon [A 540–720, C 800–900], Wed [E 600–660], Fri [F 480–540]; `copyingDay(1, to: [1, 3, 5])` → Mon still exactly A and C (same IDs); Wed and Fri each have 2 blocks with times 540–720 and 800–900, areas/intentions equal to A/C, IDs not in {A,C,E,F} and distinct; E and F gone; `isValidDay` true for Wed and Fri.
  - `CapMigrationTests.asksOnlyWhenSomethingToMove`: empty/whitespace title + nil deadline + no apps → false; title "Book" → true; only deadline → true; only apps → true; anything with `alreadyAsked: true` → false.
  - `CapMigrationTests.applyMergesApps`: area with apps [Ulysses]; apply title "  Finish book  ", deadline D, apps [Ulysses, Scrivener] → title "Finish book", deadline D, apps [Ulysses, Scrivener] (Ulysses once, original first).

- [ ] **Step 2: Run tests, verify they fail**
  Run: `swift test --package-path macos/Packages/DayPlanKit`
  Expected: build fails — `Area`, `TemplateRules`, `CapMigration` not found.

- [ ] **Step 3: Implement the three files** to the interfaces above.

- [ ] **Step 4: Run tests, verify they pass**
  Run: `swift test --package-path macos/Packages/DayPlanKit`
  Expected: all tests pass (stage 1's 55 plus the new ones).

- [ ] **Step 5: Commit**
  `git commit -m "Add life areas and weekly template rules to DayPlanKit"`

---

### Task 2: DayPlanStore — SwiftData models and PlanStore

**Files:**
- Modify: `macos/Packages/DayPlanKit/Package.swift`
- Create: `Sources/DayPlanStore/Records.swift`, `Sources/DayPlanStore/PlanStore.swift`
- Test: `Tests/DayPlanStoreTests/PlanStoreTests.swift`

**Interfaces:**
- Consumes: Task 1's `Area`, `OnGoalApp`, `TemplateRules.isValidDay`; stage 1's `TemplateBlock`, `AreaID`, `BlockID`.
- Produces:
  - Package: new library product `DayPlanStore` (target `DayPlanStore`, depends on `DayPlanKit`); test target `DayPlanStoreTests` depends on `DayPlanStore`.
  - `@Model final class AreaRecord` — `id: UUID = UUID()`, `name: String = ""`, `colorIndex: Int = 0`, `goalTitle: String = ""`, `goalDeadline: Date? = nil`, `onGoalAppsData: Data = Data()` (JSON of `[OnGoalApp]`; empty data = no apps), `order: Int = 0`, `@Relationship(deleteRule: .cascade, inverse: \TemplateBlockRecord.area) var blocks: [TemplateBlockRecord]? = []`.
  - `@Model final class TemplateBlockRecord` — `id: UUID = UUID()`, `weekdayISO: Int = 1`, `startMinute: Int = 0`, `endMinute: Int = 0`, `intention: String = ""`, `area: AreaRecord? = nil`.
  - `@MainActor public final class PlanStore`:
    - `public static func makeContainer(inMemory: Bool) throws -> ModelContainer` — schema `[AreaRecord, TemplateBlockRecord]`, configuration name `"DayPlan"`, `isStoredInMemoryOnly: inMemory`, `cloudKitDatabase: .none`.
    - `public init(container: ModelContainer, defaults: UserDefaults)`
    - `public func areas() -> [Area]` — sorted by `order`, then name.
    - `public func save(_ area: Area) throws` — insert or update by `id`.
    - `public func deleteArea(_ id: AreaID) throws` — cascades to its blocks.
    - `public func reorderAreas(_ ids: [AreaID]) throws` — `order = index` in the given order.
    - `public func blockCount(for id: AreaID) -> Int`
    - `public func template() -> [TemplateBlock]` — all blocks with a non-nil area, sorted by weekday then start.
    - `public func replaceDay(_ weekdayISO: Int, with blocks: [TemplateBlock]) throws` — throws `PlanStoreError.invalidDay` (and changes nothing) unless every block has `weekdayISO == weekdayISO` and `TemplateRules.isValidDay(blocks)`; blocks whose area doesn't exist are skipped; otherwise the day's records are replaced.
    - `public func replaceTemplate(_ blocks: [TemplateBlock]) throws` — same check per weekday, then replaces every day (used by copy-day).
    - `public func seedIfNeeded(_ seeds: [Area]) throws -> Bool` — inserts `seeds` only when the `"dayplan.areasSeeded"` flag is false **and** there are no areas; then sets the flag. Returns whether it seeded.
    - `public var capQuestionAsked: Bool { get set }` — `UserDefaults` key `"dayplan.capQuestionAsked"`.
    - `public enum PlanStoreError: Error { case invalidDay }`
  - Every mutating method saves the context before returning.

- [ ] **Step 1: Write the failing tests** (`@MainActor`, in-memory container, `UserDefaults(suiteName: UUID().uuidString)!`)
  - `areaRoundTrip`: save an area with title, deadline, colour 5, two apps → `areas()` returns an equal `Area`.
  - `reorder`: three areas, `reorderAreas([c, a, b])` → `areas().map(\.id) == [c, a, b]`.
  - `seedsOnceEvenAfterDeletion`: `seedIfNeeded(4 seeds)` → true, 4 areas; delete all four; `seedIfNeeded` again → false and `areas().isEmpty`. A second `PlanStore` on the same container and defaults → `seedIfNeeded` false.
  - `seedSkippedWhenAreasExist`: fresh defaults, one area already saved → `seedIfNeeded` false, flag set, 1 area.
  - `deleteAreaCascadesBlocks`: areas W and H; Monday blocks W 540–720, H 1080–1140 → `blockCount(for: W) == 1`; `deleteArea(W)` → `template()` is exactly the H block.
  - `replaceDayRejectsInvalid`: valid Monday day saved; `replaceDay(1, with: [overlapping pair])` throws `.invalidDay` and `template()` unchanged; a block with `weekdayISO 2` passed to `replaceDay(1, …)` also throws.
  - `replaceTemplateAfterCopy`: Monday two blocks; `replaceTemplate(TemplateRules.copyingDay(1, to: [3], in: template()))` → Wednesday has 2 blocks, Monday unchanged.
  - `capQuestionFlagPersists`: set `capQuestionAsked = true`; a new `PlanStore` on the same defaults reads true.

- [ ] **Step 2: Run tests, verify they fail**
  Run: `swift test --package-path macos/Packages/DayPlanKit`
  Expected: build fails — no `DayPlanStore` module.

- [ ] **Step 3: Implement** `Package.swift` changes, `Records.swift` and `PlanStore.swift` to the interfaces above.

- [ ] **Step 4: Run tests, verify they pass**
  Run: `swift test --package-path macos/Packages/DayPlanKit`
  Expected: all suites pass.

- [ ] **Step 5: Commit**
  `git commit -m "Add SwiftData store for areas and the weekly template"`

---

### Task 3: App — PlanModel, area colours, Settings tabs, Areas tab

**Files:**
- Modify: `macos/project.yml` (add `DayPlanStore` product to the app target), `macos/DayView/DayViewApp.swift`, `macos/DayView/SettingsView.swift`, `macos/DayView/DayViewPalette.swift`, `macos/DayView/{en,fr}.lproj/Localizable.strings`
- Create: `macos/DayView/PlanModel.swift`, `macos/DayView/InstalledApps.swift`, `macos/DayView/AreasSettingsView.swift`

**Interfaces:**
- Consumes: Task 1 (`Area`, `OnGoalApp`, `AreaSeed`, `AreaPalette`), Task 2 (`PlanStore`).
- Produces:
  - `@MainActor final class PlanModel: ObservableObject` — `@Published private(set) var areas: [Area]`, `@Published private(set) var template: [TemplateBlock]`; `init(store: PlanStore)`; `static func live() -> PlanModel` (on-disk container; if creating it throws, logs with `NSLog` and falls back to an in-memory container); seeds on init with `AreaSeed.defaults(names: [L("Personal life"), L("Writing"), L("Work"), L("Health")])`; pass-through mutators `save(_:)`, `deleteArea(_:)`, `moveAreas(from: IndexSet, to: Int)`, `blockCount(for:)`, `replaceDay(_:with:) -> Bool` (false when the store rejects it), `copyDay(_:to:)`, `addArea()` (name `L("New area")`, next colour index, last order), and `var capQuestionAsked: Bool`. Every mutator reloads `areas` and `template`; store errors are logged, never crash.
  - `DayViewPalette.areaColor(_ index: Int) -> Color` backed by an `areas: [Color]` array of exactly 8 colours per variant, chosen to read on both `ink` backgrounds and to stay distinct from the `busy` blues (builder picks the values).
  - `enum InstalledApps { static func scan() -> [OnGoalApp] }` — `.app` bundles directly under `/Applications`, `/Applications/Utilities`, `/System/Applications`, `~/Applications`; bundle id + display name from the bundle's Info.plist (`CFBundleDisplayName` ?? `CFBundleName` ?? file name); excludes `fr.dayview.app` and `fr.dayview.app.debug`; deduped by bundle id; sorted by display name (localized, case-insensitive). Runs off the main thread (`Task.detached`) when called from views.
  - `SettingsView` becomes a `TabView` with tabs **General** (the existing form unchanged), **Areas**, **Week** (Week placeholder until Task 4 — Task 3 may add an empty `WeekSettingsView` stub that Task 4 fills).
  - `AreasSettingsView(plan: PlanModel, today: TodayModel)`: list of areas in order (drag to reorder, colour dot from `areaColor`); a detail editor for the selected area: name, colour (8 swatches), goal title, optional goal deadline (toggle + date picker), on-goal apps (toggle list over `InstalledApps.scan()` ∪ the area's stored apps, so a stored but uninstalled app stays visible); "Add area"; "Delete area" — disabled when only one area remains; when `blockCount > 0` a confirmation states the number of blocks that will be deleted ("Delete Writing and its 3 blocks?"); with no blocks it deletes directly.
- New strings (en / fr): General / Général, Areas / Domaines, Week / Semaine, Personal life / Vie personnelle, Writing / Écriture, Work / Travail, Health / Santé, New area / Nouveau domaine, Add area / Ajouter un domaine, Delete area / Supprimer le domaine, Name / Nom, Colour / Couleur, Goal / Objectif, Deadline / Échéance, On-goal apps / Apps liées à l'objectif, "Delete %@ and its %lld blocks?" / "Supprimer %@ et ses %lld blocs ?", Delete / Supprimer. Reuse existing keys where they already exist.

- [ ] **Step 1: Wire the package product and the model.** Add `DayPlanStore` to `project.yml`; write `PlanModel` and `InstalledApps`; create the model in `DayViewApp` as `@StateObject private var plan = PlanModel.live()` and pass it to `SettingsView(model:plan:)`.
- [ ] **Step 2: Add the 8 area colours** to both palette variants and `areaColor(_:)` (uses `AreaPalette.normalized`).
- [ ] **Step 3: Convert `SettingsView` to the tab layout** and write `AreasSettingsView` as specified; add the strings to both `.strings` files.
- [ ] **Step 4: Build and run the package tests**
  Run: `cd macos && xcodegen generate && xcodebuild -project DayView.xcodeproj -scheme DayView -configuration Debug -destination 'platform=macOS' build CODE_SIGNING_ALLOWED=NO -quiet` then `swift test --package-path macos/Packages/DayPlanKit`
  Expected: build succeeds with no errors; tests pass.
- [ ] **Step 5: Launch check.** Launch the built Debug app once, open Settings (⌘,), confirm the Areas tab lists the four seeded areas and the General tab is unchanged; quit. Report what was seen (a screenshot via `screencapture -l` or `-R` is enough).
- [ ] **Step 6: Commit**
  `git commit -m "Add Areas settings tab backed by the day plan store"`

---

### Task 4: App — Week tab

**Files:**
- Create/replace: `macos/DayView/WeekSettingsView.swift`
- Modify: `macos/DayView/{en,fr}.lproj/Localizable.strings`

**Interfaces:**
- Consumes: `PlanModel.template`, `.areas`, `.replaceDay(_:with:) -> Bool`, `.copyDay(_:to:)`; `TemplateRules.snapped`, `.overlappingIDs`, `.isWellFormed`; `DayViewPalette.areaColor`.
- Produces: `WeekSettingsView(plan: PlanModel)`:
  - Weekday selector Monday…Sunday (ISO 1…7), labels from `Calendar.current.shortStandaloneWeekdaySymbols` reordered to start on Monday.
  - The selected day's blocks as a **local draft** (`[TemplateBlock]`), one row each: start, end, area picker (colour dot + name), intention text field, delete button. Start and end are chosen from 5-minute steps 00:00…24:00 (end may be 24:00), labelled in the user's clock format (`Date.FormatStyle` with the system hour cycle); the 1440 end is labelled "24:00" in 24 h and "Midnight" in 12 h.
  - After every draft change: if `TemplateRules.isValidDay(draft)` → `plan.replaceDay(weekday, with: draft)`; otherwise rows in `overlappingIDs` and ill-formed rows are marked in red with one inline message ("Blocks overlap" / "End must be after start") and nothing is saved. Switching weekday or reopening reloads the draft from `plan.template` (an unsaved invalid edit is discarded).
  - "Add block": appended block of the first area, 60 minutes long, starting at the latest end among the day's blocks (or 09:00 if none), shortened to fit before 24:00; if less than 5 minutes remain, the button is disabled.
  - "Copy this day to…": a popover with a checkbox per other weekday and a Copy button calling `plan.copyDay(weekday, to: chosen)`; disabled while the draft is invalid or empty; the popover says that the chosen days' blocks are replaced.
  - Empty day: "No blocks on this day."; no areas cannot happen (Task 3 keeps at least one).
- New strings (en / fr): Add block / Ajouter un bloc, Copy this day to… / Copier ce jour vers…, Copy / Copier, "Blocks on the chosen days are replaced." / "Les blocs des jours choisis sont remplacés.", Blocks overlap / Les blocs se chevauchent, End must be after start / La fin doit être après le début, No blocks on this day. / Aucun bloc ce jour-là., Intention / Intention, Midnight / Minuit, Start / End (reuse existing keys if present).

- [ ] **Step 1: Implement `WeekSettingsView`** and the strings.
- [ ] **Step 2: Build and run the package tests** (same commands as Task 3 Step 4). Expected: build succeeds; tests pass.
- [ ] **Step 3: Launch check.** In the Debug app, add two Monday blocks, make them overlap (red + message), fix it, copy Monday to Wednesday, relaunch and confirm Wednesday shows the copies. Report what was seen.
- [ ] **Step 4: Commit**
  `git commit -m "Add Week settings tab for the weekly template"`

---

### Task 5: App — one-time Cap migration question

**Files:**
- Create: `macos/DayView/CapMigrationSheet.swift`
- Modify: `macos/DayView/AreasSettingsView.swift`, `macos/DayView/{en,fr}.lproj/Localizable.strings`

**Interfaces:**
- Consumes: `CapMigration.needsQuestion`, `CapMigration.applying`; `PlanModel.capQuestionAsked`, `.save(_:)`, `.areas`; Kotlin snapshot `goalTitle`, `goalHasDeadline`, `goalDeadlineEpochMillis`, and `TodayModel.onGoalApps() -> [AppRef]` (`bundleId`, `displayName`), mapped to `OnGoalApp`.
- Produces: when `AreasSettingsView` appears and `needsQuestion(...)` is true, a sheet:
  - Title "Which area does your current goal belong to?" (or "Which area do your on-goal apps belong to?" when the title is empty and there is no deadline), showing the goal title / deadline and the app names being moved.
  - One button per area (in order) → `plan.save(CapMigration.applying(..., to: area))`, then `capQuestionAsked = true`.
  - "Skip" → `capQuestionAsked = true`, nothing copied.
  - The Kotlin Cap and global on-goal apps are **not** modified (see Rulings).
- New strings (en / fr): "Which area does your current goal belong to?" / « À quel domaine appartient votre objectif actuel ? », "Which area do your on-goal apps belong to?" / « À quel domaine appartiennent vos apps liées à l'objectif ? », Skip / Passer, "Apps:" / « Apps : ». (French strings use the plain-quote style the existing `fr.lproj` file uses.)

- [ ] **Step 1: Implement the sheet** and its presentation in `AreasSettingsView`; add strings.
- [ ] **Step 2: Build and run the package tests.** Expected: build succeeds; tests pass.
- [ ] **Step 3: Launch check.** With a Cap set in the app, open Settings → Areas: the sheet appears; pick Writing; Writing now shows the goal and the global on-goal apps; close and reopen Settings: no sheet. Report what was seen (reset with `defaults delete fr.dayview.app.debug dayplan.capQuestionAsked` if needed).
- [ ] **Step 4: Commit**
  `git commit -m "Ask once which area the current goal belongs to"`
