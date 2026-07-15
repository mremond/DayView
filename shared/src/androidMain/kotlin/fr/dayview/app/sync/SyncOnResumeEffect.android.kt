package fr.dayview.app.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.eventFlow
import kotlinx.coroutines.flow.filter

@Composable
actual fun SyncOnResumeEffect(onResume: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.eventFlow.filter { it == Lifecycle.Event.ON_RESUME }.collect { onResume() }
    }
}
