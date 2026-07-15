package fr.dayview.app

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One closed focus session: its window `[start, end]` (effective end, early stops honoured),
 * the intention that was active when it ran, and how it was closed. Engaged / deep-focus
 * durations are NOT stored — they are derived at render time from the day-wide interval lists.
 */
data class FocusSessionRecord(
    val start: Instant,
    val end: Instant,
    val intention: String,
    val outcome: FocusClosureOutcome,
)

private const val FOCUS_SESSION_RECORDS_MARKER = "@1"

/**
 * Serialize behind a version marker: one `start,end,outcome,intentionB64` line per record
 * (epoch millis; outcome by enum name; intention Base64 so its commas / newlines survive as a
 * safe last field). Empty list encodes to just the marker.
 */
@OptIn(ExperimentalEncodingApi::class)
fun encodeFocusSessionRecords(records: List<FocusSessionRecord>): String {
    val lines = records.joinToString("\n") {
        val intention = Base64.encode(it.intention.encodeToByteArray())
        "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},${it.outcome.name},$intention"
    }
    return if (lines.isEmpty()) FOCUS_SESSION_RECORDS_MARKER else "$FOCUS_SESSION_RECORDS_MARKER\n$lines"
}

/** Inverse of [encodeFocusSessionRecords]; blank / malformed / unknown-outcome lines are skipped. */
@OptIn(ExperimentalEncodingApi::class)
fun decodeFocusSessionRecords(encoded: String): List<FocusSessionRecord> {
    if (encoded.isBlank()) return emptyList()
    val lines = encoded.split("\n")
    val bodyLines = if (lines.firstOrNull() == FOCUS_SESSION_RECORDS_MARKER) lines.drop(1) else lines
    return bodyLines.mapNotNull { line ->
        val parts = line.split(",", limit = 4)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        val outcome = parts.getOrNull(2)?.let { name -> FocusClosureOutcome.entries.firstOrNull { it.name == name } }
        val intention = parts.getOrNull(3)?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }
        if (start != null && end != null && outcome != null && intention != null) {
            FocusSessionRecord(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), intention, outcome)
        } else {
            null
        }
    }
}

/** Engaged time inside this session: the day-wide engaged intervals clipped to its window. */
fun engagedTimeForSession(
    record: FocusSessionRecord,
    sessionIntervals: List<FocusPresenceInterval>,
): Duration = focusedTime(record.start, record.end, sessionIntervals)

/** Deep-focus time inside this session: the day-wide presence intervals clipped to its window. */
fun deepFocusTimeForSession(
    record: FocusSessionRecord,
    presenceIntervals: List<FocusPresenceInterval>,
): Duration = focusedTime(record.start, record.end, presenceIntervals)

/** Angular tolerance around a session band for hover / scrub picking. */
private const val FOCUS_SESSION_ANGLE_TOLERANCE_DEGREES = 4f

/** A closed session projected as a ring band spanning its window (`-90° = window start`). */
data class FocusSessionBand(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val record: FocusSessionRecord,
)

/** Project records to bands over the day window; records fully outside the window are dropped. */
fun focusSessionBands(
    windowStart: Instant,
    windowEnd: Instant,
    records: List<FocusSessionRecord>,
): List<FocusSessionBand> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    return records.sortedBy { it.start }.mapNotNull { record ->
        val start = record.start.coerceIn(windowStart, windowEnd)
        val end = record.end.coerceIn(windowStart, windowEnd)
        if (end <= start) return@mapNotNull null
        val fStart = ((start - windowStart) / total).toFloat()
        val fEnd = ((end - windowStart) / total).toFloat()
        FocusSessionBand(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            record = record,
        )
    }
}

/** The band containing [angleDegrees] (nearest within tolerance), radius-independent. Null if none. */
fun focusSessionBandAtAngle(bands: List<FocusSessionBand>, angleDegrees: Float): FocusSessionBand? = bands
    .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    ?.takeIf {
        angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= FOCUS_SESSION_ANGLE_TOLERANCE_DEGREES
    }
