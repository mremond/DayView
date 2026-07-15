package fr.dayview.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Semantic, UI-agnostic toast triggers. The UI layer maps each to localized text. */
enum class ToastKind {
    DetourRemoved,
    ObligationRemoved,
    SyncSucceeded,
    SoundPreviewFailed,
    SaveFailed,
}

/** One-shot, app-wide events. Pure data — no Compose, no lambdas, no text. */
sealed interface AppEvent {
    data class Toast(val kind: ToastKind, val arg: String? = null) : AppEvent
}

/**
 * Single entry point for [AppEvent]s. Backed by a buffered [MutableSharedFlow] so
 * [post] never suspends and can be called from any thread or non-coroutine context.
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: Flow<AppEvent> = _events.asSharedFlow()

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }
}
