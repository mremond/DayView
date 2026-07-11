package fr.dayview.app

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * Re-reads the wall clock every time the app returns to the foreground so the
 * visible time is correct the instant the user sees it.
 *
 * The background ticker in [DayViewApp] advances the shown time with a coroutine
 * `delay`, which is backed by a monotonic clock that does not progress while the
 * device is in deep sleep. Without an explicit refresh on resume, the view can
 * therefore keep displaying a stale minute for up to a full refresh interval
 * after the screen wakes. Ticking on each `ON_RESUME` closes that window.
 *
 * Collects [events] forever; launch it in an effect scoped to the composition.
 */
internal suspend fun refreshClockOnResume(
    events: Flow<Lifecycle.Event>,
    now: () -> Long,
    tick: (Long) -> Unit,
) {
    events.filter { it == Lifecycle.Event.ON_RESUME }.collect { tick(now()) }
}
