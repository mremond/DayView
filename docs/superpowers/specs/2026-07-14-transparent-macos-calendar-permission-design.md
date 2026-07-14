# Transparent macOS calendar permission

## Problem

On macOS the Net Time settings screen shows a **"Grant calendar access"** button
whenever `CalendarSource.hasPermission()` is not `GRANTED`. The EventKit helper's
`PERMISSION` command can return three values — `GRANTED`, `DENIED`,
`NOTDETERMINED` — but the Kotlin side collapses every non-`GRANTED` reply into
`false`.

A freshly spawned helper process starts in `NOTDETERMINED` even when macOS TCC
already holds the grant for the responsible process (the persisted-grant case
after a rebuild). So the button appears, the user clicks it, `requestPermission()`
silently resolves against the existing grant, and Net Time starts working — with
no OS popup. The click is redundant: the app could have issued that request
itself. When access is genuinely denied, the same button is a dead end because
macOS will not re-prompt once a decision exists.

## Goal

Handle the permission transparently:

- When Net Time is enabled and the status is undetermined, request access
  automatically. Silent when the grant already exists (no click); the normal OS
  popup only for a true first-time grant.
- When access is denied, replace the dead-end button with guidance and a link to
  the system privacy settings.

Android keeps its existing explicit-button flow — its runtime-permission model
wants a user gesture, and it already works. This is a macOS/desktop improvement.

## Design

### 1. Tri-state authorization (core)

In `core/.../CalendarModel.kt`:

```kotlin
enum class CalendarAuthStatus { GRANTED, DENIED, NOT_DETERMINED }
```

Add to the `CalendarSource` interface, with a default so Android and
`NoopCalendarSource` need no change:

```kotlin
fun authorizationStatus(): CalendarAuthStatus =
    if (hasPermission()) CalendarAuthStatus.GRANTED else CalendarAuthStatus.DENIED
```

`hasPermission()` stays for the calendar read paths (`availableCalendars`,
`busyIntervals`).

A small pure helper, unit-tested, encodes the auto-request decision:

```kotlin
fun shouldAutoRequestCalendarAccess(
    status: CalendarAuthStatus,
    netTimeEnabled: Boolean,
    alreadyRequested: Boolean,
): Boolean =
    netTimeEnabled && status == CalendarAuthStatus.NOT_DETERMINED && !alreadyRequested
```

### 2. Desktop source maps the real value

In `composeApp/.../CalendarSource.desktop.kt`, override `authorizationStatus()`
to map the helper's existing `PERMISSION` reply:

```kotlin
override fun authorizationStatus(): CalendarAuthStatus =
    when (command("PERMISSION").firstOrNull()) {
        "GRANTED" -> CalendarAuthStatus.GRANTED
        "NOTDETERMINED" -> CalendarAuthStatus.NOT_DETERMINED
        else -> CalendarAuthStatus.DENIED
    }
```

`hasPermission()` can delegate to `authorizationStatus() == GRANTED`. The helper
Swift already emits all three strings — no helper change.

### 3. Auto-request in the probe (App.kt)

In the `NetTimeProbe` `LaunchedEffect`, read `authorizationStatus()` instead of
`hasPermission()`. Keep a session latch:

```kotlin
var calendarAccessRequested by remember { mutableStateOf(false) }
```

Inside the probe's `withContext(Dispatchers.Default)` block, when net time is
enabled:

1. Read `status = authorizationStatus()`.
2. If `shouldAutoRequestCalendarAccess(status, enabled, calendarAccessRequested)`:
   set the latch, call `requestPermission()` (blocking on the helper semaphore is
   fine off the UI thread), then re-read `status`.
3. `GRANTED` → read `busyIntervals` / `availableCalendars` as today.
4. `DENIED` → no read; report denied.
5. `NOT_DETERMINED` (still, e.g. request in flight) → treat as not-yet-granted,
   no read.

Reset `calendarAccessRequested` to `false` when net time is disabled, so
re-enabling can retry.

The latch prevents the frequently re-running probe from firing concurrent
`requestPermission()` calls. Once the request resolves to `GRANTED` or `DENIED`
the status is no longer `NOT_DETERMINED`, so it does not loop.

### 4. Denied state surfaces a Settings link

Extend the read-result plumbing so the UI can tell denied from
not-yet-determined:

- `DayViewController.updateNetTimeData(..., permissionDenied: Boolean = false)`.
- New state field `netCalendarPermissionDenied: Boolean = false`, set from the
  probe result and cleared on the granted path.

`NetTimeSettingsScreen` gains three branches:

- granted → calendar list (unchanged)
- denied → short "Calendar access is turned off" message + **"Open System
  Settings"** button (new test tag)
- otherwise (transient undetermined) → the existing neutral prompt text, no
  dead-end button

New expect/actual `openCalendarPrivacySettings()`:

- desktop: `ProcessBuilder("open",
  "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars")`
- Android: open the app's own settings screen
  (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`)
- Noop: no-op

Wired through an `onOpenCalendarSettings` callback from `App.kt`, mirroring the
existing `onRequestCalendarAccess` wiring.

New user-facing strings (with existing i18n locales): denied message and
"Open System Settings" button label.

### 5. Testing

- **Core:** `authorizationStatus()` default derives `GRANTED`/`DENIED` from
  `hasPermission()`; `shouldAutoRequestCalendarAccess` truth table.
- **Desktop:** helper-reply → `CalendarAuthStatus` mapping.
- **Controller:** `updateNetTimeData(permissionDenied = true)` sets
  `netCalendarPermissionDenied`; the granted path clears it.
- **Compose (`desktopTest`):** denied state renders the "Open System Settings"
  button (via test tag); granted renders the calendar list. Follow the harness
  rules in the repo (tags/seeded data, no `stringResource` assertions).

## Out of scope

- Any change to Android's request flow (explicit button + platform hook).
- Any change to the Swift EventKit helper.
- macOS "Add Only" / write-only remediation beyond routing it to the denied
  branch (write-only maps to `DENIED`, so the Settings link applies).
