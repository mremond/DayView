package fr.dayview.app

/**
 * Today's must-dos ("Incontournables" in French), capped so they never crowd out the long-term
 * goal. The `plannedObligations` names are retained for stored preferences and sync compatibility.
 */
const val MAX_PLANNED_OBLIGATIONS = 3

/** Append a sanitized motif; blank motifs and adds past the cap (active + [alreadyUsed]) are ignored. */
fun addPlannedObligation(current: List<String>, motif: String, alreadyUsed: Int = 0): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty() || current.size + alreadyUsed >= MAX_PLANNED_OBLIGATIONS) return current
    return current + clean
}

/** Drop every case-insensitive match of [motif]; a blank or missing motif is a no-op. */
fun removePlannedObligation(current: List<String>, motif: String): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty()) return current
    return current.filter { it.lowercase() != clean.lowercase() }
}

/**
 * Move [motif] from [active] to [completed]: drops case-insensitive matches from [active] and
 * appends the matching active entry (original casing preserved) to [completed]. A blank motif, or
 * one absent from [active], is a no-op so the completed tally is never inflated by a phantom
 * completion.
 */
fun markObligationCompleted(
    active: List<String>,
    completed: List<String>,
    motif: String,
): Pair<List<String>, List<String>> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty()) return active to completed
    val matched = active.firstOrNull { it.lowercase() == clean.lowercase() }
    if (matched == null) return active to completed
    val remaining = active.filter { it.lowercase() != clean.lowercase() }
    return remaining to (completed + matched)
}

/** One motif per line; motifs are single-line by construction. */
fun encodePlannedObligations(obligations: List<String>): String = obligations.joinToString("\n")

/** Inverse of [encodePlannedObligations]; drops blanks, sanitizes, and caps on decode. */
fun decodePlannedObligations(encoded: String): List<String> = encoded.split("\n")
    .map { sanitizeLabel(it, 60) }
    .filter { it.isNotEmpty() }
    .take(MAX_PLANNED_OBLIGATIONS)
