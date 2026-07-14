# Toast system & centralized error handling — design

## Goal

Give DayView a transient feedback mechanism (toasts) and consolidate error
handling. Today the app has only **persistent** banners (`TodayNotices` for
calendar/sync conditions) and a sync status line inside Settings; there is no
transient feedback and several errors are swallowed silently.

The toast system must cover: action confirmations, undoable actions, transient
errors, and manual-sync success. Error handling must add a central reporting
path, catch currently-swallowed errors, keep a clean transient/persistent
split, and log errors for debugging.

Shared across Android + desktop (Compose Multiplatform); no Android-native
`Toast`.

## Architecture & components

Responsibilities are split between `core` (logic, no Compose) and `composeApp`
(rendering). Each unit is independently testable.

### `core`

- **`AppEvent` (sealed) + `ToastKind` (enum)** — the semantic event model, pure
  data, no text and no Compose. Example:
  `AppEvent.Toast(kind = ToastKind.DetourRemoved, arg = "Social")`.
  `AppEvent.Toast` is **data-only — it carries no lambda** (see Undo).
- **`AppEventBus`** — wraps a `MutableSharedFlow<AppEvent>` with a small buffer
  so `post()` is non-suspending and callable from anywhere. Exposes
  `events: Flow<AppEvent>` and `post(event)`. Single entry point.
- **`Log`** — an `expect object` with `error(tag, message, throwable?)` and
  `info(...)`. Actuals: Android → `android.util.Log`; desktop → `System.err`
  with stacktrace.
- **`reportTransient(area, error, toast)`** — convenience that logs *and* posts a
  toast, replacing scattered `runCatching {}.getOrDefault` at one-off failure
  sites with a single line.

### `composeApp`

- **`DayViewToastHost`** — a Material `SnackbarHost` (reusing its proven queue,
  duration, action and accessibility machinery) with a **fully custom renderer**
  `DayViewToast`, overlaid at the bottom of `DayViewApp`, available on every
  screen.
- **`ToastKind → localized text + severity + action` mapping** — lives in the UI
  layer. It resolves EN/FR strings via `stringResource` and attaches the undo
  action by calling a controller method.

### Wiring

`AppEventBus` is created at the root and injected into `DayViewController`
(constructor, defaulting to a no-op bus so existing tests are unaffected), into
`SyncCoordinator`, and used from `App.kt` effects. Key boundary: `core` emits
neutral **semantic** events; all localization and styling stay in the UI.

## Data flow

1. An emitter (controller, `SyncCoordinator`, `App.kt` effect) calls
   `appEventBus.post(AppEvent.Toast(kind, arg))`.
2. A root `LaunchedEffect` collects `appEventBus.events`, maps the kind to a
   **localized** `ToastVisuals`, and calls `hostState.showSnackbar(visuals)`.
3. Material handles the queue/duration; `DayViewToastHost` renders the custom
   toast.
4. If the toast carries an action and it is triggered, the undo callback runs.

### Carrying severity through Material

Define `class ToastVisuals(message, severity, actionLabel, duration) :
SnackbarVisuals`. Pass it to `showSnackbar(visuals)`; the renderer reads it back
via `data.visuals as ToastVisuals`. Severity (success/error/info) and the
already-resolved text travel through Material's queue with no parallel map.

### i18n

`core` never emits text. The UI mapping does, e.g.:

```
ToastKind.DetourRemoved      → ToastVisuals(stringResource(toast_detour_removed, arg), Success, actionLabel = UNDO)
ToastKind.ObligationRemoved  → ToastVisuals(stringResource(toast_obligation_removed, arg), Success, actionLabel = UNDO)
ToastKind.SyncSucceeded      → ToastVisuals(stringResource(toast_synced), Success)
ToastKind.SoundPreviewFailed → ToastVisuals(stringResource(toast_sound_failed), Error)
ToastKind.SaveFailed         → ToastVisuals(stringResource(toast_save_failed), Error)
```

All new strings added in EN **and** FR (parity is enforced in this repo).

### Undo — pure events, UI-side wiring

`AppEvent.Toast` stays data-only (no lambda). For undoable actions the
controller keeps the last removed item and exposes
`restoreLastRemovedDetour()` / `restoreLastRemovedObligation()`. The UI mapping
attaches the action: `kind = DetourRemoved → onAction =
{ controller.restoreLastRemovedDetour() }`. One undo at a time ("last removed"),
matching the transient nature of a toast and keeping `core` serializable and
testable without Compose.

