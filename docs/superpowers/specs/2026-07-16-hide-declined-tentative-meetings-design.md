# Hide declined and tentative meetings

## Goal

Calendar events the user has **declined** or answered **"Maybe" (tentative)** should
not count as busy time in DayView. They are silently dropped when reading calendar
events, exactly like all-day and non-busy events already are — no setting, no UI.

## Motivation

DayView subtracts busy calendar intervals from the day to show net free time. A meeting
you declined is time you are not committed to, and a "Maybe" is time you have not
committed to. Counting either against the day overstates how booked you are. The app
already treats all-day events and free/tentative-*availability* events as "not real busy
time" and drops them with no toggle; declined/tentative-*response* events belong in the
same bucket.

Note the distinction: an event's **availability** (busy/free/tentative) is separate from
the user's **RSVP response** (accepted/declined/tentative/invited). The app already drops
`availability != busy`. This change additionally drops events whose *RSVP response* is
declined or tentative, regardless of their availability.

## Behavior

When reading calendar events on either platform, skip an event when the user's own
participation status is:

- **Declined** → hide
- **Tentative / "Maybe"** → hide

Keep everything else:

- **Accepted** → keep
- **Invited / pending / needs-action** (un-answered invite) → keep — the user hasn't
  said no, so it still occupies the day until they decline it
- **No attendee status** (personal event with no invitees, solo time-block) → keep —
  there is no "you" to have declined

## Approach

Filter platform-side, in the same fetch loops that already drop all-day and non-busy
events. The RSVP status is inspected and discarded in place. Nothing new enters the
Kotlin `BusyInterval` model; there is no persistence, sync, or settings plumbing.

This mirrors the existing filters and keeps the two platforms self-contained. The
rejected alternative — carrying RSVP status into `BusyInterval` and filtering in shared
Kotlin — would be unit-testable in common code but adds a model field, a Base64 codec
change (`CalendarNetTime.kt`), and sync mapping work for behavior that is identical on
both platforms. Not worth it for an always-on filter.

### macOS — `scripts/MacEventKitBridge.swift`

In `dv_calendar_busy`, after the existing all-day and `availability != .busy` checks,
inspect the current user's participation status:

```swift
if let status = event.currentUser?.participantStatus,
   status == .declined || status == .tentative {
    continue
}
```

- `event.currentUser` is `nil` for events with no invitees (personal events) → kept.
- Only `.declined` and `.tentative` are dropped; `.accepted`, `.pending`, `.unknown`,
  etc. are kept.

### Android — `shared/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt`

Add `CalendarContract.Instances.SELF_ATTENDEE_STATUS` to the projection, read it in the
loop, and drop declined/tentative after the existing all-day/availability checks:

```kotlin
val selfStatus = cursor.getInt(selfStatusIndex)
if (selfStatus == CalendarContract.Instances.ATTENDEE_STATUS_DECLINED ||
    selfStatus == CalendarContract.Instances.ATTENDEE_STATUS_TENTATIVE
) {
    continue
}
```

- `ATTENDEE_STATUS_NONE` (personal events) and `ATTENDEE_STATUS_INVITED` (un-answered)
  are kept.
- Guard against a null/absent column value the same way the existing columns are read.

## Testing

The existing all-day/availability filters live in the untested platform fetch layer and
have no unit tests. This change follows that pattern rather than restructuring for
testability. Verification:

- Manual check on both platforms: an event declined / marked "Maybe" disappears from the
  ring; an accepted or un-answered event still shows.
- Run the standard gate to confirm no regression:
  `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`.

A Robolectric test around the Android cursor mapping is possible if coverage there is
wanted later, but is out of scope by default to match the existing pattern.

## Out of scope

- Any user-facing toggle or setting.
- Changes to the `BusyInterval` model, persistence, or sync.
- Filtering based on availability (already handled) or on other participant statuses
  (delegated, completed, in-progress).
