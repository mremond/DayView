package fr.dayview.app

import android.util.Log

internal actual fun logError(tag: String, message: String, throwable: Throwable?) {
    Log.e("DayView/$tag", message, throwable)
}
