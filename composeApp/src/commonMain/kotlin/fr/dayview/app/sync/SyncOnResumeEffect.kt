package fr.dayview.app.sync

import androidx.compose.runtime.Composable

/**
 * Triggers [onResume] whenever the app returns to the foreground, so a sync
 * pass runs shortly after the user comes back to the app.
 *
 * Platform-specific because it is wired to the platform lifecycle. On Android
 * it observes `ON_RESUME`; desktop has no safe lifecycle resume hook
 * (subscribing to the desktop `LocalLifecycleOwner`'s `eventFlow` stalls the
 * Compose frame clock — see `RefreshClockOnResumeEffect.desktop.kt`), so it
 * instead fires periodically.
 */
@Composable
expect fun SyncOnResumeEffect(onResume: () -> Unit)
