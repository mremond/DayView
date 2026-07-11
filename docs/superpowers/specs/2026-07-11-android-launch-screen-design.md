# Android Launch Screen Redesign

**Date:** 2026-07-11
**Status:** Approved

## Problem

The Android launch (splash) screen looks unpolished:

1. **Icon clips on Android 12+.** `dayview_splash_icon.xml` draws the ring at radius 48 in a
   108 viewport — larger than the system splash's circular safe zone (~radius 36). The outer
   ring and the amber "now" dot get cut off by the OS mask.
2. **Muddy contrast.** The icon layers a near-black disc (`#101218`) on the near-black splash
   background (`#0B0D12`), which reads as an indistinct dark blob.
3. **Crude wordmark.** `dayview_splash_wordmark.xml` renders "DAYVIEW" as hand-drawn vector
   strokes with uneven letterforms, shown via `windowSplashScreenBrandingImage`.

## Goal

A clean, intentional launch screen that reuses the existing DayView ring identity and matches
the launcher icon, so splash → app → home-screen icon feel like one brand.

## Design

Keep the brand ring (subtle track + mint progress arc + amber "now" dot) on the fixed brand
dark background `#0B0D12`. Fixed dark in both light and dark mode is deliberate — it matches the
launcher icon's background. (Theme-aware background is a possible future enhancement, out of
scope here.)

### Changes (all under `composeApp/src/androidMain/res/`)

1. **`drawable/dayview_splash_icon.xml`** — rewrite to the launcher-icon proportions so it fits
   the splash safe zone:
   - Ring radius 30 (from launcher foreground), centered at (54,54) in the 108 viewport.
   - Remove the muddy near-black inner disc; optional subtle inner face `#12161F` for gentle
     depth.
   - Track: full circle, white at ~16% alpha, stroke width 7, round caps.
   - Progress arc: mint `#78E6BD`, stroke width 7, round caps —
     `M83.87,56.83 A30,30 0,1 1,54,24`.
   - Amber "now" dot `#FFB86B`, radius ~3.2, at `(83.87, 56.83)` — well inside the safe zone.
   - Net effect: visually identical to `ic_dayview_launcher_foreground.xml`, so the marks match.

2. **`values-v31/styles.xml`** — remove the `windowSplashScreenBrandingImage` line so no wordmark
   shows on Android 12+.

3. **`drawable/dayview_splash_wordmark.xml`** — delete (no longer referenced).

4. **`drawable/dayview_splash_legacy.xml`** — used for pre-Android-12 `windowBackground`. It
   already centers `dayview_splash_icon` on the background color; adjust the icon size so the
   redesigned (smaller-radius) icon renders at a comfortable visual size.

### Not changing

- No Kotlin, `AndroidManifest.xml`, or launcher-icon changes.
- Splash background color stays `#0B0D12`.
- Status/navigation bar colors during launch stay as-is.

## Verification

- Build the debug APK and launch on an Android 12+ device/emulator and a pre-12 one: confirm the
  ring is centered, crisp, un-clipped, with no wordmark, and transitions cleanly into the app.
