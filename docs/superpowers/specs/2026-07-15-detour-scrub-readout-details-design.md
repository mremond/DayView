# Detour detail pop-ups: show category, description and duration

## Problem

When you inspect a detour on the ring, the detail pop-ups do not give a useful summary:

- The **mobile touch-scrub readout** shows only the absolute time range
  (`start – end · duration`) — no category ("nature"), no description.
- The **desktop hover tooltip** shows category + description, but also the absolute
  `start – end` range, which is redundant with the time already read elsewhere.

The request: the detour detail pop-up should show **nature (= category)**, **category**
and **description**. Since "nature" and "category" are the same thing, the informative
fields are **category** and **description**, plus **how long** the detour lasted.

## Time rule (from follow-up)

Absolute clock times ("l'heure", the `start – end` range) are only worth showing when a
tooltip would otherwise be **empty** — i.e. when there is no label to display. When there
*is* a label, show the **duration** instead of the range: the duration is the useful part
and the absolute moment is already available (the scrub banner shows the clock time under
the cursor; the desktop user is pointing at the ring).

A detour always has a non-blank category (required at capture and edit; category-less
rows are dropped on decode). So a detour pop-up is **never empty** → it never shows the
absolute range, and always shows **category + description + duration**.

## Scope

Both detour pop-ups get the same content, in the same order:

1. **Category** (the "nature") — layer-colored (detour palette).
2. **Description** — only when non-empty. Mobile: single line + ellipsis (the banner is
   compact and a description can be up to 200 chars).
3. **Duration** — `formatDurationHm(body.end - body.start)`, muted/secondary. Replaces
   the absolute `start – end` range.

`DetourBody` already carries `category`, `description`, `start`, `end`, so there is no
data-model, serialization, or capture-form change. `formatDurationHm` already exists (it
is the `%3$s` of `detour_time_range`), so no new string resource is needed.

Busy and focus readout lines are **out of scope** — this change only touches the detour
branch of each pop-up.

## Changes

- **Mobile scrub readout** (`RingScrubReadout`, `DayViewTodayScreen.kt` ~1550): replace
  the detour branch's single `detour_time_range` line with category (detour color) +
  description (muted, 1 line, ellipsis) + duration (muted). Bound the banner width
  (`widthIn(max = 280.dp)`) so a long category/description cannot stretch it off screen.
  Both `widthIn` and `TextOverflow` are already imported in the file.
- **Desktop hover tooltip** (`HoverTooltip` detour branch, `DayViewTodayScreen.kt`
  ~1458): keep category + description; replace the `detour_time_range` line with the
  duration only.
- Consider a small shared helper (e.g. `DetourReadoutDetails`) so the two branches do not
  duplicate the category/description/duration layout, parameterized by the category color.

## Testing

Extend `RingScrubTest`: when the scrub angle lands on a detour body, assert the readout
shows the category text and, for an episode with a description, the description text.
Assert the absolute `start – end` range is **not** shown for a detour (duration only).
Use seeded data / test tags rather than asserting `stringResource` output (desktop-test
harness constraint).

## Out of scope

- No new "nature" data field (nature == category).
- No change to the detour capture/edit forms or the detour list dialog.
- No change to busy / focus readout lines.
