# Design: In-app Light / Dark / System theme toggle + correct top-bar color

## Problem

DayView already ships full light and dark palettes (`DayViewTheme`) and switches
between them by following the OS (`isSystemInDarkTheme()`). Two gaps remain:

1. **No in-app override.** The user cannot choose Light or Dark independently of the
   OS setting.
2. **Desktop title bar color.** The desktop window title bar is drawn by the OS. Today
   it happens to match because the app follows the OS. Once an in-app override exists,
   forcing a theme that disagrees with the OS (e.g. Dark app under Light macOS) leaves a
   mismatched OS-drawn title bar over the app content.

A third consequence follows from adding the override: the **Android status bar** color
is currently driven purely by `values/` + `values-night/` styles, which only know the
*OS* dark setting. An in-app override that differs from the OS would leave the status bar
stale. To keep the toggle correct, the Android status bar must follow the chosen theme.

## Goals

- Add a persisted `ThemeMode` (System / Light / Dark) with a Settings control.
- `System` preserves today's behavior (default).
- The desktop macOS window title bar matches the chosen theme (Approach A, native
  appearance sync). Linux is best-effort.
- The Android status/navigation bar follows the chosen theme.

## Non-goals

- Transparent / unified desktop title bar (Approach B) — deferred as later polish.
- Changing the existing color palettes or Material color scheme values.
- Theming the Android home-screen widget beyond its existing `values-night` handling.
- Any Linux-specific window-manager decoration control beyond what the JDK offers for
  free.

## Architecture

### 1. `ThemeMode` model (commonMain)

New enum:

```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK }
```

Resolution helper (pure, unit-testable):

```kotlin
fun ThemeMode.resolveIsDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
```

### 2. Persistence

- Add `themeMode: ThemeMode = ThemeMode.SYSTEM` to `DayPreferencesSnapshot`.
- Add a `THEME_MODE` string key to `DayPreferenceKeys` and persist/read it in
  `DayPreferencesStore` (store `enum.name`; unknown/absent value falls back to `SYSTEM`).
  Storing the name (not the ordinal) keeps the value stable if the enum is reordered.

### 3. Controller / UI state

- Add `themeMode: ThemeMode` to `DayViewUiState` and to the `toUiState`/`toSnapshot`
  mappings.
- Add `DayViewController.setThemeMode(mode: ThemeMode)` following the existing
  `setShowSeconds` pattern (update `state`, then `persistState()`).

### 4. `DayViewTheme` takes the mode

`DayViewTheme` gains a `themeMode: ThemeMode = ThemeMode.SYSTEM` parameter and resolves:

```kotlin
val isDark = themeMode.resolveIsDark(isSystemInDarkTheme())
```

The rest (palette selection, Material `colorScheme`, `CompositionLocalProvider`) is
unchanged. `DayViewTheme` also invokes the platform chrome hook (below) with `isDark`.

### 5. Feeding the mode into `DayViewTheme` (App.kt)

`DayViewTheme` is the outer wrapper in `DayViewApp`, created before the controller. Lift a
lightweight observation of the persisted mode above it:

```kotlin
val snapshot by preferences.snapshots.collectAsState(
    initial = remember(preferences) { runBlocking { preferences.snapshots.first() } },
)
DayViewTheme(themeMode = snapshot.themeMode) { colors -> Surface { /* controller, etc. */ } }
```

The Settings toggle calls `controller.setThemeMode(...)`, which persists; the persisted
change flows back through `preferences.snapshots` to both this top-level `snapshot`
(re-theming) and the controller's `onPreferencesChanged` (updating `state.themeMode` shown
in Settings). This matches the existing persist-and-re-read pattern; no new source of
truth is introduced.

### 6. Platform chrome hook — Android status bar

New `expect` composable in commonMain:

```kotlin
@Composable expect fun PlatformThemeChrome(isDark: Boolean)
```

Invoked inside `DayViewTheme` with the resolved `isDark`.

- **androidMain actual:** resolves the current `Activity` window (via `LocalView` /
  `LocalContext`) and, in a `SideEffect`, sets the status- and navigation-bar
  appearance to match `isDark` using `WindowCompat.getInsetsController(...)`
  (`isAppearanceLightStatusBars = !isDark`, same for nav bars) and sets the bar colors to
  the theme's `ink` (`#0B0D12` dark / `#F4F2EC` light) so the override wins over the XML
  defaults. Guarded so it is a no-op outside an `Activity` context (tests / previews).
