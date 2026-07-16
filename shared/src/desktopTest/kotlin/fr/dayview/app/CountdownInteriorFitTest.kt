@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package fr.dayview.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The countdown interior budget assumes the centered text column fits within
 * a 0.64-diameter vertical chord of the ring. If rendered rows are taller than
 * the budget's estimates, the stack silently spills over the ring (the Détours
 * line overlapping the bottom arc). This renders the fullest interior — seconds,
 * net + busy, strict focus + engaged, détours — and holds the layout to that
 * contract at a mid-size dial, where every row is expected to survive the cull.
 */
class CountdownInteriorFitTest {
    @Test
    fun fullInteriorStackStaysWithinTheRingChord() = runComposeUiTest {
        val circle = 250
        setContent {
            DayViewTheme {
                Box(Modifier.requiredSize(circle.dp)) {
                    CountdownCircle(
                        progress = DayProgress(
                            remaining = 7.hours + 20.minutes,
                            remainingRatio = 0.7f,
                            elapsedRatio = 0.3f,
                            startHour = 8,
                            startMinute = 0,
                            endHour = 18,
                            endMinute = 0,
                            hasStarted = true,
                            isFinished = false,
                        ),
                        showSeconds = true,
                        netTime = NetTime(
                            netDay = 7.hours,
                            netRemaining = 4.hours + 50.minutes,
                            busyRemaining = 2.hours + 30.minutes,
                        ),
                        focusedToday = 24.minutes,
                        sessionFocusedToday = 35.minutes,
                        detoursTotal = 3.hours + 35.minutes,
                    )
                }
            }
        }
        waitForIdle()

        val nodes = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val ring = nodes.first { it.config.getOrNull(SemanticsProperties.TestTag) == DayViewTestTags.Countdown }
            .boundsInRoot
        val texts = nodes.filter { it.config.getOrNull(SemanticsProperties.Text) != null }
        assertTrue(texts.size >= 9, "Expected the full interior stack, got ${texts.size} text rows")

        val chordHalf = ring.height * 0.32f
        val contentTop = texts.minOf { it.boundsInRoot.top }
        val contentBottom = texts.maxOf { it.boundsInRoot.bottom }
        assertTrue(
            contentBottom <= ring.center.y + chordHalf + 1f,
            "Interior overflows the ring: content bottom $contentBottom exceeds chord ${ring.center.y + chordHalf}",
        )
        assertTrue(
            contentTop >= ring.center.y - chordHalf - 1f,
            "Interior overflows the ring: content top $contentTop above chord ${ring.center.y - chordHalf}",
        )
    }
}
