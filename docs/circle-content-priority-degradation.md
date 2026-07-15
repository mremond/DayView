# Countdown circle: content priority & graceful degradation

## Problem

The center of the countdown ring (`CountdownCircle` in
[`DayViewTodayScreen.kt`](../shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt))
stacks up to seven rows of content. The header and the big numerals scale with the ring
(`countdownCounterScale`), but every row below the numerals — Net / busy / Focus / Détours /
clean-session pips — uses **fixed font sizes and fixed spacers**. On a small ring
(mini/compact window, small phone, non-maximized desktop) those rows keep their full size and
overflow the ring stroke, colliding with the moment marker and the detour bodies.

We want the interior to **degrade gracefully**: as the ring shrinks, lower-priority rows first
get smaller, then collapse to a compact form, then disappear — always keeping the interior
inside the dial and readable.

## Priority ranking (reference)

Keep from top; drop from the bottom first.

| Rank | Row | Notes |
|------|-----|-------|
| 1 | **Countdown numerals** (`06 h 56`) + "IL RESTE" header | Never drops — the whole point of the app. |
| 2 | **Net 6 h 26** (net remaining) | The "real" time left after calendar commitments. |
| 3 | **Détours 2 h 10** (today's distraction total) | |
| 4 | **Focus 9 min** | |
| 5 | **30 min occupé** (busy sub-line) | Secondary detail under Net. |
| 6 | **Clean sessions / streak pips** | Ambient reward, lowest stakes. |

The seconds line follows its existing `showSeconds` toggle and sits with the numerals (rank 1).

## Approach: cull + scale (option B)

Two dimensions of degradation, applied in order as the ring shrinks:

1. **Scale.** Rows 2–6 respect `counterScale` (they currently don't). This alone removes most
   overflow on mid-size rings — the secondary rows shrink in step with the numerals and their
   spacers instead of staying at 14/13/11 sp.

2. **Compact collapse.** Before a row is dropped, Net can collapse to a compact glyph form —
   the value without its label (e.g. `6 h 26` in mint, no "Net " prefix). This buys one more
   step of survival at small sizes. (Applied to Net specifically; other rows collapse straight
   from scaled → dropped.)

3. **Priority cull.** Compute a height budget for the interior from the ring diameter, then walk
   the rows bottom-up by priority (rank 6 → rank 2), dropping whole rows until the stack fits the
   budget. Rank 1 is never dropped.

### Height budget

The interior content must fit within the ring's inner diameter minus the stroke and a margin.
Derive an available content height from `circleSize` (the ring diameter already computed in the
`BoxWithConstraints` at `countdownCircleSize(minOf(maxWidth, maxHeight))`), reserve the space the
numerals + header need at the current `counterScale`, and give the remainder to rows 2–6. Measure
each candidate row's scaled height and include it only if it still fits; otherwise it and all
lower-priority rows are culled.

### Degradation sequence (illustrative)

From large to tiny ring:

- **Large:** all rows, full scale.
- **Medium:** all rows, scaled down with `counterScale`.
- **Small:** clean-session pips drop → busy sub-line drops → Focus drops.
- **Smaller:** Détours drops; Net collapses to compact `6 h 26`.
- **Tiny:** only the countdown numerals + header remain.

Exact thresholds fall out of the height-budget fit, not hard-coded breakpoints — so the behavior
stays correct across phone, mini window, compact desktop, and Supernote without per-device tuning.

## Non-goals

- No change to what appears **below** the ring (the detour chip, `+ DÉTOUR`, must-dos count).
- No reordering or restyling of rows beyond what scale/collapse requires.
- No new user setting — degradation is automatic from available size.

## Affected code

- [`DayViewTodayScreen.kt`](../shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt)
  — the center `Column` in `CountdownCircle` (rows 4–7 currently fixed-size).
- [`CountdownScaling.kt`](../shared/src/commonMain/kotlin/fr/dayview/app/CountdownScaling.kt)
  — home for the height-budget / row-fit helper, kept pure and unit-testable alongside
  `countdownCounterScale`.
