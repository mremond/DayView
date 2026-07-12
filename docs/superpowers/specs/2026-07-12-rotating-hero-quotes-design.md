# Rotating hero quotes — design

## Problem

The Today screen's side panel shows a two-line "hero" message — the app's
motivational quote area. Today each day-state has exactly one fixed line, so the
same sentence shows on every launch. We want a growing pool of quotes (with
translations) that the hero draws from, so the message varies between launches
while staying calm and non-distracting.

## Scope

- **In scope:** the wide two-line hero in the side panel
  ([DayViewTodayScreen.kt:1145](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt)).
- **Out of scope:** the compact one-line status
  ([DayViewTodayScreen.kt:383](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt))
  keeps its single existing short line, unchanged.

## Decisions

- **State-aware pools.** Quotes stay tied to the four day-states
  (`not_started`, `ending`, `finished`, `ongoing`). Each state has its own pool.
  Most new quotes go to `ongoing`; the edge states may stay single-item.
- **Random, once per launch, stable per state.** A quote is chosen at random per
  state at app start and held for the whole session; it re-rolls next launch.
  It must never flicker on the per-second countdown recomposition.

## Design

### Storage — string arrays in the resource system

Each hero string becomes a `<string-array>`, keeping translations in the same
per-locale files they live in today:

```xml
<!-- values/strings.xml -->
<string-array name="today_hero_ongoing">
    <item>See the time.\nStay the course, no pressure.</item>
    <item>…another ongoing quote…</item>
</string-array>
```
```xml
<!-- values-fr/strings.xml -->
<string-array name="today_hero_ongoing">
    <item>Voyez le temps.\nGardez le cap, sans pression.</item>
    <item>…la traduction correspondante…</item>
</string-array>
```

- Four arrays: `today_hero_not_started`, `today_hero_ending`,
  `today_hero_finished`, `today_hero_ongoing`.
- The current single line becomes item 0 of each array, so nothing is lost.
- Adding a quote = one `<item>` in `values/strings.xml` and its translation as
  the matching `<item>` in `values-fr/strings.xml`.
- Compose reads these with `stringArrayResource`, so per-locale selection keeps
  working automatically. Only the active locale's array is read at runtime.

### Selection — seed per launch, index computed in composition

- At app start, generate one random seed per state, held in a process-lifetime
  session holder (e.g. a top-level `object` whose value initialises once). It
  stays fixed for the session and re-rolls on the next launch.
- The hero composable knows the current state's array size (from
  `stringArrayResource`) and computes `index = heroQuoteIndex(size, seed)`, then
  shows `array[index]`. Because the index depends only on the launch seed and the
  array size, it is stable across the per-second recompositions.
- `heroQuoteIndex(size, seed)` is a pure function: returns `0` when `size <= 1`,
  otherwise a non-negative `seed mod size`. This is the unit-tested unit.

### State detection — unchanged

The `when` on `progress` (not started / finished / `remainingRatio < .2f` /
else) is unchanged. We only swap what each branch returns: from a single
`stringResource` to `array[index]` for that state's pool.

## Trade-offs

- **Parallel arrays are a convention, not enforced.** EN item _i_ pairs with FR
  item _i_. A length mismatch will not crash (only one locale is read at
  runtime), but to keep a quote paired with its translation we keep the arrays
  index-aligned. A test asserts EN and FR arrays have equal length per state, to
  catch a forgotten translation.

## Testing

- `heroQuoteIndex(size, seed)`: returns 0 for `size` 0 and 1; returns a value in
  `0 until size` for larger sizes; is non-negative for negative seeds.
- Array parity: for each of the four hero arrays, the English and French arrays
  have the same number of items. The test resolves each array under
  `Locale.ENGLISH` and `Locale.FRENCH` with `getStringArray(...)` inside
  `runTest` and compares sizes — the same approach as
  `LocalizedStringsTest` (which uses `getString` this way and passes on CI). The
  `stringResource`-in-`runComposeUiTest` gotcha does not apply to `getString` /
  `getStringArray` in `runTest`.

## Not doing (YAGNI)

- No interval/timer rotation, no "quote of the day" determinism, no persistence
  of which quote was shown.
- No user-facing setting to disable or pick quotes.
- No change to the compact status line.
