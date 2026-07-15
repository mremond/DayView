# Focus-session detail pop-up — design

**Date:** 2026-07-15
**Status:** Approved for planning

## Goal

Let the user hover (desktop) or tap (Android) a focus session on the countdown ring
and see what that session was: its **intention**, its **time range**, and a breakdown
of **engaged time vs deep-focus time**. Works on the today ring and on the history
day/week rings.

## Background — what exists today

- A focus session is a Pomodoro run. While it runs there is a single mutable
  `focusIntention`, a `pomodoroEnd`, and `pomodoroMinutes`. **Sessions are ephemeral:**
  on close the intention may be cleared, and each session's engaged intervals are merged
  into one day-wide `focusSessionIntervals` list, erasing per-session boundaries.
- Two focus interval layers already exist:
  - **Engaged time** = the session window minus declared detours → `focusSessionIntervals`
    (Android derives it per session via `deriveEngagedIntervals`; desktop feeds it per tick).
  - **Deep focus** = on-goal presence within the window → `focusPresenceIntervals`
    (desktop-tracked via `PresenceAccumulator`; may be empty on an Android-only day).
    Deep focus is a subset of engaged.
- The ring draws `focusPresenceIntervals` as bright mint arcs (`focusArcs` → `FocusArc`).
  Engaged time is currently only a total number, never drawn.
- Hover/tap infrastructure already exists on the ring for **calendar-busy arcs** and
  **detours** (`CountdownCircle`, `RingReadout`/`ringReadoutAt`, mouse `hoveredBusy`/
  `hoveredDetour` state, touch tap-to-toggle via `nextHoveredBusyOnTap`, touch long-press
  scrub). The detour detail pop-up (commit 6acd806) is the direct precedent for this
  feature: model → store → history codec → sync DTO → ring hit-test → hover/tap tooltip.

## Approach

Chosen: **lightweight per-session records, metrics derived at render time** (Approach A of
three considered; the alternatives — fat records that copy their own interval slices, and a
today-only in-memory log — were rejected for duplicating data / failing the today+history
scope respectively).

The one thing the app lacks is a durable per-session record. We add a small one and derive
everything else from interval lists we already keep.

### 1. Data model — `FocusSessionRecord`

New core type (`core/.../FocusSessionRecord.kt` or alongside `FocusIntervals.kt`), one entry
per closed session:

```kotlin
data class FocusSessionRecord(
    val start: Instant,        // pomodoroEnd - pomodoroMinutes
    val end: Instant,          // effective end: min(stopInstant, pomodoroEnd)
    val intention: String,     // intention active when the session ran
    val outcome: FocusClosureOutcome,
)
```

Persisted as a day-scoped list `focusSessionRecords: List<FocusSessionRecord>`, threaded
through exactly like `focusSessionIntervals` / `detours`:

- `DayPreferences` default (`emptyList()`), `DayPreferencesSnapshot`, `DayViewUiState`.
- `DayPreferencesStore`: a new preference key; encode/decode helper mirroring
  `encodeFocusPresence` — one line per record, epoch-millis for `start`/`end`, the enum
  `outcome` by name, and the intention escaped (it is free text and may contain the line/
  field separators). Reuse the day-key reset the other day-scoped lists use.
- `DayHistoryRecord` gains `focusSessionRecords`; `toHistoryRecord` clips records to the day
  window (drop records whose window falls outside `[windowStart, windowEnd]`), matching how
  the interval lists are clipped; `toFrozenUiState` passes them back for replay.

### 2. Capture

In `DayViewController.closePomodoro` **and** `stopPomodoro`, append a record built from the
session window, the intention **captured before** `focusIntentionAfterClosure` clears it, and
the outcome. Guard like `appendEngagedSession`: require a live `pomodoroEnd`; respect the
`min(stopInstant, pomodoroEnd)` effective end; reset the list on day-key rollover. No new
tracking machinery — every input already exists at close time. `closeFocusSnapshot`
(the desktop mini-window path in `CleanFocusSessions.kt`) gets the same append so that path
stays consistent.

### 3. Derivation (no stored durations)

Two render-time helpers reuse the existing `focusedTime` clip:

- `engaged(record) = focusedTime(record.start, record.end, focusSessionIntervals)`
- `deepFocus(record) = focusedTime(record.start, record.end, focusPresenceIntervals)`

Session windows are disjoint (no two Pomodoros overlap), so clipping the day-wide lists to a
session window attributes time to the correct session. Because these read the **merged**
day-wide lists, synced deep-focus from another device shows up correctly.

### 4. Ring rendering + interaction

- New `FocusSessionBand` (angle span, like `FocusArc`/`BusyBlockArc`), projected from records
  via the same window→angle math as `focusArcs`. Exposed off the controller as
  `focusSessionBandsState`, each band carrying its `FocusSessionRecord` (or an index into the
  record list) for lookup.
