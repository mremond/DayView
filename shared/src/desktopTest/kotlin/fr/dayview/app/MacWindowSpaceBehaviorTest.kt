package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MacWindowSpaceBehaviorTest {
    @Test
    fun addsOverlayBehaviorsWhilePreservingOnlyCompatibleFlags() {
        val incompatible = (1L shl 1) or (1L shl 7) or (1L shl 9) or (1L shl 16) or (1L shl 17)
        val compatible = 1L shl 4

        assertEquals(
            compatible or (1L shl 0) or (1L shl 8) or (1L shl 18),
            MacWindowSpaceBehavior.withFullScreenVisibility(incompatible or compatible),
        )
    }
}