Concretely, `removeDetour` no longer deletes outright: it records the removed
episode (with its index), posts `AppEvent.Toast(DetourRemoved, arg = category)`,
and `restoreLastRemovedDetour()` reinserts it at its original index. Same shape
for obligation removal.

## Error handling

### Transient vs persistent (no duplication)

- **Persistent** = a durable condition the user resolves via a setting →
  existing `TodayNotices` **banner** (calendar permission off, calendar
  unreadable, sync `Failed`/`KeyError`). These are **not** toasted; we only add
  **logging** where it is missing.
- **Transient** = the result of a discrete action or a one-off failure →
  **toast** (confirmations, undo, manual-sync success, sound-preview failure,
  save failure).
- **Sync edge case:** a manual "Sync now" that **succeeds** → success toast
  (nothing shows this today outside Settings); a failure stays covered by the
  persistent banner (+ log), no toast, to avoid duplication.

### Currently-swallowed sites to address

1. **Sound preview** (`soundPlayer.play` in Settings) — unguarded →
   `runCatching { … }` + `reportTransient("sound", e, ToastKind.SoundPreviewFailed)`.
2. **Keystore/prefs I/O** in `scope.launch(Dispatchers.IO)` (`storeConfig`,
   `storeKey`, `clear`, `persistState`) — unguarded → wrap +
   `reportTransient("storage", e, ToastKind.SaveFailed)`. A silently-failing key
   or config save is misleading.
3. **Calendar read** (`App.kt`, already `getOrDefault(emptyList())`) — stays
   **persistent** (the `netCalendarError` banner); we only add `Log.error` at the
   failure point. No toast (per the rule).
4. **Manual sync** — `SyncCoordinator` already knows success/failure; post
   `SyncSucceeded` on success. Failures keep feeding the status/banner, with
   `Log.error`.

### Logging

`Log.error(tag, message, throwable)` on every caught error (transient or
persistent), in addition to the visual feedback. `expect/actual`: Android →
`android.util.Log.e`, desktop → `System.err` with stacktrace. The `tag`/`area`
identifies the source.

### Central path

`reportTransient` is the single path for "one-off error → log + toast",
eliminating scattered, inconsistent `runCatching`. Persistent errors keep their
current path (controller state → banner) but gain logging.

## Rendering, accessibility & placement

- **Placement:** a `SnackbarHost(hostState)` in a `Box` overlaying `DayViewApp`,
  bottom-aligned, with `safeDrawingPadding()` and a margin. Main window only —
  not the desktop mini-window or widgets.
- **Custom `DayViewToast(data)`:** same visual language as `TodayNotices` —
  `RoundedCornerShape(12.dp)`, `colors.panel` background with an accent dot
  colored by severity: Success → `colors.mint`, Error → `colors.red`, Info →
  `colors.cloud`/`muted`. Layout: `[dot] message (colors.cloud, 13sp) …
  [optional ACTION]`. The action button (e.g. "UNDO") in `colors.mint`,
  `Role.Button`, `minimumInteractiveComponentSize()`. Enter/exit animation from
  Material's `SnackbarHost`.
- **Durations:** short (~4s) by default; toasts with an action → `Long` to allow
  time to undo; never `Indefinite`.
- **Accessibility:** `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`
  and a `contentDescription` including the text so screen readers announce it;
  the action carries an `onClickLabel`.
- **Queue:** default `SnackbarHostState` behavior (one at a time, others wait).
  No over-engineering.

## Testing

- **`core` (commonTest):** `AppEventBus` emit/collect;
  `restoreLastRemovedDetour()`/`…Obligation()` reinsert at the correct index;
  `removeDetour` posts `AppEvent.Toast(DetourRemoved)` via a fake bus;
  `reportTransient` logs and posts.
- **`composeApp` (desktopTest):** the host shows a toast on an incoming event
  (`DayViewTestTags.Toast`); the undo action invokes the callback
  (`ToastAction`); severity styling smoke test. Follows the repo pattern
  (tags/seeded data, no `stringResource` assertions).
- **i18n parity** EN/FR for new strings; the usual gate
  (`ktlintCheck` + Android + desktop tests) must pass.

## Out of scope

- Multiple simultaneous toasts / stacking.
- Persisting a toast history.
- Undo beyond the single last-removed item.
- Replacing the existing persistent `TodayNotices` banners.
