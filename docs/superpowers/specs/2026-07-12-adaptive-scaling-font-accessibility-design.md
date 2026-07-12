# Design: Adaptive space-filling + font-size accessibility

## Problem

On large screens (e.g. a Supernote e-ink tablet, or a maximized desktop window) the main
view does not use the available space, and all text feels small:

1. **The countdown ring stops growing.** `circleSize = minOf(maxWidth, maxHeight, 510.dp)`
   caps the ring at 510dp, so on a big screen it floats in whitespace. The counter
   numerals stop growing even sooner: `counterScale = (circleSize / 380.dp).coerceIn(.72f,
   1f)` caps the type scale at ×1.0.
2. **No in-app text-size control.** All text uses `.sp`, which follows the OS font-size
   setting on Android but has no in-app control. On desktop there is no OS font-size knob
   at all, so users cannot enlarge text.

These are two distinct needs, handled by two distinct mechanisms:

- **Space-filling is automatic** — the ring and its counter grow to occupy available
  space, with no user action and nothing persisted.
- **Text size is a manual accessibility control** — a slider the user adjusts, applied as
  a global `.sp` multiplier.

Keeping them separate avoids double-scaling: automatic scaling touches only the ring and
counter; every other piece of text is governed solely by the manual slider.

## Goals

- The countdown ring and its numerals grow with available space, up to a sane ceiling,
  automatically. No setting, nothing persisted.
- A persisted `fontScale` (continuous, 1.0–1.5) with a Settings slider that enlarges all
  text app-wide, working on desktop (no OS setting) and stacking on top of the OS setting
  on Android/Supernote.

## Non-goals

- A manual "display size / zoom everything" control — space-filling is automatic and
  limited to the ring/counter; text is the only manual knob.
- Scaling the surrounding panels beyond their existing readability width caps
  (`widthIn(max = 430.dp)` side panels, `widthIn(max = 1040.dp)` wide container).
- Any change to macOS's native SwiftUI path — this is scoped to the shared Compose UI
  (Android + Linux/desktop).
- Auto-detecting Supernote specifically. Everything keys off available space, so a
  maximized desktop window benefits too.

## Architecture

### 1. Automatic space-filling (DayViewTodayScreen, `CountdownCircle`)

No new state. Two constants change, both already driven by the existing
`BoxWithConstraints`:

- **Ring ceiling:** raise the cap in
  `circleSize = minOf(maxWidth, maxHeight, 510.dp)` to `720.dp`. The ring keeps its
  square, centered layout and simply grows further before clamping.
