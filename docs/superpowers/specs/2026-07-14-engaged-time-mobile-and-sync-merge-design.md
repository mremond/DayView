# Engaged time on mobile + cross-device merge — design

## Context and dependency

This is a follow-up to PR #77 (branch `claude/focus-time-calculation-57422f`), which introduced
two focus figures — strict **Focus** (`focusPresenceIntervals` → `focusedToday`) and lenient
**Engaged** (`focusSessionIntervals` → `sessionFocusedToday`) — and their full state / history /
sync-DTO plumbing. That work is **not yet merged**. Implementation of this spec must branch from
PR #77's branch (or from `main` once #77 has landed), not from an older base.

PR #77 produces the engaged figure **only in the desktop loop**, per-tick, from macOS
frontmost-app classification (`PresenceAccumulator`, lenient parameters, in
`composeApp/src/desktopMain/.../Main.kt`). Two gaps remain:

1. **Android never sets `focusSessionIntervals`**, so a mobile focus session yields engaged = 0 —
   the exact undercount the feature exists to fix. Android has no per-second in-process ticker
   while backgrounded and no frontmost classification, so it cannot reproduce the desktop
   mechanism, and it cannot produce the strict figure at all.
2. **The synced daily history archive is write-once per day.** If a phone and a desktop both
   record focus on the same day, their engaged intervals must merge (union) rather than one
   device clobbering the other.

> **Supersedes the PR #77 non-goal "not synced / desktop-only."** `main` (#72) began syncing the
> daily history archive, so engaged intervals already travel via the history record. The
> "no sync changes" non-goal in `2026-07-14-focus-session-time-design.md` is outdated and is
> replaced by the merge design in section 3 below.

## Goals

- Produce a meaningful **Engaged** figure on Android (strict stays desktop-only).
- Make same-day engaged (and strict) intervals from multiple devices **converge to their union**
  across all devices, without breaking the deliberate write-once history invariant.
- Persist Android's live/today engaged intervals so they survive restart and reach the archived
  day record at rollover.

## Non-goals

- **No strict figure on Android.** Frontmost-app classification is macOS-only (NSWorkspace).
  Android's `focusPresenceIntervals` stays empty.
- **No live cross-device merge of _today_.** Today is never archived; it is rebuilt live from
  local state and is not carried in the live sync document. Engaged-today therefore differs per
  device until the day rolls over and contributions sync — consistent with how strict focus
  already behaves. Convergence happens at the history layer, one day later.
- **No manifest retention bound in v1** (see Decisions).
- **No change to the Pomodoro countdown.** `pomodoroEnd` stays pure wall-clock.
- **No second ring.** Engaged remains a number, not an arc layer.

## Design

### 1. Mobile engaged derivation (session-window, no tick)

Android derives engaged intervals from the Pomodoro session window(s) themselves, computed at
session close — not per-tick.

A new pure function in `core`:

```
fun deriveEngagedIntervals(
    sessionStart: Instant,
    effectiveEnd: Instant,
    detours: List<DetourEpisode>,
): List<FocusPresenceInterval>
```

- `effectiveEnd = min(stopInstant, pomodoroEnd)` where `stopInstant` is the moment the session
  actually ended. This counts only the **effective declared time**:
  - **Early close** (`closePomodoro` before `pomodoroEnd`) → interval ends at the close instant.
  - **Abandon** (`stopPomodoro`) → interval `[sessionStart … stopInstant]` (today `stopPomodoro`
    records nothing; this spec adds the derivation there too).
  - **Overtime / break** (closed after `pomodoroEnd`) → capped at `pomodoroEnd`; the overrun does
    not count.
- The window is then **minus declared detours only** (`DetourEpisode` overlapping the window are
  subtracted, splitting `[sessionStart … effectiveEnd]` into 0..N pieces). Walking away without
  declaring a detour still counts as engaged — intentionally lenient, consistent with the figure's
  spirit.
- `sessionStart = pomodoroEnd − pomodoroMinutes`, both already in state at close time.

The function is platform-agnostic and unit-tested in isolation.

### 2. Wiring the derivation + Android persistence

A capability flag on `DayViewController`: **`derivesEngagedFromSessions: Boolean`** (default
`false`).

