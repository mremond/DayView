# Rotating Hero Quotes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Today-screen hero message into a per-state pool of quotes, picking one at random per state once per app launch and holding it steady for the session.

**Architecture:** Each of the four day-state hero strings becomes a `<string-array>` in the Compose resources (EN + FR, index-aligned). A pure helper maps a per-launch random seed to a stable index into the active state's array; the hero composable reads the array with `stringArrayResource` and shows `array[index]`. State-detection logic is unchanged.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform resources (`stringArrayResource` / `getStringArray`), kotlin.test, kotlinx-coroutines-test.

## Global Constraints

- Run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` — must pass with no errors or stderr.
- ktlint is enforced; run `./gradlew ktlintFormat` to auto-fix style.
- Commit messages describe the change only. Never reference Claude/Anthropic/AI, never add a Co-Authored-By or "Generated with" trailer, no test-plan/verification section, no reference to `docs/superpowers/`.
- New pure logic goes in a focused file under `composeApp/src/commonMain/kotlin/fr/dayview/app/`, tested in `composeApp/src/commonTest/kotlin/fr/dayview/app/`, matching existing patterns (e.g. `ThemeMode.kt` / `ThemeModeTest.kt`).
- Hero arrays are always non-empty and EN/FR are kept index-aligned (a quote and its translation share an index).

---

### Task 1: Pure quote-selection helper

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/HeroQuotes.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/HeroQuotesTest.kt`

**Interfaces:**
- Produces:
  - `enum class HeroQuoteSlot { NOT_STARTED, ENDING, FINISHED, ONGOING }`
  - `fun heroQuoteIndex(size: Int, seed: Int): Int` — returns `0` when `size <= 1`, otherwise a value in `0 until size` (non-negative even for negative seeds).
  - `object HeroQuoteSelection { fun seed(slot: HeroQuoteSlot): Int }` — one random seed per slot, fixed for the process lifetime (re-rolls next launch).

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/commonTest/kotlin/fr/dayview/app/HeroQuotesTest.kt`:

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeroQuotesTest {
    @Test
    fun indexIsZeroForEmptyOrSingleton() {
        assertEquals(0, heroQuoteIndex(size = 0, seed = 12345))
        assertEquals(0, heroQuoteIndex(size = 1, seed = 12345))
        assertEquals(0, heroQuoteIndex(size = 1, seed = -12345))
    }

    @Test
    fun indexStaysWithinBounds() {
        for (seed in listOf(0, 1, 7, 40, -1, -40, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val index = heroQuoteIndex(size = 3, seed = seed)
            assertTrue(index in 0 until 3, "seed=$seed produced out-of-range index=$index")
        }
    }

    @Test
    fun indexIsDeterministicForSameSeedAndSize() {
        assertEquals(heroQuoteIndex(size = 5, seed = 99), heroQuoteIndex(size = 5, seed = 99))
    }

    @Test
    fun selectionSeedIsStableWithinTheSession() {
        assertEquals(
            HeroQuoteSelection.seed(HeroQuoteSlot.ONGOING),
            HeroQuoteSelection.seed(HeroQuoteSlot.ONGOING),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HeroQuotesTest"`
Expected: FAIL — `heroQuoteIndex` / `HeroQuoteSlot` / `HeroQuoteSelection` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `composeApp/src/commonMain/kotlin/fr/dayview/app/HeroQuotes.kt`:

```kotlin
package fr.dayview.app

import kotlin.random.Random

/** The four day-states the hero message reacts to. */
enum class HeroQuoteSlot { NOT_STARTED, ENDING, FINISHED, ONGOING }

/**
 * Maps a per-launch [seed] to a stable index into a quote pool of [size] items.
 * Returns 0 for an empty or single-item pool; otherwise a non-negative index in
 * `0 until size`, so it is safe for any Int seed (including negatives).
 */
fun heroQuoteIndex(size: Int, seed: Int): Int =
    if (size <= 1) 0 else ((seed % size) + size) % size

/**
 * Holds one random seed per slot, generated once for the process lifetime, so the
 * chosen quote stays fixed for the whole session and re-rolls on the next launch.
 */
object HeroQuoteSelection {
    private val seeds: Map<HeroQuoteSlot, Int> =
        HeroQuoteSlot.entries.associateWith { Random.nextInt() }

    fun seed(slot: HeroQuoteSlot): Int = seeds.getValue(slot)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HeroQuotesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/HeroQuotes.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/HeroQuotesTest.kt
git commit -m "Add pure hero-quote selection helper"
```

