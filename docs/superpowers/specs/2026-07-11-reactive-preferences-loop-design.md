# Reactive preferences loop — design

Date: 2026-07-11
Status: Approved (pending spec review)

## Problem

External components can change persisted state while the app UI is open, but the
open UI does not reflect those changes reactively:

- On Android, the Quick Settings tile (`DayViewFocusTileService`) and the focus
  notification actions can start or stop a Focus by writing preferences. They do
  so through their **own** `AndroidDayPreferences` instances.
- `DayViewController` reads preferences only once, at construction. It never
  re-reads them, so an already-open app shows stale Focus state.

Today this is papered over by a blunt hack: `MainActivity.recreateIfFocusChanged()`
compares `pomodoroEndMillis` against a remembered value on `onResume` /
`onWindowFocusChanged` and, if it differs, calls `recreate()` — destroying and
rebuilding the whole Activity and composition. That works but:

- throws away transient UI state (scroll position, the unsaved goal-deadline
  draft, whether Settings was open, keyboard focus);
- only reacts on window-focus transitions, not live;
- only watches `pomodoroEndMillis`, not other externally-changed fields.

The `DayPreferences.observe()` hook exists but is (a) wired only in tests, and
(b) on Android backed by a per-instance in-memory map that cannot see writes made
through a different instance — so it cannot carry the tile's write to the app.

## Goal

Replace the `recreate()` hack with a live, in-place reactive loop: external
preference writes update the open UI without destroying it, preserving transient
UI state.

Non-goals (YAGNI / separately planned):

- Migrating preferences to AndroidX DataStore (planned as a later item).
- Changing the time model (`Clock` / `Long` millis).
- Touching sound/drift/break logic.
- Any change to `DesktopDayPreferences` — desktop already subscribes via
  `observe()` in `Main.kt`, and only the controller writes there, so it has no
  equivalent bug.

## Design

### 1. `DayViewController` owns observable state and reconciles external changes

- State ownership moves into the controller:
  `var state by mutableStateOf(preferences.snapshot().toUiState(initialNowMillis)); private set`.
  The controller already lives in `commonMain`, and `compose.runtime` is already a
  `commonMain` dependency, so this adds no new dependency. Reading/writing a
  `mutableStateOf` outside composition is valid, so unit tests keep working.
- **Setter ordering flips to update-first, persist-second.** Each mutating method
  becomes `state = state.copy(...)` **then** `preferences.saveX(...)`. This is what
  makes the feedback loop safe: on Android the change listener fires synchronously
  during `apply()`, re-entering the controller via `onPreferencesChanged`; because
  in-memory state already matches the just-written snapshot, the reconciliation is
  a no-op. Validation/coercion (`coerceIn`, `take`, `normalized`) is unchanged.
- **Setters return `Unit`.** The App no longer needs the returned state. One test
  (`controllerOwnsTheCompleteFocusLifecycle`) currently reads `startPomodoro()`'s
  return value; it will be updated to read `controller.state`.
- New method:

  ```
  fun onPreferencesChanged(snapshot: DayPreferencesSnapshot) {
      state = state.copy(
          // persisted fields adopted from the snapshot (validated via the shared helper):
          startMinutes = ..., endMinutes = ..., showSeconds = ...,
          soundSettings = ..., goalTitle = ..., goalDeadlineMillis = ...,
          pomodoroMinutes = ..., pomodoroEndMillis = ..., focusIntention = ...,
          // transient / UI-only fields preserved:
          //   nowMillis, goalDeadlineText, lastFocusClosure, destination
      )
  }
  ```

  So an external Focus start updates `pomodoroEndMillis` / `focusIntention` live
  without pulling the user out of Settings or clearing a half-typed deadline draft.
- The field coercion currently inline in `DayPreferencesSnapshot.toUiState` is
  extracted into a shared helper so both construction and reconciliation validate
  identically (`startMinutes` in `0..23:29`, `endMinutes` in `start+30..23:59`,
  `pomodoroMinutes` in `5..180`, `soundSettings.normalized()`).
- Idempotency: `mutableStateOf` uses structural equality, so a no-op merge causes
  no recomposition.

### 2. `App.kt` wires the loop

- `val state = controller.state` (observed read). Delete the local
  `var state by remember { mutableStateOf(controller.state) }` mirror and every
  `state = controller.setX()` reassignment — actions become plain `controller.setX()`.
- Subscribe once:

  ```
  DisposableEffect(controller, preferences) {
      val stop = preferences.observe { controller.onPreferencesChanged(it) }
      onDispose(stop)
  }
  ```

