package fr.dayview.app.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes

// Desktop has no safe lifecycle resume hook (observing the desktop
// LifecycleOwner's eventFlow stalls the Compose frame clock, see
// RefreshClockOnResumeEffect.desktop.kt), so periodically trigger a sync
// instead of reacting to a resume event.
@Composable
actual fun SyncOnResumeEffect(onResume: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(5.minutes)
            onResume()
        }
    }
}
