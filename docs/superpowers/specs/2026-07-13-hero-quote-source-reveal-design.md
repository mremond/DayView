# Hero quote: short text with reveal-to-source

## Goal

Shorten the attributed hero quote to a single line and move its source to a
reveal affordance. The Robert A. Heinlein entry currently renders as a
four-line block with the attribution baked into the string:

```
Rules for Writers
Rule One: You Must Write
…
― Robert A. Heinlein
```

It becomes the single visible line `You must write.` (`Tu dois écrire.`), with
the source `Rules for Writers ― Robert A. Heinlein` shown only on demand.

## Behavior

- The visible hero quote is a short line with no embedded attribution.
- A quote may optionally carry a **source**. When present, the quote gains a
  reveal affordance:
  - **Desktop:** hovering the quote shows the source in a tooltip, reusing the
    existing tooltip pattern in `DayViewTodayScreen.kt`.
  - **Android:** tapping the quote toggles the source visible/hidden. When
    revealed, the source appears as a small, dimmer line below the quote.
- Quotes with **no source** (the app's own-voice lines, e.g.
  "See the time. Stay the course, no pressure.") render as a plain `Text` with
  no interaction — behavior unchanged.
- The source is localized, consistent with the quotes themselves already being
  localized (EN/FR).

## Data model

Sources live in parallel index-aligned `<string-array>` resources, one per hero
slot, mirroring the existing quote arrays. An **empty string** at an index means
"no source" for that quote.

`composeApp/src/commonMain/composeResources/values/strings.xml` (English):

```xml
<string-array name="today_hero_ongoing">
    <item>See the time.\nStay the course, no pressure.</item>
    <item>You must write.</item>
</string-array>
<string-array name="today_hero_ongoing_sources">
    <item></item>
    <item>Rules for Writers ― Robert A. Heinlein</item>
</string-array>
```

`composeApp/src/commonMain/composeResources/values-fr/strings.xml` (French):

```xml
<string-array name="today_hero_ongoing">
    <item>Voyez le temps.\nGardez le cap, sans pression.</item>
    <item>Tu dois écrire.</item>
</string-array>
<string-array name="today_hero_ongoing_sources">
    <item></item>
    <item>Règles pour les écrivains ― Robert A. Heinlein</item>
</string-array>
```

Only the Heinlein item changes; the own-voice line
("Voyez le temps.\nGardez le cap, sans pression.") is preserved verbatim, as is
its English counterpart.

The other three slots (`not_started`, `finished`, `ending`) each get a matching
`_sources` array whose single item is empty (no attribution).

The `―` is a horizontal bar (U+2015), matching the current attribution glyph.

## Rendering

In the wide-hero composable in
`composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`:

1. Resolve the source array for the active slot alongside the existing quote
   array, using the same `heroQuoteIndex` selection so the quote and its source
   share an index.
2. Look up `source = sources[index]` (guard for array size). Treat blank as
   "no source".
3. If the source is blank, render the quote exactly as today: a plain `Text`.
4. If the source is present, wrap the quote so it participates in the reveal:
   - hover state drives a tooltip on desktop,
   - a tap toggles a `revealed` boolean; when revealed, a small dim `Text` with
     the source is shown below the quote.

The reveal state is local composable state (`remember { mutableStateOf(false) }`),
scoped to the current quote. Hover and tap share the same source string; the
difference is only where it is displayed (floating tooltip vs. inline line).

## Testing

- Extend the existing desktop parity test
  (`HeroQuotesArrayParityTest.kt`) to also load each `_sources` array and assert
  it exists and has the **same size** as its corresponding quote array, in both
  EN and FR. This guarantees every quote has a source slot (possibly empty) and
  that translations stay index-aligned.
- The `heroQuoteIndex` / `HeroQuoteSelection` unit tests
  (`HeroQuotesTest.kt`) are unchanged — selection logic is unaffected.

## Out of scope

- No change to how a quote is selected (still one stable seed per slot per
  process).
- No new sources for the non-attributed lines.
- No shared reveal state across quotes or across app restarts.
