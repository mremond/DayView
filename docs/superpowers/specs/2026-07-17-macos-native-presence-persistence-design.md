# macOS Native — Presence persistence (Path B, Phase 11)

## Context

Phase 10a gave the native macOS app engaged-time tracking: while a Focus runs, the frontmost
app is sampled once a second, classified against the on-goal apps, and accumulated into
presence intervals that draw as mint arcs with a "Focus H h MM" total. The 10a final review
found that **none of it survives a relaunch**, and that the recovery path was inert:

- `focusPresenceIntervals` has **no key** in `:core`'s `DayPreferencesStore` — the Compose/JVM
  app persists it out-of-band through `DesktopPreferences.saveFocusPresence`/`loadFocusPresence`,
  deliberately kept "outside the shared snapshot" because it is high-frequency and macOS-only.
  The native app has no equivalent, so presence intervals never reach disk at all.
- `focusSessionIntervals` **is** in the shared store, but `DayViewNative.create()` never passes
  `initialFocusSessionIntervals`, and `DayViewController`'s init discards the loaded
  `base.focusSessionIntervals` when `derivesEngagedFromSessions` is false (it is, natively). The
  persisted value is dropped at launch, which is what makes `PresenceCoordinator.restore()`
  — designed to reseed the accumulators — effectively dead code on macOS.
- The session's `setFocusPresenceIntervals`/`setFocusSessionIntervals`/`setSessionOffGoal`
  intentionally do not persist, so there is no per-second write storm. Nothing else fills the
  gap.

This phase closes both halves: a write path for presence, and a launch seed for both lists.

## Goals

- Persist the presence and session intervals natively, at the JVM's cadence (structural change,
  or every 30 s).
- Seed both lists into the controller at launch so `PresenceCoordinator.restore()` genuinely
  reseeds the accumulators and a relaunch mid-session continues the run.
- Keep the day-scoped staleness rule: intervals stored under a different day load as empty.

## Non-Goals

- No change to `DayPreferencesSnapshot`, the shared store schema, or the sync payload.
- No change to the presence algorithms, accumulator parameters, or the drift/resume detectors.
- The drift/resume latches stay transient (a reminder is a moment, not persisted state).
- No change to the JVM/Android side.

## Decisions (from brainstorming)

1. **Out-of-band keys in the same macOS preferences file**, mirroring the JVM's design, rather
   than adding fields to the shared snapshot — so the shared schema and the sync payload cannot
   be affected.
2. **The session pair gets distinct macOS-only key names** (see the finding below) rather than
   literally reusing the JVM's, which collide with the shared store's own keys.

## A key-collision finding (why the session keys differ from the JVM's)

`DesktopPreferences` wraps the *same* DataStore as `DayPreferencesStore` and declares
`KEY_FOCUS_SESSION_DAY = "focus_session_day"` / `KEY_FOCUS_SESSION = "focus_session"` — the
**exact strings the shared store already uses**. So on the JVM those "out-of-band" session
accessors are not separate storage: they read and write the shared keys. Only
`focus_presence_day`/`focus_presence` are genuinely new keys.

Replicating that natively would be fragile. `DayPreferencesStore.persist` writes
`prefs[focusSessionDayPrefKey] = snapshot.focusSessionDayKey`, and nothing sets
`focusSessionDayKey` when `derivesEngagedFromSessions` is false — which is the native
configuration — so it stays `-1`. A throttled write of today's day key would then be clobbered
back to `-1` by the next `persistState()` (a goal edit, a detour, a focus close), and the
stored intervals would load as stale-and-empty. The JVM carries the same latent fragility; we
should not inherit it.

**Therefore:** presence uses the JVM's key names (no collision — the shared store has no such
keys), and the session pair uses distinct macOS-only names.

| Purpose | Key | Note |
|---|---|---|
| Presence day | `focus_presence_day` | same as JVM |
| Presence intervals | `focus_presence` | same as JVM |
| Session day | `mac_focus_session_day` | macOS-only; avoids the shared `focus_session_day` |
| Session intervals | `mac_focus_session` | macOS-only; avoids the shared `focus_session` |

The shared `focus_session*` keys keep behaving exactly as they do today (written by
`persistState`, read into `initialSnapshot`, used by history/sync); this phase neither reads
them for seeding nor writes them.

## Architecture

### `:core` — a `PresencePersistence` seam

Mirroring the established injection pattern (`CalendarSource`, `FrontmostAppProvider`,
`DockAttentionProvider`):

