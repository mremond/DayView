package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.eventFlow
import kotlin.time.Instant

@Composable
actual fun RefreshClockOnResumeEffect(now: () -> Instant, tick: (Instant) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        refreshClockOnResume(events = lifecycle.eventFlow, now = now, tick = tick)
    }
}
