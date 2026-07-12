package fr.dayview.app

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
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
