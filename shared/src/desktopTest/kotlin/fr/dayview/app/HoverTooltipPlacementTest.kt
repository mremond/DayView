package fr.dayview.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoverTooltipPlacementTest {
    private val bounds = IntSize(400, 400)
    private val tooltip = IntSize(120, 60)

    @Test
    fun placesBelowRightOfThePointerWhenThereIsRoom() {
        val offset = hoverTooltipOffset(Offset(50f, 50f), tooltip, bounds)
        assertEquals(IntOffset(64, 64), offset)
    }

    @Test
    fun flipsAboveThePointerNearTheBottomEdge() {
        val offset = hoverTooltipOffset(Offset(50f, 380f), tooltip, bounds)
        assertEquals(IntOffset(64, 380 - 14 - 60), offset)
    }

    @Test
    fun flipsLeftOfThePointerNearTheRightEdge() {
        val offset = hoverTooltipOffset(Offset(380f, 50f), tooltip, bounds)
        assertEquals(IntOffset(380 - 14 - 120, 64), offset)
    }

    @Test
    fun bottomRightCornerFlipsOnBothAxes() {
        val offset = hoverTooltipOffset(Offset(390f, 390f), tooltip, bounds)
        assertEquals(IntOffset(390 - 14 - 120, 390 - 14 - 60), offset)
    }

    @Test
    fun neverLeavesTheBoundsEvenWhenFlippingWouldOverflowTheTop() {
        val tall = IntSize(120, 500)
        val offset = hoverTooltipOffset(Offset(200f, 390f), tall, bounds)
        assertTrue(offset.y >= 0)
        assertTrue(offset.x >= 0)
    }

    @Test
    fun clampsInsideBoundsWhenTheTooltipIsWiderThanTheFreeSpace() {
        val offset = hoverTooltipOffset(Offset(10f, 390f), IntSize(500, 60), bounds)
        assertEquals(0, offset.x)
        assertTrue(offset.y + 60 <= 400)
    }
}
