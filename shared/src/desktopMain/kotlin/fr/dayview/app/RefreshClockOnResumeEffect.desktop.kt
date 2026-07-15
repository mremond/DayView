package fr.dayview.app

import androidx.compose.runtime.Composable
import kotlin.time.Instant

// No-op on desktop: observing the desktop LifecycleOwner's eventFlow stalls the
// Compose frame clock, and the coroutine ticker already re-reads the wall clock.
@Composable
actual fun RefreshClockOnResumeEffect(now: () -> Instant, tick: (Instant) -> Unit) = Unit
