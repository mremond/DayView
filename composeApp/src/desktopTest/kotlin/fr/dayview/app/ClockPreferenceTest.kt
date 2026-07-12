package fr.dayview.app

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockPreferenceTest {
    @Test
    fun us_locale_is_12_hour() {
        assertEquals(false, jvmUses24HourClock(Locale.US))
    }

    @Test
    fun france_locale_is_24_hour() {
        assertEquals(true, jvmUses24HourClock(Locale.FRANCE))
    }
}
