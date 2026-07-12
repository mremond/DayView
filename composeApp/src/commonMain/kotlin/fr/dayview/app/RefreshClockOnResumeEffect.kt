package fr.dayview.app

import androidx.compose.runtime.Composable
import kotlin.time.Instant

/**
 * Ticks [tick] with the current wall clock (via [now]) each time the app returns
 * to the foreground, so the shown time is correct the instant the user sees it
 * after the device wakes from deep sleep.
 *
 * Platform-specific because it is wired to the platform lifecycle. On Android it
 * observes `ON_RESUME`; on desktop it is a no-op — subscribing to the desktop
 * `LocalLifecycleOwner`'s `eventFlow` stalls the Compose frame clock (freezing all
 * recomposition), and the desktop ticker already re-reads `Clock.System` each tick.
 */
@Composable
expect fun RefreshClockOnResumeEffect(now: () -> Instant, tick: (Instant) -> Unit)
