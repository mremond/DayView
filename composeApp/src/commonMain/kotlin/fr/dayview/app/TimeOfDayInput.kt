package fr.dayview.app

/**
 * Directional stepper snap: from a value that is not a multiple of 5, move to the
 * nearest multiple of 5 in [direction] (+1 / −1); from an aligned value, step ±5.
 * Callers clamp the result to their own range.
 */
fun snapToFive(current: Int, direction: Int): Int = when {
    direction > 0 -> (current.floorDiv(5) + 1) * 5
    current % 5 == 0 -> current - 5
    else -> current.floorDiv(5) * 5
}

private val HOUR_MINUTE = Regex("""^(\d{1,2})[:h](\d{2})?$""")
private val BARE_DIGITS = Regex("""^\d{1,4}$""")
private val MERIDIEM_SUFFIX = Regex("""^(.*?)\s*([ap])m?$""")

/**
 * Tolerant wall-clock parsing for the detour time fields. Accepts "14:32",
 * "14h32", "14h", "1432", "932", "14"; in 12-hour mode an optional am/pm
 * suffix ("2:30 pm", "12am"). Returns minutes of day, or null when invalid.
 */
fun parseMinutesOfDay(text: String, use24Hour: Boolean): Int? {
    var body = text.trim().lowercase()
    var meridiem: String? = null
    if (!use24Hour) {
        MERIDIEM_SUFFIX.matchEntire(body)?.let { match ->
            body = match.groupValues[1].trim()
            meridiem = match.groupValues[2]
        }
    }
    val (hourText, minuteText) = splitHourMinute(body) ?: return null
    val hour = hourText.toIntOrNull() ?: return null
    val minute = (if (minuteText.isEmpty()) "0" else minuteText).toIntOrNull() ?: return null
    if (minute > 59) return null
    val resolvedHour = when (meridiem) {
        null -> hour.takeIf { it <= 23 }
        "a" -> hour.takeIf { it in 1..12 }?.mod(12)
        else -> hour.takeIf { it in 1..12 }?.mod(12)?.plus(12)
    } ?: return null
    return resolvedHour * 60 + minute
}

/** "45", "1h30", "1:30", "2h" → whole minutes in 5..720, or null when invalid. */
fun parseDurationMinutes(text: String): Int? {
    val body = text.trim().lowercase()
    if (BARE_DIGITS.matches(body)) return body.toIntOrNull()?.takeIf { it in 5..720 }
    val match = HOUR_MINUTE.matchEntire(body) ?: return null
    val minutes = (if (match.groupValues[2].isEmpty()) "0" else match.groupValues[2]).toInt()
    if (minutes > 59) return null
    return (match.groupValues[1].toInt() * 60 + minutes).takeIf { it in 5..720 }
}

/** Splits "14:32" / "14h32" / "14h" / "1432" / "14" into hour and minute texts. */
private fun splitHourMinute(body: String): Pair<String, String>? {
    HOUR_MINUTE.matchEntire(body)?.let { return it.groupValues[1] to it.groupValues[2] }
    if (!BARE_DIGITS.matches(body)) return null
    return when {
        body.length <= 2 -> body to ""
        else -> body.dropLast(2) to body.takeLast(2)
    }
}
