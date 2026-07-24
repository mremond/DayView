# macOS Native — History storage: a real archive for the native app (Path B, Phase 13a)

## Context

`DayViewController` archives the previous day's ring at rollover — from `init` on a cold launch,
and from `tick` when the day key changes under a running app. `DayViewNative.create()` passes no
`history` argument, so the controller falls back to its default `InMemoryDayHistoryStore`: a
`mutableMapOf` that dies with the process.

**The native app therefore archives nothing.** Every day it runs, `maybeArchivePreviousDay()`
faithfully writes the previous day's record into a map that is discarded at quit. The parity
checklist has carried this as a ⚠️ "verify first" item since the phase-10 era; it is now
confirmed.

The blast radius is currently small — the native app ships under a distinct debug bundle id and
keeps its preferences in a different file from the Compose/JVM build, which remains the
maintainer's daily driver — so what is being lost is test data. It is nonetheless a cutover
blocker: the day the native app becomes the shipping one, every completed day would evaporate.

Phase 13 is split (approved): **13a (this spec)** is the storage — the module migration and the
wiring that makes archiving real. **13b (later)** ports the week and day screens.

## Goals

- The native macOS app archives each completed day to disk, and reads its archive back.
- The file-backed store becomes available to `:core` without dragging sync into it.
- No behaviour change for the shipping Compose/JVM and Android apps.

## Non-Goals

- The week and day screens (13b).
- `FocusContributionStore` natively — it belongs to the sync phase.
- Any change to `DayHistoryRecord`, `DayHistoryCodec`, or the archive's on-disk format.
- Any change to when archiving happens; the controller's rollover logic is untouched.

## Decisions (from brainstorming)

1. **Split 13a storage / 13b screens**, matching the project's 9a/9b, 10a/10b, 12a/12b rhythm.
   The storage half is a data-loss fix verifiable entirely in `:core:jvmTest`, with no Swift.
2. **The history directory is injected, not discovered.** `:core` gains no `expect/actual` and no
   new source set (see below).

## Why injection rather than expect/actual

`:core` today has exactly two source sets of production code — `commonMain` and `macosMain` — and
**zero `expect`/`actual` declarations**. Every platform capability it needs arrives through an
injected interface: `CalendarSource`, `FrontmostAppProvider`, `DockAttentionProvider`,
`PresencePersistence`. Moving `:shared`'s `expect fun createHistoryFileSystem()` into `:core`
would break that discipline and require creating `jvmMain` and `androidMain` source sets for a
single function.

The directory is the only genuinely platform-specific part, and each app already knows its own:
`macosDayPreferences()` computes its path in `macosMain` today. So the path becomes a constructor
argument and the filesystem implementation becomes common.

## Architecture

### What moves to `:core` commonMain

Two declarations move from `shared/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt` into
`core/src/commonMain/kotlin/fr/dayview/app/DayHistoryStore.kt`, alongside the interface and the
in-memory store that already live there, and become **public** — the required consequence of
crossing the module boundary, as with the drift/resume detectors in phase 10a:

```kotlin
/** Platform file access for the history directory. `name` is the bare `dayKey` string. */
interface HistoryFileSystem {
    fun read(name: String): String?
    fun writeAtomic(name: String, text: String)
    fun list(): List<String>
}

class FileDayHistoryStore(private val fs: HistoryFileSystem) : DayHistoryStore
```

`FileDayHistoryStore`'s body is unchanged: write is idempotent (an existing file is never
clobbered), and the two list methods filter to numeric names.

### What stays in `:shared`

`expect fun createHistoryFileSystem()` with its JVM and Android actuals, both `JvmHistoryFileSystem`
copies, `createDayHistoryStore()`, `createFocusContributionStore()`, and `FileFocusContributionStore`.

The contribution store must stay: it depends on `fr.dayview.app.sync.FocusContributionMapper`, so
moving it would pull the sync subsystem into `:core` as a side effect of a history change. It
keeps working against the now-public `HistoryFileSystem`.

The shipping apps' storage path, file names, encoding and I/O calls are all untouched.

### New in `:core` commonMain

```kotlin
class OkioHistoryFileSystem(
    private val fileSystem: FileSystem,
    private val dir: Path,
) : HistoryFileSystem
```

Okio runs on JVM, Android and Kotlin/Native, and `:core` already uses `FileSystem.SYSTEM` in
`macosDayPreferences()`. `writeAtomic` writes `<name>.tmp` then `atomicMove`s it onto `<name>`,
creating the directory first; `list` filters out `.tmp` leftovers, matching the JVM implementation
it mirrors. Okio becomes an explicit `:core` dependency (it is already present transitively via
DataStore); `gradle/libs.versions.toml` has the `okio` version but no plain `okio` library alias,
so one is added.

