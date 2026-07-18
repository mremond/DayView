package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A hand-declared detour: a named stretch of time spent off the path. */
data class DetourEpisode(
    val start: Instant,
    val end: Instant,
    val category: String,
    val description: String = "",
) {
    val duration: Duration get() = end - start
}

const val MAX_RECENT_DETOUR_CATEGORIES = 10

/** Single-line, trimmed, length-bounded label. */
fun sanitizeLabel(raw: String, maxLen: Int): String = raw.replace("\n", " ").replace("\r", " ").trim().take(maxLen).trim()

/** Single-line, trimmed, bounded category, commas stripped; every capture and edit feeds through this. */
fun sanitizeDetourCategory(raw: String): String = sanitizeLabel(raw, 60).replace(",", " ").trim()

/** Single-line, trimmed, bounded free-text description; commas are kept. */
fun sanitizeDetourDescription(raw: String): String = sanitizeLabel(raw, 200)

private const val DETOURS_FORMAT_MARKER = "@2"

/**
 * Serialize episodes behind a version marker: one `start,end,category,description` line per
 * episode (epoch millis; category third and comma-stripped so it's a safe fixed field;
 * description last so its own commas survive). Empty list encodes to just the marker.
 */
fun encodeDetours(episodes: List<DetourEpisode>): String {
    val lines = episodes.joinToString("\n") {
        val category = sanitizeDetourCategory(it.category)
        val description = sanitizeDetourDescription(it.description)
        "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},$category,$description"
    }
    return if (lines.isEmpty()) DETOURS_FORMAT_MARKER else "$DETOURS_FORMAT_MARKER\n$lines"
}

/**
 * Inverse of [encodeDetours]. When the first line is the [DETOURS_FORMAT_MARKER], the remaining
 * lines are the current four-field format (category third, description last so its commas
 * survive). Otherwise the whole blob is legacy `start,end,motif` (motif comma-tolerant, last
 * field): the motif becomes the category and the description is empty. Either way, blank,
 * malformed, inverted or category-less lines are skipped.
 */
fun decodeDetours(encoded: String): List<DetourEpisode> {
    if (encoded.isBlank()) return emptyList()
    val lines = encoded.split("\n")
    val marked = lines.firstOrNull() == DETOURS_FORMAT_MARKER
    val bodyLines = if (marked) lines.drop(1) else lines
    return bodyLines.mapNotNull { line ->
        val limit = if (marked) 4 else 3
        val parts = line.split(",", limit = limit)
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        val category = parts.getOrNull(2)?.let(::sanitizeDetourCategory)
        val description = if (marked) parts.getOrNull(3)?.let(::sanitizeDetourDescription).orEmpty() else ""
        if (start != null && end != null && end > start && !category.isNullOrEmpty()) {
            DetourEpisode(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), category, description)
        } else {
            null
        }
    }
}

/** One category per line; categories are single-line by construction. */
fun encodeRecentDetourCategories(categories: List<String>): String = categories.joinToString("\n")

/** Inverse of [encodeRecentDetourCategories]; drops blank lines. */
fun decodeRecentDetourCategories(encoded: String): List<String> = encoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/** Most-recent-first suggestion list: case-insensitive dedupe, capped. */
fun pushRecentDetourCategory(recents: List<String>, category: String): List<String> {
    val clean = sanitizeDetourCategory(category)
    if (clean.isEmpty()) return recents
    return (listOf(clean) + recents.filter { it.lowercase() != clean.lowercase() })
        .take(MAX_RECENT_DETOUR_CATEGORIES)
}

/** Drop a suggestion from the recent list, case-insensitively; blank categories are a no-op. */
fun removeRecentDetourCategory(recents: List<String>, category: String): List<String> {
    val clean = sanitizeDetourCategory(category)
    if (clean.isEmpty()) return recents
    return recents.filter { it.lowercase() != clean.lowercase() }
}

