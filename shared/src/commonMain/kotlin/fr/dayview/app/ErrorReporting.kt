package fr.dayview.app

/**
 * The single path for a one-off failure: log it for debugging and surface a transient
 * toast. Use in place of a silent `runCatching { … }.getOrDefault(…)` at action sites.
 * Persistent conditions (permission off, sync failed) keep their state/banner path.
 */
internal fun AppEventBus.reportTransient(area: String, error: Throwable, toast: ToastKind) {
    logError(area, error.message ?: error.toString(), error)
    post(AppEvent.Toast(toast))
}
