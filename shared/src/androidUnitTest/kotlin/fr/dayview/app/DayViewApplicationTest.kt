package fr.dayview.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.TextView
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = DayViewApplication::class)
class DayViewApplicationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        DayViewPreferences.resetForTest()
    }

    @Test
    fun unlockingTheDeviceRefreshesPlacedWidgets() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = shadowOf(manager)
        val widgetId = shadowManager.createWidget(DayViewWidget::class.java, R.layout.dayview_widget)

        // A preference that changed while the screen was off, with no app foreground and no
        // system time-change broadcast to redraw the widget on its own.
        DayViewPreferences.setForTest(
            InMemoryDayPreferences(DayPreferencesSnapshot(goalTitle = "Wake refresh works")),
        )

        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()

        val goal = shadowManager.getViewFor(widgetId).findViewById<TextView>(R.id.widget_goal)
        assertTrue(goal.text.toString().contains("Wake refresh works"))
    }
}
