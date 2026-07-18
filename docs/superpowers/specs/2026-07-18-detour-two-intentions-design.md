# Detours — two intentions, two doors

## Context

Declaring a detour serves two different moments, and the app currently flattens them into one
duration-shaped form:

1. **A detour that already happened.** You were pulled off the path, you came back, you want
   to record it. You know the motif and you know it ended *now*. What you do not know is a
   duration — you know a *boundary*: it started when the last thing you were doing ended.
2. **A detour you are entering.** You want the time counted. It starts *now* and has no
   determined end.

Both exist in `:core` today, but neither is well served:

- Case 1 defaults the start to `now − duration` (`detourDefaultStartMinutes`), so it asks for
  a guess instead of proposing the boundary it can compute.
- Case 2 is a `Start` button sitting inside the case-1 form, so entering a detour means
  crossing a form that is 60% irrelevant — at the exact moment your attention is being pulled
  away. It is also refused outright while a focus session runs
  (`if (state.pomodoroEnd != null || …) return`), which is precisely when it is needed, and it
  is refused silently.
- The native macOS app has case 1 only, in a degraded form: a 5/15/30/45/60 `Picker` with no
  start adjustment and no open detour at all.

## Goals

- Case 1 proposes a **range** anchored on the last known boundary, not a duration.
- Case 2 becomes a one-click toggle that asks nothing up front; the motif is requested **at
  stop**, when you actually know what it was and the form costs nothing.
- A running detour is allowed during a focus session and hollows out engaged time, on both
  engaged-time paths.
- One capture/edit form per platform instead of the current two.
- Shared design across macOS native and Compose; placement stays idiomatic per platform.

## Non-Goals (deferred)

- A global keyboard shortcut (macOS) or a quick-settings tile / persistent notification
  (Android). Reach is limited to surfaces that are already always visible.
- Pre-filling the motif at *start* time. It would display a possibly-wrong motif for the whole
  detour; pre-filling at stop is enough.
- Pausing the focus countdown during a detour. The countdown keeps running (see Decision 4).
- Any change to how detours affect the countdown or net time — they stay informational.

## Decisions (from brainstorming)

1. **Two doors, not one form with two buttons.** The two cases happen at different moments of
   attention and get different affordances.
2. **Motif at stop.** `startOpenDetour` accepts an empty motif; the motif is required when the
   detour is closed.
3. **Anchor, capped at 120 min.** Case 1's default start is the last boundary, never older
   than two hours.
4. **Focus continues, hollowed.** Starting a detour during a focus session does not stop or
   pause the countdown; the span is marked off-path and subtracted from engaged time — the
   same semantics the retroactive detour already has.
5. **4 h cap on any span derived from an open detour**, never auto-committed beyond that.
6. **Mini menu on the in-app surface**, direct toggle on the always-visible surfaces.

## Architecture

### `:core` — the anchor

A pure function in `Detours.kt`:

```kotlin
/**
 * Default start for a retroactive detour: the last boundary before [now] — the end of the
 * most recent detour or focus session — clamped so it is never older than [maxLookback]
 * nor earlier than the day window, and never later than [now].
 */
fun detourAnchorStart(
    now: Instant,
    detours: List<DetourEpisode>,
    focusSessions: List<FocusSessionRecord>,
    windowStart: Instant,
    maxLookback: Duration = 120.minutes,
): Instant
```

Result: `maxOf(lastDetourEnd, lastFocusEnd, now - maxLookback, windowStart).coerceAtMost(now)`.

The `windowStart` term stops the default reaching back before the day started (at 08:30 on a
day beginning at 08:00, the naive lookback would propose 06:30). When the anchor lands within
a minute of `now`, the proposed span floors at one minute — the form is editable and the user
adjusts.

`detourDefaultStartMinutes` is retired; it encoded the duration-shaped default this spec
replaces.

### `:core` — the open detour

```kotlin
fun startOpenDetour(category: String = "", description: String = "")
fun stopOpenDetour(category: String, description: String)
fun cancelOpenDetour()
```

- `startOpenDetour` drops two guards: the empty-category rejection and the
  `pomodoroEnd != null` rejection. The "only one open detour at a time" guard stays.
- `stopOpenDetour` commits an episode from `openDetourStart` to `now` with the supplied motif.
  The span is **capped at 4 h and floored at the start of the local day**, so a detour left
  open overnight can neither cross midnight nor produce a ten-hour episode.
- `cancelOpenDetour` clears the open state without committing — for a toggle hit by accident.

