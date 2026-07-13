# Hero Quote Source-Reveal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shorten the attributed hero quote to one line ("You must write." / "Tu dois écrire.") and reveal its source ("Rules for Writers ― Robert A. Heinlein", localized) on hover (desktop) or tap (Android).

**Architecture:** Add parallel index-aligned `<string-array>` source resources (empty string = no source). Extract the hero quote into a small `HeroQuote` composable that renders a plain `Text` when the source is blank, and otherwise wraps the quote so a mouse hover or a tap toggles a dim source line shown beneath it.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Compose resources (`stringArrayResource` / `getStringArray`), kotlin.test.

## Global Constraints

- ktlint is enforced — run `./gradlew ktlintCheck` (or `ktlintFormat`) before every commit; commits must be free of ktlint errors.
- Full pre-commit gate: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` must pass with no errors or stderr.
- JDK 21 toolchain (already configured by the build).
- Commit messages in English. **Never** add a "Generated with Claude Code" footer, a `Co-Authored-By: Claude` trailer, or any reference to Claude / Anthropic / an AI assistant. Describe the change only; no test-plan or verification section.
- **Do not** reference internal working documents (this plan, the design spec, anything under `docs/superpowers/`) in commit messages.
- Attribution glyph is a horizontal bar `―` (U+2015), matching the current string.
- Source text is localized (EN and FR).
- EN and FR quote arrays and their source arrays stay index-aligned and equal in size.

---

## File Structure

- `composeApp/src/commonMain/composeResources/values/strings.xml` — English hero arrays; shorten the Heinlein quote, add four `_sources` arrays.
- `composeApp/src/commonMain/composeResources/values-fr/strings.xml` — French counterparts, index-aligned.
- `composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt` — extend to assert each source array matches its quote array's size in both locales.
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` — add a private `HeroQuote` composable and call it from `SidePanel` in place of the inline `Text`.

---

## Task 1: Source resources + shortened quote (data + parity test)

**Files:**
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml:34-47`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml:33-46`

**Interfaces:**
- Produces: four new string-array resources — `today_hero_not_started_sources`, `today_hero_finished_sources`, `today_hero_ending_sources`, `today_hero_ongoing_sources` — each index-aligned with its quote array. Accessed in Kotlin as `Res.array.today_hero_<slot>_sources` (type `StringArrayResource`), resolving to `List<String>`.

- [ ] **Step 1: Rewrite the parity test to also cover source arrays**

Replace the entire body of `HeroQuotesArrayParityTest.kt` with:

```kotlin
package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_ending_sources
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_finished_sources
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_not_started_sources
import fr.dayview.app.generated.resources.today_hero_ongoing
import fr.dayview.app.generated.resources.today_hero_ongoing_sources
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.getStringArray
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every hero pool must be non-empty and its English and French quote arrays must have
 * the same number of items, so each quote stays paired with its translation by index.
 * Each quote array also has a source array of the same size (empty string = no source),
 * so every quote has an index-aligned source slot in both locales.
 */
class HeroQuotesArrayParityTest {
    private val heroPools: List<Pair<StringArrayResource, StringArrayResource>> = listOf(
        Res.array.today_hero_not_started to Res.array.today_hero_not_started_sources,
        Res.array.today_hero_ending to Res.array.today_hero_ending_sources,
        Res.array.today_hero_finished to Res.array.today_hero_finished_sources,
        Res.array.today_hero_ongoing to Res.array.today_hero_ongoing_sources,
    )

    @Test
    fun englishAndFrenchHeroArraysHaveMatchingSizes() = runTest {
        val previous = Locale.getDefault()
        try {
            for ((quotes, sources) in heroPools) {
                Locale.setDefault(Locale.ENGLISH)
                val englishQuotes = getStringArray(quotes)
                val englishSources = getStringArray(sources)
                Locale.setDefault(Locale.FRENCH)
                val frenchQuotes = getStringArray(quotes)
                val frenchSources = getStringArray(sources)
                assertTrue(englishQuotes.isNotEmpty(), "hero array is empty")
                assertEquals(englishQuotes.size, frenchQuotes.size, "EN/FR hero array size mismatch")
                assertEquals(englishQuotes.size, englishSources.size, "EN quote/source size mismatch")
                assertEquals(frenchQuotes.size, frenchSources.size, "FR quote/source size mismatch")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :composeApp:desktopTest --tests fr.dayview.app.HeroQuotesArrayParityTest`
