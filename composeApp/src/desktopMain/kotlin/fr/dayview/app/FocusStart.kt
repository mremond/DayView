package fr.dayview.app

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Computes the pomodoro end time for a focus session started at [now]
 * with the given [durationMinutes], coercing the duration into the valid
 * 5..180 minute range (matching calculatePomodoroProgress).
 */
fun focusStartEnd(now: Instant, durationMinutes: Int): Instant = now + durationMinutes.coerceIn(5, 180).minutes
