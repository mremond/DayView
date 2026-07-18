package fr.dayview.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSApplication
import platform.AppKit.NSInformationalRequest

/**
 * Dock affordances for a pending drift reminder. The badge label "!" matches the JVM
 * MacDockBadge; the informational request is the single bounce macOS plays only while
 * DayView is not frontmost — exactly the drift situation.
 */
@OptIn(ExperimentalForeignApi::class)
class NSAppDockAttention : DockAttentionProvider {
    override fun setBadge(visible: Boolean) {
        val tile = NSApplication.sharedApplication.dockTile
        tile.badgeLabel = if (visible) "!" else null
        tile.display()
    }

    override fun bounceOnce() {
        NSApplication.sharedApplication.requestUserAttention(NSInformationalRequest)
    }
}
