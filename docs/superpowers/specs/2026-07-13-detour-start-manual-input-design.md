# Detour Start Manual Input and 5-Minute Snap — Design

**Date:** 2026-07-13
**Status:** Approved

## Problem

Adjusting a detour's start time only works through − / + buttons stepping ±5 minutes.
Two pain points:

1. Reaching a distant time (e.g. moving a start by 2 hours) takes dozens of taps;
   there is no way to type the value directly.
2. The ±5 steps run from an arbitrary anchor. The quick-capture default start is
   "now − duration" (e.g. 14:32), so stepping produces misaligned values
   (14:37, 14:42, …) instead of round ones (14:35, 14:40, …). The edit form's
   duration stepper has the same defect when an episode's duration is not a
   multiple of 5 (17 → 22 → 27 …).

## Scope

Applies to both detour forms in `DetoursUi.kt`:

- **Quick-capture dialog** (`DetourCaptureContent`): the start time stepper.
- **Edit/retroactive-add form** (`DetourEditForm`): the start time stepper **and**
  the duration stepper.

Out of scope: other steppers in the app (work window, goal deadline), which have
aligned anchors already.

## Design

### 1. Tap-to-edit time value (shared composable)

The time text displayed between − and + becomes tappable. On tap it turns into a
small `BasicTextField` styled like `GoalTextField` (same colors, cursor, error
border), pre-filled with the current value and fully selected, numeric-friendly
keyboard. Behavior:

- **Enter / focus loss with valid text** → commit the parsed value.
- **Invalid text on commit** → revert to the previous value, exit edit mode.
- **While editing**, invalid text shows the red error border (same as
  `GoalTextField`'s `isError`).
- In the quick-capture dialog, a manual commit **pins** the start
  (`startPinned = true`), exactly like the − / + buttons do.

The composable is shared by both forms and by the duration value in the edit form
(duration edits parse a plain minute count rather than a wall-clock time).

### 2. Tolerant wall-clock parsing (pure function)

`parseMinutesOfDay(text: String, use24Hour: Boolean): Int?` accepts, after
trimming:

- `14:32`, `9:05` — colon-separated
- `14h32`, `14h` — French `h` separator
- `1432`, `932` — bare digits (last two digits are minutes when length ≥ 3)
- `14`, `9` — bare hour → minutes `:00`
- In 12-hour mode, an optional `am`/`pm` suffix (case-insensitive, with or
  without a space): `2:30 pm` → 14:30, `12am` → 0:00, `12pm` → 12:00.
  Without a suffix the value is interpreted as 24-hour.

Rejected (returns `null`): hours > 23, minutes > 59, empty text, anything else.
The result is always in `0 .. 23*60+59`.

Duration input uses a simpler `parseDurationMinutes(text: String): Int?`:
a plain integer minute count, or `1h30` / `1:30` forms; valid range 5 .. 720
(matching the form's existing floor and ceiling).

### 3. Directional 5-minute snap (pure function)

`snapToFive(current: Int, direction: Int): Int` (direction is +1 or −1):

- If `current` is already a multiple of 5 → step ±5.
- Otherwise → move to the **nearest multiple of 5 in the pressed direction**:
  from 14:32, `+` → 14:35 and `−` → 14:30.

Applied to:

- Start steppers (both forms): result clamped to `0 .. 23*60+55`.
  Button enablement: `−` active while value > 0; `+` active while value < 23:55.
- Duration stepper (edit form): result clamped to `5 .. 720`.
  Button enablement: `−` active while value > 5; `+` active while value < 720.

The quick-capture "ends now" default start is otherwise unchanged: the start
keeps tracking `now − duration` until pinned by a nudge or a manual entry.

### 4. Testing

- **commonTest** (pure functions): parse — all accepted forms, 12-hour suffixes,
  boundary values (0:00, 23:59), rejects (24:00, 9:75, garbage, empty);
  snap — misaligned anchors both directions, aligned anchors, clamping at both
  ends for start and duration ranges.
- **desktopTest** (`DetourCaptureTest`): tap the time text → field appears,
  type a value, commit → displayed start updates and confirm passes the pinned
  start; − / + from a misaligned default lands on a multiple of 5. Assertions
  use test tags and seeded data, never localized `stringResource` text.

## Components

| Unit | Responsibility |
|------|----------------|
| `parseMinutesOfDay` / `parseDurationMinutes` (new, common) | Text → minutes, pure, locale-format tolerant |
| `snapToFive` (new, common) | Directional multiple-of-5 stepping, pure |
| `EditableTimeValue` composable (new, `DetoursUi.kt`) | Tap-to-edit display/field toggle, commit/revert lifecycle |
| `DetourCaptureContent` | Uses the composable + snap for its start stepper |
| `DetourEditForm` | Uses the composable + snap for start and duration steppers |

New test tags in `DayViewTestTags` for the editable value and its edit field.
