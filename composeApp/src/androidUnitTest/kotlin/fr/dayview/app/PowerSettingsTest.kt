package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PowerSettingsTest {
    @Test
    fun candidatesTryOnyxFreezeFirstThenBatteryOptThenAppDetails() {
        val candidates = powerSettingsCandidates("fr.dayview.app")

        assertEquals(
            listOf(
                PowerSettingsTarget("onyx.settings.action.APP_FREEZE_MANAGEMENT", null),
                PowerSettingsTarget("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null),
                PowerSettingsTarget("android.settings.APPLICATION_DETAILS_SETTINGS", "package:fr.dayview.app"),
            ),
            candidates,
        )
    }
}
