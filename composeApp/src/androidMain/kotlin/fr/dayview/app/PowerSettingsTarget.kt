package fr.dayview.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** A candidate OS screen to open, tried in order by [openPowerManagementSettings]. */
internal data class PowerSettingsTarget(val action: String, val data: String? = null)

/**
 * The power/background-management screens to try, best first: Onyx's "App Freeze"
 * manager (the real lever on BOOX e-ink devices), then the AOSP battery-optimization
 * list, then the app's details page (always resolves, universal fallback).
 */
internal fun powerSettingsCandidates(packageName: String): List<PowerSettingsTarget> = listOf(
    PowerSettingsTarget("onyx.settings.action.APP_FREEZE_MANAGEMENT"),
    PowerSettingsTarget(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    PowerSettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName"),
)

/**
 * Opens the first power/background-management screen from [powerSettingsCandidates] that
 * resolves on this device, so the user can exempt DayView from OEM background limits.
 * The app-details fallback always resolves, so this always lands somewhere.
 */
fun openPowerManagementSettings(context: Context) {
    val packageManager = context.packageManager
    for (target in powerSettingsCandidates(context.packageName)) {
        val intent = Intent(target.action).apply {
            target.data?.let { data = Uri.parse(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageManager.resolveActivity(intent, 0) == null) continue
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Resolved but could not launch; fall through to the next candidate.
        }
    }
}
