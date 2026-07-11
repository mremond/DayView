# Synchronization Android and Desktop

## Summary

DayView must maintain the same state on both Android and macOS without losing its offline functionality. Both applications remain usable offline and synchronize their modifications as soon as the network is available.

Synchronization concerns business state and shared preferences. Platform-specific elements, such as menu bar icon, Android permissions, or programmed notifications, remain local.

The current implementation does not perform any synchronization: Android stores data in `SharedPreferences`, while Desktop uses `java.util.prefs.Preferences`. The common interface `DayPreferences` serves as a good entry point to introduce a synchronized layer without moving the business logic outside of `commonMain`.

## Target Experience

After associating both devices with the same account, the user will see:

- start and end times for the day;
- global goal and deadline;
- display preferences and sound settings;
- focus duration and intention;
- current session focus state.

A local modification appears immediately on the screen, then on the other device in a few seconds when connected. Without network, it is stored and sent later.

Starting or pausing a Focus on one device will update the other. Each device calculates the remaining time from a common deadline; no countdown is sent every second.

## Scope of First Version

Inclues:

- association of Android and Desktop to the same identity;
- bidirectional synchronization;
- offline functionality;
- real-time updates when applications are open;
- reconnection on launch and return to first screen;
- deterministic resolution of concurrent modifications;
- local reprogramming of alarms and notifications after a distant modification of Focus.

Outside initial scope:

- complete history of Focus;
- multi-apparatus statistics;
- sharing between multiple users;
- collaborative editing;
- saving secrets or settings specific to external integrations;
- continuous synchronization when both applications are completely stopped.

## Shared Data and Local Data

| Domain | Synchronized | Remains local |
| --- | --- | --- |
| Day | start, end | time zone of the device |
| Display | display seconds | window size and position |
| Sound | activation, reminders, interval, volume | system audio state |
| Goal | title, deadline | widget rendering |
| Focus | id, intention, duration, state, start, and end | programmed alarms and notifications |
| Desktop | — | menu icon monochrome, launch on opening session |
| Android | — | permissions, widget configuration, and tile |

Data is stored in UTC (`epochMillis`) instants. Daily hours are expressed in minutes local since midnight, as they describe a routine local and not an absolute instant.

## Data Model

The first version can use a document per user. Preferences and Focus have distinct versions to prevent simultaneous modifications.

```kotlin
data class SyncedDaySettings(
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineMillis: Long?,
    val pomodoroMinutes: Int,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)

enum class SyncedFocusStatus {
    IDLE,
    RUNNING,
    PAUSED,
    AWAITING_OUTCOME,
}

data class SyncedFocusSession(
    val sessionId: String,
    val status: SyncedFocusStatus,
    val intention: String,
    val durationMinutes: Int,
    val startedAtMillis: Long?,
    val endsAtMillis: Long?,
    val remainingMillisWhenPaused: Long?,
    val revision: Long,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)
```
`sessionId` distinguishes between successive Focus sessions. `revision` imposes an order on session transitions. `updatedByDeviceId` helps ignore echoes and facilitates debugging without exposing the device name.

The state `AWAITING_OUTCOME` represents a timer arrived at its end, but not yet closed with “Finished”, “Advanced” or “To retry”. The closure then resets the shared state to `IDLE`.

## Proposed Architecture

Local storage is the immediate source of truth. A coordinator observes local changes, transmits them to the remote service and applies distant changes to local storage.

```text
Compose interface / widget / menu bar
                    |
                    v
             DayViewController
                    |
                    v
        DayPreferences local-first
          |                    |
          v                    v
 stockage local        SyncCoordinator
                               |
                               v
                       RemoteSyncStore
                               |
                               v
                       service of data
```

Proposed contracts:

```kotlin
interface RemoteSyncStore {
    suspend fun pull(): RemoteDayViewState?
    suspend fun pushSettings(settings: SyncedDaySettings): SyncResult
    suspend fun pushFocus(focus: SyncedFocusSession): SyncResult
    fun observe(onState: (RemoteDayViewState) -> Unit): () -> Unit
}

interface SyncCoordinator {
    suspend fun start()
    suspend fun syncNow()
    suspend fun stop()
}
```

Network calls should not be placed in composables. User actions write first to local storage; the coordinator then processes a persistent queue of operations.

A failure of the remote service must never block the timer.

Eventually, it will be preferable to separate in local storage the settings, goal, and focus session. The current `DayPreferences` interface can remain as a facade during this migration.

## Synchronization Cycle

### At startup

1. Display immediately the local state.
2. Start observing the remote service.
3. Send any remaining local operations.
4. Receive the remote state.
5. Resolve versions, apply retained changes and notify the UI.
6. Reprogram local effects necessary, especially alarms on Android.

### After a local modification

1. Validate and save locally.
2. Notify immediately the UI, widget or menu bar.
3. Add an idempotent operation to the queue.
4. Try sending without blocking the user.
5. Remove the operation from the queue after confirmation by the server.

### After a remote modification

1. Compare domain, session, and version with local state.
2. Ignore duplicates and old versions.
3. Store retained version locally without re-sending as new action.
4. Notify observers.
5. Update platform-specific effects.

