package fr.dayview.app

/** An application eligible to count as on-goal presence. `bundleId` is the match key. */
data class AppRef(val bundleId: String, val displayName: String)

/**
 * Filter a candidate app list to those the user may pick as on-goal, dropping DayView itself:
 * DayView is always neutral, so offering it for selection would be meaningless.
 */
fun selectableApps(apps: List<AppRef>): List<AppRef> = apps.filterNot { it.bundleId == DAYVIEW_BUNDLE_ID }

/** Serialize to one `bundleId\tdisplayName` line per app for preference storage. */
fun encodeAppRefs(apps: Set<AppRef>): String = apps.joinToString("\n") { "${it.bundleId}\t${it.displayName}" }

/** Inverse of [encodeAppRefs]; skips blank / malformed lines and empty bundle ids. */
fun decodeAppRefs(encoded: String): Set<AppRef> = encoded.split("\n")
    .mapNotNull { line ->
        val parts = line.split("\t")
        if (parts.size == 2 && parts[0].isNotBlank()) AppRef(parts[0], parts[1]) else null
    }
    .toSet()