- `onFocusAlarmChange` continues to fire only from explicit user actions, never
  from `observe()`. External writers schedule/cancel their own alarms (the tile
  already calls `FocusAlarmScheduler.schedule`), so there is no alarm gap.

### 3. `AndroidDayPreferences.observe()` becomes cross-component

- Replace the per-instance `mutableMapOf` observer map (and `nextObserverId`,
  and the map half of `preferencesChanged`) with a
  `SharedPreferences.OnSharedPreferenceChangeListener`. Because all app components
  share one process and one prefs file (`getSharedPreferences` returns the same
  process-wide object per name; the manifest sets no `android:process`), a listener
  registered by the app fires when the tile or a notification receiver writes
  through its own instance.

  ```
  override fun observe(observer: (DayPreferencesSnapshot) -> Unit): () -> Unit {
      val listener = OnSharedPreferenceChangeListener { _, _ -> observer(snapshot()) }
      storage.registerOnSharedPreferenceChangeListener(listener)
      observer(snapshot())
      return { storage.unregisterOnSharedPreferenceChangeListener(listener) }
  }
  ```

- SharedPreferences holds listeners weakly; the returned disposer closure captures
  `listener` and the composition retains the disposer until `onDispose`, keeping a
  strong reference for the subscription's lifetime.
- Widget refresh is unchanged: the same save methods that call
  `DayViewWidget.updateAll` today keep doing so (writer-side effect), gated by the
  existing `notifyWidgets` flag. Only the observer-notification path changes.
- Threading: the listener is invoked on the thread that committed the change. All
  current writers (UI, tile `onClick`, notification receivers) run on the main
  thread, so callbacks arrive on the main thread and may mutate Compose state
  directly. If a future writer commits off the main thread, the subscription would
  need to marshal; noted, not handled now.

### 4. `MainActivity` drops the `recreate()` hack

- Remove `displayedFocusEndMillis`, `recreateIfFocusChanged()`, the
  `onWindowFocusChanged` override (it only performed the recreate check), and the
  recreate check in `onResume`.
- `onResume` keeps `DayViewWidget.updateAll(applicationContext)` and
  `restoreActiveFocusAlarm()`.
- Live `observe()` now covers the case the recreate hack handled, in place and
  without losing UI state.

### 5. Tests

- `DayViewControllerTest` (common):
  - `onPreferencesChanged` adopts external persisted fields (e.g. a Focus started
    elsewhere sets `pomodoroEndMillis` / `focusIntention`).
  - it preserves each transient field: `goalDeadlineText` draft, `destination`,
    `lastFocusClosure`, `nowMillis`.
  - a self-write reconciles to structurally equal state (no clobber).
  - Extend the test `InMemoryDayPreferences` to emit to registered observers on
    each save (it currently inherits the interface default that fires once), so the
    external-write path can be driven end-to-end.
  - Update `controllerOwnsTheCompleteFocusLifecycle` to read `controller.state`
    instead of the (now `Unit`) `startPomodoro()` return value.
- `AndroidDayPreferencesTest` (Robolectric) — key regression: a write through a
  **second** `AndroidDayPreferences` instance notifies an observer registered on
  the **first** instance. This is exactly the tile → app scenario.
- Green gate: `./gradlew ktlintCheck testDebugUnitTest desktopTest assembleDebug assembleRelease`.

## Data flow

```
External write (tile / notification)
  -> AndroidDayPreferences#saveX (own instance)
     -> SharedPreferences.apply()  [same process-wide prefs object]
        -> OnSharedPreferenceChangeListener (app's instance)
           -> observer(snapshot())
              -> DayViewController.onPreferencesChanged(snapshot)
                 -> state = merge(persisted from snapshot, transient preserved)
                    -> Compose recomposes the open UI in place

User action in UI
  -> controller.setX()
     -> state = state.copy(...)          [update first]
     -> preferences.saveX(...)           [persist second]
        -> listener re-enters onPreferencesChanged -> no-op (state already matches)
```

## Risks / edge cases

- **Feedback loop on self-writes** — mitigated by update-first ordering plus an
  idempotent merge; structural-equality state means no spurious recomposition.
- **Listener lifetime** — SharedPreferences' weak listener reference is kept alive
  by the retained disposer.
- **Draft clobbering** — `goalDeadlineText` and other transient fields are never
  overwritten by `onPreferencesChanged`.
- **Alarm scheduling** — unaffected; external writers own their alarms, and
  `MainActivity.restoreActiveFocusAlarm()` still runs on resume.
```
