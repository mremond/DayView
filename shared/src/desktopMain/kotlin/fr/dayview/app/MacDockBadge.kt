package fr.dayview.app

import java.awt.Taskbar

/**
 * Shows a notification badge on the macOS Dock icon while an action is pending
 * (the focus drift reminder waiting for the user to tap "C'est reparti").
 */
class MacDockBadge : AutoCloseable {
    private var lastShown: Boolean? = null

    fun update(visible: Boolean) {
        if (!supported || visible == lastShown) return
        lastShown = visible
        runCatching {
            Taskbar.getTaskbar().setIconBadge(if (visible) BADGE_TEXT else null)
        }
    }

    override fun close() {
        if (lastShown == true) update(false)
        lastShown = null
    }

    private companion object {
        const val BADGE_TEXT = "!"
        val supported: Boolean = run {
            val isMacOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
            isMacOS &&
                Taskbar.isTaskbarSupported() &&
                Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_BADGE_TEXT)
        }
    }
}
