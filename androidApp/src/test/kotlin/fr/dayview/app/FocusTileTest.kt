package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusTileTest {
    @Test
    fun startsSavedFocusWhenReady() {
        assertEquals(
            FocusTileAction.START_FOCUS,
            focusTileAction(
                state = FocusTileState.IDLE,
                intention = "Écrire la proposition",
                canPostNotifications = true,
            ),
        )
    }

    @Test
    fun opensAppWhenFocusIsAlreadyActive() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(
                state = FocusTileState.ACTIVE,
                intention = "Écrire la proposition",
                canPostNotifications = true,
            ),
        )
    }

    @Test
    fun opensAppWhenConfigurationOrPermissionIsMissing() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(FocusTileState.IDLE, intention = "", canPostNotifications = true),
        )
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(FocusTileState.IDLE, intention = "Écrire", canPostNotifications = false),
        )
    }

    @Test
    fun resumesFocusFromBreak() {
        assertEquals(
            FocusTileAction.START_FOCUS,
            focusTileAction(
                state = FocusTileState.BREAK,
                intention = "Écrire",
                canPostNotifications = true,
            ),
        )
        assertEquals(FocusTileState.BREAK, focusTileState(endMillis = 1_000L, nowMillis = 1_001L))
    }
}
