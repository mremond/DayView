package fr.dayview.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

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

    @Test
    fun renderShowsACountingDownChronometerWhileASessionIsActive() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = shadowOf(manager)
        val widgetId = shadowManager.createWidget(DayViewWidget::class.java, R.layout.dayview_widget)
        val endMillis = System.currentTimeMillis() + 10 * 60_000L

        DayViewWidget.render(
            context,
            DayPreferencesSnapshot(
                focusIntention = "Écrire les tests",
                pomodoroEnd = Instant.fromEpochMilliseconds(endMillis),
            ),
        )

        val view = shadowManager.getViewFor(widgetId)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_focus).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_focus_intention).visibility)
        val chronometer = view.findViewById<Chronometer>(R.id.widget_focus_countdown)
        // focusEndMillis is set (session still inside its term), so the anchor comes from it and
        // counts down: the base sits ahead of "now", not behind it.
        assertTrue(chronometer.isCountDown)
        assertTrue(chronometer.base > SystemClock.elapsedRealtime())
    }

    @Test
    fun renderFlipsToACountingUpChronometerDuringOvertime() {
        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = shadowOf(manager)
        val widgetId = shadowManager.createWidget(DayViewWidget::class.java, R.layout.dayview_widget)
        val endMillis = System.currentTimeMillis() - 10 * 60_000L

        DayViewWidget.render(
            context,
            DayPreferencesSnapshot(
                focusIntention = "Écrire les tests",
                pomodoroEnd = Instant.fromEpochMilliseconds(endMillis),
            ),
        )

        val view = shadowManager.getViewFor(widgetId)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_focus).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_focus_intention).visibility)
        val chronometer = view.findViewById<Chronometer>(R.id.widget_focus_countdown)
        // The term has passed: focusEndMillis is filtered out (it's no longer > now) so the
        // anchor must come from overtimeSinceMillis instead, and count up from a base already
        // behind "now" — this is the invariant the anchorMillis = focusEnd ?: overtimeSince!!
        // expression relies on the two takeIf filters at the call site to guarantee.
        assertFalse(chronometer.isCountDown)
        assertTrue(chronometer.base < SystemClock.elapsedRealtime())
    }
}
