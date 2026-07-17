package fr.dayview.app

import java.awt.Taskbar
import kotlin.time.Instant

/**
 * Bounces the macOS Dock icon once per focus drift reminder (informational
 * attention request). macOS only animates the icon while DayView is not the
 * frontmost application — exactly the situation when focus drifts elsewhere.
 */
class MacDockBouncer(
    private val requestAttention: () -> Unit = ::requestDockAttention,
) {
    private var lastBouncedFor: Instant? = null

    fun update(reminderId: Instant?) {
        if (reminderId == null || reminderId == lastBouncedFor) return
        lastBouncedFor = reminderId
        requestAttention()
    }

    private companion object {
        fun requestDockAttention() {
            if (!supported) return
            runCatching {
                Taskbar.getTaskbar().requestUserAttention(true, false)
            }
        }

        val supported: Boolean = run {
            val isMacOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
            isMacOS &&
                Taskbar.isTaskbarSupported() &&
                Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION)
        }
    }
}
