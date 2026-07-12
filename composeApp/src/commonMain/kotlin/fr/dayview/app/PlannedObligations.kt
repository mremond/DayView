package fr.dayview.app

/** The day's must-do obligations, capped so they never crowd out the goal. */
const val MAX_PLANNED_OBLIGATIONS = 3

/** Append a sanitized motif; blank motifs and adds past the cap are ignored. */
fun addPlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty() || current.size >= MAX_PLANNED_OBLIGATIONS) return current
    return current + clean
}

/** Drop every case-insensitive match of [motif]; a blank or missing motif is a no-op. */
fun removePlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeDetourMotif(motif)
    if (clean.isEmpty()) return current
    return current.filter { it.lowercase() != clean.lowercase() }
}

/** One motif per line; motifs are single-line by construction. */
fun encodePlannedObligations(obligations: List<String>): String = obligations.joinToString("\n")

/** Inverse of [encodePlannedObligations]; drops blanks, sanitizes, and caps on decode. */
fun decodePlannedObligations(encoded: String): List<String> = encoded.split("\n")
    .map(::sanitizeDetourMotif)
    .filter { it.isNotEmpty() }
    .take(MAX_PLANNED_OBLIGATIONS)
