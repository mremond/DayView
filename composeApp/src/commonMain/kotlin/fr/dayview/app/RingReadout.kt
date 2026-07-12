package fr.dayview.app

import kotlin.time.Instant

/**
 * Everything the countdown ring shows at one angle, for the touch scrub readout.
 * Overlaps are kept: [time] is always set, plus any layer under the angle.
 */
data class RingReadout(
    val time: Instant,
    val isNow: Boolean,
    val busy: BusyBlockArc?,
    val detour: DetourBody?,
    val focus: Boolean,
)

private const val NOW_TOLERANCE_DEGREES = 3f
private const val BUSY_TOLERANCE_DEGREES = 5f

/**
 * Read every ring layer present at [angleDegrees] (drawArc convention, -90° = window start).
 * [momentAngleDegrees] is null before the day starts / after it ends. Busy and detour use
 * the same tolerances as the mouse hover hit-tests so thin arcs and small bodies stay
 * reachable; focus is a plain containment test.
 */
fun ringReadoutAt(
    angleDegrees: Float,
    windowStart: Instant,
    windowEnd: Instant,
    busyBlockArcs: List<BusyBlockArc>,
    detourBodies: List<DetourBody>,
    focusArcs: List<FocusArc>,
    momentAngleDegrees: Float?,
): RingReadout {
    val busy = busyBlockArcs
        .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
        ?.takeIf {
            angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= BUSY_TOLERANCE_DEGREES
        }
    val detour = detourBodyAtAngle(detourBodies, angleDegrees)
    val focus = focusArcs.any { arcContainsAngle(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    val isNow = momentAngleDegrees != null &&
        angularDistanceToArc(momentAngleDegrees, 0f, angleDegrees) <= NOW_TOLERANCE_DEGREES
    return RingReadout(
        time = angleToInstant(angleDegrees, windowStart, windowEnd),
        isNow = isNow,
        busy = busy,
        detour = detour,
        focus = focus,
    )
}
