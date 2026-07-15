# Sync auto-retry with backoff — design

Date: 2026-07-15
Status: approved

## Problem

When a sync fails (typically because the network is down), nothing retries until the
next external trigger. Triggers today are: app startup, Android `ON_RESUME` / a 5-minute
periodic tick on desktop, a debounced local write, and the manual "Sync Now" button
(`App.kt`). `SyncEngine`'s internal 3-attempt loop only covers push conflicts; a network
exception aborts the run immediately with `SyncResult.Failed`.

Worst cases: on Android, a foregrounded app with no further local edits never retries a
failed sync; on desktop the user waits up to 5 minutes. The user expectation is that sync
recovers on its own once the network is back.

## Decision

Add a shared retry-with-backoff loop inside `SyncCoordinator` (common code). No platform
connectivity listeners, no UI changes, no new `SyncStatus` values.

Approaches considered and rejected:

- **Platform network listeners** (Android `ConnectivityManager.NetworkCallback`): instant
  recovery on Android, but desktop JVM has no equivalent and would need polling anyway;
  more platform code for marginal gain.
- **Hybrid** (backoff + Android callback): best responsiveness, most code; not needed for
  the current pain.

## Behavior

- After a sync run finishes with a *retryable* failure, `SyncCoordinator` schedules a
  retry on its injected `CoroutineScope`: delay by the current backoff step, then run
  `syncNow()` again.
- Backoff steps: **15s → 30s → 1m → 2m → 5m**, then stays at 5m indefinitely. The cap
  matches the existing desktop periodic cadence; on Android it adds recovery where none
  exists today.
- Consecutive failed runs advance the backoff step. Any run that ends in a non-retryable
  outcome (`Ok`, `KeyError`, `NotConfigured`, `NeedsChoice`, auth failure) resets the
  step and cancels any pending retry.
- Every sync run — regardless of trigger (manual, debounced write, resume, startup, or
  the retry itself) — cancels the pending retry before running; completion logic
  reschedules if the run still fails. At most one retry is ever pending.

## Failure classification

`SyncResult.Failed(cause)` carries the causing `Throwable`; `runOnce` currently discards
it. The coordinator inspects it:

| Outcome | Retryable |
| --- | --- |
| `Failed` with `SyncAuthenticationException` cause | No — a bad token cannot self-heal |
| `Failed` with any other cause (IO errors, 5xx `IllegalStateException`, exhausted push retries) | Yes |
| `KeyError`, `NotConfigured`, `NeedsChoice`, `Ok`, `UpToDate` | No (nothing to retry) |

## Status surface

`SyncStatus` is unchanged. Status remains `Failed` while a retry is pending; a retry
that succeeds flips it to `Ok` on its own.

## Lifecycle note

Retries run on the coordinator's injected scope. On Android that scope is owned by
`MainActivity` and cancelled in `onDestroy`, so retries fire while the app is merely
backgrounded (`onStop` without `onDestroy`) but stop once the Activity is destroyed —
this avoids an orphaned retry loop surviving a configuration change. This deliberately
avoids WorkManager.

## Testing

Unit tests for `SyncCoordinator` using `runTest` virtual time and a fake transport:

- Transport fails N times then succeeds → status ends `Ok`; delays follow the backoff
  sequence.
- Auth failure → no retry scheduled.
- Manual `syncNow()` while a retry is pending → the pending retry is cancelled, no
  double-run.
- A successful run resets the backoff step for the next failure streak.