Expected: FAIL — unresolved reference `today_hero_*_sources` (the resources don't exist yet).

- [ ] **Step 3: Shorten the English Heinlein quote and add the English source arrays**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, replace the hero block (lines 34-47) with:

```xml
    <!-- Today screen — wide hero (two lines) -->
    <string-array name="today_hero_not_started">
        <item>Your day hasn’t started yet.\nThe circle is still whole.</item>
    </string-array>
    <string-array name="today_hero_not_started_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_finished">
        <item>Your planned time is up.\nYou can let the day go.</item>
    </string-array>
    <string-array name="today_hero_finished_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_ending">
        <item>The day is drawing to a close.\nChoose one essential thing.</item>
    </string-array>
    <string-array name="today_hero_ending_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_ongoing">
        <item>See the time.\nStay the course, no pressure.</item>
        <item>You must write.</item>
    </string-array>
    <string-array name="today_hero_ongoing_sources">
        <item></item>
        <item>Rules for Writers ― Robert A. Heinlein</item>
    </string-array>
```

- [ ] **Step 4: Shorten the French Heinlein quote and add the French source arrays**

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, replace the hero block (lines 33-46) with:

```xml
    <!-- Today screen — wide hero (two lines) -->
    <string-array name="today_hero_not_started">
        <item>Votre journée n’a pas commencé.\nLe cercle est encore intact.</item>
    </string-array>
    <string-array name="today_hero_not_started_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_finished">
        <item>Le temps prévu est écoulé.\nVous pouvez relâcher la journée.</item>
    </string-array>
    <string-array name="today_hero_finished_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_ending">
        <item>La journée touche à sa fin.\nChoisissez une seule chose essentielle.</item>
    </string-array>
    <string-array name="today_hero_ending_sources">
        <item></item>
    </string-array>
    <string-array name="today_hero_ongoing">
        <item>Voyez le temps.\nGardez le cap, sans pression.</item>
        <item>Tu dois écrire.</item>
    </string-array>
    <string-array name="today_hero_ongoing_sources">
        <item></item>
        <item>Règles pour les écrivains ― Robert A. Heinlein</item>
    </string-array>
```

Note: the own-voice items are copied verbatim from the current file — only the second `today_hero_ongoing` item changes, and the `_sources` arrays are new. Preserve the existing curly apostrophes (`’`).

- [ ] **Step 5: Run the parity test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests fr.dayview.app.HeroQuotesArrayParityTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt
git commit -m "Shorten attributed hero quote and add source string-arrays"
```

---

## Task 2: HeroQuote composable with hover/tap source reveal

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (imports; `SidePanel` call site at lines 1443-1457; add private `HeroQuote` composable)

**Interfaces:**
- Consumes: `Res.array.today_hero_*` and the `Res.array.today_hero_*_sources` from Task 1; existing `heroQuoteIndex(size: Int, seed: Int): Int` and `HeroQuoteSelection.seed(slot: HeroQuoteSlot): Int`.
- Produces: `@Composable private fun HeroQuote(quote: String, source: String, color: Color, modifier: Modifier = Modifier)`.

This task is UI interaction with no unit test (consistent with the repo: `stringResource` text is unresolved under `runComposeUiTest`, so hero rendering is verified by build + manual run, not an assertion). Verification is the pre-commit gate plus a desktop run.

- [ ] **Step 1: Add the source-array imports**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, next to the existing hero imports (lines 154-157), add:

```kotlin
import fr.dayview.app.generated.resources.today_hero_ending_sources
import fr.dayview.app.generated.resources.today_hero_finished_sources
import fr.dayview.app.generated.resources.today_hero_not_started_sources
import fr.dayview.app.generated.resources.today_hero_ongoing_sources
```

Also add the one gesture import that is not yet present:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
```

All other symbols the composable uses are already imported and must NOT be re-added (duplicate imports are a ktlint error): `Column` (line 16), `getValue`/`mutableStateOf`/`remember`/`setValue` (lines 50-53), `Color` (line 62), `PointerEventType`/`PointerType`/`pointerInput` (lines 68-70). `awaitPointerEventScope` is a receiver-scope function (no import needed). Keep imports in alphabetical order per ktlint.

- [ ] **Step 2: Add the `HeroQuote` composable**

Add this private composable immediately above `SidePanel` (just before line 1418, the `@Composable private fun SidePanel(` declaration):

```kotlin
/**
 * Renders a hero quote. When [source] is blank the quote is a plain line. When a source
 * is present, hovering with a mouse (desktop) or tapping (Android) reveals a dim source
 * line beneath the quote; moving the mouse away hides it again.
 */
@Composable
private fun HeroQuote(
    quote: String,
    source: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (source.isBlank()) {
        Text(
            text = quote,
            color = color,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier,
        )
        return
    }
    val colors = LocalDayViewColors.current
    var revealed by remember(quote, source) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .pointerInput(source) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isMouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                if (isMouse) revealed = true
                            PointerEventType.Exit ->
                                if (isMouse) revealed = false
                        }
                    }
                }
            }
            .pointerInput(source) {
                detectTapGestures { revealed = !revealed }
            },
    ) {
        Text(
            text = quote,
            color = color,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
        if (revealed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = source,
                color = colors.muted,
                fontSize = 13.sp,
                letterSpacing = .5.sp,
            )
        }
    }
}
```

- [ ] **Step 3: Wire `SidePanel` to select the source and render `HeroQuote`**

In `SidePanel`, replace the quote block (current lines 1443-1457, from `val heroQuotes = stringArrayResource(` through the closing `)` of the `Text(...)` call) with:

```kotlin
        val heroQuotes = stringArrayResource(
            when (heroSlot) {
                HeroQuoteSlot.NOT_STARTED -> Res.array.today_hero_not_started
                HeroQuoteSlot.FINISHED -> Res.array.today_hero_finished
                HeroQuoteSlot.ENDING -> Res.array.today_hero_ending
                HeroQuoteSlot.ONGOING -> Res.array.today_hero_ongoing
            },
        )
        val heroSources = stringArrayResource(
            when (heroSlot) {
                HeroQuoteSlot.NOT_STARTED -> Res.array.today_hero_not_started_sources
                HeroQuoteSlot.FINISHED -> Res.array.today_hero_finished_sources
                HeroQuoteSlot.ENDING -> Res.array.today_hero_ending_sources
                HeroQuoteSlot.ONGOING -> Res.array.today_hero_ongoing_sources
            },
        )
        val heroIndex = heroQuoteIndex(heroQuotes.size, HeroQuoteSelection.seed(heroSlot))
        HeroQuote(
            quote = heroQuotes[heroIndex],
            source = heroSources.getOrElse(heroIndex) { "" },
            color = colors.cloud,
        )
```

Leave the following `Spacer(Modifier.height(22.dp))` (current line 1458) and everything after it unchanged.

- [ ] **Step 4: Run the full pre-commit gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint errors, all tests pass.

- [ ] **Step 5: Verify the interaction in the running desktop app**

Run: `./gradlew :composeApp:run`
Expected: the ongoing-state hero area shows either "See the time. / Stay the course, no pressure." (no reveal) or the short "You must write." line. For "You must write.", hovering the mouse over it reveals "Rules for Writers ― Robert A. Heinlein" beneath, and moving away hides it. (The shown quote is fixed per launch; relaunch until the ongoing slot lands on the Heinlein item to confirm the reveal.)

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Reveal hero quote source on hover or tap"
```

---

## Self-Review

- **Spec coverage:** short visible quote (Task 1 Steps 3-4); localized source in parallel arrays with empty = none (Task 1); hover on desktop + tap on Android reveal below, plain unchanged when no source (Task 2 Step 2); parity test over the source arrays (Task 1 Step 1). All spec sections map to a task.
- **Placeholder scan:** none — all resource XML, test code, and composable code are complete.
- **Type consistency:** `HeroQuote(quote, source, color, modifier)` defined in Task 2 Step 2 and called with those exact arguments in Step 3; `heroQuoteIndex` / `HeroQuoteSelection.seed` used with their real signatures; `getOrElse(index) { "" }` guards the source lookup so a shorter/absent source array can never throw.
- **Note on unified reveal:** the spec described the desktop affordance as a "tooltip"; because the quote lives in a `Column` (not over the countdown canvas the existing floating tooltips target), both platforms use the same inline dim line beneath the quote — hover drives it on desktop, tap on Android. This satisfies "hover shows source on desktop, tap on Android" more simply than a separate floating-offset tooltip.
