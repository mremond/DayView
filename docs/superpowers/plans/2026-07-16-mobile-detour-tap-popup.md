# Mobile Detour Tap Pop-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A touch tap on a detour arc of the countdown ring shows the anchored detour detail pop-up (category, description, duration), with tap-again / tap-elsewhere to close.

**Architecture:** Reuse the existing detour hover pop-up (`HoverTooltip` + `DetourReadoutDetails`, already rendered from the `hoveredDetour` state) and the existing radius-aware `hitTestDetourBody`. Add one pure state-transition helper `nextHoveredDetourOnTap` (modelled on `nextHoveredBusyOnTap`) and wire it into the touch tap branch of `CountdownCircle`, with tap priority detour → session band → busy arc and one-pop-up-at-a-time exclusivity.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlin.test (desktopTest).

## Global Constraints

- Run the full gate before every commit: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest` (spec: project CLAUDE.md).
- No new user-facing strings (nothing to localize in `values-fr`).
- Mouse hover/click behaviour, the long-press scrub readout, and the detour list panel are out of scope and must not change.
- Commit messages in English, no AI references.

All work happens in the worktree at `/Users/mremond/AIProjects/DayView/.claude/worktrees/focus-time-calculation-57422f` on branch `claude/mobile-detour-info-tap-549e7d`.

---

### Task 1: Pure tap-state helper `nextHoveredDetourOnTap`

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (around lines 1855–1872: after `nextHoveredSessionOnTap`, and the `HoveredDetourBody` declaration)
- Test: `shared/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt`

**Interfaces:**
- Consumes: `DetourBody` (data class in `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt`), `HoveredDetourBody` (currently `private data class HoveredDetourBody(val body: DetourBody, val position: Offset)` in `DayViewTodayScreen.kt`).
- Produces: `internal fun nextHoveredDetourOnTap(current: HoveredDetourBody?, tapped: DetourBody?, position: Offset): HoveredDetourBody?` and `internal data class HoveredDetourBody(val body: DetourBody, val position: Offset)` — Task 2 calls this helper from the gesture handler.

- [ ] **Step 1: Write the failing tests**

Append to `shared/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt` (below the existing `BlockedZoneTapTest` class, same file — it holds the ring tap-rule tests):

```kotlin
private fun detourBody(category: String, colorIndex: Int = 0): DetourBody = DetourBody(
    startAngleDegrees = 0f,
    sweepDegrees = 20f,
    colorIndex = colorIndex,
    category = category,
    description = "",
    start = Instant.fromEpochMilliseconds(0L),
    end = Instant.fromEpochMilliseconds(1_800_000L),
)

class DetourTapTest {
    @Test
    fun tappingEmptyLaneClosesThePopup() {
        val current = HoveredDetourBody(detourBody("Email"), Offset(10f, 10f))
        assertNull(nextHoveredDetourOnTap(current, tapped = null, position = Offset(5f, 5f)))
    }

    @Test
    fun tappingTheShownDetourClosesThePopup() {
        val email = detourBody("Email")
        val current = HoveredDetourBody(email, Offset(10f, 10f))
        assertNull(nextHoveredDetourOnTap(current, tapped = email, position = Offset(12f, 12f)))
    }

    @Test
    fun tappingAnotherDetourSwitchesThePopup() {
        val email = detourBody("Email", colorIndex = 0)
        val chat = detourBody("Chat", colorIndex = 1)
        val current = HoveredDetourBody(email, Offset(10f, 10f))
        val next = nextHoveredDetourOnTap(current, tapped = chat, position = Offset(40f, 40f))
        assertEquals(HoveredDetourBody(chat, Offset(40f, 40f)), next)
    }

    @Test
    fun tappingADetourWithNothingShownOpensThePopup() {
        val chat = detourBody("Chat")
        val next = nextHoveredDetourOnTap(current = null, tapped = chat, position = Offset(40f, 40f))
        assertEquals(HoveredDetourBody(chat, Offset(40f, 40f)), next)
    }
}
```

No new imports are needed: `Offset`, `Test`, `assertEquals`, `assertNull`, and `Instant` are already imported at the top of the file.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.DetourTapTest"`
Expected: FAIL to compile — `nextHoveredDetourOnTap` does not exist and `HoveredDetourBody` is private.

- [ ] **Step 3: Implement the helper**

In `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`, replace the existing declaration

```kotlin
private data class HoveredDetourBody(val body: DetourBody, val position: Offset)
```

with (placed directly after `nextHoveredSessionOnTap`, matching the busy/session pattern):

```kotlin
/**
 * Next [HoveredDetourBody] state after a touch tap on the detour lane. Tapping another detour
 * switches to it, tapping the shown detour or an empty part of the lane closes the pop-up.
 * Kept pure so the tap/close rules are unit-testable without driving a gesture.
 */
internal fun nextHoveredDetourOnTap(
    current: HoveredDetourBody?,
    tapped: DetourBody?,
    position: Offset,
): HoveredDetourBody? = when {
    tapped == null -> null
    current?.body == tapped -> null
    else -> HoveredDetourBody(tapped, position)
}

internal data class HoveredDetourBody(val body: DetourBody, val position: Offset)
```

