package fr.dayview.app

/** Platform error log: android.util.Log on Android, stderr on desktop. */
internal expect fun logError(tag: String, message: String, throwable: Throwable?)
