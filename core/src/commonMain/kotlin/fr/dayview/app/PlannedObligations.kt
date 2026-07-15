package fr.dayview.app

/**
 * Today's must-dos ("Incontournables" in French), capped so they never crowd out the long-term
 * goal. The `plannedObligations` names are retained for stored preferences and sync compatibility.
 */
const val MAX_PLANNED_OBLIGATIONS = 3

/**
 * Append a sanitized motif; blank motifs, adds past the cap (active + [alreadyUsed]), and a
 * case-insensitive duplicate of an existing active entry are all ignored.
 */
fun addPlannedObligation(current: List<String>, motif: String, alreadyUsed: Int = 0): List<String> {
    val clean = sanitizeLabel(motif, 60)
    if (clean.isEmpty() || current.size + alreadyUsed >= MAX_PLANNED_OBLIGATIONS) return current
    if (current.any { matchesPlannedObligation(it, clean) }) return current
    return current + clean
}

/** Drop every case-insensitive match of [motif]; a blank or missing motif is a no-op. */
fun removePlannedObligation(current: List<String>, motif: String): List<String> {
    if (sanitizeLabel(motif, 60).isEmpty()) return current
    return current.filterNot { matchesPlannedObligation(it, motif) }
}

/**
 * True if [entry] is what [removePlannedObligation] would drop for [motif]: same
 * sanitize-then-lowercase-compare predicate, exposed so callers (e.g. undo capture) can
 * find matches without duplicating the matching logic.
 */
fun matchesPlannedObligation(entry: String, motif: String): Boolean {
    val clean = sanitizeLabel(motif, 60)
    return clean.isNotEmpty() && entry.lowercase() == clean.lowercase()
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

/**
 * Rename the active obligation matching [oldMotif] to [newLabel], preserving its position.
 * Returns the new active list, or null when the edit must be rejected: [oldMotif] is absent,
 * [newLabel] is blank after sanitize, the sanitized label is unchanged, or it case-insensitively
 * duplicates another active entry or any completed entry (which would make the string-identity
 * of two items collide). Completed items are never edited here.
 */
fun editPlannedObligation(
    active: List<String>,
    completed: List<String>,
    oldMotif: String,
    newLabel: String,
): List<String>? {
    val index = active.indexOfFirst { matchesPlannedObligation(it, oldMotif) }
    if (index < 0) return null
    val clean = sanitizeLabel(newLabel, 60)
    if (clean.isEmpty()) return null
    if (clean == active[index]) return null
    val target = clean.lowercase()
    val duplicatesAnotherActive = active.withIndex().any { (i, entry) -> i != index && entry.lowercase() == target }
    val duplicatesCompleted = completed.any { it.lowercase() == target }
    if (duplicatesAnotherActive || duplicatesCompleted) return null
    return active.toMutableList().also { it[index] = clean }
}

/** One motif per line; motifs are single-line by construction. */
fun encodePlannedObligations(obligations: List<String>): String = obligations.joinToString("\n")

/** Inverse of [encodePlannedObligations]; drops blanks, sanitizes, and caps on decode. */
fun decodePlannedObligations(encoded: String): List<String> = encoded.split("\n")
    .map { sanitizeLabel(it, 60) }
    .filter { it.isNotEmpty() }
    .take(MAX_PLANNED_OBLIGATIONS)