The exclusion is deliberately **asymmetric**: `startPomodoro` keeps its
`if (state.openDetourStart != null) return` guard. Entering a detour during a focus session is
a real event — you got pulled away. Starting a focus while a detour is open is not the mirror
of it: it means you are *back*, so the detour should end rather than coexist. Since ending it
requires a motif, the honest behaviour is to refuse and let the UI say to stop the detour
first.

Day rollover deliberately leaves `openDetourStart` alone: `openDetourStart` is not day-keyed
(unlike `detoursToday`), and silently discarding an open detour at midnight would lose real
data. The cap and the floor above are what make that safe, and the capped range is surfaced
for confirmation rather than committed.

The old single-argument `stopOpenDetour()` (which silently dropped the episode when the motif
was empty) is replaced by this pair.

### `:core` — hollowing the focus session

There are two engaged-time paths and **both** need the fix:

- **Android** derives engaged intervals from the session window
  (`derivesEngagedFromSessions`): `appendEngagedSession` carves with `detoursToday`. A focus
  session that closes while a detour is open would not be carved, because the episode is not
  committed yet. Fix: carve with `detoursToday` plus a **provisional episode** built from
  `openDetourStart..now`. `deriveEngagedIntervals` already clips cuts to the session window,
  so the later real commit cannot double-count.
- **macOS / desktop** measures presence per tick: `presence.observe(...)` has no knowledge of
  detours at all. Today a detour hollows engaged time only incidentally — when it moved you
  off an on-goal app. A rabbit-hole inside your editor still counts as engaged. Fix: pass
  `openDetourRunning` into `observe` and treat the tick as off-goal. **A declared detour
  outranks app inference.**

### UI — one form, three configurations

The codebase carries four near-identical forms: `DetourCaptureContent` and `DetourEditForm`
(Compose), `DetourCaptureSheet` and `DetourEditSheet` (Swift). All three moments ask for the
same three things — a motif, a range, a note — so each platform keeps **one** form,
parameterised:

| Configuration | Default range | Secondary action |
|---|---|---|
| Note a past detour | anchor → now | Cancel |
| Stop an open detour | start → now, capped at 4 h | Discard |
| Edit an existing episode | the episode | Delete |

The duration chips survive as a shortcut that moves the *start* with the end anchored at now —
the behaviour `DetourEditForm` already implements. A range whose extent was capped is flagged
in the form and always requires confirmation; it is never committed automatically.

### UI — surfaces

| Surface | Behaviour |
|---|---|
| macOS mini window | Direct toggle, one click. It already floats across Spaces, so it is the surface that makes case 2 worth having. |
| macOS menu bar | Direct items at the top of `MenuBarContent`, above the divider. Two clicks in practice (open the menu, click) — the cost of an always-present surface with no global shortcut. |
| macOS ring (`RingView.detourSection`) | `+ Detour` opens a mini menu. |
| Compose quick actions | The detour entry opens the same mini menu; `OpenDetourPanel` stays as the running-state panel, its stop button now opening the closure form instead of committing directly. |

The mini menu has two items:

- **Start a detour** — immediate, nothing is asked.
- **Note a past detour…** — the ellipsis signals a sheet follows.

While a detour runs, the first item becomes **Stop the detour · 12 min**. One rule to
remember, rather than a button that changes nature with state.

The in-app surface costing two clicks for case 2 is deliberate: if the main window is open and
focused, you are already in the app. The one-click path lives where it matters.

### UI — the ring

An open detour draws as a **provisional** arc on the existing detour lane, from its start to
now, growing each tick. `detourBodies` takes a list of episodes, so it needs only the
provisional episode appended. The arc is outlined rather than filled, so an unclosed span
never reads as a recorded one.

## Testing

- `detourAnchorStart`: no history (falls back to lookback), lookback older than the window
  start, anchor from a detour, anchor from a focus session, anchor newer than both, anchor
  within a minute of now.
- `startOpenDetour` during an active focus session; `stopOpenDetour` with a span over 4 h and
  over midnight; `cancelOpenDetour` commits nothing.
- `appendEngagedSession` with a detour still open at session close.
- Presence: an open detour forces off-goal while the frontmost app is on-goal.
- Compose UI: the mini menu's two items, the running-state label, the capped-range flag. Per
  the repo's UI-test constraints, assert on test tags and seeded data, never on
  `stringResource` text.

## Localization

New strings go to both `values/strings.xml` and `values-fr/strings.xml` — French falls back to
English silently otherwise.
