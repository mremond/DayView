# Hide Declined and Tentative Meetings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Silently drop calendar events the user has declined or answered "Maybe" (tentative) so they don't count as busy time in DayView.

**Architecture:** Filter platform-side, in the same fetch loops that already drop all-day and non-busy events. Read the user's own RSVP status, skip declined/tentative, and discard the status in place. No change to the shared `BusyInterval` model, persistence, sync, or settings.

**Tech Stack:** Kotlin Multiplatform, Swift (EventKit via JNA dylib), Android `CalendarContract`.

## Global Constraints

- No user-facing toggle or setting; behavior is always-on.
- Do not modify `BusyInterval`, its Base64 codec, persistence, or sync mappers.
- Keep: accepted, un-answered invite (invited/pending), and no-attendee-status (personal) events. Drop only explicit **declined** and **tentative**.
- ktlint is enforced. Run `./gradlew ktlintCheck` before committing Kotlin changes.
- Commit messages describe the change only — no reference to Claude/Anthropic/AI, no test-plan section.
- There are no unit tests for the platform fetch layer (all-day/availability filters are untested); this feature follows that pattern. Per-task verification is build + lint, plus a documented manual check.

---

### Task 1: macOS — drop declined/tentative in the EventKit bridge

**Files:**
- Modify: `scripts/MacEventKitBridge.swift:72-74` (the filter block inside `dv_calendar_busy`)

**Interfaces:**
- Consumes: `EKEvent.attendees` (an `[EKParticipant]?`), `EKParticipant.isCurrentUser` (`Bool`), and `EKParticipant.participantStatus` (`EKParticipantStatus`) from the EventKit framework already imported at the top of the file. (Note: `EKEvent` has no `currentUser` property; the local user is found by scanning `attendees` for `isCurrentUser`.)
- Produces: no new symbols; the emitted tab-delimited line format (`start\tend\tcalId\ttitle`) is unchanged.

- [ ] **Step 1: Add the RSVP filter after the existing all-day/availability checks**

In `dv_calendar_busy`, the current loop head is:

```swift
    for event in store.events(matching: predicate) {
        if event.isAllDay { continue }
        if event.availability != .busy { continue }
```

Insert the participant-status check immediately after the `availability` line:

```swift
    for event in store.events(matching: predicate) {
        if event.isAllDay { continue }
        if event.availability != .busy { continue }
        if let me = event.attendees?.first(where: { $0.isCurrentUser }),
           me.participantStatus == .declined || me.participantStatus == .tentative {
            continue
        }
```

Rationale: `event.attendees` is `nil` (or has no `isCurrentUser` participant) for events with no invitees (personal events), so `me` is `nil` and those events fall through and are kept. Only `.declined` and `.tentative` are dropped; `.accepted`, `.pending`, `.unknown`, `.delegated`, etc. are kept.

- [ ] **Step 2: Verify the Swift file compiles**

The dylib is compiled during the desktop packaging build. Compile it directly to catch syntax errors fast:

Run: `xcrun -sdk macosx swiftc -emit-library -o /tmp/libdayview_eventkit_check.dylib scripts/MacEventKitBridge.swift`
Expected: exits 0, produces the dylib with no errors. Delete the artifact afterward: `rm -f /tmp/libdayview_eventkit_check.dylib`

- [ ] **Step 3: Commit**

```bash
git add scripts/MacEventKitBridge.swift
git commit -m "Hide declined and tentative meetings on macOS"
```

- [ ] **Step 4: Manual verification (record result, do not skip)**

Run the desktop app with `./gradlew :shared:run` on a machine with calendar access granted. Confirm: an event you have **Declined** and one you marked **Maybe** do not appear as busy arcs on the ring; an **Accepted** event and an **un-answered invite** still appear. If you cannot exercise this locally, note that explicitly rather than claiming it passed.

---

### Task 2: Android — drop declined/tentative in the CalendarContract query

