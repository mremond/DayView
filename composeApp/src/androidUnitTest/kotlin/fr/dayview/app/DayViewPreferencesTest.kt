package fr.dayview.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class DayViewPreferencesTest {
    @After
    fun tearDown() {
        DayViewPreferences.resetForTest()
    }

    @Test
    fun getReturnsTheInjectedFake() {
        val fake = InMemoryDayPreferences(DayPreferencesSnapshot(focusIntention = "injected"))

        DayViewPreferences.setForTest(fake)

        val context = RuntimeEnvironment.getApplication()
        assertSame(fake, DayViewPreferences.get(context))
        assertEquals(
            "injected",
            runBlocking { DayViewPreferences.get(context).snapshots.first() }.focusIntention,
        )
    }

    @Test
    fun resetForTestDropsTheInjectedInstance() {
        val fake = InMemoryDayPreferences()
        DayViewPreferences.setForTest(fake)
        val context = RuntimeEnvironment.getApplication()
        assertSame(fake, DayViewPreferences.get(context))

        DayViewPreferences.resetForTest()

        // With the cache cleared, get() rebuilds the real instance instead of the leftover fake.
        assertNotSame(fake, DayViewPreferences.get(context))
    }
}