- **Android** constructs the controller with `true`. `closePomodoro(outcome)` **and**
  `stopPomodoro()` call `deriveEngagedIntervals(...)`, coalesce-append the result into
  `state.focusSessionIntervals` (see section 4), and `persistState()`.
- **Desktop** keeps `false`: the per-tick lenient accumulator remains the sole source. The flag is
  **required** — desktop's per-tick figure excludes sustained off-goal drift (≥ 120 s) and
  unobserved interruptions, producing intervals shorter than the raw session window; running the
  window derivation there would erase that nuance (union with `mergeIntervals` would swallow the
  drift gaps). Desktop behaviour is 100% unchanged.

**Persistence (Android).** `focusSessionIntervals` is added to the shared `DayPreferences`
snapshot as a day-scoped field, paired with `focusSessionDayKey`:

- Serialized/deserialized in `DayPreferencesStore` via the existing
  `encodeFocusPresence` / `decodeFocusPresence` (same `FocusPresenceInterval` shape).
- Day-scoped: reset at day rollover like `detours`/`detoursDayKey`, and archived into the outgoing
  day's record by `toHistoryRecord` (already carries `focusSessionIntervals` from PR #77).
- Seeded into initial state at startup (`initialFocusSessionIntervals`).

Desktop also writes this snapshot field (whatever the ticker last set) but **never reads it back** —
it keeps `DesktopPreferences.saveFocusSession`/`loadFocusSession` as its source of truth. The
redundant write is harmless and avoids conflict; refactoring desktop onto the snapshot is out of
scope.

**`App.kt` push path.** The existing `LaunchedEffect(focusSessionIntervals) { setFocusSessionIntervals(...) }`
would clobber the controller-computed intervals with the (empty) parameter on Android. When
`derivesEngagedFromSessions` is `true`, this push is **disabled** — the controller owns
`focusSessionIntervals`, seeded once via `initialFocusSessionIntervals`. Desktop is unchanged.

### 3. Cross-device merge — per-device focus side-channel

The write-once day record (`HistorySync`, `PUT /history/{key}` with `If-None-Match: *`, first
writer wins) is left **untouched**: calendar / detours / goal / clean-sessions still follow
first-writer-wins as today. Alongside it we add a per-device focus contribution channel that never
conflicts and unions on read.

- **Device identity.** Reuse the existing stable per-install id
  (`SecureKeyStore.deviceIdOrCreate`, 16 random bytes hex). No new identity.
- **Manifest.** New field `focusContributions: List<String>` in `SyncDocument`, each entry
  `"dayKey:deviceId"`. Merged by union exactly like `historyDays`
  (`(a + b).distinct().sorted()` in `SyncMerge`; seeded from base in `SyncMapper`). It converges
  automatically via the main document's `If-Match` CAS.
- **Blob key.** `HistoryKey.opaqueFocusKey(dayKey, deviceId)` — HMAC over `"$dayKey:$deviceId"`
  under a distinct derivation label (e.g. `dayview-focus-index-v1`), separate from the day-record
  index so the two blob families never collide.
- **Blob payload.** `FocusContributionDto { schemaVersion, dayKey, deviceId, focusPresence,
  focusSession }` (both as `List<PresenceDto>`), encrypted with the existing `HistoryBlobCodec`
  (AAD binding schema + dayKey; extend AAD with deviceId).
- **Write-once per `(dayKey, deviceId)`.** Each device only ever writes its own key → the PUT
  never 412s on a real conflict; the write-once invariant holds unchanged.

**Local storage — a separate `FocusContributionStore`.** The local `DayHistoryStore.write` is
itself **write-once** (`InMemoryDayHistoryStore` / the file store ignore a second write for the
same day). So we do **not** mutate the archived day record. Instead, a new
`FocusContributionStore` (file-backed, keyed by `"$dayKey/$deviceId"`) holds focus contributions:

- **Own contribution `(day, self)`** is written at archive/rollover from the device's **own**
  live intervals for that day — this is the authoritative thing the device uploads. It is only
  ever created from local work, never from a download, so a device can never re-upload another
  device's intervals as its own (provenance is guaranteed by the key origin).
- **Foreign contributions `(day, other)`** are written only by downloads.

