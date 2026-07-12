# Light-mode app icons — design

## Goal

Add a light-mode variant of the DayView icon and ship it on Android. The current
icon is dark (near-black plate, pale mint ring). The light variant is a warm
off-white flat plate with a deeper mint ring and amber marker, reusing the app's
existing light widget palette so the icon stays consistent with what the app
already renders in light contexts.

## Light palette

Anchored on the existing light widget colors in
`composeApp/src/androidMain/res/values/colors.xml` (white widget, `#168866`
mint, `#B76218` amber, `#1416211D` ring track):

| Element                    | Dark (current)                     | Light (new)                         |
| -------------------------- | ---------------------------------- | ----------------------------------- |
| Plate background           | `#0B0D12`→`#202731` radial glow     | flat warm off-white `#F4F1EA`        |
| Inner disc                 | dark disc at 50%                    | removed                              |
| Full-day track             | white `#FFFFFF` @ 10%               | dark `#16211D` @ ~12%                |
| Remaining-time arc gradient| `#B9F7DE`→`#78E6BD`→`#43BE93`        | `#3FBF93`→`#168866`→`#0F6E52`         |
| "Now" marker               | `#FFB86B`                           | `#B76218`                            |

Rationale: the pale mint `#78E6BD` and white track disappear on a light plate, so
both must deepen. The flat plate (no glow, no dark disc) was the chosen Android
treatment — the ring floats on a clean warm-white plate.

## Deliverables

1. **Script** — extend `scripts/generate_icon_svg.py` with a `--theme {dark,light}`
   option. `dark` reproduces today's SVG byte-for-byte (regression-safe default);
   `light` selects the palette above, drops the inner disc, and flips the track to
   dark-on-light. Existing `--background/--surface/--accent/--marker` overrides
   still apply on top of the theme defaults.
2. **Artwork** — generate `artwork/dayview-icon-reference-light.svg`.
3. **macOS** — generate `artwork/dayview-light.icns` via the existing
   `scripts/generate_macos_icon.sh` (already accepts source + output args). This is
   available artwork only: `build.gradle.kts` keeps shipping the dark `.icns` on
   macOS (no auto-switch by system appearance).
4. **Android (ships light)** — replace the launcher artwork with light versions:
   - `dayview_icon_background` color → `#F4F1EA`.
   - `ic_dayview_launcher_foreground.xml` → ring + marker only (deeper mint arc,
     dark faint track, amber marker), no dark inner disc.
   - Round variant follows the same foreground/background.
   - Monochrome layer (`ic_dayview_launcher_monochrome.xml`) is **unchanged** — the
     system tints it for themed icons, so it stays theme-agnostic.
   - Splash: `dayview_splash_background` → `#F4F1EA` and the splash icon updated to
     the light artwork so first launch matches.
   Dark drawables are overwritten (recoverable via git history and `--theme dark`).
5. **README** — document `--theme light` and the light `.icns` command.

## Non-goals

- No auto-switching of the launcher/Dock icon by system light/dark mode (the
  platforms don't support it for the full-color icon; the Android monochrome
  themed-icon layer already adapts and is left as-is).
- No change to the in-app UI theme.
- macOS shipped icon stays dark.

## Verification

- `python3 scripts/generate_icon_svg.py --theme dark` produces a file identical to
  the committed `artwork/dayview-icon-reference.svg` (no regression to the dark
  master).
- `--theme light` renders a legible ring on the warm-white plate at 48px.
- Android: `./gradlew :composeApp:assembleDebug`; inspect the adaptive icon
  preview / launcher shows the light icon with a visible ring and marker.
- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