```kotlin
/** Day-scoped presence storage; dayKey is -1 when nothing is stored. */
data class StoredPresence(
    val dayKey: Long,
    val presence: List<FocusPresenceInterval>,
    val session: List<FocusPresenceInterval>,
)

interface PresencePersistence {
    suspend fun load(): StoredPresence
    suspend fun save(dayKey: Long, presence: List<FocusPresenceInterval>, session: List<FocusPresenceInterval>)
}

object NoopPresencePersistence : PresencePersistence   // empty load, no-op save
```

`DayViewSession` takes it as a final constructor parameter (default `Noop`, so every existing
call site and test is unaffected) and saves on **the JVM's cadence**, evaluated inside the
existing per-tick presence block:

- immediately when either list's size changes (a run opened or closed — the JVM's "structural
  change"), or
- at most every 30 s otherwise.

Saves are launched into the session's existing scope so the 1 Hz tick never blocks on disk.
The day key written is `dayKeyOf(state.now)`, the same value the coordinator is driven with.

### `macosMain` — the store

`macosDayPreferences()` currently returns only `DayPreferences` and hides its DataStore. It
gains a companion factory that also exposes the store — for example returning a small pair or a
`MacosPreferences` holder — so both the existing `DayPreferencesStore` and the new persistence
can share one DataStore instance over the same file. (One DataStore per file is required;
creating a second instance on the same path is unsupported and would risk corruption.)

`MacosPresencePersistence` implements the interface over that DataStore using the four keys
above and the existing `encodeFocusPresence`/`decodeFocusPresence` codecs. `load()` returns
empty lists when the stored `dayKey` is not today — the JVM's staleness rule, applied at read
time so a stale write can never resurrect yesterday's arcs.

### Launch seeding

`DayViewNative.create()` loads once (`runBlocking`, as it already does for the initial
snapshot) and passes the result to the controller:

```kotlin
val stored = runBlocking { presencePersistence.load() }
val controller = DayViewController(
    preferences,
    scope,
    initialSnapshot = ...,
    initialFocusPresenceIntervals = stored.presence,
    initialFocusSessionIntervals = stored.session,
)
```

`DayViewSession.init` already calls `presence.restore(state.focusPresenceIntervals,
state.focusSessionIntervals, dayKeyOf(state.now))` — seeding the controller is precisely what
turns that existing call from a no-op into a real reseed.

## Data flow

```
launch: MacosPresencePersistence.load()  [stale day -> empty]
  -> DayViewController(initialFocusPresenceIntervals, initialFocusSessionIntervals)
  -> DayViewSession.init -> PresenceCoordinator.restore(...)   (previously inert)
tick: coordinator result -> controller setters (in-memory, as today)
  -> if list size changed, or >= 30s since the last save:
       scope.launch { persistence.save(dayKey, presence, session) }
```

## Testing / done criteria

- **`:core:jvmTest`**, with a fake `PresencePersistence` and the existing clock seam:
  - a structural change (a run opening) saves immediately;
  - subsequent ticks that only extend the open run do **not** save until 30 s have passed, then
    do;
  - the saved `dayKey` is today's;
  - a controller seeded with stored intervals reports them in `focusArcs`/`focusTotalLabel`
    from the first tick (the restore path actually works).
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test: with
  an on-goal app configured, run a Focus until mint arcs and a "Focus H h MM" total appear;
  **quit and relaunch** — the arcs and total are still there and the session continues
  accumulating rather than restarting.

## Risks

- **Two DataStore instances on one file would corrupt it.** The refactor must hand the *same*
  instance to both `DayPreferencesStore` and `MacosPresencePersistence`; creating a second
  `PreferenceDataStoreFactory.create` for the same path is the failure mode to avoid. Called
  out for the implementation and worth a reviewer check.
- **Write frequency.** Worst case one write per 30 s during a focus, plus one per structural
  change; the JVM has run this cadence for months against the same encoding.
- **Day rollover mid-session.** The accumulators already reset on a `dayKey` change; the store
  writes whatever day the tick carries, and the staleness rule discards the old day at the next
  load. No special handling needed.
- **Encoding compatibility.** `encodeFocusPresence`/`decodeFocusPresence` are the shared codecs
  the JVM already uses for these lists, so a file written by one is readable by the other should
  the paths ever converge.

## Roadmap after this phase (context only)

Remaining before cutover, per the parity checklist: the drift notification banner and
menu-bar-only ritual surfacing (10b follow-ups), the focus-session detail popup, must-dos,
sounds, history archiving, the day-over screen, sync, i18n, and packaging/CI.