---

### Task 2: Convert hero strings to string-arrays

**Files:**
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml` (hero block, currently lines 24–28)
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml` (hero block, currently lines 24–28)
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt`

**Interfaces:**
- Produces resource arrays `Res.array.today_hero_not_started`, `Res.array.today_hero_ending`, `Res.array.today_hero_finished`, `Res.array.today_hero_ongoing` (replacing the same-named `Res.string.*`). Each array's item 0 is the current single line.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt`:

```kotlin
package fr.dayview.app

import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.today_hero_ending
import fr.dayview.app.generated.resources.today_hero_finished
import fr.dayview.app.generated.resources.today_hero_not_started
import fr.dayview.app.generated.resources.today_hero_ongoing
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.getStringArray
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every hero pool must be non-empty and its English and French arrays must have the
 * same number of items, so each quote stays paired with its translation by index.
 */
class HeroQuotesArrayParityTest {
    private val heroArrays: List<StringArrayResource> = listOf(
        Res.array.today_hero_not_started,
        Res.array.today_hero_ending,
        Res.array.today_hero_finished,
        Res.array.today_hero_ongoing,
    )

    @Test
    fun englishAndFrenchHeroArraysHaveMatchingSizes() = runTest {
        val previous = Locale.getDefault()
        try {
            for (array in heroArrays) {
                Locale.setDefault(Locale.ENGLISH)
                val english = getStringArray(array)
                Locale.setDefault(Locale.FRENCH)
                val french = getStringArray(array)
                assertTrue(english.isNotEmpty(), "hero array is empty")
                assertEquals(english.size, french.size, "EN/FR hero array size mismatch")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HeroQuotesArrayParityTest"`
Expected: FAIL — `Res.array.today_hero_*` unresolved (they are still `<string>`, not `<string-array>`).

- [ ] **Step 3: Convert the English hero block**

In `composeApp/src/commonMain/composeResources/values/strings.xml`, replace the four hero `<string>` lines under the `<!-- Today screen — wide hero (two lines) -->` comment:

```xml
    <string name="today_hero_not_started">Your day hasn’t started yet.\nThe circle is still whole.</string>
    <string name="today_hero_finished">Your planned time is up.\nYou can let the day go.</string>
    <string name="today_hero_ending">The day is drawing to a close.\nChoose one essential thing.</string>
    <string name="today_hero_ongoing">See the time.\nStay the course, no pressure.</string>
```

with:

```xml
    <string-array name="today_hero_not_started">
        <item>Your day hasn’t started yet.\nThe circle is still whole.</item>
    </string-array>
    <string-array name="today_hero_finished">
        <item>Your planned time is up.\nYou can let the day go.</item>
    </string-array>
    <string-array name="today_hero_ending">
        <item>The day is drawing to a close.\nChoose one essential thing.</item>
    </string-array>
    <string-array name="today_hero_ongoing">
        <item>See the time.\nStay the course, no pressure.</item>
        <item>Rules for Writers\nRule One: You Must Write\n…\n― Robert A. Heinlein</item>
    </string-array>
```

- [ ] **Step 4: Convert the French hero block**

In `composeApp/src/commonMain/composeResources/values-fr/strings.xml`, replace the four hero `<string>` lines under the `<!-- Today screen — wide hero (two lines) -->` comment:

```xml
    <string name="today_hero_not_started">Votre journée n’a pas commencé.\nLe cercle est encore intact.</string>
    <string name="today_hero_finished">Le temps prévu est écoulé.\nVous pouvez relâcher la journée.</string>
    <string name="today_hero_ending">La journée touche à sa fin.\nChoisissez une seule chose essentielle.</string>
    <string name="today_hero_ongoing">Voyez le temps.\nGardez le cap, sans pression.</string>
```

with:

```xml
    <string-array name="today_hero_not_started">
        <item>Votre journée n’a pas commencé.\nLe cercle est encore intact.</item>
    </string-array>
    <string-array name="today_hero_finished">
        <item>Le temps prévu est écoulé.\nVous pouvez relâcher la journée.</item>
    </string-array>
    <string-array name="today_hero_ending">
        <item>La journée touche à sa fin.\nChoisissez une seule chose essentielle.</item>
    </string-array>
    <string-array name="today_hero_ongoing">
        <item>Voyez le temps.\nGardez le cap, sans pression.</item>
        <item>Règles pour les écrivains\nRègle numéro un : il faut écrire.\n…\n― Robert A. Heinlein</item>
    </string-array>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.HeroQuotesArrayParityTest"`
