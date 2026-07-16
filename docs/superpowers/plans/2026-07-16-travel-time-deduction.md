# Travel Time Deduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Count Apple Calendar travel time as busy time on macOS by extending each busy interval upstream by the event's travel duration.

**Architecture:** All changes live in `dv_calendar_busy` in the in-process EventKit bridge (`scripts/MacEventKitBridge.swift`), compiled to `libdayview_eventkit.dylib` by the Gradle task `:shared:compileMacEventKitBridge`. The bridge emits `[startDate − travel, endDate]` per event; the Kotlin downstream (net time, arcs, history, sync) consumes `BusyInterval` unchanged and inherits the deduction. Travel time is only reachable through the **private** KVC key `travelTime` (seconds) — the read is guarded so a future macOS that removes it degrades silently to today's behavior.

**Tech Stack:** Swift (EventKit, KVC), Gradle `Exec` task wrapping `xcrun swiftc`.

**Spec:** `docs/superpowers/specs/2026-07-16-travel-time-deduction-design.md`

## Global Constraints

- macOS only: no Kotlin changes, no Android changes, no encoding/format changes, no settings/UI.
- Travel value clamped to `[0, 3 h]`; fetch predicate widened by the same 3 h constant.
- The bridge's contract is unchanged: it emits only intervals overlapping the requested `[start, end]`.
- Commit messages in English, no AI/assistant references, no references to docs under `docs/superpowers/`.
- Gate before every commit: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest` (must pass; the change is pure Swift but the gate keeps the rest honest).

---

### Task 1: Extend busy intervals by travel time in the EventKit bridge

**Files:**
- Modify: `scripts/MacEventKitBridge.swift` (function `dv_calendar_busy`, currently near line 94)

**Interfaces:**
- Consumes: existing `store` (`EKEventStore`), `primeAccessIfNeeded()`, existing event filters in `dv_calendar_busy`.
- Produces: same C ABI as today — `dv_calendar_busy(startMillis, endMillis)` returning `start\tend\tcalId\ttitle\n` lines; only the emitted `start` values change (earlier by the event's travel time).

There is no Swift test harness in this repo (see spec, "Testing"): the cycle here is a private-API smoke check, a compile check, the standard Kotlin gate, then manual end-to-end verification in Task 2.

- [ ] **Step 1: Smoke-check the private KVC key exists on this OS**

This validates the private-API assumption at development time without needing calendar (TCC) access — it only instantiates an `EKEvent`, it reads no calendar data.

Run:
```bash
cat > /tmp/travel-smoke.swift <<'EOF'
import EventKit
let event = EKEvent(eventStore: EKEventStore())
print("responds:", event.responds(to: NSSelectorFromString("travelTime")))
EOF
xcrun swift /tmp/travel-smoke.swift
```
Expected: `responds: true`. If it prints `false`, STOP and report — the private key is gone on this macOS version and the feature (spec) needs rethinking. The guarded code below is still safe to ship either way, but the maintainer must decide.

- [ ] **Step 2: Add the travel-time helper and clamp constant**

In `scripts/MacEventKitBridge.swift`, immediately above `@_cdecl("dv_calendar_busy")`, insert:

```swift
// EventKit has no public API for an event's travel time; it is only reachable through
// the private KVC key "travelTime" (seconds). The responds(to:) guard makes an OS
// release that removes the accessor degrade to "no travel" instead of raising an
// Objective-C exception, and the clamp keeps a corrupt value from swallowing the day.
// The same constant widens the fetch window in dv_calendar_busy so an event starting
// after the requested end, whose travel time overlaps the window, is still seen.
private let maxTravelSeconds: TimeInterval = 3 * 60 * 60

private func travelSeconds(_ event: EKEvent) -> TimeInterval {
    guard event.responds(to: NSSelectorFromString("travelTime")),
          let travel = (event.value(forKey: "travelTime") as? NSNumber)?.doubleValue
    else { return 0 }
    return min(max(travel, 0), maxTravelSeconds)
}
```

- [ ] **Step 3: Widen the predicate and emit the extended interval**

In `dv_calendar_busy`, replace:

```swift
    let predicate = store.predicateForEvents(withStart: start, end: end, calendars: nil)
```

with:

```swift
    // Fetch up to maxTravelSeconds past the requested end so an event starting after
    // the window whose travel time overlaps it is still seen. Only extended intervals
    // overlapping [start, end] are emitted, so the function's contract is unchanged.
    let predicate = store.predicateForEvents(
        withStart: start,
        end: end.addingTimeInterval(maxTravelSeconds),
        calendars: nil
    )
```

Then, in the event loop, replace:

```swift
        let s = Int64(event.startDate.timeIntervalSince1970 * 1000.0)
```

with:

```swift
        // Travel time blocks the stretch before the event: extend the busy interval
        // upstream. Events pulled in only by the widened fetch that still don't reach
        // back into the requested window are dropped here.
        let busyStart = event.startDate.addingTimeInterval(-travelSeconds(event))
        if busyStart >= end { continue }
        let s = Int64(busyStart.timeIntervalSince1970 * 1000.0)
```

- [ ] **Step 4: Compile the dylib**

Run: `./gradlew :shared:compileMacEventKitBridge`
Expected: `BUILD SUCCESSFUL`, no swiftc warnings about the modified lines. (The task is `onlyIf` macOS; this plan runs on the maintainer's Mac.)

- [ ] **Step 5: Run the standard gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (no Kotlin was touched; this guards against accidental collateral).

- [ ] **Step 6: Commit**

```bash
git add scripts/MacEventKitBridge.swift
git commit -m "Deduct Apple Calendar travel time from busy intervals on macOS"
```

---

### Task 2: Manual end-to-end verification on macOS

**Files:** none (verification only).

**Interfaces:**
- Consumes: the dylib built in Task 1, loaded by `shared/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt` via JNA.

Travel time cannot be set programmatically without the same private API and a TCC grant the agent sandbox may not have, so this task is a guided manual check with the maintainer.

- [ ] **Step 1: Launch the desktop app**

Run: `./gradlew :shared:run` (inherits the terminal's calendar grant — no packaging/signing needed).

- [ ] **Step 2: Verify the basic deduction (maintainer)**

In Calendar.app, create today's event inside the day window (e.g. 15:00–16:00) with a travel time of 30 min (event → info → "travel time"). In DayView, confirm:
- the busy arc for that event starts at 14:30, not 15:00;
- net remaining time is 1 h 30 smaller than without the event (not 1 h).

- [ ] **Step 3: Verify the window-edge case (maintainer)**

Create an event starting ~30 min **after** the configured end of day, with 45 min travel time. Confirm the ~15 min of travel that falls inside the day window appears as busy and shrinks net time by that amount only.

- [ ] **Step 4: Clean up and report**

Delete both test events. Report the observed behavior (screenshots welcome) before merging.
