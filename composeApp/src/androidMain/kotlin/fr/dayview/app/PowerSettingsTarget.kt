package fr.dayview.app

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
