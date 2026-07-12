# Off-window detours — honest accounting without changing the ring

## Context

A detour ([`DetourEpisode`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt))
is a hand-declared stretch of time spent off the path toward the goal. Since the
"leisure as detour" framing (see the longer-detours-leisure work), a detour can
legitimately be an evening activity — a series at 21:00, an evening of reading — which
falls outside the work window (default 08:00–18:00).

The day ring is a full 360° = the work window; it is a countdown of the *work* day.
`-90°` is the window start (top) and the sweep returns there at 360°. Because the whole
circle is consumed by the window, off-window time has **no angle to map to**.

Two behaviors currently lose or distort data silently:

1. **Off-window bodies are dropped from the ring.** `detourBodies` in
   [`Detours.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/Detours.kt)
   returns `null` for any episode whose midpoint is outside `[windowStart, windowEnd]`.
   Yet `detoursTotal` and `detourSources` fold **every** episode — so the "Détours · H h MM"
   total and the source tally already count off-window time that the ring omits. The dial
   and the total quietly disagree.
2. **The morning clamp shortens a long quick-capture.** `addDetour` in
   [`DayViewController.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt)
   does `if (start < windowStart && end > windowStart) start = windowStart`, silently
   trimming a detour whose true start predates the window.

## Goal

Make off-window detour time **honest** without changing the ring model. The ring stays a
pure work-window countdown; it continues to drop off-window bodies. Everywhere the total
already counts that time, the UI now explains it, so the dial and the numbers can never
disagree. Long quick-captures keep their true span.

This is an integrity fix, not a visualization change.

## Non-goals (explicitly out of scope)

- Drawing off-window time on the ring arc.
- A separate gutter/band/orbit for off-window bodies.
- Extending or adapting the visualized window to cover declared off-window activity.
- Any change to the countdown, net time, or Focus semantics.

## The organizing idea

A single predicate — *"is this episode's midpoint outside the work window?"* — drives all
three surfaces so they can never disagree:

- the **ring** drops the body (existing behavior),
- the **total** accounts for its duration as an "off-window" figure,
- the **list** tags the row.

Today that test is inlined in `detourBodies`. We lift it into a shared pure function.

## Changes

### A. Shared off-window logic (`Detours.kt`, pure functions)

- Extract the midpoint-outside-window test into a named function, e.g.
  `detourMidpointOutsideWindow(episode, windowStart, windowEnd): Boolean` (midpoint
  `< windowStart || > windowEnd`, matching the current inline check exactly). Refactor
  `detourBodies` to call it — **no behavior change to the ring.**
- Add `offWindowDetoursTotal(windowStart, windowEnd, episodes): Duration` — the summed
  `duration` of episodes whose midpoint is off-window. By construction this is exactly
  "the part of `detoursTotal` that is not on the dial," so the hint in C reconciles the
  two numbers.
- Both are pure and unit-testable; no new domain types.

### B. Fix the morning clamp (`DayViewController.addDetour`)

- Remove `if (start < windowStart && end > windowStart) start = windowStart`. The
  quick-capture keeps its full span.
- **Midnight floor safeguard:** floor `start` at the start of the local day so a
  pathological long capture (e.g. a 12 h duration captured just after the window opens)
  cannot cross into the previous day and break the day-scoped invariant and the
  time-only list display. For realistic ≤ 60 min picker durations this never triggers.
- Consequence: such an episode may now legitimately leave the ring. That is correct — it
  is accounted for by C (hint), counted by `offWindowDetoursTotal`, and tagged by D.
- Derived state still recomputes through the existing `commitDetours` path.

### C. Off-window duration hint (today screen total line)

- Expose `detoursOffWindowTotalToday: Duration` in the UI state (computed via
  `offWindowDetoursTotal(dayWindow.first, dayWindow.second, detoursToday)`), and plumb it
  into `CountdownCircle` next to `detoursTotal`.
- When it is `> Duration.ZERO`, render the total as `Détours · 3h 20 (45 min hors plage)`
  using a new two-placeholder string `detours_today_off_window`
  (arg 1 = total, arg 2 = off-window duration), added to both `values/strings.xml` (en)
  and `values-fr/strings.xml` (fr). When it is zero, keep the existing single-placeholder
  `detours_today` line unchanged.
- Same muted styling as today — subtle, not an alarm.

### D. Tag off-window rows (`DetourListDialog`)

- Pass `windowStart`/`windowEnd` into `DetourListDialog` (from `state.dayWindow`).
- For each row where `detourMidpointOutsideWindow` is true, show a subtle `hors plage`
  tag next to the time range, using a new string `detour_off_window_tag` (en + fr).
- No change to editing, adding, or deletion.

## String resources

| key | en | fr |
|-----|----|----|
| `detours_today_off_window` | `Detours %1$s (%2$s off window)` | `Détours %1$s (%2$s hors plage)` |
| `detour_off_window_tag` | `off window` | `hors plage` |

Final wording follows the app's existing UI-language conventions; the table shows intent.

## Data flow

```
detoursToday (episodes)
   │
   ├─ detourBodies(window, …) ──────── on-window bodies only  → ring (unchanged)
   ├─ offWindowDetoursTotal(window, …) ─ off-window duration   → total hint (C)
   ├─ detoursTotal(…) ───────────────── all durations          → total (unchanged)
   └─ detourMidpointOutsideWindow(…) ── per-episode flag        → list tag (D)
```

`detoursTotal = (sum of on-window body durations) + offWindowDetoursTotal`, which is what
the hint communicates.

## Testing (pure-first, per repo conventions)

- `offWindowDetoursTotal`: episode fully inside window; fully outside; straddling with
  midpoint inside; straddling with midpoint outside; empty list. Duration matches the sum
  of the off-window episodes only.
- Invariant: for a given window, an episode absent from `detourBodies` is exactly one
  counted by `offWindowDetoursTotal` (drop ⇔ off-window), and vice versa.
- `addDetour`: a capture whose start predates the window keeps its full duration (no
  longer shortened); a pathological long duration is floored at day start rather than
  crossing into the previous day; `commitDetours` recomputes totals.
- No `stringResource` assertions in UI tests (unresolved under `runComposeUiTest` on CI):
  cover the pure functions and controller state; use tags/seeded data for any UI check.
- Platform suites follow the existing split (`testDebugUnitTest` + `desktopTest`).