- Drawn as a **faint band** on the ring; the existing bright mint deep-focus arcs draw on top,
  unchanged. This makes engaged-vs-deep visible at a glance and gives a continuous,
  discoverable hover/tap target.
- Hit-test: nearest band containing the pointer/scrub angle (mirror the busy/detour hit-tests).
  Reuse `CountdownCircle`'s mouse-hover state for desktop and the tap-to-toggle path for
  Android. Extend `RingReadout` / `ringReadoutAt` to also carry the session under the angle so
  the touch long-press scrub readout surfaces it too.

### 5. Pop-up content

- **Intention** in mint, or a muted "No focus intention set" when blank.
- **Time range** `start–end` via `formatClockHm`, respecting `LocalUses24HourClock`.
- **Engaged** and **Deep focus** durations via `formatDurationHm`, each labelled. The
  deep-focus line is **hidden when zero** (e.g. an Android-only day with no presence data) so
  it never shows a bare "0m".
- A short **status** derived from `outcome` (e.g. "Completed" vs "Stopped early").
- New i18n strings in the Compose resources bundle, single-`%` formatting per repo convention.

### 6. Surfaces & sync

Because records live in `DayHistoryRecord` and flow through the same frozen-state projection,
the today ring, history day, and history week rings all get the pop-up with no per-surface
work.

**Sync requires changes in BOTH channels** (this is easy to miss — the first design pass only
caught the history DTO):

1. **History record channel** (`DayHistoryRecordDto`): add
   `focusSessionRecords: List<FocusSessionRecordDto> = emptyList()` and a new
   `FocusSessionRecordDto(start, end, intention, outcome)`; wire both directions in
   `HistoryRecordMapper`. `outcome` is serialized by enum name and defaults safely when absent.

2. **Focus contribution channel** (`FocusContributionDto`, the cross-device *merge* side
   channel): without this, a merged multi-device day would show combined engaged/deep
   *durations* (from unioned intervals) but bands/intentions from the local device only — the
   other device's focus would render as orphaned mint arcs with no owning band or pop-up. So:
   - `FocusContribution` (core) gains `records: List<FocusSessionRecord>`.
   - `FocusContributionDto` gains `records: List<FocusSessionRecordDto> = emptyList()`; wire
     `FocusContributionMapper` both ways.
   - `maybeArchivePreviousDay` writes `record.focusSessionRecords` into the contribution.
   - `withMergedFocus` concatenates records from all contributions (per-device windows are
     disjoint, so concat + sort by `start` — no coalescing, unlike the interval union).

**Do NOT bump `HISTORY_SCHEMA_VERSION`.** It is mixed into the AES-GCM AAD
(`dayview-history-v$VERSION`, `dayview-focus-v$VERSION`), so bumping it makes new and old peers
fail to decrypt each other's entire blobs. `SyncJson` is configured with
`ignoreUnknownKeys = true`, so additive defaulted fields are lossless in both directions with
the version pinned at 1 (new client ↔ old blob → default empty; old client ↔ new blob →
unknown key ignored). A version bump + migration is reserved for a genuinely *breaking* change
(removing/renaming a field, changing crypto, restructuring beyond what defaults express); such
a migration would need per-blob version metadata in the manifest for version-aware decryption,
or a download-decrypt-re-encrypt-reupload pass with a broken window for non-upgraded devices.
This feature is purely additive and needs none of it.

### 7. Testing

- **Core:** `FocusSessionRecord` encode/decode round-trip (incl. intention containing
  separators); capture on `closePomodoro`/`stopPomodoro` (intention captured pre-clear, early-
  stop effective end, day-rollover reset); the two derivation helpers, incl. the disjoint-
  window case and the empty-presence (Android-only) case.
- **Sync:** `DayHistoryRecordDto` and `FocusContributionDto` round-trips; decoding a payload
  with the new field **absent** yields an empty list; `withMergedFocus` concatenates records
  across contributions.
- **UI (desktop test harness):** tag-based assertions that a session-band hit-test surfaces the
  pop-up. No `stringResource` assertions (unresolved in `runComposeUiTest` on CI) — use tags /
  seeded data per the repo's Compose-UI-test gotchas.

## Scope guards

- Per-session **cleanliness** is not shown — the off-goal-per-session figure is not retained
  per session today (only a day-level ledger count). The pop-up shows `outcome` status only.
- No retroactive backfill: sessions closed before this ships have no records and simply show
  no band (their focus still renders as mint arcs, as today).
- The faint engaged band is the one visual change to the ring; if it reads as clutter it can be
  tuned or dropped without affecting the data model or the pop-up.