**Testability is the reason this lives in commonMain rather than `macosMain`:** `:core:jvmTest`
already depends on `okio-fakefilesystem`, so the real implementation can be tested against an
in-memory filesystem — atomic writes and directory listing included. The existing
`FakeHistoryFileSystem` in `:shared` is a `mutableMapOf` and exercises none of that.

### A test fixture the move disturbs

`FakeHistoryFileSystem` — a `mutableMapOf`-backed stub — is declared inside
`shared/src/commonTest/.../FileDayHistoryStoreTest.kt` and is also used by
`FileFocusContributionStoreTest`. Moving the store's test to `:core` alongside the store would
take the fixture with it and break the contribution test, which is staying.

So the fixture is extracted into its own file in `:shared`'s commonTest, keeping the contribution
test compiling unchanged. `:core`'s new tests do not need it: they exercise the real
`OkioHistoryFileSystem` against Okio's `FakeFileSystem`, which is the point of putting the
implementation in commonMain.

### Native wiring

`DayViewNative.create()` builds the store beside the preferences file and passes it in:

```kotlin
val historyDir = "${NSHomeDirectory()}/Library/Application Support/DayView/history".toPath()
val history = FileDayHistoryStore(OkioHistoryFileSystem(FileSystem.SYSTEM, historyDir))
val controller = DayViewController(..., history = history, ...)
```

Nothing else is needed. `maybeArchivePreviousDay()` already runs from `init` (a cold launch on a
later day archives the stale one) and from `tick` on a day-key change (an app left open across
midnight). Both paths now reach disk.

## Data flow

```
launch: DayViewNative.create()
  -> FileDayHistoryStore(OkioHistoryFileSystem(FileSystem.SYSTEM, ~/Library/.../DayView/history))
  -> DayViewController(history = ...)
       init -> maybeArchivePreviousDay()  [stale persisted day -> record written]
       tick -> day key changed -> maybeArchivePreviousDay()
  -> <dayKey> file per archived day, written atomically, never overwritten
```

## Testing / done criteria

- **`:core:jvmTest`**, against Okio's `FakeFileSystem`:
  - a record written then read back is identical (round-trip through `DayHistoryCodec`);
  - writing the same day twice keeps the first record — the idempotence the archive relies on,
    since `maybeArchivePreviousDay` may run more than once for the same stale day;
  - `listDays`/`listAllDays` return the numeric names sorted and ignore a `.tmp` leftover;
  - `writeAtomic` leaves no `.tmp` file behind and creates the directory if absent;
  - a controller given a `FileDayHistoryStore` and ticked across a day boundary writes the
    previous day's record — the integration the phase exists for, which no `:core` test covers
    today.
- **`:shared` tests keep passing unchanged**, which is the evidence that the shipping apps'
  behaviour did not move.
- **Native:** `./gradlew :core:runMacNative` → `** BUILD SUCCEEDED **`. Manual smoke test:
  archiving is invisible in the native UI until 13b lands, so verify it on disk — run the app,
  quit, then move the day forward (the simplest trigger without waiting for midnight is to launch
  the app on the following day) and confirm a file named with the previous day's epoch-day number
  appears in `~/Library/Application Support/DayView/history/` and contains that day's record.

## Risks

- **`atomicMove` throws where `File.renameTo` returned false.** The JVM implementation carries a
  delete-then-retry fallback for the case where the target exists. `FileDayHistoryStore.write`
  returns early when the target exists, so the overwrite path is unreachable in practice — but
  the new implementation is the one the native app depends on, and this difference is worth a
  reviewer's attention rather than a silent assumption.
- **Two implementations of the same idea now coexist** (`JvmHistoryFileSystem` in `:shared`, the
  Okio one in `:core`). That is deliberate: converging them would rewrite the shipping apps' I/O
  to fix a bug in an app that does not ship yet. Recorded on the parity checklist as a
  post-cutover simplification, not carried out here.
- **The archive directory differs from the Compose/JVM app's** (`~/Library/Application Support/DayView/history`
  versus `~/.dayview/history`), exactly as the preferences files already differ. The two macOS
  apps keep separate histories until the cutover; migrating the old archive is a packaging-phase
  question, not this phase's.

## Roadmap after this phase (context only)

13b (the week and day screens), then per the parity checklist: must-dos, hero quotes, keyboard
shortcuts, sounds, the day-over/upcoming screen, sync, French localization, and the
packaging/signing/CI cutover.
