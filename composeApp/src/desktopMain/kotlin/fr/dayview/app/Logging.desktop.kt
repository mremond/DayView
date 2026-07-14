package fr.dayview.app

internal actual fun logError(tag: String, message: String, throwable: Throwable?) {
    System.err.println("DayView/$tag: $message")
    throwable?.printStackTrace()
}
