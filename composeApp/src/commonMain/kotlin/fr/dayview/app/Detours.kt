package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A hand-declared detour: a named stretch of time spent off the path. */
data class DetourEpisode(val start: Instant, val end: Instant, val category: String) {
    val duration: Duration get() = end - start
}

const val MAX_RECENT_DETOUR_CATEGORIES = 10

/** Single-line, trimmed, length-bounded label. */
fun sanitizeLabel(raw: String, maxLen: Int): String = raw.replace("\n", " ").replace("\r", " ").trim().take(maxLen).trim()

/** Single-line, trimmed, bounded category; every capture and edit feeds through this. */
fun sanitizeDetourCategory(raw: String): String = sanitizeLabel(raw, 60)

/** Serialize episodes to one `start,end,category` line each (epoch millis, category last). */
fun encodeDetours(episodes: List<DetourEpisode>): String = episodes.joinToString("\n") {
    "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},${sanitizeDetourCategory(it.category)}"
}

/**
 * Inverse of [encodeDetours]. The category is the third field and the split is bounded, so
 * commas inside categories survive; blank, malformed, inverted or category-less lines are skipped.
 */
fun decodeDetours(encoded: String): List<DetourEpisode> = encoded.split("\n").mapNotNull { line ->
    val parts = line.split(",", limit = 3)
    val start = parts.getOrNull(0)?.toLongOrNull()
    val end = parts.getOrNull(1)?.toLongOrNull()
    val category = parts.getOrNull(2)?.let(::sanitizeDetourCategory)
    if (parts.size == 3 && start != null && end != null && end > start && !category.isNullOrEmpty()) {
        DetourEpisode(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), category)
    } else {
        null
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

/** A detour episode projected on the ring, ready to draw. */
data class DetourBody(
    val angleDegrees: Float,
    val sizeFraction: Float,
    val colorIndex: Int,
    val category: String,
    val start: Instant,
    val end: Instant,
)

private val MIN_BODY_DURATION = 5.minutes
private val MAX_BODY_DURATION = 180.minutes

/**
 * Project episodes to bodies threaded on the ring: angle at the episode midpoint
 * (same `-90° = window start` convention as [busyBlockArcs]), size fraction 0..1 from the
 * duration over [5 min, 3 h] on a square-root scale (steep early, gentle late).
 * Episodes whose midpoint falls outside the window are dropped.
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
        val midpoint = episode.start + episode.duration / 2
        if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
        val linearFraction = ((episode.duration - MIN_BODY_DURATION) / (MAX_BODY_DURATION - MIN_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
        val sizeFraction = sqrt(linearFraction)
        DetourBody(
            angleDegrees = -90f + fraction * 360f,
            sizeFraction = sizeFraction,
            colorIndex = colorIndex,
            category = sanitizeDetourCategory(episode.category),
            start = episode.start,
            end = episode.end,
        )
    }
}

private fun angularDistance(a: Float, b: Float): Float {
    val d = (((a - b) % 360f) + 360f) % 360f
    return minOf(d, 360f - d)
}

/**
 * The body under the pointer, or null. Bodies straddle the ring by weight (light ones
 * just outside, heavy ones inside); the radial band spans that offset orbit, and the
 * angular tolerance grows with the body size so small bodies stay hoverable.
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
    if (radiusFraction !in 0.70f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return bodies
        .minByOrNull { angularDistance(it.angleDegrees, angle) }
        ?.takeIf { angularDistance(it.angleDegrees, angle) <= 7f + 5f * it.sizeFraction }
}

/**
 * The detour body whose angular position is closest to [angleDegrees], within the same
 * size-scaled tolerance as [hitTestDetourBody] but with no radius constraint — for the
 * radius-independent touch scrub. Null if none is close enough.
 */
fun detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody? = bodies
    .minByOrNull { angularDistance(it.angleDegrees, angleDegrees) }
    ?.takeIf { angularDistance(it.angleDegrees, angleDegrees) <= 7f + 5f * it.sizeFraction }

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

/** Build an episode on the same local day as [dayReference], for the list editor. */
fun detourEpisodeAt(
    dayReference: Instant,
    startMinutesOfDay: Int,
    durationMinutes: Int,
    category: String,
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
    )
}
