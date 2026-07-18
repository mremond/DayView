package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MacWindowSpaceBehaviorTest {
    @Test
    fun addsAllSpacesAndFullScreenAuxiliaryWhilePreservingCompatibleFlags() {
        val existing = (1L shl 1) or (1L shl 4)

        assertEquals(
            (existing and (1L shl 1).inv()) or (1L shl 0) or (1L shl 8),
            MacWindowSpaceBehavior.withFullScreenVisibility(existing),
        )
    }
}
