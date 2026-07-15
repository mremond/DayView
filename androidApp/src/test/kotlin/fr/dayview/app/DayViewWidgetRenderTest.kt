package fr.dayview.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.TextView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DayViewWidgetRenderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun renderDrawsThePassedSnapshotOntoPlacedWidgets() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = shadowOf(manager)
        val widgetId = shadowManager.createWidget(DayViewWidget::class.java, R.layout.dayview_widget)

        DayViewWidget.render(
            context,
            DayPreferencesSnapshot(goalTitle = "Ship the widget fix", pomodoroEnd = null),
        )

        val goal = shadowManager.getViewFor(widgetId).findViewById<TextView>(R.id.widget_goal)
        assertEquals(View.VISIBLE, goal.visibility)
        assertTrue(goal.text.toString().contains("Ship the widget fix"))
    }
}
