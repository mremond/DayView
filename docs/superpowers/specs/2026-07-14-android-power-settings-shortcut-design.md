# Android power-settings shortcut — design

## Problem

The home-screen widget's background refresh (an `AlarmManager` idle-safe alarm, added in
the day-boundary refresh change) is silently suppressed by aggressive OEM power management
on some Android devices — confirmed on the maintainer's BOOX Palma 2 (Onyx e-ink), where
`set...AndAllowWhileIdle()` returns without error but the alarm never enters the store and
never fires. The AOSP battery-optimization exemption alone did not unblock it in testing;
the real lever is Onyx's proprietary "App Freeze / auto-start" screen. There is no way for
the app to flip that toggle programmatically, so the user must do it — but today there is
no in-app path to that screen.

## Goal

Add an in-app, Android-only shortcut that takes the user to the most relevant OS
power/background-management screen for their device, so they can exempt DayView and let
the widget refresh alarm fire. The shortcut only surfaces the screen; the user makes the
change. Desktop is unaffected.

Non-goals: no new runtime permission; no attempt to change power settings
programmatically; no promise that the exemption fixes firing on every OEM (documented
caveat — the shortcut provides the path, not a guarantee).

## Approach

A new Android-only "System" settings category containing an explanation and a single
button that deep-links, via an ordered fallback chain, to the best available power screen.
Chosen over a battery-optimization request dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
because that permission's AOSP exemption proved insufficient on BOOX and adds a
Play-flagged sensitive permission for little benefit.

## Components

### Platform seam (commonMain)

Follows the existing `onRequestCalendarPermission` pattern exactly.

- `DayViewApp(...)` gains `onOpenPowerSettings: (() -> Unit)? = null`.
- `SettingsPlatformUiState` gains `powerManagementSupported: Boolean = false`, set to
  `onOpenPowerSettings != null` at the `SettingsPlatformUiState(...)` construction site in
  `App.kt`.
- `SettingsScreenActions` gains `openPowerSettings: () -> Unit = {}`, wired to
  `onOpenPowerSettings` (or a no-op when null).
- `settingsCategoriesFor(platformState)` appends `SettingsCategory.SYSTEM` when
  `powerManagementSupported` is true.

Desktop passes `onOpenPowerSettings = null` (the default) → the category never appears and
no Android API leaks into shared code.

### New category (commonMain)

- `SettingsCategory.SYSTEM` enum value.
- Category title / description / summary strings (fr + en) via Compose resources.
- `SystemSettingsScreen` composable, rendered from the `SettingsCategoryDetail` `when`
  branch for `SYSTEM`. Contains:
  - A short explanatory paragraph (background refresh can be limited by device power
    management; allow background / auto-start for an up-to-date widget).
  - A button "Ouvrir les réglages d'énergie" / "Open power settings" with a dedicated
    `testTag`, calling `actions.openPowerSettings`.
  - Reuses `SettingsComponents.kt` building blocks.

### Android implementation (androidMain)

- Pure builder `powerSettingsCandidates(packageName: String): List<PowerSettingsTarget>`
  returning, in order:
  1. Onyx App Freeze — action `onyx.settings.action.APP_FREEZE_MANAGEMENT` (BOOX lever).
  2. AOSP battery-optimization list — `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
  3. App details — `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with `package:` data
     (universal fallback; always resolves).

  `PowerSettingsTarget` is a small value type holding the action string and an optional
  `data` URI string.

- Launcher `openPowerManagementSettings(context: Context)`: iterates the candidates, builds
  each `Intent` (adding `FLAG_ACTIVITY_NEW_TASK`), and launches the first whose
  `packageManager.resolveActivity(intent, 0) != null`. `startActivity` is wrapped in
  try/catch (`ActivityNotFoundException`) as a belt-and-braces guard so a candidate that
  resolves but fails to launch falls through to the next.

- Wiring: at the `DayViewApp(...)` call in `MainActivity`, pass
  `onOpenPowerSettings = { openPowerManagementSettings(this) }`.

No manifest permission is added.

## Data flow

User opens Settings → the Android-only "System" category is listed → taps it →
`SystemSettingsScreen` → taps the button → `actions.openPowerSettings` →
`onOpenPowerSettings` → `openPowerManagementSettings(context)` → first resolvable power
screen opens. On BOOX this is the Onyx App Freeze screen; on stock Android, the battery
optimization list; worst case, the app details page.

## Error handling

The app-details fallback always resolves, so the button always does something. If a
higher-priority candidate resolves but throws on launch, the loop continues to the next.

## Testing

- Unit (TDD, primary target): `powerSettingsCandidates` — asserts exact order and content
  (Onyx action first; app-details last carrying `package:<packageName>`). Pure, no device.
- Device verification: on the BOOX, the button opens the Onyx App Freeze screen (the
  `APP_FREEZE_MANAGEMENT` action was already confirmed launchable during investigation).
- No Compose UI test for this category: it is Android-only and the `desktopTest` harness
  does not render it (known constraint).

## Files touched

- `composeApp/src/commonMain/.../SettingsUiModels.kt` — platform state, actions, category
  gating.
- `core/src/commonMain/.../DayViewController.kt` — `SettingsCategory` enum gains `SYSTEM`.
- `composeApp/src/commonMain/.../DayViewSettingsScreen.kt` — detail branch + category
  title/description/summary.
- `composeApp/src/commonMain/.../SystemSettingsScreen.kt` — new composable.
- `composeApp/src/commonMain/.../App.kt` — new `DayViewApp` param + wiring into
  platform state / actions.
- `composeApp/src/commonMain/composeResources/.../strings` — fr + en strings.
- `composeApp/src/androidMain/.../PowerSettings.kt` — candidates builder + launcher (new).
- `composeApp/src/androidMain/.../MainActivity.kt` — pass `onOpenPowerSettings`.
- `composeApp/src/androidUnitTest/.../PowerSettingsTest.kt` — candidate-builder test (new).