- **desktopMain actual:** no-op (desktop title bar handled in `Main.kt`, see §7).

### 7. Desktop title bar — Approach A (native appearance sync)

- In `Main.kt`, at startup (before the AWT toolkit initializes, alongside the existing
  `apple.awt.application.name`), set `apple.awt.application.appearance` to `"system"` so
  per-window appearance is honored.
- In `Main.kt`, compute the resolved dark flag from the collected `preferenceSnapshot`
  plus the desktop system dark signal, and inside each `Window {}` block (where `window`
  is in scope) apply the matching macOS `NSAppearance` via `LaunchedEffect(isDark)`.
  The exact AWT/JBR call to set the window appearance (root-pane client property vs.
  reflective `NSWindow` route) will be **verified empirically during implementation** and
  the one that demonstrably re-tints the title bar at runtime will be used; if none works
  on the target runtime, fall back to setting `apple.awt.application.appearance` to the
  explicit `NSAppearanceNameDarkAqua`/`NSAppearanceNameAqua` value. This applies to both
  the main window and the mini window.
- `DayViewApp` is called from `Main.kt`; pass no extra theme argument — `DayViewApp`
  already reads `themeMode` from preferences (§5). `Main.kt` only needs the resolved
  `isDark` for the window appearance, which it computes from the same snapshot.
- Linux: best-effort. The same startup property is harmless; if the WM ignores it the
  title bar simply follows the WM theme as today.

### 8. Settings UI

Add an "APPEARANCE" section to `DayViewSettingsScreen`, above or near the existing
DISPLAY section, following the established card styling (`colors.panel`, rounded 18.dp,
`colors.overlay` hairline border). A 3-way segmented selector (System / Light / Dark)
built from the existing primitives (`Row` of `toggleable`/`clickable` boxes, selected
segment tinted with `colors.mint`), wired to `actions.changeThemeMode`. Add a test tag
(`DayViewTestTags.SettingsThemeMode`) consistent with the existing tags.

Wire-up: add `changeThemeMode: (ThemeMode) -> Unit` to `SettingsScreenActions`, populate
it in `App.kt` with `controller::setThemeMode`, and read the current value from
`state.themeMode`.

### 9. Strings (i18n)

Add to both `commonMain/composeResources/values/strings.xml` (English) and `values-fr`:

- `settings_section_appearance` — section header (e.g. "APPEARANCE").
- `settings_appearance_description` — one-line description.
- `settings_theme_system`, `settings_theme_light`, `settings_theme_dark` — segment labels.

## Data flow

```
Settings segment tap
  -> actions.changeThemeMode(mode)
  -> controller.setThemeMode(mode)         // updates state.themeMode, persists snapshot
  -> preferences.snapshots emits
       -> DayViewApp top-level snapshot updates -> DayViewTheme(themeMode) re-resolves isDark
            -> palette + Material scheme swap
            -> PlatformThemeChrome(isDark)  // Android status/nav bar
       -> controller.onPreferencesChanged   // state.themeMode reflected in Settings selection
  -> (desktop) Main.kt LaunchedEffect(isDark) -> window NSAppearance updated
```

## Testing

- **Unit (commonTest):** `ThemeMode.resolveIsDark` truth table (System follows arg;
  Light=false; Dark=true).
- **Persistence:** round-trip `themeMode` through `DayPreferencesStore` including the
  unknown/absent -> `SYSTEM` fallback (follow existing store test style if present).
- **Compose UI (desktopTest):** per the repo's UI-test gotchas — do **not** assert
  `stringResource` text. Use `DayViewTestTags.SettingsThemeMode` + seeded state to assert
  the selector renders and that tapping a segment invokes `changeThemeMode` with the
  expected `ThemeMode`. Test the pure `SettingsScreen`, not `DayViewApp`.
- **Manual verification (required):** launch desktop, toggle Light/Dark/System with the
  OS in the opposite mode, confirm the title bar re-tints and content matches. Launch
  Android, confirm the status bar follows the override.

## Risks / open items

- **macOS NSAppearance mechanism** is the one empirically-verified piece (§7). Everything
  else is standard Compose/DataStore wiring.
- **Linux title bar** is explicitly best-effort; not a blocker.
- No migration risk: absent `themeMode` reads as `SYSTEM`, i.e. current behavior.