**`FocusContributionSync`** (sibling of `HistorySync`, invoked in `SyncEngine` right after the
history reconcile), bounded per cycle like `HistorySync`:
- **Upload:** for each `(day, self)` in the local contribution store not yet in the manifest, PUT
  under `opaqueFocusKey(day, self)` and add `"day:self"` to the returned manifest (only confirmed
  uploads enter it).
- **Download:** for each `"day:device"` in the merged manifest with `device != self` not present
  locally, GET the blob and `contributionStore.write(day, device, dto)`.

### 4. Union on read + coalesce

A new pure function in `core`:

```
fun mergeIntervals(intervals: List<FocusPresenceInterval>): List<FocusPresenceInterval>
```

sorts by start and merges overlapping / adjacent intervals into a disjoint set. This is
**required**: `focusedTime` sums clipped intervals without de-overlapping
(`CalendarNetTime.kt`), so unioning two devices' intervals without coalescing would double-count.

The union is computed **at read time**, not written back into the write-once record. When the
history screens assemble a day (`openHistory`, `openHistoryDay`, week overview), the displayed
record's `focusPresenceIntervals` / `focusSessionIntervals` become
`mergeIntervals(record.ownIntervals + Σ contributions(day))` — computed separately for presence
and session. Including the record's own intervals keeps legacy days (archived before this feature)
and no-sync setups working with no contributions present. The operation is idempotent: a
contribution equal to the record's own intervals coalesces to the same result.

Android's per-session append (section 2) also runs through `mergeIntervals` so the live/today list
stays disjoint before it is ever archived or uploaded.

### 5. UI — data-driven visibility

Instead of a platform switch, gate the **Deep focus** row on the data: render it only when
`focusPresenceIntervals` is non-empty (equivalently `focusedToday > 0`). Consequences:

- Android Today (strict empty) shows **Engaged** only.
- A desktop-recorded day viewed in Android history (strict present via sync) shows **both**.
- **Engaged** renders whenever there is session data.

Labels and string resources from PR #77 are reused (strict = "Deep focus" / "Focus profond",
lenient = "Engaged" / "Temps engagé"); the maintainer may reword during implementation.

## Decisions

- **No manifest retention bound in v1.** `focusContributions` grows O(days × devices); `historyDays`
  already grows O(days) unbounded, so this is consistent. ~730 entries/year for two devices is
  acceptable. A sliding-window purge (server + manifest) is deferred as future work.
- **Desktop derivation strictly OFF.** No session-window fallback on desktop even if the ticker
  produced nothing for a session — YAGNI, and it would introduce a double-counting risk to manage.
- **Detours-only subtraction on mobile** (not abandoned-mid-session detection beyond `stopPomodoro`,
  not an explicit pause mechanism). Simplest, fully derived from already-persisted data.
- **Side-channel over full per-device records or server CAS.** Per-device *focus* blobs keep the
  blast radius to the two interval lists that actually need union, preserve write-once, and need no
  server change. Full per-device records would force a merge semantics for every field; server-side
  history CAS would break the deliberate write-once invariant and require a redeploy.

## Testing

- **`deriveEngagedIntervals`:** early close; overtime capped at `pomodoroEnd`; a detour mid-window →
  two pieces; abandon via `stopPomodoro`; empty when the window is fully covered by a detour.
- **`mergeIntervals`:** overlapping, adjacent, disjoint, and idempotence (merging an already-merged
  list is a no-op); pairs with `focusedTime` to prove no double-count.
- **`FocusContributionDto` round-trip** through `HistoryBlobCodec` incl. the deviceId-bound AAD;
  legacy records without the side-channel decode fine (empty contributions → record's own intervals).
- **`FocusContributionSync`:** two devices' same-day contributions union on both; a fresh third
  device downloads and unions all; write-once holds per `(day, device)`; manifest union merge.
- **Android persistence:** `focusSessionIntervals` round-trips through the `DayPreferences` snapshot,
  is day-scoped (reset at rollover), and reaches the archived record.
- **Non-regression:** desktop engaged path (per-tick + `DesktopPreferences`) unchanged;
  `derivesEngagedFromSessions = false` leaves `closePomodoro`/`stopPomodoro` untouched.
- ktlint + `:core:jvmTest` + `:composeApp:testDebugUnitTest` + `:composeApp:desktopTest` green.
