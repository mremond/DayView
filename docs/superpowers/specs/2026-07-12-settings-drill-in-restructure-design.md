# Settings drill-in restructure — design

## Problem

`DayViewSettingsScreen.kt` has grown to 825 lines in a single file. It renders every
settings section in one long vertical scroll and hand-rolls the same UI boilerplate
(panel cards, toggle rows, steppers, accent buttons) repeatedly. The screen is hard to
scan for users and hard to work with in code.

This is both a code-organization problem and a UX problem, and this design addresses
both.

## Goals

- Replace the single long scroll with a **drill-in list**: a landing screen lists
  categories; tapping one opens a focused sub-screen with its own back affordance.
- Split the monolithic file into a `settings/` package with one screen per category and
  a shared set of reusable composables.
- Remove duplicated boilerplate (toggle rows, panel cards, steppers, accent buttons).
- Preserve all existing behavior exactly — no new settings, no behavior changes.

## Non-goals (YAGNI)

- No new settings or controls.
- No search within settings.
- No animated transitions between the list and sub-screens (a plain swap; can be added
  later).
- No persistence of the last-open category — entering settings always starts at the list.

## Navigation model

`destination` in `DayViewUiState` stays `TODAY` / `SETTINGS`. A second axis is added for
depth inside settings:

- New enum `SettingsCategory { DAY, DISPLAY, SOUNDS, NET_TIME, ON_GOAL }`.
- New nullable field `settingsCategory: SettingsCategory? = null` on `DayViewUiState`.
  `null` = the category landing list; a value = that category's sub-screen.

Controller changes (`DayViewController`):

- `openSettings()` enters with `settingsCategory = null`.
- `openSettingsCategory(category)` sets the field.
- `closeSettingsCategory()` returns to `null` (the list).

Back behavior (`PlatformBackHandler` in `App.kt`, currently at `App.kt:111`): the handler
stays enabled for the whole `SETTINGS` destination and decides depth internally:

- If `settingsCategory != null` → close the category (return to the list).
- If `settingsCategory == null` → return to `TODAY`.

The on-screen back control mirrors this one-level-up behavior.

## Categories and the landing list

The landing list shows one row per **supported** category. Platform gating decides which
rows appear:

| Category | Contents | Shown when |
|----------|----------|------------|
| Day | start / end times | always |
| Display | show seconds (always); monochrome icon & launch-at-login (desktop only) | always |
| Sounds | cues, interval, volume | always |
| Net time | calendar selection | `netTimeSupported` |
| On-goal apps | app picker | `onGoalSupported` |

Each row shows the category name plus a **live value summary** as its subtitle, derived
from current state. Examples:

- Day → `08:00 – 18:30`
- Display → `Seconds off` (or `Seconds on`)
- Sounds → `On · every 30 min` / `Off`
- Net time → number of included calendars, or permission state
- On-goal apps → count of selected apps, or "None"

Value summaries are cheap because the state is already available to the landing screen.

The autosave note moves to the landing list footer (shown once, not per sub-screen).

## File structure

Split the monolith into several focused files in the existing
`composeApp/src/commonMain/kotlin/fr/dayview/app/` directory. The repo is flat — one
`fr.dayview.app` package, no subdirectories — so the new files stay in that package and
directory (no subpackage), which keeps `internal` visibility and test imports unchanged.

Reusable building blocks → `SettingsComponents.kt`:

- `SettingsPanelCard` — the `panel background + 1dp border + RoundedCornerShape(18) +
  padding` container (repeated ~10× today).
- `SettingsToggleRow` — title + description + `Switch` inside a card (repeated 5× today:
  show-seconds, monochrome icon, launch-at-login, net-time enable, sound enable).
- `SettingsSectionHeader` — mint label + description block, reused as each sub-screen's
  header.
- `SettingsStepper` — the `−  value  +` control (interval minutes, volume; 2× today).
- `SettingsAccentButton` — the mint pill button (grant access, add apps, preview; 3×
  today).
- Keep `SettingsDivider`, `TimePreferenceRow`, `PreviewSoundButton` here as well.

Screens (one file per category, each self-contained and wrapped in the shared scaffold):

- `SettingsScreen.kt` — scaffold (top bar: back + title) + landing list + a router that
  renders the selected category's screen.
- `DaySettingsScreen.kt`
- `DisplaySettingsScreen.kt`
- `SoundSettingsScreen.kt`
- `NetTimeSettingsScreen.kt`
- `OnGoalAppsScreen.kt`

Models → `SettingsUiModels.kt`: `SettingsPlatformUiState`, `SettingsScreenActions`, and
the new `SettingsCategory` enum.

Screen files use the `...Screen` suffix to avoid clashing with the existing
`SoundSettings` / `NetTimeSettings` data classes.

The existing `SoundSettingsPanel` / `NetTimeSettingsPanel` / `OnGoalAppsPanel` bodies move
mostly as-is into their screen files, wrapped in the shared scaffold instead of inline
section headers. `App.kt`'s call site changes only to pass the new category state and the
two new navigation actions.

## Behavior preserved

All controls, autosave, permission prompts, and platform gating behave exactly as today.
This is a restructure, not a feature change.

## Edge cases

- **Support flag flips off while its sub-screen is open** (e.g. calendar permission
  revoked): the router falls back to the landing list rather than rendering an empty
  screen.
- **Deep-link safety:** entering `SETTINGS` always resets `settingsCategory = null`, so
  the user never lands mid-drill.
- The back handler stays enabled for the whole `SETTINGS` destination and decides depth
  internally.

## Testing

The repo now has a Compose UI test harness (added in #39): `runComposeUiTest` in
`composeApp/src/desktopTest`, stable tags in `DayViewTestTags` (commonMain), and helpers
(`seededController`, `noopSettingsActions`) in `UiTestSupport.kt`. Assertions query by tag
or seeded data — not async string resources, which time out under `runComposeUiTest`.

- **Controller nav (commonTest, `DayViewControllerTest`):** `openSettings()` resets
  `settingsCategory` to `null`; `openSettingsCategory` sets it; `closeSettingsCategory`
  clears it.
- **Pure category filter (commonTest):** `settingsCategoriesFor(platformState)` returns
  exactly the supported categories (Android set vs desktop set).
- **Drill-in nav (desktopTest, controller-backed render):** the landing list shows a row
  per supported category; tapping a row shows that sub-screen's tag; scaffold back returns
  to the list.
- **Migrate existing `SettingsScreenTest`:** the current tests assert day-range /
  show-seconds / sound panel directly on `SettingsScreen`. Under drill-in these live in
  sub-screens, so those tests must navigate into the category first (or assert against the
  sub-screen composable directly). The back-link test stays as-is.
- New tags needed in `DayViewTestTags`: one per category row and one per sub-screen root.
- `ktlintCheck`, `testDebugUnitTest`, and `desktopTest` stay green.