Expected: PASS. (The resource-generation step reruns as part of the build and produces the `Res.array.*` accessors.)

Note: this task removes `Res.string.today_hero_*`. The only consumer is the hero in `DayViewTodayScreen.kt` (Task 3), which is updated next — so the full build will not compile until Task 3 is done. Running the single-test command above still exercises resource generation and the parity assertion. Do NOT run the whole `desktopTest` suite until Task 3 lands.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/HeroQuotesArrayParityTest.kt
git commit -m "Store hero messages as per-state string arrays"
```

---

### Task 3: Wire the hero composable to the quote pools

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
  - Imports around lines 148–151 and 156
  - Hero `Text` at lines 1145–1156

**Interfaces:**
- Consumes: `HeroQuoteSlot`, `heroQuoteIndex(size, seed)`, `HeroQuoteSelection.seed(slot)` (Task 1); `Res.array.today_hero_*` (Task 2).

- [ ] **Step 1: Update the hero-string imports**

In `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, the four imports at lines 148–151 stay valid (the generated accessor keeps the same name under `Res.array`). Add the array-resource composable import next to the existing `stringResource` import (line 156):

```kotlin
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
```

(Keep the existing `import org.jetbrains.compose.resources.stringResource`; add the `stringArrayResource` line above it so imports stay alphabetically ordered for ktlint.)

- [ ] **Step 2: Replace the hero `Text` selection**

Replace the hero `Text(...)` block at lines 1145–1156:

```kotlin
        Text(
            text = when {
                !progress.hasStarted -> stringResource(Res.string.today_hero_not_started)
                progress.isFinished -> stringResource(Res.string.today_hero_finished)
                progress.remainingRatio < .2f -> stringResource(Res.string.today_hero_ending)
                else -> stringResource(Res.string.today_hero_ongoing)
            },
            color = colors.cloud,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
```

with:

```kotlin
        val heroSlot = when {
            !progress.hasStarted -> HeroQuoteSlot.NOT_STARTED
            progress.isFinished -> HeroQuoteSlot.FINISHED
            progress.remainingRatio < .2f -> HeroQuoteSlot.ENDING
            else -> HeroQuoteSlot.ONGOING
        }
        val heroQuotes = stringArrayResource(
            when (heroSlot) {
                HeroQuoteSlot.NOT_STARTED -> Res.array.today_hero_not_started
                HeroQuoteSlot.FINISHED -> Res.array.today_hero_finished
                HeroQuoteSlot.ENDING -> Res.array.today_hero_ending
                HeroQuoteSlot.ONGOING -> Res.array.today_hero_ongoing
            },
        )
        Text(
            text = heroQuotes[heroQuoteIndex(heroQuotes.size, HeroQuoteSelection.seed(heroSlot))],
            color = colors.cloud,
            fontSize = 22.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
```

- [ ] **Step 3: Run ktlint and the full test suites**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS with no errors or stderr. (Confirms the hero compiles against `Res.array.*`, the existing `TodayScreenTest` still renders, and both new tests pass.)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Draw the Today hero from per-state quote pools"
```

---

## Adding quotes later (reference, not a task)

To grow a pool, add one `<item>` to the state's `<string-array>` in
`values/strings.xml` and the matching translation as the same-position `<item>`
in `values-fr/strings.xml`. Keep the two index-aligned; the parity test fails if
the counts diverge. Most quotes go to `today_hero_ongoing`.

## Self-Review

- **Spec coverage:** state-aware pools (Task 2, four arrays) ✓; random once-per-launch stable-per-state (Task 1 `HeroQuoteSelection` + `heroQuoteIndex`, Task 3 wiring) ✓; string-array storage keeping translations per locale (Task 2) ✓; hero-only, compact status untouched (Task 3 leaves lines 383–386) ✓; existing lines seeded as item 0 (Task 2) ✓; parity test via `getStringArray`/`runTest` (Task 2) ✓; pure index unit-tested (Task 1) ✓.
- **Placeholder scan:** none — every step has concrete code/commands.
- **Type consistency:** `HeroQuoteSlot`, `heroQuoteIndex(size, seed)`, `HeroQuoteSelection.seed(slot)`, `Res.array.today_hero_*` used identically across Tasks 1–3.
