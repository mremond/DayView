package fr.dayview.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidDataStoreTest {
    @Test
    fun migratesLegacySharedPreferences() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE).edit()
            .putInt("start_minutes", 9 * 60)
            .putString("goal_title", "Legacy goal")
            .putLong("goal_start", 123_456_789L)
            .putBoolean("net_time_enabled", true)
            .putString("net_time_calendars", "cal-a\ncal-b")
            .commit()

        val snapshot = androidDayPreferences(context).snapshots.first()

        assertEquals(9 * 60, snapshot.startMinutes)
        assertEquals("Legacy goal", snapshot.goalTitle)
        assertEquals(123_456_789L, snapshot.goalStartMillis)
        assertTrue(snapshot.netTimeSettings.enabled)
        assertEquals(setOf("cal-a", "cal-b"), snapshot.netTimeSettings.includedCalendarIds)
    }
}
