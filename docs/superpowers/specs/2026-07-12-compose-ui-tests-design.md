# Compose UI tests for DayView — Design

## Goal

Add a first suite of Compose Multiplatform UI tests exercising the shared
composables. Domain logic is already well covered by unit tests
(`DayViewControllerTest`, `DayProgressTest`, `PomodoroTest`, `GlobalGoalTest`,
`SoundAlertsTest`); there are currently **no** tests driving the composables.

First suite covers the high-value flows: **Settings** rendering + control
callbacks, **Today** screen rendering (countdown ring, goal, focus entry), and
the **Focus** start/stop interaction.

## Placement & dependency

- Tests live in **`composeApp/src/desktopTest/`**. Compose UI tests run reliably
  on the desktop/JVM (Skiko) target, and keeping them out of `commonTest` leaves
  the Robolectric `testDebugUnitTest` run completely untouched. `desktopTest`
  already sees `commonTest` helpers (e.g. `InMemoryDayPreferences`) and all
  `internal` composables in the module.
- Add the Compose UI-test dependency to the **desktop test** source set:
  `implementation(compose.uiTest)`. If that accessor does not resolve for the
  JVM source set, fall back to `implementation(compose.desktop.uiTestJUnit4)`
  (with `compose.desktop.currentOs`, already visible via `desktopMain`). Confirm
  the exact artifact empirically against Compose `1.11.1` in `libs.versions.toml`.
- Tests use `@OptIn(ExperimentalTestApi::class)` + `runComposeUiTest { }` (the
  lambda form; no JUnit4 rule required).

## Test seam

Test the two **pure** screen composables, not `DayViewApp`:

- `DayViewApp` is unsuitable for `runComposeUiTest`: it reads the real
  `Clock.System.now()`, uses `runBlocking`, and runs an infinite ticker
  `LaunchedEffect` that never lets the composition go idle (`waitForIdle` would
  hang).
- `SettingsScreen(state, platformState, actions)` and
  `DayViewScreen(state, actions, reminders)` are pure: they take a
  `DayViewUiState` plus callback bundles. Ideal seams.

### Deterministic state

Build `DayViewUiState` via `DayViewController(prefs, scope, seededSnapshot,
initialNow)` — the exact production path (`toUiState`) — with a fixed
`initialNow`, mirroring `DayViewControllerTest`
(`CoroutineScope(Dispatchers.Unconfined)` + `InMemoryDayPreferences`). Inside a
composable the scope comes from `rememberCoroutineScope()`.

### Assertion patterns

- **Recording fakes** for Settings (capture callback invocations into vars):
  assert "toggling a control invokes the corresponding `SettingsScreenActions`
  callback".
- **Controller-wired** actions for Focus (wire `DayViewScreenActions` to a real
  controller, minus the platform alarm hook): assert the real state transition
  (`controller.state.focusIsActive`) after `performClick`. The controller
  reference is captured in the test, and `controller.state` (a Compose
  `mutableStateOf`) reflects the post-recomposition value after `waitForIdle()`.

### Environment

- Wrap content in `DayViewTheme { }` so Material3 components (Switch, text
  fields) and `LocalDayViewColors` resolve faithfully. (`LocalDayViewColors` has
  a default, but the theme wrapper matches production.)
- `DayViewScreen` branches compact/wide at `maxWidth >= 780.dp`
  (`BoxWithConstraints`). Wrap it in a **fixed-size Box** (e.g.
  `Modifier.size(1100.dp, 900.dp)`) to force the **wide** layout, so the Goal and
  Focus panels render inline. This avoids the flakier compact modal-bottom-sheet
  path and is independent of the test window size.
- **Timezone safety**: `calculateDayProgress` maps an absolute `Instant` through
  the machine's local timezone, so the exact numeric countdown is not portable
  across machines/CI. Tests assert **structural labels** (`IL RESTE`) and
  **seeded strings** (goal title, focus intention), never the exact remaining
  hours/minutes or timezone-sensitive states (e.g. "finished").