- **Counter scale ceiling:** change
  `counterScale = (circleSize / 380.dp).coerceIn(.72f, 1f)` to
  `.coerceIn(.72f, 1.4f)` so the numerals grow *with* a larger ring instead of freezing at
  ×1.0. The lower bound is unchanged (compact/mini windows keep today's behavior).

To make this unit-testable, extract the ceiling math into a small pure helper in
commonMain, e.g.:

```kotlin
// Ring diameter for the available square, clamped to a sane maximum.
internal fun countdownCircleSize(available: Dp, max: Dp = 720.dp): Dp =
    minOf(available, max)

// Counter type scale that tracks the ring but never dwarfs a small dial.
internal fun countdownCounterScale(circleSize: Dp): Float =
    (circleSize / 380.dp).coerceIn(.72f, 1.4f)
```

`CountdownCircle` calls these instead of inlining the arithmetic. (`available` is the
existing `minOf(maxWidth, maxHeight)`.)

### 2. `fontScale` preference (persistence)

Mirror the existing `themeMode` wiring exactly:

- Add `fontScale: Float = 1.0f` to `DayPreferencesSnapshot`.
- Add a `FONT_SCALE = "font_scale"` key to `DayPreferenceKeys` and a
  `floatPreferencesKey` for it in `DayPreferencesStore`.
- Persist `snapshot.fontScale`; on read, `this[fontScaleKey] ?: defaults.fontScale`,
  then coerce into `1.0f..1.5f` so a corrupt/out-of-range stored value can never break
  layout.

### 3. Controller / UI state

- Add `fontScale: Float` to `DayViewUiState` and to the `toUiState` / `toSnapshot`
  mappings.
- Add `DayViewController.setFontScale(scale: Float)` following the existing
  `setShowSeconds` / `setThemeMode` pattern: coerce into `1.0f..1.5f`, update `state`,
  then `persistState()`.

### 4. Applying the scale globally (App.kt)

The scale is applied once, at the top, by overriding the composition density's
`fontScale`. Overriding `fontScale` (not `density`) scales only `.sp`, leaving every
`.dp` measurement untouched — so layout boxes keep their size and only text grows. This
is the idiomatic Compose approach and avoids threading a multiplier through every `Text`.

The persisted value is already observed at the top of `DayViewApp` (the
`themeSnapshot` used for `DayViewTheme`). Reuse it:

```kotlin
DayViewTheme(themeMode = themeSnapshot.themeMode) { colors ->
    val base = LocalDensity.current
    val scaled = Density(base.density, base.fontScale * themeSnapshot.fontScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        Surface(...) { /* controller, screens */ }
    }
}
```

On Android, `base.fontScale` already reflects the OS font setting, so the two multiply
(the app control stacks on top of the system one). On desktop `base.fontScale` is 1.0, so
the slider is the sole text-scale control.

### 5. Counter overflow — known risk + mitigation

The countdown numerals are `.sp` too, so they also grow under the global override and
could overflow the ring at high `fontScale`. Mitigations, in order:

1. The ring auto-grows (§1), giving the numerals more room.
2. The range is capped conservatively at 1.5.

If the numerals still clip at max scale (verified during implementation, not assumed),
wrap just the counter `Column` inside `CountdownCircle` in a nested
`CompositionLocalProvider(LocalDensity provides <base density with fontScale divided back
out>)`. The counter is sized to the ring via `counterScale`, not a readability target, so
exempting it from the accessibility scale is correct. This exemption is added only if
implementation shows it is needed.

### 6. Settings UI (DisplaySettingsScreen)

Add a font-size row to `DisplaySettingsScreen`, near the Theme selector, using a
Material3 `Slider`:

- A `SettingsPanelCard` (matching the Theme card) containing a title, a one-line
  description, a live label showing the current percentage (e.g. "115%"), and the
  `Slider`.
- `Slider(value = state.fontScale, onValueChange = actions.changeFontScale,
  valueRange = 1.0f..1.5f, steps = ...)`. Use a modest number of discrete `steps`
  (e.g. 9 → 5% increments) so the value is predictable and the live label is clean, while
  still reading as a continuous slider.
- Give the slider a `DayViewTestTags.SettingsFontScale` tag for UI tests.

Wire-up: add `changeFontScale: (Float) -> Unit` to `SettingsScreenActions`, populate it
in `App.kt` with `controller::setFontScale`, and read the current value from
`state.fontScale`.

### 7. Strings (i18n)

Add to both `commonMain/composeResources/values/strings.xml` (English) and `values-fr`:

- `settings_font_size` — row title (e.g. "Text size").
- `settings_font_size_description` — one-line description.
- `settings_font_size_value` — a `%`-formatted percentage label (single `%`, per this
  repo's Compose-resources formatting rules).

## Data flow

```
Font slider drag
  -> actions.changeFontScale(scale)
  -> controller.setFontScale(scale)        // coerce, update state.fontScale, persist
  -> preferences.snapshots emits
       -> DayViewApp themeSnapshot updates
            -> LocalDensity override recomputed -> all .sp text re-measures
       -> controller.onPreferencesChanged   // state.fontScale reflected in the slider

Window / screen resize  (no persistence)
  -> BoxWithConstraints remeasures
       -> countdownCircleSize / countdownCounterScale -> ring + numerals grow to fit
```

## Testing

Follow this repo's UI-test gotchas: never assert `stringResource` text in
`runComposeUiTest`; use test tags and seeded state, and test pure screens, not
`DayViewApp`.

- **Unit (commonTest):** `countdownCircleSize` clamps to the 720dp ceiling and passes
  smaller values through; `countdownCounterScale` hits `.72f` and `1.4f` bounds and
  scales linearly between. `DayViewController.setFontScale` coerces out-of-range input
  into `1.0f..1.5f`.
- **Persistence:** round-trip `fontScale` through `DayPreferencesStore`, including the
  absent → `1.0f` default and an out-of-range stored value coerced on read.
- **Compose UI (desktopTest):** using `DayViewTestTags.SettingsFontScale` + seeded state,
  assert the slider renders and that moving it invokes `changeFontScale`. Test the pure
  `DisplaySettingsScreen`, not `DayViewApp`.
- **Manual verification (required):** launch desktop, drag the slider, confirm all text
  enlarges and nothing clips (especially the countdown numerals at max). Maximize the
  window and confirm the ring grows toward the ceiling. If available, confirm on a large
  Android/Supernote screen that the ring fills more space and the slider stacks on the OS
  font setting.

## Risks / open items

- **Counter clipping at max scale** is the one empirically-verified piece (§5); the
  exemption is added only if needed.
- **No migration risk:** absent `fontScale` reads as `1.0f` and the ring ceilings only
  raise existing caps, so current behavior is preserved on existing screens/devices.