**Files:**
- Modify: `shared/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt:58-74` (projection + loop filter inside `busyIntervals`)

**Interfaces:**
- Consumes: `CalendarContract.Instances.SELF_ATTENDEE_STATUS` (the projection column) and the value constants `CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED` / `ATTENDEE_STATUS_TENTATIVE`. (Note: the `ATTENDEE_STATUS_*` value constants live on `CalendarContract.Attendees`, not `Instances`.)
- Produces: no new symbols; `busyIntervals(...)` signature and `BusyInterval` output are unchanged.

- [ ] **Step 1: Append `SELF_ATTENDEE_STATUS` to the projection**

The current projection (indices 0–5) is:

```kotlin
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_ID,
        )
```

Append the status column as index 6 (appending keeps existing column indices 0–5 unchanged):

```kotlin
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
```

- [ ] **Step 2: Read the status and skip declined/tentative in the loop**

The current loop body is:

```kotlin
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val availability = c.getInt(4) // 0 = BUSY
                val calId = c.getLong(5).toString()
                if (allDay) continue
                if (availability != CalendarContract.Instances.AVAILABILITY_BUSY) continue
                if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) continue
```

Add the status read and its filter after the availability check (a null/absent column reads as `0` = `ATTENDEE_STATUS_NONE`, which is kept):

```kotlin
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val availability = c.getInt(4) // 0 = BUSY
                val calId = c.getLong(5).toString()
                val selfStatus = c.getInt(6)
                if (allDay) continue
                if (availability != CalendarContract.Instances.AVAILABILITY_BUSY) continue
                if (selfStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED ||
                    selfStatus == CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                ) {
                    continue
                }
                if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) continue
```

- [ ] **Step 3: Run ktlint and the Android test task**

Run: `./gradlew ktlintCheck :shared:testAndroidHostTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, no stderr. (No test asserts this behavior — the fetch layer is untested by design — this step confirms the change compiles and violates no style rule.)

- [ ] **Step 4: Commit**

```bash
git add shared/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt
git commit -m "Hide declined and tentative meetings on Android"
```

- [ ] **Step 5: Manual verification (record result, do not skip)**

Build/install on a device with a Google account that has a declined and a "Maybe" event: `./gradlew :androidApp:installDebug`. Confirm the declined and tentative events do not appear as busy arcs, while accepted and un-answered invites still do. If you cannot exercise this on a device, note that explicitly.

---

### Task 3: Full gate and wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full commit gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with no errors and no stderr.

- [ ] **Step 2: Confirm the working tree is clean and both platform commits are present**

Run: `git status` and `git log --oneline -3`
Expected: clean tree; the macOS and Android commits from Tasks 1 and 2 present.

---

## Self-Review

**Spec coverage:**
- Behavior (drop declined + tentative, keep accepted/invited/no-status) → Tasks 1 & 2, both filter blocks.
- Platform-side approach, no model/persistence/sync change → enforced by Global Constraints; no task touches `BusyInterval` or mappers.
- macOS via `event.currentUser?.participantStatus` → Task 1 Step 1.
- Android via `SELF_ATTENDEE_STATUS` column → Task 2 Steps 1–2.
- Testing = follow untested-fetch-layer pattern + manual check + standard gate → Task 2 Step 3, Tasks 1/2 manual steps, Task 3.
- Out-of-scope items (toggle, model changes, availability filtering) → excluded; not present in any task.

**Placeholder scan:** No TBD/TODO/"handle edge cases"/vague steps. Every code step shows exact code.

**Type consistency:** macOS reads the local user via `event.attendees?.first(where: { $0.isCurrentUser })` and checks `EKParticipantStatus` cases `.declined`/`.tentative` (there is no `EKEvent.currentUser`). Android uses `ATTENDEE_STATUS_DECLINED`/`ATTENDEE_STATUS_TENTATIVE` and reads the appended column at index 6 consistently between Steps 1 and 2; existing indices 0–5 are preserved by appending.