(The visibility change from `private` to `internal` mirrors `HoveredBusyArc` and `HoveredFocusSession`, which the tests already use.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests "fr.dayview.app.DetourTapTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures, no stderr errors.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt shared/src/desktopTest/kotlin/fr/dayview/app/BlockedZoneTapTest.kt
git commit -m "Add pure tap-state helper for the detour pop-up"
```

---

### Task 2: Wire the detour hit-test into the touch tap gesture

**Files:**
- Modify: `shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt:1143-1165` (the `up != null ->` branch of the touch gesture in `CountdownCircle`)

**Interfaces:**
- Consumes: `nextHoveredDetourOnTap` and `HoveredDetourBody` from Task 1; existing `hitTestDetourBody(x: Float, y: Float, width: Int, height: Int, bodies: List<DetourBody>): DetourBody?` from `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt`; existing mutable states `hoveredDetour`, `hoveredBusy`, `hoveredSession` in `CountdownCircle`.
- Produces: final behaviour; nothing downstream consumes new symbols.

- [ ] **Step 1: Rewire the tap branch**

In `DayViewTodayScreen.kt`, replace the current `up != null ->` branch:

```kotlin
                        up != null -> {
                            // A session band takes priority over a busy arc at the same angle —
                            // bands are the primary affordance. On a band hit, toggle its detail
                            // pop-up and clear any busy tooltip; otherwise fall through to the
                            // busy-label tooltip (and dismiss any session pop-up).
                            val tappedBand = focusSessionBandAtAngle(focusSessionBands, angleOf(up.position))
                            if (tappedBand != null) {
                                hoveredSession = nextHoveredSessionOnTap(
                                    hoveredSession,
                                    tappedBand.record,
                                    engagedTimeForSession(tappedBand.record, focusSessionIntervals),
                                    deepFocusTimeForSession(tappedBand.record, focusPresenceIntervals),
                                    up.position,
                                )
                                hoveredBusy = null
                            } else {
                                // Tap: reveal / switch / dismiss the busy-label tooltip.
                                val tapped = hitTestBusyArc(up.position, size.width, size.height, busyBlockArcs)
                                hoveredBusy = nextHoveredBusyOnTap(hoveredBusy, tapped, up.position)
                                hoveredSession = null
                            }
                            up.consume()
                        }
```

with:

```kotlin
                        up != null -> {
                            // Tap priority mirrors the mouse hover: the detour lane outside the
                            // ring wins first (radius-aware hit-test), then a session band beats
                            // a busy arc at the same angle — bands are the primary affordance.
                            // Each hit toggles its own detail pop-up and clears the other two,
                            // so a single pop-up shows at a time.
                            val tappedDetour =
                                hitTestDetourBody(up.position.x, up.position.y, size.width, size.height, detourBodies)
                            val tappedBand = if (tappedDetour == null) {
                                focusSessionBandAtAngle(focusSessionBands, angleOf(up.position))
                            } else {
                                null
                            }
                            if (tappedDetour != null) {
                                hoveredDetour = nextHoveredDetourOnTap(hoveredDetour, tappedDetour, up.position)
                                hoveredBusy = null
                                hoveredSession = null
                            } else if (tappedBand != null) {
                                hoveredSession = nextHoveredSessionOnTap(
                                    hoveredSession,
                                    tappedBand.record,
                                    engagedTimeForSession(tappedBand.record, focusSessionIntervals),
                                    deepFocusTimeForSession(tappedBand.record, focusPresenceIntervals),
                                    up.position,
                                )
                                hoveredBusy = null
                                hoveredDetour = null
                            } else {
                                // Tap: reveal / switch / dismiss the busy-label tooltip.
                                val tapped = hitTestBusyArc(up.position, size.width, size.height, busyBlockArcs)
                                hoveredBusy = nextHoveredBusyOnTap(hoveredBusy, tapped, up.position)
                                hoveredSession = null
                                hoveredDetour = null
                            }
                            up.consume()
                        }
```

`hitTestDetourBody` is already imported/visible (it is used a few lines above in the mouse handler of the same file), and `detourBodies` is already a key of this `pointerInput` block, so no signature or import changes are needed.

- [ ] **Step 2: Run the shared desktop tests**

Run: `./gradlew :shared:desktopTest`
Expected: BUILD SUCCESSFUL, all tests pass (compile check for the rewired branch plus the existing tap-rule and detour tests).

- [ ] **Step 3: Run the full gate**

Run: `./gradlew ktlintCheck :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures, no stderr errors.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "Show the detour detail pop-up on touch tap"
```