## Conflicts and Idempotence

For ordinary preferences, the first version uses the rule “last modified by the server winner”, regardless of each domain. The server clock must provide `updatedAtMillis` to avoid depending on device clocks desynchronized.

For Focus, business rules are prioritized:

- a lower revision can never replace a higher revision of the same session;
- repeated actions with the same operation ID produce no new effects;
- an older session cannot be replaced by a newer one;
- a stopped or closed focus confirmation cannot be undone by a later `RUNNING` state;
- concurrent starts are decided by the server, and the client losing adopts the retained session.

Each write to the remote service carries a unique operation ID. The server applies mutations atomically.

## Focus, Alarms, and Notifications

The service only synchronizes instants and business transitions. Android and Desktop continue to produce locally display, sound, and notifications.

When an app receives a distant Focus:

- `RUNNING` with a future deadline: program or replace the local alarm;
- `PAUSED`: cancel the alarm and show the frozen time;
- `IDLE` or session closed: cancel the alarm and notifications associated;
- deadline already passed: move to `AWAITING_OUTCOME` without re-sending multiple signals.

An identifier derived from `sessionId` prevents modifying an existing Focus. Multiple devices can signal locally the end of a same Focus; this duplication is acceptable initially, but closing with “Finished”, “Advanced” or “To retry” must be recorded only once.

## Identity, Security, and Confidentiality

The user must explicitly activate synchronization. A connection via link magic or code unique usage avoids introducing a personal DayView password. An association by QR code can simplify adding the second device, but does not replace an identity retrievable.

Minimum requirements:

- HTTPS traffic only;
- tokens stored in Trousseau macOS and secure Android storage;
- server-side access control limiting each account to its own data;
- possibility of disconnecting on one device without erasing the other;
- explicit action to delete synchronized data from the account;
- no intention, token or user content in logs;
- arbitrary device identifiers, not derived from hardware.

Local data remains available after disconnection, except if the user also requests their deletion. The interface must clearly explain this distinction.

## Service Selection

A service providing authentication, transactional base and subscription to changes reduces work significantly. Supabase is a suitable candidate thanks to PostgreSQL, access policies by line and real-time streams. Firebase is another viable option.

The code business logic should depend only on `RemoteSyncStore`. This contract allows replacing the provider, using a fake server in tests, and keeping a variant of DayView entirely local.

iCloud alone is not an adapted solution since Android must access the same data.

## Visible States in UI

Add a “Synchronization” section to settings with:

- activation and connection to account;
- synchronization state (“Up-to-date”, “Syncing…”, “Offline” or “Action required”);
- date of last successful synchronization;
- action “Synchronize now”;
- disconnection from this device;
- deletion of the account and remote data.

An error in the network remains discreet and does not block usage. A resolved conflict automatically displays no modal dialogue. An authentication failure persists, displaying an action to reconnect.

## Implementation Plan

### Step 1 — Local Model and Storage

- add session Focus identifiers persistently;
- separate revisions from settings and focus sessions;
- introduce a queue of idempotent local operations;
- cover migrations from current preferences;
- test the functionality locally without remote service.

### Step 2 — Contracts and Fake Server

- add `RemoteSyncStore` and `SyncCoordinator` in `commonMain`;
- write an implementation memory deterministic for tests;
- test start-ups, reconnections, conflicts, and concurrent modifications;
- verify that a network failure does not create a loop of retransmissions.

### Step 3 — Authentication and Real Server

- configure the chosen provider and its access rules;
- implement connection on Android and Desktop;
- store tokens securely;
- branch remote data retrieval.

### Step 4 — Real-time and Platform Effects

- observe changes from the remote service when the application is active;
- resynchronize upon return to first screen and after reconnection;
- update widget, tile, menu bar, alarms, and notifications;
- add synchronization states to settings.

### Step 5 — Robustness

- test time desynchronization, changes of timezone, and changes of hour;
- test concurrent actions and delivery in the wrong order;
- test restart during an operation in queue;
- add a strategy for new attempts with exponential delay;
- document data deletion and export.

## Acceptance Criteria

- A Focus started on Android appears on Desktop with the same intention and deadline;
- stopping or pausing a Focus on Desktop updates Android and cancels its alarm;
- local modification is saved after restart and synchronized when connected;
- repeated actions do not create multiple sessions or closures;
- an older session cannot be revived by a newer one;
- settings specific to a platform never leave the device;
- failure of synchronization does not degrade DayView functionality locally;
- authentication failures display no modal dialogue.
- Tokens are never stored in ordinary preferences nor written in logs.

```kotlin
data class SyncedDaySettings(
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineMillis: Long?,
    val pomodoroMinutes: Int,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)

enum class SyncedFocusStatus {
    IDLE,
    RUNNING,
    PAUSED,
    AWAITING_OUTCOME,
}

data class SyncedFocusSession(
    val sessionId: String,
    val status: SyncedFocusStatus,
    val intention: String,
    val durationMinutes: Int,
    val startedAtMillis: Long?,
    val endsAtMillis: Long?,
    val remainingMillisWhenPaused: Long?,
    val revision: Long,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)
```
