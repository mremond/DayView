package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Diameter used when the caller doesn't constrain [MiniRing] via its own modifier. */
private val MiniRingDefaultSize = 28.dp

/**
 * A compact, non-interactive ring for the history grid. Mirrors [CountdownCircle]'s arc
 * families — background track, busy lane, focus arcs, remaining sweep — at small size, fed
 * by the same projections a live day uses. No center text, no pointer input, no animation:
 * a history cell renders a fixed snapshot of a past day.
 */
@Composable
internal fun MiniRing(
    progress: DayProgress,
    busyBlockArcs: List<BusyBlockArc>,
    focusArcs: List<FocusArc>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val accent = when {
        progress.isFinished -> colors.red
        progress.remainingRatio < .2f -> colors.amber
        else -> colors.mint
    }
    Canvas(modifier.size(MiniRingDefaultSize).testTag(DayViewTestTags.MiniRing)) {
        val strokeWidth = size.minDimension * .16f
        val inset = strokeWidth / 2f
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)

        // Background track.
        drawArc(
            color = colors.overlay.copy(alpha = .075f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(strokeWidth, cap = StrokeCap.Round),
        )

        // Focus arcs.
        focusArcs.forEach { arc ->
            drawArc(
                color = colors.mint.copy(alpha = .55f),
                startAngle = arc.startAngleDegrees,
                sweepAngle = arc.sweepDegrees,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth * .5f, cap = StrokeCap.Round),
            )
        }

        // Calendar busy lane, one arc per merged interval, colour keyed by calendar.
        busyBlockArcs.forEach { arc ->
            drawArc(
                color = colors.busy[arc.colorIndex % colors.busy.size],
                startAngle = arc.startAngleDegrees,
                sweepAngle = arc.sweepDegrees,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
            )
        }

        // Remaining sweep, anchored at the same moment angle the main ring uses. No sweep
        // gradient or moment marker here: a history cell is a static snapshot, not a live
        // countdown, so the extra relief those add has nothing to read against.
        if (progress.remainingRatio > 0f) {
            val momentAngle = currentMomentAngleDegrees(progress.remainingRatio)
            drawArc(
                color = accent,
                startAngle = momentAngle,
                sweepAngle = progress.remainingRatio * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}
