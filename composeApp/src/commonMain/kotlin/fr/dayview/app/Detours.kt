package fr.dayview.app

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A hand-declared detour: a named stretch of time spent off the path. */
data class DetourEpisode(val start: Instant, val end: Instant, val motif: String) {
    val duration: Duration get() = end - start
}

const val MAX_RECENT_DETOUR_MOTIFS = 10

/** Single-line, trimmed, bounded motif; every capture and edit feeds through this. */
fun sanitizeDetourMotif(raw: String): String = raw.replace("\n", " ").replace("\r", " ").trim().take(60)

/** Serialize episodes to one `start,end,motif` line each (epoch millis, motif last). */
fun encodeDetours(episodes: List<DetourEpisode>): String = episodes.joinToString("\n") {
    "${it.start.toEpochMilliseconds()},${it.end.toEpochMilliseconds()},${sanitizeDetourMotif(it.motif)}"
}

/**
 * Inverse of [encodeDetours]. The motif is the third field and the split is bounded, so
 * commas inside motifs survive; blank, malformed, inverted or motif-less lines are skipped.
 */
fun decodeDetours(encoded: String): List<DetourEpisode> = encoded.split("\n").mapNotNull { line ->
    val parts = line.split(",", limit = 3)
    val start = parts.getOrNull(0)?.toLongOrNull()
    val end = parts.getOrNull(1)?.toLongOrNull()
    val motif = parts.getOrNull(2)?.let(::sanitizeDetourMotif)
    if (parts.size == 3 && start != null && end != null && end > start && !motif.isNullOrEmpty()) {
        DetourEpisode(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end), motif)
    } else {
        null
    }
}

/** One motif per line; motifs are single-line by construction. */
fun encodeRecentDetourMotifs(motifs: List<String>): String = motifs.joinToString("\n")

/** Inverse of [encodeRecentDetourMotifs]; drops blank lines. */
fun decodeRecentDetourMotifs(encoded: String): List<String> = encoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/** Most-recent-first suggestion list: case-insensitive dedupe, capped. */
fun pushRecentDetourMotif(recents: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty()) return recents
    return (listOf(clean) + recents.filter { it.lowercase() != clean.lowercase() })
        .take(MAX_RECENT_DETOUR_MOTIFS)
}

/** Same day-key convention as the desktop presence loop: local epoch days. */
fun dayKeyOf(
    now: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long = now.toLocalDateTime(timeZone).date.toEpochDays().toLong()

/** A distraction source: episodes grouped by normalized motif. */
data class DetourSource(val label: String, val colorIndex: Int, val total: Duration)

private fun sourceKey(motif: String): String = sanitizeDetourMotif(motif).lowercase()

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
        val key = sourceKey(episode.motif)
        if (key.isEmpty()) continue
        colorBySource.getOrPut(key) { colorBySource.size }
        labelBySource.getOrPut(key) { sanitizeDetourMotif(episode.motif) }
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

/** A detour episode projected on the ring, ready to draw. */
data class DetourBody(
    val angleDegrees: Float,
    val sizeFraction: Float,
    val colorIndex: Int,
    val motif: String,
    val start: Instant,
    val end: Instant,
)

private val MIN_BODY_DURATION = 5.minutes
private val MAX_BODY_DURATION = 60.minutes

/**
 * Project episodes to bodies threaded on the ring: angle at the episode midpoint
 * (same `-90° = window start` convention as [busyArcs]), size fraction 0..1 from the
 * duration clamped to [5 min, 60 min]. Episodes whose midpoint falls outside the
 * window are dropped.
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
        val colorIndex = colorBySource[sourceKey(episode.motif)] ?: return@mapNotNull null
        val midpoint = episode.start + episode.duration / 2
        if (midpoint < windowStart || midpoint > windowEnd) return@mapNotNull null
        val fraction = ((midpoint - windowStart) / total).toFloat()
        val sizeFraction = ((episode.duration - MIN_BODY_DURATION) / (MAX_BODY_DURATION - MIN_BODY_DURATION))
            .toFloat().coerceIn(0f, 1f)
        DetourBody(
            angleDegrees = -90f + fraction * 360f,
            sizeFraction = sizeFraction,
            colorIndex = colorIndex,
            motif = sanitizeDetourMotif(episode.motif),
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
 * The body under the pointer, or null. Bodies are centered on the ring circle; the
 * radial band matches the busy-arc hit test and the angular tolerance grows with the
 * body size so small bodies stay hoverable.
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
    if (radiusFraction !in 0.78f..1.02f) return null
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return bodies
        .minByOrNull { angularDistance(it.angleDegrees, angle) }
        ?.takeIf { angularDistance(it.angleDegrees, angle) <= 7f + 5f * it.sizeFraction }
}

/** Build an episode on the same local day as [dayReference], for the list editor. */
fun detourEpisodeAt(
    dayReference: Instant,
    startMinutesOfDay: Int,
    durationMinutes: Int,
    motif: String,
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
        motif = sanitizeDetourMotif(motif),
    )
}
