package fr.dayview.app

import org.junit.Test
import kotlin.test.assertEquals

class DayViewWidgetTest {
    @Test
    fun compactLayoutIsUsedForNarrowOrShortWidgets() {
        assertEquals(DayViewWidgetLayout.COMPACT, selectDayViewWidgetLayout(180, 180))
        assertEquals(DayViewWidgetLayout.COMPACT, selectDayViewWidgetLayout(320, 90))
    }

    @Test
    fun mediumLayoutIsTheDefaultAndFitsTheStandardWidgetSize() {
        assertEquals(DayViewWidgetLayout.MEDIUM, selectDayViewWidgetLayout(0, 0))
        assertEquals(DayViewWidgetLayout.MEDIUM, selectDayViewWidgetLayout(250, 120))
    }

    @Test
    fun largeLayoutRequiresEnoughWidthAndHeight() {
        assertEquals(DayViewWidgetLayout.LARGE, selectDayViewWidgetLayout(300, 170))
        assertEquals(DayViewWidgetLayout.MEDIUM, selectDayViewWidgetLayout(299, 170))
        assertEquals(DayViewWidgetLayout.MEDIUM, selectDayViewWidgetLayout(300, 169))
    }
}