/** Start of the local calendar day containing [now]; mirrors the day-window construction. */
fun startOfLocalDay(
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Instant {
    val local = now.toLocalDateTime(timeZone)
    return LocalDateTime(year = local.year, month = local.month, day = local.day, hour = 0, minute = 0)
        .toInstant(timeZone)
}

/** Same day-key convention as the desktop presence loop: local epoch days. */
fun dayKeyOf(
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long = now.toLocalDateTime(timeZone).date.toEpochDays()

/** A distraction source: episodes grouped by normalized category. */
data class DetourSource(val label: String, val colorIndex: Int, val total: Duration)

private fun sourceKey(category: String): String = sanitizeDetourCategory(category).lowercase()

/**
 * Per-source cumulated durations, heaviest first. Color indices follow the chronological
 * order of each source's earliest episode, so they stay stable across retroactive edits.
 * The label keeps the casing of the source's earliest episode.
 */
fun detourSources(episodes: List<DetourEpisode>): List<DetourSource> {
    val colorBySource = LinkedHashMap<String, Int>()
    val labelBySource = LinkedHashMap<String, String>()
    val totalBySource = LinkedHashMap<String, Duration>()
    for (episode in episodes.sortedBy { it.start }) {
        val key = sourceKey(episode.category)
        if (key.isEmpty()) continue
        colorBySource.getOrPut(key) { colorBySource.size }
        labelBySource.getOrPut(key) { sanitizeDetourCategory(episode.category) }
        totalBySource[key] = (totalBySource[key] ?: Duration.ZERO) + episode.duration
    }
    return colorBySource.keys.map { key ->
        DetourSource(
            label = labelBySource.getValue(key),
            colorIndex = colorBySource.getValue(key),
            total = totalBySource.getValue(key),
        )
    }.sortedByDescending { it.total }
}

/** Total declared detour time (raw durations; episodes are day-scoped by construction). */
fun detoursTotal(episodes: List<DetourEpisode>): Duration = episodes.fold(Duration.ZERO) { acc, episode -> acc + episode.duration }

/**
 * True when the episode's midpoint falls outside the day window — the exact condition under
 * which [detourBodies] drops the episode from the ring. Shared so the ring, the off-window
 * total and the list tag can never disagree.
 */
fun detourMidpointOutsideWindow(
    episode: DetourEpisode,
    windowStart: Instant,
    windowEnd: Instant,
): Boolean {
    val midpoint = episode.start + episode.duration / 2
    return midpoint < windowStart || midpoint > windowEnd
}

/** Summed duration of the episodes the ring drops (midpoint outside the window). */
fun offWindowDetoursTotal(
    windowStart: Instant,
    windowEnd: Instant,
    episodes: List<DetourEpisode>,
): Duration = episodes.fold(Duration.ZERO) { acc, episode ->
    if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) acc + episode.duration else acc
}

/** A detour episode projected on the ring as an arc, ready to draw. */
data class DetourBody(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val colorIndex: Int,
    val category: String,
    val description: String,
    val start: Instant,
    val end: Instant,
)

/** Floor sweep so a very short detour still reads as a visible arc. */
private const val MIN_DETOUR_SWEEP_DEGREES = 3.5f

/** Angular tolerance around a detour arc for hover / scrub picking. */
private const val DETOUR_ANGLE_TOLERANCE_DEGREES = 6f

/**
 * Project episodes to arcs threaded on a lane outside the ring: start/sweep from the episode
 * bounds clipped to the window (same `-90° = window start` convention as [busyBlockArcs]).
 * Very short detours are floored to [MIN_DETOUR_SWEEP_DEGREES], the arc kept centred on the
 * midpoint of its in-window span. Episodes whose midpoint falls outside the window are dropped.
 */
fun detourBodies(
    windowStart: Instant,
    windowEnd: Instant,
    episodes: List<DetourEpisode>,
): List<DetourBody> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorBySource = detourSources(episodes).associate { sourceKey(it.label) to it.colorIndex }
    return episodes.sortedBy { it.start }.mapNotNull { episode ->
        val colorIndex = colorBySource[sourceKey(episode.category)] ?: return@mapNotNull null
        if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) return@mapNotNull null
        val clippedStart = episode.start.coerceIn(windowStart, windowEnd)
        val clippedEnd = episode.end.coerceIn(windowStart, windowEnd)
        val fStart = ((clippedStart - windowStart) / total).toFloat()
        val fEnd = ((clippedEnd - windowStart) / total).toFloat()
        val rawSweep = (fEnd - fStart) * 360f
        val sweep = maxOf(rawSweep, MIN_DETOUR_SWEEP_DEGREES)
        // Keep the arc centred on its in-window midpoint when the floor widens it.
        val startAngle = -90f + fStart * 360f - (sweep - rawSweep) / 2f
        DetourBody(
            startAngleDegrees = startAngle,
            sweepDegrees = sweep,
            colorIndex = colorIndex,
            category = sanitizeDetourCategory(episode.category),
            description = sanitizeDetourDescription(episode.description),
            start = episode.start,
            end = episode.end,
        )
    }
}

