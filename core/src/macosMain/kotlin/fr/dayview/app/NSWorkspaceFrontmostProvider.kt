package fr.dayview.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSRunningApplication
import platform.AppKit.NSWorkspace
import platform.AppKit.runningApplications

/**
 * Frontmost app + running-app list via NSWorkspace. The frontmost bundle id and the running
 * apps are public API — no screen-recording / TCC prompt.
 */
@OptIn(ExperimentalForeignApi::class)
class NSWorkspaceFrontmostProvider : FrontmostAppProvider {
    override fun frontmostBundleId(): String? = NSWorkspace.sharedWorkspace.frontmostApplication?.bundleIdentifier

    override fun runningApps(): List<AppRef> = NSWorkspace.sharedWorkspace.runningApplications
        .filterIsInstance<NSRunningApplication>()
        .filter { it.activationPolicy == NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular }
        .mapNotNull { app ->
            val id = app.bundleIdentifier ?: return@mapNotNull null
            AppRef(id, app.localizedName ?: id)
        }
}
