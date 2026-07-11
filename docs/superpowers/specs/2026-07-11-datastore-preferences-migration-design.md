# DataStore Preferences Migration — Design

## Problem

`DayPreferences` is backed by three hand-maintained implementations:

- `AndroidDayPreferences` (SharedPreferences)
- `DesktopDayPreferences` (`java.util.prefs`)
- `DefaultDayPreferences` (no-op)

Costs today:

- **Duplicated key/default logic** across Android + desktop, kept in sync by hand.
- **Blocking disk I/O** on first access (SharedPreferences / `java.util.prefs`), potentially on the main thread.
- Reactivity is provided by a **custom `observe(callback)`** contract each platform re-implements.

The reactive loop itself already landed (`DayViewController.onPreferencesChanged` collects `observe()`, `state` is `mutableStateOf`), so this work is a **storage-backend swap**, not a re-plumb of the UI loop.

## Decisions (confirmed with user)

1. **Async API** — `DataStore` has no synchronous read. The interface exposes a `Flow` of snapshots and suspend writes. The already-existing `observe(callback): () -> Unit` shape is preserved as a thin adapter over the Flow so current consumers change minimally.
2. **Migrate existing user data** — no reset. Android via `SharedPreferencesMigration`; desktop via a one-shot `DataMigration` reading `java.util.prefs`.

## Design

### Dependency

`libs.versions.toml` → `androidx.datastore:datastore-preferences-core:1.2.1` (KMP artifact, targets jvm + android) in `commonMain`. Pulls `okio` transitively for path-based construction.

### Interface (commonMain)

Replace the 15 `load*`/`save*` + `snapshot()` with:

```kotlin
interface DayPreferences {
    val snapshots: Flow<DayPreferencesSnapshot>
    suspend fun saveDayRange(startMinutes: Int, endMinutes: Int)
    suspend fun saveShowSeconds(showSeconds: Boolean)
    suspend fun saveSoundSettings(settings: SoundSettings)
    suspend fun saveGlobalGoal(title: String, deadlineMillis: Long?)
    suspend fun savePomodoro(durationMinutes: Int, endMillis: Long?)
    suspend fun saveFocusIntention(intention: String)

    // Adapter kept so non-suspend consumers migrate incrementally.
    fun observe(scope: CoroutineScope, observer: (DayPreferencesSnapshot) -> Unit): Job
}
```

### Common implementation

Single `DayPreferencesStore(dataStore: DataStore<Preferences>)` in `commonMain`:

- `snapshots = dataStore.data.map { it.toSnapshot() }`
- each `save*` = `dataStore.edit { … }` writing the same string keys as today (so migration is a straight copy).

`DataStore` **construction** stays per-platform (path + migration differ) and is injected:

- `androidMain`: path `filesDir/datastore/dayview.preferences_pb`; `SharedPreferencesMigration(context, "dayview_preferences")` mapping identical keys.
- `desktopMain`: path under user config dir; `DataMigration<Preferences>` that reads `Preferences.userNodeForPackage(...)` once, writes into DataStore, returns cleaned.
- `DefaultDayPreferences`: in-memory `MutableStateFlow`, no persistence (used by previews/tests).

### Reactive loop (already present)

`DayViewController` keeps optimistic in-memory `state`, persists via `scope.launch { store.save*() }`, and reconciles external writes by collecting `snapshots`. The self-write guard added in commit `127dd05` stays. No UI change.

### Three Android system edges (widget, tile, alarm)

Non-coroutine callbacks (`AppWidgetProvider.onUpdate`, `TileService`, `BroadcastReceiver.onReceive`) bridge with `runBlocking { store.snapshots.first() }`. Reads are single-shot and fast; these callbacks already run off the app UI.

## Testing

- `DayPreferencesStore` snapshot round-trip in `commonTest` with an in-memory/temp-file DataStore.
- Android migration test: seed SharedPreferences, assert values surface through `snapshots`.
- Desktop migration test: seed `java.util.prefs`, assert same.
- Keep existing `DayViewControllerTest` green (its fake `DayPreferences` gains a `MutableStateFlow`).

## Out of scope

- iOS target (isolated `expect`/`actual` makes it a follow-up).
- Encrypting stored preferences.

## Risks

- `datastore-preferences-core` 1.2.1 is stable but the KMP surface pulls okio — verify the desktop packaged DMG still builds.
- `runBlocking` at the three Android edges: acceptable for one-shot reads; revisit with `goAsync()` if ANRs appear.