/**
 * The detour arc under the pointer, or null. Detours ride a lane just outside the ring, so the
 * radial band is the outer region; the angular pick uses arc containment (with a small
 * tolerance so thin arcs stay reachable), the same test as the scrub readout.
 */
fun hitTestDetourBody(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    bodies: List<DetourBody>,
): DetourBody? {
    val dx = x - width / 2f
    val dy = y - height / 2f
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.90f..1.06f) return null
    val angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
    return detourBodyAtAngle(bodies, angle)
}

/**
 * The detour arc containing [angleDegrees] (or nearest within tolerance), radius-independent —
 * for the touch scrub and, via [hitTestDetourBody], the mouse hover. Null if none is close.
 */
fun detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody? = bodies
    .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    ?.takeIf {
        angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= DETOUR_ANGLE_TOLERANCE_DEGREES
    }

/**
 * Minutes-of-day for the "ends now" default start shown in the quick-capture dialog:
 * a detour that ends at [now] and lasts [durationMinutes]. Clamped to a valid time of
 * day; the day-window clamp stays in [DayViewController.addDetour].
 */
fun detourDefaultStartMinutes(
    now: Instant,
    durationMinutes: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Int {
    val local = now.toLocalDateTime(timeZone)
    val nowMinutes = local.hour * 60 + local.minute
    return (nowMinutes - durationMinutes.coerceIn(1, 12 * 60)).coerceIn(0, 23 * 60 + 59)
}

/** How far back the retroactive default start may reach when no boundary is nearer. */
val DETOUR_ANCHOR_MAX_LOOKBACK: Duration = 120.minutes

/** Longest span an open detour may record; a longer one was forgotten, not lived. */
val OPEN_DETOUR_MAX_SPAN: Duration = 4.hours

/**
 * Default start for a retroactive detour: the last boundary before [now] — the end of the most
 * recent detour or focus session — clamped so it is never older than [maxLookback] nor earlier
 * than [windowStart], and never later than [now].
 *
 * A duration is a guess; a boundary is knowledge. The clamps keep the proposal plausible on a
 * day that started hours ago with nothing declared since.
 */
fun detourAnchorStart(
    now: Instant,
    detours: List<DetourEpisode>,
    focusSessions: List<FocusSessionRecord>,
    windowStart: Instant,
    maxLookback: Duration = DETOUR_ANCHOR_MAX_LOOKBACK,
): Instant {
    val lastDetourEnd = detours.maxOfOrNull { it.end }
    val lastFocusEnd = focusSessions.maxOfOrNull { it.end }
    val floor = maxOf(now - maxLookback, windowStart)
    val boundary = listOfNotNull(lastDetourEnd, lastFocusEnd).maxOrNull()
    return maxOf(boundary ?: floor, floor).coerceAtMost(now)
}

/** Build an episode on the same local day as [dayReference], for the list editor. */
fun detourEpisodeAt(
    dayReference: Instant,
    startMinutesOfDay: Int,
    durationMinutes: Int,
    category: String,
    description: String = "",
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): DetourEpisode {
    val local = dayReference.toLocalDateTime(timeZone)
    val safeMinutes = startMinutesOfDay.coerceIn(0, 23 * 60 + 59)
    val start = LocalDateTime(
        year = local.year,
        month = local.month,
        day = local.day,
        hour = safeMinutes / 60,
        minute = safeMinutes % 60,
    ).toInstant(timeZone)
    return DetourEpisode(
        start = start,
        end = start + durationMinutes.coerceIn(1, 12 * 60).minutes,
        category = sanitizeDetourCategory(category),
        description = sanitizeDetourDescription(description),
    )
}