## Minimal test tags

Per the chosen strategy (accessibility labels + a small tag set). Add
`Modifier.testTag(...)` only to interaction/assertion targets that otherwise
carry only French copy; rendering assertions keep using stable text and the
existing `semanticLabel` / `onClickLabel`.

| Tag | Location |
| --- | --- |
| `dayViewCountdown` | `CountdownCircle` root Box (ring has no queryable label — Canvas-drawn) |
| `showSecondsToggle` | Settings "AFFICHER LES SECONDES" toggleable Row (disambiguates from the "REPÈRES SONORES" switch, same structure) |
| `settingsBack` | Settings "‹ AUJOURD'HUI" back link |
| `focusStart` | `FocusCreationContent` "DÉMARRER LE FOCUS" button |
| `focusStop` | Active `FocusPanel` "ARRÊTER" button |

Tags are appended to existing modifier chains at call sites; no composable
signatures change. `FocusActionButton` already accepts a `modifier`.

## Test files

### `SettingsScreenTest.kt` (recording fakes)

- Seeds `DayPreferencesSnapshot(startMinutes = 8*60, endMinutes = 18*60,
  showSeconds = true)`.
- `rendersSeededDayRangeAndShowSeconds`: asserts `08:00`, `18:00` shown; the
  `showSecondsToggle` node `assertIsOn()`.
- `togglingShowSecondsInvokesCallback`: `onNodeWithTag("showSecondsToggle")
  .performClick()` → recorded `changeShowSeconds(false)`.
- `backLinkInvokesCallback`: `onNodeWithTag("settingsBack").performClick()` →
  recorded `back()`.
- `rendersSoundPanel`: asserts the "SONS" panel header is present.
- Uses a `noopSettingsActions(...)` helper that returns a full
  `SettingsScreenActions` of no-ops with the relevant callback overridden.

### `TodayScreenTest.kt` (rendering)

- Seeds a fixed `initialNow` + `goalTitle = "Livrer la v2"`, IDLE focus.
- `rendersCountdownGoalAndFocusEntry`: asserts `dayViewCountdown` exists,
  `IL RESTE` present, goal title present, `focusStart` (wide inline) present.
- `rendersActiveFocusState`: seeds `pomodoroEnd = now + 25.minutes` +
  intention → asserts the intention text and `focusStop` are present.

### `FocusFlowTest.kt` (controller-wired, wide layout)

- `startFocusInvokesController`: seeds IDLE + `focusIntention = "Écrire le
  rapport"` (non-blank → start enabled). `onNodeWithTag("focusStart")
  .performClick()` → `controller.state.focusIsActive` is `true`.
- `stopFocusInvokesController`: seeds active pomodoro. `focusStop` click →
  `focusIsActive` is `false`.
- `startDisabledWhenIntentionBlank`: seeds IDLE + blank intention →
  `focusStart` `assertIsNotEnabled()`; "Écrivez une intention pour démarrer."
  present.

### Shared helper (`UiTestSupport.kt`, desktopTest)

- Fixed `Instant` constant(s).
- `seededController(snapshot, now)` convenience (scope via `rememberCoroutineScope`
  at call site, or `Dispatchers.Unconfined` where built outside composition).
- `noopSettingsActions(...)` / `noopDayViewActions(...)` factories returning full
  bundles of no-ops with named overrides, to keep each test concise.

## Deferred (note in PR)

- `DayViewApp` end-to-end (non-deterministic clock + infinite ticker).
- Net-time and On-goal-apps settings panels (desktop-gated behind calendar
  support / running-app platform state).
- Compact modal-bottom-sheet flows (`CompactSheet.FOCUS` / `.GOAL`).
- `DayViewMiniApp`.
- Exact numeric countdown and timezone-sensitive states ("finished", net time).

## Verification

`./gradlew ktlintCheck :composeApp:desktopTest :composeApp:testDebugUnitTest`
green, with the new UI tests executing on the desktop target.
